package com.plcoding.bluetoothchat.domain.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BluetoothController {
    val isConnected: StateFlow<Boolean>
    val scannedDevices: StateFlow<List<BluetoothDevice>>
    val pairedDevices: StateFlow<List<BluetoothDevice>>
    val errors: SharedFlow<String>

    val messageSendToClientStateFlow: MutableStateFlow<String>
    val messageSendToServerStateFlow: MutableStateFlow<String>
    val messageReceiveStateFlow: MutableStateFlow<String>
    val messageSendToClientSharedFlow: MutableSharedFlow<String>

    fun startDiscovery()
    fun stopDiscovery()

    fun startServer(): Flow<ConnectionResult>
    fun connectToServer(device: BluetoothDevice): Flow<ConnectionResult>

    fun closeAllConnections()
    fun closeClientConnection()
    fun closeServerConnection()
    fun release()

    fun refreshDeviceList()
    fun clearDeviceList()
}