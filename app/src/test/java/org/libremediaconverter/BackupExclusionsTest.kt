package org.libremediaconverter

import android.content.res.XmlResourceParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

/**
 * What the app lets leave the device.
 *
 * `data_extraction_rules.xml` is a resource rather than code, so nothing was checking it: reverting
 * the whole file to the template's boilerplate left the unit tests green AND `lintDebug` green, and
 * a future edit dropping the excludes would ship in silence. The failure it would cause is one
 * nobody meets in development — a cloud restore or a device-to-device transfer.
 *
 * What is at stake is written in the file itself. WorkManager's queue is the app's entire backup
 * payload, and every row in it references a `content://` URI granted to one install on one device
 * and an output path under that install's `cacheDir`. Neither survives the transfer, and the rows
 * are not inert when they arrive: reattachment queries WorkManager by tag on launch, so a fresh
 * install would come up attached to a job the user never ran on it.
 *
 * Read out of the compiled resource table rather than off `src/main/res`, so what is asserted is
 * what the APK actually carries. Note the limit of that: this pins the rules' content, not the
 * `android:dataExtractionRules` attribute that points the system at them.
 */
@RunWith(RobolectricTestRunner::class)
class BackupExclusionsTest {

    @Test
    fun `the work queue is excluded from cloud backup and from device transfer alike`() {
        // Both sections, because they are separately honoured: `allowBackup` stays true and the
        // exclusion is per-file, so an edit that dropped either half would leave the other looking
        // like the whole answer.
        assertEquals(
            mapOf(
                "cloud-backup" to WORK_MANAGER_STATE,
                "device-transfer" to WORK_MANAGER_STATE,
            ),
            excludesBySection(),
        )
    }

    /**
     * Every `<exclude>` in the rules, as `domain:path`, grouped by the section it sits in.
     *
     * Both halves of each entry, because an `<exclude>` carrying no path is skipped unchecked by
     * lint's own detector — so that spelling could protect nothing while still looking like a rule.
     */
    private fun excludesBySection(): Map<String, Set<String>> {
        // Both sections start present and empty, so a section deleted outright fails as an empty
        // set rather than as a missing key -- the same finding either way, said the same way.
        val found = SECTIONS.associateWith { mutableSetOf<String>() }
        var section: String? = null
        RuntimeEnvironment.getApplication().resources.getXml(R.xml.data_extraction_rules).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                section = parser.sectionAfter(section, found)
            }
        }
        return found
    }

    /** Folds one parse event into [found], and answers which section the parser is now inside. */
    private fun XmlResourceParser.sectionAfter(section: String?, found: Map<String, MutableSet<String>>): String? =
        when {
            eventType == XmlPullParser.START_TAG && name in SECTIONS -> name
            eventType == XmlPullParser.END_TAG && name == section -> null
            eventType == XmlPullParser.START_TAG && name == "exclude" && section != null ->
                section.also { found.getValue(it) += entry() }

            else -> section
        }

    private fun XmlResourceParser.entry(): String = "${attribute("domain")}:${attribute("path")}"

    /**
     * The value of the attribute called [name] on the current tag.
     *
     * Walked by index rather than looked up by namespace. These attributes carry the `android`
     * namespace in the source file, but a parser over the *compiled* resource reports them with
     * none, so `getAttributeValue(namespace, name)` answers null for every one of them.
     */
    private fun XmlResourceParser.attribute(name: String): String? =
        (0 until attributeCount).firstOrNull { getAttributeName(it) == name }?.let { getAttributeValue(it) }

    private companion object {
        /** The two ways data leaves a device, both of which these rules have to answer. */
        val SECTIONS = setOf("cloud-backup", "device-transfer")

        /**
         * WorkManager's own storage, spelled the way WorkManager spells it.
         *
         * The database is Room-backed and therefore in WAL mode, hence the two sidecars. Pinning
         * the spelling is the point rather than a cost: a WorkManager release renaming its database
         * would silently un-exclude the queue, and this failing is how anyone would find out.
         */
        val WORK_MANAGER_STATE = setOf(
            "database:androidx.work.workdb",
            "database:androidx.work.workdb-wal",
            "database:androidx.work.workdb-shm",
            "sharedpref:androidx.work.util.preferences.xml",
        )
    }
}
