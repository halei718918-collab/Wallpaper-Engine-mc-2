package com.example.halei.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.glfw.GLFW;

public class HaleiModClient implements ClientModInitializer {

    /** 选择壁纸快捷键 (默认 K) */
    private static KeyMapping selectWallpaperKey;

    @Override
    public void onInitializeClient() {
        // 注册快捷键 K
        selectWallpaperKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.halei-mod.select_wallpaper",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.halei-mod"
        ));

        // 初始化壁纸管理器
        WallpaperManager.getInstance().init();

        // 注册资源重载监听器（材质包切换后恢复壁纸纹理）
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return ResourceLocation.fromNamespaceAndPath("halei-mod", "wallpaper_reload");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager resourceManager) {
                        WallpaperManager.getInstance().reloadTextures();
                    }
                }
        );

        // 每 tick 更新壁纸状态
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            WallpaperManager.getInstance().tick();

            // 按 K 打开壁纸选择界面
            while (selectWallpaperKey.consumeClick()) {
                if (!WallpaperManager.getInstance().hasWallpapers()) {
                    Minecraft.getInstance().player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("§e[壁纸引擎] 未找到壁纸，请将图片放入 config/wallpaper/ 文件夹"),
                            true
                    );
                    continue;
                }
                Minecraft.getInstance().setScreen(
                        new WallpaperSelectionScreen()
                );
            }
        });
    }
}