package com.credenceid.tap2id.reference

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.credenceid.tap2id.reference.databinding.ActivityMainBinding
import com.credenceid.tap2idSdk.api.Tap2iDSdk
import kotlinx.coroutines.launch

/** The one screen: choose the reader, initialize, verify, and see what happened. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: VerifierViewModel by viewModels()
    private var renderedReader: Pair<ReaderChoice, Boolean>? = null

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) viewModel.verify(this) else viewModel.log(getString(R.string.permissions_denied))
        }

    private val usbChanged =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = renderReader(viewModel.state.value)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Also picks dark or light system bar icons to suit the theme.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        keepClearOfSystemBars()

        binding.sdkVersion.text = getString(R.string.sdk_version, Tap2iDSdk.getSdkVersion())
        binding.readerChoice.setOnCheckedChangeListener { _, checkedId ->
            viewModel.chooseReader(if (checkedId == R.id.readerUsb) ReaderChoice.USB_READER else ReaderChoice.ANDROID_NFC)
        }
        binding.initialize.setOnClickListener { viewModel.initialize() }
        binding.verify.setOnClickListener { if (viewModel.state.value.verifying) viewModel.cancel() else verifyWithPermissions() }
        binding.copyLog.setOnClickListener { copyLog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::render) }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
        ContextCompat.registerReceiver(this, usbChanged, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        renderReader(viewModel.state.value)
    }

    override fun onStop() {
        unregisterReceiver(usbChanged)
        super.onStop()
    }

    override fun onDestroy() {
        // The activity given to the SDK is going away, so the verification that holds it must end.
        // That includes a recreation for a configuration change the manifest does not handle.
        viewModel.cancel()
        super.onDestroy()
    }

    /** The ID data travels over Bluetooth, so its runtime permissions come before the tap. */
    private fun verifyWithPermissions() {
        val missing = bluetoothPermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) viewModel.verify(this) else permissionRequest.launch(missing.toTypedArray())
    }

    private fun bluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun render(state: UiState) {
        val usb = state.readerChoice == ReaderChoice.USB_READER
        binding.readerChoice.check(if (usb) R.id.readerUsb else R.id.readerAndroidNfc)
        binding.readerUsb.isEnabled = state.canChooseReader
        binding.readerAndroidNfc.isEnabled = state.canChooseReader
        // The reader line looks up USB devices, so it is redrawn only when what it shows can change;
        // plugging and unplugging redraw it through usbChanged.
        val readerKey = state.readerChoice to state.canChooseReader
        if (readerKey != renderedReader) {
            renderedReader = readerKey
            renderReader(state)
        }

        binding.initialize.isEnabled = state.canChooseReader
        binding.sdkStatus.text =
            when (val sdk = state.sdk) {
                SdkState.NotInitialized -> ""
                SdkState.Initializing -> getString(R.string.initializing)
                is SdkState.Ready -> getString(R.string.initialized, sdk.profileName ?: getString(R.string.no_profile))
                is SdkState.Failed -> sdk.message
            }

        binding.verify.isEnabled = state.canVerify
        binding.verify.setText(if (state.verifying) R.string.cancel else R.string.verify)
        binding.verifyHint.text =
            if (state.verifying) getString(if (usb) R.string.verify_hint_usb else R.string.verify_hint_nfc) else ""
        binding.stages.text = stageLines(state.stages)

        binding.result.text = state.result?.text ?: getString(R.string.no_result)
        val portrait = state.result?.portrait
        if (portrait != null) binding.portrait.setImageBitmap(portrait) else binding.portrait.setImageDrawable(null)
        binding.portrait.isVisible = portrait != null

        binding.log.text = state.log.joinToString("\n")
    }

    private fun renderReader(state: UiState) {
        val usbReader = viewModel.usbReader
        binding.readerStatus.text =
            when {
                state.readerChoice == ReaderChoice.ANDROID_NFC -> getString(R.string.reader_android_nfc_note)
                usbReader.isReaderAttached() ->
                    usbReader.readerInfo.let { getString(R.string.reader_usb_attached, "${it.vendor} ${it.model}") }
                else -> getString(R.string.reader_usb_missing)
            }.let { status -> if (state.canChooseReader) status else "$status\n${getString(R.string.reader_fixed_note)}" }
    }

    private fun stageLines(stages: List<StageLine>): CharSequence {
        val text = SpannableStringBuilder()
        stages.forEach { line ->
            if (text.isNotEmpty()) text.append('\n')
            val start = text.length
            text.append("${line.stage}: ${line.state.name.lowercase()}")
            val color =
                when (line.state) {
                    StageState.DONE -> R.color.stage_done
                    StageState.FAILED -> R.color.stage_failed
                    StageState.RUNNING, StageState.CANCELLED -> null
                }
            if (color != null) {
                text.setSpan(ForegroundColorSpan(getColor(color)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            line.detail?.let { text.append('\n').append(it) }
        }
        return text
    }

    private fun copyLog() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.section_log), binding.log.text))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    /** Apps that target Android 15 or later draw behind the system bars; pad the content clear of them. */
    private fun keepClearOfSystemBars() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }
}
