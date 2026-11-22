package com.example.systemsync

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*

class LocationService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var db: AppDatabase? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { saveLocation(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try { db = AppDatabase.get(this) } catch (_: Exception) { stopSelf(); return }
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createLocationRequest()
        startForegroundCompat()
    }

    private fun createLocationRequest() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L
        ).setMinUpdateIntervalMillis(30_000L)
            .setMaxUpdateDelayMillis(120_000L)
            .build()
    }

    private fun startForegroundCompat() {
        val ch = "loc_channel"
        val nm = getSystemService(NotificationManager::class.java)

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(ch) == null) {
            nm.createNotificationChannel(NotificationChannel(ch, "Location", NotificationManager.IMPORTANCE_LOW))
        }
        val notif = NotificationCompat.Builder(this, ch)
            .setContentTitle("System").setContentText("Location active")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, 2, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(2, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            try {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest, locationCallback, Looper.getMainLooper()
                )
            } catch (_: SecurityException) {}
        }
        return START_STICKY
    }

    private fun saveLocation(loc: Location) {
        val d = db ?: return
        scope.launch {
            try {
                d.locationDao().insert(LocationEntity(loc.latitude, loc.longitude, System.currentTimeMillis()))
                d.locationDao().deleteOld(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
