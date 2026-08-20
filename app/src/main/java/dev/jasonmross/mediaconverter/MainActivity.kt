package dev.jasonmross.mediaconverter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import dev.jasonmross.mediaconverter.convert.ConverterScreen
import dev.jasonmross.mediaconverter.ui.theme.MediaConverterTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is enforced from targetSdk 35 and has no opt-out at 36+,
        // so opt in explicitly rather than relying on the default.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MediaConverterTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ConverterScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
