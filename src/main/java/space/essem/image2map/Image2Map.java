package space.essem.image2map;

import net.fabricmc.api.ModInitializer;
import space.essem.image2map.config.Image2MapConfig;
import space.essem.image2map.renderer.MapRenderer;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import com.mojang.brigadier.arguments.StringArgumentType;

import me.sargunvohra.mcmods.autoconfig1u.AutoConfig;
import me.sargunvohra.mcmods.autoconfig1u.serializer.GsonConfigSerializer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;

public class Image2Map implements ModInitializer {
    public static Image2MapConfig CONFIG = AutoConfig.register(Image2MapConfig.class, GsonConfigSerializer::new).getConfig();

    @Override
    public void onInitialize() {
        System.out.println("Loading Image2Map...");

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(CommandManager.literal("mapcreate").requires(source -> source.hasPermissionLevel(CONFIG.minPermLevel))
                    .then(CommandManager.argument("path", StringArgumentType.string()).executes(context -> {
                        ServerCommandSource source = context.getSource();
                        Vec3d pos = source.getPosition();
                        PlayerEntity player = source.getPlayer();
                        String input = StringArgumentType.getString(context, "path");

                        source.sendFeedback(new LiteralText("Generating image map..."), false);
                        BufferedImage image;
                        try {
                            if (isValid(input)) {
                                URL url = new URL(input);
                                image = ImageIO.read(url);
                            } else if (CONFIG.allowLocalFiles) {
                                File file = new File(input);
                                image = ImageIO.read(file);
                            } else {
                                image = null;
                            }
                        } catch (IOException e) {
                            source.sendFeedback(new LiteralText("That doesn't seem to be a valid image."), false);
                            return 0;
                        }

                        if (image == null) {
                            source.sendFeedback(new LiteralText("That doesn't seem to be a valid image."), false);
                            return 0;
                        }

                        ItemStack stack = MapRenderer.render(image, source.getWorld(), pos.x, pos.z, player);
                        
                        boolean validIngredients = true;
                        ItemStack mainHandIngredient = new ItemStack(Registry.ITEM.get(Identifier.tryParse(CONFIG.mainHandIngredientId)), 1);
                        if (!mainHandIngredient.isEmpty()) {
                        	validIngredients = player.getMainHandStack().isItemEqual(mainHandIngredient)
                    				&& player.getMainHandStack().getCount() >= CONFIG.mainHandIngredientAmount;
                        }
                        ItemStack offHandIngredient = new ItemStack(Registry.ITEM.get(Identifier.tryParse(CONFIG.offHandIngredientId)), 1);
                        if (!offHandIngredient.isEmpty()) {
                        	validIngredients &= player.getOffHandStack().isItemEqual(offHandIngredient)
                    				&& player.getOffHandStack().getCount() >= CONFIG.offHandIngredientAmount;
                        }
                        
                        if (validIngredients) {
                        	if (!mainHandIngredient.isEmpty()) {
                        		player.getMainHandStack().split(CONFIG.mainHandIngredientAmount);
                        	}
                        	if (!offHandIngredient.isEmpty()) {
                        		player.getOffHandStack().split(CONFIG.offHandIngredientAmount);
                        	}
                        	
                        	source.sendFeedback(new LiteralText("Done!"), false);
                        	if (!player.inventory.insertStack(stack)) {
                                ItemEntity itemEntity = new ItemEntity(player.world, player.getPos().x, player.getPos().y,
                                        player.getPos().z, stack);
                                player.world.spawnEntity(itemEntity);
                            }
                        } else {
                        	StringBuilder sb = new StringBuilder("Missing crafting ingredients!\nRequired:");
                        	if (!mainHandIngredient.isEmpty()) {
                        		sb.append("\n  Main hand: ");
                        		if (CONFIG.mainHandIngredientAmount > 0) {
                        			sb.append(CONFIG.mainHandIngredientAmount + " ");
                        		}
                        		sb.append((new TranslatableText(mainHandIngredient.getTranslationKey())).getString());
                        	}
                        	if (!offHandIngredient.isEmpty()) {
                        		sb.append("\n  Off hand: ");
                        		if (CONFIG.offHandIngredientAmount > 0) {
                        			sb.append(CONFIG.offHandIngredientAmount + " ");
                        		}
                        		sb.append((new TranslatableText(offHandIngredient.getTranslationKey())).getString());
                        	}
                        	source.sendFeedback(new LiteralText(sb.toString()), false);
                        }

                        return 1;
                    })));
        });
    }

    private static boolean isValid(String url) {
        try {
            new URL(url).toURI();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
