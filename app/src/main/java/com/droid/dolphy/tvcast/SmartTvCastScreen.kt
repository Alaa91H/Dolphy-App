package com.droid.dolphy.tvcast

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavController
import com.droid.dolphy.AccentButton
import com.droid.dolphy.DolphyIconButton
import com.droid.dolphy.MaterialBackground
import com.droid.dolphy.R
import com.droid.dolphy.SectionTopBar
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface CastUiStatus {
    data object Idle : CastUiStatus
    data object Preparing : CastUiStatus
    data object Ready : CastUiStatus
    data object Stopped : CastUiStatus
    data class Connecting(val name: String) : CastUiStatus
    data class Playing(val name: String) : CastUiStatus
    data class Error(val message: String) : CastUiStatus
}

@Composable
fun SmartTvCastScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var prepared by remember { mutableStateOf<PreparedVideo?>(null) }
    var server by remember { mutableStateOf<CastVideoServer?>(null) }
    var videoUrl by remember { mutableStateOf<String?>(null) }
    var pageUrl by remember { mutableStateOf<String?>(null) }
    var isPreparing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<CastUiStatus>(CastUiStatus.Idle) }
    var activeSession by remember { mutableStateOf<CastSession?>(null) }
    var activeDeviceId by remember { mutableStateOf<String?>(null) }
    var castJob by remember { mutableStateOf<Job?>(null) }

    val devices = remember { mutableStateListOf<CastDevice>() }
    var isScanning by remember { mutableStateOf(false) }
    var discoveryError by remember { mutableStateOf<String?>(null) }

    val discovery = remember(context) {
        CastDiscovery(
            context = context,
            onDevice = { device ->
                val index = devices.indexOfFirst { it.id == device.id }
                if (index >= 0) devices[index] = device else devices.add(device)
            },
            onDeviceLost = { id -> devices.removeAll { it.id == id } },
            onScanning = { isScanning = it },
            onError = { discoveryError = it },
        )
    }

    fun refreshDevices() {
        devices.clear()
        discoveryError = null
        discovery.restart()
    }

    fun stopSession(stopServer: Boolean, markStopped: Boolean) {
        castJob?.cancel()
        castJob = null
        val session = activeSession
        activeSession = null
        activeDeviceId = null
        if (session != null) {
            scope.launch(Dispatchers.IO) {
                session.stop()
                session.close()
            }
        }
        if (stopServer) {
            server?.stop()
            server = null
            videoUrl = null
            pageUrl = null
        }
        status = when {
            markStopped -> CastUiStatus.Stopped
            stopServer && prepared == null -> CastUiStatus.Idle
            else -> CastUiStatus.Ready
        }
    }

    fun clearVideo() {
        stopSession(stopServer = true, markStopped = false)
        prepared?.file?.delete()
        prepared = null
        status = CastUiStatus.Idle
    }

    suspend fun startServerFor(video: PreparedVideo) {
        isPreparing = true
        status = CastUiStatus.Preparing
        val setup = withContext(Dispatchers.IO) {
            runCatching {
                val ip = CastNetwork.localIpv4(context)
                    ?: throw IllegalStateException(context.getString(R.string.smarttv_cast_no_network))
                val newServer = CastVideoServer(video.file, video.mimeType, video.displayName)
                try {
                    newServer.start()
                    ServerSetup(
                        server = newServer,
                        videoUrl = newServer.playbackUrl(ip),
                        pageUrl = newServer.pageUrl(ip),
                    )
                } catch (error: IOException) {
                    newServer.stop()
                    throw error
                }
            }
        }
        setup.onSuccess { value ->
            server = value.server
            videoUrl = value.videoUrl
            pageUrl = value.pageUrl
            status = CastUiStatus.Ready
            isPreparing = false
        }.onFailure { error ->
            Log.w(TAG, "Could not start cast server", error)
            status = CastUiStatus.Error(error.message ?: "Could not serve the selected video")
            isPreparing = false
        }
    }

    fun selectVideo(uri: Uri) {
        if (isPreparing) return
        scope.launch {
            isPreparing = true
            status = CastUiStatus.Preparing
            val result = withContext(Dispatchers.IO) {
                runCatching { prepareVideo(context, uri) }
            }
            val video = result.getOrNull()
            if (video == null) {
                status = CastUiStatus.Error(context.getString(R.string.smarttv_cast_pick_failed))
                isPreparing = false
                return@launch
            }
            val previous = prepared
            stopSession(stopServer = true, markStopped = false)
            previous?.file?.delete()
            prepared = video
            startServerFor(video)
        }
    }

    fun castTo(device: CastDevice) {
        val url = videoUrl ?: return
        val video = prepared ?: return
        if (activeDeviceId != null) return
        activeSession?.close()
        activeSession = null
        castJob = scope.launch {
            status = CastUiStatus.Connecting(device.name)
            activeDeviceId = device.id
            val result = withContext(Dispatchers.IO) {
                CastClients.play(device, url, video.mimeType, video.displayName)
            }
            when (result) {
                is CastOutcome.Success -> {
                    activeSession = result.session
                    status = CastUiStatus.Playing(device.name)
                }
                is CastOutcome.Failure -> {
                    activeDeviceId = null
                    status = CastUiStatus.Error(result.detail)
                }
            }
            castJob = null
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(::selectVideo) }

    LaunchedEffect(Unit) {
        if (CastNetwork.localIpv4(context) == null) {
            status = CastUiStatus.Error(context.getString(R.string.smarttv_cast_no_network))
        }
        discovery.start()
    }

    DisposableEffect(Unit) {
        onDispose {
            discovery.stop()
            castJob?.cancel()
            activeSession?.let { session ->
                Thread {
                    runCatching { session.stop() }
                    session.close()
                }.start()
            }
            server?.stop()
            prepared?.file?.delete()
        }
    }

    val statusText = when (val current = status) {
        CastUiStatus.Idle -> null
        CastUiStatus.Preparing -> stringResource(R.string.smarttv_cast_preparing)
        CastUiStatus.Ready -> stringResource(R.string.smarttv_cast_ready)
        CastUiStatus.Stopped -> stringResource(R.string.smarttv_cast_stopped)
        is CastUiStatus.Connecting -> stringResource(R.string.smarttv_cast_connecting, current.name)
        is CastUiStatus.Playing -> stringResource(R.string.smarttv_cast_playing, current.name)
        is CastUiStatus.Error -> stringResource(R.string.smarttv_cast_failed, current.message)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val accent = MaterialTheme.colorScheme.primary
        MaterialBackground(accentColor = accent) {
            Column(modifier = Modifier.fillMaxSize()) {
                SectionTopBar(
                    title = stringResource(R.string.smarttv_cast_title),
                    onBack = { navController.popBackStack() },
                    accentColor = accent,
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.smarttv_cast_pick_video),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        if (prepared == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { videoPickerLauncher.launch("*/*") },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(R.string.smarttv_cast_choose_video),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        } else {
                            CastPreview(
                                context = context,
                                video = prepared!!,
                                onClose = ::clearVideo,
                            )
                        }
                    }

                    if (pageUrl != null && prepared != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AccentButton(
                                onClick = {
                                    pageUrl?.let { url ->
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Language, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.smarttv_cast_open_local_site))
                            }
                            FilledTonalButton(onClick = { stopSession(stopServer = false, markStopped = true) }) {
                                Icon(Icons.Filled.Stop, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.smarttv_cast_stop))
                            }
                        }
                    }

                    statusText?.let { text ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                text = text,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.smarttv_cast_devices, devices.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            DolphyIconButton(onClick = ::refreshDevices) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.smarttv_cast_refresh),
                                )
                            }
                        }
                    }

                    discoveryError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(devices, key = { it.id }) { device ->
                            CastDeviceRow(
                                device = device,
                                isBusy = activeDeviceId == device.id,
                                canCast = videoUrl != null && !isPreparing && activeDeviceId == null,
                                onCastClick = { castTo(device) },
                            )
                        }

                        if (devices.isEmpty()) {
                            item {
                                EmptyDevices(isScanning = isScanning)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CastPreview(
    context: Context,
    video: PreparedVideo,
    onClose: () -> Unit,
) {
    val isImage = video.mimeType.startsWith("image/")
    val bitmap = remember(video) {
        if (isImage) runCatching { BitmapFactory.decodeFile(video.file.absolutePath) }.getOrNull()
        else null
    }
    val player = remember(video) {
        if (isImage) null else {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".provider",
                video.file,
            )
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                playWhenReady = true
                prepare()
            }
        }
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = video.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else if (player != null) {
            AndroidView(
                factory = { viewContext ->
                    androidx.media3.ui.PlayerView(viewContext).apply {
                        useController = false
                        keepScreenOn = true
                        this.player = player
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { playerView ->
                    playerView.player = player
                    if (!player.isPlaying) {
                        player.prepare()
                        player.play()
                    }
                },
            )
        }

        DolphyIconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun EmptyDevices(isScanning: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Cast,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.smarttv_cast_no_devices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isScanning) {
                Text(
                    text = stringResource(R.string.smarttv_cast_network_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CastDeviceRow(
    device: CastDevice,
    isBusy: Boolean,
    canCast: Boolean,
    onCastClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${device.protocol.label} • ${device.endpointLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                device.model?.takeIf { it.isNotBlank() }?.let { model ->
                    Text(
                        text = model,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                DolphyIconButton(
                    onClick = onCastClick,
                    enabled = canCast,
                ) {
                    Icon(
                        Icons.Filled.Cast,
                        contentDescription = null,
                        tint = if (canCast) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private fun prepareVideo(context: Context, uri: Uri): PreparedVideo? {
    val metadata = readVideoMetadata(context, uri) ?: return null
    val file = copyVideoToCache(context, uri, metadata) ?: return null
    return PreparedVideo(file, metadata.mimeType, metadata.displayName)
}

private fun readVideoMetadata(context: Context, uri: Uri): VideoMetadata? {
    val resolver = context.contentResolver
    val mimeType = try {
        resolver.getType(uri) ?: "video/mp4"
    } catch (error: SecurityException) {
        Log.w(TAG, "Unable to inspect selected MIME type", error)
        return null
    }
    var displayName = "video.mp4"
    val cursor = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    } catch (error: SecurityException) {
        Log.w(TAG, "Unable to inspect selected file name", error)
        return null
    }
    cursor?.use { value ->
        if (value.moveToFirst()) {
            val index = value.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                displayName = value.getString(index)?.takeIf { it.isNotBlank() } ?: displayName
            }
        }
    }
    return VideoMetadata(displayName, mimeTypeForName(displayName, mimeType))
}

private fun copyVideoToCache(
    context: Context,
    uri: Uri,
    metadata: VideoMetadata,
): File? {
    val extension = extensionForName(metadata.displayName, metadata.mimeType)
    val file = File(context.cacheDir, "cast_${System.currentTimeMillis()}.$extension")
    val input = try {
        context.contentResolver.openInputStream(uri)
    } catch (error: IOException) {
        Log.w(TAG, "Unable to open selected file", error)
        return null
    } catch (error: SecurityException) {
        Log.w(TAG, "No permission to read selected file", error)
        return null
    } ?: run {
        Log.w(TAG, "The selected file has no readable stream")
        return null
    }
    try {
        input.use { source ->
            file.outputStream().use { target -> source.copyTo(target) }
        }
    } catch (error: IOException) {
        file.delete()
        Log.w(TAG, "Unable to copy selected file", error)
        return null
    }
    if (file.length() <= 0L) {
        file.delete()
        Log.w(TAG, "The selected file is empty")
        return null
    }
    return file
}

private data class VideoMetadata(
    val displayName: String,
    val mimeType: String,
)

private data class ServerSetup(
    val server: CastVideoServer,
    val videoUrl: String,
    val pageUrl: String,
)

private const val TAG = "SmartTvCast"
