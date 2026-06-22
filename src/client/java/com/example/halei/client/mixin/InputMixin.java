package com.example.halei.client.mixin;

import com.example.halei.client.WallpaperManager;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鼠标输入 Mixin
 * 监听鼠标移动和点击事件，用于 AFK 检测。
 */
@Mixin(MouseHandler.class)
public class InputMixin {

    /**
     * 鼠标移动时重置 AFK 计时器
     */
    @Inject(method = "onMove", at = @At("HEAD"))
    private void halei_onMouseMove(long window, double x, double y, CallbackInfo ci) {
        WallpaperManager.getInstance().onInput();
    }

    /**
     * 鼠标点击时重置 AFK 计时器
     */
    @Inject(method = "onPress", at = @At("HEAD"))
    private void halei_onMousePress(long window, int button, int action, int mods, CallbackInfo ci) {
        WallpaperManager.getInstance().onInput();
    }

    /**
     * 鼠标滚轮时重置 AFK 计时器
     */
    @Inject(method = "onScroll", at = @At("HEAD"))
    private void halei_onMouseScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        WallpaperManager.getInstance().onInput();
    }
}
