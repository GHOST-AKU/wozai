package dev.ghost.nearbyim.next

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.core.IdentityVerification
import dev.ghost.nearbyim.verification.QrCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun identityQrBitmap(snapshot: IdentityVerification): Bitmap {
    val cells = QrCodes.encode(snapshot.code.qrPayload(), 512)
    return Bitmap.createBitmap(IntArray(cells.size) { if (cells[it]) android.graphics.Color.BLACK else android.graphics.Color.WHITE },
        512, 512, Bitmap.Config.ARGB_8888)
}

@Composable
fun VerificationQr(snapshot: IdentityVerification) {
    val bitmap by produceState<Bitmap?>(null, snapshot.code.qrPayload()) {
        value = withContext(Dispatchers.Default) { identityQrBitmap(snapshot) }
    }
    Box(Modifier.widthIn(max = 224.dp).fillMaxWidth().aspectRatio(1f).background(Color.White),
        contentAlignment = Alignment.Center) {
        bitmap?.let { image -> Image(image.asImageBitmap(), t("verificationQrImage"), Modifier.matchParentSize()) }
    }
}
