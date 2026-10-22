package io.github.swiderekartmax.seconddisplay

import org.junit.Assert.assertEquals
import org.junit.Test

class PairedSendersTest {
    @Test fun `adding the same key twice keeps one entry with the latest name`() {
        val once = PairedSenders.add(emptySet(), "AAAA+key/1=", "MacBook")
        val twice = PairedSenders.add(once, "AAAA+key/1=", "MacBook Pro")
        assertEquals(setOf("AAAA+key/1=\tMacBook Pro"), twice)
        assertEquals(listOf("AAAA+key/1="), PairedSenders.keys(twice))
    }

    @Test fun `a tab in the sender name cannot corrupt the stored key`() {
        val stored = PairedSenders.add(emptySet(), "key", "Office\tMac")
        assertEquals(listOf("key"), PairedSenders.keys(stored))
        assertEquals(setOf("key\tOffice Mac"), stored)
    }

    @Test fun `keys ignore empty entries`() {
        assertEquals(listOf("a", "b"), PairedSenders.keys(setOf("a\tMac", "", "b\tiPad")))
    }
}
