package dev.ghost.nearbyim.next

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.R
import dev.ghost.nearbyim.core.AttachmentRecord
import dev.ghost.nearbyim.core.TransferProgress

fun transferFraction(record: AttachmentRecord, measurement: TransferProgress): Float? = when {
    record.state == "preparing" || record.state == "verifying" -> null
    record.state == "checking" && !measurement.checking() -> null
    record.state == "checking" -> if (measurement.checkingTotalBytes() == 0L) 1f else
        (measurement.checkedBytes().toDouble() / measurement.checkingTotalBytes()).toFloat().coerceIn(0f, 1f)
    record.info.size > 0 -> (record.transferred.toDouble() / record.info.size).toFloat().coerceIn(0f, 1f)
    else -> null
}

@Composable
fun TransferMeasurements(record: AttachmentRecord, measurement: TransferProgress) {
    val context = LocalContext.current
    fun bytes(value: Long) = Formatter.formatShortFileSize(context, value)
    Text("${bytes(record.transferred)} / ${bytes(record.info.size)}", style = MaterialTheme.typography.bodySmall)
    if (record.state == "checking") {
        Text(if (measurement.checking()) t("attachmentCheckingProgress", bytes(measurement.checkedBytes()),
            bytes(measurement.checkingTotalBytes()), (transferFraction(record, measurement)!! * 100).toInt())
            else t("attachmentWaitingResume"), style = MaterialTheme.typography.bodySmall)
    } else if (record.state == "transferring") {
        Text(if (measurement.bytesPerSecond() > 0 && measurement.remainingSeconds() >= 0)
            t("attachmentSpeedEta", bytes(measurement.bytesPerSecond()), measurement.remainingSeconds())
            else if (measurement.bytesPerSecond() > 0) stringResource(R.string.next_speed_only, bytes(measurement.bytesPerSecond()))
            else stringResource(R.string.next_measurement_pending), style = MaterialTheme.typography.bodySmall)
    }
    if (record.active()) {
        val fraction = transferFraction(record, measurement)
        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(t("attachmentProgress", (fraction * 100).toInt(), t(record.stateKey())), style = MaterialTheme.typography.labelSmall)
        }
    }
}
