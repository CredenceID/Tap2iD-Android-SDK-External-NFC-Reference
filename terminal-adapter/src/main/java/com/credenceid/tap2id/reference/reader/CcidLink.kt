package com.credenceid.tap2id.reference.reader

import android.app.Activity
import com.credenceid.tap2id.reference.reader.ccid.CcidSlot
import com.credenceid.tap2idSdk.api.reader.ReaderAvailability

/**
 * What identifies a reader to a person: who made it, which model, which firmware.
 *
 * @property firmwareVersion the reader's device release number, or null when it reports none
 */
internal data class ReaderIdentity(
    val vendor: String,
    val model: String,
    val firmwareVersion: String?,
)

/** An open reader: its identity, its card slot, and the way to let go of it. */
internal interface CcidLink {
    val identity: ReaderIdentity
    val slot: CcidSlot

    /** Frees the reader. A CCID exchange waiting on another thread ends with an `IOException`. */
    fun close()
}

/**
 * Finds and opens the reader. This is the seam between [UsbCcidReader], which is plain Kotlin and
 * unit tested, and the Android USB code in [com.credenceid.tap2id.reference.reader.usb].
 */
internal interface CcidLinkFactory {
    /** @return the reader that is plugged in, or null when there is none. Opens nothing. */
    fun attachedReader(): ReaderIdentity?

    /**
     * Opens the reader that is plugged in. May block, for example while the user answers the USB
     * permission dialog.
     *
     * @throws ReaderUnavailableException when there is no reader or it cannot be used right now
     */
    @Throws(ReaderUnavailableException::class)
    fun open(activity: Activity): CcidLink
}

/**
 * The reader cannot be used.
 *
 * @property availability what [UsbCcidReader.availability] reports because of it
 */
internal class ReaderUnavailableException(
    val availability: ReaderAvailability,
    message: String,
) : Exception(message)
