package com.sweepscan.hid

/**
 * USB HID boot-keyboard report format, sent over Bluetooth.
 *
 * Only digits, Enter and Backspace are needed. Digits use the top-row usages, which type digits
 * on both Turkish Q and Turkish F layouts without Shift, and do not depend on Num Lock.
 */
object KeyboardReport {
    const val REPORT_ID = 1
    const val SIZE = 8

    const val ENTER: Byte = 0x28

    val DESCRIPTOR: ByteArray = intArrayOf(
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x06,       // Usage (Keyboard)
        0xA1, 0x01,       // Collection (Application)
        0x85, REPORT_ID,  //   Report ID
        0x05, 0x07,       //   Usage Page (Key Codes)
        0x19, 0xE0,       //   Usage Minimum (Left Control)
        0x29, 0xE7,       //   Usage Maximum (Right GUI)
        0x15, 0x00,       //   Logical Minimum (0)
        0x25, 0x01,       //   Logical Maximum (1)
        0x75, 0x01,       //   Report Size (1)
        0x95, 0x08,       //   Report Count (8)
        0x81, 0x02,       //   Input (Data, Variable, Absolute) - modifier byte
        0x95, 0x01,       //   Report Count (1)
        0x75, 0x08,       //   Report Size (8)
        0x81, 0x01,       //   Input (Constant) - reserved byte
        0x95, 0x06,       //   Report Count (6)
        0x75, 0x08,       //   Report Size (8)
        0x15, 0x00,       //   Logical Minimum (0)
        0x25, 0x65,       //   Logical Maximum (101)
        0x05, 0x07,       //   Usage Page (Key Codes)
        0x19, 0x00,       //   Usage Minimum (0)
        0x29, 0x65,       //   Usage Maximum (101)
        0x81, 0x00,       //   Input (Data, Array) - key array
        0xC0,             // End Collection
    ).map { it.toByte() }.toByteArray()

    fun keyCode(ch: Char): Byte? = when (ch) {
        in '1'..'9' -> (0x1E + (ch - '1')).toByte()
        '0' -> 0x27.toByte()
        '\n' -> ENTER
        else -> null
    }

    fun keyDown(code: Byte) = ByteArray(SIZE).also { it[2] = code }

    /** No modifier, no key: releases everything. Sent after every key and after Enter. */
    fun allReleased() = ByteArray(SIZE)
}
