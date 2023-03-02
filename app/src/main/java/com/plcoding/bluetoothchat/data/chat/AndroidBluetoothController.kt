package com.plcoding.bluetoothchat.data.chat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.plcoding.bluetoothchat.domain.chat.BluetoothController
import com.plcoding.bluetoothchat.domain.chat.BluetoothDeviceDomain
import com.plcoding.bluetoothchat.domain.chat.ConnectionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.IOException
import java.util.*

@SuppressLint("MissingPermission")
class AndroidBluetoothController(
    private val context: Context
) : BluetoothController {

    private val bluetoothManager by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }
    private val bluetoothAdapter by lazy {
        bluetoothManager?.adapter
    }

    private val _isConnected =
        MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean>
        get() = _isConnected.asStateFlow()

    private val _scannedDevices =
        MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    override val scannedDevices: StateFlow<List<BluetoothDeviceDomain>>
        get() = _scannedDevices.asStateFlow()

    private val _pairedDevices =
        MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    override val pairedDevices: StateFlow<List<BluetoothDeviceDomain>>
        get() = _pairedDevices.asStateFlow()

    private val _errors =
        MutableSharedFlow<String>()
    override val errors: SharedFlow<String>
        get() = _errors.asSharedFlow()

    override val sendToClientStateFlow =
        MutableStateFlow("")

    override val sendToServerStateFlow =
        MutableStateFlow("")

    private val foundDeviceBroadcastReceiver =
        FoundDeviceBroadcastReceiver(
            onDeviceFound = { device ->
                _scannedDevices.update { devices ->
                    val newDevice = device.toBluetoothDeviceDomain()
                    if (newDevice in devices) devices else devices + newDevice
                }
            }
        )

    private val bluetoothStateBroadcastReceiver =
        BluetoothStateBroadcastReceiver(
            onStateChanged = { isConnected, bluetoothDevice ->
                _isConnected.update { isConnected }
                if (isConnected) {
                    _pairedDevices.update { devices ->
                        val newDevice = bluetoothDevice.toBluetoothDeviceDomain()
                        if (newDevice in devices) devices else devices + newDevice
                    }
                }
            }
        )

    private var currentServerSocket: BluetoothServerSocket? = null
    private var currentClientSocket: BluetoothSocket? = null

    init {
        updatePairedDevices()
        context.registerReceiver(
            bluetoothStateBroadcastReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        )
    }

    override fun startDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                return
            }
        }

        context.registerReceiver(
            foundDeviceBroadcastReceiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND)
        )

        updatePairedDevices()

        bluetoothAdapter?.startDiscovery()
    }

    override fun stopDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                return
            }
        }

        bluetoothAdapter?.cancelDiscovery()
    }

    override fun startBluetoothServer(): Flow<ConnectionResult> {
        return flow {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    throw SecurityException("No BLUETOOTH_CONNECT permission")
                }
            }

            currentServerSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(
                "chat_service",
                UUID.fromString(SERVICE_UUID)
            )

            var shouldLoop = true
            while (shouldLoop) {
                var error: ConnectionResult? = null

                emit(ConnectionResult.ConnectionEstablished)
                currentClientSocket = try {
                    currentServerSocket?.accept()
                } catch (e: IOException) {
                    shouldLoop = false
                    null
                }

                // Communicate with the client
                currentClientSocket?.let { socket ->
                    val inputStream = socket.inputStream
                    val outputStream = socket.outputStream
                    val buffer = ByteArray(1024)
                    var bytes: Int

                    emit(ConnectionResult.ConnectionEstablished)
                    println("Connected to ${socket.remoteDevice.name} (${socket.remoteDevice.address})")

                    // Send a heartbeat ping to client
                    CoroutineScope(Dispatchers.IO).launch {
                        while (true) {
                            try {
                                delay(1000)
                                val message = "server.ping: ${
                                    System.currentTimeMillis().toString().takeLast(6)
                                }"
                                withContext(Dispatchers.IO) {
                                    outputStream.write(message.toByteArray())
                                }
                            } catch (e: IOException) {
                                error = ConnectionResult.Error("Connection was interrupted")
                                shouldLoop = false
                                break
                            }
                        }
                    }

                    // Send message to client from MutableStateFlow
                    CoroutineScope(Dispatchers.IO).launch {
                        while (true) {
                            try {
                                yield()
                                sendToClientStateFlow.collectLatest { message ->
                                    outputStream.write(message.toByteArray())
                                }
                            } catch (e: IOException) {
                                error = ConnectionResult.Error("Connection was interrupted")
                                shouldLoop = false
                                break
                            }
                        }
                    }

                    // Read message from client
                    while (true) {
                        try {
                            bytes =
                                withContext(Dispatchers.IO) {
                                    inputStream.read(buffer)
                                }
                            val readMessage = String(buffer, 0, bytes)
                            emit(ConnectionResult.Message(readMessage))

                            // Send message back
                            val message = "from server: $readMessage"
                            yield()
                            withContext(Dispatchers.IO) {
                                outputStream.write(message.toByteArray())
                            }
                        } catch (e: IOException) {
                            error = ConnectionResult.Error("Connection was interrupted")
                            shouldLoop = false
                            break
                        }
                    }
                }

                error?.let { emit(it) }
                currentClientSocket?.let {
                    currentServerSocket?.close()
                }
            }
        }.onCompletion {
            println("onCompletion - throwable: ${it?.localizedMessage}")
            closeConnection()
        }.flowOn(Dispatchers.IO)
    }

    override fun connectToDevice(device: BluetoothDeviceDomain): Flow<ConnectionResult> {
        return flow {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    throw SecurityException("No BLUETOOTH_CONNECT permission")
                }
            }

            currentClientSocket = bluetoothAdapter
                ?.getRemoteDevice(device.address)
                ?.createRfcommSocketToServiceRecord(
                    UUID.fromString(SERVICE_UUID)
                )
            stopDiscovery()
            println("Connecting to ${device.name} (${device.address})")
            println("Socket: $currentClientSocket")

            // Connect to the server
            currentClientSocket?.let { socket ->
                try {
                    socket.connect()
                    emit(ConnectionResult.ConnectionEstablished)
                } catch (e: IOException) {
                    socket.close()
                    currentClientSocket = null
                    emit(ConnectionResult.Error("Connection was interrupted"))
                }
            }

            // Communicate with the server
            currentClientSocket?.let { socket ->
                val inputStream = socket.inputStream
                val outputStream = socket.outputStream
                val buffer = ByteArray(1024)
                var bytes: Int


                // send an initial hello message to server
                withContext(Dispatchers.IO) {
                    outputStream.write("Hello from Client".toByteArray())
                }

                // Send messages from MutableStateFlow to server
                CoroutineScope(Dispatchers.IO).launch {
                    sendToServerStateFlow.collectLatest { message ->
                        outputStream ?: return@collectLatest

                        withContext(Dispatchers.IO) {
                            outputStream.write(message.toByteArray())
                        }
                    }
                }

                // get messages from server
                var isRunning = true
                while (isRunning) {
                    try {
                        yield()
//                        bytes = withContext(Dispatchers.IO) {
//                            inputStream.read(buffer)
//                        }
                        bytes = inputStream.read(buffer)
                        val readMessage = String(buffer, 0, bytes)

                        emit(ConnectionResult.Message(readMessage))
                        println("Message from server: $readMessage")
                    } catch (e: IOException) {
                        emit(ConnectionResult.Error("Connection was interrupted"))
                        isRunning = false
                        break
                    }
                }
            }

            currentClientSocket?.let {
                println("Connection was interrupted")
                currentClientSocket?.close()
            }

        }.onCompletion {
            println("onCompletion - throwable: ${it?.localizedMessage}")
            closeConnection()
        }.flowOn(Dispatchers.IO)
    }

    override fun closeConnection() {
        currentClientSocket?.close()
        currentServerSocket?.close()
        currentClientSocket = null
        currentServerSocket = null
    }

    override fun release() {
        context.unregisterReceiver(foundDeviceBroadcastReceiver)
        context.unregisterReceiver(bluetoothStateBroadcastReceiver)
        closeConnection()
    }

    private fun updatePairedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                return
            }
        }
        bluetoothAdapter
            ?.bondedDevices
            ?.map { bluetoothDevice ->
                bluetoothDevice.toBluetoothDeviceDomain()
            }
            ?.also { devices ->
                _pairedDevices.update { devices }
            }
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val SERVICE_UUID = "27b7d1da-08c7-4505-a6d1-2459987e5e2d"
    }
}