package com.credenceid.tap2id.reference

import android.graphics.Bitmap
import com.credenceid.tap2idSdk.core.model.AuthenticationResult
import com.credenceid.tap2idSdk.core.model.MsoStatus
import com.credenceid.tap2idSdk.core.model.MsoValidity
import com.credenceid.tap2idSdk.core.model.SecurityChecks
import com.credenceid.tap2idSdk.core.model.TrustAttributes
import com.credenceid.tap2idSdk.core.model.TrustStatus
import com.credenceid.tap2idSdk.core.model.VerificationResult
import com.credenceid.tap2idSdk.core.model.VerificationStatus
import com.credenceid.tap2idSdk.core.model.VerifiedDocument
import com.credenceid.tap2idSdk.core.model.VerifiedNamespace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultSummaryTest {
    // Bitmap has no public constructor; the android.jar that unit tests run against allows this.
    private val portrait: Bitmap = Bitmap::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private fun result(attributes: Map<String, Any?>) =
        VerificationResult(
            documents =
                listOf(
                    VerifiedDocument(
                        docType = "org.iso.18013.5.1.mDL",
                        nameSpaces = listOf(VerifiedNamespace("org.iso.18013.5.1", attributes)),
                        authentication =
                            AuthenticationResult(
                                securityChecks = SecurityChecks(isIssuerSignedValid = true, isDeviceSignedValid = true, areDigestsValid = true),
                                msoValidity = MsoValidity(MsoStatus.VALID),
                                trustAttributes = TrustAttributes(isIssuerTrusted = true, chainStatus = TrustStatus.VERIFIED),
                            ),
                        ageAttestationPassed = true,
                    ),
                ),
            status = VerificationStatus.SUCCESS,
        )

    @Test
    fun `the summary lists the outcome, the document and what the phone shared`() {
        // Arrange
        val result = result(mapOf("family_name" to "Doe", "age_over_21" to true))

        // Act
        val summary = ResultSummary.from(result)

        // Assert
        val expected =
            """
            Verification: SUCCESS

            org.iso.18013.5.1.mDL
              Authentication: SUCCESS
              Issuer: VERIFIED
              Age attestation: passed
              org.iso.18013.5.1
                family_name: Doe
                age_over_21: true
            """.trimIndent()
        assertEquals(expected, summary.text)
        assertNull(summary.portrait)
    }

    @Test
    fun `the portrait is handed over as the image and not printed`() {
        // Arrange
        val result = result(mapOf("portrait" to portrait))

        // Act
        val summary = ResultSummary.from(result)

        // Assert
        assertSame(portrait, summary.portrait)
        assertTrue(summary.text.contains("portrait: <image>"))
    }

    @Test
    fun `a binary value is shown by size only`() {
        // Arrange
        val result = result(mapOf("signature_usual_mark" to ByteArray(2048)))

        // Act
        val summary = ResultSummary.from(result)

        // Assert
        assertNull(summary.portrait)
        assertTrue(summary.text.contains("signature_usual_mark: <2048 bytes>"))
    }

    @Test
    fun `a long value is cut and an unshared one says so`() {
        // Arrange + Act + Assert
        assertEquals("x".repeat(120) + "…", ResultSummary.describe("x".repeat(500)))
        assertEquals("(not shared)", ResultSummary.describe(null))
    }
}
