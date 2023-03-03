package com.plcoding.bluetoothchat.presentation

import androidx.compose.runtime.toMutableStateList
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

    private var deviceConnectionJob: Job? = null

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

    @OptIn(InternalCoroutinesApi::class)
    fun connectToDevice(device: BluetoothDeviceDomain) {
        _state.update { it.copy(isConnecting = true) }
        deviceConnectionJob = bluetoothController
            .connectToDevice(device)
            .listen()
    }

    fun disconnectFromDevice() {
        deviceConnectionJob?.cancel()
        bluetoothController.closeConnection()

        _state.update { it.copy(
            isConnecting = false,
            isConnected = false
        ) }
    }

    fun waitForIncomingConnections() {
        _state.update { it.copy(isConnecting = true) }

        deviceConnectionJob = bluetoothController
            .startBluetoothServer()
            .listen()

        println("Server Job Started - Waiting for incoming connections...")
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

    private fun Flow<ConnectionResult>.listen(): Job {
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

            bluetoothController.closeConnection()
            _state.update { it.copy(
                isConnected = false,
                isConnecting = false,
            ) }
        }
        .launchIn(viewModelScope)
    }

    override fun onCleared() {
        super.onCleared()
        if(deviceConnectionJob?.isActive == true) {
            deviceConnectionJob?.cancel()
        }
        if(bluetoothController.isConnected.value) {
            bluetoothController.closeConnection()
            bluetoothController.release()
        }
    }
}