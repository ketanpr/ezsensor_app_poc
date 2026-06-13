#include "es_types.h"
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ES_Calibration", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "ES_Calibration", __VA_ARGS__)

namespace es {

/**
 * Load calibration data from disk.
 *
 * - dark.raw: Dark frame reference captured with no X-ray exposure.
 *   Subtracted from every capture to remove fixed-pattern noise.
 * - BPM.raw: Bad Pixel Map identifying dead/hot pixels.
 *   Bad pixels are replaced by interpolation from neighbors.
 */
bool loadCalibration(ES_ProcessContext& ctx,
                     const std::string& darkPath, const std::string& bpmPath,
                     const std::string& brightPath) {

    int pixelCount = ctx.info.frameWidth * ctx.info.frameHeight;

    // Load dark frame
    if (loadRawFile(darkPath, ctx.darkFrame, pixelCount)) {
        LOGI("Dark frame loaded: %d pixels from %s", pixelCount, darkPath.c_str());
    } else {
        LOGW("Dark frame not found: %s", darkPath.c_str());
    }

    // Load bad pixel map
    if (loadRawFile(bpmPath, ctx.bpmMap, pixelCount)) {
        LOGI("BPM loaded: %d pixels from %s", pixelCount, bpmPath.c_str());
    } else {
        LOGW("BPM not found: %s", bpmPath.c_str());
    }

    // Load gain map (Axx_yyyyy.raw)
    if (!brightPath.empty() && loadRawFile(brightPath, ctx.brightFrame, pixelCount)) {
        LOGI("Bright frame (Gain Map) loaded: %d pixels from %s", pixelCount, brightPath.c_str());
    } else {
        LOGW("Bright frame not found or empty path: %s", brightPath.c_str());
    }

    ctx.hasCalibration = !ctx.darkFrame.empty() || !ctx.bpmMap.empty();
    return ctx.hasCalibration;
}

/**
 * Apply calibration corrections to captured data.
 *
 * Step 1: Subtract dark frame (fixed-pattern noise removal)
 * Step 2: Apply Flat-Field Correction (Gain Normalization)
 * Step 3: Correct bad pixels (replace with neighbor average)
 */
bool calibrate(ES_ProcessContext& ctx, int16_t* data, int width, int height) {
    if (!ctx.hasCalibration) return false;

    int pixelCount = width * height;

    // Step 1 & 2: Dark frame subtraction and Flat-Field Correction (FFC)
    if (!ctx.darkFrame.empty() && (int)ctx.darkFrame.size() >= pixelCount) {
        
        bool hasFFC = !ctx.brightFrame.empty() && (int)ctx.brightFrame.size() >= pixelCount;
        float meanGain = 1.0f;
        
        if (hasFFC) {
            // Calculate the target mean from the gain map
            double sum = 0;
            int count = 0;
            for (int i = 0; i < pixelCount; i++) {
                if (ctx.brightFrame[i] > 0) {
                    sum += ctx.brightFrame[i];
                    count++;
                }
            }
            if (count > 0) {
                meanGain = static_cast<float>(sum / count);
            }
            LOGI("FFC Active. Target Mean Gain = %f", meanGain);
        }

        for (int i = 0; i < pixelCount; i++) {
            float raw = static_cast<float>(data[i]);
            float dark = static_cast<float>(ctx.darkFrame[i]);
            
            float corrected = raw - dark;
            
            if (hasFFC) {
                float gain = static_cast<float>(ctx.brightFrame[i]);
                if (gain <= 0.0f) gain = meanGain; // Prevent divide by zero
                corrected = (corrected / gain) * meanGain;
            }
            
            // Re-add dark to sit at correct baseline, clip to bounds
            corrected += dark;
            if (corrected < 0.0f) corrected = 0.0f;
            if (corrected > 4095.0f) corrected = 4095.0f;
            
            data[i] = static_cast<int16_t>(corrected);
        }
        LOGI("Dark subtraction and FFC applied");
    }

    // Step 3: Bad pixel correction
    if (!ctx.bpmMap.empty() && (int)ctx.bpmMap.size() >= pixelCount) {
        int corrected = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int idx = y * width + x;
                if (ctx.bpmMap[idx] != 0) {
                    // Replace bad pixel with average of 4 neighbors
                    int sum = static_cast<int>(data[idx - 1])      // left
                            + static_cast<int>(data[idx + 1])      // right
                            + static_cast<int>(data[idx - width])  // above
                            + static_cast<int>(data[idx + width]); // below
                    data[idx] = static_cast<int16_t>(sum / 4);
                    corrected++;
                }
            }
        }
        if (corrected > 0) {
            LOGI("Corrected %d bad pixels", corrected);
        }
    }

    return true;
}

} // namespace es
