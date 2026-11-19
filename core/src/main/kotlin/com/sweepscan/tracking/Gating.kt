package com.sweepscan.tracking

import kotlin.math.abs
import kotlin.math.max

/**
 * Normalised distance between two boxes, or null when outside the gate.
 * Distances are measured in units of the observed barcode's own size along each axis.
 */
internal fun gatedCost(predicted: Box, observed: Box, gate: Float): Float? {
    val (sx, sy) = barcodeScale(observed)
    val nx = abs(predicted.centerX - observed.centerX) / sx
    val ny = abs(predicted.centerY - observed.centerY) / sy
    if (nx > gate || ny > gate) return null
    return nx + ny + (1f - predicted.iou(observed))
}

/**
 * Barcodes are wide and short; give the vertical axis a floor so small jitter still matches.
 * On the phone ML Kit often returns only the scan line it read (box height 2-10 % of the width,
 * against 25 % on the emulator). Floors of 0.5 and 0.7 were replayed on the 16 Nov phone
 * recordings and changed no count, so the floor stays at 0.35.
 */
internal fun barcodeScale(box: Box): Pair<Float, Float> {
    val sx = max(box.width, 1f)
    val sy = max(box.height, 0.35f * box.width).coerceAtLeast(1f)
    return Pair(sx, sy)
}
