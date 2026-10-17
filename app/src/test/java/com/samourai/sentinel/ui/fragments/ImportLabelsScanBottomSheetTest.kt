package com.samourai.sentinel.ui.fragments

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.repository.Bip329Importer
import com.samourai.sentinel.data.repository.ImportResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.StringReader

/**
 * QR label-import surface, JVM side: the door-check
 * (isValidLabelsJsonl) and the decoded-blob hand-off — the exact
 * Reader shape the settings QR path feeds the importer. The animated-UR
 * encode/decode side stays on-device with the #36 sitting; hummingbird
 * is exercised there, not here. Same fixture-first pattern as
 * Bip329ImporterTest / Bip329ParserTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportLabelsScanBottomSheetTest {

    private val created = mutableListOf<SentinelRoomDb>()
    private lateinit var importer: Bip329Importer

    @Before
    fun setUp() {
        importer = freshImporter()
    }

    @After
    fun tearDown() {
        created.forEach { it.close() }
    }

    private fun freshImporter(): Bip329Importer {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SentinelRoomDb::class.java
        ).allowMainThreadQueries().build()
        created.add(db)
        return Bip329Importer(db)
    }

    private fun fixture(): List<String> =
        javaClass.getResourceAsStream("/bip329/sample.jsonl")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }

    private fun import(text: String): ImportResult = runBlocking {
        importer.import(BufferedReader(StringReader(text)), "mainnet", 1000L)
    }

    @Test
    fun doorCheckAcceptsLabelJsonl() {
        assertTrue(isValidLabelsJsonl(fixture().joinToString("\n")))
    }

    @Test
    fun doorCheckRejectsBlankAndGarbage() {
        assertFalse(isValidLabelsJsonl(""))
        assertFalse(isValidLabelsJsonl("   \n   \n"))
        assertFalse(isValidLabelsJsonl("not json at all"))
    }

    @Test
    fun doorCheckSkipsLeadingBlankLines() {
        val text = "\n\n" + fixture().joinToString("\n")
        assertTrue(isValidLabelsJsonl(text))
    }

    @Test
    fun decodedBlobImportsMatchFileShapeImports() {
        val blob = "\n" + fixture().joinToString("\n") + "\n"
        val baseline = freshImporter()
        val fromQr = import(blob)
        val fromFile = runBlocking {
            baseline.import(
                BufferedReader(StringReader(fixture().joinToString("\n"))),
                "mainnet", 1000L
            )
        }
        assertEquals(fromFile.imported, fromQr.imported)
        assertEquals(fromFile.updated, fromQr.updated)
        assertEquals(fromFile.skipped, fromQr.skipped)
    }

    @Test
    fun classifyRoutesUrAndBbqrFramesAwayFromDoorCheck() {
        assertEquals(LabelQrPayload.UrFrame, classifyLabelQrPayload("UR:BYTES/1-2/ABCDEF"))
        assertEquals(LabelQrPayload.BbqrFrame, classifyLabelQrPayload("BQR:1/1/T/QUJD"))
        assertEquals(LabelQrPayload.Invalid, classifyLabelQrPayload("not json"))
    }

    @Test
    fun classifyRoutesValidJsonlToLabels() {
        val text = fixture().joinToString("\n")
        assertEquals(LabelQrPayload.Labels(text), classifyLabelQrPayload(text))
    }

    @Test
    fun classifyAcceptsLowercaseUrScheme() {
        assertEquals(LabelQrPayload.UrFrame, classifyLabelQrPayload("ur:BYTES/1-2/ABCDEF"))
    }

    @Test
    fun doorCheckRoutesFramesToResumeAndJsonlToImport() {
        val text = fixture().joinToString("\n")
        assertEquals(LabelScanAction.Import(text), labelScanAction(text))
        assertEquals(
            LabelScanAction.NotifyBbqrUnsupported,
            labelScanAction("BQR:1/1/T/QUJD")
        )
        assertEquals(
            LabelScanAction.IgnoreAndResume,
            labelScanAction("ur:BYTES/1-2/ABCDEF")
        )
        assertEquals(LabelScanAction.IgnoreAndResume, labelScanAction("not json"))
    }
}
