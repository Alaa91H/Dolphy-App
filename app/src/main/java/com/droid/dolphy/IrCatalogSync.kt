package com.droid.dolphy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class IrCatalogMetadata(
    val etag: String,
    val lastModified: String?,
    val syncedAt: Long,
    val fileCount: Int,
    val bytes: Long,
    val sha256: String
)

enum class IrSyncPhase {
    CHECKING,
    DOWNLOADING,
    EXTRACTING,
    INSTALLING,
    DONE
}

data class IrSyncProgress(
    val phase: IrSyncPhase,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val extractedFiles: Int = 0
)

sealed interface IrSyncOutcome {
    data class Updated(val metadata: IrCatalogMetadata) : IrSyncOutcome
    data class UpToDate(val metadata: IrCatalogMetadata) : IrSyncOutcome
}

private const val ARCHIVE_URL =
    "https://github.com/Lucaslhm/Flipper-IRDB/archive/refs/heads/main.zip"
private const val USER_AGENT = "Dolphy-IRDB-Sync/1.0"
private const val MAX_DOWNLOAD_BYTES = 256L * 1024L * 1024L
private const val MAX_EXTRACTED_BYTES = 512L * 1024L * 1024L
private const val MAX_FILES = 50_000
private const val MIN_FREE_BYTES = 300L * 1024L * 1024L
private const val CACHE_DIR = "irdb-sync"
private const val ARCHIVE_ROOT = "Flipper-IRDB-main"

private val allowedHosts = setOf(
    "github.com",
    "codeload.github.com",
    "api.github.com",
    "objects.githubusercontent.com"
)
private val syncMutex = Mutex()

/**
 * Validates a GitHub archive entry and returns its path relative to the
 * repository root. The archive always has one top-level directory, so an
 * entry without a slash is not a catalog remote.
 */
internal fun safeIrRelativePath(entryName: String): String? {
    val normalized = entryName.replace('\\', '/').trimStart('/')
    if (normalized.substringBefore('/', missingDelimiterValue = "") != ARCHIVE_ROOT) return null
    val relative = normalized.removePrefix("$ARCHIVE_ROOT/")
    if (relative.isBlank() || !relative.endsWith(".ir", ignoreCase = true)) return null
    val parts = relative.split('/')
    if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
    return relative
}

/**
 * Keeps an optional, updated copy of Lucaslhm/Flipper-IRDB in app-private
 * storage. Installation is atomic, so a failed or cancelled sync never leaves
 * a partially extracted database in use.
 */
object IrCatalogSync {
    const val CATALOG_DIR = "Flipper-IRDB-main"

    fun installedRoot(context: Context): File? {
        val root = File(context.filesDir, "ir/Flipper-IRDB-main")
        return root.takeIf { it.isDirectory }
    }

    fun localFile(context: Context, requestedPath: String): File? {
        val root = installedRoot(context) ?: return null
        val normalized = requestedPath
            .replace('\\', '/')
            .removePrefix("/")
            .removePrefix("assets/")
        val prefix = "Flipper-IRDB-main/"
        if (!normalized.startsWith(prefix)) return null

        val relative = normalized.removePrefix(prefix)
        if (relative.isBlank()) return null
        return try {
            val rootPath = root.canonicalFile
            val candidate = File(rootPath, relative).canonicalFile
            val insideRoot = candidate.path == rootPath.path ||
                candidate.path.startsWith(rootPath.path + File.separator)
            candidate.takeIf { insideRoot && it.isFile }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Changes whenever the active catalog changes. It is stored in the index
     * cache so an interrupted process cannot reuse an index from another version.
     */
    fun indexToken(context: Context): String {
        val root = installedRoot(context)
        return if (root == null) "assets" else "local:${root.lastModified()}"
    }

    fun readMetadata(context: Context): IrCatalogMetadata? = readCatalogMetadata(context)

    suspend fun sync(
        context: Context,
        onProgress: suspend (IrSyncProgress) -> Unit = {}
    ): IrSyncOutcome = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            performSync(context.applicationContext, onProgress)
        }
    }
}

