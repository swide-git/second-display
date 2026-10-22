package io.github.swiderekartmax.seconddisplay

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverIdentityTest {

    @Test
    fun `sanitizeServiceName returns default name for null blank or default strings`() {
        assertEquals(ReceiverIdentity.DEFAULT_SERVICE_NAME, ReceiverIdentity.sanitizeServiceName(null))
        assertEquals(ReceiverIdentity.DEFAULT_SERVICE_NAME, ReceiverIdentity.sanitizeServiceName(""))
        assertEquals(ReceiverIdentity.DEFAULT_SERVICE_NAME, ReceiverIdentity.sanitizeServiceName("   "))
        assertEquals(ReceiverIdentity.DEFAULT_SERVICE_NAME, ReceiverIdentity.sanitizeServiceName("Second Display"))
        assertEquals(ReceiverIdentity.DEFAULT_SERVICE_NAME, ReceiverIdentity.sanitizeServiceName("  Second Display  "))
    }

    @Test
    fun `sanitizeServiceName trims custom names and limits length`() {
        assertEquals("Living Room Display", ReceiverIdentity.sanitizeServiceName("  Living Room Display  "))
        val longName = "A".repeat(80)
        assertEquals("A".repeat(60), ReceiverIdentity.sanitizeServiceName(longName))
    }
}
