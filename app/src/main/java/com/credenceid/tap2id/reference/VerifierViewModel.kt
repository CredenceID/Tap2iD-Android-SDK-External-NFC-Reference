package com.credenceid.tap2id.reference

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.credenceid.tap2id.reference.reader.UsbCcidReader
import com.credenceid.tap2idSdk.api.InitSdkResultListener
import com.credenceid.tap2idSdk.api.MdocVerificationListener
import com.credenceid.tap2idSdk.api.Tap2iDSdk
import com.credenceid.tap2idSdk.api.models.EngagementConfig
import com.credenceid.tap2idSdk.api.models.NfcConfig
import com.credenceid.tap2idSdk.api.models.SdkConfigBuilder
import com.credenceid.tap2idSdk.api.models.SdkInitializationResult
import com.credenceid.tap2idSdk.api.models.VerificationStage
import com.credenceid.tap2idSdk.core.model.VerificationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Everything this app does with the Tap2iD SDK: initialize it once, with or without a
 * `ContactlessReader`, then run and cancel verifications.
 */
class VerifierViewModel(application: Application) : AndroidViewModel(application) {
    /**
     * The adapter for the USB reader. Like the SDK it lives as long as the process: the SDK keeps
     * the instance it is given and calls attach and release on it for every verification.
     */
    val usbReader = UsbCcidReader(application)

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private var verification: Job? = null

    fun chooseReader(choice: ReaderChoice) {
        if (state.value.canChooseReader) mutableState.update { it.copy(readerChoice = choice) }
    }

    /** Initializes the SDK with the reader that is chosen. Safe to call again after a failure. */
    fun initialize() {
        val apiKey = BuildConfig.TAP2ID_API_KEY
        if (apiKey.isBlank()) {
            val message = getApplication<Application>().getString(R.string.missing_api_key)
            mutableState.update { it.copy(sdk = SdkState.Failed(message)) }
            return
        }
        val choice = state.value.readerChoice
        mutableState.update { it.copy(sdk = SdkState.Initializing) }
        log("Initializing with $choice")

        viewModelScope.launch {
            val config =
                SdkConfigBuilder()
                    .setApiKey(apiKey)
                    .setApplicationContext(getApplication())
                    // This line is the whole integration: with it the SDK taps through your adapter,
                    // without it (null) the SDK uses Android's NFC.
                    .setContactlessReader(usbReader.takeIf { choice == ReaderChoice.USB_READER })
                    .build()

            Tap2iDSdk.initSdk(
                config,
                object : InitSdkResultListener {
                    override fun onInitializationSuccess(result: SdkInitializationResult) {
                        val profile = result.licenseVerificationResult.profileName
                        log("Initialized, profile $profile")
                        mutableState.update { it.copy(sdk = SdkState.Ready(profile)) }
                    }

                    override fun onInitializationFailure(error: Error) {
                        log("Initialization failed: ${error.message}")
                        mutableState.update { it.copy(sdk = SdkState.Failed(error.message.orEmpty())) }
                    }
                },
            )
        }
    }

    /**
     * Runs one verification. The call is the same whichever reader does the tap.
     *
     * @param activity passed to the SDK in `NfcConfig`; the SDK hands it to the adapter's `attach`
     */
    fun verify(activity: Activity) {
        if (verification?.isActive == true || !state.value.canVerify) return
        mutableState.update { it.copy(verifying = true, stages = emptyList(), result = null) }
        log("Verification started")

        verification =
            viewModelScope.launch {
                try {
                    Tap2iDSdk.verifyMdoc(EngagementConfig(nfcConfig = NfcConfig(activity)), listener)
                } catch (cancelled: CancellationException) {
                    log("Verification cancelled")
                    markRunningStagesCancelled()
                    throw cancelled
                } finally {
                    mutableState.update { it.copy(verifying = false) }
                }
            }
    }

    /**
     * Cancels the running verification. Cancelling the coroutine is all the SDK needs: it releases
     * the reader, closes Bluetooth and makes no further listener calls.
     */
    fun cancel() {
        verification?.cancel()
    }

    // The SDK calls the listener from background threads; StateFlow takes updates from any thread.
    private val listener =
        object : MdocVerificationListener {
            override fun onVerificationStageStarted(stage: VerificationStage) {
                log("$stage started")
                setStage(StageLine(stage, StageState.RUNNING))
            }

            override fun onVerificationStageCompleted(stage: VerificationStage) {
                log("$stage completed")
                setStage(StageLine(stage, StageState.DONE))
            }

            override fun onVerificationStageError(
                stage: VerificationStage,
                error: Throwable,
            ) {
                // A reader problem arrives here for NFC_ENGAGEMENT, as "[Error - nnn] ... Reader: ...".
                log("$stage failed: ${error.message}")
                setStage(StageLine(stage, StageState.FAILED, error.message))
            }

            override fun onVerificationCompleted(verificationResult: VerificationResult) {
                log("Completed: ${verificationResult.status}")
                mutableState.update { it.copy(result = ResultSummary.from(verificationResult)) }
            }
        }

    private fun setStage(line: StageLine) {
        mutableState.update { state ->
            val others = state.stages.filterNot { it.stage == line.stage }
            state.copy(stages = others + line)
        }
    }

    private fun markRunningStagesCancelled() {
        mutableState.update { state ->
            state.copy(
                stages = state.stages.map { if (it.state == StageState.RUNNING) it.copy(state = StageState.CANCELLED) else it },
            )
        }
    }

    /** Adds a line to the on-screen log and to Logcat. */
    fun log(message: String) {
        Log.i(TAG, message)
        val line = "${LocalTime.now().format(TIME)}  $message"
        mutableState.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }

    private companion object {
        const val TAG = "ReaderReference"
        const val MAX_LOG_LINES = 200
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
