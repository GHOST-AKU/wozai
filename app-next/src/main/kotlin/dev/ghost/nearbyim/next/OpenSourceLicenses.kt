package dev.ghost.nearbyim.next

import android.content.res.AssetManager
import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.R
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MESH_LICENSES = "mesh-notices/vendor-licenses"
private const val UNICODE_LICENSE = "unicode-license"

private data class LicenseDocument(val title: String, val path: String)

private val projectLicenses = listOf(
    LicenseDocument("WoZai · AGPL-3.0-only", "mesh-notices/LICENSE"),
    LicenseDocument("AndroidX, Compose, Material 3 & Kotlin · Apache-2.0", "open-source/Apache-2.0.txt"),
    LicenseDocument("Google Material Icons · Apache-2.0", "open-source/material-icons-LICENSE.txt"),
    LicenseDocument("ZXing · Apache-2.0", "open-source/zxing-Apache-2.0.txt"),
    LicenseDocument("Noise Java · MIT", "open-source/noise-java-MIT.txt"),
    LicenseDocument("Unicode / CLDR · Unicode License", UNICODE_LICENSE),
)

@Composable
internal fun OpenSourceLicenses(dismiss: () -> Unit) {
    val assets = LocalContext.current.assets
    var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var browsingMesh by rememberSaveable { mutableStateOf(false) }
    val projectScroll = rememberLazyListState()
    val meshScroll = rememberLazyListState()
    val back: () -> Unit = {
        when {
            selectedPath != null -> selectedPath = null
            browsingMesh -> browsingMesh = false
            else -> dismiss()
        }
    }
    val meshTitle = stringResource(R.string.next_mesh_licenses)
    val meshDocuments by produceState<List<LicenseDocument>?>(null, browsingMesh, assets) {
        if (browsingMesh) value = withContext(Dispatchers.IO) { meshLicenses(assets) }
    }
    val documents = if (browsingMesh) meshDocuments else projectLicenses
    val document = documents?.firstOrNull { it.path == selectedPath }
    AlertDialog(
        onDismissRequest = back,
        title = { Text(document?.title ?: if (browsingMesh) meshTitle else stringResource(R.string.next_licenses)) },
        text = {
            when {
                documents == null -> CircularProgressIndicator()
                document != null -> key(document.path) { LicenseBody(document) }
                selectedPath != null -> Text(stringResource(R.string.next_license_unavailable))
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    state = if (browsingMesh) meshScroll else projectScroll,
                ) {
                    items(documents, key = { it.path }) { license ->
                        ListItem(
                            headlineContent = { Text(license.title) },
                            modifier = Modifier.clickable { selectedPath = license.path },
                        )
                    }
                    if (!browsingMesh) item {
                        ListItem(
                            headlineContent = { Text(meshTitle) },
                            modifier = Modifier.clickable { browsingMesh = true },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = back) { Text(t(if (selectedPath != null || browsingMesh) "back" else "close")) }
        },
    )
}

@Composable
private fun LicenseBody(document: LicenseDocument) {
    val context = LocalContext.current
    val content by produceState<String?>(null, context) {
        value = withContext(Dispatchers.IO) {
            try {
                val source = if (document.path == UNICODE_LICENSE) context.resources.openRawResource(R.raw.emoji_license)
                    else context.assets.open(document.path)
                source.bufferedReader().use { it.readText() }
            } catch (_: IOException) {
                ""
            } catch (_: Resources.NotFoundException) {
                ""
            }
        }
    }
    val body = content
    SelectionContainer {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (body == null) CircularProgressIndicator()
            else Text(body.ifEmpty { stringResource(R.string.next_license_unavailable) })
        }
    }
}

private fun meshLicenses(assets: AssetManager): List<LicenseDocument> {
    val notices = listOf(LicenseDocument("Mesh · THIRD_PARTY", "mesh-notices/THIRD_PARTY.md"))
    return try {
        notices + assets.list(MESH_LICENSES).orEmpty().flatMap { crate ->
            val directory = "$MESH_LICENSES/$crate"
            assets.list(directory).orEmpty().map { name -> LicenseDocument("$crate · $name", "$directory/$name") }
        }
    } catch (_: IOException) {
        notices
    }
}
