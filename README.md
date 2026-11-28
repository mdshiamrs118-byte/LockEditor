# Lock Editor (com.lockeditor.rs118)

LSPosed module for AOSP Android 11 (minSdk 29). Hooks `com.android.systemui` and `android` (system framework).

## Build (GitHub Actions)
Push this folder to a GitHub repo -> Actions -> "Android CI" -> download the `LockEditor-debug-apk` artifact.

## Use
1. Install the APK, enable it in LSPosed with scopes **System UI** and **System Framework**, then reboot
   (or at least restart SystemUI).
2. Open Lock Editor and tap **Setup check** to verify root, the hooks, the widget host and the wallpaper source.
3. Tap **Edit layout**, then **Add widget**. Root is used to run
   `cmd appwidget grantbind --package com.android.systemui` so SystemUI may host widgets
   (automatic on first bind denial, or via Tools > Grant widget access).

## Editor
- Drag to move, drag the corner handle to resize. **Snap** cycles 1 / 1/2 / 1/4 cell / Free (pixel-level).
- **Guides**: pink dashed lines show alignment with other widgets and the grid; with a fine snap or Free they also pull magnetically.
- **Undo / redo** (top bar) cover moves, resizes, adds, deletes and layer changes.
- **Layers**: Back / ▼ / ▲ / Front in the widget panel. The last widget is drawn on top, on the lock screen too.
- **Notifications**: the amber box is the lock screen notification list. Drag to move, corner handle to resize, or use the panel (W/H %, X/Y dp). Toggle it with the Notifs chip. Widgets sit above it, as on the real lock screen.
- The editor and home preview show live renders of the widgets as hosted by SystemUI.

## Backups
Backups list: new, restore (saves the current state first), rename, delete, export to a .json file, import from a file.

## Hide stock elements
Clock, date, notifications, shortcuts, lock icon, hints (unlock / charging text), carrier name, status bar.
The Setup check shows which of these were found on your ROM.

## Theme
Tools > Theme: System, Light or Dark.

Debug: LSPosed log / `logcat | grep LockEditor`, or Tools > Status log.
