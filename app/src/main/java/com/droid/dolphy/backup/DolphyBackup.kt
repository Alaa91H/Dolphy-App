package com.droid.dolphy.backup

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Base64
import com.droid.dolphy.nfc.db.NfcDatabase
import com.droid.dolphy.plugin.PluginSignature
import com.droid.dolphy.plugin.PluginSignatureStatus
import com.droid.dolphy.plugin.PluginTrustStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val BACKUP_FORMAT = "dolphy.backup"
internal const val BACKUP_SCHEMA_VERSION = 1
internal const val HISTORY_PREFS = "DolphyBackupPrefs"

private const val MAX_BACKUP_BYTES = 256L * 1024L * 1024L
private const val MAX_BACKUP_FILES = 20_000
private const val ROOT_PLUGINS = "plugins"
private const val ROOT_PLUGIN_DATA = "plugin_data"
private const val ROOT_FILES = "files"
private const val ROOT_DATABASES = "databases"
private const val ROOT_EXTERNAL = "external"
private const val ROOT_WEBVIEW = "webview"
private const val PLUGIN_DIR_NAME = "dolphy_plugins"
private const val PLUGIN_DATA_DIR_NAME = "dolphy_plugins_data"
private const val DATABASES_DIR_NAME = "databases"
private const val WEBVIEW_DIR_NAME = "app_webview"
private const val TYPE_STRING = "string"
private const val TYPE_INT = "int"
private const val TYPE_LONG = "long"
private const val TYPE_FLOAT = "float"
private const val TYPE_BOOLEAN = "boolean"
private const val TYPE_STRING_SET = "string_set"
private const val KEY_LAST_BACKUP_AT = "last_backup_at"
private const val KEY_LAST_BACKUP_VERSION = "last_backup_version"
private const val KEY_LAST_BACKUP_CODE = "last_backup_code"
private const val KEY_LAST_BACKUP_SETTINGS = "last_backup_settings"
private const val KEY_LAST_BACKUP_PLUGINS = "last_backup_plugins"
private const val KEY_LAST_BACKUP_FILES = "last_backup_files"
private const val KEY_LAST_BACKUP_BYTES = "last_backup_bytes"
private val INTERNAL_FILES = setOf(
    "crash_stack.txt",
    "ir_flipper_index_v1.txt",
    "plugin_safe_mode_crash.log",
)

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal enum class BackupFailure {
    INVALID_FORMAT,
    UNSUPPORTED_VERSION,
    CHECKSUM_MISMATCH,
    TOO_LARGE,
    INVALID_PATH,
    UNSUPPORTED_PREFERENCE,
    INVALID_PLUGIN,
    UNTRUSTED_PLUGINS,
    IO,
}

