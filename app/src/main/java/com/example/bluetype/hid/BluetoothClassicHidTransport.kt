package com.example.bluetype.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * BluetoothClassicHidTransport — Concrete implementation of HidTransport using Bluetooth Classic HID Device profile.
 *
 * Responsibility: Manages the Android BluetoothHidDevice lifecycle, SDP registration, pairing state, and HID report transmission.
 * Depends on: [HidTransport], [HidReportDescriptor], [ConnectionState], [KeyReport].
 * Notes: Requires BLUETOOTH_CONNECT runtime permission on API 31+. Surfaces hardware unsupported status per §4.1.
 */
class BluetoothClassicHidTransport(
    private val context: Context
) : HidTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executors.newSingleThreadExecutor()

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var hidDevice: BluetoothHidDevice? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isAppRegistered = MutableStateFlow(false)
    override val isAppRegistered: StateFlow<Boolean> = _isAppRegistered.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    override val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice.asStateFlow()

    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    override val bondedDevices: StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()

    private var intentionalDisconnect = false

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Timber.d("onAppStatusChanged: registered = %b", registered)
            _isAppRegistered.value = registered
            if (!registered) {
                // If the OS returns false for registration, the chipset does not support HID device role (§4.1)
                _connectionState.value = ConnectionState.Unsupported(
                    "[ERR_UNSUPPORTED_CHIPSET: 0x10] Your phone's Bluetooth hardware or firmware does not support acting as a keyboard"
                )
            } else {
                refreshBondedDevices()
                if (pluggedDevice != null) {
                    val name = getDeviceName(pluggedDevice)
                    _connectedDevice.value = pluggedDevice
                    _connectionState.value = ConnectionState.Connected(pluggedDevice, name)
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val name = getDeviceName(device)
            Timber.d("onConnectionStateChanged: state = %d for %s", state, device.address)
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    intentionalDisconnect = false
                    _connectedDevice.value = device
                    _connectionState.value = ConnectionState.Connected(device, name)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    _connectionState.value = ConnectionState.Connecting(device, name)
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    _connectionState.value = ConnectionState.Connecting(device, "Disconnecting...")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = _connectedDevice.value != null
                    _connectedDevice.value = null
                    _connectionState.value = ConnectionState.Disconnected()
                    if (wasConnected && !intentionalDisconnect) {
                        Timber.w("Unexpected disconnect from host. Ready for reconnect.")
                    }
                }
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Timber.d("HID Device service proxy connected")
                val device = proxy as BluetoothHidDevice
                hidDevice = device
                registerSdp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Timber.d("HID Device service proxy disconnected")
                hidDevice = null
                _isAppRegistered.value = false
                _connectedDevice.value = null
                _connectionState.value = ConnectionState.Disconnected()
            }
        }
    }

    override fun register() {
        if (!hasBluetoothConnectPermission()) {
            Timber.w("Missing BLUETOOTH_CONNECT permission; cannot register HID device")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _connectionState.value = ConnectionState.Disconnected("[ERR_BLUETOOTH_OFF: 0x11] Bluetooth is disabled")
            return
        }

        try {
            bluetoothAdapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
            refreshBondedDevices()
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException while requesting HID profile proxy")
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerSdp() {
        val device = hidDevice ?: return
        if (!hasBluetoothConnectPermission()) return

        try {
            val sdp = HidReportDescriptor.buildSdpSettings("BlueType Keyboard")
            // QoS: generous byte budget with burst headroom so the Bluetooth controller/host
            // doesn't rate-limit reports below what the app's own pacing (AppSettings.typingDelayMs)
            // controls. The previous 800 B/s, 9-byte bucket config throttled the L2CAP channel
            // to well under 1 char/sec regardless of in-app delay settings.
            val qos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                64000,   // token rate: bytes/sec budget
                64,      // token bucket size: allow bursting several reports
                0,       // peak bandwidth: unspecified
                BluetoothHidDeviceAppQosSettings.MAX, // latency: don't constrain
                BluetoothHidDeviceAppQosSettings.MAX
            )

            var registered = false
            try {
                registered = device.registerApp(
                    sdp,
                    qos,
                    qos,
                    executor,
                    hidCallback
                )
            } catch (e: Exception) {
                Timber.w(e, "registerApp with QoS failed; falling back to default QoS")
                registered = device.registerApp(
                    sdp,
                    null,
                    null,
                    executor,
                    hidCallback
                )
            }

            Timber.d("hidDevice.registerApp initiated: %b", registered)
            if (!registered) {
                _connectionState.value = ConnectionState.Unsupported(
                    "[ERR_UNSUPPORTED_CHIPSET: 0x10] Your phone's Bluetooth hardware or firmware does not support acting as a keyboard"
                )
            }
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException while registering HID SDP")
        }
    }

    override fun unregister() {
        intentionalDisconnect = true
        try {
            if (hasBluetoothConnectPermission()) {
                hidDevice?.unregisterApp()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error unregistering HID app")
        } finally {
            hidDevice?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
            hidDevice = null
            _isAppRegistered.value = false
            _connectedDevice.value = null
            _connectionState.value = ConnectionState.Disconnected()
        }
    }

    override fun connect(device: BluetoothDevice) {
        if (!hasBluetoothConnectPermission()) {
            Timber.w("Missing BLUETOOTH_CONNECT permission; cannot connect")
            return
        }
        intentionalDisconnect = false
        val name = getDeviceName(device)
        _connectionState.value = ConnectionState.Connecting(device, name)

        scope.launch {
            try {
                val success = hidDevice?.connect(device) ?: false
                Timber.d("hidDevice.connect(%s) result: %b", device.address, success)
                if (!success) {
                    _connectionState.value = ConnectionState.Disconnected("[ERR_CONN_INIT_FAILED: 0x06] Failed to initiate connection")
                }
            } catch (e: SecurityException) {
                Timber.e(e, "SecurityException in connect")
                _connectionState.value = ConnectionState.Disconnected("[ERR_PERMISSION_DENIED: 0x03] Permission denied")
            }
        }
    }

    override fun disconnect() {
        val target = _connectedDevice.value ?: return
        if (!hasBluetoothConnectPermission()) return
        intentionalDisconnect = true

        try {
            hidDevice?.disconnect(target)
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException in disconnect")
        }
    }

    override fun refreshBondedDevices() {
        if (!hasBluetoothConnectPermission()) return
        try {
            val bonded = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
            _bondedDevices.value = bonded
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException in refreshBondedDevices")
        }
    }

    override suspend fun sendKeystrokes(
        reports: List<KeyReport>,
        delayPerKeystrokeMs: Long,
        onProgress: ((sent: Int, total: Int) -> Unit)?
    ): Result<Int> = withContext(Dispatchers.IO) {
        val device = _connectedDevice.value
            ?: return@withContext Result.failure(IllegalStateException("[ERR_BT_NOT_CONNECTED: 0x01] No host PC connected"))
        val hid = hidDevice
            ?: return@withContext Result.failure(IllegalStateException("[ERR_HID_NOT_REGISTERED: 0x02] HID service not registered"))

        if (!hasBluetoothConnectPermission()) {
            return@withContext Result.failure(SecurityException("[ERR_PERMISSION_DENIED: 0x03] Missing BLUETOOTH_CONNECT permission"))
        }

        var sentCount = 0
        val total = reports.size
        val reportId = HidReportDescriptor.REPORT_ID_KEYBOARD.toInt()

        // High-throughput streaming pacing:
        // Key-press duration (pulse width): 5ms ensures Windows kbdhid.sys and kbdclass.sys register the keydown.
        // Inter-keystroke interval: controlled by delayPerKeystrokeMs, with a minimum 4ms buffer for Turbo mode.
        val pressPulseMs = 5L
        val interKeyDelayMs = if (delayPerKeystrokeMs <= 0L) 4L else delayPerKeystrokeMs

        for (i in reports.indices) {
            // Verify connection hasn't dropped mid-transmission (§10)
            if (_connectedDevice.value == null) {
                return@withContext Result.failure(
                    IllegalStateException("[ERR_CONNECTION_LOST: 0x04] Connection dropped after sending $sentCount of $total reports")
                )
            }

            val report = reports[i]
            val reportBytes = report.toByteArray()

            // Non-blocking retry with backoff to absorb Bluetooth L2CAP buffer saturation
            var attempts = 0
            var dispatched = false
            while (!dispatched && attempts < 50) {
                try {
                    dispatched = hid.sendReport(
                        device,
                        reportId,
                        reportBytes
                    )
                    if (!dispatched) {
                        attempts++
                        delay(1L) // reduced from 3L now that QoS no longer starves the channel
                    }
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        IllegalStateException("[ERR_SEND_FAILED: 0x05] ${e.message ?: "Report dispatch failed"}", e)
                    )
                }
            }

            if (!dispatched) {
                Timber.w("[ERR_SEND_FAILED: 0x05] Bluetooth sendReport buffer exhausted at report %d after 50 retries", i)
            }

            sentCount++
            onProgress?.invoke(sentCount, total)

            // Streaming pacing:
            if (report == KeyReport.EMPTY) {
                // Post-release delay between discrete characters
                delay(interKeyDelayMs)
            } else {
                // Hold key-down long enough for Windows HID stack to reliably sample it,
                // and respect the user's typing delay setting.
                delay(pressPulseMs + interKeyDelayMs)
            }
        }

        Result.success(sentCount)
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun getDeviceName(device: BluetoothDevice): String {
        return if (hasBluetoothConnectPermission()) {
            try {
                device.name ?: device.address
            } catch (e: SecurityException) {
                device.address
            }
        } else {
            device.address
        }
    }
}
