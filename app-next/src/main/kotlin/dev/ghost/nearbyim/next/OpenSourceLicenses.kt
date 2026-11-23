package dev.ghost.nearbyim.next

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import android.content.res.AssetManager

private const val MESH_LICENSES = "mesh-notices/vendor-licenses"

private val licenseDocuments = listOf(
    "WoZai · AGPL-3.0-only" to "mesh-notices/LICENSE",
    "AndroidX, Compose, Material 3 & Kotlin · Apache-2.0" to "open-source/Apache-2.0.txt",
    "Google Material Icons · Apache-2.0" to "open-source/material-icons-LICENSE.txt",
    "ZXing · Apache-2.0" to "open-source/zxing-Apache-2.0.txt",
    "Noise Java · MIT" to "open-source/noise-java-MIT.txt",
    "Unicode / CLDR · Unicode License" to ""
)

@Composable
internal fun OpenSourceLicenses(dismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val back = {
        when {
            selected == null -> dismiss()
            selected?.startsWith("mesh-notices/") == true && selected != "mesh-notices/LICENSE" -> {
                selected = if (selected == MESH_LICENSES) null else MESH_LICENSES
            }
            else -> selected = null
        }
    }
    val meshTitle = stringResource(R.string.next_mesh_licenses)
    val needsMeshNotices = selected?.startsWith("mesh-notices/") == true && selected != "mesh-notices/LICENSE"
    val meshNotices by produceState<List<Pair<String, String>>>(emptyList(), needsMeshNotices) {
        if (needsMeshNotices) value = withContext(Dispatchers.IO) { meshLicenses(context.assets) }
    }
    val browsingMesh = selected == MESH_LICENSES
    val documents = if (browsingMesh) meshNotices
                    else licenseDocuments + (meshTitle to MESH_LICENSES)
    val document = (licenseDocuments + meshNotices).firstOrNull { it.second == selected }
    AlertDialog(
        onDismissRequest = back,
        title = { Text(document?.first ?: if (browsingMesh) meshTitle else stringResource(R.string.next_licenses)) },
        text = {
            if (document == null) {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(documents, key = { it.second }) { (title, path) ->
                        ListItem(headlineContent = { Text(title) }, modifier = Modifier.clickable { selected = path })
                    }
                }
            } else {
                key(document.second) {
                    val content by produceState<String?>(null) {
                        value = withContext(Dispatchers.IO) {
                            try {
                                val source = if (document.second.isEmpty()) context.resources.openRawResource(R.raw.emoji_license)
                                             else context.assets.open(document.second)
                                source.bufferedReader().use { it.readText() }
                            } catch (_: IOException) { "" }
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
            }
        },
        confirmButton = { TextButton(onClick = back) { Text(t(if (selected == null) "close" else "back")) } }
    )
}

private fun meshLicenses(assets: AssetManager): List<Pair<String, String>> {
    val notices = listOf("Mesh · THIRD_PARTY" to "mesh-notices/THIRD_PARTY.md")
    return try {
        notices + assets.list(MESH_LICENSES).orEmpty().flatMap { crate ->
            val directory = "$MESH_LICENSES/$crate"
            assets.list(directory).orEmpty().map { name -> "$crate · $name" to "$directory/$name" }
        }
    } catch (_: IOException) { notices }
}
