@file:OptIn(ExperimentalMaterial3Api::class)

package com.droid.dolphy.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droid.dolphy.M3SegmentedListSectionHeader
import com.droid.dolphy.MainActivity
import com.droid.dolphy.MaterialCard
import com.droid.dolphy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

private data class BackupStatus(
    val text: String,
    val isError: Boolean,
)

private data class BackupCandidate(
    val uri: Uri,
    val summary: BackupSummary,
)

@Composable
fun BackupRestoreSection(accentColor: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<BackupStatus?>(null) }
    var pending by remember { mutableStateOf<BackupCandidate?>(null) }
    var restartRequired by remember { mutableStateOf(false) }
    var lastBackup by remember { mutableStateOf(DolphyBackupManager.lastBackup(context)) }

    val createLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            busyText = context.getString(R.string.settings_backup_creating)
            status = null
            runCatching {
                withContext(Dispatchers.IO) { DolphyBackupManager.createBackup(context, uri) }
            }.onSuccess { summary ->
                lastBackup = summary
                status = BackupStatus(
                    context.getString(R.string.settings_backup_export_success),
                    isError = false,
                )
            }.onFailure { error ->
                status = BackupStatus(
                    context.getString(
                        R.string.settings_backup_export_failed,
                        describeBackupError(context, error),
                    ),
                    isError = true,
                )
            }
            busy = false
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            busyText = context.getString(R.string.settings_backup_reading)
            status = null
            runCatching {
                withContext(Dispatchers.IO) { DolphyBackupManager.inspectBackup(context, uri) }
            }.onSuccess { summary ->
                pending = BackupCandidate(uri, summary)
            }.onFailure { error ->
                pending = null
                status = BackupStatus(
                    context.getString(
                        R.string.settings_backup_read_failed,
                        describeBackupError(context, error),
                    ),
                    isError = true,
                )
            }
            busy = false
        }
    }

    fun restore(candidate: BackupCandidate, allowUntrustedPlugins: Boolean) {
        scope.launch {
            busy = true
            busyText = context.getString(R.string.settings_backup_restoring)
            status = null
            runCatching {
                withContext(Dispatchers.IO) {
                    DolphyBackupManager.restoreBackup(context, candidate.uri, allowUntrustedPlugins)
                }
            }.onSuccess { summary ->
                lastBackup = summary
                pending = null
                restartRequired = true
                status = BackupStatus(
                    context.getString(R.string.settings_backup_restore_success),
                    isError = false,
                )
            }.onFailure { error ->
                pending = null
                status = BackupStatus(
                    context.getString(
                        R.string.settings_backup_restore_failed,
                        describeBackupError(context, error),
                    ),
                    isError = true,
                )
            }
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        M3SegmentedListSectionHeader(
            title = stringResource(R.string.settings_backup_section).uppercase(),
        )
        BackupRestoreCard(
            accentColor = accentColor,
            busy = busy,
            busyText = busyText,
            lastBackup = lastBackup,
            status = status,
            restartRequired = restartRequired,
            onCreate = {
                createLauncher.launch(DolphyBackupManager.defaultFileName(context))
            },
            onRestore = {
                openLauncher.launch(arrayOf("*/*"))
            },
            onRestart = { restartDolphy(context) },
        )
    }

    pending?.let { candidate ->
        RestoreConfirmationDialog(
            candidate = candidate,
            busy = busy,
            onDismiss = { if (!busy) pending = null },
            onConfirm = { allowUntrustedPlugins -> restore(candidate, allowUntrustedPlugins) },
        )
    }
}

@Composable
private fun BackupRestoreCard(
    accentColor: Color,
    busy: Boolean,
    busyText: String,
    lastBackup: BackupSummary?,
    status: BackupStatus?,
    restartRequired: Boolean,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    onRestart: () -> Unit,
) {
    MaterialCard(
        modifier = Modifier.fillMaxWidth(),
        accentColor = accentColor,
        contentPadding = 16.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupCardHeader(accentColor)
            BackupDetails(lastBackup)
            BackupActions(busy = busy, onCreate = onCreate, onRestore = onRestore)
            BackupFeedback(busy = busy, busyText = busyText, status = status)
            if (restartRequired) BackupRestart(onRestart)
        }
    }
}

