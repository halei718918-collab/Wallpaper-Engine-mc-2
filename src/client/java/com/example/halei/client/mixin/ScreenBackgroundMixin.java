package com.example.halei.client.mixin;

import com.example.halei.client.WallpaperManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 所有菜单界面的壁纸背景 Mixin
 * 在 Screen.renderBackground 中渲染壁纸，覆盖默认的泥土背景。
 * 覆盖范围：世界选择、多人游戏、设置等所有菜单界面。
 * 不干扰 TitleScreen（由 TitleScreenMixin 单独处理）。
 * 注意：仅在未进入游戏时渲染壁纸，游戏中打开的界面（如暂停菜单）
 * 保留默认的半透明背景，让游戏画面正常显示。
 */
@Mixin(Screen.class)
public class ScreenBackgroundMixin {

    /**
     * 在渲染背景时绘制壁纸，并取消默认背景渲染。
     * 当玩家在游戏中（level != null）时不覆盖，保留游戏画面透出。
     */
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void halei_drawWallpaperBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        // 游戏中不覆盖背景，让游戏画面透过半透明背景显示
        if (Minecraft.getInstance().level != null) return;

        WallpaperManager manager = WallpaperManager.getInstance();
        if (manager.hasWallpapers()) {
            int width = guiGraphics.guiWidth();
            int height = guiGraphics.guiHeight();
            manager.renderMenuWallpaper(guiGraphics, width, height);
            ci.cancel();
        }
    }
}
