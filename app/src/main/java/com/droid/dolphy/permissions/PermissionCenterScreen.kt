package com.droid.dolphy.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.droid.dolphy.AccentButton
import com.droid.dolphy.DolphyIconButton
import com.droid.dolphy.DolphySwitch
import com.droid.dolphy.MaterialBackground
import com.droid.dolphy.MaterialCard
import com.droid.dolphy.R
import com.droid.dolphy.RootUtils
import com.droid.dolphy.SectionTopBar
import com.droid.dolphy.successGreenColor
import com.droid.dolphy.plugin.PluginManager
import com.droid.dolphy.plugin.PluginPermission
import com.droid.dolphy.plugin.model.LoadedPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_REQUEST_CODE = 0x5A1D
private const val SHIZUKU_SITE = "https://shizuku.rikka.app/download/"

private data class RuntimePermissionItem(
    val permission: String,
    @StringRes val labelRes: Int,
)

private data class RuntimePermissionGroup(
    @StringRes val titleRes: Int,
    val items: List<RuntimePermissionItem>,
)

private data class PermissionSnapshot(
    val rootGranted: Boolean,
    val rootChecking: Boolean,
    val rootMessage: String?,
    val shizukuInstalled: Boolean,
    val shizukuRunning: Boolean,
    val shizukuGranted: Boolean,
    val grantedCount: Int,
    val totalCount: Int,
    val missing: List<String>,
)

private enum class BadgeTone { POSITIVE, WARNING, NEUTRAL }

@Composable
fun PermissionCenterScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plugins by PluginManager.plugins.collectAsState()

    var rootGranted by remember { mutableStateOf(false) }
    var rootChecking by remember { mutableStateOf(true) }
    var rootMessage by remember { mutableStateOf<String?>(null) }
    var shizukuInstalled by remember { mutableStateOf(false) }
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuGranted by remember { mutableStateOf(false) }
    var accessVersion by remember { mutableIntStateOf(0) }
    var activePluginId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(accessVersion) {
        rootChecking = true
        rootGranted = withContext(Dispatchers.IO) {
            runCatching { RootUtils.isRooted() }.getOrDefault(false)
        }
        rootChecking = false
        shizukuInstalled = isShizukuInstalled(context)
        shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        shizukuGranted = shizukuRunning && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    ShizukuBinderEffects { accessVersion++ }

    val groups = remember { runtimePermissionGroups() }
    val allPermissions = remember(groups) { groups.flatMap { it.items } }
    val grantedCount = remember(accessVersion, groups) {
        allPermissions.count { isGranted(context, it.permission) }
    }
    val missingPermissions = remember(accessVersion, groups) {
        allPermissions.map { it.permission }.filterNot { isGranted(context, it) }
    }
    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { accessVersion++ }

    fun probeRoot() {
        rootChecking = true
        rootMessage = null
        scope.launch {
            val granted = withContext(Dispatchers.IO) {
                runCatching { RootUtils.executeRootCommand("id").first == 0 }.getOrDefault(false)
            }
            rootGranted = granted
            rootChecking = false
            if (!granted) rootMessage = context.getString(R.string.permission_center_root_denied)
        }
    }

    fun onShizukuAction() {
        when {
            !shizukuInstalled -> openUrl(context, SHIZUKU_SITE)
            shizukuRunning && !shizukuGranted -> runCatching {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
            shizukuRunning -> accessVersion++
            else -> openShizukuApp(context)
        }
    }

    val snapshot = PermissionSnapshot(
        rootGranted = rootGranted,
        rootChecking = rootChecking,
        rootMessage = rootMessage,
        shizukuInstalled = shizukuInstalled,
        shizukuRunning = shizukuRunning,
        shizukuGranted = shizukuGranted,
        grantedCount = grantedCount,
        totalCount = allPermissions.size,
        missing = missingPermissions,
    )

    MaterialBackground(accentColor = MaterialTheme.colorScheme.primary) {
        Column(Modifier.fillMaxSize()) {
            SectionTopBar(
                title = stringResource(R.string.permission_center_title),
                onBack = { navController.popBackStack() },
                accentColor = MaterialTheme.colorScheme.primary,
                alwaysCollapsed = true,
                actions = {
                    DolphyIconButton(onClick = { accessVersion++ }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.permission_center_refresh))
                    }
                },
            )
            PermissionCenterList(
                snapshot = snapshot,
                groups = groups,
                plugins = plugins,
                onGrantMissing = { runtimeLauncher.launch(missingPermissions.toTypedArray()) },
                onProbeRoot = { probeRoot() },
                onShizukuAction = { onShizukuAction() },
                onManagePlugin = { activePluginId = it },
                onOpenManager = { navController.navigate("plugin_manager") },
            )
        }
    }

    val activePlugin = plugins.firstOrNull { it.manifest.id == activePluginId }
    if (activePlugin != null) {
        PluginCapabilityDialog(
            plugin = activePlugin,
            onChanged = { accessVersion++ },
            onDismiss = { activePluginId = null },
        )
    }
}

