@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.TvReorderList
import com.nuvio.tv.ui.components.tvReorderDisabledFocusBorder
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import kotlinx.coroutines.delay

@Composable
fun CatalogOrderScreen(
    viewModel: CatalogOrderViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    // Live TV fork: adding and changing Live TV / Sports rows.
    val rowsSetup: com.nuvio.tv.livetv.home.HomeRowsSetupViewModel = hiltViewModel()
    var addingRow by remember { mutableStateOf(false) }
    var editingRowId by remember { mutableStateOf<String?>(null) }
    val liveRows by rowsSetup.rows.collectAsState()
    val emptyRowIds by rowsSetup.emptyRowIds.collectAsState()
    val playlistNames by rowsSetup.playlistNames.collectAsState()
    if (addingRow) {
        com.nuvio.tv.livetv.home.AddHomeRowDialog(viewModel = rowsSetup, onDismiss = { addingRow = false })
    }
    editingRowId?.let { id ->
        val row = liveRows.firstOrNull { it.id == id }
        if (row == null) editingRowId = null
        else com.nuvio.tv.livetv.home.HomeRowOptionsDialog(row = row, viewModel = rowsSetup, onDismiss = { editingRowId = null })
    }
    // Live TV fork: Add a row, and naming rows with their playlist, under the header.
    val liveRowHeaderExtras: @Composable () -> Unit = {
        AddLiveRowCard(onClick = { addingRow = true })
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        com.nuvio.tv.ui.screens.settings.SettingsToggleRow(
            title = stringResource(R.string.home_rows_playlist_names),
            subtitle = stringResource(R.string.home_rows_playlist_names_hint),
            checked = playlistNames,
            onToggle = { rowsSetup.setPlaylistNames(!playlistNames) }
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    }
    val pinToMoveNotice = stringResource(R.string.catalog_order_pin_to_move_toast)
    var pinnedNoticeMessage by remember { mutableStateOf<String?>(null) }
    var pinnedNoticeNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(pinnedNoticeNonce) {
        if (pinnedNoticeMessage != null) {
            delay(3_000)
            pinnedNoticeMessage = null
        }
    }

    BackHandler(enabled = uiState.movingKey == null) {
        onBackPress()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background),
    ) {
        when {
            uiState.isLoading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl),
                ) {
                    CatalogOrderHeader(
                        followAddonsOrder = uiState.followAddonsOrder,
                        movingKey = uiState.movingKey,
                        onToggleFollowAddons = viewModel::toggleFollowAddonsOrder,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }
                }
            }

            uiState.items.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl),
                ) {
                    CatalogOrderHeader(
                        followAddonsOrder = uiState.followAddonsOrder,
                        movingKey = uiState.movingKey,
                        onToggleFollowAddons = viewModel::toggleFollowAddonsOrder,
                        extras = liveRowHeaderExtras,
                    )
                    Text(
                        text = stringResource(R.string.catalog_order_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary,
                    )
                }
            }

            else -> {
                val reorderScrollState = rememberScrollState()
                val movingItem = uiState.movingKey?.let { movingKey ->
                    uiState.items.firstOrNull { it.key == movingKey }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                TvReorderList(
                    items = uiState.items,
                    key = { it.key },
                    canMove = { it.canMove && uiState.movingKey == null },
                    movingKey = uiState.movingKey,
                    onStartMove = viewModel::enterMoveMode,
                    onMoveBy = viewModel::moveDuringDrag,
                    onConfirmMove = { viewModel.confirmMove() },
                    onCancelMove = { viewModel.cancelMove() },
                    modifier = Modifier.fillMaxSize(),
                    scrollState = reorderScrollState,
                    contentPadding = PaddingValues(
                        start = NuvioTheme.spacing.xxxl,
                        end = NuvioTheme.spacing.xxxl,
                        top = NuvioTheme.spacing.xl,
                        bottom = NuvioTheme.spacing.xxxl,
                    ),
                    header = {
                        CatalogOrderHeader(
                            followAddonsOrder = uiState.followAddonsOrder,
                            movingKey = uiState.movingKey,
                            onToggleFollowAddons = viewModel::toggleFollowAddonsOrder,
                            extras = liveRowHeaderExtras,
                        )
                    },
                    useHeaderAwareBringIntoView = true,
                    scrollToTopOnEdgeItem = true,
                ) { item, isMoving, onStartMove, onConfirmMove ->
                    CatalogOrderCard(
                        item = item,
                        isMoving = isMoving,
                        onStartMove = onStartMove,
                        onConfirmMove = onConfirmMove,
                        onPinnedMoveAttempt = {
                            pinnedNoticeMessage = pinToMoveNotice
                            pinnedNoticeNonce++
                        },
                        onToggleEnabled = { viewModel.toggleCatalogEnabled(item.disableKey) },
                        actionsEnabled = uiState.movingKey == null || isMoving,
                        onEdit = item.liveRow?.let { r -> { editingRowId = r.id } },
                        hiddenEmpty = item.liveRow?.id?.let { it in emptyRowIds } == true,
                    )
                }
                SettingsVerticalScrollIndicators(
                    state = reorderScrollState,
                    suppressBackward = movingItem != null && !movingItem.canMoveUp,
                    suppressForward = movingItem != null && !movingItem.canMoveDown,
                    forceBackward = movingItem?.canMoveUp == true,
                    forceForward = movingItem?.canMoveDown == true
                )
                }
            }
        }

        AnimatedVisibility(
            visible = pinnedNoticeMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl),
        ) {
            val visibleMessage = pinnedNoticeMessage ?: return@AnimatedVisibility
            Surface(
                shape = RoundedCornerShape(NuvioTheme.radii.md),
                colors = SurfaceDefaults.colors(containerColor = NuvioTheme.colors.BackgroundElevated),
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(NuvioTheme.radii.md),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = NuvioTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = NuvioTheme.colors.Secondary,
                    )
                    Text(
                        text = visibleMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogOrderHeader(
    followAddonsOrder: Boolean,
    movingKey: String?,
    onToggleFollowAddons: (Boolean) -> Unit,
    extras: @Composable () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
    Text(
        text = stringResource(R.string.catalog_order_title),
        style = MaterialTheme.typography.headlineLarge,
        color = NuvioTheme.colors.TextPrimary,
    )
    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
    Text(
        text = stringResource(R.string.catalog_order_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary,
    )
    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.catalog_order_follow_addons),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
            )
            Text(
                text = stringResource(R.string.catalog_order_follow_addons_desc),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
            )
        }
        Surface(
            onClick = {
                if (movingKey == null) {
                    onToggleFollowAddons(!followAddonsOrder)
                }
            },
            modifier = Modifier.semantics {
                role = Role.Switch
                toggleableState = if (followAddonsOrder) ToggleableState.On else ToggleableState.Off
            },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                )
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Switch(
                    checked = followAddonsOrder,
                    onCheckedChange = null,
                    enabled = movingKey == null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NuvioTheme.colors.Secondary,
                        checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.3f)
                    ),
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    extras()
    }
}

