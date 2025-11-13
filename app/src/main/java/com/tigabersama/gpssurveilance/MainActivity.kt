package com.tigabersama.gpssurveilance

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

private const val TAG = "MainActivity"
private const val PREFS_NAME = "prefs_location_sender"
private const val KEY_SERVER = "key_server"
private const val KEY_USERNAME = "key_username"
private const val KEY_PASSWORD = "key_password"
private const val KEY_INTERVAL_SECONDS = "key_interval_seconds"
private const val KEY_AUTH_TOKEN = "key_auth_token"
private const val KEY_AUTH_NAME = "key_auth_name"
private const val DEFAULT_API = "http://192.168.2.235:3000/v1"

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Permission states
    private var hasFineLocation = false
    private var hasCoarseLocation = false
    private var hasBackgroundLocation = false
    private var hasNotificationPermission = false
    private var isBatteryOptimizationDisabled = false

    // Permission launcher untuk foreground location + notification
    private val foregroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasFineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        hasCoarseLocation = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else {
            true
        }

        Log.d(TAG, "Foreground permissions: Fine=$hasFineLocation, Coarse=$hasCoarseLocation, Notification=$hasNotificationPermission")

        if (hasFineLocation && hasCoarseLocation) {
            // Request background location setelah foreground granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation) {
                requestBackgroundLocation()
            }
        }
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    // Launcher khusus untuk background location
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasBackgroundLocation = isGranted
        Log.d(TAG, "Background location permission: $isGranted")
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityCompat.requestPermissions(this, requiredPermissions, 1001)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Cek permission yang sudah ada
        checkExistingPermissions()

        // Request permission jika belum ada
        if (!hasAllRequiredPermissions()) {
            requestForegroundPermissions()
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedServer = prefs.getString(KEY_SERVER, DEFAULT_API) ?: DEFAULT_API
        val savedUser = prefs.getString(KEY_USERNAME, "") ?: ""
        val savedPass = prefs.getString(KEY_PASSWORD, "") ?: ""
        val savedInterval = prefs.getLong(KEY_INTERVAL_SECONDS, 60L)
        val savedToken = prefs.getString(KEY_AUTH_TOKEN, "")
        val savedName = prefs.getString(KEY_AUTH_NAME, "")
        val isLoggedIn = !savedToken.isNullOrEmpty()

        setContent {
            val context = LocalContext.current
            var server by remember { mutableStateOf(savedServer) }
            var username by remember { mutableStateOf(savedUser) }
            var password by remember { mutableStateOf(savedPass) }
            var token by remember { mutableStateOf(savedToken ?: "") }
            var name by remember { mutableStateOf(savedName ?: "") }
            var interval by remember { mutableStateOf(savedInterval.toString()) }
            var message by remember { mutableStateOf("") }
            var isRunning by remember { mutableStateOf(isLoggedIn) }
            var isLoading by remember { mutableStateOf(false) }
            var batteryOptimizationStatus by remember { mutableStateOf(checkBatteryOptimization()) }

            fun savePrefs(newToken: String? = null, name: String? = null) {
                val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                edit.putString(KEY_SERVER, server.trim())
                edit.putString(KEY_USERNAME, username)
                edit.putString(KEY_PASSWORD, password)
                edit.putLong(KEY_INTERVAL_SECONDS, interval.toLongOrNull() ?: 60L)
                if (newToken != null) {
                    edit.putString(KEY_AUTH_TOKEN, newToken)
                }
                if (name != null) {
                    edit.putString(KEY_AUTH_NAME, name)
                }
                edit.apply()
            }

            fun clearPrefs() {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
            }

            fun validateInputs(): Boolean {
                val trimmed = server.trim()
                // Cek format URL atau IP:PORT
                val okAsUrl = Patterns.WEB_URL.matcher(trimmed).matches()
                val okAsIp = trimmed.matches(Regex("""https?://\d{1,3}(\.\d{1,3}){3}(:\d+)?.*"""))

                if (!okAsUrl && !okAsIp) {
                    message = "Server tidak valid (contoh: http://192.168.1.1:3000/v1)"
                    return false
                }
                if (username.isBlank() || password.isBlank()) {
                    message = "Isi username dan password"
                    return false
                }
                val intervalVal = interval.toLongOrNull()
                if (intervalVal == null || intervalVal <= 0) {
                    message = "Interval harus angka > 0"
                    return false
                }
                return true
            }


            var resultText by remember { mutableStateOf("Tekan tombol untuk ambil IMEI / fallback ID") }

            // launcher untuk meminta permission READ_PHONE_STATE
            val requestPermissionLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) @androidx.annotation.RequiresPermission(
                    "android.permission.READ_PRIVILEGED_PHONE_STATE"
                ) { granted ->
                    if (granted) {
                        resultText = getImeiOrFallback(context)
                    } else {
                        resultText = "Permission ditolak — gunakan fallback ID:\n${getAndroidId(context)}"
                    }
                }

            fun startService() {
                val intent = Intent(context, LocationForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "Service started")
            }

            fun stopService() {
                val intent = Intent(context, LocationForegroundService::class.java)
                context.stopService(intent)
                Log.d(TAG, "Service stopped")
            }

            fun loginUser() {
                if (!validateInputs()) return

                // Cek permission sebelum login
                if (!hasAllRequiredPermissions()) {
                    message = "Permission belum lengkap, mohon izinkan semua permission"
                    requestForegroundPermissions()
                    return
                }

                isLoading = true
                message = "Sedang login..."

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val baseUrl = server.trim()
                        val fixedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

                        Log.d(TAG, "Attempting login to: $fixedUrl")

                        val retrofit = Retrofit.Builder()
                            .baseUrl(fixedUrl)
                            .addConverterFactory(GsonConverterFactory.create())
                            .build()

                        val api = retrofit.create(ApiService::class.java)
                        val response = api.login(LoginRequest(username, password))

                        withContext(Dispatchers.Main) {
                            if (response.isSuccessful && response.body() != null) {
                                val accessToken = response.body()!!.data.accessToken
                                val accessName = response.body()!!.data.name
                                token = accessToken
                                name = accessName
                                savePrefs(newToken = accessToken, name = name)
                                startService()

                                isRunning = true
                                message = "Login berhasil & service berjalan"
                                Log.d(TAG, "Login successful, token: ${accessToken.take(10)}...")
                            } else {
                                message = "Login gagal (${response.code()}): ${response.message()}"
                                Log.e(TAG, "Login failed: ${response.code()} - ${response.errorBody()?.string()}")
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            message = "Error: ${e.message}"
                            Log.e(TAG, "Login error", e)
                        }
                    } finally {
                        withContext(Dispatchers.Main) {
                            isLoading = false
                        }
                    }
                }
            }

            fun sendLocationNow() {
                if (token.isBlank()) {
                    message = "Belum login! Login dulu."
                    return
                }

                if (!hasFineLocation && !hasCoarseLocation) {
                    message = "Permission lokasi belum diizinkan"
                    requestForegroundPermissions()
                    return
                }

                message = "Mengambil lokasi..."

                try {
                    if (isHuaweiDevice()) {
                        // ✅ Huawei Location Kit
                        try {
                            val huaweiClient = com.huawei.hms.location.LocationServices.getFusedLocationProviderClient(context)
                            huaweiClient.lastLocation
                                .addOnSuccessListener { location ->
                                    if (location != null) {
                                        Log.d("Location", "Huawei lat=${location.latitude}, lng=${location.longitude}")

                                        val payload = LocationPayload(
                                            latitude = location.latitude,
                                            longitude = location.longitude,
                                            timestamp = System.currentTimeMillis().toString()
                                        )

                                        CoroutineScope(Dispatchers.IO).launch {
                                            try {
                                                val baseUrl = server.trim()
                                                val fixedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

                                                val retrofit = Retrofit.Builder()
                                                    .baseUrl(fixedUrl)
                                                    .addConverterFactory(GsonConverterFactory.create())
                                                    .build()

                                                val api = retrofit.create(ApiService::class.java)
                                                val response = api.sendLocation("Bearer $token", payload)

                                                withContext(Dispatchers.Main) {
                                                    if (response.isSuccessful) {
                                                        message = "✓ Lokasi (Huawei) berhasil dikirim"
                                                        Log.d(TAG, "Huawei location sent successfully")
                                                    } else {
                                                        message = "✗ Gagal mengirim (Huawei): ${response.code()}"
                                                        Log.e(TAG, "Huawei send failed: ${response.errorBody()?.string()}")
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    message = "✗ Error Huawei: ${e.message}"
                                                    Log.e(TAG, "Huawei send error", e)
                                                }
                                            }
                                        }
                                    } else {
                                        message = "Lokasi Huawei tidak tersedia. Pastikan GPS aktif."
                                        Log.w(TAG, "Huawei location is null")
                                    }
                                }
                                .addOnFailureListener { e ->
                                    message = "Gagal mendapat lokasi Huawei: ${e.message}"
                                    Log.e(TAG, "Huawei get location failed", e)
                                }
                        } catch (e: Exception) {
                            message = "Huawei Location Kit error: ${e.message}"
                            Log.e(TAG, "Huawei SDK error", e)
                        }
                        return
                    }

                    // ✅ Default (Google)
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            val payload = LocationPayload(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                timestamp = System.currentTimeMillis().toString()
                            )

                            Log.d(TAG, "Google Location obtained: ${location.latitude}, ${location.longitude}")

                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val baseUrl = server.trim()
                                    val fixedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

                                    val retrofit = Retrofit.Builder()
                                        .baseUrl(fixedUrl)
                                        .addConverterFactory(GsonConverterFactory.create())
                                        .build()

                                    val api = retrofit.create(ApiService::class.java)
                                    val response = api.sendLocation("Bearer $token", payload)

                                    withContext(Dispatchers.Main) {
                                        if (response.isSuccessful) {
                                            message = "✓ Lokasi (Google) berhasil dikirim"
                                            Log.d(TAG, "Google location sent successfully")
                                        } else {
                                            message = "✗ Gagal mengirim (Google): ${response.code()}"
                                            Log.e(TAG, "Google send failed: ${response.errorBody()?.string()}")
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        message = "✗ Error: ${e.message}"
                                        Log.e(TAG, "Send error", e)
                                    }
                                }
                            }
                        } else {
                            message = "Lokasi tidak tersedia. Pastikan GPS aktif."
                            Log.w(TAG, "Location is null")
                        }
                    }.addOnFailureListener { e ->
                        message = "Gagal mendapat lokasi: ${e.message}"
                        Log.e(TAG, "Failed to get location", e)
                    }
                } catch (e: SecurityException) {
                    message = "Permission ditolak"
                    Log.e(TAG, "SecurityException", e)
                }
            }

            fun requestBatteryOptimization() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                            message = "Izinkan app berjalan di background tanpa batasan"
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to open battery optimization settings", e)
                            message = "Gagal membuka pengaturan: ${e.message}"
                        }
                    } else {
                        batteryOptimizationStatus = "✓ Sudah Diaktifkan"
                        message = "Background service sudah optimal"
                    }
                }
            }

            // State untuk mengontrol visibilitas dialog
            var showHuaweiDialog by remember { mutableStateOf(false) }

            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("GPS Surveillance", style = MaterialTheme.typography.titleLarge)
                    if (name.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Card(
                        ) {
                            Text(
                                text = name,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                    }
                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("http://192.168.1.1:3000/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRunning
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Code") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRunning
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRunning
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it.filter { c -> c.isDigit() } },
                        label = { Text("Interval (detik)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRunning
                    )

                    Spacer(Modifier.height(16.dp))

                    // Tombol Aktifkan Background Service
                    Button(
                        onClick = {
                            requestBatteryOptimization()
                            batteryOptimizationStatus = checkBatteryOptimization()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (batteryOptimizationStatus.contains("✓"))
                                MaterialTheme.colorScheme.secondary
                            else
                                MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text("🔋 Aktifkan Background Service - $batteryOptimizationStatus")
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (!isRunning) {
                                loginUser()
                            } else {
                                stopService()
                                clearPrefs()
                                token = ""
                                message = "Service berhenti & logout berhasil"
                                isRunning = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        Text(
                            if (isRunning) "Berhenti & Logout"
                            else if (isLoading) "Loading..."
                            else "Login & Mulai Service"
                        )
                    }

                    Spacer(Modifier.height(8.dp))

//                    OutlinedButton(
//                        onClick = { showHuaweiDialog = true },
//                        modifier = Modifier.fillMaxWidth()
//                    ) {
//                        Text("🔧 Buka Panduan Pengaturan Huawei")
//                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                            val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                            if (!gpsEnabled) {
                                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            } else {
                                message = "GPS sudah aktif"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📍 Cek/Aktifkan GPS")
                    }
                    Spacer(Modifier.height(8.dp))

//                    Button(onClick = {
//                        // cek permission
//                        val hasPerm = ContextCompat.checkSelfPermission(
//                            context,
//                            Manifest.permission.READ_PHONE_STATE
//                        ) == PermissionChecker.PERMISSION_GRANTED
//
//                        if (hasPerm) {
//                            resultText = getDeviceId(context)
//                        } else {
//                            // request permission
//                            requestPermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
//                        }
//                    }) {
//                        Text("Ambil IMEI")
//                    }
                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { sendLocationNow() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isRunning
                    ) {
                        Text("📤 Kirim Lokasi Sekarang")
                    }

                    Spacer(Modifier.height(12.dp))

                    if (message.isNotEmpty()) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (message.contains("✓") || message.contains("berhasil"))
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }

                    if (token.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Token: ${token.take(15)}...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(text = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID))

                    // Tampilkan dialog jika showHuaweiDialog == true
                    if (showHuaweiDialog) {
                        HuaweiSettingsGuideDialog(
                            onDismiss = { showHuaweiDialog = false },
                            context = context
                        )
                    }
                }
            }
        }

        ignoreBatteryOptimization()
    }

    private fun checkBatteryOptimization(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                "✓ Sudah Diaktifkan"
            } else {
                "⚠ Belum Diaktifkan"
            }
        } else {
            "✓ Tidak Diperlukan"
        }
    }

    private fun checkExistingPermissions() {
        hasFineLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        hasCoarseLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        isBatteryOptimizationDisabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }

        Log.d(TAG, "Permissions: Fine=$hasFineLocation, Coarse=$hasCoarseLocation, Background=$hasBackgroundLocation, Notification=$hasNotificationPermission, BatteryOpt=$isBatteryOptimizationDisabled")
    }

    private fun hasAllRequiredPermissions(): Boolean {
        return hasFineLocation && hasCoarseLocation && hasNotificationPermission
    }

    private fun requestForegroundPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        Log.d(TAG, "Requesting foreground permissions")
        foregroundPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "Requesting background location permission")
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun ignoreBatteryOptimization() {
        val packageName = packageName
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    /**
    * Coba ambil IMEI kalau device mengizinkan. Kalau tidak tersedia / Android >= Q -> kembalikan fallback ANDROID_ID.
    */
    @RequiresPermission("android.permission.READ_PRIVILEGED_PHONE_STATE")
    fun getImeiOrFallback(context: Context): String {
        try {
            // Pada Android 10+ akses IMEI dibatasi untuk apps biasa => kemungkinan null / SecurityException
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                // kalau API >= 26 gunakan imei(), kalau lebih rendah pakai deviceId()
                val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        tm.imei // requires READ_PHONE_STATE
                    } catch (e: SecurityException) {
                        null
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    try {
                        @Suppress("DEPRECATION")
                        tm.deviceId
                    } catch (e: SecurityException) {
                        null
                    } catch (e: Exception) {
                        null
                    }
                }
                if (!imei.isNullOrBlank()) {
                    return "IMEI: $imei"
                }
            }
        } catch (e: Exception) {
            // ignore, nanti fallback
        }

        // fallback: Android ID (persisten per device+user, bukan IMEI)
        val androidId = getAndroidId(context)
        return "IMEI tidak tersedia pada device ini (terbatas oleh OS). Fallback ANDROID_ID:\n$androidId"
    }

    fun getAndroidId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    fun getDeviceId(context: Context): String {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        // Coba ambil IMEI (hanya untuk Android < 10)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    telephonyManager.imei
                } else {
                    @Suppress("DEPRECATION")
                    telephonyManager.deviceId
                }
                if (!imei.isNullOrBlank()) return "IMEI: $imei"
            } catch (e: Exception) {
                // IMEI tidak bisa diakses, lanjut ke fallback
            }
        }

        // Fallback: ANDROID_ID
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return "IMEI tidak tersedia pada device ini (terbatas oleh OS). Fallback ANDROID_ID:\n$androidId"
    }
}



