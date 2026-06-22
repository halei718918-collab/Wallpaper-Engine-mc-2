package com.example.halei.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/**
 * 壁纸引擎核心管理器
 * 负责壁纸的加载、切换、AFK 检测和透明度渐变动画。
 */
public class WallpaperManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("halei-mod/wallpaper");
    private static final WallpaperManager INSTANCE = new WallpaperManager();

    // ===== 配置常量 =====
    /** AFK 触发时间：5 分钟 = 6000 ticks */
    private static final long AFK_TICKS = 20L * 60 * 5;
    /** 从 0→50 透明度所需毫秒 */
    private static final long FADE_TO_HALF_MS = 5000L;
    /** 从 50→100 透明度所需毫秒 */
    private static final long FADE_HALF_TO_FULL_MS = 5000L;
    /** 从 50→0 透明度所需毫秒（恢复活动时） */
    private static final long FADE_OUT_MS = 5000L;

    // ===== 壁纸列表 =====
    private final List<ResourceLocation> wallpaperIds = new ArrayList<>();
    private final List<Path> wallpaperPaths = new ArrayList<>();
    private final List<Integer> texWidths = new ArrayList<>();
    private final List<Integer> texHeights = new ArrayList<>();
    /** 像素数据缓存（RGBA 字节数组），用于资源重载后恢复纹理 */
    private final List<byte[]> wallpaperPixelData = new ArrayList<>();
    private int currentIndex = -1;
    private final Random random = new Random();

    // ===== 视频播放 =====
    private final java.util.Map<Integer, VideoPlayer> videoPlayers = new java.util.HashMap<>();
    private volatile boolean hasVideoPlayer = false;

    // ===== AFK 状态 =====
    private long lastInputTime = System.currentTimeMillis();
    private long idleTicks = 0;
    private boolean isAfk = false;
    private float currentOpacity = 0f;
    /** 当用户活动时记录此刻时间，用于计算淡出动画 */
    private long activityStartTime = 0;
    /** 当进入 AFK 时记录此刻时间，用于计算淡入动画 */
    private long afkStartTime = 0;
    /** 之前是否处于 AFK 状态 */
    private boolean wasAfk = false;

    // ===== 标记 =====
    private boolean initialized = false;
    /** 延迟加载计数器（等游戏完全启动后再加载壁纸） */
    private int loadDelayTicks = 100;

    private WallpaperManager() {}

    public static WallpaperManager getInstance() {
        return INSTANCE;
    }

    // ===== 初始化 =====
    public void init() {
        if (initialized) return;
        lastInputTime = System.currentTimeMillis();
        initialized = true;
        LOGGER.info("壁纸管理器已初始化，壁纸将在第一个 tick 时加载");
    }

    // ===== 加载壁纸 =====
    private void loadWallpapers() {
        Minecraft mc = Minecraft.getInstance();
        Path configDir = mc.gameDirectory.toPath().resolve("config").resolve("wallpaper");

        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
                LOGGER.info("已创建壁纸目录: {}", configDir);
                return;
            }

            // 加载静态图片 (PNG, JPG, BMP, GIF)
            loadImageWallpapers(mc, configDir);
            // 加载视频文件 (MP4, AVI, MKV, MOV, WEBM)
            loadVideoWallpapers(mc, configDir);
        } catch (IOException e) {
            LOGGER.error("读取壁纸目录失败", e);
        }
    }

    private void loadImageWallpapers(Minecraft mc, Path configDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "*.{png,jpg,jpeg,bmp,gif}")) {
            for (Path path : stream) {
                try {
                    BufferedImage image = ImageIO.read(path.toFile());
                    if (image == null) {
                        LOGGER.warn("无法读取图片: {}", path.getFileName());
                        continue;
                    }

                    // 生成安全的 ResourceLocation（只允许小写 [a-z0-9/._-]）
                    String fileName = path.getFileName().toString();
                    int dot = fileName.lastIndexOf('.');
                    String nameWithoutExt = (dot > 0) ? fileName.substring(0, dot) : fileName;
                    String safeName = nameWithoutExt
                            .replaceAll("[^a-zA-Z0-9._-]", "_")
                            .toLowerCase(Locale.ROOT);
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                            "halei-mod", "wallpaper/" + safeName
                    );

                    NativeImage nativeImage = nativeImageFromBufferedImage(image);

                    // 保存像素数据副本（RGBA 格式），用于资源重载后恢复纹理
                    byte[] pixelData = bufferedImageToRgba(image);

                    DynamicTexture texture = new DynamicTexture(nativeImage);
                    mc.getTextureManager().register(id, texture);

                    wallpaperIds.add(id);
                    wallpaperPaths.add(path);
                    texWidths.add(texture.getPixels().getWidth());
                    texHeights.add(texture.getPixels().getHeight());
                    wallpaperPixelData.add(pixelData);
                    LOGGER.info("已加载壁纸: {} ({}x{})", path.getFileName(),
                            texture.getPixels().getWidth(), texture.getPixels().getHeight());
                } catch (IOException e) {
                    LOGGER.error("加载壁纸失败: {}", path.getFileName(), e);
                }
            }
        }
    }

    /** ffmpeg 可执行文件路径（搜索到后缓存） */
    private String ffmpegPath = null;

    /** 搜索 ffmpeg 可执行文件 */
    private boolean findFfmpeg() {
        // 1. 先试 PATH 中的 ffmpeg
        try {
            Process check = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .start();
            if (check.waitFor(2, TimeUnit.SECONDS) && check.exitValue() == 0) {
                ffmpegPath = "ffmpeg";
                return true;
            }
        } catch (Exception ignored) {}

        // 2. 搜索 winget 安装目录
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path wingetDir = Path.of(localAppData,
                    "Microsoft", "WinGet", "Packages",
                    "Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe");
            if (Files.isDirectory(wingetDir)) {
                try (DirectoryStream<Path> dirs = Files.newDirectoryStream(wingetDir, "ffmpeg-*")) {
                    for (Path dir : dirs) {
                        Path exe = dir.resolve("bin").resolve("ffmpeg.exe");
                        if (Files.exists(exe)) {
                            ffmpegPath = exe.toAbsolutePath().toString();
                            return true;
                        }
                    }
                } catch (IOException ignored) {}
            }
        }

        // 3. 搜索常见路径
        String[] searchPaths = {
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
        };
        for (String path : searchPaths) {
            if (Files.exists(Path.of(path))) {
                ffmpegPath = path;
                return true;
            }
        }

        return false;
    }

    private void loadVideoWallpapers(Minecraft mc, Path configDir) {
        // 搜索 ffmpeg（只搜一次）
        if (ffmpegPath == null) {
            if (!findFfmpeg()) {
                LOGGER.info("未找到 ffmpeg，跳过视频壁纸");
                ffmpegPath = ""; // 标记已搜索过
                return;
            }
            LOGGER.info("找到 ffmpeg: {}", ffmpegPath);
        } else if (ffmpegPath.isEmpty()) {
            return; // 之前已搜索过但没找到
        }

        // 逐个扩展名扫描（避免花括号通配符在某些系统上不支持）
        String[] videoExts = {"mp4", "avi", "mkv", "mov", "webm"};
        for (String ext : videoExts) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "*." + ext)) {
                for (Path path : stream) {
                    loadSingleVideoWallpaper(mc, path);
                }
            } catch (IOException e) {
                LOGGER.warn("扫描 .{} 文件时出错: {}", ext, e.getMessage());
            }
        }
    }

    private void loadSingleVideoWallpaper(Minecraft mc, Path path) {
        Path tempFile = null;
        try {
            // ---- 1. 用 ffprobe 获取视频尺寸与帧率 ----
            int[] videoInfo = getVideoInfo(path);
            if (videoInfo == null) {
                LOGGER.warn("无法获取视频信息，跳过: {}", path.getFileName());
                return;
            }
            int vidWidth = videoInfo[0];
            int vidHeight = videoInfo[1];
            double fps = videoInfo[2] / 1000.0; // 存储为整数毫秒，此处还原

            // ---- 计算缩放尺寸（限制最大 1920x1080，降低 GPU 带宽消耗） ----
            int maxW = 1920, maxH = 1080;
            int[] scaled = calcScaledDimensions(vidWidth, vidHeight, maxW, maxH);
            int scaledW = scaled[0];
            int scaledH = scaled[1];
            boolean needScale = scaledW != vidWidth || scaledH != vidHeight;

            // ---- 2. 提取第一帧（已缩放）作为初始静态纹理 ----
            tempFile = Files.createTempFile("halei_wp_", ".png");
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath, "-loglevel", "quiet", "-y",
                    "-i", path.toAbsolutePath().toString(),
                    "-vframes", "1",
                    "-s", scaledW + "x" + scaledH,
                    tempFile.toAbsolutePath().toString()
            );
            Process process = pb.start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                LOGGER.warn("ffmpeg 提取视频帧失败，跳过: {}", path.getFileName());
                return;
            }

            BufferedImage image = ImageIO.read(tempFile.toFile());
            if (image == null) {
                LOGGER.warn("无法读取视频帧: {}", path.getFileName());
                return;
            }

            // ---- 3. 注册纹理 ----
            String fileName = path.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String nameWithoutExt = (dot > 0) ? fileName.substring(0, dot) : fileName;
            String safeName = nameWithoutExt
                    .replaceAll("[^a-zA-Z0-9._-]", "_")
                    .toLowerCase(Locale.ROOT);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    "halei-mod", "wallpaper/" + safeName
            );
            NativeImage firstFrame = nativeImageFromBufferedImage(image);

            // 保存像素数据副本（RGBA 格式），用于资源重载后恢复纹理
            byte[] pixelData = bufferedImageToRgba(image);

            DynamicTexture texture = new DynamicTexture(firstFrame);
            mc.getTextureManager().register(id, texture);

            int idx = wallpaperIds.size();
            wallpaperIds.add(id);
            wallpaperPaths.add(path);
            texWidths.add(scaledW);
            texHeights.add(scaledH);
            wallpaperPixelData.add(pixelData);
            String fpsInfo = String.format(java.util.Locale.ROOT, "%.1f", fps);
            String scaleInfo = needScale ? String.format(" (缩放至 %dx%d)", scaledW, scaledH) : "";
            LOGGER.info("已加载视频壁纸: {} ({}x{}{}, {} fps)",
                    path.getFileName(), vidWidth, vidHeight, scaleInfo, fpsInfo);

            // ---- 4. 启动视频播放器（使用缩放后的尺寸） ----
            VideoPlayer player = new VideoPlayer(path, scaledW, scaledH, fps, texture, id);
            videoPlayers.put(idx, player);
            hasVideoPlayer = true;
            // 如果当前就是这张壁纸，立即开始播放
            if (idx == currentIndex) {
                player.start();
            }

        } catch (IOException e) {
            LOGGER.error("加载视频壁纸失败: {}", path.getFileName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("加载视频壁纸被中断: {}", path.getFileName(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {}
            }
        }
    }

    /** 用 ffprobe 解析视频尺寸和帧率 */
    private int[] getVideoInfo(Path videoPath) {
        try {
            // 先试 ffprobe（通常与 ffmpeg 同目录）
            String probeCmd = ffmpegPath;
            String probeExe;
            if (probeCmd.equals("ffmpeg")) {
                probeExe = "ffprobe";
            } else {
                // 把 ffmpeg.exe 替换为 ffprobe.exe
                Path p = Path.of(probeCmd);
                String parent = p.getParent() != null ? p.getParent().toString() : ".";
                probeExe = parent + "/ffprobe" +
                        (probeCmd.endsWith(".exe") ? ".exe" : "");
            }

            ProcessBuilder pb = new ProcessBuilder(
                    probeExe, "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height,r_frame_rate",
                    "-of", "csv=p=0",
                    videoPath.toAbsolutePath().toString()
            );
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            boolean ok = process.waitFor(5, TimeUnit.SECONDS);
            if (ok && process.exitValue() == 0 && !output.isEmpty()) {
                String[] parts = output.split(",");
                if (parts.length >= 3) {
                    int w = Integer.parseInt(parts[0].trim());
                    int h = Integer.parseInt(parts[1].trim());
                    String fpsStr = parts[2].trim();
                    double fps = 30.0;
                    if (fpsStr.contains("/")) {
                        String[] frac = fpsStr.split("/");
                        double num = Double.parseDouble(frac[0].trim());
                        double den = Double.parseDouble(frac[1].trim());
                        fps = (den > 0) ? num / den : 30.0;
                    } else {
                        fps = Double.parseDouble(fpsStr);
                    }
                    return new int[]{w, h, (int) (fps * 1000)}; // fps 存为整数毫秒
                }
            }
        } catch (Exception ignored) {}

        // fallback: 用 ffmpeg 探测
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath, "-loglevel", "error", "-i",
                    videoPath.toAbsolutePath().toString(),
                    "-f", "null", "-"
            );
            Process process = pb.start();
            String err = new String(process.getErrorStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            process.waitFor(5, TimeUnit.SECONDS);

            // 从输��中提取尺寸，例如: Stream #0:0: Video: ... 1920x1080 ...
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(\\d+)x(\\d+)").matcher(err);
            if (m.find()) {
                int w = Integer.parseInt(m.group(1));
                int h = Integer.parseInt(m.group(2));
                return new int[]{w, h, 30000}; // 默认 30fps
            }
        } catch (Exception ignored) {}

        return null;
    }

    // ===== BufferedImage 工具方法 =====

    /** 将 BufferedImage 转为 RGBA 字节数组（用于缓存像素数据） */
    private static byte[] bufferedImageToRgba(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] data = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                int i = (y * w + x) * 4;
                data[i]     = (byte)((argb >> 16) & 0xFF); // R
                data[i + 1] = (byte)((argb >> 8) & 0xFF);  // G
                data[i + 2] = (byte)(argb & 0xFF);          // B
                data[i + 3] = (byte)((argb >> 24) & 0xFF);  // A
            }
        }
        return data;
    }

    // ===== BufferedImage 转 NativeImage =====
    private static NativeImage nativeImageFromBufferedImage(BufferedImage image) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();
        NativeImage ni = new NativeImage(w, h, true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                ni.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return ni;
    }

    /** 计算缩放尺寸，限制最大 maxW×maxH，保持宽高比，确保偶数 */
    private static int[] calcScaledDimensions(int w, int h, int maxW, int maxH) {
        if (w <= maxW && h <= maxH) return new int[]{w, h};
        float scale = Math.min((float) maxW / w, (float) maxH / h);
        int sw = Math.round(w * scale);
        int sh = Math.round(h * scale);
        if (sw % 2 != 0) sw++;
        if (sh % 2 != 0) sh++;
        return new int[]{sw, sh};
    }

    // ===== 每帧更新 =====
    public void tick() {
        if (!initialized) return;

        // 延迟加载壁纸（等游戏完全启动，OpenGL 初始化完毕）
        if (loadDelayTicks > 0) {
            loadDelayTicks--;
            return;
        }
        if (loadDelayTicks == 0) {
            loadDelayTicks = -1; // 只执行一次
            loadWallpapers();
            if (!wallpaperIds.isEmpty()) {
                currentIndex = random.nextInt(wallpaperIds.size());
                // 如果当前壁纸是视频，启动播放器
                if (hasVideoPlayer) {
                    switchVideoPlayer(-1, currentIndex);
                }
            }
            LOGGER.info("壁纸加载完成，共 {} 张", wallpaperIds.size());
        }

        long now = System.currentTimeMillis();
        long idleTimeMs = now - lastInputTime;
        boolean active = idleTimeMs < (AFK_TICKS * 50); // 5分钟对应的毫秒

        if (active) {
            // 用户活跃 - 淡出壁纸
            if (isAfk) {
                // 刚从 AFK 恢复
                isAfk = false;
                activityStartTime = now;
                // 立即将透明度降到 50
                currentOpacity = Math.min(currentOpacity, 50f);
            }

            if (currentOpacity > 0) {
                if (activityStartTime > 0) {
                    long elapsed = now - activityStartTime;
                    // 从当前值（最多50）在 FADE_OUT_MS 毫秒内降到 0
                    float progress = Math.min(1f, (float) elapsed / FADE_OUT_MS);
                    float startOpacity = 50f;
                    currentOpacity = startOpacity * (1f - progress);
                    if (currentOpacity <= 0.5f) {
                        currentOpacity = 0;
                    }
                }
            }
        } else {
            // 用户 AFK - 淡入壁纸
            if (!isAfk) {
                isAfk = true;
                afkStartTime = now;
                wasAfk = false;
            }

            long elapsed = now - afkStartTime;

            if (elapsed < FADE_TO_HALF_MS) {
                // 阶段 1：0 → 50
                float progress = (float) elapsed / FADE_TO_HALF_MS;
                currentOpacity = progress * 50f;
            } else if (elapsed < FADE_TO_HALF_MS + FADE_HALF_TO_FULL_MS) {
                // 阶段 2：50 → 100
                float progress = (float) (elapsed - FADE_TO_HALF_MS) / FADE_HALF_TO_FULL_MS;
                currentOpacity = 50f + progress * 50f;
            } else {
                currentOpacity = 100f;
            }
        }

        wasAfk = isAfk;

        // 更新视频帧（tick 速率 ≈20Hz 的保底更新）
        // 主要更新由 renderMenuWallpaper / renderAfkOverlay 在渲染循环中驱动（≈60Hz）
        if (hasVideoPlayer && currentIndex >= 0) {
            VideoPlayer player = videoPlayers.get(currentIndex);
            if (player != null) {
                player.tick();
            }
        }
    }

    // ===== 输入通知 =====
    public void onInput() {
        lastInputTime = System.currentTimeMillis();
    }

    // ===== 渲染：主菜单壁纸 =====
    public void renderMenuWallpaper(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (!initialized || wallpaperIds.isEmpty() || currentIndex < 0) return;

        // 在渲染前检查新帧（60fps 检查频率，实际只有新帧到达时才 upload，约 30fps）
        updateVideoFrame();

        ResourceLocation wallpaper = wallpaperIds.get(currentIndex);
        int texW = currentIndex < texWidths.size() ? texWidths.get(currentIndex) : screenWidth;
        int texH = currentIndex < texHeights.size() ? texHeights.get(currentIndex) : screenHeight;
        if (texW <= 0 || texH <= 0) { texW = screenWidth; texH = screenHeight; }

        // 先画实心黑色背景（防止 ModernUI/Sodium 下 disableBlend 失效导致泥土透出）
        guiGraphics.fill(0, 0, screenWidth, screenHeight, 0xFF000000);

        // 使用 cover 方式填充屏幕（保持宽高比，裁剪多余部分）
        renderCoverFit(guiGraphics, wallpaper, 0, 0, screenWidth, screenHeight, texW, texH);
    }

    // ===== 渲染：游戏内 AFK 叠加层 =====
    public void renderAfkOverlay(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        // 先更新视频帧（不受透明度影响，确保游戏中活跃时也能 60fps 更新）
        updateVideoFrame();

        if (!initialized || currentOpacity <= 0 || wallpaperIds.isEmpty() || currentIndex < 0) return;

        // 壁纸完全透明时无需渲染
        if (currentOpacity <= 0) return;

        ResourceLocation wallpaper = wallpaperIds.get(currentIndex);
        int texW = currentIndex < texWidths.size() ? texWidths.get(currentIndex) : screenWidth;
        int texH = currentIndex < texHeights.size() ? texHeights.get(currentIndex) : screenHeight;
        if (texW <= 0 || texH <= 0) { texW = screenWidth; texH = screenHeight; }

        float alpha = currentOpacity / 100f;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);

        renderCoverFit(guiGraphics, wallpaper, 0, 0, screenWidth, screenHeight, texW, texH);

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.defaultBlendFunc();
    }

    /**
     * 以 cover 方式渲染纹理（保持宽高比，填满目标区域，多余部分裁剪）
     */
    private void renderCoverFit(GuiGraphics guiGraphics, ResourceLocation tex,
                                int dx, int dy, int dw, int dh, int tw, int th) {
        if (tw <= 0 || th <= 0) return;
        float scale = Math.max((float) dw / tw, (float) dh / th);
        int sw = (int) (tw * scale);
        int sh = (int) (th * scale);
        int sx = dx + (dw - sw) / 2;
        int sy = dy + (dh - sh) / 2;
        // 使用 float UV 版本 blit，将整个纹理缩放到 (sx,sy,sw,sh) 区域
        guiGraphics.blit(tex, sx, sy, sw, sh, 0.0f, 0.0f, tw, th, tw, th);
    }

    // ===== 资源重载后恢复纹理 =====
    public void reloadTextures() {
        if (wallpaperIds.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();

        for (int i = 0; i < wallpaperIds.size(); i++) {
            try {
                byte[] pixelData = wallpaperPixelData.get(i);
                int w = texWidths.get(i);
                int h = texHeights.get(i);

                NativeImage ni = new NativeImage(w, h, true);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int pixelIdx = (y * w + x) * 4;
                        int r = pixelData[pixelIdx] & 0xFF;
                        int g = pixelData[pixelIdx + 1] & 0xFF;
                        int b = pixelData[pixelIdx + 2] & 0xFF;
                        int a = pixelData[pixelIdx + 3] & 0xFF;
                        ni.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                    }
                }

                DynamicTexture texture = new DynamicTexture(ni);
                mc.getTextureManager().register(wallpaperIds.get(i), texture);

                // 更新视频播放器的纹理引用
                VideoPlayer player = videoPlayers.get(i);
                if (player != null) {
                    player.updateTexture(texture);
                }
            } catch (Exception e) {
                LOGGER.error("恢复壁纸纹理失败 #{}", i, e);
            }
        }
        LOGGER.info("壁纸纹理已重新注册（共 {} 张）", wallpaperIds.size());
    }

    // ===== 切换壁纸（含视频切换） =====
    public void nextWallpaper() {
        if (wallpaperIds.size() <= 1) return;
        int oldIndex = currentIndex;
        currentIndex = (currentIndex + 1) % wallpaperIds.size();
        switchVideoPlayer(oldIndex, currentIndex);
        LOGGER.info("切换到壁纸 #{}: {}", currentIndex, wallpaperPaths.get(currentIndex).getFileName());
    }

    // ===== 选择壁纸（含视频切换） =====
    public void selectWallpaper(int index) {
        if (index >= 0 && index < wallpaperIds.size()) {
            int oldIndex = currentIndex;
            currentIndex = index;
            switchVideoPlayer(oldIndex, currentIndex);
            LOGGER.info("选择壁纸 #{}: {}", index, wallpaperPaths.get(index).getFileName());
        }
    }

    /** 在渲染循环中更新当前视频帧（在渲染线程调用，达到 60fps） */
    public void updateVideoFrame() {
        if (hasVideoPlayer && currentIndex >= 0) {
            VideoPlayer player = videoPlayers.get(currentIndex);
            if (player != null) {
                player.tick();
            }
        }
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public ResourceLocation getWallpaperId(int index) {
        if (index >= 0 && index < wallpaperIds.size()) {
            return wallpaperIds.get(index);
        }
        return null;
    }

    public int getWallpaperWidth(int index) {
        if (index >= 0 && index < texWidths.size()) {
            return texWidths.get(index);
        }
        return 0;
    }

    public int getWallpaperHeight(int index) {
        if (index >= 0 && index < texHeights.size()) {
            return texHeights.get(index);
        }
        return 0;
    }

    public boolean isVideoWallpaper(int index) {
        return index >= 0 && videoPlayers.containsKey(index);
    }

    public String getWallpaperName(int index) {
        if (index >= 0 && index < wallpaperPaths.size()) {
            return wallpaperPaths.get(index).getFileName().toString();
        }
        return "未知";
    }

    /** 切换视频播放器：停止旧的，启动新的 */
    private void switchVideoPlayer(int oldIndex, int newIndex) {
        if (oldIndex >= 0 && oldIndex != newIndex) {
            VideoPlayer old = videoPlayers.get(oldIndex);
            if (old != null) old.stop();
        }
        if (newIndex >= 0) {
            VideoPlayer next = videoPlayers.get(newIndex);
            if (next != null) next.start();
        }
    }

    // ===== Getters =====
    public boolean isAfk() {
        return isAfk;
    }

    public float getCurrentOpacity() {
        return currentOpacity;
    }

    public boolean hasWallpapers() {
        return !wallpaperIds.isEmpty();
    }

    public int getWallpaperCount() {
        return wallpaperIds.size();
    }

    // ===== 播放速度 =====

    public float getPlaySpeed() {
        return ModConfig.getInstance().getPlaySpeed();
    }

    public void setPlaySpeed(float speed) {
        ModConfig.getInstance().setPlaySpeed(speed);
        // 通知所有视频播放器更新帧间隔
        for (VideoPlayer player : videoPlayers.values()) {
            player.updateSpeed(speed);
        }
    }

    // =================================================================
    //  视频播放器（ffmpeg 管道 → 逐帧解码）
    // =================================================================

    /**
     * 单视频播放器。
     * 在后台线程中通过 ffmpeg 管道读取原始 RGBA 帧（已缩放），
     * 在主线程 {@link #tick()} 中更新 {@link DynamicTexture}。
     */
    private static class VideoPlayer {
        private static final Logger LOG = LoggerFactory.getLogger("halei-mod/video");

        private final Path videoPath;
        private final int width;
        private final int height;
        private final double fps;
        private DynamicTexture texture;
        private final ResourceLocation id;

        private volatile boolean running = false;
        private Thread decodeThread;
        private Process ffmpegProcess;

        /** 最新一帧的 RGBA 数据，由解码线程写入，由主线程消费 */
        private byte[] pendingFrame = null;
        private final Object frameLock = new Object();

        /** 双 PBO 用于异步纹理上传（ping-pong 避免阻塞） */
        private final int[] pboIds = new int[]{-1, -1};
        private int currentPboIndex = 0;

        /** 解码线程帧计数器（用于调试） */
        private long decodedFrames = 0;
        /** 上游帧间隔（毫秒），由速度倍率动态计算 */
        private volatile long frameIntervalMs;

        VideoPlayer(Path videoPath, int width, int height, double fps, DynamicTexture texture, ResourceLocation id) {
            this.videoPath = videoPath;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.texture = texture;
            this.id = id;
            // 根据配置速度倍率计算帧间隔
            updateSpeed(ModConfig.getInstance().getPlaySpeed());
        }

        /** 更新速度倍率，动态调整帧间隔 */
        void updateSpeed(float speed) {
            long interval = fps > 0 ? (long) (1000.0 / fps / speed) : 33;
            this.frameIntervalMs = Math.max(interval, 4);
        }

        /** 更新纹理引用（资源重载后调用） */
        void updateTexture(DynamicTexture newTexture) {
            this.texture = newTexture;
        }

        /** 启动解码线程（幂等） */
        void start() {
            if (running) return;
            running = true;

            // 初始化 PBO（异步纹理上传，不阻塞渲染管线）
            initPBO();

            decodeThread = new Thread(this::decodeLoop, "halei-video-" + id.getPath().replace('/', '-'));
            decodeThread.setDaemon(true);
            decodeThread.start();
        }

        /** 停止解码 */
        void stop() {
            running = false;
            cleanupPBO();
            if (ffmpegProcess != null) {
                ffmpegProcess.destroy();
                ffmpegProcess = null;
            }
            if (decodeThread != null) {
                decodeThread.interrupt();
                decodeThread = null;
            }
        }

        /** 主线程每帧调用（从渲染循环 ≈60Hz 调用）
         *  通过 PBO 异步上传新帧到 GPU，不阻塞渲染 */
        void tick() {
            synchronized (frameLock) {
                if (pendingFrame == null) return;
                uploadWithPBO(pendingFrame);
                pendingFrame = null;
            }
        }

        // ===== PBO 异步纹理上传 =====

        /** 创建双 PBO 缓冲区（ping-pong 异步上传） */
        private void initPBO() {
            try {
                pboIds[0] = GL15.glGenBuffers();
                pboIds[1] = GL15.glGenBuffers();
                int size = width * height * 4;
                for (int pbo : pboIds) {
                    GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
                    GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, (long) size, GL15.GL_STREAM_DRAW);
                }
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                currentPboIndex = 0;
                LOG.debug("双 PBO 已创建 ({}x{}, {} bytes, ping-pong)", width, height, size);
            } catch (Exception e) {
                LOG.error("创建 PBO 失败: {}", videoPath.getFileName(), e);
                pboIds[0] = -1;
                pboIds[1] = -1;
            }
        }

        /**
         * 通过 PBO 将帧数据异步上传到 GPU 纹理。
         * 使用双缓冲 ping-pong + glMapBuffer(GL_WRITE_ONLY)，
         * 映射失败时跳过上传以避免 GL_INVALID_OPERATION。
         */
        private void uploadWithPBO(byte[] frameData) {
            if (pboIds[0] == -1 || pboIds[1] == -1) return;

            int pboId = pboIds[currentPboIndex];
            int texId = texture.getId();
            int size = width * height * 4;

            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pboId);

                // 孤立旧存储：GPU 继续使用旧缓冲区，新分配存储供我们写入
                GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, (long) size, GL15.GL_STREAM_DRAW);

                // 映射 PBO 写入像素数据（若失败则跳过本轮上传）
                ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, GL15.GL_WRITE_ONLY);
                if (mapped != null) {
                    mapped.put(frameData);
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);

                    // 从 PBO 异步上传到纹理——立即返回，GPU 后台 DMA 传输
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
                    GL21.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
                } else {
                    // 映射失败时回退：直接上传（非 PBO 路径）
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(frameData));
                }

                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

                // ping-pong：下一帧换另一个 PBO
                currentPboIndex ^= 1;
            } catch (Exception e) {
                LOG.error("PBO 纹理上传失败: {}", videoPath.getFileName(), e);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            }
        }

        /** 销毁双 PBO */
        private void cleanupPBO() {
            for (int pbo : pboIds) {
                if (pbo != -1) {
                    try {
                        GL15.glDeleteBuffers(pbo);
                    } catch (Exception ignored) {}
                }
            }
            pboIds[0] = -1;
            pboIds[1] = -1;
        }

        /** 解码线程主循环 —— 带缩放 + 帧率节流 */
        private void decodeLoop() {
            try {
                String ffmpeg = WallpaperManager.getInstance().ffmpegPath;
                if (ffmpeg == null || ffmpeg.isEmpty()) return;

                // ffmpeg 输出缩放后的 RGBA 帧，由 Java 侧的帧间隔控制播放速度
                ProcessBuilder pb = new ProcessBuilder(
                        ffmpeg,
                        "-loglevel", "quiet",
                        "-stream_loop", "-1",
                        "-i", videoPath.toAbsolutePath().toString(),
                        "-vf", "scale=" + width + ":" + height,
                        "-f", "rawvideo",
                        "-pix_fmt", "rgba",
                        "-an", "-sn", "-dn",
                        "-"
                );
                ffmpegProcess = pb.start();
                InputStream in = ffmpegProcess.getInputStream();
                int frameSize = width * height * 4;
                byte[] buf = new byte[frameSize];
                decodedFrames = 0;

                // 预分配帧数据缓冲区
                byte[] frameData = new byte[frameSize];

                while (running) {
                    long frameStart = System.currentTimeMillis();

                    // 读取一帧完整的 RGBA 数据
                    int totalRead = 0;
                    while (totalRead < frameSize) {
                        int read = in.read(buf, totalRead, frameSize - totalRead);
                        if (read < 0) {
                            LOG.info("视频播放完毕: {}", videoPath.getFileName());
                            running = false;
                            return;
                        }
                        totalRead += read;
                    }
                    decodedFrames++;

                    // 引用交换：无需拷贝，直接把 buf 丢给主线程，buf 换成预分配的空闲缓冲区
                    synchronized (frameLock) {
                        if (pendingFrame == null) {
                            pendingFrame = buf;
                            buf = frameData;
                            frameData = null;
                        }
                        // 有积压则直接跳过此帧
                    }

                    // 如果帧数据被消费了（frameData 被换走），需要重新分配
                    if (frameData == null) {
                        frameData = new byte[frameSize];
                    }

                    // 帧率节流：帧间隔已减半以实现 2 倍速播放
                    // 保留少量休眠避免解码线程空转占 CPU
                    long elapsed = System.currentTimeMillis() - frameStart;
                    long toSleep = frameIntervalMs - elapsed;
                    if (toSleep > 5) {
                        Thread.sleep(toSleep / 2); // 减半休眠，由 Java 侧帧间隔控制帧率
                    }
                }
            } catch (IOException e) {
                if (running) {
                    LOG.error("视频解码失败: {}", videoPath.getFileName(), e);
                }
            } catch (InterruptedException ignored) {
            } finally {
                running = false;
                if (ffmpegProcess != null) {
                    ffmpegProcess.destroy();
                    ffmpegProcess = null;
                }
            }
        }
    }
}
