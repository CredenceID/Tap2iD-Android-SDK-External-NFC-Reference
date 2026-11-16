package com.credenceid.tap2id.reference.reader

import android.app.Activity
import android.content.Context
import com.credenceid.tap2id.reference.reader.ccid.Ccid
import com.credenceid.tap2id.reference.reader.ccid.CcidSlot
import com.credenceid.tap2id.reference.reader.ccid.FakeCcidReaderDevice
import com.credenceid.tap2id.reference.reader.ccid.FakeCcidReaderDevice.Companion.STATUS_OK
import com.credenceid.tap2idSdk.api.reader.CardPresenceListener
import com.credenceid.tap2idSdk.api.reader.ReaderAvailability
import com.credenceid.tap2idSdk.api.reader.ReaderException
import com.credenceid.tap2idSdk.api.reader.ReaderException.Reason
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs [UsbCcidReader] against a simulated CCID reader, through the same sequence of calls the
 * Tap2iD SDK makes: attach, availability, startDetection, transceive, release.
 */
class UsbCcidReaderTest {
    private val activity = Activity()
    private val context: Context = activity
    private val links = FakeLinkFactory()
    private val reader = UsbCcidReader(links, pollIntervalMs = 10)
    private val listener = RecordingListener()
    private val background = Executors.newCachedThreadPool()

    @After
    fun tearDown() {
        reader.release()
        background.shutdownNow()
    }

    // region A tap

    @Test
    fun `a phone that taps is detected, powered on and answers an APDU`() {
        // Arrange
        links.device.card = { apdu -> apdu + STATUS_OK }
        reader.attach(activity)
        reader.startDetection(listener)

        // Act
        links.device.cardPresent = true
        val event = listener.next()
        val response = reader.transceive(SELECT, TIMEOUT_MS)

        // Assert
        assertEquals("detected", event)
        assertArrayEquals(SELECT + STATUS_OK, response)
        assertTrue("the card is powered on before it is reported", Ccid.ICC_POWER_ON in links.device.commands)
    }

    @Test
    fun `a phone that leaves and comes back is reported each time`() {
        // Arrange
        reader.attach(activity)
        reader.startDetection(listener)

        // Act
        links.device.cardPresent = true
        val first = listener.next()
        links.device.cardPresent = false
        val second = listener.next()
        links.device.cardPresent = true
        val third = listener.next()

        // Assert
        assertEquals(listOf("detected", "removed", "detected"), listOf(first, second, third))
    }

    @Test
    fun `a phone resting on the reader is reported once`() {
        // Arrange
        reader.attach(activity)
        reader.startDetection(listener)
        links.device.cardPresent = true
        listener.next()

        // Act: several polling passes go by.
        val extra = listener.next(waitMs = 200)

        // Assert
        assertNull(extra)
    }

    @Test
    fun `a reader unplugged while waiting for a phone is reported to the SDK`() {
        // Arrange
        reader.attach(activity)
        reader.startDetection(listener)

        // Act
        links.device.unplugged = true

        // Assert: once, and polling has stopped.
        assertEquals("error READER_DISCONNECTED", listener.next())
        assertNull(listener.next(waitMs = 200))
    }

    // endregion

    // region Failures during an exchange

    @Test
    fun `a phone that leaves mid-exchange is CARD_LOST`() {
        // Arrange
        reader.attach(activity)
        links.device.cardPresent = false

        // Act
        val thrown = assertThrows(ReaderException::class.java) { reader.transceive(SELECT, TIMEOUT_MS) }

        // Assert
        assertEquals(Reason.CARD_LOST, thrown.reason)
    }

    @Test
    fun `a phone that does not answer in time is TIMEOUT`() {
        // Arrange
        reader.attach(activity)
        links.device.cardPresent = true
        links.device.card = { null }

        // Act
        val thrown = assertThrows(ReaderException::class.java) { reader.transceive(SELECT, SHORT_TIMEOUT_MS) }

        // Assert
        assertEquals(Reason.TIMEOUT, thrown.reason)
    }

    @Test
    fun `an unplugged reader is READER_DISCONNECTED`() {
        // Arrange
        reader.attach(activity)
        links.device.unplugged = true

        // Act
        val thrown = assertThrows(ReaderException::class.java) { reader.transceive(SELECT, TIMEOUT_MS) }

        // Assert
        assertEquals(Reason.READER_DISCONNECTED, thrown.reason)
    }

    @Test
    fun `a command the reader refuses stays a plain IOException`() {
        // Arrange
        reader.attach(activity)
        links.device.cardPresent = true
        links.device.apduError = FakeCcidReaderDevice.CMD_NOT_SUPPORTED

        // Act
        val thrown = assertThrows(IOException::class.java) { reader.transceive(SELECT, TIMEOUT_MS) }

        // Assert
        assertFalse(thrown is ReaderException)
    }

    // endregion

    // region Release

