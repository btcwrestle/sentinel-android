package com.samourai.sentinel.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #208: deleteAll must clear every on-disk copy of a payload - the
 * primary, the rotated .bak, and any staged .tmp - because clearDojo
 * previously deleted only the primary, leaving the pairing payload
 * (and its node API key) sitting in the .bak after a Dojo removal or
 * a network switch.
 *
 * The three files are planted directly, not written through
 * PayloadRecord.write: the write path routes through encrypt() ->
 * AccessFactory pin state, which this test deliberately does not
 * exercise. The delete path needs no decryption.
 *
 * Lock reentrancy against a concurrent writer is not unit-testable
 * here - covered by the per-path ReentrantLock the write path already
 * uses, and by the device regression leg.
 */
class PayloadRecordDeleteAllTest {

    private fun freshRecord(): Pair<File, PayloadRecord> {
        val dir = File.createTempFile("sentinel_deleteAll_", "")
        dir.delete()
        dir.mkdirs()
        return dir to PayloadRecord(dir.absolutePath, "dojo.payload")
    }

    @Test
    fun `deletes primary, backup and staged temp file`() {
        val (dir, record) = freshRecord()
        File(dir, "dojo.payload").writeText("primary")
        File(dir, "dojo.payload.bak").writeText("rotated")
        File(dir, "dojo.payload.tmp").writeText("staged")

        assertTrue(record.deleteAll())

        assertFalse(File(dir, "dojo.payload").exists())
        assertFalse(File(dir, "dojo.payload.bak").exists())
        assertFalse(File(dir, "dojo.payload.tmp").exists())
    }

    @Test
    fun `deleteAll is idempotent - a payload that never existed is gone`() {
        val (dir, record) = freshRecord()

        assertTrue(record.deleteAll())
        assertTrue(record.deleteAll())
        assertFalse(File(dir, "dojo.payload").exists())
    }

    @Test
    fun `partial residue - primary and bak only`() {
        val (dir, record) = freshRecord()
        File(dir, "dojo.payload").writeText("primary")
        File(dir, "dojo.payload.bak").writeText("rotated")

        assertTrue(record.deleteAll())
        assertFalse(File(dir, "dojo.payload").exists())
        assertFalse(File(dir, "dojo.payload.bak").exists())
    }

    @Test
    fun `staged temp file alone is cleared`() {
        val (dir, record) = freshRecord()
        // A crash mid-write leaves only the .tmp behind; the next
        // clearDojo must not report success with staging residue on disk.
        File(dir, "dojo.payload.tmp").writeText("staged")

        assertTrue(record.deleteAll())
        assertFalse(File(dir, "dojo.payload.tmp").exists())
    }
}
