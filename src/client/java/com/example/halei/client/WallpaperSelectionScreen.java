package com.example.halei.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.narration.NarrationElementOutput;

/**
 * 壁纸选择界面
 * 按 K 键打开，列出所有可用壁纸（缩略图预览）供玩家选择。
 */
public class WallpaperSelectionScreen extends Screen {

    private static final Component TITLE = Component.translatable("screen.halei-mod.select_wallpaper");

    /** 缩略图宽度 */
    private static final int THUMB_W = 100;
    /** 缩略图高度 */
    private static final int THUMB_H = 56;
    /** 每行总高度 */
    private static final int ENTRY_H = 70;

    private WallpaperList wallpaperList;
    private Button selectButton;
    private SpeedSlider speedSlider;

    public WallpaperSelectionScreen() {
        super(TITLE);
    }

    @Override
    protected void init() {
        WallpaperManager manager = WallpaperManager.getInstance();

        // 速度滑块（居中对齐）
        int sliderW = 200;
        speedSlider = new SpeedSlider(width / 2 - sliderW / 2, 24, sliderW, 20);
        addRenderableWidget(speedSlider);

        // 创建壁纸列表（下移为滑块腾出空间）
        wallpaperList = new WallpaperList(width, height, 50, height - 40);
        for (int i = 0; i < manager.getWallpaperCount(); i++) {
            wallpaperList.addWallpaperEntry(wallpaperList.new Entry(i));
        }
        addRenderableWidget(wallpaperList);

        // 选择按钮
        selectButton = Button.builder(
                Component.translatable("screen.halei-mod.select"),
                button -> selectCurrent()
        ).bounds(width / 2 - 102, height - 30, 100, 20).build();
        selectButton.active = false;
        addRenderableWidget(selectButton);

        // 关闭按钮
        addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                button -> onClose()
        ).bounds(width / 2 + 2, height - 30, 100, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        WallpaperManager mgr = WallpaperManager.getInstance();
        if (mgr.hasWallpapers()) {
            // 使用统一的壁纸渲染方法（含实心黑色背景，防止泥土透过半透明壁纸显示）
            mgr.renderMenuWallpaper(guiGraphics, width, height);
            // 半透明暗色叠加层，提高 UI 可读性
            guiGraphics.fill(0, 0, width, height, 0x88000000);
        } else {
            super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 绘制标题
        guiGraphics.drawCenteredString(font, TITLE, width / 2, 16, 0xFFFFFF);

        // 绘制底部提示
        guiGraphics.drawCenteredString(font,
                Component.translatable("screen.halei-mod.hint"),
                width / 2, height - 50, 0x888888);
    }

    private void selectCurrent() {
        WallpaperList.Entry entry = wallpaperList.getSelected();
        if (entry != null) {
            WallpaperManager.getInstance().selectWallpaper(entry.index);
            onClose();
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    // ===== 壁纸列表组件（内含列表项 Entry） =====
    private class WallpaperList extends AbstractSelectionList<WallpaperList.Entry> {

        public WallpaperList(int width, int height, int y0, int y1) {
            super(Minecraft.getInstance(), width, height, y0, y1);
        }

        public void addWallpaperEntry(Entry entry) {
            addEntry(entry);
        }

        @Override
        public int getRowWidth() {
            return Math.min(420, this.width - 50);
        }

        @Override
        protected int getScrollbarPosition() {
            return this.getRight() - 6;
        }

        @Override
        public void setSelected(Entry entry) {
            super.setSelected(entry);
            if (selectButton != null) {
                selectButton.active = entry != null;
            }
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            Entry selected = getSelected();
            if (selected != null) {
                String name = WallpaperManager.getInstance().getWallpaperName(selected.index);
                int dot = name.lastIndexOf('.');
                if (dot > 0) name = name.substring(0, dot);
                narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                        Component.literal(name));
            }
        }

        // ===== 壁纸列表项（含缩略图） =====
        class Entry extends AbstractSelectionList.Entry<Entry> {
            final int index;
            private final String displayName;
            private final ResourceLocation textureId;

            Entry(int index) {
                this.index = index;
                WallpaperManager mgr = WallpaperManager.getInstance();
                String fileName = mgr.getWallpaperName(index);
                String name = fileName;
                int dot = name.lastIndexOf('.');
                if (dot > 0) name = name.substring(0, dot);
                this.displayName = name;
                this.textureId = mgr.getWallpaperId(index);
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int y, int x, int entryWidth, int entryHeight,
                               int mouseX, int mouseY, boolean hovered, float partialTick) {
                WallpaperManager mgr = WallpaperManager.getInstance();

                // ---- 缩略图 ----
                int thumbX = x + 8;
                int thumbY = y + (entryHeight - THUMB_H) / 2;

                if (textureId != null) {
                    // 绘制缩略图背景（黑色边框）
                    guiGraphics.fill(thumbX - 1, thumbY - 1,
                            thumbX + THUMB_W + 1, thumbY + THUMB_H + 1,
                            0xFF444444);
                    // 绘制壁纸缩略图（正确缩放 texture 到 THUMB_W×THUMB_H）
                    int tw = mgr.getWallpaperWidth(this.index);
                    int th = mgr.getWallpaperHeight(this.index);
                    if (tw <= 0) tw = THUMB_W;
                    if (th <= 0) th = THUMB_H;
                    guiGraphics.blit(textureId,
                            thumbX, thumbY, THUMB_W, THUMB_H,
                            0.0f, 0.0f, tw, th, tw, th);
                } else {
                    // 无纹理时显示占位
                    guiGraphics.fill(thumbX, thumbY,
                            thumbX + THUMB_W, thumbY + THUMB_H,
                            0xFF333333);
                    guiGraphics.drawCenteredString(Minecraft.getInstance().font, "?",
                            thumbX + THUMB_W / 2, thumbY + THUMB_H / 2 - 4, 0x888888);
                }

                // ---- 文件名 ----
                int textX = thumbX + THUMB_W + 12;
                int textY = thumbY + 6;

                boolean isCurrent = (this.index == mgr.getCurrentIndex());
                boolean isVideo = mgr.isVideoWallpaper(this.index);

                // 文件名
                String label = isCurrent ? "▶ " + displayName : displayName;
                int nameColor = isCurrent ? 0x55FF55 : (hovered ? 0xFFFFFF : 0xCCCCCC);
                guiGraphics.drawString(Minecraft.getInstance().font, label,
                        textX, textY, nameColor);

                // 视频标记
                if (isVideo) {
                    guiGraphics.drawString(Minecraft.getInstance().font,
                            "§b[VIDEO]", textX, textY + 12, 0x55FFFF);
                }

                // 尺寸信息
                int tw = mgr.getWallpaperWidth(this.index);
                int th = mgr.getWallpaperHeight(this.index);
                if (tw > 0 && th > 0) {
                    guiGraphics.drawString(Minecraft.getInstance().font,
                            tw + "x" + th, textX, textY + (isVideo ? 24 : 12),
                            0x888888);
                }

                // 当前选中标记
                if (isCurrent) {
                    guiGraphics.drawString(Minecraft.getInstance().font,
                            "§a✓ 当前", textX, textY + (isVideo ? 36 : 24),
                            0x55FF55);
                }
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    wallpaperList.setSelected(this);
                    if (selectButton != null && selectButton.active) {
                        selectCurrent();
                    }
                    return true;
                }
                return false;
            }
        }
    }

    // ===== 播放速度滑块 =====

    private class SpeedSlider extends AbstractSliderButton {
        SpeedSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.literal(""), 0.0);
            float speed = WallpaperManager.getInstance().getPlaySpeed();
            this.value = net.minecraft.util.Mth.clamp((speed - 0.5f) / 4.5f, 0.0, 1.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float speed = 0.5f + (float) this.value * 4.5f;
            setMessage(Component.literal(
                    String.format(java.util.Locale.US, "播放速度: %.1fx", speed)));
        }

        @Override
        protected void applyValue() {
            float speed = 0.5f + (float) this.value * 4.5f;
            WallpaperManager.getInstance().setPlaySpeed(speed);
        }
    }
}
