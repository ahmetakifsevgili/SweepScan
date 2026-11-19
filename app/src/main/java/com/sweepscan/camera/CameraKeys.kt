package com.sweepscan.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build

/**
 * Camera2 keys newer than the SDK constants we can rely on (AE priority mode is Android 16 /
 * API 36). Built by name with the public Key(String, Class) constructors (API 29+), so the app
 * still runs on older phones: on those, or when the device doesn't know the key, values are null.
 */
object CameraKeys {
    /** CONTROL_AE_PRIORITY_MODE values. */
    const val AE_PRIORITY_OFF = 0
    const val AE_PRIORITY_SENSITIVITY = 1
    const val AE_PRIORITY_EXPOSURE_TIME = 2

    val availableAePriorityModes: CameraCharacteristics.Key<IntArray>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            CameraCharacteristics.Key("android.control.aeAvailablePriorityModes", IntArray::class.java)
        } else {
            null
        }

    val requestAePriority: CaptureRequest.Key<Int>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            CaptureRequest.Key("android.control.aePriorityMode", Int::class.javaObjectType)
        } else {
            null
        }

    val resultAePriority: CaptureResult.Key<Int>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            CaptureResult.Key("android.control.aePriorityMode", Int::class.javaObjectType)
        } else {
            null
        }
}
