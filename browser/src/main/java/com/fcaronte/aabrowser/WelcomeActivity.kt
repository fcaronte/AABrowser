package com.fcaronte.aabrowser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcaronte.aabrowser.settings.AppSettings

class WelcomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        AppSettings.init(applicationContext)

        if (areAllPermissionsGranted()) {
            AppSettings.setOnboardingCompleted(this, true)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContent {
            WelcomeContent()
        }
    }

    override fun onResume() {
        super.onResume()
        if (areAllPermissionsGranted()) {
            AppSettings.setOnboardingCompleted(this, true)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContent {
            WelcomeContent()
        }
    }

    @Composable
    private fun WelcomeContent() {
        var micGranted by remember { mutableStateOf(checkPermissionGranted(Manifest.permission.RECORD_AUDIO)) }
        var locGranted by remember { mutableStateOf(checkPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) }
        var notifGranted by remember { mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) checkPermissionGranted(Manifest.permission.POST_NOTIFICATIONS) else true) }

        WelcomeScreen(
            micGranted = micGranted,
            locGranted = locGranted,
            notifGranted = notifGranted,
            onMicClick = {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            },
            onLocClick = {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1002)
            },
            onNotifClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1003)
                }
            },
            onStartClicked = {
                AppSettings.setOnboardingCompleted(this, true)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            },
            onSkipClicked = {
                AppSettings.setOnboardingCompleted(this, true)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        )
    }

    private fun checkPermissionGranted(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun areAllPermissionsGranted(): Boolean {
        val mic = checkPermissionGranted(Manifest.permission.RECORD_AUDIO)
        val loc = checkPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) checkPermissionGranted(Manifest.permission.POST_NOTIFICATIONS) else true
        return mic && loc && notif
    }

}

@Composable
fun WelcomeScreen(
    micGranted: Boolean,
    locGranted: Boolean,
    notifGranted: Boolean,
    onMicClick: () -> Unit,
    onLocClick: () -> Unit,
    onNotifClick: () -> Unit,
    onStartClicked: () -> Unit,
    onSkipClicked: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.welcome_title),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.welcome_subtitle),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))

                PermissionExplanationItem(
                    icon = Icons.Default.Mic,
                    title = stringResource(R.string.perm_mic_title),
                    description = stringResource(R.string.perm_mic_desc),
                    isGranted = micGranted,
                    onClick = onMicClick
                )
                Spacer(modifier = Modifier.height(12.dp))

                PermissionExplanationItem(
                    icon = Icons.Default.LocationOn,
                    title = stringResource(R.string.perm_location_title),
                    description = stringResource(R.string.perm_location_desc),
                    isGranted = locGranted,
                    onClick = onLocClick
                )
                Spacer(modifier = Modifier.height(12.dp))

                PermissionExplanationItem(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.perm_notifications_title),
                    description = stringResource(R.string.perm_notifications_desc),
                    isGranted = notifGranted,
                    onClick = onNotifClick
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onStartClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.grant_permissions_button),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onSkipClicked,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.skip_onboarding_button),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun PermissionExplanationItem(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
            else 
                MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isGranted) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
