# Lock Editor (com.lockeditor.rs118)

LSPosed module for AOSP Android 11 (minSdk 29). Hooks `com.android.systemui` and `android` (system framework).

## Build (GitHub Actions)
Push this folder to a GitHub repo -> Actions -> "Android CI" -> download the `LockEditor-debug-apk` artifact.

## Use
1. Install the APK, enable it in LSPosed with scopes **System UI** and **System Framework**, then reboot
   (or at least restart SystemUI).
2. Open Lock Editor. Tap **Add widget**. Root is used to run
   `cmd appwidget grantbind --package com.android.systemui` so SystemUI may host widgets
   (automatic on first bind denial, or via Tools > Grant widget access).
3. Drag a widget to move it (snaps to cells), drag the corner handle to resize.
   Use Rows/Cols and the top/bottom inset sliders to match your lock screen area.
4. Toggles hide the stock clock, date, notifications and bottom shortcuts.

The editor and home preview show live renders of the widgets as hosted by SystemUI
(KWGT, Tasker etc. appear once they finish loading). The home preview also shows the wallpaper.

Debug: LSPosed log / `logcat | grep LockEditor`, or Tools > Status log.
