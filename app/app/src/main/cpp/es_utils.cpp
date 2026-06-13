#include "es_types.h"
#include <android/log.h>
#include <algorithm>
#include <fstream>
#include <numeric>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ES_Utils", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "ES_Utils", __VA_ARGS__)

namespace es {

/**
 * Convert 16-bit grayscale sensor data to 32-bit ARGB pixels.
 * Maps the 16-bit range to 8-bit grayscale for display.
 */
void toARGBPixels(const int16_t* data, int width, int height,
                  int32_t* output, bool invert) {

    int pixelCount = width * height;

    // Find actual data range for optimal mapping
    int16_t minVal = data[0], maxVal = data[0];
    for (int i = 1; i < pixelCount; i++) {
        if (data[i] < minVal) minVal = data[i];
        if (data[i] > maxVal) maxVal = data[i];
    }

    float range = static_cast<float>(maxVal - minVal);
    if (range < 1.0f) range = 1.0f;

    LOGI("toARGBPixels: range [%d, %d] invert=%d", minVal, maxVal, invert);

    for (int i = 0; i < pixelCount; i++) {
        // Normalize to 0-255
        float normalized = (static_cast<float>(data[i] - minVal) / range) * 255.0f;
        uint8_t gray = static_cast<uint8_t>(std::clamp(normalized, 0.0f, 255.0f));

        if (invert) {
            gray = 255 - gray;
        }

        // Pack as ARGB (fully opaque)
        output[i] = static_cast<int32_t>(
            (0xFF << 24) | (gray << 16) | (gray << 8) | gray
        );
    }
}

/**
 * Load a raw calibration file (16-bit little-endian values).
 */
bool loadRawFile(const std::string& path, std::vector<int16_t>& output, int expectedSize) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        return false;
    }

    auto fileSize = file.tellg();
    file.seekg(0, std::ios::beg);

    int numValues = static_cast<int>(fileSize) / 2;
    output.resize(numValues);

    file.read(reinterpret_cast<char*>(output.data()), numValues * 2);

    if (numValues != expectedSize) {
        LOGW("Raw file size mismatch: got %d values, expected %d", numValues, expectedSize);
    }

    return true;
}

/**
 * Auto-contrast: stretch histogram to use full dynamic range.
 * Clips `cutoff` percent from each end of the histogram.
 */
void autoContrast(int16_t* data, int width, int height, float cutoff) {
    int pixelCount = width * height;

    // Build histogram (12-bit values: 0 to 4095)
    const int BINS = 4096;
    std::vector<int> histogram(BINS, 0);

    for (int i = 0; i < pixelCount; i++) {
        int bin = std::clamp(static_cast<int>(data[i]), 0, BINS - 1);
        histogram[bin]++;
    }

    // Find cutoff points
    int cutoffCount = static_cast<int>(pixelCount * cutoff / 100.0f);
    int lowBin = 0, highBin = BINS - 1;

    int cumSum = 0;
    for (int i = 0; i < BINS; i++) {
        cumSum += histogram[i];
        if (cumSum >= cutoffCount) { lowBin = i; break; }
    }

    cumSum = 0;
    for (int i = BINS - 1; i >= 0; i--) {
        cumSum += histogram[i];
        if (cumSum >= cutoffCount) { highBin = i; break; }
    }

    if (highBin <= lowBin) return;

    // Map to the data range
    float stretch = 4095.0f / static_cast<float>(highBin - lowBin);

    for (int i = 0; i < pixelCount; i++) {
        float val = (static_cast<float>(data[i]) - lowBin) * stretch;
        data[i] = static_cast<int16_t>(std::clamp(val, 0.0f, 4095.0f));
    }

    LOGI("AutoContrast: [%d,%d] → [0,4095] stretch=%.2f", lowBin, highBin, stretch);
}

/**
 * Despeckle filter — removes salt-and-pepper noise.
 * Replaces pixels that differ from their neighbors by more than
 * `threshold` with the median of the 3x3 neighborhood.
 * If threshold < 0, forces median replacement everywhere.
 */
void despeckle(int16_t* data, int width, int height, int threshold) {
    std::vector<int16_t> temp(width * height);
    std::memcpy(temp.data(), data, width * height * sizeof(int16_t));

    int corrected = 0;
    int16_t window[9];

    for (int y = 1; y < height - 1; y++) {
        for (int x = 1; x < width - 1; x++) {
            int idx = y * width + x;
            int16_t center = temp[idx];

            // Collect 3x3 neighborhood
            window[0] = temp[idx - width - 1];
            window[1] = temp[idx - width];
            window[2] = temp[idx - width + 1];
            window[3] = temp[idx - 1];
            window[4] = center;
            window[5] = temp[idx + 1];
            window[6] = temp[idx + width - 1];
            window[7] = temp[idx + width];
            window[8] = temp[idx + width + 1];

            // Calculate median of 9 pixels
            std::sort(window, window + 9);
            int16_t median = window[4];

            // Replace if pixel deviates too much from median, or if forced
            if (threshold < 0 || std::abs(static_cast<int>(center) - static_cast<int>(median)) > threshold) {
                data[idx] = median;
                corrected++;
            }
        }
    }

    if (corrected > 0) {
        LOGI("Despeckle: corrected %d pixels (threshold=%d)", corrected, threshold);
    }
}

