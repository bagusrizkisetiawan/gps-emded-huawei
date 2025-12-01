package com.tigabersama.gpssurveilance

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.*
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.huawei.hms.location.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.PI

private const val TAG = "LocationFgService"
private const val PREFS_NAME = "prefs_location_sender"
private const val KEY_SERVER = "key_server"
private const val KEY_AUTH_TOKEN = "key_auth_token"
private const val KEY_INTERVAL_SECONDS = "key_interval_seconds"
private const val DEFAULT_API = "http://192.168.100.155:3000/v1"

class LocationForegroundService : Service(), SensorEventListener {

    private val channelId = "location_channel"
    private val channelName = "Location Service"

    private var huaweiClient: FusedLocationProviderClient? = null
    private var huaweiLocationCallback: LocationCallback? = null

    private var googleClient: com.google.android.gms.location.FusedLocationProviderClient? = null
    private var googleCallback: com.google.android.gms.location.LocationCallback? = null

    private var locationManager: android.location.LocationManager? = null


    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var schedulerJob: Job? = null

    private var apiService: ApiService? = null
    private var token: String? = null
    private var intervalSeconds: Long = 60L
    private var serverUrl: String = DEFAULT_API

    private var lastSentAt = 0L


    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var magSensor: Sensor? = null

    // temp storage for fallback method
    private val accelValues = FloatArray(3)
    private val magValues = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    // Compose-observable gyro state (degrees)
    private val _yawDeg = mutableStateOf(0f)
    private val _pitchDeg = mutableStateOf(0f)
    private val _rollDeg = mutableStateOf(0f)

    val yawDeg: State<Float> get() = _yawDeg
    val pitchDeg: State<Float> get() = _pitchDeg
    val rollDeg: State<Float> get() = _rollDeg

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")

        sensorManager = getSystemService(Activity.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // Daftarkan listener sensor agar bisa membaca orientasi
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        magSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }


        createNotificationChannel()
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GPSSurveillance::LocationWakeLock"
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 jam
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun isHuaweiDevice(): Boolean {
        return try {
            Class.forName("com.huawei.hms.location.LocationServices")
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            Log.d(TAG, "WakeLock released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        serverUrl = prefs.getString(KEY_SERVER, DEFAULT_API) ?: DEFAULT_API
        token = prefs.getString(KEY_AUTH_TOKEN, "")
        intervalSeconds = prefs.getLong(KEY_INTERVAL_SECONDS, 60L)

        Log.d(TAG, "onStartCommand() - Server=$serverUrl, token=${token?.take(10)}..., interval=$intervalSeconds detik")

        apiService = buildApiService(serverUrl)

        startForeground(1, buildNotification())
        if (isHuaweiDevice()) {
            startHuaweiLocationUpdates()
        } else {
            startGoogleOrGPSFallback()
        }
        startManualScheduler()

        return START_STICKY
    }



    private fun buildApiService(baseUrl: String): ApiService {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        val fixedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(fixedBaseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Monitoring lokasi GPS Huawei"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("🛰️ Huawei GPS Surveillance Active")
            .setContentText("Mengirim lokasi setiap $intervalSeconds detik")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startHuaweiLocationUpdates() {
        try {
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                updateNotification("✗ Izin lokasi belum diberikan")
                return
            }

            huaweiClient = LocationServices.getFusedLocationProviderClient(this)

            huaweiLocationCallback?.let {
                huaweiClient?.removeLocationUpdates(it)
            }

            val intervalMillis = maxOf(10_000L, intervalSeconds * 1000L)
            val request = LocationRequest.create().apply {
                interval = intervalMillis
                fastestInterval = intervalMillis / 2
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                smallestDisplacement = 0f // biar tetap dapat walau diam
            }

            huaweiLocationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult?) {
                    result?.lastLocation?.let { location ->
                        if (System.currentTimeMillis() - lastSentAt >= intervalSeconds * 1000) {
                            Log.d(TAG, "Huawei callback location: lat=${location.latitude}, lon=${location.longitude}")
                            sendLocationToServer(location)
                            lastSentAt = System.currentTimeMillis()
                        }
                    }
                }
            }

            huaweiClient?.requestLocationUpdates(request, huaweiLocationCallback!!, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.e(TAG, "Error Huawei location: ${e.message}", e)
        }
    }
    @Suppress("MissingPermission")
    private fun startGoogleOrGPSFallback() {
        googleClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)

        val request = com.google.android.gms.location.LocationRequest.create().apply {
            interval = intervalSeconds * 1000
            fastestInterval = intervalSeconds * 500
            priority = com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        googleCallback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val loc = result.lastLocation ?: return
                Log.d(TAG, "Google location → ${loc.latitude}, ${loc.longitude}")
                sendLocationToServer(loc)
            }
        }

        // Request update
        googleClient?.requestLocationUpdates(request, googleCallback!!, Looper.getMainLooper())

        // Kalau gagal → fallback ke GPS murni
        googleClient?.lastLocation?.addOnFailureListener {
            Log.e(TAG, "Google client gagal, fallback ke LocationManager GPS")
            startLocationManagerFallback()
        }

        updateNotification("GPS aktif (Google Provider)")
    }

    @Suppress("MissingPermission")
    private fun startLocationManagerFallback() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        if (!locationManager!!.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            updateNotification("GPS mati — hidupkan GPS")
            return
        }

        locationManager!!.requestLocationUpdates(
            android.location.LocationManager.GPS_PROVIDER,
            intervalSeconds * 1000,
            0f
        ) { loc ->
            Log.d(TAG, "LocationManager → ${loc.latitude}, ${loc.longitude}")
            sendLocationToServer(loc)
        }

