package com.ezdent.sensorpoc

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ezdent.sensorpoc.sensor.EzSensorManager
import com.ezdent.sensorpoc.sensor.EzSensorProtocol

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EzDentSensorPOCTheme {
                SensorPOCScreen()
            }
        }
    }
}

// --- Theme ---

private val DarkNavy = Color(0xFF0D1B2A)
private val DeepBlue = Color(0xFF1B2838)
private val CardBg = Color(0xFF1E2D3D)
private val AccentCyan = Color(0xFF00BCD4)
private val AccentTeal = Color(0xFF009688)
private val SuccessGreen = Color(0xFF4CAF50)
private val WarningAmber = Color(0xFFFFC107)
private val ErrorRed = Color(0xFFF44336)
private val TextPrimary = Color(0xFFE0E6ED)
private val TextSecondary = Color(0xFF8899AA)

@Composable
fun EzDentSensorPOCTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AccentCyan,
            onPrimary = DarkNavy,
            secondary = AccentTeal,
            background = DarkNavy,
            surface = DeepBlue,
            onBackground = TextPrimary,
            onSurface = TextPrimary,
            error = ErrorRed
        ),
        content = content
    )
}

// --- Main Screen ---

@Composable
fun SensorPOCScreen(viewModel: MainViewModel = viewModel()) {
    val connectionState by viewModel.sensorManager.connectionState.collectAsState()
    val sensorInfo by viewModel.sensorManager.sensorInfo.collectAsState()
    val captureState by viewModel.protocol.state.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val capturedBitmap by viewModel.capturedBitmap.collectAsState()
    val savedPath by viewModel.savedPath.collectAsState()
    val brightness by viewModel.brightness.collectAsState()
    val contrast by viewModel.contrast.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DarkNavy, DeepBlue)
                )
            )
            .systemBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left panel: Controls
            Column(
                modifier = Modifier
                    .weight(0.35f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Text(
                    "EzDent Sensor POC",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyan
                )

                // Sensor Status Card
                SensorStatusCard(connectionState, sensorInfo)

                // Action Buttons
                ActionButtons(
                    connectionState = connectionState,
                    captureStage = captureState.stage,
                    hasBitmap = capturedBitmap != null,
                    isSaved = savedPath != null,
                    onConnect = viewModel::connectSensor,
                    onDisconnect = viewModel::disconnectSensor,
                    onCapture = viewModel::startCapture,
                    onCancel = viewModel::cancelCapture,
                    onSave = viewModel::saveImage,
                    onReset = viewModel::resetCapture
                )

                // Capture Progress
                if (captureState.stage != EzSensorProtocol.CaptureStage.IDLE &&
                    captureState.stage != EzSensorProtocol.CaptureStage.COMPLETE) {
                    CaptureProgressCard(captureState)
                }

                // Brightness & Contrast sliders (shown after capture)
                if (capturedBitmap != null) {
                    ImageAdjustmentPanel(
                        brightness = brightness,
                        contrast = contrast,
                        onBrightnessChange = viewModel::setBrightness,
                        onContrastChange = viewModel::setContrast,
                        onReset = viewModel::resetAdjustments
                    )
                }

                Spacer(Modifier.weight(1f))

                // Status bar
                StatusBar(statusMessage, savedPath)
            }

            // Right panel: Image Preview
            ImagePreviewPanel(
                bitmap = capturedBitmap,
                captureStage = captureState.stage,
                captureMessage = captureState.message,
                brightness = brightness,
                contrast = contrast
            )
        }
    }
}

// --- Components ---

