package com.knap43.rocker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knap43.rocker.data.Action
import com.knap43.rocker.data.Config
import com.knap43.rocker.data.Gesture
import com.knap43.rocker.data.Key
import com.knap43.rocker.data.Kind
import com.knap43.rocker.data.Store
import com.knap43.rocker.engine.RockerService
import com.knap43.rocker.engine.Status
import com.knap43.rocker.ui.theme.AppearanceDialog
import com.knap43.rocker.ui.theme.ProseFamily
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val config by Store.config(context).collectAsStateWithLifecycle()
    val update: ((Config) -> Config) -> Unit = { Store.update(context, it) }

    var menuOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<Gesture?>(null) }
    var resetOpen by remember { mutableStateOf(false) }

    if (appearanceOpen) AppearanceDialog(onDismiss = { appearanceOpen = false })
    choosing?.let { gesture ->
        ActionDialog(
            gesture = gesture,
            current = config.action(gesture),
            onPick = { action -> update { it.copy(actions = it.actions + (gesture to action)) } },
            onDismiss = { choosing = null },
        )
    }
    if (resetOpen) {
        AlertDialog(
            onDismissRequest = { resetOpen = false },
            title = { Text("Restore defaults?") },
            text = { Text("Holds skip tracks, both keys together play or pause, and everything else changes the volume.") },
            confirmButton = { TextButton(onClick = { update { Config() }; resetOpen = false }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { resetOpen = false }) { Text("Cancel") } },
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("Rocker", fontFamily = ProseFamily) },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Appearance") },
                                leadingIcon = { Icon(Icons.Outlined.Palette, null) },
                                onClick = { menuOpen = false; appearanceOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Restore defaults") },
                                leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                                onClick = { menuOpen = false; resetOpen = true },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp), modifier = Modifier.navigationBarsPadding()) {
            item { SetupCard() }
            item { StatusPanel() }

            Key.entries.forEach { key ->
                item { SectionHeader(key.label) }
                item {
                    Column(Modifier.panel()) {
                        Kind.entries.forEachIndexed { i, kind ->
                            if (i > 0) RowDivider()
                            val gesture = Gesture.of(key, kind)
                            MappingRow(gesture, kind.label, config.action(gesture)) { choosing = gesture }
                        }
                    }
                }
            }
            item { SectionHeader("Both keys") }
            item {
                Column(Modifier.panel()) {
                    MappingRow(
                        Gesture.BOTH,
                        "Press together",
                        config.action(Gesture.BOTH),
                        note = "Screen on only — with it off, Android reports one key at a time",
                    ) { choosing = Gesture.BOTH }
                }
            }

            item { SectionHeader("Timing") }
            item {
                Column(Modifier.panel().padding(vertical = 8.dp)) {
                    TimingSlider(
                        title = "Hold for",
                        value = config.holdMs,
                        range = 250..1000,
                        onChange = { ms -> update { it.copy(holdMs = ms) } },
                    )
                    TimingSlider(
                        title = "Double-press window",
                        value = config.doubleMs,
                        range = 150..600,
                        onChange = { ms -> update { it.copy(doubleMs = ms) } },
                    )
                    Text(
                        "A key with a double press waits this long before an ordinary press counts. " +
                            "With the screen off, holds follow the system's key-repeat delay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            item { SectionHeader("Options") }
            item {
                Column(Modifier.panel()) {
                    SwitchRow(
                        "Work with the screen off",
                        "Takes over the volume keys while the screen is off; they then change media volume only",
                        config.screenOff,
                    ) { on -> update { it.copy(screenOff = on) } }
                    RowDivider()
                    SwitchRow(
                        "Only while something plays",
                        "When nothing is playing, the keys are plain volume keys again — except play/pause, so you can resume",
                        config.onlyWhilePlaying,
                    ) { on -> update { it.copy(onlyWhilePlaying = on) } }
                    RowDivider()
                    SwitchRow(
                        "Vibrate",
                        "A short tick when a gesture plays, pauses or skips",
                        config.vibrate,
                    ) { on -> update { it.copy(vibrate = on) } }
                }
            }
        }
    }
}

private data class Step(val icon: ImageVector, val title: String, val detail: String, val button: String, val act: () -> Unit)

/** What still needs granting, listed only while missing; re-checked whenever the app returns. */
@Composable
private fun SetupCard() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val steps = remember(tick) {
        buildList {
            if (!RockerService.enabled(context)) {
                add(Step(
                    Icons.Outlined.Accessibility,
                    "Turn on Rocker",
                    "In Accessibility, under installed or downloaded apps. If the switch is greyed out, " +
                        "open App info → ⋮ → Allow restricted settings, then try again.",
                    "Open",
                ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            }
            if (!unrestricted(context)) {
                add(Step(
                    Icons.Outlined.BatteryAlert,
                    "Battery restrictions",
                    "Some phones, realme among them, freeze background services with the screen off.",
                    "Allow",
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    )
                })
            }
        }
    }
    if (steps.isEmpty()) return

    Column(Modifier.panel().padding(vertical = 8.dp)) {
        Text(
            "Before the buttons listen",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        steps.forEach { step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Icon(step.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = MaterialTheme.typography.bodyLarge)
                    Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = step.act) { Text(step.button) }
            }
        }
        if (steps.any { it.icon == Icons.Outlined.Accessibility }) {
            TextButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                },
                modifier = Modifier.padding(start = 46.dp),
            ) { Text("App info") }
        }
    }
}

/** Whether the service is listening, and the last thing it did — the quickest way to test a gesture. */
@Composable
private fun StatusPanel() {
    val running by Status.running.collectAsStateWithLifecycle()
    val last by Status.last.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(last) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.panel().padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (running) "Listening" else "Not running",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = ProseFamily),
            )
            val event = last
            Text(
                when {
                    event != null -> "${event.text}, " +
                        DateUtils.getRelativeTimeSpanString(event.at, now, DateUtils.SECOND_IN_MILLIS).toString().lowercase()
                    running -> "Try a gesture; the last one will show here."
                    else -> "Switch the service on to begin."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MappingRow(gesture: Gesture, title: String, action: Action, note: String? = null, onClick: () -> Unit) {
    val plain = action == Action.VOLUME || (gesture == Gesture.BOTH && action == Action.NOTHING)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            action.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (plain) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (plain) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ActionDialog(gesture: Gesture, current: Action, onPick: (Action) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(gesture.label) },
        text = {
            Column(Modifier.selectableGroup()) {
                gesture.choices.forEach { action ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(
                                selected = action == current,
                                role = Role.RadioButton,
                                onClick = { onPick(action); onDismiss() },
                            )
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = action == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(action.label, style = MaterialTheme.typography.bodyLarge)
                            hint(gesture, action)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun hint(gesture: Gesture, action: Action): String? = when {
    action == Action.VOLUME && gesture.kind == Kind.PRESS -> "What the button normally does"
    action == Action.VOLUME && gesture.kind == Kind.DOUBLE -> "No double press: two ordinary steps"
    action == Action.VOLUME && gesture.kind == Kind.HOLD -> "Ramp the volume as usual"
    action == Action.NOTHING && gesture != Gesture.BOTH -> "Silence this gesture entirely"
    else -> null
}

@Composable
private fun TimingSlider(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    // Follow the thumb locally and save once it's let go, rather than on every pixel.
    var dragging by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${dragging.roundToInt()} ms", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = dragging,
            onValueChange = { dragging = (it / 10).roundToInt() * 10f },
            onValueChangeFinished = { onChange(dragging.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 16.dp))
}

private fun unrestricted(context: Context) =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true
