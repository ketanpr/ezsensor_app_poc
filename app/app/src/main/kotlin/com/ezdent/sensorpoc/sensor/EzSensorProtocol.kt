package com.ezdent.sensorpoc.sensor

import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Implements the full X-ray capture pipeline as a state machine.
 *
 * Pipeline stages:
 * 1. INITIALIZING  — Read sensor config (EzSensor.ini)
 * 2. DARK_FRAME    — Capture dark reference frame (no X-ray exposure)
 * 3. WAITING       — "Push X-Ray button!!!" — waiting for sensor trigger
 * 4. READING       — USB bulk read of raw sensor data
 * 5. PROCESSING    — DeScramble → Calibrate → Process → Bitmap
 * 6. COMPLETE      — Image ready for display/save
 */
class EzSensorProtocol(private val sensorManager: EzSensorManager) {

    companion object {
        private const val TAG = "EzSensorProtocol"
        private const val USB_TIMEOUT_MS = 5000
        private const val BULK_READ_SIZE = 65536 // 64KB per bulk read
    }

    enum class CaptureStage {
        IDLE,
        INITIALIZING,
        READY_TO_ARM,
        DARK_FRAME,
        WAITING_TRIGGER,
        READING,
        DESCRAMBLING,
        CALIBRATING,
        PROCESSING,
        COMPLETE,
        ERROR,
        CANCELLED
    }

    data class CaptureState(
        val stage: CaptureStage = CaptureStage.IDLE,
        val message: String = "",
        val progress: Float = 0f,
        val bitmap: Bitmap? = null,
        val error: String? = null
    )

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    @Volatile
    private var cancelled = false
    private var nativeHandle: Long = 0

    private var config: EzSensorConfig = EzSensorConfig()
    private var imageWidth: Int = 810   // EzSensor Classic 1.5 default
    private var imageHeight: Int = 1115 // EzSensor Classic 1.5 default

