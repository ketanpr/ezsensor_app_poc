#include "es_types.h"
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ES_DeScramble", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "ES_DeScramble", __VA_ARGS__)

namespace es {

/**
 * Descramble raw sensor data.
 *
 * Dental X-ray sensors output pixels in a scrambled order due to the
 * readout circuit design. The descramble mode (from EzSensor.ini)
 * determines which permutation pattern to apply.
 *
 * Common patterns observed in dental sensors:
 * - Mode 0: No descramble (identity / already ordered)
 * - Mode 1: Row-interleaved (even rows first, then odd rows)
 * - Mode 2: Block-interleaved (2-line blocks, alternating)
 * - Mode 3: Column-interleaved
 * - Mode 4: 4-line interleaved blocks
 *
 * The raw data is 16-bit little-endian (2 bytes per pixel).
 */
bool descramble(const ES_Info& info, const uint8_t* rawData, int rawSize,
                int16_t* output, int width, int height) {

    int pixelCount = width * height;
    
    // Clear output buffer first (in case of missing packets)
    // Initialize with 4095 (pure white background) so dark subtraction works correctly
    for (int i = 0; i < pixelCount; i++) {
        output[i] = 4095;
    }

    // Convert raw bytes to 16-bit words (little-endian)
    int wordCount = rawSize / 2;
    std::vector<uint16_t> words(wordCount);
    for (int i = 0; i < wordCount; i++) {
        words[i] = static_cast<uint16_t>(
            rawData[i * 2] | (rawData[i * 2 + 1] << 8)
        );
    }

    int mode = info.descrambleMode;
    LOGI("Descramble mode=%d  %dx%d  rawWords=%d", mode, width, height, wordCount);

    int x = 0;
    int y = 0;
    int pixelsWritten = 0;

    for (int i = 0; i < wordCount; i++) {
        uint16_t val = words[i];
        uint16_t marker = val & 0xF000;
        
        if (marker == 0xE000 || marker == 0xA000) {
            // New row marker: lower 12 bits indicate the row number
            y = val & 0x0FFF;
            x = 0;
        } else if (marker == 0xF000) {
            // End of frame or special marker, ignore for now
        } else {
            // Pixel data
            if (x < width && y < height) {
                // DO NOT INVERT YET. Extract the pure 12-bit sensor counts to preserve
                // physical linearity for Flat-Field Calibration (FFC)
                uint16_t pixel = val & 0x0FFF;
                
                if (mode == 3) {
                    // Mode 3 is a known column-interleaved exception
                    int halfCols = width / 2;
                    int actualX = (x < halfCols) ? (x * 2) : ((x - halfCols) * 2 + 1);
                    output[y * width + actualX] = static_cast<int16_t>(pixel);
                } else {
                    // Unified stream logic for Mode 6, Mode 1, Mode 2, etc.
                    output[y * width + x] = static_cast<int16_t>(pixel);
                }
                x++;
                pixelsWritten++;
            }
        }
    }

    LOGI("Descramble complete. Wrote %d pixels.", pixelsWritten);
    return true;
}

} // namespace es
