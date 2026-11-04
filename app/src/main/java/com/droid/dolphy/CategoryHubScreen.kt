@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.droid.dolphy

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.droid.dolphy.feature.catalog.FeatureCategory
import com.droid.dolphy.feature.catalog.FeatureTaxonomy

@Composable
fun CategoryHubScreen(
    navController: NavController,
    spamViewModel: SpamViewModel,
    category: FeatureCategory,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    val features = rememberCategoryFeatures(category)
    val pinnedRoutes by spamViewModel.pinnedCardRoutes.collectAsState()
    val viewMode by spamViewModel.functionsMenuViewType.collectAsState()

    var query by rememberSaveable(category.id) { mutableStateOf("") }
    var infoDestination by remember { mutableStateOf<FunctionDestination?>(null) }
    var menuRoute by remember { mutableStateOf<String?>(null) }

    val matching = remember(features, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            features
        } else {
            features.filter { item ->
                item.title.contains(needle, ignoreCase = true) ||
                    item.description.contains(needle, ignoreCase = true)
            }
        }
    }
    val pinned = remember(matching, pinnedRoutes, query) {
        if (query.isBlank()) matching.filter { it.route in pinnedRoutes } else emptyList()
    }
    val regular = remember(matching, pinned) {
        matching.filterNot { item -> pinned.any { it.route == item.route } }
    }

    fun open(item: FunctionDestination) {
        if (menuRoute != null) return
        vibrate(context)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        spamViewModel.recordCardUsage(item.route)
        openFunctionDestination(item, navController, context)
    }

    fun togglePin(item: FunctionDestination) {
        vibrate(context)
        if (item.route in pinnedRoutes) spamViewModel.unpinCard(item.route) else spamViewModel.pinCard(item.route)
        menuRoute = null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var deviceProbeVersion by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { deviceProbeVersion++ }
    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { deviceProbeVersion++ }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) deviceProbeVersion++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val deviceStatus = remember(category, deviceProbeVersion) {
        readCategoryDeviceStatus(context.applicationContext, category)
    }

    val handleDeviceAction: () -> Unit = {
        performCategoryDeviceAction(
            context = context,
            status = deviceStatus,
            onRequestPermissions = { permissionLauncher.launch(it) },
            onEnableBluetooth = {
                runCatching {
                    bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            },
        )
    }
    val handleFeatureInfo: (FunctionDestination) -> Unit = { item ->
        vibrate(context)
        infoDestination = item
        menuRoute = null
    }
    val handleFeatureLongPress: (FunctionDestination) -> Unit = { item ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        vibrate(context)
        menuRoute = item.route
    }
    val handleViewMode: (Int) -> Unit = { mode ->
        vibrate(context)
        spamViewModel.setFunctionsMenuViewType(mode)
    }

    CategoryHubRoot(
        state = CategoryHubUiState(
            category = category,
            features = features,
            pinned = pinned,
            regular = regular,
            query = query,
            viewMode = viewMode,
            accent = accent,
            deviceStatus = deviceStatus,
            pinnedRoutes = pinnedRoutes,
            menuRoute = menuRoute,
        ),
        onSettingsClick = {
            vibrate(context)
            navController.navigate("settings")
        },
        onQueryChange = { query = it },
        onDeviceAction = handleDeviceAction,
        onMenuDismiss = { menuRoute = null },
        onTogglePin = ::togglePin,
        onInfo = handleFeatureInfo,
        onOpen = ::open,
        onLongPress = handleFeatureLongPress,
        onViewModeChange = handleViewMode,
    )

    infoDestination?.let { info ->
        FeatureInfoDialog(info = info, accent = accent, onDismiss = { infoDestination = null })
    }
}