    /**
     * Phase 1: Identify and configure.
     * Called when "Connect & Identify" is pressed.
     */
    suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        cancelled = false
        try {
            // Copy assets to the app-specific external files directory (or internal filesDir)
            val targetDir = sensorManager.context.getExternalFilesDir(null) ?: sensorManager.context.filesDir
            try {
                EzSensorConfig.copyAssetFolder(sensorManager.context, "EzSensor", targetDir)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract default asset files", e)
            }

            // Poll sensor ID (command 0x90)
            val serialId = pollSensorId(USB_TIMEOUT_MS)
            if (serialId != null) {
                Log.i(TAG, "Sensor identified: '$serialId'")
            }

            val configFile = EzSensorConfig.resolveConfigPath(sensorManager.context, serialId)
            config = EzSensorConfig.parse(configFile)

            Log.i(TAG, "Sensor config: ${config.frameWidth}x${config.frameHeight} " +
                    "descramble=${config.descramble}")

            imageWidth = if (config.frameWidth > 0) config.frameWidth else 810
            imageHeight = if (config.frameHeight > 0) config.frameHeight else 1115

            // Initialize native processing context, releasing previous context if it exists
            if (nativeHandle != 0L) {
                SensorImageBridge.nativeRelease(nativeHandle)
                nativeHandle = 0L
            }
            nativeHandle = SensorImageBridge.nativeInit(
                sensorManager.productId, imageWidth, imageHeight, config.descramble
            )
            if (nativeHandle == 0L) {
                throw RuntimeException("Failed to initialize native sensor context")
            }

            updateState(CaptureStage.READY_TO_ARM, "Sensor ready. Position sensor and tap Ready / Arm.", 0.1f)
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Prepare failed", e)
            updateState(CaptureStage.ERROR, error = e.message ?: "Unknown error")
            return@withContext false
        }
    }

    /**
     * Execute the capture pipeline (Phase 2).
     * This is a suspending function that runs on the IO dispatcher.
     */
    suspend fun capture(): Bitmap? = withContext(Dispatchers.IO) {
        cancelled = false
        var resultBitmap: Bitmap? = null

        try {
            if (nativeHandle == 0L) {
                throw RuntimeException("Sensor not prepared. Call prepare() first.")
            }

            checkCancelled()

            // Stage 2: Dark frame capture / Arming
            updateState(CaptureStage.DARK_FRAME, "Preparing sensor...", 0.1f)

            // Send initialization control transfers to the sensor (0xB0, 0x50, 0xAA)
            armSensor(config)

            checkCancelled()

            // Stage 3: Wait for X-ray trigger
            updateState(CaptureStage.WAITING_TRIGGER, "Push X-Ray button!!!", 0.2f)

            // Poll the sensor for trigger signal via bulk reads
            val rawData = waitForCaptureAndRead(imageWidth, imageHeight)

            Log.i(TAG, "Raw data received: ${rawData.size} bytes")

            // Recompute width/height based on received data if it differs from expected
            val receivedPixels = rawData.size / 2
            val expectedPixels = imageWidth * imageHeight
            var w = imageWidth
            var h = imageHeight
            
            if (receivedPixels != expectedPixels) {
                Log.w(TAG, "Received $receivedPixels pixels, expected $expectedPixels.")
                // Known exact pixel counts from reverse engineering
                if (receivedPixels == 903150) {
                    // EzSensor Classic 1.5 full frame: 810 x 1115
                    w = 810
                    h = 1115
                    Log.i(TAG, "Detected EzSensor Classic 1.5 full frame: ${w}x${h}")
                    SensorImageBridge.nativeRelease(nativeHandle)
                    nativeHandle = SensorImageBridge.nativeInit(
                        sensorManager.productId, w, h, config.descramble
                    )
                } else if (receivedPixels % imageWidth == 0) {
                    h = receivedPixels / imageWidth
                    Log.i(TAG, "Adjusting height to $h (width=$w)")
                    SensorImageBridge.nativeRelease(nativeHandle)
                    nativeHandle = SensorImageBridge.nativeInit(
                        sensorManager.productId, w, h, config.descramble
                    )
                } else if (receivedPixels % imageHeight == 0) {
                    w = receivedPixels / imageHeight
                    Log.i(TAG, "Adjusting width to $w (height=$h)")
                    SensorImageBridge.nativeRelease(nativeHandle)
                    nativeHandle = SensorImageBridge.nativeInit(
                        sensorManager.productId, w, h, config.descramble
                    )
                } else {
                    Log.w(TAG, "Could not determine dimensions cleanly. Processing as ${w}x${h}.")
                }
            }

            checkCancelled()

            // Stage 4: Descramble
            updateState(CaptureStage.DESCRAMBLING, "Descrambling...", 0.5f)

            val descrambled = SensorImageBridge.nativeDeScramble(
                nativeHandle, rawData, w, h
            ) ?: throw RuntimeException("Descramble failed")

            checkCancelled()

            // Stage 5: Calibrate
            updateState(CaptureStage.CALIBRATING, "Calibrating...", 0.65f)

            val serialId = sensorManager.sensorInfo.value?.serialNumber
            val calDir = EzSensorConfig.resolveCalibrationDir(sensorManager.context, serialId)
            val darkPath = File(calDir, "dark.raw").absolutePath
            val bpmPath = File(calDir, "BPM.raw").absolutePath
            
            // Find the highest exposure Gain Map (Axx_yyyyy.raw) for Flat-Field Correction
            var brightPath = ""
            var maxMean = -1
            val calFiles = calDir.listFiles { _, name -> name.startsWith("A") && name.endsWith(".raw") }
            if (calFiles != null) {
                for (f in calFiles) {
                    try {
                        val parts = f.nameWithoutExtension.split("_")
                        if (parts.size == 2) {
                            val mean = parts[1].toInt()
                            if (mean > maxMean) {
                                maxMean = mean
                                brightPath = f.absolutePath
                            }
                        }
                    } catch (e: Exception) {
                        // ignore malformed names
                    }
                }
            }
            if (brightPath.isEmpty()) {
                Log.w(TAG, "No Axx gain map found in $calDir! FFC will be disabled.")
            } else {
                Log.i(TAG, "Selected Gain Map for FFC: $brightPath (mean=$maxMean)")
            }

            val calibrated = SensorImageBridge.nativeCalibrate(
                nativeHandle, descrambled, w, h, darkPath, bpmPath, brightPath
            ) ?: descrambled // Fall back to uncalibrated if cal files missing

            checkCancelled()

            // Stage 6: Process
            updateState(CaptureStage.PROCESSING, "Processing image...", 0.8f)

            val configFile = EzSensorConfig.resolveConfigPath(sensorManager.context, serialId)

            val processed = SensorImageBridge.nativeProcess(
                nativeHandle, calibrated, w, h, configFile.absolutePath
            ) ?: calibrated // Fall back to unprocessed

            checkCancelled()

            // Stage 7: Convert to bitmap using full frame dimensions to avoid row shifting
            val pixels = SensorImageBridge.nativeToPixels(
                processed, w, h, config.invert
            ) ?: throw RuntimeException("Pixel conversion failed")

            resultBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            resultBitmap.setPixels(pixels, 0, w, 0, 0, w, h)

            // Crop the bitmap using imgCut margins
            if (config.imgCutLeft > 0 || config.imgCutTop > 0 || config.imgCutRight > 0 || config.imgCutBottom > 0) {
                val cropW = w - config.imgCutLeft - config.imgCutRight
                val cropH = h - config.imgCutTop - config.imgCutBottom
                if (cropW > 0 && cropH > 0) {
                    val cropped = Bitmap.createBitmap(
                        resultBitmap,
                        config.imgCutLeft,
                        config.imgCutTop,
                        cropW,
                        cropH
                    )
                    if (cropped !== resultBitmap) {
                        resultBitmap.recycle()
                        resultBitmap = cropped
                    }
                }
            }

            // Apply flip if configured (from EzSensor.ini Flip=3 means both axes)
            val flipMode = config.flip
            val bmpW = resultBitmap.width
            val bmpH = resultBitmap.height
            run {
                val matrix = android.graphics.Matrix()
                // Flip: 1=horizontal, 2=vertical, 3=both
                if (flipMode == 1 || flipMode == 3) {
                    matrix.postScale(-1f, 1f, bmpW / 2f, bmpH / 2f)
                }
                if (flipMode == 2 || flipMode == 3) {
                    matrix.postScale(1f, -1f, bmpW / 2f, bmpH / 2f)
                }
                if (config.rotation != 0) {
                    matrix.postRotate(config.rotation.toFloat())
                }
                // Rotate 90° CCW for landscape display on the Android tablet
                matrix.postRotate(-90f)
                val transformed = Bitmap.createBitmap(
                    resultBitmap, 0, 0, bmpW, bmpH, matrix, true
                )
                if (transformed !== resultBitmap) {
                    resultBitmap.recycle()
                    resultBitmap = transformed
                }
            }

            updateState(CaptureStage.COMPLETE, "Capture complete!", 1.0f, resultBitmap)

        } catch (e: CancelledException) {
            Log.i(TAG, "Capture cancelled")
            updateState(CaptureStage.CANCELLED, "Cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            updateState(CaptureStage.ERROR, error = e.message ?: "Unknown error")
        } finally {
            if (nativeHandle != 0L) {
                SensorImageBridge.nativeRelease(nativeHandle)
                nativeHandle = 0
            }
        }

        return@withContext resultBitmap
    }

    /**
     * Arm the sensor for capture.
     * Sends 0xB0 resets, 0x50 config, and 0xAA arm.
     */
    private fun armSensor(config: EzSensorConfig) {
        val timeout = USB_TIMEOUT_MS

        Log.d(TAG, "Phase: Set Ready — initializing capture")

        // 2 more ID polls (observed in Wireshark — may be confirming readiness)
        repeat(2) {
            pollSensorId(timeout)
        }

        // 3x RESET/CLEAR (command 0xB0 — flush stale data from sensor buffer)
        Log.d(TAG, "Resetting sensor buffer (3x 0xB0)")
        repeat(3) {
            sensorManager.controlTransfer(
                0x40,   // bmRequestType: OUT, Vendor, Device
                0xB0,   // bRequest: RESET/CLEAR
                0x0000, // wValue
                0x0000, // wIndex
                null, 0, timeout
            )
        }

        // CONFIGURE exposure parameters (command 0x50, wValue from Wireshark)
        // wValue=0x0225 was observed during the capture. This likely encodes
        // exposure time, gain, or a combined sensor mode parameter.
        val configValue = config.exposureParam.takeIf { it > 0 } ?: 0x0225
        Log.d(TAG, "Configuring sensor: 0x50 wValue=0x${configValue.toString(16)}")
        sensorManager.controlTransfer(
            0x40,   // bmRequestType: OUT, Vendor, Device
            0x50,   // bRequest: CONFIGURE
            configValue, // wValue: exposure/gain parameter
            0x0000, // wIndex
            null, 0, timeout
        )

        // ARM CAPTURE (command 0xAA — the critical "go" command)
        // After this, the sensor hardware waits for X-ray radiation.
        Log.d(TAG, "Arming sensor for capture (0xAA)")
        sensorManager.controlTransfer(
            0x40,   // bmRequestType: OUT, Vendor, Device
            0xAA,   // bRequest: ARM_CAPTURE
            0x0000, // wValue
            0x0000, // wIndex
            null, 0, timeout
        )

        Log.i(TAG, "Sensor armed — waiting for X-ray exposure")
    }

    /**
     * Send a GET_STATUS vendor command (0x90) and read the 32-byte sensor ID.
     *
     * Response format (32 bytes):
     *   Bytes 0-17:  ASCII serial number, null-terminated
     *   Bytes 18-19: 0x00 padding
     *   Bytes 20-21: 0xFFFF (status/sentinel)
     *   Byte  22:    Firmware version or sensor type (e.g., 0x32 = 50)
     *   Bytes 23-31: 0x00 padding
     *
     * @return The sensor serial number string, or null on failure.
     */
    private fun pollSensorId(timeout: Int): String? {
        // Send vendor control command 0x90
        sensorManager.controlTransfer(
            0x40, 0x90, 0x0000, 0x0000, null, 0, timeout
        )

        // Read 32-byte ID response from bulk endpoint
        val idBuffer = ByteArray(32)
        val bytesRead = sensorManager.bulkRead(idBuffer, 32, timeout)

        if (bytesRead >= 18) {
            // Extract ASCII serial (null-terminated, up to 18 chars)
            val nullIdx = idBuffer.indexOf(0.toByte()).takeIf { it >= 0 } ?: 18
            val serial = String(idBuffer, 0, minOf(nullIdx, 18), Charsets.US_ASCII)
            Log.d(TAG, "Sensor ID: '$serial' (read $bytesRead bytes)")
            return serial
        }

        Log.d(TAG, "Sensor ID poll: read $bytesRead bytes")
        return null
    }

    /**
     * Post-capture cleanup: send RESET/CLEAR commands.
     * Called after image data has been fully read.
     */
    private fun cleanupAfterCapture() {
        Log.d(TAG, "Post-capture cleanup (2x 0xB0)")
        repeat(2) {
            sensorManager.controlTransfer(
                0x40, 0xB0, 0x0000, 0x0000, null, 0, USB_TIMEOUT_MS
            )
        }
    }

    /**
     * Wait for the X-ray trigger and read raw sensor data.
     *
     * After the ARM command (0xAA), the host pre-queues ~30 empty bulk read
     * requests so the USB controller is ready to receive data the instant
     * the X-ray fires. Then it reads the full image frame.
     *
     * From the Wireshark capture:
     * - Packets 72-101: 30 pre-queued zero-length bulk OUT requests
     * - Packets 102-128: 27 bulk IN transfers (26 x 65508 + 1 x 7064 bytes)
     * - Packets 129-131: 3 zero-length reads (end-of-stream markers)
     */
    private fun waitForCaptureAndRead(width: Int, height: Int): ByteArray {
        // Expected raw data size: 2 bytes per pixel (16-bit sensor)
        val expectedSize = width * height * 2
        val rawData = ByteArray(expectedSize)
        var totalRead = 0
        val readBuffer = ByteArray(BULK_READ_SIZE)

        Log.d(TAG, "Waiting for X-ray trigger, expecting $expectedSize bytes")

        // The bulk read will block until the sensor receives X-ray radiation
        // and starts streaming data.
        while (totalRead < expectedSize && !cancelled) {
            val chunkTimeout = if (totalRead == 0) 30000 else 2000 // Short timeout once data is flowing

            val bytesRead = sensorManager.bulkRead(
                readBuffer,
                minOf(BULK_READ_SIZE, expectedSize - totalRead),
                chunkTimeout
            )

            if (bytesRead > 0) {
                if (totalRead == 0) {
                    updateState(CaptureStage.READING, "Reading sensor data...", 0.3f)
                    Log.i(TAG, "X-ray triggered! First chunk: $bytesRead bytes")
                }
                System.arraycopy(readBuffer, 0, rawData, totalRead, bytesRead)
                totalRead += bytesRead

                val progress = 0.3f + 0.2f * (totalRead.toFloat() / expectedSize)
                updateState(CaptureStage.READING,
                    "Reading... ${totalRead / 1024}KB", progress)
            } else if (bytesRead == 0) {
                // Zero-length read — end of stream marker
                if (totalRead > 0) {
                    Log.i(TAG, "End-of-stream marker received after $totalRead bytes")
                    break
                }
                checkCancelled()
            } else {
                // Negative = timeout or USB error
                if (totalRead > 0) {
                    Log.i(TAG, "Data stream finished (timeout after $totalRead bytes). Assuming capture complete.")
                    break
                }
                // Still waiting for trigger, continue
                checkCancelled()
            }
        }

        checkCancelled()

        // Post-capture cleanup
        cleanupAfterCapture()

        if (totalRead < expectedSize) {
            Log.i(TAG, "Variable size capture: got $totalRead bytes (expected $expectedSize)")
        } else {
            Log.i(TAG, "Full capacity read complete: $totalRead bytes")
        }

        // Return a copy trimmed to the actual number of bytes read
        return rawData.copyOf(totalRead)
    }


    fun cancel() {
        cancelled = true
    }

    fun reset() {
        cancelled = false
        _state.value = CaptureState()
        release()
    }

    fun release() {
        if (nativeHandle != 0L) {
            SensorImageBridge.nativeRelease(nativeHandle)
            nativeHandle = 0L
        }
    }

    private fun updateState(
        stage: CaptureStage,
        message: String = "",
        progress: Float = 0f,
        bitmap: Bitmap? = null,
        error: String? = null
    ) {
        _state.value = CaptureState(stage, message, progress, bitmap, error)
    }

    private fun checkCancelled() {
        if (cancelled) throw CancelledException()
    }

    private class CancelledException : Exception("Capture cancelled")
}
