package org.libremediaconverter.work

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.installTestWorkManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/**
 * The two things a progress notification can say, and that they are not the same thing.
 *
 * An assertion gap rather than a coverage one, and the distinction is the reason this file exists.
 * JaCoCo is green on `build`'s `if (indeterminate)`, because `ProgressNotificationTest` drives it
 * through a real worker -- but that test reads only the notification id and
 * `Notification.EXTRA_PROGRESS`. **Nothing had ever read the text.** Swapping the two branches, or
 * collapsing them into one string, passed the entire suite.
 *
 * What it costs to get wrong is small and constant: a conversion that has been running for four
 * minutes still saying "Preparing", or one that has not started reporting yet claiming 0%. Neither
 * is a crash, and neither would be found by anything else here -- which is exactly the kind of
 * thing that survives for a long time.
 *
 * Nothing else in the suite constructs [ConversionNotifications] directly.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class NotificationProgressTextTest {

    /**
     * `build` reaches `WorkManager.getInstance` for the Cancel action's PendingIntent, so the
     * notification cannot be built at all without one. That coupling is why nothing had ever
     * constructed this class directly and read what it produced.
     */
    @Before
    fun setUp() {
        installTestWorkManager(RuntimeEnvironment.getApplication(), Data.EMPTY)
    }

    @Test
    fun `an indeterminate notification says something different from a measured one`() {
        val context = RuntimeEnvironment.getApplication()
        val notifications = ConversionNotifications(context)

        val preparing = notifications.build(JOB_ID, TITLE, percent = 0, indeterminate = true).text()
        val measured = notifications.build(JOB_ID, TITLE, percent = 42, indeterminate = false).text()

        assertNotEquals(
            "the two states have to read differently, or the text says nothing at all",
            preparing,
            measured,
        )
        assertTrue(
            "a measured notification has to carry its percentage, got \"$measured\"",
            measured.contains("42"),
        )
        assertTrue(
            "an indeterminate one must not invent one, got \"$preparing\"",
            !preparing.contains("42") && !preparing.contains("0"),
        )
    }

    /**
     * The title is the caller's, not the builder's -- it is the file the user picked, and it is what
     * tells two simultaneous conversions apart in the shade.
     */
    @Test
    fun `the notification is titled with the file it is converting`() {
        val context = RuntimeEnvironment.getApplication()

        val built = ConversionNotifications(context).build(JOB_ID, TITLE, percent = 10)

        assertEquals(TITLE, built.extras.getString(Notification.EXTRA_TITLE))
    }

    private fun Notification.text(): String = extras.getString(Notification.EXTRA_TEXT).orEmpty()

    private companion object {
        const val TITLE = "holiday.mp4"
        val JOB_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000a")
    }
}
