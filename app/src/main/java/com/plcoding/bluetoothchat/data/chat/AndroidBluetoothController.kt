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
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.plcoding.bluetoothchat.domain.chat.BluetoothController
import com.plcoding.bluetoothchat.domain.chat.BluetoothDeviceDomain
import com.plcoding.bluetoothchat.domain.chat.ConnectionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
//    override val scannedDevices: SnapshotStateList<BluetoothDeviceDomain> = _scannedDevices

    private val _pairedDevices =
        MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    override val pairedDevices: StateFlow<List<BluetoothDeviceDomain>>
        get() = _pairedDevices.asStateFlow()

    private val _errors =
        MutableSharedFlow<String>()
    override val errors: SharedFlow<String>
        get() = _errors.asSharedFlow()

    override val messageSendToClientStateFlow =
        MutableStateFlow("")

    override val messageSendToClientSharedFlow =
        MutableSharedFlow<String>(0)

    override val messageSendToServerStateFlow =
        MutableStateFlow("")

    // When server is running, these are the messages from the client.
    override val messageReceiveStateFlow =
        MutableStateFlow("")

    private val foundDeviceBroadcastReceiver =
        FoundDeviceBroadcastReceiver(
            onDeviceFound = { device ->
                println("onDeviceFound: device=$device ${device.name}")
                _scannedDevices.update { devices ->
                    val newDevice = device.toBluetoothDeviceDomain()
                    if (newDevice in devices) {
                        devices.map {
                            it.copy()
                        }
                    } else
                        devices + newDevice
                }
            }
        )

    private val bluetoothStateBroadcastReceiver =
        BluetoothStateBroadcastReceiver(
            onStateChanged = { isConnected, bluetoothDevice ->
                println("onStateChanged: isConnected=$isConnected, bluetoothDevice=$bluetoothDevice ${bluetoothDevice.name}")

                _isConnected.update { isConnected }
                if (isConnected) {
                    _pairedDevices.update { devices ->
                        val newDevice = bluetoothDevice.toBluetoothDeviceDomain()
//                        if (newDevice in devices) devices else devices + newDevice
                        if (newDevice in devices)
                            devices.map {
                                it.copy()
                            }
                        else
                            devices + newDevice
                    }
                }
            }
        )

    private var currentServerSocket: BluetoothServerSocket? = null
    private var currentClientSocket: BluetoothSocket? = null

    private var isFoundDeviceBroadcastReceiverRegistered = false

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

        // Name Change test
//        CoroutineScope(Dispatchers.IO).launch {
//            bluetoothAdapter?.name = "BluetoothChat " + Random().nextInt()
//            var count = 0
//
//            while(true) {
//                delay(1000)
//                bluetoothAdapter?.name = "BluetoothChat " + count++
//            }
//        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun refreshDeviceList() {
        stopDiscovery()

