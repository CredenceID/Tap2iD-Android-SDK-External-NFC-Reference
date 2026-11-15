package com.credenceid.tap2id.reference.reader.ccid

import java.io.IOException

/**
 * Carries whole CCID messages to and from one reader. The USB implementation is
 * [com.credenceid.tap2id.reference.reader.usb.UsbCcidTransport]; the unit tests script a fake one.
 */
internal interface CcidTransport {
    /**
     * Sends one message to the reader.
     *
     * @throws IOException when the reader is gone or the transport is closed
     */
    @Throws(IOException::class)
    fun send(message: ByteArray)

    /**
     * Waits for the next message from the reader.
     *
     * @param timeoutMs how long to wait, in milliseconds
     * @return the message, or null when none arrived in time. A message that arrives later is not
     *   lost; the next call returns it.
     * @throws IOException when the reader is gone or the transport is closed
     */
    @Throws(IOException::class)
    fun receive(timeoutMs: Int): ByteArray?

    /** Ends a [receive] that is waiting on another thread and frees the reader. */
    fun close()
}
