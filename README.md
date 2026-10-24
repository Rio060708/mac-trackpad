# MacTrackpad (Bluetooth HID for Android → macOS)

Turns 100% of your Android phone screen (including notch/cutout areas in edge-to-edge immersive mode) into a wireless Bluetooth Multi-Touch Trackpad for your Mac — **with zero companion software needed on macOS**.

## Gesture Mapping (3-Finger Selection + 4-Finger Mission Control & Spaces)

| Gesture on Phone Screen | macOS Action | How It Works Over Bluetooth HID |
| :--- | :--- | :--- |
| **1-Finger Move** | Move Cursor | Native HID Mouse X/Y deltas with velocity acceleration |
| **1-Finger Single Tap** | Left Click | Native HID Mouse Button 1 click |
| **3-Finger Move** | **Text Selection & Window Drag** | Holds HID Mouse Button 1 down while moving 3-finger centroid (with 250ms edge-continuation window) |
| **1-Finger Double-Tap & Hold** | **Tap-and-Drag (Alternative)** | Holds HID Mouse Button 1 down while moving 1 finger; releases on lift |
| **2-Finger Single Tap** | Right Click | Native HID Mouse Button 2 click |
| **2-Finger Drag (Up/Down/Left/Right)** | **Vertical & Horizontal Scroll** | Native HID Wheel + Consumer `AC Pan` (`0x0238`) with 60fps fling inertia |
| **2-Finger Pinch In / Out** | **Pinch-to-Zoom** | Detects scale ratio and sends `⌘+` (`Cmd + =`) / `⌘-` (`Cmd + -`) |
| **2-Finger Double-Tap** | **Smart Zoom Toggle** | Toggles zoom in (`⌘+` twice) and zoom reset (`⌘0`) |
| **4-Finger Swipe Up** | **Mission Control** | Sends default macOS shortcut `⌃↑` (`Ctrl + Up Arrow`) |
| **4-Finger Swipe Down** | **App Exposé** | Sends default macOS shortcut `⌃↓` (`Ctrl + Down Arrow`) |
| **4-Finger Swipe Left / Right** | **Switch Spaces / Full-Screen Apps** | Sends default macOS shortcut `⌃→` / `⌃←` (`Ctrl + Right/Left Arrow`) |
