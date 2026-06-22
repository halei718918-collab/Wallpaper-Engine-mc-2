package com.example.halei.client.mixin;

import com.example.halei.client.WallpaperManager;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 键盘输入 Mixin
 * 监听键盘按键事件，用于 AFK 检测。
 */
@Mixin(KeyboardHandler.class)
public class KeyboardInputMixin {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void halei_onKeyPress(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        WallpaperManager.getInstance().onInput();
    }
}
