package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context and verify tool registry in full and store safe modes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("JARVIS", appName)

        val app = context as JarvisApplication
        val fullControlTools = app.container.toolRegistry.getAvailableTools(fullControlOverride = true)
        assertEquals(20, fullControlTools.size)
        assertTrue(fullControlTools.any { it.name == "flashlight" })
        assertTrue(fullControlTools.any { it.name == "call_contact" })
        assertTrue(fullControlTools.any { it.name == "send_sms" })
        assertTrue(fullControlTools.any { it.name == "read_screen" })
        assertTrue(fullControlTools.any { it.name == "tap_element" })
        assertTrue(fullControlTools.any { it.name == "send_whatsapp_or_telegram" })
        assertTrue(fullControlTools.any { it.name == "read_or_reply_notifications" })

        val storeSafeTools = app.container.toolRegistry.getAvailableTools(fullControlOverride = false)
        assertEquals(13, storeSafeTools.size)
        assertTrue(storeSafeTools.none { it.requiresFullControlMode })
    }
}
