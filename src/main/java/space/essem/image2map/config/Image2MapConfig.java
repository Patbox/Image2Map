package space.essem.image2map.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.commons.io.IOUtils;
import space.essem.image2map.Image2Map;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class Image2MapConfig {
  private static final Gson GSON = new GsonBuilder()
          .disableHtmlEscaping().setLenient().setPrettyPrinting()
          .create();

  public boolean allowLocalFiles = false;

  public int minPermLevel = 2;

  public boolean allowSurvivalMode = true;


  public static Image2MapConfig loadOrCreateConfig() {
    try {
      Image2MapConfig config;
      File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "image2map.json");

      if (configFile.exists()) {
        String json = IOUtils.toString(new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8));

        config = GSON.fromJson(json, Image2MapConfig.class);
      } else {
        config = new Image2MapConfig();
      }


      saveConfig(config);
      return config;
    }
    catch(IOException exception) {
      Image2Map.LOGGER.error("Something went wrong while reading config!");
      exception.printStackTrace();
      return new Image2MapConfig();
    }
  }

  public static void saveConfig(Image2MapConfig config) {
    File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "image2map.json");
    try {
      BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8));
      writer.write(GSON.toJson(config));
      writer.close();
    } catch (Exception e) {
      Image2Map.LOGGER.error("Something went wrong while saving config!");
      e.printStackTrace();
    }
  }
}
