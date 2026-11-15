package com.credenceid.tap2id.reference.reader.ccid

/**
 * What a CCID reader says about itself in its smart card class descriptor.
 *
 * @property features the `dwFeatures` bit field
 * @property maxMessageSize the longest CCID message the reader accepts
 */
internal data class CcidDescriptor(
    val features: Int,
    val maxMessageSize: Int,
) {
    /**
     * The reader takes whole APDUs and runs the card protocol itself. Readers that exchange TPDUs
     * or single characters leave that protocol to the host, which this adapter does not implement.
     */
    val exchangesApdus: Boolean
        get() = features and (SHORT_APDU_LEVEL or EXTENDED_APDU_LEVEL) != 0

    companion object {
        private const val INTERFACE_DESCRIPTOR = 0x04
        private const val SMART_CARD_DESCRIPTOR = 0x21
        private const val SMART_CARD_DESCRIPTOR_SIZE = 0x36
        private const val FEATURES_OFFSET = 40
        private const val MAX_MESSAGE_SIZE_OFFSET = 44
        private const val SHORT_APDU_LEVEL = 0x00020000
        private const val EXTENDED_APDU_LEVEL = 0x00040000

        /**
         * Finds the smart card class descriptor of one interface.
         *
         * @param rawDescriptors the device's descriptors as USB delivers them, one after another
         * @param interfaceNumber the interface whose descriptor is wanted
         * @return the descriptor, or null when the interface has none
         */
        fun find(
            rawDescriptors: ByteArray,
            interfaceNumber: Int,
        ): CcidDescriptor? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= rawDescriptors.size) {
                val length = rawDescriptors[offset].toInt() and 0xFF
                val type = rawDescriptors[offset + 1].toInt() and 0xFF
                if (length < 2 || offset + length > rawDescriptors.size) return null
                when {
                    type == INTERFACE_DESCRIPTOR && length > 2 ->
                        currentInterface = rawDescriptors[offset + 2].toInt() and 0xFF
                    type == SMART_CARD_DESCRIPTOR &&
                        length >= SMART_CARD_DESCRIPTOR_SIZE &&
                        currentInterface == interfaceNumber ->
                        return CcidDescriptor(
                            features = rawDescriptors.int32(offset + FEATURES_OFFSET),
                            maxMessageSize = rawDescriptors.int32(offset + MAX_MESSAGE_SIZE_OFFSET),
                        )
                }
                offset += length
            }
            return null
        }

        private fun ByteArray.int32(at: Int): Int =
            (this[at].toInt() and 0xFF) or
                ((this[at + 1].toInt() and 0xFF) shl 8) or
                ((this[at + 2].toInt() and 0xFF) shl 16) or
                ((this[at + 3].toInt() and 0xFF) shl 24)
    }
}
