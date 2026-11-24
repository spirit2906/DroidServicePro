package com.droidservicepro

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.RSAPublicKey

internal enum class AdbFailure {
    INTERFACE, OPEN, TRANSFER, AUTHORIZATION, PROTOCOL, SHELL, KEY
}

internal class AdbException(val reason: AdbFailure) : Exception()

internal fun adbInterface(device: UsbDevice): UsbInterface? =
    (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
        it.interfaceClass == 255 && it.interfaceSubclass == 66 && it.interfaceProtocol == 1 &&
            endpoint(it, UsbConstants.USB_DIR_IN) != null &&
            endpoint(it, UsbConstants.USB_DIR_OUT) != null
    }

private fun endpoint(intf: UsbInterface, direction: Int): UsbEndpoint? =
    (0 until intf.endpointCount).map(intf::getEndpoint).firstOrNull {
        it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == direction && it.maxPacketSize > 0
    }

internal fun readUsbDiagnostics(
    manager: UsbManager,
    device: UsbDevice,
    authorization: () -> Unit
): DeviceReport {
    val intf = adbInterface(device) ?: throw AdbException(AdbFailure.INTERFACE)
    val connection = manager.openDevice(device) ?: throw AdbException(AdbFailure.OPEN)
    try {
        if (!connection.claimInterface(intf, false)) throw AdbException(AdbFailure.OPEN)
        try {
            val adb = UsbAdb(
                connection, requireNotNull(endpoint(intf, UsbConstants.USB_DIR_IN)),
                requireNotNull(endpoint(intf, UsbConstants.USB_DIR_OUT))
            )
            adb.connect(authorization)
            val report = DeviceReport.fromProperties(adb.readProperties())
            if (report.model == null && report.android == null) throw AdbException(AdbFailure.SHELL)
            return report
        } finally {
            connection.releaseInterface(intf)
        }
    } finally {
        connection.close()
    }
}