internal class BackupException(
    val failure: BackupFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

@Serializable
internal data class DolphyBackup(
    val format: String = BACKUP_FORMAT,
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val createdAt: Long = 0L,
    val appVersionName: String = "",
    val appVersionCode: Long = 0L,
    val packageName: String = "",
    val preferences: List<BackupPreferenceFile> = emptyList(),
    val files: List<BackupFile> = emptyList(),
    val checksum: String = "",
)

@Serializable
internal data class BackupPreferenceFile(
    val name: String,
    val entries: List<BackupPreferenceEntry>,
)

@Serializable
internal data class BackupPreferenceEntry(
    val key: String,
    val type: String,
    val value: String? = null,
    val values: List<String>? = null,
)

@Serializable
internal data class BackupFile(
    val root: String,
    val path: String,
    val size: Long,
    val content: String,
)

internal data class BackupSummary(
    val createdAt: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val settingsCount: Int,
    val pluginCount: Int,
    val fileCount: Int,
    val totalBytes: Long,
    val untrustedPluginCount: Int = 0,
)

internal object DolphyBackupManager {

    fun defaultFileName(context: Context): String {
        val version = BackupCodec.appVersion(context).first.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "dolphy-backup-$version-$stamp.json"
    }

    fun lastBackup(context: Context): BackupSummary? {
        val prefs = historyPrefs(context)
        val createdAt = prefs.getLong(KEY_LAST_BACKUP_AT, 0L)
        if (createdAt <= 0L) return null
        return BackupSummary(
            createdAt = createdAt,
            appVersionName = prefs.getString(KEY_LAST_BACKUP_VERSION, "").orEmpty(),
            appVersionCode = prefs.getLong(KEY_LAST_BACKUP_CODE, 0L),
            settingsCount = prefs.getInt(KEY_LAST_BACKUP_SETTINGS, 0),
            pluginCount = prefs.getInt(KEY_LAST_BACKUP_PLUGINS, 0),
            fileCount = prefs.getInt(KEY_LAST_BACKUP_FILES, 0),
            totalBytes = prefs.getLong(KEY_LAST_BACKUP_BYTES, 0L),
        )
    }

    fun createBackup(context: Context, uri: android.net.Uri): BackupSummary {
        val version = BackupCodec.appVersion(context)
        val backup = DolphyBackup(
            createdAt = System.currentTimeMillis(),
            appVersionName = version.first,
            appVersionCode = version.second,
            packageName = context.packageName,
            preferences = BackupPreferences.collect(context),
            files = BackupFiles.collect(context),
        ).let { it.copy(checksum = BackupCodec.checksum(it)) }
        writeBackup(context, uri, backup)
        val summary = BackupCodec.summary(backup)
        saveLastBackup(context, summary)
        return summary
    }

    fun inspectBackup(context: Context, uri: android.net.Uri): BackupSummary =
        readBackup(context, uri).let(BackupCodec::summary)

    fun restoreBackup(
        context: Context,
        uri: android.net.Uri,
        allowUntrustedPlugins: Boolean,
    ): BackupSummary {
        val backup = readBackup(context, uri)
        val trust = BackupPlugins.analyze(backup.files)
        if (trust.untrusted > 0 && !allowUntrustedPlugins) {
            fail(BackupFailure.UNTRUSTED_PLUGINS, "Untrusted plugins were not approved")
        }
        NfcDatabase.close()
        BackupFiles.restore(context, backup.files)
        BackupPreferences.restore(context, backup.preferences)
        val summary = BackupCodec.summary(backup)
        saveLastBackup(context, summary)
        return summary
    }

    private fun readBackup(context: Context, uri: android.net.Uri): DolphyBackup {
        val text = try {
            context.contentResolver.openInputStream(uri)?.use(BackupCodec::readLimited)
                ?: fail(BackupFailure.IO, "The selected file could not be opened")
        } catch (e: BackupException) {
            throw e
        } catch (e: IOException) {
            fail(BackupFailure.IO, e.message ?: "Could not read the backup", e)
        } catch (e: SecurityException) {
            fail(BackupFailure.IO, e.message ?: "Could not read the backup", e)
        }
        val backup = try {
            json.decodeFromString(DolphyBackup.serializer(), text)
        } catch (e: SerializationException) {
            fail(BackupFailure.INVALID_FORMAT, "Invalid backup JSON", e)
        } catch (e: IllegalArgumentException) {
            fail(BackupFailure.INVALID_FORMAT, "Invalid backup JSON", e)
        }
        BackupValidator.validate(context, backup)
        return backup
    }

    private fun writeBackup(context: Context, uri: android.net.Uri, backup: DolphyBackup) {
        val text = json.encodeToString(DolphyBackup.serializer(), backup)
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
            } ?: fail(BackupFailure.IO, "The selected file could not be opened")
        } catch (e: IOException) {
            fail(BackupFailure.IO, e.message ?: "Could not write the backup", e)
        } catch (e: SecurityException) {
            fail(BackupFailure.IO, e.message ?: "Could not write the backup", e)
        }
    }

    private fun saveLastBackup(context: Context, summary: BackupSummary) {
        val saved = historyPrefs(context).edit()
            .putLong(KEY_LAST_BACKUP_AT, summary.createdAt)
            .putString(KEY_LAST_BACKUP_VERSION, summary.appVersionName)
            .putLong(KEY_LAST_BACKUP_CODE, summary.appVersionCode)
            .putInt(KEY_LAST_BACKUP_SETTINGS, summary.settingsCount)
            .putInt(KEY_LAST_BACKUP_PLUGINS, summary.pluginCount)
            .putInt(KEY_LAST_BACKUP_FILES, summary.fileCount)
            .putLong(KEY_LAST_BACKUP_BYTES, summary.totalBytes)
            .commit()
        if (!saved) fail(BackupFailure.IO, "Could not store backup history")
    }

    private fun historyPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
}

