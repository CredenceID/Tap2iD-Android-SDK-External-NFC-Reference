# Tap2iD Android SDK External NFC Reference

A small Android app that verifies mobile IDs with the Tap2iD SDK through a contactless reader that
is **not** Android's own NFC. It is two things:

- **A test bench.** Plug a USB contactless smart card reader into an Android phone and verify a
  mobile ID with it. No terminal hardware is needed to see the whole flow work.
- **A reference adapter.** `UsbCcidReader` is a complete implementation of the SDK's
  `ContactlessReader` interface. If you make a terminal with its own NFC SDK, your adapter has the
  same shape; this one shows where each piece goes.

It goes with the integration guide,
[*Connect your terminal with ContactlessReader*](docs/Tap2iD-Android-SDK-External-NFC-Integration-Guide.pdf)
(PDF, in `docs/`). The guide tells you what to build; this project is one that has been built.

## What you need

| | |
|---|---|
| Android device | Android 10 or later, 64-bit ARM, Bluetooth Low Energy, USB host (OTG) |
| Reader | A USB contactless reader of the CCID class that exchanges APDUs, and a USB OTG adapter if its plug does not fit the device |
| Mobile ID | A second phone with a test mobile ID in a wallet |
| Tap2iD | Your API key and the package name it was issued for |
| Tools | JDK 17, Android SDK 36, Kotlin 2.2 or later |

This project was tested with an HID OMNIKEY 5427 CK on a Pixel 9 Pro XL running Android 16.

## Run it

1. Create `local.properties` in the project root:

   ```properties
   sdk.dir=/path/to/Android/sdk
   tap2id.apiKey=<your API key>
   tap2id.applicationId=<the package name the key was issued for>
   ```

   The API key only works for the package name it was issued for, which is why both live here.

2. `gradle/libs.versions.toml` pins `tap2idSdk` to 2.3.0, the first version with
   `ContactlessReader`. Change it there to use a later one. The SDK and its libraries come from the
   Credence ID Maven repository in `settings.gradle.kts`, which needs no credentials.

3. Build and install:

   ```bash
   ./gradlew :app:installDebug
   ```

   Use `:app:installRelease` for the minified build, which is the one to test before you ship.

4. Plug the reader into the device. The device's USB port is now taken, so use
   [wireless debugging](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi) if
   you want Logcat.

5. In the app, keep **USB contactless reader** selected, tap **Initialize SDK**, then
   **Verify mobile ID**, and hold the phone with the mobile ID on the reader until the result shows.
   Android asks for permission to use the USB reader: either when you plug it in, by offering to
   open this app for it, or on the first verification.

The **Android NFC** choice runs the same verification through the device's own NFC, with no adapter.
It is there so you can compare the two with the same phone. The SDK is initialized once per launch,
so close the app to switch.

## Where the guide's steps are in this project