        updateNotification("GPS aktif (LocationManager)")
    }



    /** Scheduler manual agar tetap kirim meskipun device diam */
    private fun startManualScheduler() {
        schedulerJob?.cancel()
        schedulerJob = scope.launch {
            while (isActive) {
                delay(intervalSeconds * 1000)
                try {
                    val loc = suspendCoroutine<Location?> { cont ->
                        huaweiClient?.lastLocation
                            ?.addOnSuccessListener { cont.resume(it) }
                            ?.addOnFailureListener { e ->
                                Log.e(TAG, "Gagal ambil lastLocation: ${e.message}")
                                cont.resume(null)
                            }
                    }

                    if (loc != null) {
                        Log.d(TAG, "Manual scheduler tick — kirim lokasi: lat=${loc.latitude}, lon=${loc.longitude}")
                        sendLocationToServer(loc)
                    } else {
                        Log.w(TAG, "Manual scheduler: lastLocation null")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Scheduler error: ${e.message}")
                }
            }
        }
    }

    private fun stopHuaweiLocationUpdates() {
        try {
            schedulerJob?.cancel()
            schedulerJob = null
            huaweiLocationCallback?.let {
                huaweiClient?.removeLocationUpdates(it)
            }
            Log.d(TAG, "Huawei updates stopped.")
            huaweiLocationCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stop Huawei updates", e)
        }
    }

    private fun sendLocationToServer(location: Location) {

        val payload = LocationPayload(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = System.currentTimeMillis().toString(),
            yaw = yawDeg.value,
            pitch = pitchDeg.value,
            roll = rollDeg.value
        )

        scope.launch {
            try {
                val response = apiService?.sendLocation("Bearer $token", payload)
                withContext(Dispatchers.Main) {
                    if (response?.isSuccessful == true) {
                        Log.d(TAG, "✓ Lokasi terkirim ke server.")
                    } else {
                        Log.e(TAG, "✗ Gagal kirim: ${response?.code()}")
                        fallbackSendWithOkHttp(payload)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendLocationToServer error: ${e.message}", e)
                fallbackSendWithOkHttp(payload)
            }
        }
    }

    private fun fallbackSendWithOkHttp(payload: LocationPayload) {
        try {
            val json =
                """{"latitude":${payload.latitude},"longitude":${payload.longitude},"timestamp":"${payload.timestamp}","yaw":${payload.yaw},"pitch":${payload.pitch},"roll":${payload.roll}}"""
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = okhttp3.RequestBody.create(mediaType, json)
            val request = okhttp3.Request.Builder()
                .url(if (serverUrl.endsWith("/")) serverUrl + "gps" else serverUrl + "/gps")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()

            val resp = client.newCall(request).execute()
            if (resp.isSuccessful) {
                Log.d(TAG, "✓ OkHttp fallback: Lokasi terkirim.")
            } else {
                Log.e(TAG, "✗ OkHttp gagal: code=${resp.code}")
            }
            resp.close()
        } catch (e: Exception) {
            Log.e(TAG, "✗ Fallback exception: ${e.message}", e)
        }
    }

    private fun updateNotification(message: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = NotificationCompat.Builder(this, channelId)
                .setContentTitle("🛰️ Huawei GPS Surveillance")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build()
            nm.notify(1, notif)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHuaweiLocationUpdates()
        releaseWakeLock()
        serviceJob.cancel()
        Log.d(TAG, "Service destroyed.")

        try {
            sensorManager.unregisterListener(this)
            Log.d(TAG, "Sensor listener unregistered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering sensor", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved() - Restarting Huawei service")
        val restartIntent = Intent(applicationContext, this::class.java)
        val restartPending = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            else PendingIntent.FLAG_ONE_SHOT
        )
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            restartPending
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)

                val azimuthRad = orientation[0]
                val pitchRad = orientation[1]
                val rollRad = orientation[2]

                val azimuthDeg = normalizeDegree(radToDeg(azimuthRad))
                val pitchDeg = radToDeg(pitchRad)
                val rollDeg = radToDeg(rollRad)

                // ⛔ Jangan update kalau 0.0 (sensor belum stabil)
                if (azimuthDeg != 0.0f && pitchDeg != 0.0f && rollDeg != 0.0f) {
                    _yawDeg.value = azimuthDeg
                    _pitchDeg.value = pitchDeg
                    _rollDeg.value = rollDeg
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                // store for fallback
                System.arraycopy(event.values, 0, accelValues, 0, 3)
                hasAccel = true
                if (hasMag) computeYawFromAccelMag()
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magValues, 0, 3)
                hasMag = true
                if (hasAccel) computeYawFromAccelMag()
            }
        }
    }

    private fun computeYawFromAccelMag() {
        val rotationMatrix = FloatArray(9)
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, accelValues, magValues)
        if (success) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)

            val azimuthDeg = radToDeg(orientation[0])
            val pitchDeg = radToDeg(orientation[1])
            val rollDeg = radToDeg(orientation[2])

            // Skip kalau 0.0 semua
            if (azimuthDeg != 0.0f && pitchDeg != 0.0f && rollDeg != 0.0f) {
                _yawDeg.value = normalizeDegree(azimuthDeg)
                _pitchDeg.value = pitchDeg
                _rollDeg.value = rollDeg
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // optionally handle accuracy changes
    }

    private fun radToDeg(rad: Float): Float = (rad * 180f / PI.toFloat())

    private fun normalizeDegree(angle: Float): Float {
        var a = angle % 360f
        if (a < 0) a += 360f
        return a
    }

}
