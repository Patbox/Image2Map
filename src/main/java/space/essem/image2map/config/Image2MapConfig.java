package space.essem.image2map.config;

import me.sargunvohra.mcmods.autoconfig1u.ConfigData;
import me.sargunvohra.mcmods.autoconfig1u.annotation.Config;
import me.sargunvohra.mcmods.autoconfig1u.shadowed.blue.endless.jankson.Comment;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundTag;

@Config(name = "image2map")
public class Image2MapConfig implements ConfigData {
  @Comment(value = "When enabled, the mod can access files hosted on the server")
  public boolean allowLocalFiles = false;

  @Comment(value = "Specifies the needed permission level to use the /mapcreate command")
  public int minPermLevel = 2;
  
  @Comment(value = "The ingredient to be held in the player's main hand when creating a map")
  public CompoundTag mainHandIngredient = new ItemStack(Items.MAP, 1).toTag(new CompoundTag());
  
  @Comment(value = "The ingredient to be held in the player's off hand when creating a map")
  public CompoundTag offHandIngredient = new ItemStack(Items.INK_SAC, 1).toTag(new CompoundTag());
}
