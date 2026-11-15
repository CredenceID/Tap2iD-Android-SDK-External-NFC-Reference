package com.credenceid.tap2id.reference.reader.ccid

import java.io.IOException

/**
 * The few messages of the USB smart card class (CCID, revision 1.1) that a contactless tap needs.
 *
 * Every message starts with a 10-byte header: the message type, the length of the data that follows
 * (4 bytes, little endian), the slot, a sequence number the reply repeats, and three bytes whose
 * meaning depends on the type.
 */
internal object Ccid {
    const val HEADER_SIZE = 10

    // Commands, from the host to the reader.
    const val ICC_POWER_ON: Byte = 0x62
    const val ICC_POWER_OFF: Byte = 0x63
    const val GET_SLOT_STATUS: Byte = 0x65
    const val XFR_BLOCK: Byte = 0x6F

    // Replies, from the reader to the host.
    const val DATA_BLOCK: Byte = 0x80.toByte()
    const val SLOT_STATUS: Byte = 0x81.toByte()

    /** In an XfrBlock with no data: "send me the next part of the response". */
    const val CONTINUE_RESPONSE: Byte = 0x10

    /**
     * Builds one command.
     *
     * @param type the message type
     * @param slot the slot the command is for
     * @param sequence the number the reader repeats in its reply
     * @param parameter the three type-specific header bytes; missing ones are zero
     * @param data the bytes that follow the header
     */
    fun command(
        type: Byte,
        slot: Int,
        sequence: Int,
        parameter: ByteArray = EMPTY,
        data: ByteArray = EMPTY,
    ): ByteArray {
        val message = ByteArray(HEADER_SIZE + data.size)
        message[0] = type
        message[1] = data.size.toByte()
        message[2] = (data.size ushr 8).toByte()
        message[3] = (data.size ushr 16).toByte()
        message[4] = (data.size ushr 24).toByte()
        message[5] = slot.toByte()
        message[6] = sequence.toByte()
        parameter.copyInto(message, destinationOffset = 7)
        data.copyInto(message, destinationOffset = HEADER_SIZE)
        return message
    }

    /** @return the length of the data a message header announces */
    fun dataLength(header: ByteArray): Int =
        (header[1].toInt() and 0xFF) or
            ((header[2].toInt() and 0xFF) shl 8) or
            ((header[3].toInt() and 0xFF) shl 16) or
            ((header[4].toInt() and 0xFF) shl 24)

    val EMPTY = ByteArray(0)
}

/** Whether a card is in the reader's field, as the reader reports it. */
internal enum class IccStatus {
    /** A card is present and powered, ready for APDUs. */
    ACTIVE,

    /** A card is present and has not been powered on. */
    INACTIVE,

    /** No card is in the field. */
    ABSENT,
}

/**
 * One reply from the reader.
 *
 * @property type the message type, [Ccid.DATA_BLOCK] or [Ccid.SLOT_STATUS]
 * @property sequence the sequence number of the command this answers
 * @property error the reader's error code; only meaningful when [failed]
 * @property chain for a data block of a reader that splits long responses: which part this is
 * @property data the bytes after the header
 */
internal class CcidReply private constructor(
    val type: Byte,
    val sequence: Int,
    private val status: Int,
    val error: Int,
    val chain: Int,
    val data: ByteArray,
) {
    val iccStatus: IccStatus
        get() =
            when (status and 0x03) {
                0 -> IccStatus.ACTIVE
                1 -> IccStatus.INACTIVE
                else -> IccStatus.ABSENT
            }

    /** The command failed; [error] says why. */
    val failed: Boolean
        get() = (status ushr 6) and 0x03 == 1

    /** The card asked for more time; the real reply follows. */
    val isTimeExtension: Boolean
        get() = (status ushr 6) and 0x03 == 2

    /** More of the response follows; ask for it with [Ccid.CONTINUE_RESPONSE]. */
    val hasMoreData: Boolean
        get() = type == Ccid.DATA_BLOCK && (chain == CHAIN_BEGINS || chain == CHAIN_CONTINUES)

    companion object {
        private const val CHAIN_BEGINS = 0x01
        private const val CHAIN_CONTINUES = 0x03

        /**
         * @param message one complete message as received from the reader
         * @throws CcidException.Protocol when the message is not a well-formed CCID reply
         */
        fun parse(message: ByteArray): CcidReply {
            if (message.size < Ccid.HEADER_SIZE) {
                throw CcidException.Protocol("The reader sent ${message.size} bytes, less than a CCID header")
            }
            val length = Ccid.dataLength(message)
            if (length < 0 || message.size < Ccid.HEADER_SIZE + length) {
                throw CcidException.Protocol("The reader announced $length data bytes and sent ${message.size - Ccid.HEADER_SIZE}")
            }
            return CcidReply(
                type = message[0],
                sequence = message[6].toInt() and 0xFF,
                status = message[7].toInt() and 0xFF,
                error = message[8].toInt() and 0xFF,
                chain = message[9].toInt() and 0xFF,
                data = message.copyOfRange(Ccid.HEADER_SIZE, Ccid.HEADER_SIZE + length),
            )
        }
    }
}

/** Why a CCID exchange failed. The reader adapter turns each kind into the matching `ReaderException`. */
internal sealed class CcidException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** The reader did not reply in time. */
    class Timeout(message: String) : CcidException(message)

    /** The card left the field or stopped answering. */
    class CardLost(message: String) : CcidException(message)

    /** The reader replied with something this adapter does not understand, or refused the command. */
    class Protocol(message: String) : CcidException(message)
}
