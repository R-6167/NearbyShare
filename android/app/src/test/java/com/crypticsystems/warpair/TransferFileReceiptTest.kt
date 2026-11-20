package com.crypticsystems.warpair

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class TransferFileReceiptTest {
    @Test
    fun acceptsReceiptForSameLogicalFile() {
        TransferFileReceipt.requireAccepted("file-1", true, TransferFileReceipt.reason("file-1"))
    }

    @Test
    fun rejectsReceiptForDifferentFile() {
        assertThrows(IOException::class.java) {
            TransferFileReceipt.requireAccepted("file-1", true, TransferFileReceipt.reason("file-2"))
        }
    }

    @Test
    fun rejectsMissingOrNegativeReceipt() {
        assertThrows(IOException::class.java) {
            TransferFileReceipt.requireAccepted("file-1", false, "file_complete:file-1")
        }
        assertThrows(IOException::class.java) {
            TransferFileReceipt.requireAccepted("file-1", true, null)
        }
    }
}
