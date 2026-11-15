package com.credenceid.tap2id.reference

import com.credenceid.tap2idSdk.api.models.VerificationStage

/** Which NFC does the tap. */
enum class ReaderChoice {
    /** A USB reader behind the `ContactlessReader` adapter: the case this project demonstrates. */
    USB_READER,

    /** The device's own NFC, with no adapter. Kept to compare the two on the same phone. */
    ANDROID_NFC,
}

/** Where SDK initialization stands. The SDK is initialized once per process. */
sealed interface SdkState {
    data object NotInitialized : SdkState

    data object Initializing : SdkState

    /** @property profileName the verification profile the API key selected */
    data class Ready(val profileName: String?) : SdkState

    /** @property message why initialization failed; it can be tried again */
    data class Failed(val message: String) : SdkState
}

enum class StageState { RUNNING, DONE, FAILED, CANCELLED }

/**
 * One line of verification progress.
 *
 * @property detail the SDK's error message when the stage failed
 */
data class StageLine(
    val stage: VerificationStage,
    val state: StageState,
    val detail: String? = null,
)

/** Everything the screen shows. */
data class UiState(
    val readerChoice: ReaderChoice = ReaderChoice.USB_READER,
    val sdk: SdkState = SdkState.NotInitialized,
    val verifying: Boolean = false,
    val stages: List<StageLine> = emptyList(),
    val result: ResultSummary? = null,
    val log: List<String> = emptyList(),
) {
    /** The reader can only be chosen before the SDK is initialized. */
    val canChooseReader: Boolean
        get() = sdk is SdkState.NotInitialized || sdk is SdkState.Failed

    val canVerify: Boolean
        get() = sdk is SdkState.Ready
}
