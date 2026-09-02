package org.libremediaconverter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.media3.common.util.UnstableApi
import org.libremediaconverter.convert.ConverterScreen
import org.libremediaconverter.join.JoinScreen
import org.libremediaconverter.ui.TestTags
import org.libremediaconverter.ui.theme.LibreMediaConverterTheme

/**
 * The tabs of the adaptive shell.
 *
 * `internal` rather than `private` so the unit tests can name a tab. The JVM test source
 * set is a friend of `main`, so this stays invisible to anything outside the module.
 */
internal enum class Destination(val label: String) {
    CONVERT("Convert"),
    JOIN("Join"),
}

/**
 * Saves a [Destination] as its constant name.
 *
 * A saver is needed at all because `rememberSaveable`'s default only accepts what a
 * `Bundle` can hold. An enum does qualify -- it is `Serializable`, so `autoSaver` would take
 * it without complaint -- and that is the reason to be explicit rather than the reason not
 * to be: nothing in the declaration says this type has to stay `Serializable`, so the
 * implicit route would keep working until someone made it a value class or a sealed
 * interface, and then quietly stop.
 *
 * The name and not the ordinal. An ordinal is a position, so inserting a tab between the
 * existing two would silently redefine every value already written down; the name only
 * changes when someone renames a constant, which is a visible edit. It also reads as itself
 * in a `Bundle` dump.
 *
 * An unknown name restores to null, which `rememberSaveable` treats as "nothing saved" and
 * falls back to the default tab. That is the state a downgrade or a renamed constant
 * produces, and landing on Convert is the right answer for it.
 */
internal val DestinationSaver: Saver<Destination, String> = Saver(
    save = { it.name },
    restore = { name -> Destination.entries.firstOrNull { it.name == name } },
)

@UnstableApi
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is enforced from targetSdk 35 and has no opt-out at 36+, so opt
        // in explicitly rather than relying on the default.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LibreMediaConverterTheme {
                AppRoot(widthSizeClass = calculateWindowSizeClass(this).widthSizeClass)
            }
        }
    }
}

/**
 * Adaptive shell: a bottom bar on phones, a side rail on wider screens.
 *
 * This is not cosmetic. From targetSdk 37, Android ignores `screenOrientation`,
 * `resizableActivity` and aspect-ratio limits on any display at least 600dp wide, and
 * the Android 16 opt-out no longer applies. The app will be resized and rotated
 * whether or not it is ready, so it has to lay out properly at every width.
 *
 * [content] is a parameter with a default rather than a direct call to [Content] so a test
 * can drive the shell -- which tab is selected, and whether that survives recreation --
 * without standing up either screen. Both screens resolve a ViewModel, which builds a
 * WorkManager and a media probe, none of which the tab selection depends on. The app
 * itself never passes it.
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppRoot(
    widthSizeClass: WindowWidthSizeClass,
    content: @Composable (Destination, Modifier) -> Unit = { destination, modifier ->
        Content(destination, modifier)
    },
) {
    // rememberSaveable, NOT remember. MainActivity declares no configChanges, so every
    // rotation and every resize recreates it -- exactly the case the KDoc above says the
    // shell exists for -- and remember does not survive that.
    var destination by rememberSaveable(stateSaver = DestinationSaver) {
        mutableStateOf(Destination.CONVERT)
    }
    val useRail = widthSizeClass != WindowWidthSizeClass.Compact

    if (useRail) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(modifier = Modifier.testTag(TestTags.Shell.NAVIGATION_RAIL)) {
                Destination.entries.forEach { item ->
                    NavigationRailItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = {},
                        label = { Text(item.label) },
                    )
                }
            }
            Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                content(destination, Modifier.padding(padding))
            }
        }
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar(modifier = Modifier.testTag(TestTags.Shell.NAVIGATION_BAR)) {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = {},
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            content(destination, Modifier.padding(padding))
        }
    }
}

@UnstableApi
@Composable
private fun Content(destination: Destination, modifier: Modifier) {
    when (destination) {
        Destination.CONVERT -> ConverterScreen(modifier = modifier)
        Destination.JOIN -> JoinScreen(modifier = modifier)
    }
}
