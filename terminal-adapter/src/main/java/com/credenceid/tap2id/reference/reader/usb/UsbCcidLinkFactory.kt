package com.credenceid.tap2id.reference.reader.usb

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.credenceid.tap2id.reference.reader.CcidLink
import com.credenceid.tap2id.reference.reader.CcidLinkFactory
import com.credenceid.tap2id.reference.reader.ReaderIdentity
import com.credenceid.tap2id.reference.reader.ReaderUnavailableException
import com.credenceid.tap2id.reference.reader.ccid.CcidDescriptor
import com.credenceid.tap2id.reference.reader.ccid.CcidSlot
import com.credenceid.tap2idSdk.api.reader.ReaderAvailability
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finds the USB smart card reader plugged into this device and opens it: USB permission, claiming
 * the interface, reading its CCID descriptor and locating its endpoints.
 *
 * @param context the application context
 */
internal class UsbCcidLinkFactory(private val context: Context) : CcidLinkFactory {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    override fun attachedReader(): ReaderIdentity? = findReader()?.first?.identity()

    override fun open(activity: Activity): CcidLink {
        val (device, usbInterface) =
            findReader() ?: throw ReaderUnavailableException(ReaderAvailability.DISCONNECTED, "No USB smart card reader is plugged in")
        val name = device.productName ?: "USB reader"

        if (!usbManager.hasPermission(device) && !requestPermission(device)) {
            throw ReaderUnavailableException(ReaderAvailability.DISCONNECTED, "USB permission for $name was not granted")
        }
        val connection =
            usbManager.openDevice(device)
                ?: throw ReaderUnavailableException(ReaderAvailability.DISCONNECTED, "$name could not be opened")
        // Without force: when another app already holds the reader this fails, and that is "busy".
        if (!connection.claimInterface(usbInterface, false)) {
            connection.close()
            throw ReaderUnavailableException(ReaderAvailability.BUSY, "$name is in use by another app")
        }

        val descriptor = connection.rawDescriptors?.let { CcidDescriptor.find(it, usbInterface.id) }
        val input = usbInterface.bulkEndpoint(UsbConstants.USB_DIR_IN)
        val output = usbInterface.bulkEndpoint(UsbConstants.USB_DIR_OUT)
        val problem =
            when {
                descriptor == null || input == null || output == null -> "$name is not a usable CCID reader"
                !descriptor.exchangesApdus -> "$name exchanges TPDUs, not APDUs, which this adapter does not support"
                else -> null
            }
        if (problem != null) {
            connection.releaseInterface(usbInterface)
            connection.close()
            throw ReaderUnavailableException(ReaderAvailability.DISCONNECTED, problem)
        }

        val transport =
            UsbCcidTransport(
                connection = connection,
                usbInterface = usbInterface,
                input = input!!,
                output = output!!,
                maxMessageSize = descriptor!!.maxMessageSize,
                isAttached = { usbManager.deviceList.containsKey(device.deviceName) },
            )
        val slot = CcidSlot(transport, maxMessageSize = descriptor.maxMessageSize)
        return object : CcidLink {
            override val identity = device.identity()
            override val slot = slot

            override fun close() = transport.close()
        }
    }

    /**
     * @return the first attached device with a smart card interface. On a reader with a contact and
     *   a contactless interface, the contactless one is preferred.
     */
    private fun findReader(): Pair<UsbDevice, UsbInterface>? =
        usbManager.deviceList.values.firstNotNullOfOrNull { device ->
            val smartCardInterfaces =
                (0 until device.interfaceCount)
                    .map(device::getInterface)
                    .filter { it.interfaceClass == UsbConstants.USB_CLASS_CSCID }
            val chosen =
                smartCardInterfaces.firstOrNull { CONTACTLESS_NAME.containsMatchIn(it.name.orEmpty()) }
                    ?: smartCardInterfaces.firstOrNull()
            chosen?.let { device to it }
        }

    /**
     * Shows Android's USB permission dialog and waits for the answer.
     *
     * @return whether the user granted access
     * @implNote blocks its thread, which is fine in `attach`: the Tap2iD SDK never calls that on the
     * main thread, and the main thread is where the answer is delivered.
     */
    private fun requestPermission(device: UsbDevice): Boolean {
        val action = "${context.packageName}.USB_READER_PERMISSION"
        val answered = CountDownLatch(1)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = answered.countDown()
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(action))
        }
        try {
            // The system adds the answer to the intent, so it must be mutable and, on Android 14, explicit.
            val flags =
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val answer = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
            usbManager.requestPermission(device, answer)
            answered.await(PERMISSION_WAIT_SECONDS, TimeUnit.SECONDS)
        } finally {
            context.unregisterReceiver(receiver)
        }
        return usbManager.hasPermission(device)
    }

    private fun UsbInterface.bulkEndpoint(direction: Int): UsbEndpoint? =
        (0 until endpointCount)
            .map(::getEndpoint)
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == direction }

    private fun UsbDevice.identity() =
        ReaderIdentity(
            vendor = manufacturerName ?: "USB vendor %04X".format(vendorId),
            model = productName ?: "reader %04X".format(productId),
            firmwareVersion = version.takeIf { it.isNotBlank() },
        )

    private companion object {
        const val PERMISSION_WAIT_SECONDS = 60L
        val CONTACTLESS_NAME = Regex("contactless|\\bCL\\b", RegexOption.IGNORE_CASE)
    }
}
