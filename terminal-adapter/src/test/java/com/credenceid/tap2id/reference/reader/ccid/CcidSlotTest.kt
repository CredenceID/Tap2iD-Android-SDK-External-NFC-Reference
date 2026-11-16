package com.credenceid.tap2id.reference.reader.ccid

import com.credenceid.tap2id.reference.reader.ccid.FakeCcidReaderDevice.Companion.ATR
import com.credenceid.tap2id.reference.reader.ccid.FakeCcidReaderDevice.Companion.STATUS_OK
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CcidSlotTest {
    private val device = FakeCcidReaderDevice()
    private val slot = CcidSlot(device)

    // region Message format

    @Test
    fun `a command carries its type, length, slot, sequence and data`() {
        // Arrange + Act
        val message = Ccid.command(Ccid.XFR_BLOCK, slot = 0, sequence = 7, data = SELECT)

        // Assert
        val header = byteArrayOf(0x6F, SELECT.size.toByte(), 0, 0, 0, 0, 7, 0, 0, 0)
        assertArrayEquals(header + SELECT, message)
    }

    @Test
    fun `a reply shorter than a header is a protocol error`() {
        // Arrange + Act + Assert
        assertThrows(CcidException.Protocol::class.java) { CcidReply.parse(ByteArray(4)) }
    }

    @Test
    fun `a reply that announces more data than it holds is a protocol error`() {
        // Arrange
        val truncated = Ccid.command(Ccid.DATA_BLOCK, slot = 0, sequence = 0, data = STATUS_OK).copyOf(Ccid.HEADER_SIZE + 1)

        // Act + Assert
        assertThrows(CcidException.Protocol::class.java) { CcidReply.parse(truncated) }
    }

    // endregion

    // region Card presence and power

    @Test
    fun `an empty field is reported as absent`() {
        // Arrange
        device.cardPresent = false

        // Act
        val status = slot.status()

        // Assert
        assertEquals(IccStatus.ABSENT, status)
    }

    @Test
    fun `a card is inactive until it is powered on`() {
        // Arrange
        device.cardPresent = true

        // Act
        val before = slot.status()
        val atr = slot.powerOn()
        val after = slot.status()

        // Assert
        assertEquals(IccStatus.INACTIVE, before)
        assertArrayEquals(ATR, atr)
        assertEquals(IccStatus.ACTIVE, after)
    }

    @Test
    fun `powering on an empty field reports the card as lost`() {
        // Arrange
        device.cardPresent = false

        // Act + Assert
        assertThrows(CcidException.CardLost::class.java) { slot.powerOn() }
    }

    // endregion

    // region APDU exchange

    @Test
    fun `an APDU reaches the card and its response comes back with the status bytes`() {
        // Arrange
        device.cardPresent = true
        device.card = { apdu -> apdu + STATUS_OK }

        // Act
        val response = slot.transmit(SELECT, TIMEOUT_MS)

        // Assert
        assertArrayEquals(SELECT + STATUS_OK, response)
    }

    @Test
    fun `a status other than 9000 is returned, not thrown`() {
        // Arrange
        device.cardPresent = true
        device.card = { FILE_NOT_FOUND }

        // Act
        val response = slot.transmit(SELECT, TIMEOUT_MS)

        // Assert
        assertArrayEquals(FILE_NOT_FOUND, response)
    }

    @Test
    fun `a card that asks for more time is waited for`() {
        // Arrange
        device.cardPresent = true
        device.timeExtensions = 3

        // Act
        val response = slot.transmit(SELECT, TIMEOUT_MS)

        // Assert
        assertArrayEquals(STATUS_OK, response)
    }

    @Test
    fun `a response split over several messages is put back together`() {
        // Arrange
        device.cardPresent = true
        val long = ByteArray(600) { it.toByte() } + STATUS_OK
        device.card = { long }
        device.responseChunkSize = 255

        // Act
        val response = slot.transmit(SELECT, TIMEOUT_MS)

        // Assert
        assertArrayEquals(long, response)
        assertEquals(3, device.commands.count { it == Ccid.XFR_BLOCK })
    }

    @Test
    fun `a late reply to an earlier command is skipped`() {
        // Arrange: sequence 200 answers nothing this slot has asked.
        device.cardPresent = true
        device.injectReply(FakeCcidReaderDevice.reply(Ccid.DATA_BLOCK, sequence = 200.toByte(), status = 0, data = FILE_NOT_FOUND))

        // Act
        val response = slot.transmit(SELECT, TIMEOUT_MS)

        // Assert
        assertArrayEquals(STATUS_OK, response)
    }

    @Test
    fun `a card that says nothing runs into the timeout`() {
        // Arrange
        device.cardPresent = true
        device.card = { null }
        val started = System.nanoTime()

        // Act
        assertThrows(CcidException.Timeout::class.java) { slot.transmit(SELECT, SHORT_TIMEOUT_MS) }

        // Assert
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited $waitedMs ms", waitedMs in SHORT_TIMEOUT_MS..SHORT_TIMEOUT_MS + 1_000)
    }

    @Test
    fun `the timeout never fires before its deadline, however precise the clock`() {
        // Arrange: a fake clock on which every read costs 0.3 ms, and a reader that stays silent
        // for exactly the time it is asked to wait - the precise timing of a Linux host, where a
        // timeout that gives up early shows (a Windows host wakes up late and hides it).
        var nowNs = 0L
        val waitsMs = mutableListOf<Int>()
        val silentReader = object : CcidTransport {
            override fun send(message: ByteArray) = Unit

            override fun receive(timeoutMs: Int): ByteArray? {
                waitsMs += timeoutMs
                nowNs += TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
                return null
            }

            override fun close() = Unit
        }
        val clockedSlot = CcidSlot(silentReader, nanoTime = { nowNs.also { nowNs += CLOCK_READ_COST_NS } })

        // Act
        assertThrows(CcidException.Timeout::class.java) { clockedSlot.transmit(SELECT, SHORT_TIMEOUT_MS) }

        // Assert: the first clock read (at 0) set the deadline, so giving up before it is early.
        val deadlineNs = TimeUnit.MILLISECONDS.toNanos(SHORT_TIMEOUT_MS.toLong())
        assertTrue("gave up at $nowNs ns, before the deadline at $deadlineNs ns", nowNs >= deadlineNs)
        assertTrue("asked the reader to wait 0 ms: $waitsMs", waitsMs.all { it > 0 })
    }

    @Test
    fun `a card that left the field is reported as lost`() {
        // Arrange
        device.cardPresent = false

        // Act + Assert
        assertThrows(CcidException.CardLost::class.java) { slot.transmit(SELECT, TIMEOUT_MS) }
    }

    @Test
    fun `a card that goes mute mid-exchange is reported as lost`() {
        // Arrange
        device.cardPresent = true
        device.apduError = FakeCcidReaderDevice.ICC_MUTE

        // Act + Assert
        assertThrows(CcidException.CardLost::class.java) { slot.transmit(SELECT, TIMEOUT_MS) }
    }

    @Test
    fun `a command the reader refuses is a protocol error`() {
        // Arrange
        device.cardPresent = true
        device.apduError = FakeCcidReaderDevice.CMD_NOT_SUPPORTED

        // Act + Assert
        assertThrows(CcidException.Protocol::class.java) { slot.transmit(SELECT, TIMEOUT_MS) }
    }

    @Test
    fun `an APDU longer than the reader's messages is refused before it is sent`() {
        // Arrange
        val small = CcidSlot(device, maxMessageSize = 64)

        // Act + Assert
        assertThrows(CcidException.Protocol::class.java) { small.transmit(ByteArray(100), TIMEOUT_MS) }
        assertTrue(device.commands.isEmpty())
    }

    // endregion

    private companion object {
        const val TIMEOUT_MS = 2_000
        const val SHORT_TIMEOUT_MS = 200
        const val CLOCK_READ_COST_NS = 300_000L

        val SELECT = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07, 0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01, 0x00)
        val FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    }
}