internal object BackupCodec {

    fun checksum(backup: DolphyBackup): String {
        val canonical = json.encodeToString(
            DolphyBackup.serializer(),
            backup.copy(checksum = ""),
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte) }
    }

    fun summary(backup: DolphyBackup): BackupSummary = BackupSummary(
        createdAt = backup.createdAt,
        appVersionName = backup.appVersionName,
        appVersionCode = backup.appVersionCode,
        settingsCount = backup.preferences.sumOf { it.entries.size },
        pluginCount = backup.files.count(::isPluginSource),
        fileCount = backup.files.size,
        totalBytes = backup.files.sumOf { it.size },
        untrustedPluginCount = BackupPlugins.analyze(backup.files).untrusted,
    )

    fun readLimited(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BACKUP_BYTES) {
                fail(BackupFailure.TOO_LARGE, "Backup exceeds the size limit")
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    @Suppress("DEPRECATION")
    fun appVersion(context: Context): Pair<String, Long> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        return (info.versionName ?: "unknown") to code
    }

}

internal object BackupPreferences {

    fun collect(context: Context): List<BackupPreferenceFile> =
        files(context).map { name ->
            val entries = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .all
                .entries
                .sortedBy { it.key }
                .map { (key, value) -> encode(key, value) }
            BackupPreferenceFile(name = name, entries = entries)
        }

    fun restore(context: Context, preferences: List<BackupPreferenceFile>) {
        val backupNames = preferences.map { it.name }.toSet()
        files(context)
            .filterNot { it in backupNames || it == HISTORY_PREFS }
            .forEach(context::deleteSharedPreferences)
        preferences.forEach { preference ->
            val editor = context.getSharedPreferences(preference.name, Context.MODE_PRIVATE)
                .edit()
                .clear()
            preference.entries.forEach { entry -> editor.putBackupEntry(entry) }
            if (!editor.commit()) fail(BackupFailure.IO, "Could not restore preferences")
        }
    }

    fun validate(preference: BackupPreferenceFile) {
        if (!isSafePreferenceName(preference.name)) {
            fail(BackupFailure.INVALID_PATH, "Unsafe preference file name")
        }
        preference.entries.forEach(::validateEntry)
    }

    private fun files(context: Context): List<String> {
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        return directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?.filter { it.isNotBlank() && it != HISTORY_PREFS }
            ?.sorted()
            ?.toList()
            .orEmpty()
    }

    private fun encode(key: String, value: Any?): BackupPreferenceEntry {
        if (value == null) fail(BackupFailure.UNSUPPORTED_PREFERENCE, "Preference value is missing")
        return when (value) {
            is String -> BackupPreferenceEntry(key, TYPE_STRING, value)
            is Int -> BackupPreferenceEntry(key, TYPE_INT, value.toString())
            is Long -> BackupPreferenceEntry(key, TYPE_LONG, value.toString())
            is Float -> BackupPreferenceEntry(key, TYPE_FLOAT, value.toString())
            is Boolean -> BackupPreferenceEntry(key, TYPE_BOOLEAN, value.toString())
            is Set<*> -> BackupPreferenceEntry(
                key = key,
                type = TYPE_STRING_SET,
                values = value.map { it.toString() }.sorted(),
            )
            else -> fail(
                BackupFailure.UNSUPPORTED_PREFERENCE,
                "Unsupported preference type ${value.javaClass.name}",
            )
        }
    }

