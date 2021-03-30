package space.essem.image2map;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.minecraft.item.FilledMapItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.network.ServerPlayerEntity;
import space.essem.image2map.config.Image2MapConfig;
import space.essem.image2map.renderer.MapRenderer;

import java.awt.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

import java.awt.image.BufferedImage;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import me.sargunvohra.mcmods.autoconfig1u.AutoConfig;
import me.sargunvohra.mcmods.autoconfig1u.serializer.GsonConfigSerializer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;

public class Image2Map implements ModInitializer {
	private ListTag getLore(int width, int height) {
		ListTag posterLore = new ListTag();
		posterLore.add(StringTag.of("[{\"text\":\"Use me on an item frame grid at least " + width + " by " + height + " big\",\"color\":\"gold\",\"italic\":false}]"));
		posterLore.add(StringTag.of("[{\"text\":\"and I'll make a big image!\",\"color\":\"gold\",\"italic\":false}]"));
		return posterLore;
	}
	public static Image2MapConfig CONFIG = AutoConfig.register(Image2MapConfig.class, GsonConfigSerializer::new)
		.getConfig();

	@Override
	public void onInitialize() {
		System.out.println("Loading Image2Map...");

		CommandRegistrationCallback.EVENT.register((dispatcher, _dedicated) ->
			dispatcher.register(CommandManager.literal("mapcreate")
					.requires(source -> source.hasPermissionLevel(CONFIG.minPermLevel))
					.then(ditherAndPath(ctx -> createMaps(MapGenerationContext.getBasicInfo(ctx))))
					.then(CommandManager.literal("multi")
						.then(CommandManager.argument("width", IntegerArgumentType.integer(1, 25))
						.then(CommandManager.argument("height", IntegerArgumentType.integer(1, 25))
							.then(CommandManager.argument("scale", StringArgumentType.word())
								.suggests(ScaleMode.getSuggestor())
								.then(CommandManager.argument("makePoster", BoolArgumentType.bool())
									.then(ditherAndPath(ctx ->
										createMaps(MapGenerationContext.getBasicInfo(ctx).getSize(ctx).getScaleMethod(ctx).getMakePoster(ctx)))
								)
								.then(ditherAndPath(ctx -> createMaps(MapGenerationContext.getBasicInfo(ctx).getSize(ctx).getScaleMethod(ctx))))
							)
							.then(ditherAndPath(ctx -> createMaps(MapGenerationContext.getBasicInfo(ctx).getSize(ctx))))
							)
						))
					)
			)
		);
	}


	protected static ArgumentBuilder<ServerCommandSource, ?> ditherAndPath(Command<ServerCommandSource> command) {
		return CommandManager.argument("dither", StringArgumentType.word())
			.suggests(DitherMode.getSuggestor())
			.then(CommandManager.argument("path", StringArgumentType.greedyString())
				.executes(command)
			);
	}


	public enum DitherMode {
		NONE,
		FLOYD;

		// The default from string method doesn't quite fit my needs
		public static DitherMode fromString(String string) throws CommandSyntaxException {
			if (string.equalsIgnoreCase("NONE"))
				return DitherMode.NONE;
			else if (string.equalsIgnoreCase("DITHER") || string.equalsIgnoreCase("FLOYD"))
				return DitherMode.FLOYD;
			throw new CommandSyntaxException(
				new SimpleCommandExceptionType(new LiteralMessage("Invalid Dither mode '" + string + "'")),
				new LiteralMessage("Invalid Dither mode '" + string + "'"));
		}

		public static SuggestionProvider<ServerCommandSource> getSuggestor() {
			return new DitherModeSuggestionProvider();
		}

		static class DitherModeSuggestionProvider implements SuggestionProvider<ServerCommandSource> {

