package com.droidservicepro

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class UsbModemReport(
    val interfaces: String,
    val response: String?,
    val note: String
)

internal fun probeUsbModem(manager: UsbManager, device: UsbDevice): UsbModemReport {
    val interfaceSummary = (0 until device.interfaceCount).joinToString("\n") { index ->
        val usbInterface = device.getInterface(index)
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
            .joinToString(", ") { endpoint ->
                val direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                "$direction type=${endpoint.type} maxPacket=${endpoint.maxPacketSize}"
            }
        "Interface $index class=${usbInterface.interfaceClass}/${usbInterface.interfaceSubclass}/${usbInterface.interfaceProtocol}" +
            if (endpoints.isEmpty()) "" else "\n  $endpoints"
    }

    val dataInterface = (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
        it.interfaceClass == 10 &&
            bulkEndpoint(it, UsbConstants.USB_DIR_IN) != null &&
            bulkEndpoint(it, UsbConstants.USB_DIR_OUT) != null
    } ?: return UsbModemReport(interfaceSummary, null, "CDC data interface not found")

    val controlInterface = (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
        it.interfaceClass == 2
    }
    val connection = manager.openDevice(device)
        ?: return UsbModemReport(interfaceSummary, null, "USB permission or open failed")
    return try {
        if (!connection.claimInterface(dataInterface, false)) {
            return UsbModemReport(interfaceSummary, null, "CDC data interface claim failed")
        }
        try {
            if (controlInterface != null) {
                // CDC ACM control requests are device control transfers addressed to
                // the interface. Claiming the interrupt/control interface itself can
                // fail on Android even when the bulk data interface is available.
                setLineCoding(connection, controlInterface, 115200)
                setControlLines(connection, controlInterface, true)
            }
            val output = requireNotNull(bulkEndpoint(dataInterface, UsbConstants.USB_DIR_OUT))
            val input = requireNotNull(bulkEndpoint(dataInterface, UsbConstants.USB_DIR_IN))
            val command = "AT\r".toByteArray(Charsets.US_ASCII)
            if (connection.bulkTransfer(output, command, command.size, 1500) != command.size) {
                return UsbModemReport(interfaceSummary, null, "AT write failed")
            }
            val response = readResponse(connection, input)
            UsbModemReport(
                interfaceSummary,
                response,
                if (response.contains("OK", ignoreCase = true)) "AT modem responds" else "No AT OK response"
            )
        } finally {
            connection.releaseInterface(dataInterface)
        }
    } catch (error: Exception) {
        UsbModemReport(interfaceSummary, null, "USB modem error: ${error.javaClass.simpleName}")
    } finally {
        connection.close()
    }
}

private fun bulkEndpoint(intf: UsbInterface, direction: Int): UsbEndpoint? =
    (0 until intf.endpointCount).map(intf::getEndpoint).firstOrNull {
        it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
            it.direction == direction &&
            it.maxPacketSize > 0
    }

private fun setLineCoding(connection: UsbDeviceConnection, intf: UsbInterface, baud: Int) {
    val coding = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(baud).put(0).put(0).put(8).array()
    connection.controlTransfer(0x21, 0x20, 0, intf.id, coding, coding.size, 1000)
}

private fun setControlLines(connection: UsbDeviceConnection, intf: UsbInterface, enabled: Boolean) {
    connection.controlTransfer(0x21, 0x22, if (enabled) 3 else 0, intf.id, null, 0, 1000)
}

private fun readResponse(
    connection: UsbDeviceConnection,
    input: UsbEndpoint
): String {
    val bytes = ByteArray(512)
    val response = StringBuilder()
    val stopAt = android.os.SystemClock.elapsedRealtime() + 2500
    while (android.os.SystemClock.elapsedRealtime() < stopAt && response.length < 1024) {
        val count = connection.bulkTransfer(input, bytes, bytes.size, 250)
        if (count > 0) response.append(String(bytes, 0, count, Charsets.US_ASCII))
        if (response.contains("OK") || response.contains("ERROR")) break
    }
    return response.toString().replace("\r", "\\r").replace("\n", "\\n")
}