private data class CategoryHubUiState(
    val category: FeatureCategory,
    val features: List<FunctionDestination>,
    val pinned: List<FunctionDestination>,
    val regular: List<FunctionDestination>,
    val query: String,
    val viewMode: Int,
    val accent: Color,
    val deviceStatus: CategoryDeviceStatus,
    val pinnedRoutes: List<String>,
    val menuRoute: String?,
)

@Composable
private fun CategoryHubRoot(
    state: CategoryHubUiState,
    onSettingsClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    onDeviceAction: () -> Unit,
    onMenuDismiss: () -> Unit,
    onTogglePin: (FunctionDestination) -> Unit,
    onInfo: (FunctionDestination) -> Unit,
    onOpen: (FunctionDestination) -> Unit,
    onLongPress: (FunctionDestination) -> Unit,
    onViewModeChange: (Int) -> Unit,
) {
    MaterialBackground(accentColor = state.accent) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { CategoryTopBar(onSettingsClick = onSettingsClick) },
        ) { paddingValues ->
            if (state.viewMode == 1) {
                CategoryGrid(
                    paddingValues = paddingValues,
                    category = state.category,
                    features = state.features,
                    pinned = state.pinned,
                    regular = state.regular,
                    query = state.query,
                    onQueryChange = onQueryChange,
                    accent = state.accent,
                    deviceStatus = state.deviceStatus,
                    onDeviceAction = onDeviceAction,
                    pinnedRoutes = state.pinnedRoutes,
                    menuRoute = state.menuRoute,
                    onMenuDismiss = onMenuDismiss,
                    onTogglePin = onTogglePin,
                    onInfo = onInfo,
                    onOpen = onOpen,
                    onLongPress = onLongPress,
                    onViewModeChange = { onViewModeChange(0) },
                )
            } else {
                CategoryList(
                    paddingValues = paddingValues,
                    category = state.category,
                    features = state.features,
                    pinned = state.pinned,
                    regular = state.regular,
                    query = state.query,
                    onQueryChange = onQueryChange,
                    accent = state.accent,
                    deviceStatus = state.deviceStatus,
                    onDeviceAction = onDeviceAction,
                    pinnedRoutes = state.pinnedRoutes,
                    menuRoute = state.menuRoute,
                    onMenuDismiss = onMenuDismiss,
                    onTogglePin = onTogglePin,
                    onInfo = onInfo,
                    onOpen = onOpen,
                    onLongPress = onLongPress,
                    onViewModeChange = { onViewModeChange(1) },
                )
            }
        }
    }
}

@Composable
private fun CategoryTopBar(onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 22.dp, end = 14.dp, top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "DOLPHY",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.4.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.category_hub_tagline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalIconButton(
            onClick = onSettingsClick,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings))
        }
    }
}

