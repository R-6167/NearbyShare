package com.crypticsystems.warpair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class TransferBatchIdentityTest {
    private fun file(name: String, size: Long, id: String) = LanLink.OutgoingFile(name, size, id) {
        ByteArrayInputStream(ByteArray(0))
    }

    @Test
    fun sameOrderedSelectionHasSameIdentityAcrossRetries() {
        val first = listOf(file("one.txt", 10, "file-one"), file("two.txt", 20, "file-two"))
        val retry = listOf(file("one.txt", 10, "file-one"), file("two.txt", 20, "file-two"))
        assertEquals(TransferBatchIdentity.from(first), TransferBatchIdentity.from(retry))
    }

    @Test
    fun differentSelectionOrOrderHasDifferentIdentity() {
        val first = listOf(file("one.txt", 10, "file-one"), file("two.txt", 20, "file-two"))
        val reordered = first.reversed()
        val changed = listOf(file("one.txt", 11, "file-one"), file("two.txt", 20, "file-two"))
        assertNotEquals(TransferBatchIdentity.from(first), TransferBatchIdentity.from(reordered))
        assertNotEquals(TransferBatchIdentity.from(first), TransferBatchIdentity.from(changed))
    }

    @Test
    fun rejectsEmptyOrDuplicateFileIdentity() {
        assertThrows(IllegalArgumentException::class.java) { TransferBatchIdentity.from(emptyList()) }
        val duplicate = listOf(file("one.txt", 10, "same"), file("two.txt", 20, "same"))
        assertThrows(IllegalArgumentException::class.java) { TransferBatchIdentity.from(duplicate) }
    }
}
