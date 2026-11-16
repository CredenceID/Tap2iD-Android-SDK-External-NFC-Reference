package com.credenceid.tap2id.reference.reader.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import com.credenceid.tap2id.reference.reader.ccid.Ccid
import com.credenceid.tap2id.reference.reader.ccid.CcidTransport
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * CCID messages over the bulk endpoints of an open USB reader.
 *
 * Reading uses one asynchronous [UsbRequest] that stays queued across calls. That is what makes two
 * things work that a blocking `bulkTransfer` cannot do: a wait that times out loses no data, because
 * the request is still pending for the next call, and [close] can end a wait from another thread by
 * cancelling the request.
 *
 * @param connection the open device
 * @param usbInterface the claimed smart card interface
 * @param input the bulk endpoint the reader answers on
 * @param output the bulk endpoint commands go to
 * @param maxMessageSize the longest message the reader sends
 * @param isAttached whether the reader is still plugged in
 */
internal class UsbCcidTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
    maxMessageSize: Int,
    private val isAttached: () -> Boolean,
) : CcidTransport {
    private val readBuffer = ByteBuffer.allocate(maxMessageSize.coerceIn(Ccid.HEADER_SIZE, MAX_READ_SIZE))
    private val readRequest = UsbRequest().apply { initialize(connection, input) }

    // Held while a thread is inside the connection, so close() never closes it under that thread.
    private val inUse = ReentrantLock()

    @Volatile private var readPending = false

    @Volatile private var closed = false

    override fun send(message: ByteArray) =
        inUse.withLock {
            if (closed) throw IOException(CLOSED)
            var sent = 0
            while (sent < message.size) {
                val count = connection.bulkTransfer(output, message, sent, message.size - sent, WRITE_TIMEOUT_MS)
                if (count <= 0) throw IOException(if (isAttached()) "Writing to the USB reader failed" else UNPLUGGED)
                sent += count
            }
            // USB ends a transfer on a short packet, so a message that fills its last packet needs an empty one.
            if (message.size % output.maxPacketSize == 0) {
                connection.bulkTransfer(output, message, 0, 0, WRITE_TIMEOUT_MS)
            }
        }

    override fun receive(timeoutMs: Int): ByteArray? =
        inUse.withLock {
            if (closed) throw IOException(CLOSED)
            if (!readPending) {
                readBuffer.clear()
                if (!readRequest.queue(readBuffer)) {
                    throw IOException(if (isAttached()) "Reading from the USB reader failed" else UNPLUGGED)
                }
                readPending = true
            }
            val finished =
                try {
                    connection.requestWait(timeoutMs.coerceAtLeast(1).toLong())
                } catch (stillWaiting: TimeoutException) {
                    return null
                }
            readPending = false
            if (closed) throw IOException(CLOSED)
            if (finished == null || !isAttached()) throw IOException(UNPLUGGED)
            val message = ByteArray(readBuffer.position())
            readBuffer.flip()
            readBuffer.get(message)
            // An empty transfer carries nothing; the caller waits again for the real message.
            if (message.isEmpty()) null else message
        }

    override fun close() {
        if (closed) return
        closed = true
        // Ends a receive() that is waiting on another thread, then waits for it to leave.
        readRequest.cancel()
        val idle = inUse.tryLock(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        try {
            connection.releaseInterface(usbInterface)
            readRequest.close()
            connection.close()
        } finally {
            if (idle) inUse.unlock()
        }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 1_000
        const val CLOSE_WAIT_MS = 2_000L
        const val MAX_READ_SIZE = 16_384
        const val CLOSED = "The USB reader is closed"
        const val UNPLUGGED = "The USB reader was unplugged"
    }
}