@Composable
private fun CatalogOrderCard(
    item: CatalogOrderItem,
    isMoving: Boolean,
    onStartMove: () -> Unit,
    onConfirmMove: () -> Unit,
    onPinnedMoveAttempt: () -> Unit,
    onToggleEnabled: () -> Unit,
    actionsEnabled: Boolean,
    onEdit: (() -> Unit)? = null,
    /** Live TV fork: a row left off Home for now, having nothing to show. */
    hiddenEmpty: Boolean = false,
) {
    val cardColor by animateColorAsState(
        targetValue = if (isMoving) {
            NuvioTheme.colors.FocusBackground
        } else {
            NuvioTheme.colors.BackgroundCard
        },
        animationSpec = NuvioMotion.focusTween(),
        label = "catalogOrderCardColor",
    )
    val cardBorder = if (isMoving) {
        BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing)
    } else {
        null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(NuvioTheme.radii.md),
        border = cardBorder,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            ) {
                Column {
                    val live = item.liveRow
                    val typeText = when (live?.kind) {
                        com.nuvio.tv.livetv.home.HomeLiveRow.KIND_SPORTS -> stringResource(R.string.home_rows_type_sports)
                        com.nuvio.tv.livetv.home.HomeLiveRow.KIND_CHANNELS -> stringResource(R.string.home_rows_type_live)
                        com.nuvio.tv.livetv.home.HomeLiveRow.KIND_VOD -> stringResource(R.string.home_rows_type_vod)
                        else -> item.typeLabel.toDisplayTypeLabel()
                    }
                    Text(
                        text = "${item.catalogName} - $typeText",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (item.isDisabled) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                    val subtitle = if (item.isPinnedToTop) {
                        "${item.addonName} • ${stringResource(R.string.catalog_order_pinned_to_top)}"
                    } else when (live?.kind) {
                        com.nuvio.tv.livetv.home.HomeLiveRow.KIND_SPORTS -> stringResource(
                            R.string.home_rows_desc_sports,
                            stringResource(if (live.startingSoon) R.string.home_rows_pick_sports_all_hint else R.string.home_rows_type_sports),
                            live.limit
                        )
                        com.nuvio.tv.livetv.home.HomeLiveRow.KIND_CHANNELS -> stringResource(
                            R.string.home_rows_desc_channels, stringResource(R.string.home_rows_type_live), live.limit
                        )
                        com.nuvio.tv.livetv.home.HomeLiveRow.KIND_VOD -> stringResource(
                            R.string.home_rows_desc_vod, stringResource(R.string.home_rows_type_vod), live.limit
                        )
                        else -> item.addonName
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    if (hiddenEmpty && !item.isDisabled) {
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                        Text(
                            text = stringResource(R.string.home_rows_hidden_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextTertiary
                        )
                    }
                    if (item.isDisabled) {
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                        Text(
                            text = stringResource(R.string.catalog_order_disabled_on_home),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.Error
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Live TV fork: a Live TV or Sports row's options.
                if (onEdit != null) {
                    Button(
                        onClick = onEdit,
                        enabled = actionsEnabled && !isMoving,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                        Text(text = stringResource(R.string.home_rows_edit))
                    }
                }
                val toggleLabel = stringResource(
                    if (item.isDisabled) R.string.catalog_order_enable else R.string.catalog_order_disable
                )
                Surface(
                    onClick = {
                        if (actionsEnabled && !isMoving) onToggleEnabled()
                    },
                    modifier = Modifier.semantics {
                        role = Role.Switch
                        contentDescription = toggleLabel
                        toggleableState = if (item.isDisabled) ToggleableState.Off else ToggleableState.On
                    },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Switch(
                            checked = !item.isDisabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NuvioTheme.colors.Secondary,
                                checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
                if (item.isPinnedToTop) {
                    Button(
                        onClick = onPinnedMoveAttempt,
                        enabled = actionsEnabled,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            ),
                            focusedDisabledBorder = tvReorderDisabledFocusBorder(RoundedCornerShape(NuvioTheme.radii.md))
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = stringResource(R.string.catalog_order_pinned),
                        )
                    }
                } else {
                    Button(
                        onClick = if (isMoving) onConfirmMove else onStartMove,
                        enabled = isMoving || (item.canMove && actionsEnabled),
                        colors = if (isMoving) {
                            ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.Secondary,
                                contentColor = NuvioTheme.colors.OnSecondary,
                                focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                                focusedContentColor = NuvioTheme.colors.OnSecondary,
                            )
                        } else {
                            ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextSecondary,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                focusedContentColor = NuvioTheme.colors.Primary
                            )
                        },
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            ),
                            focusedDisabledBorder = tvReorderDisabledFocusBorder(RoundedCornerShape(NuvioTheme.radii.md))
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Reorder,
                            contentDescription = stringResource(R.string.cd_reorder)
                        )
                    }
                }

            }
        }
    }
}

/** Live TV fork: the Add a row button at the top of the list. */
@Composable
private fun AddLiveRowCard(onClick: () -> Unit) {
    androidx.tv.material3.Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = androidx.tv.material3.CardDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = NuvioTheme.colors.Primary)
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            Column {
                Text(
                    text = stringResource(R.string.home_rows_add_button),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.home_rows_add_button_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
    }
}

private fun String.toDisplayTypeLabel(): String {
    return replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
    }
}