			@Override
			public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context,
																 SuggestionsBuilder builder) {
				if ("none".startsWith(builder.getRemaining().toLowerCase()))
					builder.suggest("none");
				if ("dither".startsWith(builder.getRemaining().toLowerCase()))
					builder.suggest("dither");
				return builder.buildFuture();
			}

		}
	}

	private int createMaps(MapGenerationContext context) throws CommandSyntaxException {
		try {
			ServerCommandSource source = context.getSource();
			source.sendFeedback(new LiteralText("Generating image map..."), false);
			BufferedImage sourceImage = ImageUtils.getImage(context.getPath(), source);
			if (sourceImage == null)
				return 0;
			ServerPlayerEntity player = source.getPlayer();
			new Thread(() -> {
				BufferedImage img = new BufferedImage(context.getCountX() * 128, context.getCountY() * 128, BufferedImage.TYPE_INT_ARGB);
				if (context.getScaleMode() == ScaleMode.STRETCH)
					img.createGraphics().drawImage(sourceImage, 0, 0, img.getWidth(), img.getHeight(), null);
				else if (context.getScaleMode() == ScaleMode.FIT)
					ImageUtils.scaleImage(img, sourceImage, true);
				else if (context.getScaleMode() == ScaleMode.FILL)
					ImageUtils.scaleImage(img, sourceImage, false);
				img.flush();
				final int SECTION_SIZE = 128;
				ListTag maps = new ListTag();
				for (int y = 0; y < context.getCountY(); y++) {
					ListTag mapsY = new ListTag();
					for (int x = 0; x < context.getCountX(); x++) {
						BufferedImage subImage = img.getSubimage(x * SECTION_SIZE, y * SECTION_SIZE, SECTION_SIZE, SECTION_SIZE);
						ItemStack stack = createMap(source, context.getDither(), subImage);
						if (context.shouldMakePoster() && (context.getCountX() > 1 || context.getCountY() > 1)) {
							mapsY.add(IntTag.of(FilledMapItem.getMapId(stack)));
						} else {
							givePlayerMap(player, stack);
						}
					}
					maps.add(mapsY);
				}
				if (context.shouldMakePoster() && (context.getCountX() > 1 || context.getCountY() > 1)) {
					BufferedImage posterImg = new BufferedImage(context.getCountX() * 128, context.getCountY() * 128, BufferedImage.TYPE_INT_ARGB);
					ImageUtils.scaleImage(posterImg, sourceImage, true);
					ItemStack stack = createMap(source, context.getDither(), posterImg);
					stack.putSubTag("i2mStoredMaps", maps);
					CompoundTag stackDisplay = stack.getOrCreateSubTag("display");
					String path = context.getPath();
					String fileName = path.length() < 15 ? path : "image";
					if (ImageUtils.isValid(path)) {
						try {
							URL url = new URL(path);
							fileName = url.getFile();
							int start = fileName.lastIndexOf('/');
							if (start > 0 && start + 1 < fileName.length())
								fileName = fileName.substring(start + 1);
							int end = fileName.indexOf('?');
							if (end > 0)
								fileName = fileName.substring(0, end);
						} catch (MalformedURLException e) {
							e.printStackTrace();
						}
					} else {
						int index = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
						if (index > 0)
							fileName = path.substring(index);
						else
							fileName = path;
					}
					stackDisplay.put("Name", StringTag.of("{\"text\":\"Poster for '" + fileName + "'\",\"italic\":false}"));
					stackDisplay.put("Lore", getLore(context.getCountX(), context.getCountY()));

					givePlayerMap(player, stack);
				}
				source.sendFeedback(new LiteralText("Done!"), false);
			}).start();
			source.sendFeedback(new LiteralText("Map Creation Queued!"), false);
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
		return 1;
	}


	private ItemStack createMap(ServerCommandSource source, DitherMode mode,
								BufferedImage image) {
		return MapRenderer.render(image, mode, source.getWorld(), source.getPosition().x, source.getPosition().z);
	}

	private void givePlayerMap(PlayerEntity player, ItemStack stack) {
		if (!player.inventory.insertStack(stack)) {
			ItemEntity itemEntity = new ItemEntity(player.world, player.getPos().x, player.getPos().y,
				player.getPos().z, stack);
			player.world.spawnEntity(itemEntity);
		}
	}

	public enum ScaleMode {
		FIT,
		FILL,
		STRETCH;

		public static ScaleMode fromString(String sMode) {
			switch (sMode.toUpperCase()) {
				case "FIT":
					return FIT;
				case "FILL":
					return FILL;
				case "STRETCH":
					return STRETCH;
				default:
					throw new IllegalArgumentException("input string must be a valid enum value!");
			}
		}
		public static SuggestionProvider<ServerCommandSource> getSuggestor() {
			return new ScaleSuggestionProvider();
		}
		private static class ScaleSuggestionProvider implements SuggestionProvider<ServerCommandSource> {
			@Override
			public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
				if ("fit".startsWith(builder.getRemaining().toLowerCase()))
					builder.suggest("fit");
				if ("fill".startsWith(builder.getRemaining().toLowerCase()))
					builder.suggest("fill");
				if ("stretch".startsWith(builder.getRemaining().toLowerCase()))
					builder.suggest("stretch");
				return builder.buildFuture();
			}
		}
	}

}
