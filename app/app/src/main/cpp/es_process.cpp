#include "es_types.h"
#include <android/log.h>
#include <algorithm>
#include <fstream>
#include <sstream>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ES_Process", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "ES_Process", __VA_ARGS__)

namespace es {

void parseIniForIP4(const std::string& iniPath, int& despeckleThresh, std::vector<std::vector<float>>& usmMatrices) {
    std::ifstream file(iniPath);
    if (!file.is_open()) return;

    std::string line;
    bool inIP4 = false;
    
    while (std::getline(file, line)) {
        // Trim whitespace
        line.erase(0, line.find_first_not_of(" \t\r\n"));
        line.erase(line.find_last_not_of(" \t\r\n") + 1);
        
        if (line.empty()) continue;
        
        if (line[0] == '[') {
            inIP4 = (line == "[IP4]");
            continue;
        }
        
        if (inIP4) {
            auto pos = line.find('=');
            if (pos != std::string::npos) {
                std::string key = line.substr(0, pos);
                std::string val = line.substr(pos + 1);
                
                if (key == "DespeckleThreshold") {
                    try { despeckleThresh = std::stoi(val); } catch (...) {}
                } else if (key.find("USM") == 0 && key != "USM_NumSteps") {
                    std::stringstream ss(val);
                    std::string token;
                    std::vector<float> matrix;
                    while (std::getline(ss, token, ' ')) {
                        // Trim token
                        token.erase(0, token.find_first_not_of(" \t\r\n"));
                        token.erase(token.find_last_not_of(" \t\r\n") + 1);
                        if (!token.empty()) {
                            try { matrix.push_back(std::stof(token)); } catch (...) {}
                        }
                    }
                    if (!matrix.empty()) usmMatrices.push_back(matrix);
                }
            }
        }
    }
}

/**
 * Image processing pipeline matching Vatech's dynamic ES_Prcs.
 */
bool process(ES_ProcessContext& ctx, int16_t* data, int width, int height, const std::string& iniPath) {
    LOGI("Processing %dx%d image using INI: %s", width, height, iniPath.c_str());

    // Defaults matching Python prototype
    int despeckleThresh = -1;
    std::vector<std::vector<float>> usmMatrices;
    
    parseIniForIP4(iniPath, despeckleThresh, usmMatrices);
    
    // Step 0: Invert the image (4095 - pixel)
    // The sensor outputs high values for no exposure (white) and low values for X-ray absorption (dark).
    // The processing filters expect tooth=bright, so we invert before processing.
    int pixelCount = width * height;
    for (int i = 0; i < pixelCount; i++) {
        int val = 4095 - static_cast<int>(data[i]);
        data[i] = static_cast<int16_t>(std::clamp(val, 0, 4095));
    }
    
    // Step 1: Despeckle (3x3 Median)
    despeckle(data, width, height, despeckleThresh);

    // Step 2: Auto-contrast (Percentile stretch to 0-4095)
    autoContrast(data, width, height, 1.0f);

    // Step 3: Multi-pass Unsharp Mask
    unsharpMask(data, width, height, usmMatrices);

    // Step 4: Fill Bevels (Apply at the very end with 0 to ensure pure black corners)
    fillBevels(data, width, height, 150, 0);

    LOGI("Processing complete");
    return true;
}

} // namespace es