private class UsbAdb(
    private val connection: UsbDeviceConnection,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint
) {
    private data class Packet(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private var deadline = SystemClock.elapsedRealtime() + 90_000
    private var payloadLimit = 4096

    fun connect(authorization: () -> Unit) {
        send(CNXN, VERSION, payloadLimit, "host::\u0000".toByteArray())
        var authStep = 0
        var key: AdbKey? = null
        while (true) {
            val packet = try {
                receive()
            } catch (error: AdbException) {
                if (authStep == 2 && error.reason == AdbFailure.TRANSFER) {
                    throw AdbException(AdbFailure.AUTHORIZATION)
                }
                throw error
            }
            when (packet.command) {
                CNXN -> {
                    if (packet.arg0 < VERSION || packet.arg1 <= 0) invalid()
                    payloadLimit = minOf(payloadLimit, packet.arg1)
                    return
                }
                AUTH -> {
                    if (packet.arg0 != 1 || packet.arg1 != 0 || packet.data.size != 20) invalid()
                    if (authStep == 2) throw AdbException(AdbFailure.AUTHORIZATION)
                    try {
                        val identity = key ?: AdbKey().also { key = it }
                        if (authStep == 0) {
                            val signature = identity.sign(packet.data)
                            send(AUTH, 2, 0, signature)
                        } else {
                            authorization()
                            send(AUTH, 3, 0, identity.publicKey())
                        }
                    } catch (error: java.security.GeneralSecurityException) {
                        throw AdbException(AdbFailure.KEY)
                    }
                    authStep++
                }
                else -> invalid()
            }
        }
    }

    fun readProperties(): String {
        deadline = SystemClock.elapsedRealtime() + 15_000
        val localId = 1
        send(OPEN, localId, 0, "shell:getprop\u0000".toByteArray())
        val opened = receive()
        if (opened.command == CLSE) throw AdbException(AdbFailure.SHELL)
        if (opened.command != OKAY || opened.arg1 != localId || opened.arg0 == 0) invalid()
        val remoteId = opened.arg0
        val result = ByteArrayOutputStream()
        while (true) {
            val packet = receive()
            if (packet.arg0 != remoteId || packet.arg1 != localId) invalid()
            when (packet.command) {
                WRTE -> {
                    if (result.size() + packet.data.size > 512 * 1024) invalid()
                    result.write(packet.data)
                    send(OKAY, localId, remoteId)
                }
                CLSE -> {
                    send(CLSE, localId, remoteId)
                    if (result.size() == 0) throw AdbException(AdbFailure.SHELL)
                    return result.toString(Charsets.UTF_8.name())
                }
                else -> invalid()
            }
        }
    }

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray = byteArrayOf()) {
        if (data.size > payloadLimit) invalid()
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(checksum(data)).putInt(command.inv()).array()
        write(header)
        if (data.isNotEmpty()) {
            write(data)
            // Older Linux ADB USB implementations expect termination on a packet boundary.
            if (data.size % output.maxPacketSize == 0) {
                if (connection.bulkTransfer(output, ByteArray(1), 0, timeout()) < 0) {
                    throw AdbException(AdbFailure.TRANSFER)
                }
            }
        }
    }

    private fun receive(): Packet {
        val header = ByteBuffer.wrap(read(24)).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val length = header.int
        val sum = header.int
        if (header.int != command.inv() || length !in 0..payloadLimit) invalid()
        val data = read(length)
        // Modern adbd sends checksum zero in its initial CNXN before negotiating version.
        if (sum != checksum(data) && !(command == CNXN && arg0 > VERSION && sum == 0)) invalid()
        return Packet(command, arg0, arg1, data)
    }

    private fun read(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        var emptyPackets = 0
        while (offset < size) {
            val count = connection.bulkTransfer(input, result, offset, size - offset, timeout())
            if (count < 0 || (count == 0 && ++emptyPackets > 1)) throw AdbException(AdbFailure.TRANSFER)
            offset += count
        }
        return result
    }

    private fun write(data: ByteArray) {
        if (connection.bulkTransfer(output, data, data.size, timeout()) != data.size) {
            throw AdbException(AdbFailure.TRANSFER)
        }
    }

    private fun timeout(): Int {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0 || Thread.currentThread().isInterrupted) {
            throw AdbException(AdbFailure.TRANSFER)
        }
        return remaining.toInt()
    }

    private fun checksum(data: ByteArray) = data.sumOf { it.toInt() and 0xff }
    private fun invalid(): Nothing = throw AdbException(AdbFailure.PROTOCOL)

    companion object {
        private const val VERSION = 0x01000000
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val WRTE = 0x45545257
        private const val CLSE = 0x45534c43
    }
}

private class AdbKey {
    private val entry: KeyStore.PrivateKeyEntry

    init {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        synchronized(AdbKey::class.java) {
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                            .setKeySize(2048)
                            .setDigests(KeyProperties.DIGEST_NONE)
                            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                            .build()
                    )
                    generateKeyPair()
                }
            }
            entry = store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        }
    }

    fun sign(token: ByteArray): ByteArray {
        // ADB supplies the SHA-1 digest, so sign its DigestInfo without hashing again.
        return java.security.Signature.getInstance("NONEwithRSA").run {
            initSign(entry.privateKey)
            update(SHA1_DIGEST_INFO)
            update(token)
            sign()
        }
    }

    fun publicKey(): ByteArray {
        val key = entry.certificate.publicKey as RSAPublicKey
        val modulus = key.modulus
        val word = BigInteger.ONE.shiftLeft(32)
        val n0inv = modulus.modInverse(word).negate().mod(word)
        val rr = BigInteger.ONE.shiftLeft(4096).mod(modulus)
        val encoded = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        encoded.putInt(64).putInt(n0inv.toInt())
        for (part in listOf(modulus, rr)) {
            repeat(64) { encoded.putInt(part.shiftRight(it * 32).toInt()) }
        }
        encoded.putInt(key.publicExponent.toInt())
        return (Base64.encodeToString(encoded.array(), Base64.NO_WRAP) +
            " droidservicepro@android\u0000").toByteArray()
    }

    companion object {
        private const val ALIAS = "droidservicepro.usb.adb.v1"
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
    }
}
