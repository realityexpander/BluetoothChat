package com.plcoding.bluetoothchat.domain.chat

import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BluetoothController {
    val isConnected: StateFlow<Boolean>
    val scannedDevices: StateFlow<List<BluetoothDevice>>
//    val scannedDevices: SnapshotStateList<BluetoothDevice>
    val pairedDevices: StateFlow<List<BluetoothDevice>>
    val errors: SharedFlow<String>

    val messageSendToClientStateFlow: MutableStateFlow<String>
    val messageSendToServerStateFlow: MutableStateFlow<String>
    val messageReceiveStateFlow: MutableStateFlow<String>
    val messageSendToClientSharedFlow: MutableSharedFlow<String>

    fun startDiscovery()
    fun stopDiscovery()

    fun startBluetoothServer(): Flow<ConnectionResult>
//@OptIn(InternalCoroutinesApi::class)
//fun startBluetoothServer(): ChannelFlow<ConnectionResult>

    fun connectToDevice(device: BluetoothDevice): Flow<ConnectionResult>
//@OptIn(InternalCoroutinesApi::class)
//fun connectToDevice(device: BluetoothDevice): ChannelFlow<ConnectionResult>

    fun closeConnection()
    fun release()

    fun refreshDeviceList()
    fun clearDeviceList()
}