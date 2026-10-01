package com.meshand.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** Runtime permissions needed to scan for and connect to BLE radios on this Android version. */
object BluetoothPermissions {
    val required: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // API 24–30: BLUETOOTH/BLUETOOTH_ADMIN are install-time; scanning needs fine location.
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Requested together with [required] but not needed for BLE: the connection notification. */
    val optional: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

    fun status(context: Context): Map<String, Boolean> = required.associateWith { isGranted(context, it) }

    fun optionalStatus(context: Context): Map<String, Boolean> = optional.associateWith { isGranted(context, it) }

    private fun isGranted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Before Android 12, BLE scans return nothing while system Location is switched off. */
    val needsLocationServices: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    fun locationServicesEnabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }
}