private suspend fun performSync(
    context: Context,
    onProgress: suspend (IrSyncProgress) -> Unit
): IrSyncOutcome {
    onProgress(IrSyncProgress(IrSyncPhase.CHECKING))

    val previousMetadata = readCatalogMetadata(context)
    val installed = IrCatalogSync.installedRoot(context)
    val remoteHeaders = probe(previousMetadata?.etag)
    upToDateMetadata(remoteHeaders, installed, previousMetadata)?.let {
        return IrSyncOutcome.UpToDate(it)
    }

    val usable = context.filesDir.usableSpace
    if (usable > 0L && usable < MIN_FREE_BYTES) {
        spaceFailure(context)
    }

    val cacheDir = File(context.cacheDir, CACHE_DIR)
    val archive = File(cacheDir, "flipper-irdb.zip")
    archive.delete()
    val staging = File(context.filesDir, "ir/Flipper-IRDB-main.new")
    staging.deleteRecursively()
    var stagingInstalled = false
    try {
        val downloaded = try {
            downloadArchive(
                context = context,
                archive = archive,
                previousEtag = previousMetadata?.etag.takeIf { installed != null },
                onProgress = onProgress
            )
        } catch (upToDate: AlreadyUpToDate) {
            return IrSyncOutcome.UpToDate(upToDate.metadata)
        }

        onProgress(
            IrSyncProgress(
                phase = IrSyncPhase.EXTRACTING,
                downloadedBytes = downloaded.bytes,
                totalBytes = downloaded.totalBytes
            )
        )
        val extracted = extractArchive(context, archive, staging, onProgress)
        if (extracted.fileCount < 100) {
            archiveFailure(context)
        }

        onProgress(
            IrSyncProgress(
                phase = IrSyncPhase.INSTALLING,
                downloadedBytes = downloaded.bytes,
                totalBytes = downloaded.totalBytes,
                extractedFiles = extracted.fileCount
            )
        )
        installStagedDirectory(context, staging)
        stagingInstalled = true

        val metadata = IrCatalogMetadata(
            etag = downloaded.etag.ifBlank { downloaded.sha256 },
            lastModified = downloaded.lastModified,
            syncedAt = System.currentTimeMillis(),
            fileCount = extracted.fileCount,
            bytes = extracted.bytes,
            sha256 = downloaded.sha256
        )
        runCatching { writeMetadata(context, metadata) }
            .onFailure { Log.w("IrCatalogSync", "Metadata write failed: ${it.message}") }
        IrRepository.invalidateFlipperCache(context)
        onProgress(IrSyncProgress(IrSyncPhase.DONE, extractedFiles = extracted.fileCount))
        return IrSyncOutcome.Updated(metadata)
    } finally {
        archive.delete()
        cacheDir.delete()
        if (!stagingInstalled) staging.deleteRecursively()
    }
}

private fun upToDateMetadata(
    remote: RemoteHeaders?,
    installed: File?,
    previous: IrCatalogMetadata?
): IrCatalogMetadata? {
    if (installed == null || previous == null) return null
    if (remote?.notModified == true) return previous
    if (remote?.etag != null && remote.etag == previous.etag) return previous
    return null
}

private suspend fun downloadArchive(
    context: Context,
    archive: File,
    previousEtag: String?,
    onProgress: suspend (IrSyncProgress) -> Unit
): DownloadedArchive {
    val connection = openConnection(ARCHIVE_URL, "GET")
    try {
        previousEtag?.takeIf { it.isNotBlank() }?.let {
            connection.setRequestProperty("If-None-Match", it)
        }
        val response = readArchiveResponse(context, connection)
        return copyArchive(context, connection, archive, response, onProgress)
    } finally {
        connection.disconnect()
    }
}

private fun readArchiveResponse(
    context: Context,
    connection: HttpURLConnection
): ArchiveResponse {
    val code = connection.responseCode
    validateFinalUrl(connection)
    if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
        val metadata = readCatalogMetadata(context) ?: archiveFailure(context)
        throw AlreadyUpToDate(metadata)
    }
    if (code !in 200..299) {
        httpFailure(context, code)
    }

    val totalBytes = connection.contentLengthLong.coerceAtLeast(0L)
    if (totalBytes > MAX_DOWNLOAD_BYTES) {
        archiveFailure(context)
    }
    return ArchiveResponse(
        totalBytes = totalBytes,
        etag = connection.getHeaderField("ETag").orEmpty(),
        lastModified = connection.getHeaderField("Last-Modified").orEmpty().ifBlank { null }
    )
}

