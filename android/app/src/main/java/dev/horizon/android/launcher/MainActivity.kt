package dev.horizon.android.launcher

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.android.R
import dev.horizon.android.auth.AuthState
import dev.horizon.android.game.HorizonNative
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = HorizonColors) {
                LauncherScreen()
            }
        }
    }
}

private val HorizonColors = darkColorScheme(
    primary = Color(0xFF6BCB77),
    onPrimary = Color(0xFF08130A),
    secondary = Color(0xFF7FB2E5),
    background = Color(0xFF101418),
    surface = Color(0xFF161C22),
    surfaceVariant = Color(0xFF1D252E),
    onBackground = Color(0xFFE6E9EC),
    onSurface = Color(0xFFE6E9EC),
    onSurfaceVariant = Color(0xFFB8C0C8),
    error = Color(0xFFE57373),
)

@Composable
private fun LauncherScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { LauncherState(context, scope) }
    val authState by state.auth.state.collectAsState()

    // On launch, with nothing imported yet: ask for the .minecraft folder at
    // once — the primary no-PC path. Cancelling the picker keeps the manual
    // card below as the fallback.
    val minecraftPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { state.importMinecraft(it) }
        }
    LaunchedEffect(Unit) {
        if (!state.hasPrepared) minecraftPicker.launch(null)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(state)

        when (val a = authState) {
            is AuthState.SignedOut -> SignInSection(state)
            is AuthState.SignedIn -> Unit // the account card below shows it
        }

        LocalAccountsSection(state)
        if (state.hasPrepared) {
            ReadySection(state)
        }
        if (!state.hasPrepared || !state.usable) {
            ImportSection(state, onPickMinecraft = { minecraftPicker.launch(null) })
        }
        SettingsSection(state)
        DeviceSection(state)
        Footer(state)
    }
}