@Composable
fun SensorStatusCard(
    state: EzSensorManager.ConnectionState,
    info: EzSensorManager.SensorInfo?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status indicator dot
            val dotColor = when (state) {
                EzSensorManager.ConnectionState.CONNECTED -> SuccessGreen
                EzSensorManager.ConnectionState.CONNECTING,
                EzSensorManager.ConnectionState.REQUESTING_PERMISSION -> WarningAmber
                EzSensorManager.ConnectionState.ERROR -> ErrorRed
                EzSensorManager.ConnectionState.DISCONNECTED -> TextSecondary
            }

            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (state) {
                        EzSensorManager.ConnectionState.CONNECTED -> "Sensor Connected"
                        EzSensorManager.ConnectionState.CONNECTING -> "Connecting..."
                        EzSensorManager.ConnectionState.REQUESTING_PERMISSION -> "Requesting Permission..."
                        EzSensorManager.ConnectionState.ERROR -> "Connection Error"
                        EzSensorManager.ConnectionState.DISCONNECTED -> "No Sensor"
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    fontSize = 14.sp
                )

                if (info != null) {
                    Text(
                        "PID: 0x${info.productId.toString(16).uppercase()} " +
                                "S/N: ${info.serialNumber ?: "N/A"}",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ActionButtons(
    connectionState: EzSensorManager.ConnectionState,
    captureStage: EzSensorProtocol.CaptureStage,
    hasBitmap: Boolean,
    isSaved: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCapture: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit
) {
    val isConnected = connectionState == EzSensorManager.ConnectionState.CONNECTED
    val isCapturing = captureStage != EzSensorProtocol.CaptureStage.IDLE &&
            captureStage != EzSensorProtocol.CaptureStage.READY_TO_ARM &&
            captureStage != EzSensorProtocol.CaptureStage.COMPLETE &&
            captureStage != EzSensorProtocol.CaptureStage.ERROR &&
            captureStage != EzSensorProtocol.CaptureStage.CANCELLED

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Connect / Disconnect
        if (!isConnected) {
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Filled.Usb, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Connect & Identify", fontWeight = FontWeight.SemiBold)
            }
        } else {
            // Capture / Cancel
            if (!isCapturing) {
                Button(
                    onClick = onCapture,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = captureStage == EzSensorProtocol.CaptureStage.READY_TO_ARM || captureStage == EzSensorProtocol.CaptureStage.COMPLETE,
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Filled.CameraAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ready / Arm Capture", fontWeight = FontWeight.SemiBold)
                }
            } else {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Filled.Cancel, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel Capture")
                }
            }

            // Save button
            if (hasBitmap && !isSaved) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Filled.Save, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save to Device", fontWeight = FontWeight.SemiBold)
                }
            }

            // Reset button
            if (hasBitmap) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New Capture")
                }
            }

            // Disconnect
            TextButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect", color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun CaptureProgressCard(state: EzSensorProtocol.CaptureState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                state.message,
                color = if (state.stage == EzSensorProtocol.CaptureStage.WAITING_TRIGGER)
                    WarningAmber else TextPrimary,
                fontWeight = if (state.stage == EzSensorProtocol.CaptureStage.WAITING_TRIGGER)
                    FontWeight.Bold else FontWeight.Normal,
                fontSize = if (state.stage == EzSensorProtocol.CaptureStage.WAITING_TRIGGER)
                    16.sp else 13.sp
            )

            if (state.progress > 0f) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = AccentCyan,
                    trackColor = DarkNavy
                )
            }

            if (state.error != null) {
                Text(
                    "Error: ${state.error}",
                    color = ErrorRed,
                    fontSize = 12.sp
                )
            }
        }
    }
}


@Composable
fun RowScope.ImagePreviewPanel(
    bitmap: Bitmap?,
    captureStage: EzSensorProtocol.CaptureStage,
    captureMessage: String,
    brightness: Float = 0f,
    contrast: Float = 1f
) {
    // Build a brightness/contrast ColorMatrix
    // Contrast: scale RGB around 128 midpoint
    // Brightness: offset added to RGB
    val colorMatrix = remember(brightness, contrast) {
        val c = contrast
        val b = brightness
        val t = (1f - c) * 128f + b
        ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    Card(
        modifier = Modifier
            .weight(0.65f)
            .fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111820)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Captured X-Ray",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.colorMatrix(colorMatrix)
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Filled.MedicalServices,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = TextSecondary.copy(alpha = 0.3f)
                    )
                    Text(
                        text = when (captureStage) {
                            EzSensorProtocol.CaptureStage.WAITING_TRIGGER ->
                                "Push X-Ray Button!!!"
                            EzSensorProtocol.CaptureStage.IDLE ->
                                "Connect sensor and capture\nan X-ray to preview here"
                            else -> captureMessage
                        },
                        color = if (captureStage == EzSensorProtocol.CaptureStage.WAITING_TRIGGER)
                            WarningAmber else TextSecondary,
                        textAlign = TextAlign.Center,
                        fontWeight = if (captureStage == EzSensorProtocol.CaptureStage.WAITING_TRIGGER)
                            FontWeight.Bold else FontWeight.Normal,
                        fontSize = if (captureStage == EzSensorProtocol.CaptureStage.WAITING_TRIGGER)
                            20.sp else 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ImageAdjustmentPanel(
    brightness: Float,
    contrast: Float,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Image Adjustments",
                    fontWeight = FontWeight.SemiBold,
                    color = AccentCyan,
                    fontSize = 13.sp
                )
                TextButton(
                    onClick = onReset,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Reset", fontSize = 11.sp, color = TextSecondary)
                }
            }

            // Brightness slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.LightMode,
                    contentDescription = "Brightness",
                    modifier = Modifier.size(16.dp),
                    tint = TextSecondary
                )
                Slider(
                    value = brightness,
                    onValueChange = onBrightnessChange,
                    valueRange = -100f..100f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = AccentCyan,
                        activeTrackColor = AccentCyan,
                        inactiveTrackColor = DarkNavy
                    )
                )
                Text(
                    "${brightness.toInt()}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.width(30.dp),
                    textAlign = TextAlign.End
                )
            }

            // Contrast slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Contrast,
                    contentDescription = "Contrast",
                    modifier = Modifier.size(16.dp),
                    tint = TextSecondary
                )
                Slider(
                    value = contrast,
                    onValueChange = onContrastChange,
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = AccentCyan,
                        activeTrackColor = AccentCyan,
                        inactiveTrackColor = DarkNavy
                    )
                )
                Text(
                    "%.1f".format(contrast),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.width(30.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
fun StatusBar(message: String, savedPath: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                message,
                color = TextPrimary,
                fontSize = 12.sp
            )
            if (savedPath != null) {
                Text(
                    "📂 $savedPath",
                    color = SuccessGreen,
                    fontSize = 10.sp
                )
            }
        }
    }
}
