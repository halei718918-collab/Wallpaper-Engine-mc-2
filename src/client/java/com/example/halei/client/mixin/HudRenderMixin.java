package com.example.halei.client.mixin;

import com.example.halei.client.WallpaperManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 游戏内 HUD 渲染 Mixin
 * 在 HUD 渲染后叠加 AFK 壁纸层，实现从透明→半透明→不透明的渐变效果。
 */
@Mixin(Gui.class)
public class HudRenderMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void halei_renderAfkOverlay(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        WallpaperManager manager = WallpaperManager.getInstance();
        if (!manager.hasWallpapers()) return;

        int width = guiGraphics.guiWidth();
        int height = guiGraphics.guiHeight();
        manager.renderAfkOverlay(guiGraphics, width, height);
    }
}
