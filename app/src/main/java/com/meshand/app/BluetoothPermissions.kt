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
            // API 26–30: BLUETOOTH/BLUETOOTH_ADMIN are install-time; scanning needs fine location.
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun status(context: Context): Map<String, Boolean> = required.associateWith {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Before Android 12, BLE scans return nothing while system Location is switched off. */
    val needsLocationServices: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    fun locationServicesEnabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }
}
