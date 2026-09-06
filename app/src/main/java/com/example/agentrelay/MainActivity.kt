package com.example.agentrelay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.agentrelay.notifications.SESSION_NAVIGATION_KEY_EXTRA
import com.example.agentrelay.notifications.SessionNotificationPermissionState
import com.example.agentrelay.notifications.resolveSessionNotificationPermissionState
import com.example.agentrelay.notifications.sessionNotificationNavigationKey
import com.example.agentrelay.theme.AgentRelayTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationNavigationKey = MutableStateFlow<String?>(null)
    private val pairingHandoffState = MutableStateFlow<PairingHandoffUiState?>(null)
    private val pairingEnrollment by lazy { AndroidPairingAppLinkEnrollment(this) }
    private var pairingIntentJob: Job? = null
    private val notificationPermissionState =
        MutableStateFlow(SessionNotificationPermissionState.HIDDEN)
    private val notificationPermissionPreferences by lazy {
        getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshNotificationPermissionState()
        lifecycleScope.launch {
            (application as AgentRelayApplication).graph.retryNotificationsIfInitialized()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptNotificationNavigation(intent)
        acceptPairingAppLink(intent)
        refreshNotificationPermissionState()

        enableEdgeToEdge()
        setContent {
            val navigationKey by notificationNavigationKey.collectAsStateWithLifecycle()
            val permissionState by notificationPermissionState.collectAsStateWithLifecycle()
            AgentRelayTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainNavigation(
                        notificationNavigationKey = navigationKey,
                        onNotificationNavigationConsumed = ::consumeNotificationNavigation,
                        notificationPermissionState = permissionState,
                        onRequestNotificationPermission = ::requestNotificationPermission,
                        onOpenNotificationSettings = ::openNotificationSettings,
                        pairingHandoffState = pairingHandoffState,
                        onPairingApproved = ::approvePairing,
                        onPairingDismissed = ::dismissPairing,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptNotificationNavigation(intent)
        acceptPairingAppLink(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationPermissionState()
        if (notificationPermissionGranted()) {
            lifecycleScope.launch {
                (application as AgentRelayApplication).graph.retryNotificationsIfInitialized()
            }
        }
    }

    override fun onDestroy() {
        pairingIntentJob?.cancel()
        super.onDestroy()
    }

    private fun acceptNotificationNavigation(intent: Intent) {
        intent.sessionNotificationNavigationKey(packageName)?.let { navigationKey ->
            notificationNavigationKey.value = navigationKey
        }
    }

    private fun acceptPairingAppLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW || intent.dataString == null) {
            return
        }
        val rawLink = intent.dataString ?: return
        pairingIntentJob?.cancel()
        pairingHandoffState.value = null
        pairingIntentJob = lifecycleScope.launch {
            val verified = pairingEnrollment.resolveAndVerifyLink(rawLink, System.currentTimeMillis())
            pairingHandoffState.value = verified?.let(PairingHandoffUiState::Review)
                ?: PairingHandoffUiState.Rejected
        }
    }

    private fun approvePairing(verified: VerifiedPairingAppLink) {
        pairingIntentJob?.cancel()
        pairingIntentJob = lifecycleScope.launch {
            if (pairingEnrollment.enroll(verified)) {
                pairingHandoffState.value = null
                intent.action = null
                intent.data = null
            } else {
                pairingHandoffState.value = PairingHandoffUiState.Rejected
            }
        }
    }

    private fun dismissPairing() {
        pairingIntentJob?.cancel()
        pairingHandoffState.value = null
        intent.action = null
        intent.data = null
    }

    private fun consumeNotificationNavigation(navigationKey: String) {
        if (!notificationNavigationKey.compareAndSet(navigationKey, null)) {
            return
        }
        if (intent.sessionNotificationNavigationKey(packageName) == navigationKey) {
            intent.removeExtra(SESSION_NAVIGATION_KEY_EXTRA)
            intent.action = null
            intent.data = null
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        notificationPermissionPreferences.edit {
            putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true)
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    private fun refreshNotificationPermissionState() {
        val runtimePermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        notificationPermissionState.value = resolveSessionNotificationPermissionState(
            runtimePermissionRequired = runtimePermissionRequired,
            permissionGranted = notificationPermissionGranted(),
            requestedBefore = notificationPermissionPreferences.getBoolean(
                NOTIFICATION_PERMISSION_REQUESTED,
                false,
            ),
            shouldShowRationale = runtimePermissionRequired &&
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    private fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val NOTIFICATION_PERMISSION_PREFERENCES = "notification-permission"
        const val NOTIFICATION_PERMISSION_REQUESTED = "requested"
    }
}
