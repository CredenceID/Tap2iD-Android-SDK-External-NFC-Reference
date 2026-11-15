package com.credenceid.tap2id.reference.reader.ccid

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One card slot of a CCID reader: is a card there, power it, exchange an APDU with it.
 *
 * Not thread safe. The reader adapter lets only one thread in at a time.
 *
 * @param transport the link to the reader
 * @param slot the slot index; a contactless-only reader has one slot, 0
 * @param maxMessageSize the longest message the reader accepts, from its class descriptor
 * @param nanoTime the clock used for timeouts
 */
internal class CcidSlot(
    private val transport: CcidTransport,
    private val slot: Int = 0,
    private val maxMessageSize: Int = DEFAULT_MAX_MESSAGE_SIZE,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var sequence = 0

    /** @return whether a card is in the field */
    @Throws(IOException::class)
    fun status(): IccStatus = exchange(Ccid.GET_SLOT_STATUS, timeoutMs = CONTROL_TIMEOUT_MS).iccStatus

    /**
     * Powers the card on. For a contactless card the reader runs the ISO 14443 activation, after
     * which the card answers APDUs.
     *
     * @return the answer to reset the reader builds for the card
     * @throws CcidException.CardLost when the card left or would not activate
     */
    @Throws(IOException::class)
    fun powerOn(): ByteArray {
        val reply = exchange(Ccid.ICC_POWER_ON, timeoutMs = POWER_ON_TIMEOUT_MS)
        if (reply.failed) throw failure(reply)
        return reply.data
    }

    /** Powers the card off. */
    @Throws(IOException::class)
    fun powerOff() {
        exchange(Ccid.ICC_POWER_OFF, timeoutMs = CONTROL_TIMEOUT_MS)
    }

    /**
     * Sends a command APDU to the card and returns its response, status bytes included.
     *
     * @param apdu the command APDU
     * @param timeoutMs how long the whole exchange may take, in milliseconds
     * @return the response APDU exactly as the card sent it
     * @throws CcidException.Timeout when the reader did not reply in time
     * @throws CcidException.CardLost when the card left the field
     */
    @Throws(IOException::class)
    fun transmit(
        apdu: ByteArray,
        timeoutMs: Int,
    ): ByteArray {
        if (Ccid.HEADER_SIZE + apdu.size > maxMessageSize) {
            throw CcidException.Protocol(
                "An APDU of ${apdu.size} bytes does not fit the reader's $maxMessageSize-byte messages",
            )
        }
        val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        val response = ByteArrayOutputStream()
        var reply = exchange(Ccid.XFR_BLOCK, data = apdu, deadline = deadline)
        while (true) {
            if (reply.type != Ccid.DATA_BLOCK) {
                throw CcidException.Protocol("The reader answered an APDU with message type 0x%02X".format(reply.type))
            }
            if (reply.failed) throw failure(reply)
            response.write(reply.data)
            if (!reply.hasMoreData) return response.toByteArray()
            // A reader that exchanges extended APDUs splits a long response over several messages.
            reply = exchange(Ccid.XFR_BLOCK, parameter = byteArrayOf(0, Ccid.CONTINUE_RESPONSE), deadline = deadline)
        }
    }

    private fun exchange(
        type: Byte,
        parameter: ByteArray = Ccid.EMPTY,
        data: ByteArray = Ccid.EMPTY,
        timeoutMs: Int = 0,
        deadline: Long = nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong()),
    ): CcidReply {
        val sent = sequence
        sequence = (sequence + 1) and 0xFF
        transport.send(Ccid.command(type, slot, sent, parameter, data))
        while (true) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - nanoTime())
            if (remainingMs <= 0) throw CcidException.Timeout("The reader did not reply in time")
            val message = transport.receive(remainingMs.toInt()) ?: continue
            val reply = CcidReply.parse(message)
            // A reply to a command that was given up on earlier can still arrive; it is not ours.
            if (reply.sequence != sent) continue
            if (reply.isTimeExtension) continue
            return reply
        }
    }

    private fun failure(reply: CcidReply): CcidException {
        val code = "CCID error 0x%02X".format(reply.error)
        return if (reply.iccStatus == IccStatus.ABSENT || reply.error in CARD_GONE_ERRORS) {
            CcidException.CardLost("The card stopped answering ($code)")
        } else {
            CcidException.Protocol("The reader refused the command ($code)")
        }
    }

    companion object {
        /** The smallest message size a CCID reader that exchanges short APDUs supports. */
        const val DEFAULT_MAX_MESSAGE_SIZE = 271

        private const val CONTROL_TIMEOUT_MS = 1_000
        private const val POWER_ON_TIMEOUT_MS = 3_000

        // ICC_MUTE, XFR_PARITY_ERROR, XFR_OVERRUN: what a reader reports when the card moves away.
        private val CARD_GONE_ERRORS = setOf(0xFE, 0xFD, 0xFC)
    }
}
