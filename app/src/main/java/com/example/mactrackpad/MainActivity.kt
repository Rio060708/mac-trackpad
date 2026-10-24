package com.example.mactrackpad

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var hidController: BluetoothHidController
    private lateinit var gestureEngine: TrackpadGestureEngine
    private lateinit var statusPill: TextView
    private lateinit var gestureBadge: TextView

    companion object {
        private const val REQ_BLUETOOTH_PERMS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on and expand across display cutouts (notches/camera holes)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Enable 100% full-screen edge-to-edge immersive mode
        enableImmersiveFullscreen()

        hidController = BluetoothHidController(this) { statusText, connectedDevice ->
            statusPill.text = if (connectedDevice != null) {
                "●  $statusText  (Hold for settings)"
            } else {
                "○  $statusText"
            }
            statusPill.setTextColor(
                if (connectedDevice != null) Color.parseColor("#7CE38B")
                else Color.parseColor("#AAB2C8")
            )
        }

        gestureEngine = TrackpadGestureEngine(this, hidController) { gestureLabel ->
            gestureBadge.text = gestureLabel
            gestureBadge.alpha = 0.9f
            gestureBadge.animate().alpha(0f).setDuration(900L).start()
        }

        // Root full-screen container
        val rootLayout = FrameLayout(this)
        val trackpadSurface = TrackpadSurfaceView(this, gestureEngine)
        rootLayout.addView(
            trackpadSurface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Minimalist floating status pill in top center (tap to connect Mac, long-press for settings)
        statusPill = TextView(this).apply {
            text = "○  Initializing Bluetooth HID..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#AAB2C8"))
            setPadding(dp(16), dp(7), dp(16), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3141722"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setOnClickListener { showDevicePickerDialog() }
            setOnLongClickListener {
                showSettingsDialog()
                true
            }
        }
        val pillParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(14)
        }
        rootLayout.addView(statusPill, pillParams)

        // Subtle gesture indicator badge at bottom center (non-clickable, passes touches through)
        gestureBadge = TextView(this).apply {
            text = ""
            alpha = 0f
            isClickable = false
            isFocusable = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#99D0E2FF"))
        }
        val badgeParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(16)
        }
        rootLayout.addView(gestureBadge, badgeParams)

        setContentView(rootLayout)
        ensureBluetoothPermissionsAndStart()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveFullscreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        hidController.stop()
    }

    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun ensureBluetoothPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_BLUETOOTH_PERMS)
                return
            }
        }
        hidController.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLUETOOTH_PERMS) {
            hidController.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePickerDialog() {
        val paired = hidController.getPairedDevices()
        if (paired.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Pair Your Mac First")
                .setMessage(
                    "1. Open System Settings → Bluetooth on your Mac.
" +
                    "2. Pair your Android phone with your Mac once.
" +
                    "3. Return here and tap this pill to connect as a Bluetooth Trackpad."
                )
                .setPositiveButton("OK", null)
                .setNeutralButton("Trackpad Settings") { _, _ -> showSettingsDialog() }
                .show()
            return
        }

        val names = paired.map { "${it.name ?: "Unknown Device"} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Connect to Paired Mac")
            .setItems(names) { _, which ->
                hidController.connectToDevice(paired[which])
            }
            .setNeutralButton("Sensitivity & Gestures") { _, _ -> showSettingsDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val speedLabel = TextView(this).apply {
            text = "Cursor Speed: ${"%.1f".format(gestureEngine.cursorSensitivity)}x"
        }
        val speedSeek = SeekBar(this).apply {
            max = 30
            progress = (gestureEngine.cursorSensitivity * 10).toInt().coerceIn(5, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress.coerceAtLeast(5) / 10f
                    gestureEngine.cursorSensitivity = v
                    speedLabel.text = "Cursor Speed: ${"%.1f".format(v)}x"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val naturalSwitch = Switch(this).apply {
            text = "Natural Scrolling (macOS default)"
            isChecked = gestureEngine.naturalScrolling
            setOnCheckedChangeListener { _, checked ->
                gestureEngine.naturalScrolling = checked
            }
        }

        val threeFingerDragSwitch = Switch(this).apply {
            text = "3-Finger Selection & Drag (4-Finger Mission Control)"
            isChecked = gestureEngine.threeFingerDragEnabled
            setOnCheckedChangeListener { _, checked ->
                gestureEngine.threeFingerDragEnabled = checked
            }
        }

        val guideText = TextView(this).apply {
            setPadding(0, dp(12), 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            text =
                "Configured macOS Gestures:
" +
                "• 1-Finger Move / Tap: Move cursor / Left click
" +
                "• 3-Finger Move: Selection & Window Drag (plus 1-Finger Double-Tap & Hold)
" +
                "• 2-Finger Tap: Right click
" +
                "• 2-Finger Drag: Smooth Vertical & Horizontal Scroll + Inertia
" +
                "• 2-Finger Pinch In / Out: Zoom Out (⌘-) / Zoom In (⌘+)
" +
                "• 2-Finger Double-Tap: Smart Zoom Toggle (⌘+ / ⌘0)
" +
                "• 4-Finger Swipe Up / Down: Mission Control (⌃↑) / App Exposé (⌃↓)
" +
                "• 4-Finger Swipe Left / Right: Switch Spaces (⌃→ / ⌃←)"
        }

        layout.addView(speedLabel)
        layout.addView(speedSeek)
        layout.addView(naturalSwitch)
        layout.addView(threeFingerDragSwitch)
        layout.addView(guideText)

        AlertDialog.Builder(this)
            .setTitle("Mac Trackpad Settings")
            .setView(layout)
            .setPositiveButton("Done") { _, _ -> enableImmersiveFullscreen() }
            .show()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
