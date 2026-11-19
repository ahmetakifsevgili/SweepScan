package com.sweepscan.hid

/** Something the keyboard did, written to the field/event log as a `"type":"hid"` line. */
data class HidEvent(
    val ev: String,
    val id: Long? = null,
    val len: Int? = null,
    val typed: Int? = null,
    val enter: Boolean? = null,
    val reason: String? = null,
    val typeMs: Long? = null,
    val gapMs: Long? = null,
    val detail: String? = null,
)