    private fun validateEntry(entry: BackupPreferenceEntry) {
        if (entry.key.isBlank()) fail(BackupFailure.INVALID_FORMAT, "Preference key is missing")
        when (entry.type) {
            TYPE_STRING -> if (entry.value == null) {
                fail(BackupFailure.INVALID_FORMAT, "String preference value is missing")
            }
            TYPE_INT -> requireInt(entry.value)
            TYPE_LONG -> requireLong(entry.value)
            TYPE_FLOAT -> requireFloat(entry.value)
            TYPE_BOOLEAN -> requireBoolean(entry.value)
            TYPE_STRING_SET -> if (entry.values == null) {
                fail(BackupFailure.INVALID_FORMAT, "String set values are missing")
            }
            else -> fail(
                BackupFailure.UNSUPPORTED_PREFERENCE,
                "Unsupported preference type ${entry.type}",
            )
        }
    }

    private fun SharedPreferences.Editor.putBackupEntry(entry: BackupPreferenceEntry) {
        when (entry.type) {
            TYPE_STRING -> putString(entry.key, entry.value.orEmpty())
            TYPE_INT -> putInt(entry.key, entry.value.orEmpty().toInt())
            TYPE_LONG -> putLong(entry.key, entry.value.orEmpty().toLong())
            TYPE_FLOAT -> putFloat(entry.key, entry.value.orEmpty().toFloat())
            TYPE_BOOLEAN -> putBoolean(entry.key, entry.value.orEmpty().toBooleanStrict())
            TYPE_STRING_SET -> putStringSet(entry.key, entry.values.orEmpty().toSet())
            else -> fail(BackupFailure.UNSUPPORTED_PREFERENCE, "Unsupported preference type")
        }
    }
}

internal object BackupFiles {

    fun collect(context: Context): List<BackupFile> {
        checkpointDatabases(context)
        val result = mutableListOf<BackupFile>()
        var totalBytes = 0L
        rootDirs(context).forEach { (root, directory) ->
            if (!directory.exists()) return@forEach
            directory.walkTopDown()
                .onEnter { candidate ->
                    candidate == directory ||
                        (!Files.isSymbolicLink(candidate.toPath()) && !isExcludedFile(root, candidate, directory))
                }
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                .filterNot { isExcludedFile(root, it, directory) }
                .forEach { file ->
                    if (result.size >= MAX_BACKUP_FILES) {
                        fail(BackupFailure.TOO_LARGE, "Too many files to back up")
                    }
                    val bytes = file.readBytes()
                    totalBytes += bytes.size
                    if (totalBytes > MAX_BACKUP_BYTES) {
                        fail(BackupFailure.TOO_LARGE, "Backup payload is too large")
                    }
                    result += BackupFile(
                        root = root,
                        path = file.relativeTo(directory).invariantSeparatorsPath,
                        size = bytes.size.toLong(),
                        content = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    )
                }
        }
        return result
    }

    fun restore(context: Context, files: List<BackupFile>) {
        val roots = rootDirs(context)
        clearDirectory(roots.getValue(ROOT_PLUGINS))
        clearDirectory(roots.getValue(ROOT_PLUGIN_DATA))
        clearDirectory(roots.getValue(ROOT_DATABASES))
        clearDirectory(roots.getValue(ROOT_WEBVIEW))
        roots[ROOT_EXTERNAL]?.let(::clearDirectory)
        clearUserFiles(context)
        files.forEach { file ->
            val root = roots[file.root]
                ?: fail(BackupFailure.INVALID_PATH, "Unknown backup root")
            val target = safeTarget(root, file.path)
            val bytes = Base64.decode(file.content, Base64.DEFAULT)
            try {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            } catch (e: IOException) {
                fail(BackupFailure.IO, e.message ?: "Could not restore a file", e)
            } catch (e: SecurityException) {
                fail(BackupFailure.IO, e.message ?: "Could not restore a file", e)
            }
        }
    }

