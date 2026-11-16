package com.credenceid.tap2id.reference.reader.ccid

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A CCID reader for the unit tests: it answers status, power and APDU commands the way a real
 * contactless reader does, with a card that the test puts into the field and takes out again.
 */
internal class FakeCcidReaderDevice : CcidTransport {
    /** Every command the host sent, as its message type. */
    val commands = CopyOnWriteArrayList<Byte>()

    /** Whether a card is in the field. */
    @Volatile var cardPresent = false

    /** How the card answers an APDU. Null means the card says nothing. */
    @Volatile var card: (apdu: ByteArray) -> ByteArray? = { STATUS_OK }

    /** When set, an APDU is answered with this CCID error instead. */
    @Volatile var apduError: Int? = null

    /** How many "the card needs more time" replies come before the real one. */
    @Volatile var timeExtensions = 0

    /** When set, the response is split into parts of this size, as extended-APDU readers do. */
    @Volatile var responseChunkSize: Int? = null

    /** When true, the reader has been unplugged. */
    @Volatile var unplugged = false

    @Volatile var closed = false

    private val replies = LinkedBlockingQueue<ByteArray>()
    private var powered = false
    private var pendingResponse = ByteArray(0)

    /** Queues a reply the host did not ask for, such as a late answer to an abandoned command. */
    fun injectReply(message: ByteArray) {
        replies += message
    }

    override fun send(message: ByteArray) {
        if (closed) throw IOException("closed")
        if (unplugged) throw IOException("unplugged")
        val type = message[0]
        val sequence = message[6]
        val data = message.copyOfRange(Ccid.HEADER_SIZE, message.size)
        commands += type
        when (type) {
            Ccid.GET_SLOT_STATUS -> replies += reply(Ccid.SLOT_STATUS, sequence, status = iccStatus())
            Ccid.ICC_POWER_ON ->
                if (cardPresent) {
                    powered = true
                    replies += reply(Ccid.DATA_BLOCK, sequence, status = 0, data = ATR)
                } else {
                    replies += reply(Ccid.DATA_BLOCK, sequence, status = FAILED or NO_CARD, error = ICC_MUTE)
                }
            Ccid.ICC_POWER_OFF -> {
                powered = false
                replies += reply(Ccid.SLOT_STATUS, sequence, status = iccStatus())
            }
            Ccid.XFR_BLOCK -> answerApdu(sequence, data, continuation = message[8] == Ccid.CONTINUE_RESPONSE)
        }
    }

    private fun answerApdu(
        sequence: Byte,
        apdu: ByteArray,
        continuation: Boolean,
    ) {
        if (!continuation) {
            apduError?.let { error ->
                replies += reply(Ccid.DATA_BLOCK, sequence, status = FAILED or iccStatus(), error = error)
                return
            }
            if (!cardPresent) {
                replies += reply(Ccid.DATA_BLOCK, sequence, status = FAILED or NO_CARD, error = ICC_MUTE)
                return
            }
            // A silent card: the reader sends nothing and the host runs into its timeout.
            pendingResponse = card(apdu) ?: return
            repeat(timeExtensions) { replies += reply(Ccid.DATA_BLOCK, sequence, status = TIME_EXTENSION, error = 1) }
        }
        val chunkSize = responseChunkSize ?: pendingResponse.size.coerceAtLeast(1)
        val part = pendingResponse.copyOfRange(0, minOf(chunkSize, pendingResponse.size))
        pendingResponse = pendingResponse.copyOfRange(part.size, pendingResponse.size)
        val chain =
            when {
                responseChunkSize == null -> CHAIN_COMPLETE
                pendingResponse.isEmpty() -> if (continuation) CHAIN_ENDS else CHAIN_COMPLETE
                else -> if (continuation) CHAIN_CONTINUES else CHAIN_BEGINS
            }
        replies += reply(Ccid.DATA_BLOCK, sequence, status = 0, chain = chain, data = part)
    }

    override fun receive(timeoutMs: Int): ByteArray? {
        if (closed) throw IOException("closed")
        if (unplugged) throw IOException("unplugged")
        val message = replies.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (closed) throw IOException("closed")
        return message?.takeUnless { it === CLOSE_SIGNAL }
    }

    override fun close() {
        closed = true
        // Wakes a receive() that is waiting, as cancelling the USB request does.
        replies += CLOSE_SIGNAL
    }

    private fun iccStatus(): Int =
        when {
            !cardPresent -> NO_CARD
            powered -> 0
            else -> 1
        }

    companion object {
        val STATUS_OK = byteArrayOf(0x90.toByte(), 0x00)
        val ATR = byteArrayOf(0x3B, 0x80.toByte(), 0x80.toByte(), 0x01, 0x01)

        const val ICC_MUTE = 0xFE
        const val CMD_NOT_SUPPORTED = 0x00

        private const val NO_CARD = 0x02
        private const val FAILED = 0x40
        private const val TIME_EXTENSION = 0x80
        private const val CHAIN_COMPLETE = 0x00
        private const val CHAIN_BEGINS = 0x01
        private const val CHAIN_ENDS = 0x02
        private const val CHAIN_CONTINUES = 0x03
        private val CLOSE_SIGNAL = ByteArray(0)

        /** Builds a reply message the way a reader would. */
        fun reply(
            type: Byte,
            sequence: Byte,
            status: Int,
            error: Int = 0,
            chain: Int = 0,
            data: ByteArray = ByteArray(0),
        ): ByteArray =
            Ccid.command(
                type = type,
                slot = 0,
                sequence = sequence.toInt(),
                parameter = byteArrayOf(status.toByte(), error.toByte(), chain.toByte()),
                data = data,
            )
    }
}
