# Lock Editor (com.lockeditor.rs118)

LSPosed module for AOSP Android 11 (minSdk 29). Hooks `com.android.systemui`.

## Build (GitHub Actions)
Push this folder to a GitHub repo -> Actions -> "Android CI" -> download the `LockEditor-debug-apk` artifact.

## Use
1. Install the APK, enable it in LSPosed with scope **System UI**, restart SystemUI (or reboot).
2. Open Lock Editor. Tap **Add widget** (root is used once to run
   `cmd appwidget grantbind --package com.android.systemui` so SystemUI may host widgets).
3. Drag a widget to move it (snaps to cells), drag the corner handle to resize.
   Use Rows/Cols and the top/bottom inset sliders to match your lock screen area.
4. Toggles hide the stock clock, date, notifications and bottom shortcuts.

Debug: LSPosed log / `logcat | grep LockEditor`.
