package com.ezdent.sensorpoc.sensor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the USB connection lifecycle for Vatech EzSensor devices.
 *
 * The EzSensor connects via USB-OTG and is identified by:
 * - Vendor ID: 0x0547 (Vatech Co., Ltd.)
 * - Product ID: 0x2001–0x2FFF (EzSensor family)
 *
 * USB I/O uses bulk transfer on the sensor's bulk-in endpoint.
 */
class EzSensorManager(val context: Context) {

    companion object {
        private const val TAG = "EzSensorManager"
        const val ACTION_USB_PERMISSION = "com.ezdent.sensorpoc.USB_PERMISSION"

        // Vatech USB identifiers (reverse-engineered from original APK)
        const val VATECH_VENDOR_ID = 0x0547 // 1351 decimal
        const val PID_RANGE_MIN = 0x2000    // 8192 decimal (exclusive)
        const val PID_RANGE_MAX = 0x3000    // 12288 decimal (exclusive)

        fun isVatechSensor(device: UsbDevice): Boolean {
            return device.vendorId == VATECH_VENDOR_ID &&
                    device.productId > PID_RANGE_MIN &&
                    device.productId < PID_RANGE_MAX
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var bulkOutEndpoint: UsbEndpoint? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _sensorInfo = MutableStateFlow<SensorInfo?>(null)
    val sensorInfo: StateFlow<SensorInfo?> = _sensorInfo.asStateFlow()

    enum class ConnectionState {
        DISCONNECTED,
        REQUESTING_PERMISSION,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    data class SensorInfo(
        val vendorId: Int,
        val productId: Int,
        val serialNumber: String?,
        val deviceName: String,
        val productName: String?
    )

    // BroadcastReceiver for USB permission result
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    Log.i(TAG, "USB permission granted for ${device.deviceName}")
                    openDevice(device)
                } else {
                    Log.w(TAG, "USB permission denied")
                    _connectionState.value = ConnectionState.ERROR
                }
            }
        }
    }

    private var receiverRegistered = false

    fun registerReceiver() {
        if (!receiverRegistered) {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbPermissionReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    fun unregisterReceiver() {
        if (receiverRegistered) {
            context.unregisterReceiver(usbPermissionReceiver)
            receiverRegistered = false
        }
    }

    /**
     * Scan USB bus for connected EzSensor devices.
     */
    fun findSensor(): UsbDevice? {
        val deviceList = usbManager.deviceList
        Log.d(TAG, "USB devices found: ${deviceList.size}")
        for ((name, device) in deviceList) {
            Log.d(TAG, "  Device: $name VID=0x${device.vendorId.toString(16)} " +
                    "PID=0x${device.productId.toString(16)}")
            if (isVatechSensor(device)) {
                Log.i(TAG, "EzSensor found: $name PID=0x${device.productId.toString(16)}")
                return device
            }
        }
        Log.d(TAG, "No EzSensor found on USB bus")
        return null
    }

    /**
     * Connect to the sensor. Requests USB permission if needed.
     */
    fun connect(device: UsbDevice) {
        usbDevice = device

        if (usbManager.hasPermission(device)) {
            openDevice(device)
        } else {
            _connectionState.value = ConnectionState.REQUESTING_PERMISSION
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun openDevice(device: UsbDevice) {
        _connectionState.value = ConnectionState.CONNECTING

        try {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                Log.e(TAG, "Failed to open USB device")
                _connectionState.value = ConnectionState.ERROR
                return
            }

            // Find bulk transfer interface and endpoints
            var iface: UsbInterface? = null
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        iface = intf
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            bulkIn = ep
                        } else {
                            bulkOut = ep
                        }
                    }
                }
                if (bulkIn != null) break
            }

            if (iface == null || bulkIn == null) {
                Log.e(TAG, "No bulk transfer endpoint found")
                connection.close()
                _connectionState.value = ConnectionState.ERROR
                return
            }

            // Claim the interface
            if (!connection.claimInterface(iface, true)) {
                Log.e(TAG, "Failed to claim USB interface")
                connection.close()
                _connectionState.value = ConnectionState.ERROR
                return
            }

            usbConnection = connection
            usbInterface = iface
            bulkInEndpoint = bulkIn
            bulkOutEndpoint = bulkOut

            val serialNum = try { connection.serial } catch (e: Exception) { null }

            _sensorInfo.value = SensorInfo(
                vendorId = device.vendorId,
                productId = device.productId,
                serialNumber = serialNum,
                deviceName = device.deviceName,
                productName = device.productName
            )

            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "EzSensor connected: PID=0x${device.productId.toString(16)} " +
                    "Serial=$serialNum Endpoint=${bulkIn.address}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open device", e)
            _connectionState.value = ConnectionState.ERROR
        }
    }

    /**
     * Read raw data from the sensor via USB bulk transfer.
     * Called from the native C++ layer during capture.
     */
    fun bulkRead(buffer: ByteArray, length: Int, timeout: Int): Int {
        val conn = usbConnection ?: return -1
        val ep = bulkInEndpoint ?: return -1
        return conn.bulkTransfer(ep, buffer, length, timeout)
    }

    /**
     * Send a USB control transfer to the sensor.
     */
    fun controlTransfer(
        requestType: Int, request: Int, value: Int,
        index: Int, data: ByteArray?, length: Int, timeout: Int
    ): Int {
        val conn = usbConnection ?: return -1
        return conn.controlTransfer(requestType, request, value, index, data, length, timeout)
    }

    /**
     * Disconnect from the sensor and release resources.
     */
    fun disconnect() {
        usbConnection?.let { conn ->
            usbInterface?.let { conn.releaseInterface(it) }
            conn.close()
        }
        usbConnection = null
        usbInterface = null
        bulkInEndpoint = null
        bulkOutEndpoint = null
        usbDevice = null
        _sensorInfo.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.i(TAG, "EzSensor disconnected")
    }

    val isConnected: Boolean
        get() = _connectionState.value == ConnectionState.CONNECTED

    val productId: Int
        get() = usbDevice?.productId ?: 0
}