    @Test
    fun `release makes a waiting transceive throw RELEASED at once`() {
        // Arrange: a card that never answers and a timeout far longer than the test.
        reader.attach(activity)
        links.device.cardPresent = true
        val waiting = CountDownLatch(1)
        links.device.card = {
            waiting.countDown()
            null
        }
        val exchange = background.submit(Callable { reader.transceive(SELECT, LONG_TIMEOUT_MS) })
        assertTrue(waiting.await(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))

        // Act
        reader.release()

        // Assert
        val thrown = assertThrows(ExecutionException::class.java) { exchange.get(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS) }
        assertEquals(Reason.RELEASED, (thrown.cause as ReaderException).reason)
    }

    @Test
    fun `release stops polling and closes the reader`() {
        // Arrange
        reader.attach(activity)
        reader.startDetection(listener)

        // Act
        reader.release()
        links.device.cardPresent = true

        // Assert
        assertNull(listener.next(waitMs = 200))
        assertTrue(links.device.closed)
    }

    @Test
    fun `a second release does nothing`() {
        // Arrange
        reader.attach(activity)
        reader.release()
        val commandsAfterFirst = links.device.commands.size

        // Act
        reader.release()

        // Assert
        assertEquals(commandsAfterFirst, links.device.commands.size)
    }

    @Test
    fun `a transceive after release throws RELEASED`() {
        // Arrange
        reader.attach(activity)
        reader.release()

        // Act
        val thrown = assertThrows(ReaderException::class.java) { reader.transceive(SELECT, TIMEOUT_MS) }

        // Assert
        assertEquals(Reason.RELEASED, thrown.reason)
    }

    @Test
    fun `the reader attaches and works again after a release`() {
        // Arrange
        reader.attach(activity)
        reader.release()

        // Act
        reader.attach(activity)
        reader.startDetection(listener)
        links.device.cardPresent = true
        val event = listener.next()
        val response = reader.transceive(SELECT, TIMEOUT_MS)

        // Assert
        assertEquals("detected", event)
        assertArrayEquals(STATUS_OK, response)
        assertEquals(2, links.opened)
    }

    // endregion

    // region Availability and identity

    @Test
    fun `an open reader is AVAILABLE`() {
        // Arrange
        reader.attach(activity)

        // Act + Assert
        assertEquals(ReaderAvailability.AVAILABLE, reader.availability(context))
    }

    @Test
    fun `no reader plugged in is DISCONNECTED and attach does not throw`() {
        // Arrange
        links.failure = ReaderUnavailableException(ReaderAvailability.DISCONNECTED, "No USB smart card reader is plugged in")

        // Act
        reader.attach(activity)

        // Assert
        assertEquals(ReaderAvailability.DISCONNECTED, reader.availability(context))
    }

    @Test
    fun `a reader held by another app is BUSY`() {
        // Arrange
        links.failure = ReaderUnavailableException(ReaderAvailability.BUSY, "in use by another app")

        // Act
        reader.attach(activity)

        // Assert
        assertEquals(ReaderAvailability.BUSY, reader.availability(context))
    }

    @Test
    fun `readerInfo names the plugged-in reader and this adapter`() {
        // Arrange + Act
        val info = reader.readerInfo

        // Assert
        assertEquals("HID OMNIKEY", info.vendor)
        assertEquals("5427 CK", info.model)
        assertEquals("1.00", info.firmwareVersion)
        assertEquals(UsbCcidReader.ADAPTER_VERSION, info.adapterVersion)
    }

    @Test
    fun `readerInfo still answers when no reader is plugged in`() {
        // Arrange
        links.identity = null

        // Act
        val info = reader.readerInfo

        // Assert
        assertEquals("CCID contactless reader", info.model)
        assertNull(info.firmwareVersion)
    }

    // endregion

    /** Opens a simulated reader, or fails the way a missing or busy one does. */
    private class FakeLinkFactory : CcidLinkFactory {
        var device = FakeCcidReaderDevice()
        var identity: ReaderIdentity? = ReaderIdentity("HID OMNIKEY", "5427 CK", "1.00")
        var failure: ReaderUnavailableException? = null
        var opened = 0

        override fun attachedReader(): ReaderIdentity? = identity

        override fun open(activity: Activity): CcidLink {
            failure?.let { throw it }
            opened++
            // A reader that was closed is a new connection the next time it is opened.
            if (device.closed) device = FakeCcidReaderDevice().also { it.card = device.card }
            val transport = device
            return object : CcidLink {
                override val identity = this@FakeLinkFactory.identity!!
                override val slot = CcidSlot(transport)

                override fun close() = transport.close()
            }
        }
    }

    private class RecordingListener : CardPresenceListener {
        private val events = LinkedBlockingQueue<String>()

        override fun onCardDetected() {
            events += "detected"
        }

        override fun onCardRemoved() {
            events += "removed"
        }

        override fun onReaderError(error: ReaderException) {
            events += "error ${error.reason}"
        }

        fun next(waitMs: Long = TIMEOUT_MS.toLong()): String? = events.poll(waitMs, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val TIMEOUT_MS = 2_000
        const val SHORT_TIMEOUT_MS = 200
        const val LONG_TIMEOUT_MS = 60_000

        val SELECT = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)
    }
}