@Composable
private fun BackupCardHeader(accentColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Save,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.settings_backup_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.settings_backup_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BackupDetails(lastBackup: BackupSummary?) {
    val context = LocalContext.current
    Text(
        text = lastBackup?.let { summary ->
            stringResource(R.string.settings_backup_last, formatBackupDate(summary.createdAt))
        } ?: stringResource(R.string.settings_backup_never),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    lastBackup?.let { summary ->
        Text(
            text = stringResource(
                R.string.settings_backup_counts,
                summary.settingsCount,
                summary.pluginCount,
                summary.fileCount,
                Formatter.formatShortFileSize(context, summary.totalBytes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = stringResource(R.string.settings_backup_includes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BackupActions(
    busy: Boolean,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = onCreate,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_backup_create))
        }
        OutlinedButton(
            onClick = onRestore,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_backup_restore))
        }
    }
}

@Composable
private fun BackupFeedback(
    busy: Boolean,
    busyText: String,
    status: BackupStatus?,
) {
    if (busy) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = busyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        status?.let { message ->
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (message.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun BackupRestart(onRestart: () -> Unit) {
    Text(
        text = stringResource(R.string.settings_backup_restart_needed),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
    )
    Button(onClick = onRestart) {
        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.settings_backup_restart))
    }
}

@Composable
private fun RestoreConfirmationDialog(
    candidate: BackupCandidate,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var trustAccepted by remember(candidate) { mutableStateOf(false) }
    val untrustedCount = candidate.summary.untrustedPluginCount
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Restore, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_backup_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_backup_confirm_body))
                Text(
                    text = stringResource(
                        R.string.settings_backup_confirm_details,
                        formatBackupDate(candidate.summary.createdAt),
                        candidate.summary.settingsCount,
                        candidate.summary.pluginCount,
                        candidate.summary.fileCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (untrustedCount > 0) {
                    Text(
                        text = stringResource(R.string.settings_backup_untrusted_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_backup_untrusted_body,
                            untrustedCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = trustAccepted,
                            onCheckedChange = { trustAccepted = it },
                        )
                        Text(
                            text = stringResource(R.string.settings_backup_untrusted_confirm),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trustAccepted) },
                enabled = !busy && (untrustedCount == 0 || trustAccepted),
            ) {
                Text(stringResource(R.string.settings_backup_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.settings_backup_cancel))
            }
        },
    )
}

private fun describeBackupError(context: Context, throwable: Throwable): String {
    val fallback = throwable.message?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.settings_backup_unknown_error)
    return when ((throwable as? BackupException)?.failure) {
        BackupFailure.INVALID_FORMAT -> context.getString(R.string.settings_backup_invalid)
        BackupFailure.UNSUPPORTED_VERSION -> context.getString(R.string.settings_backup_too_new)
        BackupFailure.CHECKSUM_MISMATCH -> context.getString(R.string.settings_backup_corrupt)
        BackupFailure.TOO_LARGE -> context.getString(R.string.settings_backup_too_large)
        BackupFailure.INVALID_PATH -> context.getString(R.string.settings_backup_invalid_path)
        BackupFailure.UNSUPPORTED_PREFERENCE -> context.getString(R.string.settings_backup_unsupported)
        BackupFailure.INVALID_PLUGIN -> context.getString(R.string.settings_backup_tampered_plugin)
        BackupFailure.UNTRUSTED_PLUGINS -> context.getString(R.string.settings_backup_untrusted_blocked)
        BackupFailure.IO, null -> fallback
    }
}

private fun formatBackupDate(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    return DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.getDefault(),
    ).format(Date(timestamp))
}

private fun restartDolphy(context: Context) {
    val intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    context.startActivity(intent)
    exitProcess(0)
}