/**
 * Fast separable 2D moving average filter for noise reduction (Box Blur).
 * Runs in O(1) time relative to kernelSize by applying horizontal then vertical 1D passes.
 */
void movingAverage2D(int16_t* data, int width, int height, int kernelSize) {
    if (kernelSize <= 1) return;
    int half = kernelSize / 2;
    std::vector<int16_t> temp(width * height);
    
    // Horizontal Pass
    for (int y = 0; y < height; y++) {
        int sum = 0;
        int rowStart = y * width;
        // Init sum for row
        for (int k = -half; k <= half; k++) {
            int x = std::clamp(k, 0, width - 1);
            sum += data[rowStart + x];
        }
        for (int x = 0; x < width; x++) {
            temp[rowStart + x] = static_cast<int16_t>(sum / kernelSize);
            // Sliding window: subtract old left edge, add new right edge
            int left = std::clamp(x - half, 0, width - 1);
            int right = std::clamp(x + half + 1, 0, width - 1);
            sum += data[rowStart + right] - data[rowStart + left];
        }
    }

    // Vertical Pass
    for (int x = 0; x < width; x++) {
        int sum = 0;
        // Init sum for column
        for (int k = -half; k <= half; k++) {
            int y = std::clamp(k, 0, height - 1);
            sum += temp[y * width + x];
        }
        for (int y = 0; y < height; y++) {
            data[y * width + x] = static_cast<int16_t>(sum / kernelSize);
            // Sliding window
            int top = std::clamp(y - half, 0, height - 1);
            int bottom = std::clamp(y + half + 1, 0, height - 1);
            sum += temp[bottom * width + x] - temp[top * width + x];
        }
    }
}

/**
 * Multi-pass Unsharp mask for edge enhancement.
 * Enhances edges using Vatech's dynamic USM matrices, incorporating safe intermediate clipping
 * to prevent high-frequency exponential explosion.
 */
void unsharpMask(int16_t* data, int width, int height, const std::vector<std::vector<float>>& usmMatrices) {
    if (usmMatrices.empty()) return;

    std::vector<int16_t> blurred(width * height);

    for (const auto& usm : usmMatrices) {
        if (usm.size() < 5) continue;

        // Vatech INI Format: [-3, 2, 0, 0, 150, 3, 0.1, 1, 1]
        int radius = std::abs(static_cast<int>(usm[0]));
        if (radius == 0) radius = 1;
        int kernelSize = radius * 2 + 1;

        float rawWeight = usm[4];
        float weight = (rawWeight > 10.0f || rawWeight < -10.0f) ? rawWeight / 100.0f : rawWeight;
        weight = std::clamp(weight, -2.0f, 2.0f); // Prevent math explosion

        // 1. Create blurred copy
        std::memcpy(blurred.data(), data, width * height * sizeof(int16_t));
        movingAverage2D(blurred.data(), width, height, kernelSize);

        // 2. Apply unsharp equation
        for (int i = 0; i < width * height; i++) {
            float orig = static_cast<float>(data[i]);
            float blur = static_cast<float>(blurred[i]);
            float mask = orig - blur;
            float enhanced = orig + mask * weight;

            // CRITICAL: Prevent exponential ring explosion by strictly clipping to 0-4095 between passes!
            data[i] = static_cast<int16_t>(std::clamp(enhanced, 0.0f, 4095.0f));
        }

        LOGI("Unsharp mask pass applied: radius=%d, weight=%.2f", radius, weight);
    }
}

/**
 * Fills the corner bevels with a constant value.
 * Vatech sensors have physical dead zones in the corners (cut corners).
 */
void fillBevels(int16_t* data, int width, int height, int cornerSize, int16_t fillValue) {
    for (int y = 0; y < cornerSize; y++) {
        int x_limit = cornerSize - y;
        // Bottom-Left
        int bl_y = height - 1 - y;
        for (int x = 0; x < x_limit; x++) {
            data[bl_y * width + x] = fillValue;
        }
        // Bottom-Right
        for (int x = width - x_limit; x < width; x++) {
            data[bl_y * width + x] = fillValue;
        }
    }
    LOGI("Fill bevels applied: corner=%d, fill=%d", cornerSize, fillValue);
}

} // namespace es
