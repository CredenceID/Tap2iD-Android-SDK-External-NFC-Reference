package com.credenceid.tap2id.reference.reader

import android.app.Activity
import android.content.Context
import android.util.Log
import com.credenceid.tap2id.reference.reader.ccid.CcidException
import com.credenceid.tap2id.reference.reader.ccid.IccStatus
import com.credenceid.tap2id.reference.reader.usb.UsbCcidLinkFactory
import com.credenceid.tap2idSdk.api.reader.CardPresenceListener
import com.credenceid.tap2idSdk.api.reader.ContactlessReader
import com.credenceid.tap2idSdk.api.reader.ReaderAvailability
import com.credenceid.tap2idSdk.api.reader.ReaderException
import com.credenceid.tap2idSdk.api.reader.ReaderException.Reason
import com.credenceid.tap2idSdk.api.reader.ReaderInfo
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * A [ContactlessReader] for a USB contactless smart card reader of the CCID class, such as an
 * HID OMNIKEY 5427 CK, plugged into the Android device.
 *
 * It is a complete, working adapter and also the worked example for writing your own. A terminal
 * maker's adapter has the same five jobs, done with the terminal's NFC SDK instead of USB:
 *
 * | Member | What this adapter does | Your adapter |
 * |---|---|---|
 * | [attach] | Opens the USB reader | Open the terminal's NFC service |
 * | [availability] | Reports a missing reader or one another app holds | Report the service state |
 * | [startDetection] | Polls the slot, powers on a card that arrives, reports an unplugged reader | Start polling |
 * | [transceive] | Sends one APDU and waits for the response | The same, with your SDK |
 * | [release] | Stops polling and closes USB | Stop polling, close the service |
 *
 * The Tap2iD SDK calls these in that order for every verification, and [release] always last.
 *
 * Threading: the SDK may call from any thread, and calls [transceive] on a thread of its own.
 * Every exchange with the reader goes through [io], so polling and an APDU never interleave.
 *
 * @param links finds and opens the reader
 * @param pollIntervalMs how often the slot is asked whether a card is there
 */
