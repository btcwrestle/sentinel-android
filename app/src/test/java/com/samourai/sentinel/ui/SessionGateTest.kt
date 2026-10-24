package com.samourai.sentinel.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #300. A fresh process has not been admitted, so a restored activity
 * must bounce to the lock before it reads payloads (#289, #291).
 * Once MainActivity.navigate() has admitted the process, screens run.
 *
 * The activity itself is not JVM-constructible. The decision is, and
 * the source pin goes red if a SentinelActivity subclass goes back to
 * overriding onCreate (which would run before the gate, or fail to
 * compile against the final method and be "fixed" by deleting it).
 */
class SessionGateTest {

    @Test
    fun `an unadmitted process bounces`() {
        assertTrue(shouldBounceUnadmittedSession(sessionAdmitted = false))
    }

    @Test
    fun `an admitted process does not bounce`() {
        assertFalse(shouldBounceUnadmittedSession(sessionAdmitted = true))
    }

    @Test
    fun `the gate is final and admit happens only in navigate`() {
        val activity = source("app/src/main/java/com/samourai/sentinel/ui/SentinelActivity.kt")
        assertTrue(activity.contains("final override fun onCreate(savedInstanceState: Bundle?)"))
        assertTrue(activity.contains("shouldBounceUnadmittedSession(accessFactory.isSessionAdmitted)"))
        assertTrue(activity.contains("protected open fun onCreateAdmitted(savedInstanceState: Bundle?)"))
        val navigate = source("app/src/main/java/com/samourai/sentinel/ui/MainActivity.kt")
        val start = navigate.indexOf("private fun navigate()")
        val body = navigate.substring(start, navigate.indexOf("private fun checkMigration()"))
        assertTrue(body.contains("accessFactory.admitSession()"))
        val factory = source("app/src/main/java/com/samourai/sentinel/core/access/AccessFactory.java")
        assertTrue(factory.contains("public void admitSession()"))
        assertTrue(factory.contains("public boolean isSessionAdmitted()"))
    }

    @Test
    fun `every SentinelActivity subclass waits for onCreateAdmitted`() {
        val root = sequenceOf(
            File("app/src/main/java/com/samourai/sentinel/ui"),
            File("src/main/java/com/samourai/sentinel/ui"),
        ).firstOrNull { it.isDirectory }
            ?: error("ui sources not found from ${File(".").absolutePath}")
        val offenders = root.walkTopDown().filter { it.extension == "kt" }.mapNotNull { file ->
            val text = file.readText()
            if (!text.contains(": SentinelActivity")) return@mapNotNull null
            if (file.name == "SentinelActivity.kt") return@mapNotNull null
            val overridesOnCreate = text.contains("override fun onCreate(savedInstanceState: Bundle?)")
            val waits = text.contains("override fun onCreateAdmitted(savedInstanceState: Bundle?)")
            if (overridesOnCreate || !waits) file.path else null
        }.toList()
        assertTrue(offenders.toString(), offenders.isEmpty())
    }

    @Test
    fun `an export before read is refused`() {
        val util = source("app/src/main/java/com/samourai/sentinel/util/ExportImportUtil.kt")
        val fn = util.substring(util.indexOf("fun makePayload()"))
        assertTrue(fn.contains("if (!collectionRepository.isPayloadLoaded())"))
        assertTrue(fn.contains("throw CollectionsNotLoadedException()"))
        val settings = source(
            "app/src/main/java/com/samourai/sentinel/ui/settings/MainSettingsFragment.kt",
        )
        assertTrue(settings.contains("Backup not created. Unlock the wallet and try again."))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
