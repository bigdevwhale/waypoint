package app.waypoint.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.waypoint.BuildConfig
import app.waypoint.R
import app.waypoint.container
import app.waypoint.data.Backup
import app.waypoint.data.Settings
import app.waypoint.data.ThemeMode
import app.waypoint.notify.Alarms
import app.waypoint.ui.Language
import app.waypoint.ui.clockTime
import app.waypoint.ui.components.Chip
import app.waypoint.ui.components.CommunityLinks
import app.waypoint.ui.components.ConfirmDialog
import app.waypoint.ui.components.SectionTitle
import app.waypoint.ui.components.TimeDialog
import app.waypoint.ui.components.TopBar
import app.waypoint.ui.components.UCard
import app.waypoint.ui.components.UTextField
import app.waypoint.ui.theme.U
import app.waypoint.ui.theme.UType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(settings: Settings, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    fun update(change: (Settings) -> Settings) {
        container.scope.launch {
            container.settings.update(change)
            container.changed()
        }
    }
    var canNotify by remember { mutableStateOf(Alarms.canNotify(context)) }
    // Re-read on resume so the permission card disappears after granting it in system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canNotify = Alarms.canNotify(context) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        canNotify = it
        container.scope.launch { container.changed() }
    }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var pickTime by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(settings.name) }
    LaunchedEffect(name) {
        delay(400)
        if (name.trim() != settings.name) update { it.copy(name = name.trim()) }
    }

    val exported = stringResource(R.string.backup_done)
    val failed = stringResource(R.string.backup_failed)
    val restored = stringResource(R.string.backup_restored)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        container.scope.launch {
            val ok = runCatching {
                val json = Backup.encode(container.dao.snapshot())
                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
            }.isSuccess
            withContext(Dispatchers.Main) { Toast.makeText(context, if (ok) exported else failed, Toast.LENGTH_SHORT).show() }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingImport = uri }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar(stringResource(R.string.settings), onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            if (!canNotify) {
                Spacer(Modifier.height(8.dp))
                UCard(onClick = { if (Build.VERSION.SDK_INT >= 33) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text(stringResource(R.string.perm_title), style = UType.Strong.copy(color = U.c.accent))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.perm_text), style = UType.Muted)
                }
            }

            SectionTitle(stringResource(R.string.set_name))
            UTextField(name, { name = it.take(30) }, placeholder = stringResource(R.string.ob_name_hint))

            SectionTitle(stringResource(R.string.set_focus))
            Text(stringResource(R.string.set_focus_text), style = UType.Muted, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { n -> Chip(n.toString(), settings.focusLimit == n, { update { it.copy(focusLimit = n) } }) }
                Chip(stringResource(R.string.set_focus_off), settings.focusLimit == 0, { update { it.copy(focusLimit = 0) } })
            }

            SectionTitle(stringResource(R.string.set_notifications))
            UCard {
                SwitchRow(stringResource(R.string.set_morning), stringResource(R.string.set_morning_text, clockTime(settings.morningAt)), settings.morningOn, { pickTime = 1 }) { v -> update { it.copy(morningOn = v) } }
                Divider()
                SwitchRow(stringResource(R.string.set_evening), stringResource(R.string.set_evening_text, clockTime(settings.eveningAt)), settings.eveningOn, { pickTime = 2 }) { v -> update { it.copy(eveningOn = v) } }
                Divider()
                val dayName = DayOfWeek.of(settings.reviewDay).getDisplayName(TextStyle.FULL_STANDALONE, app.waypoint.ui.locale())
                SwitchRow(stringResource(R.string.set_review), stringResource(R.string.set_review_text, dayName, clockTime(settings.reviewAt)), settings.reviewOn, { pickTime = 3 }) { v -> update { it.copy(reviewOn = v) } }
                if (settings.reviewOn) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..7).forEach { d ->
                            Chip(app.waypoint.ui.weekdayShort(d - 1), settings.reviewDay == d, { update { it.copy(reviewDay = d) } })
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            UCard(onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) {
                Text(stringResource(R.string.autostart_title), style = UType.Strong)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.autostart_text), style = UType.Muted)
            }

            SectionTitle(stringResource(R.string.set_feel))
            UCard {
                SwitchRow(stringResource(R.string.set_haptics), stringResource(R.string.set_haptics_text), settings.haptics, null) { v -> update { it.copy(haptics = v) } }
            }

            SectionTitle(stringResource(R.string.set_appearance))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.theme_dark), settings.theme == ThemeMode.DARK, { update { it.copy(theme = ThemeMode.DARK) } })
                Chip(stringResource(R.string.theme_light), settings.theme == ThemeMode.LIGHT, { update { it.copy(theme = ThemeMode.LIGHT) } })
                Chip(stringResource(R.string.theme_system), settings.theme == ThemeMode.SYSTEM, { update { it.copy(theme = ThemeMode.SYSTEM) } })
            }

            SectionTitle(stringResource(R.string.language))
            val current = Language.current()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.lang_system), current == Language.SYSTEM, { Language.set(Language.SYSTEM) })
                Chip("English", current == Language.EN, { Language.set(Language.EN) })
                Chip("Русский", current == Language.RU, { Language.set(Language.RU) })
            }

            SectionTitle(stringResource(R.string.backup))
            UCard {
                Text(stringResource(R.string.backup_text), style = UType.Muted)
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { export.launch("waypoint-${LocalDate.now()}.json") }) {
                        Text(stringResource(R.string.backup_export), color = U.c.accent, style = UType.Button)
                    }
                    TextButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                        Text(stringResource(R.string.backup_import), color = U.c.accent, style = UType.Button)
                    }
                }
            }

            CommunityLinks()

            SectionTitle(stringResource(R.string.about))
            UCard {
                Text(stringResource(R.string.about_text), style = UType.Sub)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = UType.Small)
            }
        }
    }

    when (pickTime) {
        1 -> TimeDialog(settings.morningAt, { pickTime = 0 }) { m -> update { it.copy(morningAt = m, morningOn = true) } }
        2 -> TimeDialog(settings.eveningAt, { pickTime = 0 }) { m -> update { it.copy(eveningAt = m, eveningOn = true) } }
        3 -> TimeDialog(settings.reviewAt, { pickTime = 0 }) { m -> update { it.copy(reviewAt = m, reviewOn = true) } }
    }

    pendingImport?.let { uri ->
        ConfirmDialog(
            title = stringResource(R.string.backup_import_title),
            text = stringResource(R.string.backup_import_text),
            confirm = stringResource(R.string.backup_import),
            danger = true,
            onConfirm = {
                container.scope.launch {
                    val ok = runCatching {
                        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                        container.dao.replaceAll(Backup.decode(text))
                        container.changed()
                    }.isSuccess
                    withContext(Dispatchers.Main) { Toast.makeText(context, if (ok) restored else failed, Toast.LENGTH_SHORT).show() }
                }
            },
            onDismiss = { pendingImport = null },
        )
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 10.dp), color = U.c.line)

/** A setting with a switch; when [onClick] is set, tapping the text changes it (e.g. the time). */
@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onClick: (() -> Unit)?, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
            Text(title, style = UType.Strong)
            Spacer(Modifier.height(2.dp))
            Text(text, style = UType.Muted.copy(color = if (onClick != null) U.c.accent else U.c.muted))
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = U.c.accent, checkedThumbColor = U.c.onAccent))
    }
}
