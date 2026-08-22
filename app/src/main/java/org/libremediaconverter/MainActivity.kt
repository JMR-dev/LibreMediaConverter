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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import org.libremediaconverter.convert.ConverterScreen
import org.libremediaconverter.join.JoinScreen
import org.libremediaconverter.ui.theme.LibreMediaConverterTheme

private enum class Destination(val label: String) {
    CONVERT("Convert"),
    JOIN("Join"),
}

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
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(widthSizeClass: WindowWidthSizeClass) {
    var destination by remember { mutableStateOf(Destination.CONVERT) }
    val useRail = widthSizeClass != WindowWidthSizeClass.Compact

    if (useRail) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
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
                Content(destination, Modifier.padding(padding))
            }
        }
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
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
            Content(destination, Modifier.padding(padding))
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
