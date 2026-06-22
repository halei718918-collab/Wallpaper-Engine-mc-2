package com.example.halei.client.mixin;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 Screen 的字段和方法，供其他 Mixin 调用。
 */
@Mixin(Screen.class)
public interface ScreenAccessor {

    @Accessor("width")
    int getWidth();

    @Accessor("height")
    int getHeight();

    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry> T invokeAddRenderableWidget(T widget);
}
