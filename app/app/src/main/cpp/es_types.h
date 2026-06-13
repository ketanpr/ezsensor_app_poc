#pragma once

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <vector>
#include <string>
#include <memory>

/**
 * Core data structures for EzSensor image processing.
 * Reimplemented from reverse-engineered tES_Info and tES_Prcs structures.
 */

struct ES_Info {
    int pid;                     // USB Product ID
    char serialId[64];           // Sensor serial number
    int frameWidth;              // Raw frame width (pixels)
    int frameHeight;             // Raw frame height (pixels)
    int descrambleMode;          // Pixel descramble pattern (from INI)
    int vreset;
    int vresetStepBase;
    int binMode;
    int outMode;
    int gainMode;
    int pattern;
    int invert;
    int imgCut[4];               // L, T, R, B crop margins

    // Derived values
    int effectiveWidth() const {
        return frameWidth - imgCut[0] - imgCut[2];
    }
    int effectiveHeight() const {
        return frameHeight - imgCut[1] - imgCut[3];
    }
};

struct ES_ProcessContext {
    ES_Info info;

    // Calibration data (loaded from files)
    std::vector<int16_t> darkFrame;    // Dark frame reference
    std::vector<int16_t> bpmMap;       // Bad pixel map
    std::vector<int16_t> brightFrame;  // Gain Map (Axx.raw) for Flat-Field Correction
    bool hasCalibration = false;

    // Processing buffers
    std::vector<int16_t> buffer1;
    std::vector<int16_t> buffer2;
};

// Function declarations
namespace es {

// es_descramble.cpp
bool descramble(const ES_Info& info, const uint8_t* rawData, int rawSize,
                int16_t* output, int width, int height);

// es_calibration.cpp
bool loadCalibration(ES_ProcessContext& ctx,
                     const std::string& darkPath, const std::string& bpmPath,
                     const std::string& brightPath);
bool calibrate(ES_ProcessContext& ctx, int16_t* data, int width, int height);

// es_process.cpp
bool process(ES_ProcessContext& ctx, int16_t* data, int width, int height, const std::string& iniPath);

// es_utils.cpp
void toARGBPixels(const int16_t* data, int width, int height,
                  int32_t* output, bool invert);
bool loadRawFile(const std::string& path, std::vector<int16_t>& output,
                 int expectedSize);
void autoContrast(int16_t* data, int width, int height, float cutoff = 1.0f);
void despeckle(int16_t* data, int width, int height, int threshold = 100);
void movingAverage2D(int16_t* data, int width, int height, int kernelSize = 3);
void unsharpMask(int16_t* data, int width, int height, const std::vector<std::vector<float>>& usmMatrices);
void fillBevels(int16_t* data, int width, int height, int cornerSize = 150, int16_t fillValue = 0);

} // namespace es
