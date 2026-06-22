# Wallpaper Engine — User Guide

## Introduction

This mod (halei-mod) adds a dynamic wallpaper system for Minecraft 1.21.1 (Fabric). It supports both static images and video wallpapers, displaying beautiful backgrounds on the main menu and automatically fading in wallpaper overlays when you're AFK.

---

## 1. Requirements

| Requirement | Details |
|-------------|---------|
| Minecraft | 1.21.1 |
| Fabric Loader | ≥ 0.19.3 |
| Fabric API | Any version |
| Java | ≥ 21 |
| ffmpeg (optional) | Required for video wallpapers |

---

## 2. Installing Wallpapers

1. Launch the game once — the mod will automatically create the `config/wallpaper/` directory
2. Place your wallpaper files into that directory

### Supported Formats

| Type | Formats |
|------|---------|
| Static Images | PNG, JPG/JPEG, BMP, GIF |
| Videos (ffmpeg required) | MP4, AVI, MKV, MOV, WebM |

> **Note**: Video wallpapers are scaled to a maximum of 1920×1080 to reduce GPU load.

---

## 3. Hotkeys

| Key | Function |
|-----|----------|
| **K** | Open the wallpaper selection screen (works on the main menu and in-game) |

> The hotkey can be customized in Game Options → Controls → Wallpaper Engine.

---

## 4. Wallpaper Selection Screen

Press **K** to open the wallpaper selection screen, where you can:

- Browse all wallpapers (with thumbnails, file names, dimensions, and video indicators)
- Click a wallpaper entry → click **Select** to switch to that wallpaper
- Adjust video playback speed using the slider (0.5x ~ 5.0x)
- Press Esc to close

The currently active wallpaper is marked with a **green ▶** icon and a **✓ Active** label.

---

## 5. AFK Wallpaper Overlay

The mod has a built-in AFK (Away From Keyboard) detection system:

| Phase | Duration | Opacity | Description |
|-------|----------|---------|-------------|
| Player Active | — | 0% | Wallpaper transparent, no impact on gameplay |
| AFK Phase 1 | 5 sec | 0% → 50% | Just went AFK, wallpaper fades in semi-transparent |
| AFK Phase 2 | 5 sec | 50% → 100% | Wallpaper fades to full opacity |
| AFK Stable | After 5 min | 100% | Wallpaper fully visible |
| Return Active | 5 sec | 50% → 0% | Wallpaper fades out on mouse/keyboard activity |

> AFK trigger: **5 minutes** without any input (mouse movement, clicks, scroll, keyboard).

---

## 6. Video Wallpapers (ffmpeg)

### Installing ffmpeg

Video playback requires **ffmpeg**. The mod searches automatically in this order:

1. **System PATH** environment variable (recommended)
2. **winget install directory**: `%LOCALAPPDATA%\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.WinGet.Source_8wekyb3d8bbwe\`
3. **Common paths**: `C:\ffmpeg\bin\ffmpeg.exe`

> If ffmpeg is not found, all video files are skipped — only static images will be shown.

### How It Works

- ffmpeg decodes video frames in a background thread (real-time scaling applied)
- Frame data is uploaded to the GPU asynchronously via PBO (Pixel Buffer Object)
- Videos loop continuously (`-stream_loop -1`)
- Playback speed can be adjusted in real-time via the slider (0.5x ~ 5.0x)

### Performance Tips

- Keep video resolution reasonable (1920×1080 or lower)
- When switching videos, the old player is automatically stopped

---

## 7. Configuration

Config file location: `config/halei-mod.json`

```json
{
  "playSpeed": 2.5,
  "inGameKKey": true
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `playSpeed` | float | 2.5 | Video playback speed (0.5 ~ 5.0) |
| `inGameKKey` | boolean | true | Enable K key in-game to open wallpaper selector |

> Changes take effect immediately — no game restart required.

---

## 8. Resource Pack Switching

When switching resource packs, all wallpaper textures are automatically reloaded. No manual action needed.

---

## 9. Main Menu Wallpaper Button

A small **「壁」** button is located at the bottom-right corner of the main menu (near the accessibility button). Click it to open the wallpaper selection screen directly.
