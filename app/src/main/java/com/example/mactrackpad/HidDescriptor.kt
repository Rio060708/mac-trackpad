package com.example.mactrackpad

/**
 * Composite USB/Bluetooth HID Report Descriptor combining:
 * 1. Report ID 1: 5-byte Mouse + Horizontal/Vertical Precision Scroll
 *    - Byte 0: Buttons (Bit 0 = Left Click/Selection Drag, Bit 1 = Right Click, Bit 2 = Middle Click)
 *    - Byte 1: X Delta (-127 to 127)
 *    - Byte 2: Y Delta (-127 to 127)
 *    - Byte 3: Vertical Wheel (-127 to 127)
 *    - Byte 4: Horizontal Scroll / AC Pan (-127 to 127)
 *
 * 2. Report ID 2: 8-byte Standard Keyboard (for macOS Multi-Touch Shortcut Emulation)
 *    - Byte 0: Modifier Bitmask (0x01 = Left Ctrl, 0x08 = Left Command/GUI)
 *    - Byte 1: Reserved (0x00)
 *    - Bytes 2..7: Up to 6 Simultaneous Keycodes
 *      - Mission Control (4-finger swipe up):    Left Ctrl (0x01) + Up Arrow (0x52)
 *      - App Expose (4-finger swipe down):       Left Ctrl (0x01) + Down Arrow (0x51)
 *      - Next Space (4-finger swipe left):       Left Ctrl (0x01) + Right Arrow (0x4F)
 *      - Previous Space (4-finger swipe right):  Left Ctrl (0x01) + Left Arrow (0x50)
 *      - Pinch Zoom In:                          Left Cmd  (0x08) + Equal/Plus (0x2E)
 *      - Pinch Zoom Out:                         Left Cmd  (0x08) + Minus (0x2D)
 *      - Smart Zoom Reset:                       Left Cmd  (0x08) + Key 0 (0x27)
 */
object HidDescriptor {

    const val REPORT_ID_MOUSE: Int = 1
    const val REPORT_ID_KEYBOARD: Int = 2

    // Keyboard Modifier Bits
    const val MOD_NONE: Byte = 0x00
    const val MOD_LEFT_CTRL: Byte = 0x01
    const val MOD_LEFT_SHIFT: Byte = 0x02
    const val MOD_LEFT_ALT: Byte = 0x04
    const val MOD_LEFT_GUI: Byte = 0x08 // macOS Command (Cmd) Key

    // USB HID Usage Keycodes
    const val KEY_0: Byte = 0x27
    const val KEY_MINUS: Byte = 0x2D
    const val KEY_EQUAL_PLUS: Byte = 0x2E
    const val KEY_RIGHT_ARROW: Byte = 0x4F
    const val KEY_LEFT_ARROW: Byte = 0x50
    const val KEY_DOWN_ARROW: Byte = 0x51
    const val KEY_UP_ARROW: Byte = 0x52

