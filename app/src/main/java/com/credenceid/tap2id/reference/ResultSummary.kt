package com.credenceid.tap2id.reference

import android.graphics.Bitmap
import com.credenceid.tap2idSdk.core.model.VerificationResult
import com.credenceid.tap2idSdk.core.model.VerifiedDocument

/**
 * A verification result reduced to what this screen shows.
 *
 * @property text the outcome and the data elements the phone shared, one per line
 * @property portrait the holder's photo, if shared. The SDK delivers it decoded, as a [Bitmap].
 */
class ResultSummary(
    val text: String,
    val portrait: Bitmap?,
) {
    companion object {
        private const val PORTRAIT = "portrait"
        private const val MAX_VALUE_LENGTH = 120

        /** @param result what `onVerificationCompleted` delivered */
        fun from(result: VerificationResult): ResultSummary {
            val text =
                buildString {
                    appendLine("Verification: ${result.status}")
                    result.documents.forEach { appendDocument(it) }
                }.trimEnd()
            val portrait =
                result.documents
                    .asSequence()
                    .flatMap { it.nameSpaces }
                    .mapNotNull { it.attributes[PORTRAIT] as? Bitmap }
                    .firstOrNull()
            return ResultSummary(text, portrait)
        }

        private fun StringBuilder.appendDocument(document: VerifiedDocument) {
            val authentication = document.authentication
            appendLine()
            appendLine(document.docType)
            appendLine("  Authentication: ${authentication.authenticationStatus}")
            appendLine("  Issuer: ${authentication.trustAttributes.chainStatus}")
            document.ageAttestationPassed?.let { appendLine("  Age attestation: ${if (it) "passed" else "failed"}") }
            authentication.validationErrors.forEach { appendLine("  Validation error: $it") }
            document.nameSpaces.forEach { nameSpace ->
                appendLine("  ${nameSpace.name}")
                nameSpace.attributes.forEach { (name, value) -> appendLine("    $name: ${describe(value)}") }
            }
        }

        /** Shows a data element's value on one line; images and binary values are not printed. */
        internal fun describe(value: Any?): String =
            when (value) {
                null -> "(not shared)"
                is Bitmap -> "<image>"
                is ByteArray -> "<${value.size} bytes>"
                else -> value.toString().let { if (it.length > MAX_VALUE_LENGTH) it.take(MAX_VALUE_LENGTH) + "…" else it }
            }
    }
}