@Composable
private fun Header(state: LauncherState) {
    Column {
        Text(
            "HORIZON",
            fontSize = 40.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 6.sp,
        )
        Text(
            "Minecraft: Java Edition — native Android port (unofficial)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SignInSection(state: LauncherState) {
    val activity = LocalContext.current as Activity
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sign in", style = MaterialTheme.typography.titleLarge)
            if (state.isStubAuth) {
                Text(
                    text = "MSAL is not configured yet — signing in creates a local test profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Button(onClick = { state.signIn(activity) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResourceCompat(R.string.sign_in_with_microsoft))
            }
            Text(
                "No Microsoft account? Create a local account below and play.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportSection(state: LauncherState, onPickMinecraft: () -> Unit) {
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { state.importZip(it) }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { state.importTree(it) }
    }

    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Game files", style = MaterialTheme.typography.titleLarge)
            Text(
                "Point Horizon at your .minecraft folder: the pinned jars are imported and " +
                    "checked, the client JAR's resources extracted and the asset store copied — " +
                    "all on the phone, and only the pinned version's files (its libraries and " +
                    "its asset index's sounds, fonts, languages and packs), not every " +
                    "version's. The ${"server-"}${HorizonNative.pinnedVersion()}.jar (minecraft.net → " +
                    "download server jar) goes into the same folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPickMinecraft, modifier = Modifier.fillMaxWidth()) {
                Text("Select .minecraft folder")
            }
            Text(
                "…or import the portable export (the horizon-portable-export-" +
                    "${HorizonNative.pinnedVersion()} artifact from the repo's Actions, or a " +
                    "desktop export — pick the downloaded zip as-is):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        // octet-stream too: some file managers label zips that
                        // way, and the picker would then hide them.
                        zipPicker.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Export (.zip)") }
                OutlinedButton(
                    onClick = { treePicker.launch(null) },
                    modifier = Modifier.weight(1f),
                ) { Text("Export folder") }
            }
            ImportProgress(state)
        }
    }
}

@Composable
private fun LocalAccountsSection(state: LauncherState) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val active = state.activeProfile()

    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Local accounts", style = MaterialTheme.typography.titleLarge)
            Text(
                "Play without any Microsoft account. The UUID is vanilla's offline scheme " +
                    "(UUIDv3 of \"OfflinePlayer:<name>\"), so your identity is stable across reinstalls. " +
                    "Singleplayer, LAN and offline-mode servers work; online-mode servers will refuse it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.localAccounts.forEach { account ->
                val selected = state.activeLocalUuid == account.uuid
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { state.selectLocalAccount(if (selected) null else account.uuid) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            account.name + if (selected) "  ✓" else "",
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            account.uuid,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { state.deleteLocalAccount(account.uuid) }) { Text("Remove") }
                }
            }

            if (state.localAccounts.isEmpty()) {
                Text(
                    "None yet — create one to play without signing in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                singleLine = true,
                label = { Text("Player name (3–16 letters, digits or _)") },
                isError = error != null,
                supportingText = { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (state.createLocalAccount(name)) {
                        name = ""
                    } else {
                        error = "Not a valid player name (3–16 of A-Z a-z 0-9 _)."
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create local account") }

            if (active != null && active.msUserId == null) {
                Text(
                    "Next game runs as ${active.username} (local account).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ImportProgress(state: LauncherState) {
    val import = state.importState
    if (import.running) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Importing… ${import.bytes / (1 shl 20)} MB${import.currentFile.take(40).let { if (it.isBlank()) "" else " — $it" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    } else if (import.error != null) {
        Text(
            "Import failed: ${import.error}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    } else if (state.validationLog.isNotEmpty()) {
        // The on-device prepare's progress lines (it runs for minutes).
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.validationLog.takeLast(3).forEach { line ->
                Text(
                    line.take(72),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.startsWith("result:")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun ReadySection(state: LauncherState) {
    val profile = state.activeProfile()
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ready", style = MaterialTheme.typography.titleLarge)
            if (!state.usable) {
                Text(
                    "Resources incomplete — ${state.missing.size} item(s) missing: the Java-25 " +
                        "exporter tables Android cannot run. Import a portable export once " +
                        "(any ${HorizonNative.pinnedVersion()} export works) to finish; after " +
                        "that, re-prepares never need it again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (profile == null) {
                Text(
                    "Pick a Microsoft account or a local account to play.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Text(
                "Touch controls: lower-left = move (invisible stick), drag elsewhere = look, " +
                    "tap = attack, long-press = place, bottom strip = hotbar, " +
                    "second finger = inventory, top-left corner = pause.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { state.play() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(),
                enabled = dev.horizon.android.game.HorizonNative.available && profile != null && state.usable,
            ) {
                Text(
                    when {
                        !dev.horizon.android.game.HorizonNative.available -> "PLAY - native library missing"
                        !state.usable -> "PLAY — resources incomplete"
                        else -> "PLAY"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { state.validate() }) { Text("Validate") }
                OutlinedButton(onClick = { state.deleteFiles() }) { Text("Delete files") }
            }
            if (state.validationLog.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.validationLog.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (line.startsWith("ok") || line.startsWith("result:usable")) {
                                MaterialTheme.colorScheme.primary
                            } else if (line.startsWith("missing")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(state: LauncherState) {
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Text("Render distance: ${state.renderDistance} chunks", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = state.renderDistance.toFloat(),
                onValueChange = { state.renderDistance = it.toInt() },
                valueRange = 2f..16f,
                steps = 13,
            )

            Text("Look sensitivity: ×%.1f".format(state.sensitivity), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = state.sensitivity,
                onValueChange = { state.sensitivity = it },
                valueRange = 0.5f..3f,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = state.invertLook, onCheckedChange = { state.invertLook = it })
                Spacer(Modifier.width(8.dp))
                Text("Invert look")
            }

            Text("Graphics preset", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("potato", "low", "medium", "high").forEach { preset ->
                    OutlinedButton(
                        onClick = { state.graphicsPreset = preset },
                        colors = if (state.graphicsPreset == preset) {
                            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                    ) { Text(preset) }
                }
            }
        }
    }
}

@Composable
private fun DeviceSection(state: LauncherState) {
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Device", style = MaterialTheme.typography.titleLarge)
            InfoRow("Model", android.os.Build.MODEL)
            InfoRow("Android", "API ${android.os.Build.VERSION.SDK_INT}")
            InfoRow("Vulkan", vulkanFeature())
            InfoRow("GPU (native)", state.gpuInfo)
        }
    }
}

@Composable
private fun Footer(state: LauncherState) {
    val profile = state.activeProfile()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            profile?.let { p ->
                "Next game: ${p.username}" + (p.uuid?.let { "  (${it.take(8)}...)" } ?: "")
            } ?: "No account selected",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            state.nativeVersion,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.auth.state.value is AuthState.SignedIn) {
            OutlinedButton(onClick = { state.signOut() }) { Text("Sign out of Microsoft") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun vulkanFeature(): String {
    val context = LocalContext.current
    return if (context.packageManager.hasSystemFeature(PM_VULKAN_VERSION, 0x401000)) {
        "1.1+ ✓"
    } else {
        "1.1+ required but not advertised — device may be unsupported"
    }
}

private const val PM_VULKAN_VERSION = "android.hardware.vulkan.version"

/** Minimal stringResource without pulling extra imports into every call site. */
@Composable
private fun stringResourceCompat(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
