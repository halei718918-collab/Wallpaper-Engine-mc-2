# Wallpaper Engine — Notes & Troubleshooting

## Table of Contents

- [1. Environment Requirements](#1-environment-requirements)
- [2. Wallpaper Files](#2-wallpaper-files)
- [3. Video Wallpapers & ffmpeg](#3-video-wallpapers--ffmpeg)
- [4. Rendering & Compatibility](#4-rendering--compatibility)
- [5. Performance & Resources](#5-performance--resources)
- [6. Configuration Notes](#6-configuration-notes)
- [7. Troubleshooting](#7-troubleshooting)
- [8. Developer Notes](#8-developer-notes)

---

## 1. Environment Requirements

### Java Version
- **Java 21 or higher is required** for both build tools and runtime.
- Gradle will automatically check the Java version during builds.

### Fabric Environment
- Requires Fabric Loader ≥ 0.19.3 and Fabric API.
- Uses `splitEnvironmentSourceSets()` to separate client and server code — server does not load client code.

---

## 2. Wallpaper Files

### Wallpaper Directory
- Wallpapers go in `.minecraft/config/wallpaper/`.
- The directory is created automatically on first launch. If no wallpapers are found, a message will appear saying "No wallpapers found".

### Static Images
- Supported formats: PNG, JPG/JPEG, BMP, GIF
- Unsupported characters in file names are replaced with underscores `_`. Final characters are `[a-z0-9._-]`.
- **GIF files use only the first frame** — animated GIFs are not supported.

### File Name Restrictions
- File names are converted to a lowercase ResourceLocation: `halei-mod:wallpaper/<name>`
- Uppercase letters and special characters are processed, which may cause name collisions.
- **Use English letters, numbers, hyphens, and underscores for file names.**

---

## 3. Video Wallpapers & ffmpeg

### ffmpeg Search Order
The mod searches for ffmpeg once on startup (in order of priority):

1. `ffmpeg` in the system PATH
2. winget install directory
3. `C:\ffmpeg\bin\ffmpeg.exe`
4. `C:\Program Files\ffmpeg\bin\ffmpeg.exe`

### ⚠️ Important Limitations
- **If ffmpeg is not installed, all video files are silently skipped.** The log will show "ffmpeg not found, skipping video wallpaper".
- Video playback relies on ffmpeg's `rawvideo` pipe output (RGBA format).
- Videos are scaled with the `scale` filter, capped at 1920×1080, with even dimensions enforced.
- Videos **loop by default** (`-stream_loop -1`). If the file can't loop, playback will stop.

### Playback Speed
- Range: 0.5x ~ 5.0x (via slider or config file)
- Speed is controlled by adjusting the frame decode interval. At higher speeds, backlogged frames are dropped to keep real-time playback.
- At very high speeds (≥ 4x), the video may not keep up, resulting in visible frame drops.

### Video Decode Threads
- Each video wallpaper runs an independent background decode thread (daemon thread).
- Switching wallpapers automatically stops the old decode thread.
- Thread name pattern: `halei-video-<wallpaper_name>`

---

## 4. Rendering & Compatibility

### ModernUI + Sodium Environment
- With both ModernUI and Sodium installed, the standard `disableBlend` method may fail, causing dirt background to show through the wallpaper.
- **Solution**: A solid black background (`0xFF000000`) is rendered before the wallpaper. This is already implemented in `renderMenuWallpaper`.

### Main Menu Rendering
- Uses a **triple guarantee** to ensure wallpaper displays correctly:
  1. `@Inject` at `TitleScreen.render()` HEAD — draws wallpaper first
  2. `@Redirect` prevents panorama background from overwriting the wallpaper
  3. `@Inject` at `TitleScreen.renderBackground()` HEAD — cancels the default render
- Falls back to the original panorama if no wallpapers are available.

### All Menu Screens
- Injected into `Screen.renderBackground()` via `@Mixin(Screen.class)`.
- In-game screens (where `level != null`) are not overridden — the semi-transparent background is preserved so the game world shows through.

### HUD AFK Overlay
- Injected at `Gui.render()` TAIL, rendered above all HUD elements.
- Opacity is controlled via `RenderSystem.setShaderColor` alpha channel.
- Video frames continue updating even at 0% opacity (ensures immediacy when switching wallpapers).

---

## 5. Performance & Resources

### GPU Textures
- Static wallpapers use `DynamicTexture` (NativeImage) in RGBA format.
- Video wallpapers use **dual PBO (Pixel Buffer Objects)** for async GPU upload:
  - Double-buffered ping-pong: one PBO uploading while the other receives new data.
  - Uses `glMapBuffer(GL_WRITE_ONLY)`; falls back to `glTexSubImage2D` on failure.

### Pixel Data Cache
- All wallpaper RGBA pixel data is cached in memory (`wallpaperPixelData` list).
- Used to quickly restore textures after a resource pack reload.
- Memory usage = number of wallpapers × width × height × 4 bytes.
- **Large numbers of high-resolution wallpapers may consume significant memory.**

### Video Frame Updates
- The decode thread outputs frames at intervals calculated from fps and speed.
- The main render loop (≈60fps) checks for new frames and uploads via PBO.
- If the decode thread produces frames too quickly, backlogged frames are dropped (skipped when `pendingFrame` is not null).

---

## 6. Configuration Notes

### Config File Location
`config/halei-mod.json` (relative to the game run directory)

### Configuration Fields
| Field | Description |
|-------|-------------|
| `playSpeed` | Video playback speed, range 0.5~5.0. Values outside this range are clamped. |
| `inGameKKey` | Toggle the in-game K key to open the wallpaper selector. |

### Notes
- Changes take effect **immediately** — no restart needed.
- Config is serialized with Gson in pretty-print format.
- If the config file doesn't exist, it is created automatically with default values.

---

## 7. Troubleshooting

### 1. Wallpapers Not Showing
- **Check wallpaper directory**: `.minecraft/config/wallpaper/` — make sure files are in the right location.
- **Check file format**: Only PNG, JPG/JPEG, BMP, GIF (images); MP4, AVI, MKV, MOV, WebM (videos).
- **Check game log**: Search for `halei-mod/wallpaper` log entries.
- **Check on-screen message**: Pressing K and seeing "No wallpapers found" means the directory is empty or incorrect.

### 2. Video Wallpapers Not Playing
- **Check ffmpeg**: Run `ffmpeg -version` in the terminal to confirm it's installed.
- **Check game log**: Search for "ffmpeg not found".
- **Check video format**: Make sure the codec is supported by ffmpeg.
- **Check video resolution**: Very large videos may exceed decoding capability.

### 3. Wallpaper Colors Wrong / Glitched
- **ModernUI + Sodium**: The mod already handles this specific combination — make sure you're using the latest version.
- **OpenGL version**: OpenGL 3.0+ is required (for PBO support).
- **PBO map failure**: The mod falls back to direct upload, which may slightly reduce frame rate.

### 4. K Key Not Responding
- **Check key binding conflicts**: Other mods may be using the K key.
- **Check config**: Make sure `inGameKKey` is not set to `false`.
- **Try the main menu**: If it doesn't work in-game, try pressing K on the main menu.

### 5. Wallpapers Lost After Resource Pack Switch
- A reload listener is already registered — textures should auto-restore after switching resource packs.
- If still missing, press K to open the selection screen and re-select a wallpaper.

---

## 8. Developer Notes

### Build Commands
```bash
./gradlew build           # Build the mod
./gradlew clean build     # Clean and rebuild
```

Build artifacts are located in `build/libs/`.

### Code Structure
```
src/
├── client/                          # Client-side code (client only)
│   ├── java/com/example/halei/client/
│   │   ├── HaleiModClient.java      # Client initialization entry point
│   │   ├── ModConfig.java           # Configuration management
│   │   ├── WallpaperManager.java    # Core wallpaper manager (includes video player)
│   │   ├── WallpaperSelectionScreen.java  # Wallpaper selection UI
│   │   └── mixin/                   # Mixin injections
│   │       ├── TitleScreenMixin.java       # Main menu wallpaper
│   │       ├── ScreenBackgroundMixin.java  # Menu screen wallpaper
│   │       ├── HudRenderMixin.java         # AFK overlay
│   │       ├── InputMixin.java             # Mouse AFK detection
│   │       ├── KeyboardInputMixin.java     # Keyboard AFK detection
│   │       ├── ScreenAccessor.java         # Screen accessor
│   │       └── TitleScreenInvoker.java     # Invoker accessor
│   └── resources/halei-mod.client.mixins.json
└── main/
    ├── java/com/example/halei/
    │   ├── HaleiMod.java            # Mod main entry
    │   └── mixin/ExampleMixin.java   # Example mixin
    └── resources/
        ├── fabric.mod.json
        ├── halei-mod.mixins.json
        └── assets/halei-mod/lang/
            ├── zh_cn.json            # Chinese translations
            └── en_us.json            # English translations
```

### Key Constants

| Constant | Value | Description |
|----------|-------|-------------|
| AFK_TICKS | 6000 (5 min) | AFK trigger time |
| FADE_TO_HALF_MS | 5000ms | Opacity 0→50 |
| FADE_HALF_TO_FULL_MS | 5000ms | Opacity 50→100 |
| FADE_OUT_MS | 5000ms | Opacity 50→0 |
| loadDelayTicks | 100 ticks | Load delay (wait for OpenGL init) |
| Max video size | 1920×1080 | Scale cap |

### Known Limitations
- **GIF is static (first frame only)** — animated GIFs are not supported.
- A brief black screen may appear when switching video wallpapers (between stopping old and starting new decoder).
- PBO may fail on some integrated GPUs — falls back automatically.
- After resource pack reload, video players need to recapture the new texture reference (already implemented via `updateTexture`).