//        CoroutineScope(Dispatchers.IO).launch {
//            _scannedDevices.update { emptyList() }
//            _pairedDevices.update { emptyList() }
//            yield()

        println(
            "bluetoothAdapter?.bondedDevices=${
                bluetoothAdapter?.bondedDevices?.map {
                    it.name
                }?.joinToString(", ")
            }"
        )

        // attempt to connect with each paired device to force list refresh
        _pairedDevices.value.forEach { pairedDevice ->

            CoroutineScope(Dispatchers.IO).launch {
                val device = BluetoothDeviceDomain(
                    name = "Name does not matter",
                    address = pairedDevice.address // must be a real device
                )
                val socket = bluetoothAdapter
                    ?.getRemoteDevice(device.address)
                    ?.createRfcommSocketToServiceRecord(
                        UUID.fromString(SERVICE_UUID)
                    )

                // Connect to the a real device to force list refresh
                try {
                    println("refreshDeviceList starting for ${pairedDevice.name}")

                    withContext(Dispatchers.IO) {
                        if (socket?.isConnected == true) {
                            socket.close()
                        }
                        yield()
                        withTimeout(50) {
                            socket?.connect()
                        }
                    }
                } catch (e: IOException) {
                    //e.printStackTrace()
                    println("IOException: ${e.message}")
                } finally {
                    socket?.close()
                }

                println("refreshDeviceList done for ${pairedDevice.name}")
                delay(250)
                startDiscovery()
            }
        }
    }

    override fun clearDeviceList() {
        _scannedDevices.update { emptyList() }
        _pairedDevices.update { emptyList() }
    }

    override fun startDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                return
            }
        }

        if(isFoundDeviceBroadcastReceiverRegistered) {
            context.unregisterReceiver(foundDeviceBroadcastReceiver)
        }
        isFoundDeviceBroadcastReceiverRegistered = true
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
    @OptIn(InternalCoroutinesApi::class)
        return flow {
            var error: ConnectionResult? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    throw SecurityException("No BLUETOOTH_CONNECT permission")
                }
            }

            // Create a new server socket
            currentServerSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(
                "chat_service",
                UUID.fromString(SERVICE_UUID)
            )
            emit(ConnectionResult.ConnectionEstablished)

            // Accept multiple connections
            var shouldLoop2 = true
            while (shouldLoop2) {
                val clientSocket = currentServerSocket?.accept()
                if (clientSocket == null) {
                    error = ConnectionResult.Error("Connection was interrupted (accept)")
                    shouldLoop2 = false
                    break
                }

                println("Connected to ${clientSocket.remoteDevice.name} (${clientSocket.remoteDevice.address})")

                // launch a coroutine for sending messages to client
                CoroutineScope(Dispatchers.IO).launch {

                    messageSendToClientSharedFlow.collect {
                        println("clientSocket=$clientSocket, messageSendToClientSharedFlow=$it")

                        val message = it
                        if (message.isNotEmpty()) {
                            val outputStream = clientSocket.outputStream

                            if(clientSocket.isConnected) {
                                @Suppress("BlockingMethodInNonBlockingContext")
                                outputStream?.write(message.toByteArray())
                            }
                        }
                    }

//                    while (true) {
//                        try {
//                            yield()
//                            val message = messageSendToClientStateFlow.value
//                            //val message = messageSendToClientSharedFlow.replayCache.firstOrNull() ?: ""
//                            if (message.isNotEmpty()) {
//                                val outputStream = clientSocket.outputStream
//                                @Suppress("BlockingMethodInNonBlockingContext")
//                                // maybe a random delay here?
//                                outputStream?.write(message.toByteArray())
////                                messageSendToClientStateFlow.value = ""
//                            }
//                        } catch (e: IOException) {
//                            error = ConnectionResult.Error("Connection was interrupted (send) ${e.localizedMessage}")
//                            //shouldLoop2 = false
//                            clientSocket.close()
//                            break
//                        }
//                    }
                }

                // Read message from client
                CoroutineScope(Dispatchers.IO).launch {

                    while (true) {
                        println("clientSocket=$clientSocket")

                        val inputStream = clientSocket.inputStream
                        val outputStream = clientSocket.outputStream
                        val buffer = ByteArray(1024)
                        var bytes: Int

                        try {
                            bytes = withContext(Dispatchers.IO) {
                                inputStream.read(buffer)
                            }
                            val readMessage = String(buffer, 0, bytes)
                            println("client: ${clientSocket.remoteDevice.address}readMessage: $readMessage")

                            // Update `messages` state flow for UI
                            messageReceiveStateFlow.update { readMessage }

                            // Send message back
                            val message = "from server: $readMessage"
                            yield()
                            withContext(Dispatchers.IO) {
                                outputStream.write(message.toByteArray())
                            }
                        } catch (e: IOException) {
                            error = ConnectionResult.Error("Connection was interrupted ${e.localizedMessage}")
                            clientSocket.close()
                            //shouldLoop2 = false
                            break
                        }
                    }
                }

                error?.let {
                    println("startBluetoothServer client END error=$error")
                    emit(it)
                }

                println("startBluetoothServer END clientSocket=$clientSocket, shouldLoop2=$shouldLoop2")

//                // Close the socket?
//                if(!shouldLoop2) {
//                    clientSocket.let {
//                        clientSocket.close()
//                    }
//                }
            }

            // after server error
            error?.let {
                println("startBluetoothServer END error=$error")
                emit(it)
            }