| Guide step | Here |
|---|---|
| Create the adapter module, SDK as `compileOnly` | [`terminal-adapter/build.gradle.kts`](terminal-adapter/build.gradle.kts) |
| Implement `ContactlessReader` | [`UsbCcidReader.kt`](terminal-adapter/src/main/java/com/credenceid/tap2id/reference/reader/UsbCcidReader.kt) |
| Add the dependencies to your app | [`app/build.gradle.kts`](app/build.gradle.kts) |
| Pass your adapter to the SDK | `initialize()` in [`VerifierViewModel.kt`](app/src/main/java/com/credenceid/tap2id/reference/VerifierViewModel.kt) |
| Verify as usual | `verify()` in the same file |
| Test on the terminal | [The checks](#the-checks) below |

`Tap2iDSdk.initSdk` and `Tap2iDSdk.verifyMdoc` are Kotlin `suspend` functions. Call them from a
coroutine, as `VerifierViewModel` does with `viewModelScope.launch`.

## Writing your own adapter

Copy the `terminal-adapter` module, add your terminal's NFC SDK as a second `compileOnly`
dependency, and replace `UsbCcidReader` with a class that does the same five jobs with your SDK.
`CcidLink.kt` and everything under `ccid/` and `usb/` is specific to USB readers and goes away.

```kotlin
import com.credenceid.tap2idSdk.api.reader.CardPresenceListener
import com.credenceid.tap2idSdk.api.reader.ContactlessReader
import com.credenceid.tap2idSdk.api.reader.ReaderAvailability
import com.credenceid.tap2idSdk.api.reader.ReaderException
import com.credenceid.tap2idSdk.api.reader.ReaderException.Reason
import com.credenceid.tap2idSdk.api.reader.ReaderInfo
```

### What the SDK calls, and when

| Call | Your job | In `UsbCcidReader` |
|---|---|---|
| `attach(activity)` | Open the NFC. It may block. | Opens the USB reader |
| `availability(context)` | Say whether the NFC can poll | `DISCONNECTED` if unplugged, `BUSY` if another app holds it |
| `startDetection(listener)` | Start polling; report a phone when it is ready for APDUs, and an NFC that fails meanwhile | A polling thread that powers the card on, then calls `onCardDetected`; `onReaderError` if the reader is unplugged |
| `transceive(apdu, timeoutMs)` | Send one APDU, return the response | One CCID exchange |
| `release()` | Stop polling, free the NFC | Stops the thread, closes USB |

For every verification:

- **Order.** `attach`, then `availability`. Only if that returned `AVAILABLE`: `startDetection`, then
  `transceive` several times.
- **`release` is called exactly once per `attach`**, whatever happened in between: a finished tap, a
  reader that was not available, an error, or a cancelled verification.
- **`release` comes as soon as the tap is over**, before the Bluetooth transfer starts. Your NFC is
  free for payments while the ID data is read.
- **Threads.** The SDK makes all five calls on background threads, never on the main thread.
  `transceive` runs on a thread of its own, one call at a time, and never on the thread that called
  `onCardDetected`. `timeoutMs` is 5000.
- **`release` can arrive while you are polling or inside `transceive`**, but never while `attach` is
  still running. A cancelled verification waits for `attach` to return, so give any wait in `attach`
  an upper bound.
- **`readerInfo` can be read at any time**, also before `attach` and after `release`. Return what you
  know without blocking.

### What goes through `transceive`

The SDK reads the phone as an NFC Forum Type 4 Tag. It selects the NDEF application
(AID `D2 76 00 00 85 01 01`) and sends SELECT, READ BINARY and UPDATE BINARY commands.

| | |
|---|---|
| Longest command | 260 bytes (short APDU: 5-byte header and up to 255 data bytes) |
| Longest response | 257 bytes (up to 255 data bytes and SW1 SW2) |
| Extended-length APDUs | Not used |
| GET RESPONSE (`61xx`) | Not used. The SDK expects the whole response in one exchange. |

Your NFC must carry these APDUs over ISO 14443-4 to a phone, Type A at least. If your terminal only
lets payment AIDs through, the NDEF application's AID has to be allowed.

### The rules, and how this adapter keeps them

1. **Return the response untouched.** SW1 SW2 stay on it. A status other than `9000` is a response,
   not an error. `UsbCcidReader.transceive` returns exactly what the card sent.
2. **Don't throw from `attach` for a reader that is missing or busy.** Remember it and report it
   from `availability`. The SDK then tells the application which of the two it is.
3. **Throw `ReaderException` for transport failures, with the reason that fits:**

   | Reason | When |
   |---|---|
   | `TIMEOUT` | No response within `timeoutMs` |
   | `CARD_LOST` | The phone left the field or stopped answering |
   | `READER_DISCONNECTED` | The NFC service or the reader went away |
   | `RELEASED` | `release()` arrived while `transceive` was waiting |

   For a failure that is none of these, such as an error code of your SDK that you cannot place,
   throw a plain `IOException` with the code in its message. The SDK reports it as error 132 and
   quotes your message. `asReaderException` in `UsbCcidReader` is this mapping for USB.
4. **Let `release()` end a waiting `transceive` at once.** Here, closing the USB link makes the
   pending read fail, and `transceive` throws `RELEASED`. With a callback SDK, cancel the future or
   latch that `transceive` waits on, and check for a release that landed just before the wait began.
5. **Call `onCardDetected` when the phone is ready for APDUs**, so run any activation your SDK needs
   first. The SDK acts on the first phone you report. Anything you report after that in the same
   verification is ignored, so you may stop polling there or keep going until `release`.
6. **Callbacks may come from any thread**, including your SDK's own callback thread. The SDK never
   exchanges APDUs on the thread that called `onCardDetected`, so an SDK that delivers its APDU
   responses on that same thread does not deadlock.

What an exception becomes, when `attach`, `availability`, `startDetection` or `transceive` throws:

| You throw | The application gets |
|---|---|
| `ReaderException` | The error for its reason: 133, 134 or 137 (132 for `RELEASED`) |
| Any other `IOException` | Error 132 with your message |
| Anything else | Error 136, naming the method and the exception |

In every case the verification ends, `release` is called, and the application does not crash. An
exception from `release` itself is logged and otherwise ignored. The
SDK also backs you up: it gives up on a `transceive` that runs half a second past its timeout,
rejects a response shorter than two bytes, and ignores detection events that arrive after `release`.

Outside those four calls there is one more failure to report: an NFC that fails **while you wait
for a phone**, after `availability` said `AVAILABLE`. Its service dies, the reader is unplugged, or a
payment takes the NFC. Tell the listener, and the verification ends with the matching error:

```kotlin
listener.onReaderError(ReaderException(Reason.READER_DISCONNECTED, "NFC service died"))
```

If you don't, the verification keeps waiting until the application cancels it. The `poll` function
of `UsbCcidReader` does this when the reader is unplugged.

### Test your adapter without hardware

[`UsbCcidReaderTest`](terminal-adapter/src/test/java/com/credenceid/tap2id/reference/reader/UsbCcidReaderTest.kt)
drives the adapter through `attach`, `startDetection`, `transceive` and `release` against a
simulated reader, on the JVM. The cases to copy are the last ones a real terminal shows you: a
phone that leaves mid-exchange, a reader that disappears, and `release` arriving during a wait.

```bash
./gradlew :terminal-adapter:testDebugUnitTest
```

## Errors your application can receive

A reader problem arrives at `MdocVerificationListener.onVerificationStageError` for
`VerificationStage.NFC_ENGAGEMENT`. The message contains `[Error - nnn]` and ends with
`Reader: <vendor> <model> (firmware x, adapter y)`, built from your `readerInfo`.

| Code | Meaning | What to check |
|---|---|---|
| 132 | The reader reported an I/O error, or was released before the tap finished | The cause in the message |
| 133 | The phone left the reader before the tap finished | Hold the phone still until the result shows |
| 134 | The reader is not connected | Cable, power, the NFC service, USB permission |
| 135 | The reader is in use by another app | Close the other app, or wait for the payment to finish |
| 136 | The adapter threw something other than an `IOException` | A bug in the adapter; the message names the method and the exception |
| 137 | The reader did not answer an APDU within 5 seconds | The phone was moved, or the reader is stuck |
| 138 | The adapter returned a response without SW1 SW2 | Don't strip the status bytes |

One verification is one tap. A tap that fails ends the verification with one of these errors; a
second tap does not continue it. Start a new verification to try again, and `attach` is called again.

## The checks

Run these on your hardware with a release build before you ship.

| Check | Do this | Passes when |
|---|---|---|
| Read an ID | Verify, and hold the phone on the reader | The result shows |
| Read twice | Run two verifications in a row with the same phone | Both complete |
| Cancel, then retry | Start a verification, tap **Cancel** before you tap the phone, then verify again | The second one completes |
| Reader missing | Unplug the reader and verify | Error 134 |
| Reader lost while waiting | Start a verification, then unplug the reader before you tap | Error 134 within a second, without cancelling |
| Reader busy | Hold the NFC with another app and verify | Error 135 |
| Phone pulled away | Lift the phone as soon as the tap starts | Error 133, and the next verification works |

## Troubleshooting

- **"No USB smart card reader is plugged in."** Check the OTG adapter. Some readers ship in keyboard
  mode and must be switched to CCID mode with the maker's tool. `adb shell dumpsys usb` lists what
  Android sees.
- **Error 134 mentioning TPDUs.** The reader leaves the card protocol to the host. This adapter
  supports readers that exchange whole APDUs, which most contactless CCID readers do.
- **Initialization fails.** The API key and `tap2id.applicationId` do not belong together, or the
  device has no network.
- **The tap works and the connection stage fails.** Bluetooth is off, or its permission was denied.
- **The phone is not detected.** Wake and unlock it, and open the wallet if your wallet needs that
  to present over NFC.
- **Several devices on adb.** `installDebug` installs on all of them. Set `ANDROID_SERIAL` to the
  one you mean.

## Project layout

```
terminal-adapter/   the adapter library; compiles against the SDK, ships without it
  reader/UsbCcidReader.kt        the ContactlessReader implementation: the part you rewrite
  reader/CcidLink.kt             how UsbCcidReader reaches a USB reader, and what the tests fake
  reader/ccid/                   the CCID messages a tap needs, plain Kotlin
  reader/usb/                    Android USB host: permission, endpoints, transfers
app/                the application
  VerifierViewModel.kt           initialize, verify, cancel
  MainActivity.kt                the screen
```

## License

This project is licensed under the [Apache License 2.0](LICENSE), so you can copy the adapter into
your own code. The Tap2iD SDK it depends on is not covered by this license; it is licensed separately
by Credence ID.
