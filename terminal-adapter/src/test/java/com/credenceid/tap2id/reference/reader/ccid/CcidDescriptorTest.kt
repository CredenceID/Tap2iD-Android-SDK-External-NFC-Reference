package com.credenceid.tap2id.reference.reader.ccid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CcidDescriptorTest {
    @Test
    fun `the smart card descriptor of the wanted interface is found`() {
        // Arrange: a device, one configuration, and a smart card interface with three endpoints.
        val descriptors = DEVICE + CONFIGURATION + usbInterface(0) + smartCard(EXTENDED_APDU, maxMessageSize = 65_546) + ENDPOINTS

        // Act
        val descriptor = CcidDescriptor.find(descriptors, interfaceNumber = 0)

        // Assert
        assertEquals(CcidDescriptor(features = EXTENDED_APDU, maxMessageSize = 65_546), descriptor)
        assertTrue(descriptor!!.exchangesApdus)
    }

    @Test
    fun `a reader with a contact and a contactless interface gives each its own descriptor`() {
        // Arrange
        val descriptors =
            DEVICE + CONFIGURATION +
                usbInterface(0) + smartCard(TPDU, maxMessageSize = 271) + ENDPOINTS +
                usbInterface(1) + smartCard(SHORT_APDU, maxMessageSize = 271) + ENDPOINTS

        // Act
        val contact = CcidDescriptor.find(descriptors, interfaceNumber = 0)
        val contactless = CcidDescriptor.find(descriptors, interfaceNumber = 1)

        // Assert
        assertFalse(contact!!.exchangesApdus)
        assertTrue(contactless!!.exchangesApdus)
    }

    @Test
    fun `an interface without a smart card descriptor has none`() {
        // Arrange
        val descriptors = DEVICE + CONFIGURATION + usbInterface(0) + ENDPOINTS

        // Act + Assert
        assertNull(CcidDescriptor.find(descriptors, interfaceNumber = 0))
    }

    @Test
    fun `descriptors that are cut short are not read past their end`() {
        // Arrange
        val whole = DEVICE + CONFIGURATION + usbInterface(0) + smartCard(SHORT_APDU, maxMessageSize = 271)
        val cut = whole.copyOf(whole.size - 10)

        // Act + Assert
        assertNull(CcidDescriptor.find(cut, interfaceNumber = 0))
    }

    private companion object {
        const val TPDU = 0x00010000
        const val SHORT_APDU = 0x00020000
        const val EXTENDED_APDU = 0x00040000

        val DEVICE = ByteArray(18).also {
            it[0] = 18
            it[1] = 0x01
        }
        val CONFIGURATION = ByteArray(9).also {
            it[0] = 9
            it[1] = 0x02
        }
        val ENDPOINTS = ByteArray(21).also {
            for (start in 0 until 21 step 7) {
                it[start] = 7
                it[start + 1] = 0x05
            }
        }

        fun usbInterface(number: Int) =
            ByteArray(9).also {
                it[0] = 9
                it[1] = 0x04
                it[2] = number.toByte()
                it[5] = 0x0B
            }

        fun smartCard(
            features: Int,
            maxMessageSize: Int,
        ) = ByteArray(0x36).also {
            it[0] = 0x36
            it[1] = 0x21
            it.putInt32(40, features)
            it.putInt32(44, maxMessageSize)
        }

        private fun ByteArray.putInt32(
            at: Int,
            value: Int,
        ) {
            for (index in 0 until 4) this[at + index] = (value ushr (8 * index)).toByte()
        }
    }
}