class UsbCcidReader internal constructor(
    private val links: CcidLinkFactory,
    private val pollIntervalMs: Long,
) : ContactlessReader {
    /**
     * @param context any context; the application context is kept
     */
    constructor(context: Context) : this(UsbCcidLinkFactory(context.applicationContext), POLL_INTERVAL_MS)

    private val io = ReentrantLock()
    private val released = AtomicBoolean(true)

    @Volatile private var link: CcidLink? = null

    @Volatile private var unavailable: ReaderUnavailableException? = null

    @Volatile private var poller: Thread? = null

    @Volatile private var lastIdentity: ReaderIdentity? = null

    override val readerInfo: ReaderInfo
        get() {
            val identity = link?.identity ?: links.attachedReader() ?: lastIdentity
            return ReaderInfo(
                vendor = identity?.vendor ?: "USB",
                model = identity?.model ?: "CCID contactless reader",
                firmwareVersion = identity?.firmwareVersion,
                adapterVersion = ADAPTER_VERSION,
            )
        }

    /** @return whether a USB smart card reader is plugged in right now. Opens nothing. */
    fun isReaderAttached(): Boolean = links.attachedReader() != null

    /**
     * Opens the reader. A reader that is missing or in use is not an error here: [availability]
     * reports it, and the SDK turns that into the matching error for the host application.
     */
    override fun attach(activity: Activity) {
        released.set(false)
        unavailable = null
        try {
            link = links.open(activity).also { lastIdentity = it.identity }
        } catch (notUsable: ReaderUnavailableException) {
            Log.w(TAG, "Reader not usable: ${notUsable.message}")
            unavailable = notUsable
        }
    }

    override fun availability(context: Context): ReaderAvailability =
        if (link != null) ReaderAvailability.AVAILABLE else unavailable?.availability ?: ReaderAvailability.DISCONNECTED

    override fun startDetection(listener: CardPresenceListener) {
        val opened = link ?: return
        poller = thread(name = "usb-ccid-poll", isDaemon = true) { poll(opened, listener) }
    }

    /**
     * Asks the slot for a card until [release]. A card that arrives is powered on first, so it is
     * ready for APDUs by the time the listener hears of it. A reader that is unplugged while this
     * waits is reported with `onReaderError`.
     */
    private fun poll(
        opened: CcidLink,
        listener: CardPresenceListener,
    ) {
        var present = false
        // Each verification opens its own link, so a poller from an earlier one stops here even after
        // the next attach has reset `released`.
        fun current() = !released.get() && link === opened
        while (current()) {
            try {
                val inField =
                    io.withLock {
                        when {
                            !current() -> return
                            opened.slot.status() == IccStatus.ABSENT -> false
                            present -> true
                            else -> {
                                opened.slot.powerOn()
                                true
                            }
                        }
                    }
                if (inField != present) {
                    present = inField
                    if (inField) listener.onCardDetected() else listener.onCardRemoved()
                }
            } catch (notReady: CcidException) {
                // A card that is leaving, or one that will not activate. Look again on the next pass.
                Log.d(TAG, "Slot not ready: ${notReady.message}")
            } catch (gone: IOException) {
                // Released: nothing to say. Otherwise the reader went away while waiting for a phone,
                // and the SDK has to hear of it or the verification would wait until it is cancelled.
                if (current()) {
                    Log.w(TAG, "Polling stopped: ${gone.message}")
                    listener.onReaderError(ReaderException(Reason.READER_DISCONNECTED, gone.message, gone))
                }
                return
            }
            try {
                Thread.sleep(pollIntervalMs)
            } catch (interrupted: InterruptedException) {
                return
            }
        }
    }

    /**
     * Sends one APDU. The response goes back untouched: the status bytes stay on it, and a status
     * other than `9000` is a response like any other.
     */
    @Throws(IOException::class)
    override fun transceive(
        apdu: ByteArray,
        timeoutMs: Int,
    ): ByteArray {
        val opened = link ?: throw ReaderException(if (released.get()) Reason.RELEASED else Reason.READER_DISCONNECTED)
        try {
            return io.withLock {
                if (released.get()) throw ReaderException(Reason.RELEASED)
                opened.slot.transmit(apdu, timeoutMs)
            }
        } catch (failed: IOException) {
            throw asReaderException(failed)
        }
    }

    /** Maps what went wrong on the wire to the reason the SDK understands. */
    private fun asReaderException(failed: IOException): IOException =
        when {
            failed is ReaderException -> failed
            // release() closed the link under this exchange; whatever it threw, that is the reason.
            released.get() -> ReaderException(Reason.RELEASED, cause = failed)
            failed is CcidException.Timeout -> ReaderException(Reason.TIMEOUT, failed.message, failed)
            failed is CcidException.CardLost -> ReaderException(Reason.CARD_LOST, failed.message, failed)
            // Not a transport failure the SDK has a reason for; it reports the message as an I/O error.
            failed is CcidException.Protocol -> failed
            else -> ReaderException(Reason.READER_DISCONNECTED, failed.message, failed)
        }

    /**
     * Stops polling and closes the reader. Closing the link makes a [transceive] that is waiting on
     * another thread throw at once, which is what lets a cancelled verification end promptly.
     */
    override fun release() {
        if (!released.compareAndSet(false, true)) return
        poller?.interrupt()
        poller = null
        val opened = link ?: return
        link = null
        // Switch the card off when the reader is idle. When an exchange is running, closing ends it.
        if (io.tryLock()) {
            try {
                opened.slot.powerOff()
            } catch (ignored: IOException) {
                Log.d(TAG, "Power off skipped: ${ignored.message}")
            } finally {
                io.unlock()
            }
        }
        opened.close()
    }

    companion object {
        /** The version reported in `ReaderInfo.adapterVersion`, which the SDK quotes in its errors. */
        const val ADAPTER_VERSION = "1.0.0"

        private const val TAG = "UsbCcidReader"
        private const val POLL_INTERVAL_MS = 150L
    }
}
