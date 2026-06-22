package com.example.halei.client.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker mixin 用于访问 TitleScreen 的 protected 方法 renderPanorama
 */
@Mixin(TitleScreen.class)
public interface TitleScreenInvoker {
    @Invoker("renderPanorama")
    void halei_invokeRenderPanorama(GuiGraphics guiGraphics, float partialTick);
}
