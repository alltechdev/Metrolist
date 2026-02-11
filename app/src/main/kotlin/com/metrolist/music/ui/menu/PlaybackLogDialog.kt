package com.metrolist.music.ui.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.getSystemService
import androidx.media3.common.Player
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.PlaybackLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun PlaybackLogDialog(
    mediaMetadata: MediaMetadata,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return

    // Real-time log refresh
    var logText by remember { mutableStateOf(PlaybackLogger.getLog()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            logText = PlaybackLogger.getLog()
            delay(2000)
        }
    }

    val playbackState by playerConnection.playbackState.collectAsState()
    val error by playerConnection.error.collectAsState()
    val currentFormat by playerConnection.currentFormat.collectAsState(initial = null)

    val stateName = when (playbackState) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }

    val connectivityManager = context.getSystemService<ConnectivityManager>()
    @Suppress("DEPRECATION")
    val networkInfo = connectivityManager?.activeNetworkInfo
    @Suppress("DEPRECATION")
    val networkStatus = if (networkInfo?.isConnected == true) "connected" else "disconnected"

    // Build error chain
    val errorText = if (error != null) {
        buildString {
            appendLine("Error: ${error!!.message} (code=${error!!.errorCode})")
            var cause: Throwable? = error!!.cause
            var depth = 1
            while (cause != null) {
                appendLine("  cause[$depth]: ${cause::class.simpleName}: ${cause.message}")
                cause = cause.cause
                depth++
            }
        }
    } else "None"

    val headerText = buildString {
        appendLine("=== Device Info ===")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine()
        appendLine("=== Current Song ===")
        appendLine("Title: ${mediaMetadata.title}")
        appendLine("Artist: ${mediaMetadata.artists.joinToString { it.name }}")
        appendLine("ID: ${mediaMetadata.id}")
        appendLine()
        appendLine("=== Playback State ===")
        appendLine("State: $stateName")
        appendLine("Error: $errorText")
        appendLine()
        appendLine("=== Format ===")
        if (currentFormat != null) {
            appendLine("itag: ${currentFormat!!.itag}")
            appendLine("mime: ${currentFormat!!.mimeType}")
            appendLine("bitrate: ${currentFormat!!.bitrate}")
            appendLine("sampleRate: ${currentFormat!!.sampleRate}")
        } else {
            appendLine("No format loaded")
        }
        appendLine()
        appendLine("=== Network ===")
        appendLine("Status: $networkStatus")
    }

    val fullText = "$headerText\n=== Log ===\n$logText"

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playback_log_title)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                val verticalScrollState = rememberScrollState()
                val horizontalScrollState = rememberScrollState()
                Text(
                    text = fullText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier
                        .verticalScroll(verticalScrollState)
                        .horizontalScroll(horizontalScrollState)
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    PlaybackLogger.clear()
                    logText = PlaybackLogger.getLog()
                    Toast.makeText(context, R.string.log_cleared, Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.clear_log))
                }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Playback Log", fullText))
                    Toast.makeText(context, R.string.log_copied, Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.copy_log))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}