//            var shouldLoop = true
            var shouldLoop = false
            while (shouldLoop) {

                emit(ConnectionResult.ConnectionEstablished)

                // Accept a single connection
                currentClientSocket = try {
                    currentServerSocket?.accept()
                } catch (e: IOException) {
                    shouldLoop = false
                    null
                }

                // Communicate with a single client
                if (currentClientSocket?.isConnected == true) {
                        val socket = currentClientSocket!!
                        val inputStream = socket.inputStream
                        val outputStream = socket.outputStream
                        val buffer = ByteArray(1024)
                        var bytes: Int

                        emit(ConnectionResult.ConnectionEstablished)
//                    send(ConnectionResult.ConnectionEstablished)
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
                                    error =
                                        ConnectionResult.Error("Connection was interrupted (ping) ${e.localizedMessage}")
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
                                    messageSendToClientStateFlow.collectLatest { message ->
                                        outputStream.write(message.toByteArray())
                                    }
                                } catch (e: IOException) {
                                    error = ConnectionResult.Error("Connection was interrupted (send) ${e.localizedMessage}")
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
//                            send(ConnectionResult.Message(readMessage))

                                // Send message back
                                val message = "from server: $readMessage"
                                yield()
                                withContext(Dispatchers.IO) {
                                    outputStream.write(message.toByteArray())
                                }
                            } catch (e: IOException) {
                                error =
                                    ConnectionResult.Error("Connection was interrupted ${e.localizedMessage}")
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
            var error: ConnectionResult? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    throw SecurityException("No BLUETOOTH_CONNECT permission")
                }
            }
            stopDiscovery()

            currentClientSocket = bluetoothAdapter
                ?.getRemoteDevice(device.address)
                ?.createRfcommSocketToServiceRecord(
                    UUID.fromString(SERVICE_UUID)
                )
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
                    emit(ConnectionResult.Error("Connection was interrupted ${e.localizedMessage}"))
                }
            }

            // Communicate with the server
            if(currentClientSocket?.isConnected == true) {
                val socket = currentClientSocket!!
                val inputStream = socket.inputStream
                val outputStream = socket.outputStream
                val buffer = ByteArray(1024)
                var bytes: Int


                // send an initial hello message to server
                withContext(Dispatchers.IO) {
                    outputStream.write("Hello from Client".toByteArray())
                }

                var isRunning = true

                // Send messages from messageSendToServerStateFlow to server
                CoroutineScope(Dispatchers.IO).launch {
                    messageSendToServerStateFlow.collectLatest { message ->
                        outputStream ?: return@collectLatest

                        try {
                            if (socket.isConnected) {
                                //withContext(Dispatchers.IO) {
                                outputStream.write(message.toByteArray())
                                //}
                            }
                        } catch (e: IOException) {
                            error = ConnectionResult.Error("Connection was interrupted ${e.localizedMessage}")
                            isRunning = false
                        }
                    }
                }

                // get messages from server
                while (isRunning) {
                    try {
                        yield()
//                        bytes = withContext(Dispatchers.IO) {
//                            if(!socket.isConnected) {
//                                isRunning = false
//                                return@withContext 0
//                            }
//                            inputStream.read(buffer)
//                        }

                        if(!socket.isConnected) {
                            isRunning = false
                            break
                        }
                        bytes = inputStream.read(buffer)

                        val readMessage = String(buffer, 0, bytes)

                        emit(ConnectionResult.Message(readMessage))
                        println("Message from server: $readMessage")
                    } catch (e: IOException) {
                        error = ConnectionResult.Error("Connection was interrupted (send) ${e.localizedMessage}")
                        isRunning = false
                        break
                    }
                }
            }

            error?.let { emit(it) }

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
            ?.cancelDiscovery()

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