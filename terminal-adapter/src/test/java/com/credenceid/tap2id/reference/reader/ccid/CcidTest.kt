package com.credenceid.tap2id.reference.reader.ccid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Direct tests of the CCID wire format in `Ccid.kt`, with no reader behind it. */
class CcidTest {
    /** Builds a reply message with the given header fields, the way a reader would send it. */
    private fun reply(
        type: Byte = Ccid.DATA_BLOCK,
        sequence: Int = 0,
        status: Int = 0,
        error: Int = 0,
        chain: Int = 0,
        data: ByteArray = Ccid.EMPTY,
    ): ByteArray =
        Ccid.command(
            type = type,
            slot = 0,
            sequence = sequence,
            parameter = byteArrayOf(status.toByte(), error.toByte(), chain.toByte()),
            data = data,
        )

    // region Ccid.command

    @Test
    fun `command lays out type, little endian length, slot, sequence and data`() {
        // Arrange
        val data = ByteArray(0x0102) { 0x55 }

        // Act
        val message = Ccid.command(Ccid.XFR_BLOCK, slot = 2, sequence = 9, data = data)

        // Assert
        assertEquals(Ccid.HEADER_SIZE + data.size, message.size)
        assertEquals(Ccid.XFR_BLOCK, message[0])
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x00, 0x00), message.copyOfRange(1, 5))
        assertEquals(2.toByte(), message[5])
        assertEquals(9.toByte(), message[6])
        assertArrayEquals(data, message.copyOfRange(Ccid.HEADER_SIZE, message.size))
    }

    @Test
    fun `command without data or parameters is a bare header with zeroed parameter bytes`() {
        // Arrange + Act
        val message = Ccid.command(Ccid.ICC_POWER_ON, slot = 0, sequence = 1)

        // Assert
        assertEquals(Ccid.HEADER_SIZE, message.size)
        assertArrayEquals(byteArrayOf(0x62, 0, 0, 0, 0, 0, 1, 0, 0, 0), message)
    }

    @Test
    fun `command leaves the parameter bytes it was not given at zero`() {
        // Arrange + Act
        val message = Ccid.command(Ccid.XFR_BLOCK, slot = 0, sequence = 0, parameter = byteArrayOf(0x10))

        // Assert
        assertArrayEquals(byteArrayOf(0x10, 0, 0), message.copyOfRange(7, 10))
    }

    // endregion

    // region Ccid.dataLength

    @Test
    fun `dataLength reads the four length bytes little endian`() {
        // Arrange
        val header = byteArrayOf(0, 0x04, 0x03, 0x02, 0x01, 0, 0, 0, 0, 0)

        // Act
        val length = Ccid.dataLength(header)

        // Assert
        assertEquals(0x01020304, length)
    }

    @Test
    fun `dataLength treats every length byte as unsigned`() {
        // Arrange
        val header = byteArrayOf(0, 0xFF.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0)

        // Act
        val length = Ccid.dataLength(header)

        // Assert
        assertEquals(0x80FF, length)
    }

    @Test
    fun `dataLength is negative when the top bit of the last byte is set`() {
        // Arrange
        val header = byteArrayOf(0, 0, 0, 0, 0x80.toByte(), 0, 0, 0, 0, 0)

        // Act + Assert
        assertTrue(Ccid.dataLength(header) < 0)
    }

    // endregion

    // region CcidReply.parse

    @Test
    fun `parse extracts the header fields and the data`() {
        // Arrange
        val payload = byteArrayOf(0x90.toByte(), 0x00)
        val message = reply(Ccid.SLOT_STATUS, sequence = 0xFE, status = 0x41, error = 0xEF, chain = 0x03, data = payload)

        // Act
        val parsed = CcidReply.parse(message)

        // Assert
        assertEquals(Ccid.SLOT_STATUS, parsed.type)
        assertEquals(0xFE, parsed.sequence)
        assertEquals(0xEF, parsed.error)
        assertEquals(0x03, parsed.chain)
        assertArrayEquals(payload, parsed.data)
    }

    @Test
    fun `parse ignores bytes after the announced data`() {
        // Arrange
        val message = reply(data = byteArrayOf(1, 2)) + byteArrayOf(9, 9, 9)

        // Act
        val parsed = CcidReply.parse(message)

        // Assert
        assertArrayEquals(byteArrayOf(1, 2), parsed.data)
    }

    @Test
    fun `parse accepts a reply with no data`() {
        // Arrange + Act
        val parsed = CcidReply.parse(reply())

        // Assert
        assertEquals(0, parsed.data.size)
    }

    @Test
    fun `parse rejects a message shorter than the header`() {
        // Arrange
        val message = ByteArray(Ccid.HEADER_SIZE - 1)

        // Act
        val failure = assertThrows(CcidException.Protocol::class.java) { CcidReply.parse(message) }

        // Assert
        assertTrue(failure.message!!.contains("${message.size} bytes"))
    }

    @Test
    fun `parse rejects a message whose data is shorter than announced`() {
        // Arrange
        val message = reply(data = byteArrayOf(1, 2, 3)).copyOf(Ccid.HEADER_SIZE + 2)

        // Act + Assert
        assertThrows(CcidException.Protocol::class.java) { CcidReply.parse(message) }
    }

    @Test
    fun `parse rejects a negative announced length`() {
        // Arrange
        val message = ByteArray(Ccid.HEADER_SIZE).also { it[4] = 0x80.toByte() }

        // Act + Assert
        assertThrows(CcidException.Protocol::class.java) { CcidReply.parse(message) }
    }

    // endregion

    // region CcidReply status flags

    @Test
    fun `iccStatus maps the low status bits and ignores the command status bits`() {
        // Arrange + Act + Assert
        assertEquals(IccStatus.ACTIVE, CcidReply.parse(reply(status = 0x00)).iccStatus)
        assertEquals(IccStatus.INACTIVE, CcidReply.parse(reply(status = 0x01)).iccStatus)
        assertEquals(IccStatus.ABSENT, CcidReply.parse(reply(status = 0x02)).iccStatus)
        assertEquals(IccStatus.ABSENT, CcidReply.parse(reply(status = 0x03)).iccStatus)
        assertEquals(IccStatus.INACTIVE, CcidReply.parse(reply(status = 0x41)).iccStatus)
    }

    @Test
    fun `failed is set only when the command status bits say failed`() {
        // Arrange + Act + Assert
        assertFalse(CcidReply.parse(reply(status = 0x00)).failed)
        assertTrue(CcidReply.parse(reply(status = 0x40)).failed)
        assertFalse(CcidReply.parse(reply(status = 0x80)).failed)
        assertFalse(CcidReply.parse(reply(status = 0x03)).failed)
    }

    @Test
    fun `isTimeExtension is set only when the command status bits ask for more time`() {
        // Arrange + Act + Assert
        assertTrue(CcidReply.parse(reply(status = 0x80)).isTimeExtension)
        assertFalse(CcidReply.parse(reply(status = 0x40)).isTimeExtension)
        assertFalse(CcidReply.parse(reply(status = 0x00)).isTimeExtension)
    }

    @Test
    fun `hasMoreData is true for a data block that begins or continues a chain`() {
        // Arrange + Act + Assert
        assertTrue(CcidReply.parse(reply(Ccid.DATA_BLOCK, chain = 0x01)).hasMoreData)
        assertTrue(CcidReply.parse(reply(Ccid.DATA_BLOCK, chain = 0x03)).hasMoreData)
    }

    @Test
    fun `hasMoreData is false for the end of a chain, a single block or a slot status`() {
        // Arrange + Act + Assert
        assertFalse(CcidReply.parse(reply(Ccid.DATA_BLOCK, chain = 0x00)).hasMoreData)
        assertFalse(CcidReply.parse(reply(Ccid.DATA_BLOCK, chain = 0x02)).hasMoreData)
        assertFalse(CcidReply.parse(reply(Ccid.SLOT_STATUS, chain = 0x01)).hasMoreData)
    }

    // endregion

    // region CcidException

    @Test
    fun `each exception kind keeps its message and is an IOException`() {
        // Arrange
        val failures: List<java.io.IOException> = listOf(CcidException.Timeout("slow"), CcidException.CardLost("gone"), CcidException.Protocol("odd"))

        // Act + Assert
        assertEquals(listOf("slow", "gone", "odd"), failures.map { it.message })
    }

    // endregion
}