private suspend fun copyArchive(
    context: Context,
    connection: HttpURLConnection,
    archive: File,
    response: ArchiveResponse,
    onProgress: suspend (IrSyncProgress) -> Unit
): DownloadedArchive {
    ensureDirectory(context, archive.parentFile)
    val digest = MessageDigest.getInstance("SHA-256")
    val downloaded = FileOutputStream(archive).use { output ->
        connection.inputStream.use { input ->
            copyArchiveStream(context, input, output, digest, response.totalBytes, onProgress)
        }
    }
    if (downloaded == 0L) {
        archiveFailure(context)
    }
    onProgress(
        IrSyncProgress(
            phase = IrSyncPhase.DOWNLOADING,
            downloadedBytes = downloaded,
            totalBytes = response.totalBytes
        )
    )
    return DownloadedArchive(
        bytes = downloaded,
        totalBytes = response.totalBytes,
        etag = response.etag,
        lastModified = response.lastModified,
        sha256 = digest.digest().toHex()
    )
}

private suspend fun copyArchiveStream(
    context: Context,
    input: InputStream,
    output: OutputStream,
    digest: MessageDigest,
    totalBytes: Long,
    onProgress: suspend (IrSyncProgress) -> Unit
): Long {
    val buffer = ByteArray(64 * 1024)
    var downloaded = 0L
    var lastReported = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        digest.update(buffer, 0, read)
        downloaded += read
        if (downloaded > MAX_DOWNLOAD_BYTES) {
            archiveFailure(context)
        }
        if (downloaded - lastReported >= 512L * 1024L) {
            lastReported = downloaded
            onProgress(
                IrSyncProgress(
                    phase = IrSyncPhase.DOWNLOADING,
                    downloadedBytes = downloaded,
                    totalBytes = totalBytes
                )
            )
        }
    }
    return downloaded
}

private suspend fun extractArchive(
    context: Context,
    archive: File,
    staging: File,
    onProgress: suspend (IrSyncProgress) -> Unit
): ExtractedArchive {
    ensureDirectory(context, staging)
    val canonicalRoot = staging.canonicalFile
    val state = ExtractionState()
    ZipInputStream(BufferedInputStream(FileInputStream(archive)), Charsets.UTF_8).use { zip ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = zip.nextEntry ?: break
            extractEntry(context, zip, entry, canonicalRoot, archive, state, onProgress)
            zip.closeEntry()
        }
    }
    return ExtractedArchive(fileCount = state.fileCount, bytes = state.bytes)
}

private suspend fun extractEntry(
    context: Context,
    zip: ZipInputStream,
    entry: java.util.zip.ZipEntry,
    canonicalRoot: File,
    archive: File,
    state: ExtractionState,
    onProgress: suspend (IrSyncProgress) -> Unit
) {
    val entryName = entry.name ?: return
    if (entry.isDirectory) return
    val relative = safeIrRelativePath(entryName) ?: return
    val destination = safeDestination(context, canonicalRoot, relative)
    ensureDirectory(context, destination.parentFile)
    FileOutputStream(destination).use { output ->
        copyEntry(context, zip, output, state)
    }
    state.fileCount++
    if (state.fileCount > MAX_FILES) {
        archiveFailure(context)
    }
    if (state.fileCount % 100 == 0) {
        onProgress(
            IrSyncProgress(
                phase = IrSyncPhase.EXTRACTING,
                downloadedBytes = archive.length(),
                extractedFiles = state.fileCount
            )
        )
    }
}

private suspend fun copyEntry(
    context: Context,
    input: InputStream,
    output: OutputStream,
    state: ExtractionState
) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        state.bytes += read
        if (state.bytes > MAX_EXTRACTED_BYTES) {
            archiveFailure(context)
        }
        output.write(buffer, 0, read)
    }
}

private fun safeDestination(context: Context, canonicalRoot: File, relative: String): File {
    val destination = File(canonicalRoot, relative).canonicalFile
    val insideRoot = destination.path == canonicalRoot.path ||
        destination.path.startsWith(canonicalRoot.path + File.separator)
    if (!insideRoot) {
        archiveFailure(context)
    }
    return destination
}

private fun installStagedDirectory(context: Context, staging: File) {
    val parent = File(context.filesDir, "ir")
    ensureDirectory(context, parent)
    val live = File(parent, "Flipper-IRDB-main")
    val backup = File(parent, "Flipper-IRDB-main.old")
    backup.deleteRecursively()

    if (live.exists()) {
        if (!live.renameTo(backup)) installFailure(context)
        if (!staging.renameTo(live)) {
            backup.renameTo(live)
            installFailure(context)
        }
        backup.deleteRecursively()
    } else if (!staging.renameTo(live)) {
        installFailure(context)
    }
}

