package space.essem.image2map.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import blue.endless.jankson.Comment;

@Config(name = "image2map")
public class Image2MapConfig implements ConfigData {
  @Comment(value = "When enabled, the mod can access files hosted on the server")
  public boolean allowLocalFiles = false;

  @Comment(value = "Specifies the needed permission level to use the /mapcreate command")
  public int minPermLevel = 2;
}
