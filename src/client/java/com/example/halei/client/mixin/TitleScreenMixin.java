package com.example.halei.client.mixin;

import com.example.halei.client.WallpaperManager;
import com.example.halei.client.WallpaperSelectionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 主菜单壁纸 Mixin
 * 在 TitleScreen 渲染时将壁纸作为背景显示。
 * 使用多重保证：1) @Inject render HEAD 先绘制壁纸
 * 2) @Redirect 阻止全景背景绘制覆盖
 * 3) @Inject renderBackground HEAD 拦截 TitleScreen 的 renderBackground 重写
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    /**
     * 在渲染一开始就绘制壁纸，确保壁纸在最底层。
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void halei_drawWallpaper(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        WallpaperManager manager = WallpaperManager.getInstance();
        if (manager.hasWallpapers()) {
            int width = guiGraphics.guiWidth();
            int height = guiGraphics.guiHeight();
            manager.renderMenuWallpaper(guiGraphics, width, height);
        }
    }

    /**
     * 阻止原版全景背景绘制，防止覆盖我们的壁纸。
     */
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/TitleScreen;renderPanorama(Lnet/minecraft/client/gui/GuiGraphics;F)V"
            )
    )
    private void halei_noopPanorama(TitleScreen instance, GuiGraphics guiGraphics, float partialTick) {
        // 当有壁纸时，不渲染全景背景
        if (!WallpaperManager.getInstance().hasWallpapers()) {
            // 没有壁纸时调用原版全景渲染
            ((TitleScreenInvoker)(Object)this).halei_invokeRenderPanorama(guiGraphics, partialTick);
        }
        // 有壁纸时什么都不做（跳过全景渲染）
    }

    /**
     * TitleScreen 重写了 renderBackground，所以 @Mixin(Screen.class) 的 ScreenBackgroundMixin 不生效。
     * 这里在 TitleScreen.renderBackground 时也绘制壁纸并取消默认渲染。
     */
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void halei_drawWallpaperBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        WallpaperManager manager = WallpaperManager.getInstance();
        if (manager.hasWallpapers()) {
            int width = guiGraphics.guiWidth();
            int height = guiGraphics.guiHeight();
            manager.renderMenuWallpaper(guiGraphics, width, height);
            ci.cancel();
        }
    }

    /**
     * 在 TitleScreen 初始化完成后，在右下角添加一个小正方形「壁」按钮，
     * 靠近辅助功能按钮，点击打开壁纸选择界面。
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void halei_addWallpaperButton(CallbackInfo ci) {
        int btnSize = 20;
        Button wallpaperBtn = Button.builder(
                Component.literal("壁"),
                button -> Minecraft.getInstance().setScreen(new WallpaperSelectionScreen())
        ).bounds(
                ((ScreenAccessor) this).getWidth() - btnSize - 4,
                ((ScreenAccessor) this).getHeight() - btnSize - 4,
                btnSize,
                btnSize
        ).build();
        ((ScreenAccessor) this).invokeAddRenderableWidget(wallpaperBtn);
    }
}
