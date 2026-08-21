package io.github.crossbowcraft13.openvoice.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import io.github.crossbowcraft13.openvoice.service.AssistantService
import io.github.crossbowcraft13.openvoice.ui.SetupViewModel.DownloadStatus

private val DarkColorScheme = darkColorScheme(
    primary = NeonOrange,
    onPrimary = OnNeonOrange,
    primaryContainer = NeonOrangeContainer,
    background = BackgroundDark,
    surface = SurfaceDark,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceDarkVariant,
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: SetupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = DarkColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SetupScreen(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh after returning from a system settings screen so the UI
        // reflects freshly granted permissions.
        viewModel.refreshPermissions()
    }
}

@Composable
fun SetupScreen(viewModel: SetupViewModel) {
    val context = LocalContext.current
    var started by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshPermissions() }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshPermissions() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(32.dp))
        Text(
            "OpenVoice",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Private, Local Voice Assistant",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        if (started) {
            Text(
                "Listening for \"Hey OpenVoice\"",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "OpenVoice is now running in the background. Everything stays on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            SectionTitle("Permissions")
            SetupRow(
                title = "Microphone",
                description = "Required to hear your voice. Audio is processed on-device and never recorded or uploaded.",
                done = viewModel.micGranted,
                actionLabel = "Grant",
                onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            )
            SetupRow(
                title = "Notifications",
                description = "A persistent notification keeps OpenVoice listening in the background.",
                done = viewModel.notificationGranted,
                actionLabel = "Grant",
                onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            )
            SetupRow(
                title = "Accessibility",
                description = "Lets OpenVoice tap and read other apps. Recommended for full control, not required.",
                done = viewModel.accessibilityGranted,
                actionLabel = "Open settings",
                onAction = { viewModel.openAccessibilitySettings() }
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("AI Model")
            ModelCard(viewModel)

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    AssistantService.start(context)
                    started = true
                },
                enabled = viewModel.micGranted && viewModel.notificationGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Start listening")
            }
            if (!viewModel.micGranted || !viewModel.notificationGranted) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Grant the required permissions above to start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun SetupRow(
    title: String,
    description: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (done) {
                Text(
                    "Done ✓",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun ModelCard(viewModel: SetupViewModel) {
    val model = viewModel.recommendedModel
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Recommended for your device",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))

            if (model == null) {
                Text(
                    "This device doesn't have enough free resources to run a local model comfortably. " +
                        "OpenVoice will still work for basic commands.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            Text(
                model.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${model.fileSizeMb} MB download · ${model.paramsB}B parameters",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            when (viewModel.downloadStatus) {
                DownloadStatus.IDLE -> Button(onClick = { viewModel.downloadRecommendedModel() }) {
                    Text("Download model")
                }
                DownloadStatus.DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = viewModel.downloadProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Downloading… ${(viewModel.downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DownloadStatus.DONE -> Text(
                    "Downloaded and activated ✓",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                DownloadStatus.ERROR -> {
                    Text(
                        viewModel.downloadError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.downloadRecommendedModel() }) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}