    fun validate(context: Context, files: List<BackupFile>) {
        val roots = rootDirs(context)
        files.forEach { file -> validateFile(roots, file) }
    }

    private fun rootDirs(context: Context): Map<String, File> = buildMap {
        put(ROOT_PLUGINS, context.getDir(PLUGIN_DIR_NAME, Context.MODE_PRIVATE))
        put(ROOT_PLUGIN_DATA, File(context.filesDir, PLUGIN_DATA_DIR_NAME))
        put(ROOT_FILES, context.filesDir)
        put(ROOT_DATABASES, File(context.applicationInfo.dataDir, DATABASES_DIR_NAME))
        put(ROOT_WEBVIEW, File(context.applicationInfo.dataDir, WEBVIEW_DIR_NAME))
        context.getExternalFilesDir(null)?.let { put(ROOT_EXTERNAL, it) }
    }

    private fun checkpointDatabases(context: Context) {
        val directory = File(context.applicationInfo.dataDir, DATABASES_DIR_NAME)
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".db") }
            ?.forEach { file ->
                runCatching {
                    val database = SQLiteDatabase.openDatabase(
                        file.path,
                        null,
                        SQLiteDatabase.OPEN_READWRITE,
                    )
                    try {
                        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                            cursor.moveToFirst()
                        }
                    } finally {
                        database.close()
                    }
                }
            }
    }

    private fun validateFile(roots: Map<String, File>, file: BackupFile) {
        val root = roots[file.root]
            ?: fail(BackupFailure.INVALID_PATH, "Unknown backup root")
        if (file.size < 0L || (file.size > 0L && file.content.isBlank())) {
            fail(BackupFailure.INVALID_FORMAT, "Backup file payload is missing")
        }
        safeTarget(root, file.path)
        if (file.root == ROOT_FILES &&
            (file.path == PLUGIN_DATA_DIR_NAME || file.path.startsWith("$PLUGIN_DATA_DIR_NAME/"))
        ) {
            fail(BackupFailure.INVALID_PATH, "Plugin data must use its own root")
        }
        val decoded = try {
            Base64.decode(file.content, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            fail(BackupFailure.INVALID_FORMAT, "Backup file is not valid Base64", e)
        }
        if (decoded.size.toLong() != file.size) {
            fail(BackupFailure.INVALID_FORMAT, "Backup file size does not match")
        }
    }

    private fun safeTarget(root: File, path: String): File {
        if (isUnsafeRelativePath(path)) fail(BackupFailure.INVALID_PATH, "Unsafe backup path")
        val base = root.canonicalFile
        val target = try {
            File(base, path.replace('\\', '/')).canonicalFile
        } catch (e: IOException) {
            fail(BackupFailure.INVALID_PATH, "Could not resolve backup path", e)
        }
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            fail(BackupFailure.INVALID_PATH, "Backup path escapes its root")
        }
        return target
    }

    private fun isExcludedFile(root: String, file: File, base: File): Boolean {
        if (root != ROOT_FILES) return false
        val relative = file.relativeTo(base).invariantSeparatorsPath
        return relative == PLUGIN_DATA_DIR_NAME ||
            relative.startsWith("$PLUGIN_DATA_DIR_NAME/") ||
            relative.substringAfterLast('/') in INTERNAL_FILES
    }

    private fun clearDirectory(directory: File) {
        directory.listFiles()?.forEach { it.deleteRecursively() }
        directory.mkdirs()
    }

    private fun clearUserFiles(context: Context) {
        context.filesDir.listFiles()?.forEach { file ->
            if (file.name == PLUGIN_DATA_DIR_NAME || file.name in INTERNAL_FILES) return@forEach
            file.deleteRecursively()
        }
    }
}

internal object BackupValidator {