@Composable
private fun CategoryList(
    paddingValues: PaddingValues,
    category: FeatureCategory,
    features: List<FunctionDestination>,
    pinned: List<FunctionDestination>,
    regular: List<FunctionDestination>,
    query: String,
    onQueryChange: (String) -> Unit,
    accent: Color,
    deviceStatus: CategoryDeviceStatus,
    onDeviceAction: () -> Unit,
    pinnedRoutes: List<String>,
    menuRoute: String?,
    onMenuDismiss: () -> Unit,
    onTogglePin: (FunctionDestination) -> Unit,
    onInfo: (FunctionDestination) -> Unit,
    onOpen: (FunctionDestination) -> Unit,
    onLongPress: (FunctionDestination) -> Unit,
    onViewModeChange: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "hub_hero") {
            CategoryHero(
                category = category,
                count = features.size,
                accent = accent,
                deviceStatus = deviceStatus,
                onDeviceAction = onDeviceAction,
            )
        }
        item(key = "hub_search") {
            CategorySearchRow(
                query = query,
                onQueryChange = onQueryChange,
                accent = accent,
                viewMode = 0,
                onViewModeChange = onViewModeChange,
            )
        }
        if (pinned.isNotEmpty()) {
            item(key = "hub_pinned_header") {
                CategorySectionHeader(
                    title = stringResource(R.string.category_pinned),
                    count = pinned.size,
                    accent = accent,
                )
            }
            items(pinned, key = { "pinned_${it.route}" }) { item ->
                FeatureRow(
                    item = item,
                    accent = accent,
                    pinned = true,
                    menuExpanded = menuRoute == item.route,
                    onMenuDismiss = onMenuDismiss,
                    onTogglePin = { onTogglePin(item) },
                    onInfo = { onInfo(item) },
                    onClick = { onOpen(item) },
                    onLongClick = { onLongPress(item) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (regular.isNotEmpty()) {
            item(key = "hub_all_header") {
                CategorySectionHeader(
                    title = if (query.isBlank()) {
                        stringResource(R.string.category_all_tools)
                    } else {
                        stringResource(R.string.category_search_results, regular.size + pinned.size)
                    },
                    count = regular.size,
                    accent = accent,
                )
            }
            items(regular, key = { it.route }) { item ->
                FeatureRow(
                    item = item,
                    accent = accent,
                    pinned = item.route in pinnedRoutes,
                    menuExpanded = menuRoute == item.route,
                    onMenuDismiss = onMenuDismiss,
                    onTogglePin = { onTogglePin(item) },
                    onInfo = { onInfo(item) },
                    onClick = { onOpen(item) },
                    onLongClick = { onLongPress(item) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (matchingIsEmpty(features, pinned, regular)) {
            item(key = "hub_empty") {
                CategoryEmptyState(
                    searching = query.isNotBlank(),
                    accent = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun CategoryGrid(
    paddingValues: PaddingValues,
    category: FeatureCategory,
    features: List<FunctionDestination>,
    pinned: List<FunctionDestination>,
    regular: List<FunctionDestination>,
    query: String,
    onQueryChange: (String) -> Unit,
    accent: Color,
    deviceStatus: CategoryDeviceStatus,
    onDeviceAction: () -> Unit,
    pinnedRoutes: List<String>,
    menuRoute: String?,
    onMenuDismiss: () -> Unit,
    onTogglePin: (FunctionDestination) -> Unit,
    onInfo: (FunctionDestination) -> Unit,
    onOpen: (FunctionDestination) -> Unit,
    onLongPress: (FunctionDestination) -> Unit,
    onViewModeChange: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 176.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "hub_hero", span = { GridItemSpan(maxLineSpan) }) {
            CategoryHero(
                category = category,
                count = features.size,
                accent = accent,
                deviceStatus = deviceStatus,
                onDeviceAction = onDeviceAction,
            )
        }
        item(key = "hub_search", span = { GridItemSpan(maxLineSpan) }) {
            CategorySearchRow(
                query = query,
                onQueryChange = onQueryChange,
                accent = accent,
                viewMode = 1,
                onViewModeChange = onViewModeChange,
            )
        }
        if (pinned.isNotEmpty()) {
            item(key = "hub_pinned_header", span = { GridItemSpan(maxLineSpan) }) {
                CategorySectionHeader(
                    title = stringResource(R.string.category_pinned),
                    count = pinned.size,
                    accent = accent,
                )
            }
            gridItems(pinned, key = { "pinned_${it.route}" }) { item ->
                FeatureTile(
                    item = item,
                    accent = accent,
                    pinned = true,
                    menuExpanded = menuRoute == item.route,
                    onMenuDismiss = onMenuDismiss,
                    onTogglePin = { onTogglePin(item) },
                    onInfo = { onInfo(item) },
                    onClick = { onOpen(item) },
                    onLongClick = { onLongPress(item) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (regular.isNotEmpty()) {
            item(key = "hub_all_header", span = { GridItemSpan(maxLineSpan) }) {
                CategorySectionHeader(
                    title = if (query.isBlank()) {
                        stringResource(R.string.category_all_tools)
                    } else {
                        stringResource(R.string.category_search_results, regular.size + pinned.size)
                    },
                    count = regular.size,
                    accent = accent,
                )
            }
            gridItems(regular, key = { it.route }) { item ->
                FeatureTile(
                    item = item,
                    accent = accent,
                    pinned = item.route in pinnedRoutes,
                    menuExpanded = menuRoute == item.route,
                    onMenuDismiss = onMenuDismiss,
                    onTogglePin = { onTogglePin(item) },
                    onInfo = { onInfo(item) },
                    onClick = { onOpen(item) },
                    onLongClick = { onLongPress(item) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (matchingIsEmpty(features, pinned, regular)) {
            item(key = "hub_empty", span = { GridItemSpan(maxLineSpan) }) {
                CategoryEmptyState(
                    searching = query.isNotBlank(),
                    accent = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                )
            }
        }
    }
}

private fun matchingIsEmpty(
    features: List<FunctionDestination>,
    pinned: List<FunctionDestination>,
    regular: List<FunctionDestination>,
): Boolean = features.isEmpty() || (pinned.isEmpty() && regular.isEmpty())

@Composable
private fun CategoryHero(
    category: FeatureCategory,
    count: Int,
    accent: Color,
    deviceStatus: CategoryDeviceStatus,
    onDeviceAction: () -> Unit,
) {
    val shape = RoundedCornerShape(30.dp)
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val description = stringResource(categoryDescriptionRes(category))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.24f),
                        surface,
                        surface,
                    ),
                    start = Offset.Zero,
                    end = Offset(900f, 520f),
                )
            )
            .border(1.dp, accent.copy(alpha = 0.16f), shape)
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                color = accent.copy(alpha = 0.08f),
                radius = size.minDimension * 0.56f,
                center = Offset(size.width * 0.92f, size.height * 0.04f),
            )
            drawCircle(
                color = accent.copy(alpha = 0.05f),
                radius = size.minDimension * 0.35f,
                center = Offset(size.width * 0.56f, size.height * 1.06f),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    modifier = Modifier.size(58.dp),
                    shape = RoundedCornerShape(19.dp),
                    color = accent.copy(alpha = 0.17f),
                    contentColor = accent,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = category.icon,
                            contentDescription = null,
                            modifier = Modifier.size(31.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(category.titleRes),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.3).sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    icon = Icons.Default.Apps,
                    text = stringResource(R.string.category_tools_count, count),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CategoryDeviceStatusChip(
                    status = deviceStatus,
                    onAction = onDeviceAction,
                )
            }
        }
    }
}

@Composable
private fun CategoryDeviceStatusChip(
    status: CategoryDeviceStatus,
    onAction: () -> Unit,
) {
    val tone = when (status.state) {
        CategoryDeviceState.READY -> successGreenColor()
        CategoryDeviceState.ACTION_REQUIRED,
        CategoryDeviceState.UNAVAILABLE -> MaterialTheme.colorScheme.error
    }
    val actionIcon = when (status.action) {
        CategoryDeviceAction.REQUEST_PERMISSION -> Icons.Default.Lock
        CategoryDeviceAction.ENABLE_BLUETOOTH -> Icons.Default.Bluetooth
        CategoryDeviceAction.OPEN_WIFI_SETTINGS,
        CategoryDeviceAction.OPEN_NFC_SETTINGS -> Icons.Default.Settings
        CategoryDeviceAction.NONE -> null
    }

    when {
        status.state == CategoryDeviceState.READY -> StatusPill(
            icon = Icons.Default.CheckCircle,
            text = stringResource(R.string.category_status_ready),
            containerColor = tone.copy(alpha = 0.12f),
            contentColor = tone,
        )
        status.action != CategoryDeviceAction.NONE &&
            status.actionLabel != null &&
            actionIcon != null -> DeviceActionChip(
                icon = actionIcon,
                label = status.actionLabel,
                tone = tone,
                onClick = onAction,
            )
        else -> StatusPill(
            icon = Icons.Default.Close,
            text = if (status.state == CategoryDeviceState.UNAVAILABLE) {
                stringResource(R.string.category_device_headline_unavailable)
            } else {
                status.headline
            },
            containerColor = tone.copy(alpha = 0.12f),
            contentColor = tone,
        )
    }
}

@Composable
private fun DeviceActionChip(
    icon: ImageVector,
    label: String,
    tone: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = tone.copy(alpha = 0.12f),
        contentColor = tone,
        border = BorderStroke(1.dp, tone.copy(alpha = 0.24f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatusPill(
    icon: ImageVector,
    text: String,
    containerColor: Color,
    contentColor: Color,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CategorySearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    accent: Color,
    viewMode: Int,
    onViewModeChange: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            placeholder = {
                Text(
                    text = stringResource(R.string.category_search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.category_clear_search),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = accent.copy(alpha = 0.65f),
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        ViewModeSwitch(
            viewMode = viewMode,
            accent = accent,
            onViewModeChange = onViewModeChange,
        )
    }
}

@Composable
private fun ViewModeSwitch(
    viewMode: Int,
    accent: Color,
    onViewModeChange: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val colors = IconButtonDefaults.iconToggleButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = accent.copy(alpha = 0.16f),
                checkedContentColor = accent,
            )
            IconToggleButton(
                checked = viewMode == 0,
                onCheckedChange = { if (!it) onViewModeChange() },
                modifier = Modifier.size(38.dp),
                colors = colors,
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = Icons.Default.ViewAgenda,
                    contentDescription = stringResource(R.string.category_view_list),
                    modifier = Modifier.size(19.dp),
                )
            }
            IconToggleButton(
                checked = viewMode == 1,
                onCheckedChange = { if (!it) onViewModeChange() },
                modifier = Modifier.size(38.dp),
                colors = colors,
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = Icons.Default.GridView,
                    contentDescription = stringResource(R.string.category_view_grid),
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun CategorySectionHeader(
    title: String,
    count: Int,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Surface(
            shape = CircleShape,
            color = accent.copy(alpha = 0.12f),
            contentColor = accent,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun FeatureRow(
    item: FunctionDestination,
    accent: Color,
    pinned: Boolean,
    menuExpanded: Boolean,
    onMenuDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onInfo: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(stiffness = 900f, dampingRatio = 0.8f),
        label = "feature_row_scale",
    )

    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(17.dp),
                    color = accent.copy(alpha = 0.13f),
                    contentColor = accent,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            modifier = Modifier.size(27.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.description.isNotBlank()) {
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.requiresRoot || item.isPlugin || pinned) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (item.isPlugin) BadgeLabel(stringResource(R.string.category_badge_plugin), accent)
                            if (item.requiresRoot) RootBadge(accentColor = accent)
                            if (pinned) {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        FeatureMenu(
            expanded = menuExpanded,
            pinned = pinned,
            accent = accent,
            onDismiss = onMenuDismiss,
            onTogglePin = onTogglePin,
            onInfo = onInfo,
        )
    }
}

@Composable
private fun FeatureTile(
    item: FunctionDestination,
    accent: Color,
    pinned: Boolean,
    menuExpanded: Boolean,
    onMenuDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onInfo: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(stiffness = 900f, dampingRatio = 0.8f),
        label = "feature_tile_scale",
    )

    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp)
                .scale(scale)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .animateContentSize(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(15.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Surface(
                        modifier = Modifier.size(46.dp),
                        shape = RoundedCornerShape(15.dp),
                        color = accent.copy(alpha = 0.13f),
                        contentColor = accent,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (pinned) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        if (item.requiresRoot) RootBadge(accentColor = accent)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.description.isNotBlank()) {
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.isPlugin) {
                        BadgeLabel(stringResource(R.string.category_badge_plugin), accent)
                    }
                }
            }
        }

        FeatureMenu(
            expanded = menuExpanded,
            pinned = pinned,
            accent = accent,
            onDismiss = onMenuDismiss,
            onTogglePin = onTogglePin,
            onInfo = onInfo,
        )
    }
}

@Composable
private fun FeatureMenu(
    expanded: Boolean,
    pinned: Boolean,
    accent: Color,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onInfo: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(
                        if (pinned) R.string.action_unpin else R.string.action_pin,
                    ),
                    fontWeight = FontWeight.Medium,
                )
            },
            leadingIcon = {
                Icon(Icons.Default.PushPin, contentDescription = null, tint = accent)
            },
            onClick = onTogglePin,
        )
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(R.string.action_info),
                    fontWeight = FontWeight.Medium,
                )
            },
            leadingIcon = {
                Icon(Icons.Default.Info, contentDescription = null, tint = accent)
            },
            onClick = onInfo,
        )
    }
}

@Composable
private fun BadgeLabel(text: String, accent: Color) {
    Surface(
        shape = CircleShape,
        color = accent.copy(alpha = 0.11f),
        contentColor = accent,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CategoryEmptyState(
    searching: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(62.dp),
            shape = CircleShape,
            color = accent.copy(alpha = 0.11f),
            contentColor = accent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (searching) Icons.Default.SearchOff else Icons.Default.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Text(
            text = stringResource(
                if (searching) R.string.category_search_empty else R.string.cat_empty,
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (searching) {
            Text(
                text = stringResource(R.string.category_search_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FeatureInfoDialog(
    info: FunctionDestination,
    accent: Color,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = accent.copy(alpha = 0.14f),
                contentColor = accent,
                modifier = Modifier.size(58.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(info.icon, contentDescription = null, modifier = Modifier.size(30.dp))
                }
            }
        },
        title = {
            Text(
                text = info.title,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = info.description.ifBlank { stringResource(R.string.other_no_description) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BadgeLabel(sectionTitle(info.section), accent)
                    if (info.requiresRoot) BadgeLabel(stringResource(R.string.category_badge_root), accent)
                    if (info.isPlugin) BadgeLabel(stringResource(R.string.category_badge_plugin), accent)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK", fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun rememberCategoryFeatures(category: FeatureCategory): List<FunctionDestination> {
    val sections = functionDestinationSections()
    return remember(sections, category.id) {
        sections
            .flatMap { it.second }
            .filter { item ->
                val itemCategory = if (item.isPlugin) {
                    FeatureTaxonomy.categoryIdForPlugin(item.section, item.title, item.description)
                } else {
                    FeatureTaxonomy.categoryIdForRoute(item.route) ?: FeatureTaxonomy.OTHER
                }
                itemCategory == category.id
            }
            .distinctBy { it.route }
    }
}

private enum class CategoryDeviceAction {
    NONE,
    REQUEST_PERMISSION,
    ENABLE_BLUETOOTH,
    OPEN_WIFI_SETTINGS,
    OPEN_NFC_SETTINGS,
}

private enum class CategoryDeviceState { READY, ACTION_REQUIRED, UNAVAILABLE }

private data class CategoryDeviceStatus(
    val state: CategoryDeviceState,
    val headline: String,
    val detail: String,
    val action: CategoryDeviceAction = CategoryDeviceAction.NONE,
    val actionLabel: String? = null,
    val missingPermissions: List<String> = emptyList(),
)

private fun readCategoryDeviceStatus(context: Context, category: FeatureCategory): CategoryDeviceStatus =
    when (category) {
        FeatureCategory.BLUETOOTH -> bluetoothDeviceStatus(context)
        FeatureCategory.NETWORK -> networkDeviceStatus(context)
        FeatureCategory.INFRARED -> infraredDeviceStatus(context)
        FeatureCategory.NFC -> nfcDeviceStatus(context)
        FeatureCategory.QR -> qrDeviceStatus(context)
        FeatureCategory.USB -> usbDeviceStatus(context)
        FeatureCategory.MEDIA -> mediaDeviceStatus(context)
        FeatureCategory.OTHER -> otherDeviceStatus(context)
    }

private fun bluetoothDeviceStatus(context: Context): CategoryDeviceStatus {
    val missing = missingPermissions(context, bluetoothPermissions())
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    return when {
        adapter == null -> unavailableDeviceStatus(
            context,
            R.string.category_device_bt_unavailable_detail,
        )
        missing.isNotEmpty() -> actionDeviceStatus(
            context = context,
            action = CategoryDeviceAction.REQUEST_PERMISSION,
            actionLabelRes = R.string.category_device_action_grant_bt,
            detailRes = R.string.category_device_bt_permission_detail,
            missingPermissions = missing,
        )
        !readBluetoothEnabled(context) -> actionDeviceStatus(
            context = context,
            action = CategoryDeviceAction.ENABLE_BLUETOOTH,
            actionLabelRes = R.string.category_device_action_enable_bt,
            detailRes = R.string.category_device_bt_off_detail,
        )
        else -> readyDeviceStatus(context, R.string.category_device_bt_ready_detail)
    }
}

private fun networkDeviceStatus(context: Context): CategoryDeviceStatus {
    val missing = missingPermissions(context, wifiPermissions())
    return when {
        missing.isNotEmpty() -> actionDeviceStatus(
            context = context,
            action = CategoryDeviceAction.REQUEST_PERMISSION,
            actionLabelRes = R.string.category_device_action_grant_wifi,
            detailRes = R.string.category_device_network_permission_detail,
            missingPermissions = missing,
        )
        !hasActiveNetwork(context) -> actionDeviceStatus(
            context = context,
            action = CategoryDeviceAction.OPEN_WIFI_SETTINGS,
            actionLabelRes = R.string.category_device_action_wifi,
            detailRes = R.string.category_device_network_off_detail,
        )
        else -> readyDeviceStatus(context, R.string.category_device_network_ready_detail)
    }
}

private fun infraredDeviceStatus(context: Context): CategoryDeviceStatus = when {
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR) ->
        readyDeviceStatus(context, R.string.category_device_ir_ready_detail)
    runCatching { UsbIrManager.isConnected.value }.getOrDefault(false) ->
        readyDeviceStatus(context, R.string.category_device_ir_usb_detail)
    else -> unavailableDeviceStatus(context, R.string.category_device_ir_unavailable_detail)
}

private fun nfcDeviceStatus(context: Context): CategoryDeviceStatus {
    val adapter = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
    return when {
        adapter == null -> unavailableDeviceStatus(
            context,
            R.string.category_device_nfc_unavailable_detail,
        )
        runCatching { adapter.isEnabled }.getOrDefault(false) ->
            readyDeviceStatus(context, R.string.category_device_nfc_ready_detail)
        else -> actionDeviceStatus(
            context = context,
            action = CategoryDeviceAction.OPEN_NFC_SETTINGS,
            actionLabelRes = R.string.category_device_action_nfc,
            detailRes = R.string.category_device_nfc_off_detail,
        )
    }
}

private fun qrDeviceStatus(context: Context): CategoryDeviceStatus {
    val missing = missingPermissions(context, listOf(Manifest.permission.CAMERA))
    return when {
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ->
            unavailableDeviceStatus(context, R.string.category_device_qr_unavailable_detail)
        missing.isNotEmpty() -> actionDeviceStatus(
            context = context,
            action = CategoryDeviceAction.REQUEST_PERMISSION,
            actionLabelRes = R.string.category_device_action_grant_camera,
            detailRes = R.string.category_device_qr_permission_detail,
            missingPermissions = missing,
        )
        else -> readyDeviceStatus(context, R.string.category_device_qr_ready_detail)
    }
}

private fun usbDeviceStatus(context: Context): CategoryDeviceStatus {
    val connected = context.getSystemService(UsbManager::class.java)
        ?.deviceList?.isNotEmpty() == true
    return when {
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST) ->
            unavailableDeviceStatus(context, R.string.category_device_usb_unavailable_detail)
        connected -> readyDeviceStatus(context, R.string.category_device_usb_connected_detail)
        else -> readyDeviceStatus(context, R.string.category_device_usb_ready_detail)
    }
}

private fun mediaDeviceStatus(context: Context): CategoryDeviceStatus =
    if (hasActiveNetwork(context)) {
        readyDeviceStatus(context, R.string.category_device_media_ready_detail)
    } else {
        actionDeviceStatus(
            context = context,
            action = CategoryDeviceAction.OPEN_WIFI_SETTINGS,
            actionLabelRes = R.string.category_device_action_wifi,
            detailRes = R.string.category_device_media_off_detail,
        )
    }

private fun otherDeviceStatus(context: Context): CategoryDeviceStatus =
    readyDeviceStatus(context, R.string.category_device_other_ready_detail)

private fun performCategoryDeviceAction(
    context: Context,
    status: CategoryDeviceStatus,
    onRequestPermissions: (Array<String>) -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    vibrate(context)
    val missing = status.missingPermissions
    if (missing.isNotEmpty()) {
        onRequestPermissions(missing.toTypedArray())
        return
    }
    when (status.action) {
        CategoryDeviceAction.ENABLE_BLUETOOTH -> runCatching(onEnableBluetooth)
        CategoryDeviceAction.OPEN_WIFI_SETTINGS -> runCatching {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        CategoryDeviceAction.OPEN_NFC_SETTINGS -> runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        CategoryDeviceAction.NONE, CategoryDeviceAction.REQUEST_PERMISSION -> Unit
    }
}

private fun readyDeviceStatus(context: Context, detailRes: Int) = CategoryDeviceStatus(
    state = CategoryDeviceState.READY,
    headline = context.getString(R.string.category_device_headline_ready),
    detail = context.getString(detailRes),
)

private fun actionDeviceStatus(
    context: Context,
    action: CategoryDeviceAction,
    actionLabelRes: Int,
    detailRes: Int,
    missingPermissions: List<String> = emptyList(),
) = CategoryDeviceStatus(
    state = CategoryDeviceState.ACTION_REQUIRED,
    headline = context.getString(R.string.category_device_headline_action),
    detail = context.getString(detailRes),
    action = action,
    actionLabel = context.getString(actionLabelRes),
    missingPermissions = missingPermissions,
)

private fun unavailableDeviceStatus(context: Context, detailRes: Int) = CategoryDeviceStatus(
    state = CategoryDeviceState.UNAVAILABLE,
    headline = context.getString(R.string.category_device_headline_unavailable),
    detail = context.getString(detailRes),
)

private fun missingPermissions(context: Context, permissions: List<String>): List<String> =
    permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

private fun bluetoothPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
} else {
    listOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun wifiPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
} else {
    listOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun hasActiveNetwork(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private fun readBluetoothEnabled(context: Context): Boolean {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
    return runCatching { adapter.isEnabled }.getOrDefault(false)
}

private fun categoryDescriptionRes(category: FeatureCategory): Int = when (category) {
    FeatureCategory.BLUETOOTH -> R.string.category_bluetooth_desc
    FeatureCategory.NETWORK -> R.string.category_network_desc
    FeatureCategory.INFRARED -> R.string.category_infrared_desc
    FeatureCategory.NFC -> R.string.category_nfc_desc
    FeatureCategory.QR -> R.string.category_qr_desc
    FeatureCategory.USB -> R.string.category_usb_desc
    FeatureCategory.MEDIA -> R.string.category_media_desc
    FeatureCategory.OTHER -> R.string.category_other_desc
}
