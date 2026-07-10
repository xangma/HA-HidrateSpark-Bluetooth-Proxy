package dev.xangma.hidratespark.healthconnect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

class BottleGattClient(
    context: Context,
    private val settings: BottleSettings,
    private val sipStore: SipStore,
) {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    suspend fun collectBufferedSips(): Int {
        if (!BluetoothPermissions.hasConnectPermission(appContext)) {
            throw BluetoothPermissionRequiredException()
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("Bluetooth is not supported on this phone")
        if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")
        val device = try {
            adapter.getRemoteDevice(settings.address)
        } catch (error: IllegalArgumentException) {
            throw IOException("The saved bottle address is invalid", error)
        }

        val session = GattSession()
        val gatt = device.connectGatt(
            appContext,
            false,
            session.callback,
            BluetoothDevice.TRANSPORT_LE,
        ) ?: throw IOException("Could not start the bottle connection")
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { session.connected.await() }
            session.discoverServices(gatt)
            val dataCharacteristic = session.prepareDataCharacteristic(gatt)
            delay(SUBSCRIPTION_SETTLE_MS)
            return session.drain(gatt, dataCharacteristic)
        } catch (error: TimeoutCancellationException) {
            throw IOException("Timed out while connecting to the bottle", error)
        } finally {
            runCatching { gatt.disconnect() }
            gatt.close()
        }
    }

    private inner class GattSession {
        val connected = CompletableDeferred<Unit>()
        private val servicesDiscovered = CompletableDeferred<Int>()
        private val notifications = Channel<Notification>(Channel.UNLIMITED)
        private val operationLock = Any()
        @Volatile
        private var pendingCharacteristicWrite: PendingWrite? = null
        @Volatile
        private var pendingDescriptorWrite: PendingWrite? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS &&
                    newState == BluetoothProfile.STATE_CONNECTED
                ) {
                    connected.complete(Unit)
                    return
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED ||
                    status != BluetoothGatt.GATT_SUCCESS
                ) {
                    fail(IOException("Bottle disconnected (Bluetooth status $status)"))
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                servicesDiscovered.complete(status)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                completeWrite(pendingCharacteristicWrite, characteristic.uuid, status)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                completeWrite(pendingDescriptorWrite, descriptor.uuid, status)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    notify(characteristic.uuid, characteristic.value?.copyOf() ?: byteArrayOf())
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                notify(characteristic.uuid, value.clone())
            }
        }

        @SuppressLint("MissingPermission")
        suspend fun discoverServices(gatt: BluetoothGatt) {
            if (!gatt.discoverServices()) throw IOException("Could not discover bottle services")
            val status = withTimeout(OPERATION_TIMEOUT_MS) { servicesDiscovered.await() }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw IOException("Bottle service discovery failed (status $status)")
            }
        }

        suspend fun prepareDataCharacteristic(
            gatt: BluetoothGatt,
        ): BluetoothGattCharacteristic {
            return try {
                for (command in BleProtocol.handshakeCommands) {
                    writeCharacteristic(
                        gatt,
                        requireCharacteristic(gatt, command.characteristic),
                        command.payload,
                    )
                    delay(HANDSHAKE_INTERVAL_MS)
                }
                requireCharacteristic(gatt, BleProtocol.USER_DATA).also {
                    enableNotifications(gatt, it)
                    Log.i(TAG, "Using modern USER_DATA protocol for ${settings.address}")
                }
            } catch (error: IOException) {
                prepareLegacyDataCharacteristic(gatt, error)
            } catch (error: TimeoutCancellationException) {
                prepareLegacyDataCharacteristic(gatt, error)
            }
        }

        private suspend fun prepareLegacyDataCharacteristic(
            gatt: BluetoothGatt,
            modernError: Exception,
        ): BluetoothGattCharacteristic {
            Log.w(TAG, "Modern handshake unavailable; trying legacy protocol", modernError)
            return requireCharacteristic(gatt, BleProtocol.DATA_POINT).also {
                enableNotifications(gatt, it)
                Log.i(TAG, "Using legacy DATA_POINT protocol for ${settings.address}")
            }
        }

        suspend fun drain(
            gatt: BluetoothGatt,
            dataCharacteristic: BluetoothGattCharacteristic,
        ): Int {
            var inserted = 0
            var lastFrame: ByteArray? = null
            var repeated = 0
            writeCharacteristic(gatt, dataCharacteristic, BleProtocol.DRAIN_COMMAND)

            while (true) {
                val notification = withTimeoutOrNull(NOTIFICATION_IDLE_MS) {
                    notifications.receive()
                } ?: break
                if (notification.characteristic != dataCharacteristic.uuid) continue
                val frame = notification.value
                val remaining = BleProtocol.pendingRecords(frame) ?: continue
                if (remaining == 0) break

                if (lastFrame?.contentEquals(frame) == true) {
                    repeated += 1
                } else {
                    repeated = 0
                    lastFrame = frame
                }

                BleProtocol.parseSipFrame(frame, settings.sizeMl)?.let { parsed ->
                    if (sipStore.recordSip(settings, parsed)) inserted += 1
                    Log.i(
                        TAG,
                        "Sip ${parsed.volumeMl}mL, ${remaining} record(s) pending, raw=${frame.toHex()}",
                    )
                }

                if (repeated >= BleProtocol.MAX_IDENTICAL_FRAMES) {
                    Log.w(TAG, "Bottle repeated one frame; stopping drain at raw=${frame.toHex()}")
                    break
                }

                // The local transaction above commits before this acknowledges
                // and pops the current record from the bottle's queue.
                writeCharacteristic(gatt, dataCharacteristic, BleProtocol.DRAIN_COMMAND)
            }
            return inserted
        }

        @SuppressLint("MissingPermission")
        private suspend fun enableNotifications(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                throw IOException("Could not enable bottle notifications")
            }
            val descriptor = characteristic.getDescriptor(BleProtocol.CLIENT_CHARACTERISTIC_CONFIG)
                ?: throw IOException("Bottle notification descriptor is missing")
            val value = if (
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            ) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            writeDescriptor(gatt, descriptor, value)
        }

        private fun requireCharacteristic(
            gatt: BluetoothGatt,
            uuid: UUID,
        ): BluetoothGattCharacteristic = gatt.services.asSequence()
            .flatMap { it.characteristics.asSequence() }
            .firstOrNull { it.uuid == uuid }
            ?: throw IOException("Bottle characteristic $uuid is unavailable")

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        private suspend fun writeCharacteristic(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val pending = PendingWrite(characteristic.uuid)
            synchronized(operationLock) {
                check(pendingCharacteristicWrite == null) { "A GATT write is already pending" }
                pendingCharacteristicWrite = pending
            }
            try {
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic,
                        value,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    characteristic.value = value
                    gatt.writeCharacteristic(characteristic)
                }
                if (!started) throw IOException("Bottle rejected a GATT write request")
                val status = withTimeout(OPERATION_TIMEOUT_MS) { pending.result.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    throw IOException("Bottle GATT write failed (status $status)")
                }
            } finally {
                synchronized(operationLock) {
                    if (pendingCharacteristicWrite === pending) pendingCharacteristicWrite = null
                }
            }
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        private suspend fun writeDescriptor(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            value: ByteArray,
        ) {
            val pending = PendingWrite(descriptor.uuid)
            synchronized(operationLock) {
                check(pendingDescriptorWrite == null) { "A GATT descriptor write is already pending" }
                pendingDescriptorWrite = pending
            }
            try {
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    descriptor.value = value
                    gatt.writeDescriptor(descriptor)
                }
                if (!started) throw IOException("Bottle rejected notification setup")
                val status = withTimeout(OPERATION_TIMEOUT_MS) { pending.result.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    throw IOException("Bottle notification setup failed (status $status)")
                }
            } finally {
                synchronized(operationLock) {
                    if (pendingDescriptorWrite === pending) pendingDescriptorWrite = null
                }
            }
        }

        private fun completeWrite(pending: PendingWrite?, uuid: UUID, status: Int) {
            if (pending?.uuid == uuid) pending.result.complete(status)
        }

        private fun notify(uuid: UUID, value: ByteArray) {
            notifications.trySend(Notification(uuid, value))
        }

        private fun fail(error: IOException) {
            if (!connected.isCompleted) connected.completeExceptionally(error)
            if (!servicesDiscovered.isCompleted) servicesDiscovered.completeExceptionally(error)
            synchronized(operationLock) {
                pendingCharacteristicWrite?.result?.completeExceptionally(error)
                pendingDescriptorWrite?.result?.completeExceptionally(error)
            }
            notifications.close(error)
        }
    }

    private data class PendingWrite(
        val uuid: UUID,
        val result: CompletableDeferred<Int> = CompletableDeferred(),
    )

    private data class Notification(val characteristic: UUID, val value: ByteArray)

    private fun ByteArray.toHex(): String = joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    companion object {
        private const val TAG = "HidrateSparkBLE"
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val OPERATION_TIMEOUT_MS = 7_000L
        private const val NOTIFICATION_IDLE_MS = 7_000L
        private const val HANDSHAKE_INTERVAL_MS = 50L
        private const val SUBSCRIPTION_SETTLE_MS = 100L
    }
}