@Composable
private fun ShizukuBinderEffects(onChanged: () -> Unit) {
    DisposableEffect(Unit) {
        val onBinder = Shizuku.OnBinderReceivedListener { onChanged() }
        val onDead = Shizuku.OnBinderDeadListener { onChanged() }
        val onResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_REQUEST_CODE) onChanged()
        }
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(onBinder)
            Shizuku.addBinderDeadListener(onDead)
            Shizuku.addRequestPermissionResultListener(onResult)
        }
        onDispose {
            runCatching {
                Shizuku.removeBinderReceivedListener(onBinder)
                Shizuku.removeBinderDeadListener(onDead)
                Shizuku.removeRequestPermissionResultListener(onResult)
            }
        }
    }
}

@Composable
private fun PermissionCenterList(
    snapshot: PermissionSnapshot,
    groups: List<RuntimePermissionGroup>,
    plugins: List<LoadedPlugin>,
    onGrantMissing: () -> Unit,
    onProbeRoot: () -> Unit,
    onShizukuAction: () -> Unit,
    onManagePlugin: (String) -> Unit,
    onOpenManager: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 40.dp),
    ) {
        item("summary") {
            AccessSummaryCard(
                grantedCount = snapshot.grantedCount,
                totalCount = snapshot.totalCount,
                canGrantMissing = snapshot.missing.isNotEmpty(),
                onGrantMissing = onGrantMissing,
            )
        }
        item("root") {
            RootAccessCard(
                granted = snapshot.rootGranted,
                checking = snapshot.rootChecking,
                message = snapshot.rootMessage,
                onAction = onProbeRoot,
            )
        }
        item("shizuku") {
            ShizukuAccessCard(
                installed = snapshot.shizukuInstalled,
                running = snapshot.shizukuRunning,
                granted = snapshot.shizukuGranted,
                onAction = onShizukuAction,
            )
        }
        item("runtime_header") {
            SectionHeader(
                title = stringResource(R.string.permission_center_runtime_title),
                subtitle = stringResource(R.string.permission_center_runtime_desc),
            )
        }
        groups.forEach { group ->
            item("group_${group.titleRes}") { RuntimePermissionCard(group) }
        }
        item("plugins_header") {
            SectionHeader(
                title = stringResource(R.string.permission_center_plugins_title),
                subtitle = stringResource(R.string.permission_center_plugins_desc),
            )
        }
        if (plugins.isEmpty()) {
            item("plugins_empty") {
                MaterialCard(
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = MaterialTheme.colorScheme.primary,
                    contentPadding = 18.dp,
                ) {
                    Text(
                        stringResource(R.string.permission_center_plugins_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(plugins, key = { it.manifest.id }) { plugin ->
                PluginAccessCard(plugin) { onManagePlugin(plugin.manifest.id) }
            }
        }
        item("open_manager") {
            AccentButton(
                onClick = onOpenManager,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Default.Extension, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.permission_center_plugin_open_manager))
            }
        }
        item("note") {
            Text(
                text = stringResource(R.string.permission_center_note),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccessSummaryCard(
    grantedCount: Int,
    totalCount: Int,
    canGrantMissing: Boolean,
    onGrantMissing: () -> Unit,
) {
    val fraction = if (totalCount == 0) 1f else grantedCount.toFloat() / totalCount.toFloat()
    MaterialCard(
        modifier = Modifier.fillMaxWidth(),
        accentColor = MaterialTheme.colorScheme.primary,
        contentPadding = 16.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryFixed,
                    contentColor = MaterialTheme.colorScheme.onPrimaryFixed,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.AdminPanelSettings, null, modifier = Modifier.size(24.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.permission_center_subtitle),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.permission_center_summary, grantedCount, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            if (canGrantMissing) {
                AccentButton(
                    onClick = onGrantMissing,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(stringResource(R.string.permission_center_grant_all))
                }
            }
        }
    }
}

@Composable
private fun RootAccessCard(
    granted: Boolean,
    checking: Boolean,
    message: String?,
    onAction: () -> Unit,
) {
    AccessCard(
        icon = { Icon(Icons.Default.Security, null, modifier = Modifier.size(22.dp)) },
        title = stringResource(R.string.permission_center_root_title),
        description = stringResource(R.string.permission_center_root_desc),
        badge = when {
            checking -> stringResource(R.string.permission_center_root_checking) to BadgeTone.NEUTRAL
            granted -> stringResource(R.string.permission_center_root_granted) to BadgeTone.POSITIVE
            else -> stringResource(R.string.permission_center_root_missing) to BadgeTone.WARNING
        },
        actionLabel = stringResource(R.string.permission_center_root_action),
        actionEnabled = !checking,
        onAction = onAction,
        message = message,
    )
}

@Composable
private fun ShizukuAccessCard(
    installed: Boolean,
    running: Boolean,
    granted: Boolean,
    onAction: () -> Unit,
) {
    val badge = when {
        !installed -> stringResource(R.string.permission_center_shizuku_not_installed) to BadgeTone.NEUTRAL
        !running -> stringResource(R.string.permission_center_shizuku_not_running) to BadgeTone.WARNING
        !granted -> stringResource(R.string.permission_center_shizuku_missing_permission) to BadgeTone.WARNING
        else -> stringResource(R.string.permission_center_shizuku_ready) to BadgeTone.POSITIVE
    }
    val action = when {
        !installed -> stringResource(R.string.permission_center_shizuku_install)
        !running -> stringResource(R.string.permission_center_shizuku_open)
        !granted -> stringResource(R.string.permission_center_shizuku_grant)
        else -> stringResource(R.string.permission_center_refresh)
    }
    AccessCard(
        icon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(22.dp)) },
        title = stringResource(R.string.permission_center_shizuku_title),
        description = stringResource(R.string.permission_center_shizuku_desc),
        badge = badge,
        actionLabel = action,
        actionEnabled = true,
        onAction = onAction,
    )
}

@Composable
private fun AccessCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    badge: Pair<String, BadgeTone>,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    message: String? = null,
) {
    MaterialCard(
        modifier = Modifier.fillMaxWidth(),
        accentColor = MaterialTheme.colorScheme.primary,
        contentPadding = 16.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Box(contentAlignment = Alignment.Center) { icon() }
                }
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusBadge(badge.first, badge.second)
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(
                onClick = onAction,
                enabled = actionEnabled,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun RuntimePermissionCard(group: RuntimePermissionGroup) {
    val context = LocalContext.current
    MaterialCard(
        modifier = Modifier.fillMaxWidth(),
        accentColor = MaterialTheme.colorScheme.primary,
        contentPadding = 14.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(groupIcon(group.titleRes), null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(group.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            group.items.forEachIndexed { index, item ->
                val granted = isGranted(context, item.permission)
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                RuntimePermissionRow(item, granted)
            }
        }
    }
}

@Composable
private fun RuntimePermissionRow(item: RuntimePermissionItem, granted: Boolean) {
    val grantedColor = successGreenColor()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.CheckCircle,
            null,
            modifier = Modifier.size(16.dp),
            tint = if (granted) grantedColor else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(item.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.permission.substringAfterLast('.'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(
                if (granted) R.string.permission_center_granted else R.string.permission_center_not_granted,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (granted) grantedColor else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun PluginAccessCard(plugin: LoadedPlugin, onManage: () -> Unit) {
    val capabilityCount = plugin.manifest.permissions.size
    val revokedCount = PluginAccessPolicy.revoked(plugin.manifest.id).size
    val capabilitiesLabel = stringResource(R.string.permission_center_plugin_capabilities, capabilityCount)
    MaterialCard(
        modifier = Modifier.fillMaxWidth(),
        accentColor = MaterialTheme.colorScheme.primary,
        contentPadding = 14.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Extension, null, modifier = Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        plugin.manifest.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append("v")
                            append(plugin.manifest.version)
                            append(" · ")
                            append(capabilitiesLabel)
                            if (revokedCount > 0) {
                                append(" · ")
                                append(
                                    stringResource(R.string.permission_center_revoked_count, revokedCount),
                                )
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DolphySwitch(
                    checked = plugin.enabled,
                    onCheckedChange = { PluginManager.setEnabled(plugin.manifest.id, it) },
                )
            }
            if (capabilityCount > 0) {
                TextButton(
                    onClick = onManage,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text(
                        stringResource(R.string.permission_center_plugin_manage),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginCapabilityDialog(
    plugin: LoadedPlugin,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pluginId = plugin.manifest.id
    var revoked by remember(pluginId) { mutableStateOf(PluginAccessPolicy.revoked(pluginId)) }
    val capabilities = remember(pluginId, plugin.manifest.permissions) {
        plugin.manifest.permissions.sortedBy { it.token }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { Icon(Icons.Default.Extension, null) },
        title = { Text(stringResource(R.string.permission_center_plugin_dialog_title, plugin.manifest.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.permission_center_plugin_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (capabilities.isEmpty()) {
                    Text(
                        stringResource(R.string.permission_center_plugin_no_capabilities),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    capabilities.forEach { permission ->
                        CapabilityRow(
                            permission = permission,
                            allowed = permission !in revoked,
                            onAllowedChange = { allowed ->
                                PluginAccessPolicy.setRevoked(pluginId, permission, !allowed)
                                revoked = PluginAccessPolicy.revoked(pluginId)
                                if (plugin.enabled) {
                                    PluginManager.setEnabled(pluginId, false)
                                    PluginManager.setEnabled(pluginId, true)
                                }
                                onChanged()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            AccentButton(onClick = onDismiss) {
                Text(stringResource(R.string.permission_center_done))
            }
        },
    )
}

@Composable
private fun CapabilityRow(
    permission: PluginPermission,
    allowed: Boolean,
    onAllowedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            capabilityLabel(permission),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (allowed) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        DolphySwitch(checked = allowed, onCheckedChange = onAllowedChange)
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusBadge(text: String, tone: BadgeTone) {
    val color = when (tone) {
        BadgeTone.POSITIVE -> successGreenColor()
        BadgeTone.WARNING,
        BadgeTone.NEUTRAL -> MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.14f), contentColor = color, shape = CircleShape) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private val capabilityStringResources = mapOf(
    PluginPermission.NETWORK to R.string.plugin_capability_network,
    PluginPermission.FILES to R.string.plugin_capability_files,
    PluginPermission.ROOT to R.string.plugin_capability_root,
    PluginPermission.SHIZUKU to R.string.plugin_capability_shizuku,
    PluginPermission.SHELL to R.string.plugin_capability_shell,
    PluginPermission.DEX to R.string.plugin_capability_dex,
    PluginPermission.DOWNLOAD to R.string.plugin_capability_download,
    PluginPermission.BLUETOOTH to R.string.plugin_capability_bluetooth,
    PluginPermission.WIFI to R.string.plugin_capability_wifi,
    PluginPermission.INFRARED to R.string.plugin_capability_infrared,
    PluginPermission.IRDB to R.string.plugin_capability_irdb,
    PluginPermission.USB to R.string.plugin_capability_usb,
    PluginPermission.BLE_SPAM to R.string.plugin_capability_ble_spam,
)

@Composable
private fun capabilityLabel(permission: PluginPermission): String {
    val res = capabilityStringResources[permission]
    return if (res != null) stringResource(res) else permission.token.replace('_', ' ').uppercase()
}

private fun groupIcon(@StringRes titleRes: Int) = when (titleRes) {
    R.string.perm_group_wifi -> Icons.Default.Wifi
    R.string.perm_group_camera -> Icons.Default.CameraAlt
    R.string.perm_group_microphone -> Icons.Default.Mic
    R.string.perm_group_contacts -> Icons.Default.Contacts
    R.string.perm_group_notifications -> Icons.Default.Notifications
    R.string.perm_group_location -> Icons.Default.LocationOn
    else -> Icons.Default.Bluetooth
}

private fun runtimePermissionGroups(): List<RuntimePermissionGroup> {
    val groups = mutableListOf<RuntimePermissionGroup>()

    val bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            RuntimePermissionItem(Manifest.permission.BLUETOOTH_SCAN, R.string.permission_center_perm_bluetooth_scan),
            RuntimePermissionItem(Manifest.permission.BLUETOOTH_CONNECT, R.string.permission_center_perm_bluetooth_connect),
            RuntimePermissionItem(Manifest.permission.BLUETOOTH_ADVERTISE, R.string.permission_center_perm_bluetooth_advertise),
        )
    } else {
        listOf(
            RuntimePermissionItem(Manifest.permission.BLUETOOTH, R.string.permission_center_perm_bluetooth_legacy),
        )
    }
    groups += RuntimePermissionGroup(R.string.perm_group_bluetooth, bluetooth)

    groups += RuntimePermissionGroup(
        R.string.perm_group_location,
        listOf(
            RuntimePermissionItem(Manifest.permission.ACCESS_FINE_LOCATION, R.string.permission_center_perm_location),
        ),
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        groups += RuntimePermissionGroup(
            R.string.perm_group_wifi,
            listOf(
                RuntimePermissionItem(Manifest.permission.NEARBY_WIFI_DEVICES, R.string.permission_center_perm_wifi_nearby),
            ),
        )
    }

    groups += RuntimePermissionGroup(
        R.string.perm_group_camera,
        listOf(
            RuntimePermissionItem(Manifest.permission.CAMERA, R.string.permission_center_perm_camera),
        ),
    )
    groups += RuntimePermissionGroup(
        R.string.perm_group_microphone,
        listOf(
            RuntimePermissionItem(Manifest.permission.RECORD_AUDIO, R.string.permission_center_perm_microphone),
        ),
    )
    groups += RuntimePermissionGroup(
        R.string.perm_group_contacts,
        listOf(
            RuntimePermissionItem(Manifest.permission.READ_CONTACTS, R.string.permission_center_perm_contacts),
        ),
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        groups += RuntimePermissionGroup(
            R.string.perm_group_notifications,
            listOf(
                RuntimePermissionItem(Manifest.permission.POST_NOTIFICATIONS, R.string.permission_center_perm_notifications),
            ),
        )
    }

    return groups
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isShizukuInstalled(context: Context): Boolean = runCatching {
    context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
    true
}.getOrDefault(false)

private fun openShizukuApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
    if (intent != null) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    } else {
        openUrl(context, SHIZUKU_SITE)
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
