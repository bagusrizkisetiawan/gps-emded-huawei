package com.tigabersama.gpssurveilance

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

private const val TAG = "HuaweiSettings"

object HuaweiSettingsHelper {

    fun isHuaweiDevice(): Boolean {
        return Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) ||
                Build.MANUFACTURER.equals("HONOR", ignoreCase = true)
    }

    /**
     * Buka pengaturan Auto-launch Huawei
     */
    fun openAutoLaunchSettings(context: Context): Boolean {
        return try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened Huawei Auto-launch settings")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Auto-launch settings", e)
            false
        }
    }

    /**
     * Buka pengaturan Battery Optimization Huawei
     */
    fun openBatterySettings(context: Context): Boolean {
        return try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened Huawei Battery settings")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Battery settings", e)
            // Fallback ke settings umum
            openGeneralBatterySettings(context)
        }
    }

    /**
     * Buka App Launch Management Huawei
     */
    fun openAppLaunchSettings(context: Context): Boolean {
        return try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened Huawei App Launch settings")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open App Launch settings", e)
            false
        }
    }

    /**
     * Buka settings umum battery optimization
     */
    fun openGeneralBatterySettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open general battery settings", e)
            false
        }
    }

    /**
     * Buka App Info untuk manual settings
     */
    fun openAppInfo(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app info", e)
            false
        }
    }
}

/**
 * Dialog Composable untuk panduan setting Huawei
 */
@Composable
fun HuaweiSettingsGuideDialog(
    onDismiss: () -> Unit,
    context: Context
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "🔧 Pengaturan Huawei",
                    style = MaterialTheme.typography.titleLarge
                )

                Divider()

                Text(
                    text = "Agar GPS tetap berjalan di background, lakukan langkah berikut:",
                    style = MaterialTheme.typography.bodyMedium
                )

                SettingStep(
                    number = "1",
                    title = "Auto-launch",
                    description = "Aktifkan agar app bisa start otomatis"
                ) {
                    if (!HuaweiSettingsHelper.openAutoLaunchSettings(context)) {
                        HuaweiSettingsHelper.openAppInfo(context)
                    }
                }

                SettingStep(
                    number = "2",
                    title = "Battery Optimization",
                    description = "Pilih 'No restrictions' atau 'Don't optimize'"
                ) {
                    HuaweiSettingsHelper.openBatterySettings(context)
                }

                SettingStep(
                    number = "3",
                    title = "App Launch Management",
                    description = "Set ke 'Manual manage' dan aktifkan semua"
                ) {
                    if (!HuaweiSettingsHelper.openAppLaunchSettings(context)) {
                        HuaweiSettingsHelper.openAppInfo(context)
                    }
                }

                Divider()

                Text(
                    text = "💡 Petunjuk Manual:",
                    style = MaterialTheme.typography.titleSmall
                )

                Text(
                    text = """
                        Settings → Apps → GPS Surveillance:
                        • Battery → No restrictions
                        • Launch: Manual manage
                          ✓ Auto-launch
                          ✓ Secondary launch  
                          ✓ Run in background
                        • Notifications → Allow
                        • Location → Allow all the time
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp)
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Tutup")
                }
            }
        }
    }
}

@Composable
private fun SettingStep(
    number: String,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Text(
                text = "$number. ",
                style = MaterialTheme.typography.titleMedium
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}