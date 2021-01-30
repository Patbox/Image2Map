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
  
  @Comment(value = "The item to be held in the player's main hand when creating a map")
  public String mainHandIngredientId = "minecraft:map";
  
  @Comment(value = "The amount of the main hand ingredient consumed when creating a map")
  public int mainHandIngredientAmount = 1;
  
  @Comment(value = "The ingredient to be held in the player's off hand when creating a map")
  public String offHandIngredientId = "minecraft:black_dye";
  
  @Comment(value = "The amount of the off hand ingredient consumed when creating a map")
  public int offHandIngredientAmount = 0;
}
