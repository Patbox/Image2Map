package space.essem.image2map.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import space.essem.image2map.Image2Map;
import space.essem.image2map.image.ImageSafety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Image2MapConfig {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    public int version = 2;
    public boolean allowServerLocalFiles = false;
    public boolean allowClientUploadFiles = true;
    public int minPermLevel = 2;
    public int imageMaxWidthHeight = 2048;
    public int imageFileMaxSize = 20480; // KiB, encoded file size (20 MiB)
    public List<String> allowedImageFormats = List.of("png", "jpeg", "gif", "bmp", "webp");
    public int imageOperationCooldownSeconds = 5;
    public int networkTimeout = 30;

    public ImageSafety.Limits imageLimits() {
        return new ImageSafety.Limits(imageFileMaxSize * 1024L, allowedImageFormats);
    }

    public static Image2MapConfig fromJson(String json) {
        var object = JsonParser.parseString(json).getAsJsonObject();
        ConfigMigration.migrate(object);
        Image2MapConfig config = GSON.fromJson(object, Image2MapConfig.class);
        config.validate();
        return config;
    }

    private void validate() {
        if (version != 2 || minPermLevel < 0 || minPermLevel > 4 || imageMaxWidthHeight < 1
                || imageMaxWidthHeight > 16384 || imageFileMaxSize < 1 || imageFileMaxSize > 262144
                || imageOperationCooldownSeconds < 0 || networkTimeout < 1 || networkTimeout > 3600
                || allowedImageFormats == null || allowedImageFormats.isEmpty() || allowedImageFormats.size() > 32
                || allowedImageFormats.stream().anyMatch(x -> x == null || !x.matches("[A-Za-z0-9]{1,32}"))) {
            throw new IllegalArgumentException("Invalid image2map configuration limits or version");
        }
    }

    public static Image2MapConfig loadOrCreateConfig() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("image2map.json");
        try {
            if (!Files.exists(path)) {
                Image2MapConfig config = new Image2MapConfig();
                saveConfig(config);
                return config;
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            boolean needsMigration = !JsonParser.parseString(json).getAsJsonObject().has("version");
            Image2MapConfig config = fromJson(json);
            if (needsMigration) {
                Path backup = path.resolveSibling("image2map.json.v1.bak");
                if (!Files.exists(backup)) Files.copy(path, backup);
                saveConfig(config);
            }
            return config;
        } catch (IOException | RuntimeException exception) {
            Image2Map.LOGGER.error("Failed to read image2map config; using defaults and preserving the file", exception);
            return new Image2MapConfig();
        }
    }

    public static void saveConfig(Image2MapConfig config) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("image2map.json");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            Image2Map.LOGGER.error("Failed to save image2map config", exception);
        }
    }
}
