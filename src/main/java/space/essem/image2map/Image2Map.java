package space.essem.image2map;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.api.ModInitializer;
import net.minecraft.item.FilledMapItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.NotNull;
import space.essem.image2map.config.Image2MapConfig;
import space.essem.image2map.renderer.MapRenderer;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import org.jetbrains.annotations.Nullable;

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

		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) ->
			dispatcher.register(CommandManager.literal("mapcreate")
				.requires(source -> source.hasPermissionLevel(CONFIG.minPermLevel))
				.then(CommandManager.argument("mode", StringArgumentType.word())
					.suggests(new DitherModeSuggestionProvider())
					.then(CommandManager.argument("path", StringArgumentType.string())
						.executes(ctx -> createMaps(MapGenerationContext.getBasicInfo(ctx)))
					)
				).then(CommandManager.literal("multi")
					.requires(source -> source.hasPermissionLevel(CONFIG.minPermLevel))
					.then(CommandManager.argument("mode", StringArgumentType.word())
						.suggests(new DitherModeSuggestionProvider())
						.then(CommandManager.argument("width", IntegerArgumentType.integer(1, 25))
						.then(CommandManager.argument("height", IntegerArgumentType.integer(1, 25))
						.then(CommandManager.argument("path", StringArgumentType.string())
							.then(CommandManager.argument("scale", StringArgumentType.word())
							.suggests(new ScaleSuggestionProvider())
							.then(CommandManager.argument("makePoster", BoolArgumentType.bool())
								.executes(ctx ->
									createMaps(MapGenerationContext.getBasicInfo(ctx).getSize(ctx).getScaleMethod(ctx).getPosterfy(ctx))
								)
							)
							.executes(ctx ->
								createMaps(MapGenerationContext.getBasicInfo(ctx).getSize(ctx).getScaleMethod(ctx))
							))
							.executes(ctx -> createMaps(MapGenerationContext.getBasicInfo(ctx).getSize(ctx)))
						)))
					)
				)
			));
	}

	static class ScaleSuggestionProvider implements SuggestionProvider<ServerCommandSource> {
		@Override
		public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) throws CommandSyntaxException {
			if ("fit".startsWith(builder.getRemaining().toLowerCase()))
				builder.suggest("fit");
			if ("fill".startsWith(builder.getRemaining().toLowerCase()))
				builder.suggest("fill");
			if ("stretch".startsWith(builder.getRemaining().toLowerCase()))
				builder.suggest("stretch");
			return builder.buildFuture();
		}
	}

	static class DitherModeSuggestionProvider implements SuggestionProvider<ServerCommandSource> {

		@Override
		public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context,
															 SuggestionsBuilder builder) throws CommandSyntaxException {
			if ("none".startsWith(builder.getRemaining().toLowerCase()))
				builder.suggest("none");
			if ("dither".startsWith(builder.getRemaining().toLowerCase()))
				builder.suggest("dither");
			return builder.buildFuture();
		}

	}

	public enum DitherMode {
		NONE,
		FLOYD;

		public static DitherMode fromString(String string) throws CommandSyntaxException {
			if (string.equalsIgnoreCase("NONE"))
				return DitherMode.NONE;
			else if (string.equalsIgnoreCase("DITHER") || string.equalsIgnoreCase("FLOYD"))
				return DitherMode.FLOYD;
			throw new CommandSyntaxException(
				new SimpleCommandExceptionType(new LiteralMessage("Invalid Dither mode '" + string + "'")),
				new LiteralMessage("Invalid Dither mode '" + string + "'"));
		}
	}

	private static void scaleImage(BufferedImage output, BufferedImage input, Graphics g, boolean fitAll) {
		double imgAspect = (double) input.getHeight() / input.getWidth();

		int outputWidth = output.getWidth();
		int outputHeight = output.getHeight();

		double canvasAspect = (double) outputHeight / outputWidth;

		int x1 = 0;
		int y1 = 0;

		// XOR conditionally negates the IF statement (A XOR true == !A, A XOR false == A)
		if (canvasAspect > imgAspect ^ !fitAll) {
			y1 = outputHeight;
			outputHeight = (int) (outputWidth * imgAspect);
			y1 = (y1 - outputHeight) / 2;
		} else {
			x1 = outputWidth;
			outputWidth = (int) (outputHeight / imgAspect);
			x1 = (x1 - outputWidth) / 2;
		}
		int x2 = outputWidth + x1;
		int y2 = outputHeight + y1;

		g.drawImage(input, x1, y1, x2, y2, 0, 0, input.getWidth(), input.getHeight(), null);
	}

	private int createMaps(MapGenerationContext context) throws CommandSyntaxException {
		try {
			ServerCommandSource source = context.getSource();
			source.sendFeedback(new LiteralText("Generating image map..."), false);
			BufferedImage sourceImage = getImage(context.path, source);
			if (sourceImage == null)
				return 0;
			ServerPlayerEntity player = source.getPlayer();
			new Thread(() -> {
				BufferedImage img = new BufferedImage(context.countX * 128, context.countY * 128, BufferedImage.TYPE_INT_ARGB);
				Graphics2D graphics = img.createGraphics();
				if (context.getScaleMode() == ScaleMode.STRETCH)
					graphics.drawImage(sourceImage, 0, 0, img.getWidth(), img.getHeight(), null);
				else if (context.getScaleMode() == ScaleMode.FIT)
					scaleImage(img, sourceImage, graphics, true);
				else if (context.getScaleMode() == ScaleMode.FILL)
					scaleImage(img, sourceImage, graphics, false);
				img.flush();
				final int SECTION_SIZE = 128;
				ListTag maps = new ListTag();
				for (int y = 0; y < context.countY; y++) {
					ListTag mapsY = new ListTag();
					for (int x = 0; x < context.countX; x++) {
						BufferedImage subImage = img.getSubimage(x * SECTION_SIZE, y * SECTION_SIZE, SECTION_SIZE, SECTION_SIZE);
						ItemStack stack = createMap(source, context.mode, subImage);
						if (context.shouldMakePoster() && (context.countX > 1 || context.countY > 1)) {
							mapsY.add(IntTag.of(FilledMapItem.getMapId(stack)));
						} else {
							givePlayerMap(player, stack);
						}
					}
					maps.add(mapsY);
				}
				if (context.shouldMakePoster()) {
					ItemStack stack = createMap(source, context.mode, MapRenderer.convertToBufferedImage(
						sourceImage.getScaledInstance(128, 128, Image.SCALE_DEFAULT)));
					stack.putSubTag("i2mStoredMaps", maps);
					CompoundTag stackDisplay = stack.getOrCreateSubTag("display");
					String path = context.getPath();
					String fileName = path.length() < 15 ? path : "image";
					if (isValid(path)) {
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
					stackDisplay.put("Lore", getLore(context.countX, context.countY));

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


	@Nullable
	private BufferedImage getImage(String urlStr, ServerCommandSource source) {
		BufferedImage image = null;
		try {
			if (isValid(urlStr)) {
				URL url = new URL(urlStr);
				URLConnection connection = url.openConnection();
				connection.setRequestProperty("User-Agent", "Image2Map mod");
				connection.connect();
				image = ImageIO.read(connection.getInputStream());
			} else if (CONFIG.allowLocalFiles) {
				File file = new File(urlStr);
				image = ImageIO.read(file);
			} else {
				image = null;
			}
		} catch (IOException e) {
			source.sendFeedback(new LiteralText("That doesn't seem to be a valid image."), false);
			return null;
		}

		if (image == null) {
			source.sendFeedback(new LiteralText("That doesn't seem to be a valid image."), false);
			return null;
		}
		return image;
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

	private static boolean isValid(String url) {
		try {
			new URL(url).toURI();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static enum ScaleMode {
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
	}

	private static class MapGenerationContext {
		public ServerCommandSource getSource() {
			return source;
		}

		public MapGenerationContext source(ServerCommandSource source) {
			this.source = source;
			return this;
		}

		ServerCommandSource source;
		private DitherMode mode = DitherMode.FLOYD;
		private ScaleMode scaleMode = ScaleMode.STRETCH;
		private String path;

		public boolean shouldMakePoster() {
			return makePoster;
		}

		private boolean makePoster = true;
		private int countX = 1;
		private int countY = 1;

		private static MapGenerationContext getBasicInfo(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
			return new MapGenerationContext(StringArgumentType.getString(context, "path"))
				.mode(DitherMode.fromString(StringArgumentType.getString(context, "mode")))
				.source(context.getSource());
		}

		private MapGenerationContext getSize(CommandContext<ServerCommandSource> context) {
			return this
				.countX(IntegerArgumentType.getInteger(context, "width"))
				.countY(IntegerArgumentType.getInteger(context, "height"));
		}

		private MapGenerationContext getScaleMethod(CommandContext<ServerCommandSource> context) {
			return this
				.scaleMode(ScaleMode.fromString(StringArgumentType.getString(context, "scale")));
		}

		public MapGenerationContext getPosterfy(CommandContext<ServerCommandSource> context) {
			return this
				.makePoster(BoolArgumentType.getBool(context, "makePoster"));
		}

		private MapGenerationContext makePoster(boolean makePoster) {
			this.makePoster = makePoster;
			return this;
		}


		public DitherMode getMode() {
			return mode;
		}

		public MapGenerationContext mode(DitherMode mode) {
			this.mode = mode;
			return this;
		}

		public String getPath() {
			return path;
		}

		public MapGenerationContext path(String path) {
			this.path = path;
			return this;
		}

		public int getCountX() {
			return countX;
		}

		public MapGenerationContext countX(int countX) {
			this.countX = countX;
			return this;
		}

		public int getCountY() {
			return countY;
		}

		public MapGenerationContext countY(int countY) {
			this.countY = countY;
			return this;
		}

		public MapGenerationContext(@NotNull String path) {
			this.path = path;
		}

		public ScaleMode getScaleMode() {
			return scaleMode;
		}

		public MapGenerationContext scaleMode(ScaleMode scaleMode) {
			this.scaleMode = scaleMode;
			return this;
		}

	}

}