    fun validate(context: Context, backup: DolphyBackup) {
        if (backup.format != BACKUP_FORMAT || backup.schemaVersion < 1) {
            fail(BackupFailure.INVALID_FORMAT, "Unrecognised backup format")
        }
        if (backup.schemaVersion > BACKUP_SCHEMA_VERSION) {
            fail(BackupFailure.UNSUPPORTED_VERSION, "Backup schema is newer")
        }
        if (backup.createdAt <= 0L) fail(BackupFailure.INVALID_FORMAT, "Backup timestamp is missing")
        if (backup.files.size > MAX_BACKUP_FILES) {
            fail(BackupFailure.TOO_LARGE, "Backup contains too many files")
        }
        if (backup.files.sumOf { it.size } > MAX_BACKUP_BYTES) {
            fail(BackupFailure.TOO_LARGE, "Backup payload is too large")
        }
        val expected = BackupCodec.checksum(backup)
        if (!MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                backup.checksum.toByteArray(Charsets.UTF_8),
            )
        ) {
            fail(BackupFailure.CHECKSUM_MISMATCH, "Backup checksum mismatch")
        }
        backup.preferences.forEach(BackupPreferences::validate)
        BackupFiles.validate(context, backup.files)
        if (BackupPlugins.analyze(backup.files).invalid > 0) {
            fail(BackupFailure.INVALID_PLUGIN, "A plugin has an invalid signature")
        }
    }
}

internal data class PluginTrustAnalysis(
    val untrusted: Int,
    val invalid: Int,
)

internal object BackupPlugins {

    fun analyze(files: List<BackupFile>): PluginTrustAnalysis {
        var untrusted = 0
        var invalid = 0
        files.filter(::isPluginSource).forEach { file ->
            val raw = sourceText(file)
            if (raw == null) {
                invalid++
                return@forEach
            }
            when (PluginSignature.verify(raw, PluginTrustStore.keys)) {
                is PluginSignatureStatus.Trusted -> Unit
                is PluginSignatureStatus.Invalid -> invalid++
                is PluginSignatureStatus.UnknownKey,
                is PluginSignatureStatus.Unsigned,
                -> untrusted++
            }
        }
        return PluginTrustAnalysis(untrusted = untrusted, invalid = invalid)
    }

    private fun sourceText(file: BackupFile): String? = runCatching {
        String(Base64.decode(file.content, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()
}

private fun isPluginSource(file: BackupFile): Boolean {
    if (file.root != ROOT_PLUGINS || !file.path.startsWith("sources/")) return false
    val extension = file.path.substringAfterLast('.', "").lowercase(Locale.US)
    return extension in setOf("dolphyplugin", "js", "plugin")
}

private fun fail(
    failure: BackupFailure,
    message: String,
    cause: Throwable? = null,
): Nothing = throw BackupException(failure, message, cause)

private fun isSafePreferenceName(name: String): Boolean {
    if (name.isBlank() || name == HISTORY_PREFS) return false
    if (name != File(name).name) return false
    return !name.contains('/') && !name.contains('\\')
}

private fun isUnsafeRelativePath(path: String): Boolean {
    if (path.isBlank() || path.startsWith('/')) return true
    return path.split('/').any { it.isEmpty() || it == "." || it == ".." }
}

private fun requireInt(value: String?) {
    value?.toIntOrNull() ?: fail(BackupFailure.INVALID_FORMAT, "Invalid int preference")
}

private fun requireLong(value: String?) {
    value?.toLongOrNull() ?: fail(BackupFailure.INVALID_FORMAT, "Invalid long preference")
}

private fun requireFloat(value: String?) {
    value?.toFloatOrNull() ?: fail(BackupFailure.INVALID_FORMAT, "Invalid float preference")
}

private fun requireBoolean(value: String?) {
    value?.toBooleanStrictOrNull() ?: fail(BackupFailure.INVALID_FORMAT, "Invalid boolean preference")
}
