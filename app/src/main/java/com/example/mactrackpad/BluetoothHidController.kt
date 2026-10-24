package com.example.mactrackpad

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

@SuppressLint("MissingPermission")
class BluetoothHidController(
    private val context: Context,
    private val onStatusChanged: (status: String, connectedDevice: BluetoothDevice?) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var hidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    val isConnected: Boolean
        get() = hidDevice != null && hostDevice != null

    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        "Mac Trackpad Pro",
        "Full-Screen Multi-Touch Trackpad for macOS",
        "AndroidHID",
        BluetoothHidDevice.SUBCLASS1_COMBO,
        HidDescriptor.COMPOSITE_MOUSE_KEYBOARD_DESCRIPTOR
    )

    // 8000us (125Hz) low-latency QoS for smooth cursor tracking
    private val qosOut = BluetoothHidDeviceAppQosSettings(
        BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
        800,
        9,
        0,
        8000,
        BluetoothHidDeviceAppQosSettings.MAX
    )

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) {
                notifyStatus("HID Ready — Pair or tap Connect", null)
                if (pluggedDevice != null) {
                    hidDevice?.connect(pluggedDevice)
                }
            } else {
                hostDevice = null
                notifyStatus("HID Unregistered", null)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hostDevice = device
                    notifyStatus("Connected to ${device?.name ?: "Mac"}", device)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    notifyStatus("Connecting to ${device?.name ?: "Mac"}...", null)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (hostDevice == device) hostDevice = null
                    notifyStatus("Disconnected — Tap status pill to connect", null)
                }
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                hidDevice?.registerApp(sdpSettings, null, qosOut, executor, hidCallback)
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                hostDevice = null
                notifyStatus("Bluetooth HID service disconnected", null)
            }
        }
    }

    fun start() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            notifyStatus("Please enable Bluetooth", null)
            return
        }
        bluetoothAdapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    fun stop() {
        try {
            hidDevice?.unregisterApp()
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        } catch (_: Exception) {
        }
        hidDevice = null
        hostDevice = null
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun connectToDevice(device: BluetoothDevice) {
        hidDevice?.connect(device)
    }

    /**
     * Sends a 5-byte Mouse + Scroll HID report (Report ID 1).
     */
    fun sendMouseReport(
        leftButton: Boolean,
        rightButton: Boolean,
        dx: Int,
        dy: Int,
        vScroll: Int = 0,
        hScroll: Int = 0
    ) {
        val hid = hidDevice ?: return
        val host = hostDevice ?: return

        var buttons = 0
        if (leftButton) buttons = buttons or 0x01
        if (rightButton) buttons = buttons or 0x02

        val report = byteArrayOf(
            buttons.toByte(),
            clampByte(dx),
            clampByte(dy),
            clampByte(vScroll),
            clampByte(hScroll)
        )
        hid.sendReport(host, HidDescriptor.REPORT_ID_MOUSE, report)
    }

    /**
     * Sends a macOS keyboard shortcut via Report ID 2 (press + release).
     */
    fun sendShortcut(modifier: Byte, keycode: Byte) {
        val hid = hidDevice ?: return
        val host = hostDevice ?: return

        val pressReport = byteArrayOf(modifier, 0x00, keycode, 0x00, 0x00, 0x00, 0x00, 0x00)
        val releaseReport = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

        hid.sendReport(host, HidDescriptor.REPORT_ID_KEYBOARD, pressReport)
        mainHandler.postDelayed({
            hid.sendReport(host, HidDescriptor.REPORT_ID_KEYBOARD, releaseReport)
        }, 22L)
    }

    private fun clampByte(value: Int): Byte {
        return max(-127, min(127, value)).toByte()
    }

    private fun notifyStatus(msg: String, device: BluetoothDevice?) {
        mainHandler.post {
            onStatusChanged(msg, device)
        }
    }
}
