package com.plcoding.bluetoothchat.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plcoding.bluetoothchat.domain.chat.BluetoothController
import com.plcoding.bluetoothchat.domain.chat.BluetoothDeviceDomain
import com.plcoding.bluetoothchat.domain.chat.ConnectionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import javax.inject.Inject

@HiltViewModel
class BluetoothViewModel @Inject constructor(
    private val bluetoothController: BluetoothController
): ViewModel() {

    private val _state = MutableStateFlow(BluetoothUiState())
    val state = combine(
        bluetoothController.scannedDevices,
        bluetoothController.pairedDevices,
        _state
    ) { scannedDevices, pairedDevices, state ->
        state.copy(
            scannedDevices = scannedDevices,
            pairedDevices = pairedDevices
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _state.value)

    private var clientConnectionJob: Job? = null
    private var serverConnectionJob: Job? = null

    init {
        // Show connection status
        bluetoothController.isConnected.onEach { isConnected ->
            _state.update { it.copy(isConnected = isConnected) }
        }.launchIn(viewModelScope)

        // Show errors
        bluetoothController.errors.onEach { error ->
            _state.update { it.copy(
                errorMessage = error
            ) }
        }.launchIn(viewModelScope)

        // Show received messages
        bluetoothController.messageReceiveStateFlow.onEach { message ->
            _state.update { it.copy(
                messages = it.messages +"\n" + message
            ) }
        }.launchIn(viewModelScope)

        monitorMessages()
    }

    fun connectToServer(device: BluetoothDeviceDomain) {
        _state.update { it.copy(isConnecting = true) }

        clientConnectionJob?.cancel()
        clientConnectionJob = bluetoothController
            .connectToServer(device)
            .listen (
                onConnectionClosed = {
                    bluetoothController.closeClientConnection()
                }
            )

        println("Client Connected to Server - Now processing messages...")
    }

    fun disconnectFromServer() {
        clientConnectionJob?.cancel()
        //bluetoothController.closeConnection()

        _state.update { it.copy(
            isConnecting = false,
            isConnected = false
        ) }
    }

    fun shutdownServer() {
        serverConnectionJob?.cancel()
        //bluetoothController.closeConnection()

        _state.update { it.copy(
            isConnecting = false,
            //isConnected = false
        ) }

        println("Server shut down.")
    }

    fun serveIncomingConnections() {
        _state.update { it.copy(isConnecting = true) }

        serverConnectionJob?.cancel()
        serverConnectionJob = bluetoothController
            .startServer()
            .listen {
                bluetoothController.closeServerConnection()
            }

        println("Server Job Started - Now processing incoming connections...")
    }

    fun sendMessageToClient(message: String) {
//        bluetoothController.messageSendToClientStateFlow.update { message }
        viewModelScope.launch {
            bluetoothController.messageSendToClientSharedFlow.emit(message)
        }
    }

    fun refreshDeviceList() {
        bluetoothController.refreshDeviceList()
    }

    fun sendMessageToServer(message: String) {
        bluetoothController.messageSendToServerStateFlow.update { message }
    }

    fun startScan() {
        bluetoothController.clearDeviceList()
        bluetoothController.startDiscovery()
    }

    fun stopScan() {
        bluetoothController.stopDiscovery()
    }

    fun monitorMessages() {
        return

        state.onEach {
            if(it.messages != null) {
                val lastMsg = it.messages.split("\n").last()

                if(lastMsg.contains("from client")) {
                    sendMessageToServer("from server XXXXXXXXXXX YOLO")
                }
            }
        }.launchIn(viewModelScope)
    }

    private fun Flow<ConnectionResult>.listen(
        onConnectionClosed: () -> Unit = {}
    ): Job {
        return this.onEach { result ->
            when(result) {
                ConnectionResult.ConnectionEstablished -> {
                    _state.update { it.copy(
                        isConnected = true,
                        isConnecting = false,
                        errorMessage = null
                    ) }
                }
                is ConnectionResult.Message -> {
                    _state.update { it.copy(
                        isConnected = true,
                        isConnecting = false,
                        errorMessage = null,
                        messages = it.messages +"\n" + result.message
                    ) }
                }
                is ConnectionResult.Error -> {
                    _state.update { it.copy(
                        isConnected = false,
                        isConnecting = false,
                        errorMessage = result.message,
                        messages = it.messages +"\n" + result.message
                    ) }
                }
            }
        }
        .catch { throwable ->
            println("Error: ${throwable.localizedMessage}")

            onConnectionClosed()
            _state.update { it.copy(
                isConnected = false,
                isConnecting = false,
            ) }
        }
        .launchIn(viewModelScope)
    }

    override fun onCleared() {
        super.onCleared()
        if(clientConnectionJob?.isActive == true) {
            clientConnectionJob?.cancel()
        }
        if(bluetoothController.isConnected.value) {
            bluetoothController.closeAllConnections()
            bluetoothController.release()
        }
    }
}