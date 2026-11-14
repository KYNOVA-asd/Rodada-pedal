package com.rodada.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Presenta este teléfono como un control remoto Bluetooth (HID, solo Consumer Control)
 * para mandar teclas multimedia a otro teléfono sin instalarle nada.
 *
 * Quien lo usa debe tener ya BLUETOOTH_CONNECT concedido antes de llamar a start().
 * Mientras está iniciado, si el host se desconecta, reintenta conectar cada pocos segundos.
 */
@SuppressLint("MissingPermission")
class HidMediaRemote(
    context: Context,
    /** Dirección del host emparejado antes; null si todavía no hay ninguno. */
    private var preferredAddress: String?,
    private val onStatus: (Status) -> Unit
) {
    enum class Key(val usage: Int) {
        PLAY_PAUSE(0xCD),
        NEXT(0xB5),
        PREVIOUS(0xB6),
        VOLUME_UP(0xE9),
        VOLUME_DOWN(0xEA)
    }

    enum class Error { BLUETOOTH_OFF, PROFILE_UNAVAILABLE, REGISTER_FAILED, SEND_FAILED }

    data class Status(
        val registered: Boolean = false,
        val connectedName: String? = null,
        val connectedAddress: String? = null,
        val reconnecting: Boolean = false,
        val lastError: Error? = null
    )

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var started = false
    var status = Status()
        private set
    val isConnected: Boolean
        get() = host != null

    private val sdp = BluetoothHidDeviceAppSdpSettings(
        "Rodada",
        "Control multimedia",
        "Rodada",
        BluetoothHidDevice.SUBCLASS1_NONE,
        DESCRIPTOR
    )

    private val reconnect = object : Runnable {
        override fun run() {
            if (!started || host != null || !status.registered) return
            val device = preferredDevice() ?: return
            Log.d(TAG, "reconectando con ${device.address}")
            hid?.connect(device)
            main.postDelayed(this, RECONNECT_INTERVAL_MS)
        }
    }

    private val registerTimeout = Runnable {
        if (started && !status.registered) fail(Error.REGISTER_FAILED)
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged registered=$registered plugged=${pluggedDevice?.address}")
            main.post {
                status = status.copy(
                    registered = registered,
                    lastError = if (registered) null else status.lastError
                )
                if (!registered) host = null
                publish()
                if (registered && host == null) {
                    val device = pluggedDevice ?: preferredDevice()
                    if (device != null) {
                        hid?.connect(device)
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.d(TAG, "onConnectionStateChanged ${device.address} state=$state")
            main.post {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        host = device
                        preferredAddress = device.address
                        main.removeCallbacks(reconnect)
                        status = status.copy(
                            connectedName = device.name ?: device.address,
                            connectedAddress = device.address,
                            reconnecting = false,
                            lastError = null
                        )
                        publish()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> if (host == null || host == device) {
                        host = null
                        status = status.copy(connectedName = null, connectedAddress = null)
                        publish()
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            Log.d(TAG, "onGetReport type=$type id=$id")
            hid?.replyReport(device, type, REPORT_ID.toByte(), byteArrayOf(0, 0))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            Log.d(TAG, "onSetReport type=$type id=$id")
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            Log.d(TAG, "onVirtualCableUnplug ${device.address}")
            main.post {
                if (host == device) host = null
                status = status.copy(connectedName = null, connectedAddress = null)
                publish()
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val device = proxy as BluetoothHidDevice
            if (!started) {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
                return
            }
            hid = device
            // En el S40, registerApp() a veces devuelve false y aun así registra:
            // se decide por onAppStatusChanged y solo se marca error si no llega a tiempo.
            val accepted = device.registerApp(sdp, null, null, { it.run() }, callback)
            Log.d(TAG, "registerApp aceptado=$accepted")
            main.postDelayed(registerTimeout, REGISTER_TIMEOUT_MS)
        }

        override fun onServiceDisconnected(profile: Int) {
            Log.d(TAG, "onServiceDisconnected")
            hid = null
            host = null
            main.removeCallbacks(reconnect)
            status = status.copy(registered = false, connectedName = null, connectedAddress = null, reconnecting = false)
            publish()
        }
    }

    fun start() {
        if (started) return
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            fail(Error.BLUETOOTH_OFF)
            return
        }
        started = true
        status = Status()
        publish()
        if (!bt.getProfileProxy(appContext, serviceListener, BluetoothProfile.HID_DEVICE)) {
            started = false
            fail(Error.PROFILE_UNAVAILABLE)
        }
    }

    fun sendKey(key: Key): Boolean {
        val device = host
        val proxy = hid
        if (device == null || proxy == null) return false
        val press = byteArrayOf((key.usage and 0xFF).toByte(), (key.usage shr 8).toByte())
        val down = proxy.sendReport(device, REPORT_ID, press)
        val up = proxy.sendReport(device, REPORT_ID, byteArrayOf(0, 0))
        Log.d(TAG, "sendKey ${key.name} down=$down up=$up")
        if (!down || !up) fail(Error.SEND_FAILED)
        return down && up
    }

    /** Suelta el registro HID y el proxy. Después de esto no se reintenta nada. */
    fun stop() {
        main.removeCallbacks(reconnect)
        main.removeCallbacks(registerTimeout)
        if (!started) return
        started = false
        hid?.let { proxy ->
            proxy.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
        }
        hid = null
        host = null
        status = Status()
        Log.d(TAG, "HID liberado")
    }

    private fun preferredDevice(): BluetoothDevice? {
        val address = preferredAddress ?: return null
        val bt = adapter ?: return null
        return bt.bondedDevices.orEmpty().firstOrNull { it.address == address }
    }

    private fun scheduleReconnect() {
        if (!started || preferredDevice() == null) return
        status = status.copy(reconnecting = true)
        publish()
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, RECONNECT_INTERVAL_MS)
    }

    private fun fail(error: Error) {
        Log.w(TAG, "error $error")
        main.post {
            status = status.copy(lastError = error)
            publish()
        }
    }

    private fun publish() {
        if (started || status.lastError != null) onStatus(status)
    }

    companion object {
        private const val TAG = "RodadaHid"
        private const val REPORT_ID = 1
        private const val RECONNECT_INTERVAL_MS = 15_000L
        private const val REGISTER_TIMEOUT_MS = 3_000L

        /** Consumer Control: un solo uso de 16 bits (0x000–0x3FF), report ID 1. */
        private val DESCRIPTOR = byteArrayOf(
            0x05, 0x0C,                         // Usage Page (Consumer)
            0x09, 0x01,                         // Usage (Consumer Control)
            0xA1.toByte(), 0x01,                // Collection (Application)
            0x85.toByte(), 0x01,                //   Report ID (1)
            0x15, 0x00,                         //   Logical Minimum (0)
            0x26, 0xFF.toByte(), 0x03,          //   Logical Maximum (0x3FF)
            0x19, 0x00,                         //   Usage Minimum (0)
            0x2A, 0xFF.toByte(), 0x03,          //   Usage Maximum (0x3FF)
            0x75, 0x10,                         //   Report Size (16)
            0x95.toByte(), 0x01,                //   Report Count (1)
            0x81.toByte(), 0x00,                //   Input (Data, Array, Absolute)
            0xC0.toByte()                       // End Collection
        )
    }
}
