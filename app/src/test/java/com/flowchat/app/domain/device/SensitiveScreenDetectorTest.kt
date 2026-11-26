package com.flowchat.app.domain.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveScreenDetectorTest {
    @Test
    fun detectsPasswordFieldsWithoutRetainingTheirContents() {
        val elements = listOf(
            element(text = "123456", editable = true, password = true)
        )

        val result = SensitiveScreenDetector.detect("com.example.app", "Login", elements)

        assertTrue(result.sensitive)
        assertTrue(result.reason == "password_field")
    }

    @Test
    fun detectsPaymentPackagesAndAllowsOrdinarySettingsPages() {
        assertTrue(
            SensitiveScreenDetector.detect("com.example.wallet", "Checkout", emptyList()).sensitive
        )
        assertFalse(
            SensitiveScreenDetector.detect(
                "com.android.settings",
                "Settings",
                listOf(element(text = "Password and security", editable = false, password = false))
            ).sensitive
        )
    }

    private fun element(text: String, editable: Boolean, password: Boolean) = DeviceUiElement(
        index = 0,
        className = "android.widget.EditText",
        text = text,
        contentDescription = null,
        resourceId = null,
        bounds = DeviceBounds(0, 0, 100, 100),
        clickable = true,
        editable = editable,
        scrollable = false,
        enabled = true,
        password = password
    )
}
