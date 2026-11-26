package com.credenceid.tap2id.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateTest {
    private fun stateWith(sdk: SdkState) = UiState(sdk = sdk)

    @Test
    fun `a new state starts on the USB reader, not initialized and idle`() {
        // Arrange + Act
        val state = UiState()

        // Assert
        assertEquals(ReaderChoice.USB_READER, state.readerChoice)
        assertEquals(SdkState.NotInitialized, state.sdk)
        assertFalse(state.verifying)
        assertTrue(state.stages.isEmpty())
        assertNull(state.result)
        assertTrue(state.log.isEmpty())
    }

    @Test
    fun `the reader can be chosen before initialization`() {
        // Arrange + Act
        val state = stateWith(SdkState.NotInitialized)

        // Assert
        assertTrue(state.canChooseReader)
    }

    @Test
    fun `the reader can be chosen again after an initialization failure`() {
        // Arrange + Act
        val state = stateWith(SdkState.Failed("no network"))

        // Assert
        assertTrue(state.canChooseReader)
    }

    @Test
    fun `the reader cannot be chosen while the SDK initializes`() {
        // Arrange + Act
        val state = stateWith(SdkState.Initializing)

        // Assert
        assertFalse(state.canChooseReader)
    }

    @Test
    fun `the reader cannot be chosen once the SDK is ready`() {
        // Arrange + Act
        val state = stateWith(SdkState.Ready("profile"))

        // Assert
        assertFalse(state.canChooseReader)
    }

    @Test
    fun `a verification can start once the SDK is ready`() {
        // Arrange + Act
        val state = stateWith(SdkState.Ready("profile"))

        // Assert
        assertTrue(state.canVerify)
    }

    @Test
    fun `a verification can start when the profile has no name`() {
        // Arrange + Act
        val state = stateWith(SdkState.Ready(null))

        // Assert
        assertTrue(state.canVerify)
    }

    @Test
    fun `a verification cannot start before the SDK is ready`() {
        // Arrange
        val notReady = listOf(SdkState.NotInitialized, SdkState.Initializing, SdkState.Failed("boom"))

        // Act + Assert
        notReady.forEach { assertFalse("$it", stateWith(it).canVerify) }
    }

    @Test
    fun `copying one field leaves the others unchanged`() {
        // Arrange
        val state = UiState(sdk = SdkState.Ready("p"), log = listOf("line"))

        // Act
        val copy = state.copy(verifying = true)

        // Assert
        assertTrue(copy.verifying)
        assertEquals(state.sdk, copy.sdk)
        assertEquals(state.log, copy.log)
    }
}
