package com.voxpen.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.voxpen.app.R
import com.voxpen.app.ui.settings.SettingsUiState
import com.voxpen.app.ui.settings.SettingsViewModel

object HomeScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenContent(
    onNavigateToSettings: () -> Unit,
    onNavigateToTranscription: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val isKeyboardEnabled = rememberKeyboardEnabled()
    var hasMicPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val micPermLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> hasMicPerm = granted }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        HomeBody(
            modifier = Modifier.padding(innerPadding),
            state = state,
            isKeyboardEnabled = isKeyboardEnabled,
            hasMicPerm = hasMicPerm,
            onEnableKeyboard = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToTranscription = onNavigateToTranscription,
            onGrantMicPermission = { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )
    }
}

@Composable
private fun rememberKeyboardEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(false) }

    fun isKeyboardEnabled(): Boolean =
        try {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm.enabledInputMethodList.any { it.packageName == context.packageName }
        } catch (_: Exception) {
            false
        }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = isKeyboardEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        enabled = isKeyboardEnabled()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return enabled
}

@Suppress("LongParameterList")
@Composable
private fun HomeBody(
    modifier: Modifier,
    state: SettingsUiState,
    isKeyboardEnabled: Boolean,
    hasMicPerm: Boolean,
    onEnableKeyboard: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTranscription: () -> Unit,
    onGrantMicPermission: () -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        WelcomeHeader()
        Spacer(Modifier.height(24.dp))
        UnlimitedUsageCard()
        Spacer(Modifier.height(16.dp))
        SetupChecklist(
            isKeyboardEnabled = isKeyboardEnabled,
            isApiKeyConfigured = state.isApiKeyConfigured,
            hasMicPerm = hasMicPerm,
            onEnableKeyboard = onEnableKeyboard,
            onConfigureApiKey = onNavigateToSettings,
            onGrantMicPermission = onGrantMicPermission,
        )
        Spacer(Modifier.height(24.dp))
        HomeActions(onNavigateToSettings, onNavigateToTranscription)
    }
}

@Composable
private fun UnlimitedUsageCard() {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.usage_unlimited_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.usage_unlimited_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun WelcomeHeader() {
    Text(
        stringResource(R.string.welcome_message),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        stringResource(R.string.home_tagline),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SetupChecklist(
    isKeyboardEnabled: Boolean,
    isApiKeyConfigured: Boolean,
    hasMicPerm: Boolean,
    onEnableKeyboard: () -> Unit,
    onConfigureApiKey: () -> Unit,
    onGrantMicPermission: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.home_setup_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            ChecklistItem(
                done = isKeyboardEnabled,
                label = stringResource(R.string.setup_keyboard, ""),
                status =
                    if (isKeyboardEnabled) {
                        stringResource(R.string.status_enabled)
                    } else {
                        stringResource(R.string.status_disabled)
                    },
                onClick = onEnableKeyboard,
            )
            ChecklistItem(
                done = isApiKeyConfigured,
                label = stringResource(R.string.setup_api_key, ""),
                status =
                    if (isApiKeyConfigured) {
                        stringResource(R.string.status_configured)
                    } else {
                        stringResource(R.string.status_not_configured)
                    },
                onClick = onConfigureApiKey,
            )
            ChecklistItem(
                done = hasMicPerm,
                label = stringResource(R.string.setup_permission, ""),
                status =
                    if (hasMicPerm) {
                        stringResource(R.string.status_granted)
                    } else {
                        stringResource(R.string.status_denied)
                    },
                onClick = onGrantMicPermission,
            )
        }
    }
}

@Composable
private fun ChecklistItem(
    done: Boolean,
    label: String,
    status: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (done) "\u2705" else "\u26A0\uFE0F",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label.trimEnd(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (done) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
        )
    }
}

@Composable
private fun HomeActions(
    onNavigateToSettings: () -> Unit,
    onNavigateToTranscription: () -> Unit,
) {
    FilledTonalButton(onClick = onNavigateToTranscription, Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.transcribe_audio))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onNavigateToSettings, Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.open_settings))
    }
}
