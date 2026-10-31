package dev.ghost.nearbyim.next

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.AndroidText
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.ghost.nearbyim.AndroidAvatars
import dev.ghost.nearbyim.AvatarView

@Composable
fun t(key: String, vararg args: Any): String = AndroidText.get(LocalContext.current, key, *args)

@Composable
fun AppIcon(@DrawableRes icon: Int, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(icon), description, modifier.size(24.dp))
}

@Composable
fun InitialAvatar(name: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Box(modifier.size(if (large) 72.dp else 48.dp).clip(CircleShape)
        .background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(name.codePoints().findFirst().orElse(0x6211).let { String(Character.toChars(it)) },
            style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DeviceAvatar(root: String?, name: String, modifier: Modifier = Modifier, large: Boolean = false) {
    if (root == null) InitialAvatar(name, modifier, large)
    else AndroidView(factory = { AvatarView(it) }, modifier = modifier.size(if (large) 72.dp else 48.dp),
        update = { view ->
            if (view.tag != root) { view.tag = root; AndroidAvatars.get(view.context).bind(view, root) }
        })
}

@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, body: String, modifier: Modifier = Modifier,
               action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                AppIcon(icon, modifier = Modifier.size(30.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 24.dp, top = 24.dp, bottom = 10.dp),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
}
