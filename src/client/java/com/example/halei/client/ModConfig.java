package com.example.halei.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组配置，保存到 config/halei-mod.json
 */
public class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("halei-mod/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static ModConfig instance;

    /** 播放速度倍率（0.5 ~ 5.0） */
    private float playSpeed = 2.5f;

    /** 游戏内按 K 键打开壁纸选择界面 */
    private boolean inGameKKey = true;

    public static ModConfig getInstance() {
        if (instance == null) {
            instance = new ModConfig();
            instance.load();
        }
        return instance;
    }

    public float getPlaySpeed() {
        return playSpeed;
    }

    public void setPlaySpeed(float speed) {
        this.playSpeed = Math.max(0.5f, Math.min(5.0f, speed));
        save();
    }

    public boolean isInGameKKey() {
        return inGameKKey;
    }

    public void setInGameKKey(boolean enabled) {
        this.inGameKKey = enabled;
        save();
    }

    private Path getConfigPath() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("halei-mod.json");
    }

    public void load() {
        Path path = getConfigPath();
        if (!Files.exists(path)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
            if (loaded != null) {
                this.playSpeed = loaded.playSpeed;
                this.inGameKKey = loaded.inGameKKey;
            }
        } catch (IOException e) {
            LOGGER.error("读取配置失败", e);
        }
    }

    public void save() {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.error("保存配置失败", e);
        }
    }
}
