package space.essem.image2map.config;

import me.sargunvohra.mcmods.autoconfig1u.ConfigData;
import me.sargunvohra.mcmods.autoconfig1u.annotation.Config;
import me.sargunvohra.mcmods.autoconfig1u.shadowed.blue.endless.jankson.Comment;

@Config(name = "image2map")
public class Image2MapConfig implements ConfigData {
  @Comment(value = "When enabled, the mod can access files hosted on the server")
  public boolean allowLocalFiles = false;

  @Comment(value = "Specifies the needed permission level to use the /mapcreate command")
  public int minPermLevel = 2;

  @Comment(value = "When enabled, the server will log whenever a player creates a map.")
  public boolean enableLogging = true;
}
