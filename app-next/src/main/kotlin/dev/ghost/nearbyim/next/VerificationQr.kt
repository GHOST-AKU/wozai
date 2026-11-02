package dev.ghost.nearbyim.next

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
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
    bitmap?.let { image -> Image(image.asImageBitmap(), t("verificationQrImage"), Modifier.fillMaxWidth().heightIn(max = 240.dp).aspectRatio(1f)) }
}