private fun writeMetadata(context: Context, metadata: IrCatalogMetadata) {
    val file = metadataFile(context)
    ensureDirectory(context, file.parentFile)
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.writeText(
        JSONObject().apply {
            put("etag", metadata.etag)
            put("lastModified", metadata.lastModified ?: "")
            put("syncedAt", metadata.syncedAt)
            put("fileCount", metadata.fileCount)
            put("bytes", metadata.bytes)
            put("sha256", metadata.sha256)
        }.toString(),
        Charsets.UTF_8
    )
    if (!temporary.renameTo(file)) {
        file.delete()
        if (!temporary.renameTo(file)) temporary.delete()
    }
}

private fun readCatalogMetadata(context: Context): IrCatalogMetadata? {
    val file = metadataFile(context)
    if (!file.isFile) return null
    return try {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val etag = json.optString("etag")
        val sha256 = json.optString("sha256")
        if (etag.isBlank() && sha256.isBlank()) return null
        IrCatalogMetadata(
            etag = etag.ifBlank { sha256 },
            lastModified = json.optString("lastModified").ifBlank { null },
            syncedAt = json.optLong("syncedAt", 0L),
            fileCount = json.optInt("fileCount", 0),
            bytes = json.optLong("bytes", 0L),
            sha256 = sha256
        )
    } catch (e: JSONException) {
        Log.w("IrCatalogSync", "Metadata read failed: ${e.message}")
        null
    } catch (e: IOException) {
        Log.w("IrCatalogSync", "Metadata read failed: ${e.message}")
        null
    }
}

private fun metadataFile(context: Context): File =
    File(context.filesDir, "ir/Flipper-IRDB-main.sync.json")

private fun probe(previousEtag: String?): RemoteHeaders? {
    if (previousEtag.isNullOrBlank()) return null
    val connection = openConnection(ARCHIVE_URL, "HEAD")
    connection.setRequestProperty("If-None-Match", previousEtag)
    return try {
        val code = connection.responseCode
        validateFinalUrl(connection)
        when {
            code == HttpURLConnection.HTTP_NOT_MODIFIED -> RemoteHeaders(
                etag = previousEtag,
                notModified = true
            )
            code in 200..299 -> RemoteHeaders(
                etag = connection.getHeaderField("ETag").orEmpty(),
                notModified = false
            )
            else -> null
        }
    } catch (e: IOException) {
        Log.w("IrCatalogSync", "HEAD check failed: ${e.message}")
        null
    } finally {
        connection.disconnect()
    }
}

private fun openConnection(url: String, method: String): HttpURLConnection {
    val parsed = URL(url)
    if (parsed.protocol != "https" || parsed.host !in allowedHosts) {
        throw IOException("Unexpected IR database URL")
    }
    return (parsed.openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 30_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        useCaches = false
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/zip")
        setRequestProperty("Accept-Encoding", "identity")
    }
}

private fun validateFinalUrl(connection: HttpURLConnection) {
    val finalUrl = connection.url
    if (finalUrl.protocol != "https" || finalUrl.host !in allowedHosts) {
        throw IOException("Unexpected IR database redirect")
    }
}

private fun ensureDirectory(context: Context, directory: File?) {
    if (directory == null || directory.isDirectory) return
    if (!directory.mkdirs()) storageFailure(context)
}

private fun archiveFailure(context: Context): Nothing =
    throw IOException(context.getString(R.string.ir_sync_archive_error))

private fun httpFailure(context: Context, code: Int): Nothing =
    throw IOException(context.getString(R.string.ir_sync_http_error, code))

private fun storageFailure(context: Context): Nothing =
    throw IOException(context.getString(R.string.ir_sync_failed, "storage"))

private fun installFailure(context: Context): Nothing =
    throw IOException(context.getString(R.string.ir_sync_failed, "install"))

private fun spaceFailure(context: Context): Nothing =
    throw IOException(context.getString(R.string.ir_sync_space_error))

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

private data class RemoteHeaders(
    val etag: String,
    val notModified: Boolean
)

private data class ArchiveResponse(
    val totalBytes: Long,
    val etag: String,
    val lastModified: String?
)

private data class DownloadedArchive(
    val bytes: Long,
    val totalBytes: Long,
    val etag: String,
    val lastModified: String?,
    val sha256: String
)

private data class ExtractedArchive(
    val fileCount: Int,
    val bytes: Long
)

private class ExtractionState(
    var fileCount: Int = 0,
    var bytes: Long = 0L
)

private class AlreadyUpToDate(val metadata: IrCatalogMetadata) : Exception()
