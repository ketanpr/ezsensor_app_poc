package com.ezdent.sensorpoc.sensor

/**
 * JNI bridge to native C++ image processing functions.
 *
 * Reimplements the Vatech ES_* image processing pipeline in 64-bit:
 * - ES_DeScramble  → nativeDeScramble
 * - ES_Calibration → nativeCalibrate
 * - ES_Prcs        → nativeProcess
 * - ES_RAWtoBMP    → nativeToPixels
 */
object SensorImageBridge {

    init {
        System.loadLibrary("ezdent_sensor")
    }

    /**
     * Initialize sensor processing context.
     * @param pid USB Product ID (determines sensor model & parameters)
     * @param frameWidth sensor frame width from config
     * @param frameHeight sensor frame height from config
     * @param descrambleMode descramble pattern index from config
     * @return native handle (pointer) for subsequent calls, or 0 on failure
     */
    external fun nativeInit(
        pid: Int,
        frameWidth: Int,
        frameHeight: Int,
        descrambleMode: Int
    ): Long

    /**
     * Descramble raw sensor data.
     * The sensor outputs pixels in a hardware-specific scrambled order.
     * @param handle native context handle
     * @param rawData raw bytes from USB bulk read
     * @param width frame width
     * @param height frame height
     * @return descrambled 16-bit pixel data, or null on error
     */
    external fun nativeDeScramble(
        handle: Long,
        rawData: ByteArray,
        width: Int,
        height: Int
    ): ShortArray?

    /**
     * Apply calibration corrections.
     * Subtracts dark frame reference and corrects bad pixels.
     * @param handle native context handle
     * @param data descrambled pixel data
     * @param width image width
     * @param height image height
     * @param darkFramePath path to dark.raw calibration file
     * @param bpmPath path to BPM.raw (bad pixel map) file
     * @return calibrated pixel data, or null on error
     */
    external fun nativeCalibrate(
        handle: Long,
        data: ShortArray,
        width: Int,
        height: Int,
        darkFramePath: String,
        bpmPath: String,
        brightFramePath: String
    ): ShortArray?

    /**
     * Process image (noise reduction, auto-contrast, edge enhancement).
     * @param handle native context handle
     * @param data calibrated pixel data
     * @param width image width
     * @param height image height
     * @return processed pixel data, or null on error
     */
    external fun nativeProcess(
        handle: Long,
        data: ShortArray,
        width: Int,
        height: Int,
        iniPath: String
    ): ShortArray?

    /**
     * Convert 16-bit processed data to 32-bit ARGB pixels for Bitmap.
     * @param data processed 16-bit pixel data
     * @param width image width
     * @param height image height
     * @param invert true to invert (negative) the image
     * @return ARGB pixel array suitable for Bitmap.setPixels()
     */
    external fun nativeToPixels(
        data: ShortArray,
        width: Int,
        height: Int,
        invert: Boolean
    ): IntArray?

    /**
     * Release native processing context.
     * @param handle native context handle from nativeInit
     */
    external fun nativeRelease(handle: Long)
}