    val COMPOSITE_MOUSE_KEYBOARD_DESCRIPTOR: ByteArray = byteArrayOf(
        // =====================================================================
        // REPORT ID 1: MOUSE + VERTICAL WHEEL + HORIZONTAL AC PAN (5 BYTES)
        // =====================================================================
        0x05.toByte(), 0x01.toByte(),       // Usage Page (Generic Desktop)
        0x09.toByte(), 0x02.toByte(),       // Usage (Mouse)
        0xA1.toByte(), 0x01.toByte(),       // Collection (Application)
        0x85.toByte(), 0x01.toByte(),       //   Report ID (1)
        0x09.toByte(), 0x01.toByte(),       //   Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(),       //   Collection (Physical)

        // Byte 0: 3 Buttons (Left, Right, Middle) + 5 padding bits
        0x05.toByte(), 0x09.toByte(),       //     Usage Page (Buttons)
        0x19.toByte(), 0x01.toByte(),       //     Usage Minimum (1)
        0x29.toByte(), 0x03.toByte(),       //     Usage Maximum (3)
        0x15.toByte(), 0x00.toByte(),       //     Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),       //     Logical Maximum (1)
        0x95.toByte(), 0x03.toByte(),       //     Report Count (3)
        0x75.toByte(), 0x01.toByte(),       //     Report Size (1)
        0x81.toByte(), 0x02.toByte(),       //     Input (Data, Variable, Absolute)
        0x95.toByte(), 0x01.toByte(),       //     Report Count (1)
        0x75.toByte(), 0x05.toByte(),       //     Report Size (5)
        0x81.toByte(), 0x01.toByte(),       //     Input (Constant - 5 bit padding)

        // Bytes 1, 2, 3: X, Y, Vertical Wheel (-127 to +127)
        0x05.toByte(), 0x01.toByte(),       //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(),       //     Usage (X)
        0x09.toByte(), 0x31.toByte(),       //     Usage (Y)
        0x09.toByte(), 0x38.toByte(),       //     Usage (Wheel)
        0x15.toByte(), 0x81.toByte(),       //     Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(),       //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(),       //     Report Size (8)
        0x95.toByte(), 0x03.toByte(),       //     Report Count (3)
        0x81.toByte(), 0x06.toByte(),       //     Input (Data, Variable, Relative)

        // Byte 4: Horizontal Scroll / AC Pan (-127 to +127)
        0x05.toByte(), 0x0C.toByte(),       //     Usage Page (Consumer Devices)
        0x0A.toByte(), 0x38.toByte(), 0x02.toByte(), // Usage (AC Pan - 0x0238)
        0x15.toByte(), 0x81.toByte(),       //     Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(),       //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(),       //     Report Size (8)
        0x95.toByte(), 0x01.toByte(),       //     Report Count (1)
        0x81.toByte(), 0x06.toByte(),       //     Input (Data, Variable, Relative)

        0xC0.toByte(),                      //   End Collection (Physical)
        0xC0.toByte(),                      // End Collection (Application)

        // =====================================================================
        // REPORT ID 2: KEYBOARD FOR MACOS MULTI-TOUCH SHORTCUTS (8 BYTES)
        // =====================================================================
        0x05.toByte(), 0x01.toByte(),       // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(),       // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(),       // Collection (Application)
        0x85.toByte(), 0x02.toByte(),       //   Report ID (2)

        // Byte 0: Modifier Keys (Left Ctrl..Right GUI)
        0x05.toByte(), 0x07.toByte(),       //   Usage Page (Key Codes)
        0x19.toByte(), 0xE0.toByte(),       //   Usage Minimum (224 - Left Control)
        0x29.toByte(), 0xE7.toByte(),       //   Usage Maximum (231 - Right GUI)
        0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),       //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),       //   Report Size (1)
        0x95.toByte(), 0x08.toByte(),       //   Report Count (8)
        0x81.toByte(), 0x02.toByte(),       //   Input (Data, Variable, Absolute)

        // Byte 1: Reserved
        0x95.toByte(), 0x01.toByte(),       //   Report Count (1)
        0x75.toByte(), 0x08.toByte(),       //   Report Size (8)
        0x81.toByte(), 0x01.toByte(),       //   Input (Constant)

        // Bytes 2..7: 6 Keycodes
        0x95.toByte(), 0x06.toByte(),       //   Report Count (6)
        0x75.toByte(), 0x08.toByte(),       //   Report Size (8)
        0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
        0x25.toByte(), 0x65.toByte(),       //   Logical Maximum (101)
        0x05.toByte(), 0x07.toByte(),       //   Usage Page (Key Codes)
        0x19.toByte(), 0x00.toByte(),       //   Usage Minimum (0)
        0x29.toByte(), 0x65.toByte(),       //   Usage Maximum (101)
        0x81.toByte(), 0x00.toByte(),       //   Input (Data, Array)

        0xC0.toByte()                       // End Collection (Application)
    )
}
