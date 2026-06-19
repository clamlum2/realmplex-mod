package com.realmplex;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES;

public class RealmplexConfig<T> {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .setFieldNamingPolicy(LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private final String fileName;
    private final T defaults;
    private final Class<T> configClass;

    private File configFile;

    public RealmplexConfig(String fileName, T defaults, Class<T> configClass) {
        this.fileName = fileName;
        this.defaults = defaults;
        this.configClass = configClass;
    }

    public T load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        configFile = configDir.resolve(fileName).toFile();

        if (!configFile.exists()) {
            try {
                createWithDefaults();
            } catch (IOException e) {
                RealmplexMod.LOGGER.error("Failed to create config '{}', using defaults", fileName, e);
                return defaults;
            }
        }

        try {
            String json = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            T loaded = GSON.fromJson(json, configClass);
            RealmplexMod.LOGGER.debug("Loaded config '{}'", fileName);
            return loaded;
        } catch (IOException e) {
            RealmplexMod.LOGGER.error("Failed to read config '{}', using defaults", fileName, e);
            return defaults;
        }
    }


    private void createWithDefaults() throws IOException {
        if (configFile.getParentFile().mkdirs()) {
            RealmplexMod.LOGGER.debug("Created config directory");
        }
        Files.createFile(configFile.toPath());
        try (PrintWriter writer = new PrintWriter(configFile, StandardCharsets.UTF_8)) {
            writer.write(GSON.toJson(defaults));
        }
    }
}