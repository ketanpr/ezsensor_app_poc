package com.ezdent.sensorpoc

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ezdent.sensorpoc.sensor.EzSensorManager
import com.ezdent.sensorpoc.sensor.EzSensorProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    val sensorManager = EzSensorManager(application)
    val protocol = EzSensorProtocol(sensorManager)

    private val _statusMessage = MutableStateFlow("Ready. Connect EzSensor via USB-OTG.")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _capturedBitmap = MutableStateFlow<Bitmap?>(null)
    val capturedBitmap: StateFlow<Bitmap?> = _capturedBitmap.asStateFlow()

    private val _savedPath = MutableStateFlow<String?>(null)
    val savedPath: StateFlow<String?> = _savedPath.asStateFlow()

    // Post-capture brightness/contrast adjustments (non-destructive)
    private val _brightness = MutableStateFlow(0f)     // -100..+100, default 0
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    private val _contrast = MutableStateFlow(1f)        // 0.5..2.0, default 1.0
    val contrast: StateFlow<Float> = _contrast.asStateFlow()

    fun setBrightness(value: Float) { _brightness.value = value }
    fun setContrast(value: Float) { _contrast.value = value }
    fun resetAdjustments() { _brightness.value = 0f; _contrast.value = 1f }

    init {
        sensorManager.registerReceiver()
        
        // Automatically prepare sensor once USB is connected
        viewModelScope.launch {
            sensorManager.connectionState.collect { state ->
                if (state == EzSensorManager.ConnectionState.CONNECTED) {
                    prepareSensor()
                }
            }
        }
    }

    /**
     * Scan for and connect to an EzSensor.
     */
    fun connectSensor() {
        val device = sensorManager.findSensor()
        if (device != null) {
            _statusMessage.value = "EzSensor found! Requesting permission..."
            sensorManager.connect(device)
        } else {
            _statusMessage.value = "No EzSensor found. Check USB-OTG connection."
        }
    }

    fun prepareSensor() {
        if (!sensorManager.isConnected) return
        
        viewModelScope.launch {
            val success = protocol.prepare()
            if (success) {
                _statusMessage.value = "Sensor identified. Position it and tap 'Ready / Arm'."
            } else {
                _statusMessage.value = "Failed to identify sensor."
            }
        }
    }

    /**
     * Start the X-ray capture pipeline.
     */
    fun startCapture() {
        if (!sensorManager.isConnected) {
            _statusMessage.value = "Sensor not connected!"
            return
        }

        _capturedBitmap.value = null
        _savedPath.value = null

        viewModelScope.launch {
            val bitmap = protocol.capture()
            if (bitmap != null) {
                _capturedBitmap.value = bitmap
                _statusMessage.value = "Capture complete! Tap Save to store the image."
            }
        }
    }

    /**
     * Cancel an in-progress capture.
     */
    fun cancelCapture() {
        protocol.cancel()
    }

    /**
     * Save the captured bitmap to device storage.
     */
    fun saveImage() {
        val bitmap = _capturedBitmap.value ?: return

        viewModelScope.launch {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val filename = "EzDent_XRay_$timestamp.png"

                val savedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ — use MediaStore
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_PICTURES}/EzDent")
                    }

                    val uri = getApplication<Application>().contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                    )

                    uri?.let {
                        getApplication<Application>().contentResolver.openOutputStream(it)?.use { os ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                        }
                    }

                    uri?.toString() ?: "Unknown"
                } else {
                    // Android 9 and below — write to file directly
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES
                        ), "EzDent"
                    )
                    dir.mkdirs()
                    val file = File(dir, filename)
                    FileOutputStream(file).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    file.absolutePath
                }

                _savedPath.value = savedUri
                _statusMessage.value = "Image saved: $filename"
                Log.i(TAG, "Image saved to: $savedUri")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to save image", e)
                _statusMessage.value = "Save failed: ${e.message}"
            }
        }
    }

    fun disconnectSensor() {
        sensorManager.disconnect()
        _statusMessage.value = "Sensor disconnected."
    }

    fun resetCapture() {
        protocol.reset()
        _capturedBitmap.value = null
        _savedPath.value = null
        resetAdjustments()
        _statusMessage.value = if (sensorManager.isConnected) {
            "Sensor connected. Ready to capture."
        } else {
            "Ready. Connect EzSensor via USB-OTG."
        }
    }

    override fun onCleared() {
        super.onCleared()
        protocol.release()
        sensorManager.disconnect()
        sensorManager.unregisterReceiver()
    }
}
