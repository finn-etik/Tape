package app.tape.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tape.BuildConfig

/** Kept in step with THIRD_PARTY_NOTICES.md at the repo root. */
private val COMPONENTS = listOf(
    "Kotlin standard library & kotlinx.coroutines" to "Apache 2.0",
    "AndroidX Core, Activity, Lifecycle, Browser" to "Apache 2.0",
    "Jetpack Compose & Material icons" to "Apache 2.0",
    "AndroidX Media3 (ExoPlayer, Session)" to "Apache 2.0",
    "AndroidX Security Crypto & Google Tink" to "Apache 2.0",
    "OkHttp & Okio" to "Apache 2.0",
    "Coil" to "Apache 2.0",
    "Guava & annotations" to "Apache 2.0",
    "Checker Framework qualifiers" to "MIT",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showLicense by remember { mutableStateOf(false) }
    val licenseTexts = remember {
        listOf("apache-2.0.txt", "mit-checker-qual.txt").joinToString("\n\n────────\n\n") { name ->
            runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text("TAPE", style = MaterialTheme.typography.headlineSmall, color = Ink)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "A quiet music and audiobook player. Open source under the Apache License 2.0.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                    )
                    Text("github.com/finn-etik/tape", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            item { SectionLabel("Open-source licenses", Modifier.padding(top = 16.dp)) }
            items(COMPONENTS) { (name, license) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
                    Text(license, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            item {
                Text(
                    if (showLicense) "Hide license texts" else "Show license texts",
                    style = MaterialTheme.typography.labelLarge,
                    color = Accent,
                    modifier = Modifier.clickable { showLicense = !showLicense }.padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
            if (showLicense) {
                item {
                    Text(
                        licenseTexts,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Muted,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }
    }
}
