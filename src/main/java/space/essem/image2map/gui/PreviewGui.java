package space.essem.image2map.gui;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.RootCommandNode;
import eu.pb4.mapcanvas.api.core.CanvasColor;
import eu.pb4.mapcanvas.api.core.CanvasImage;
import eu.pb4.mapcanvas.api.font.DefaultFonts;
import eu.pb4.mapcanvas.api.utils.CanvasUtils;
import net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import space.essem.image2map.Image2Map;
import space.essem.image2map.renderer.MapRenderer;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;

public class PreviewGui extends MapGui {
    private static final CommandDispatcher<PreviewGui> COMMANDS = new CommandDispatcher<>();

    private final BufferedImage sourceImage;
    private final String source;
    private boolean dirty;
    private int xPos;
    private CanvasImage image;
    private int yPos;
    private Image2Map.DitherMode ditherMode;
    private int width;
    private int height;
    private boolean grid = true;
    private CompletableFuture<CanvasImage> imageProcessing;

    public PreviewGui(ServerPlayerEntity player, BufferedImage image, String source, Image2Map.DitherMode ditherMode, int width, int height) {
        super(player, MathHelper.ceil(width / 128d) + 2, MathHelper.ceil(height / 128d) + 2);
        this.width = width;
        this.height = height;
        this.ditherMode = ditherMode;
        this.source = source;
        this.sourceImage = image;

        player.networkHandler.sendPacket(new CommandTreeS2CPacket((RootCommandNode) COMMANDS.getRoot()));

        this.updateImage();

    }

    protected void updateImage() {
        this.setDistance(Math.max(this.height / 128d * 0.8, this.width / 128d * 0.5));
        this.dirty = true;
        this.drawLoading();
    }

    @Override
    public void onTick() {
        if (this.dirty) {
            if (this.imageProcessing != null) {
                this.imageProcessing.cancel(true);
            }

            this.imageProcessing = CompletableFuture.supplyAsync(() ->  MapRenderer.render(this.sourceImage, this.ditherMode, this.width, this.height));
            this.dirty = false;
        }

        if (this.imageProcessing != null) {
            if (this.imageProcessing.isDone()) {
                if (this.imageProcessing.isCompletedExceptionally()) {
                    this.imageProcessing = null;
                } else {
                    try {
                        this.image = this.imageProcessing.get();
                        this.imageProcessing = null;

                        this.xPos = (this.canvas.getWidth() - this.image.getWidth()) / 2;
                        this.yPos = (this.canvas.getHeight() - this.image.getHeight()) / 2;

                        this.draw();
                    } catch (Throwable e) {
                        e.printStackTrace();
                        this.close();
                    }
                }
            }
        }
    }

    @Override
    public void onClose() {
        if (this.imageProcessing != null) {
            this.imageProcessing.cancel(true);
        }
        super.onClose();
    }

    private void drawLoading() {
        var text = "Loading...";
        var size = (int) Math.min(this.height / 128d, this.width / 128d) * 16;
        var width = DefaultFonts.VANILLA.getTextWidth(text, size);

        CanvasUtils.fill(this.canvas,
                (this.width - width) / 2 - size + 128,
                (this.height - size) / 2 - size + 128,
                (this.width - width) / 2 + size + width + 128,
                (this.height - size) / 2 + size * 2 + 128, CanvasColor.BLACK_LOW);

        DefaultFonts.VANILLA.drawText(this.canvas, text, (this.width - width) / 2 + 128, (this.height - size) / 2 + 128, size, CanvasColor.WHITE_HIGH);

        this.canvas.sendUpdates();
    }

    private void draw() {
        var image = new CanvasImage(this.canvas.getWidth(), this.canvas.getHeight());

        if (this.grid) {
            for (int x = 0; x < this.canvas.getSectionsWidth(); x++) {
                for (int y = 0; y < this.canvas.getSectionsHeight(); y++) {
                    CanvasUtils.fill(image, x * 128, y * 128, (x + 1) * 128, (y + 1) * 128,
                            (x + (y % 2)) % 2 == 0 ? CanvasColor.BLACK_LOW : CanvasColor.GRAY_HIGH);
                }
            }

            CanvasUtils.fill(image, this.xPos - 2, this.yPos - 2, this.xPos + 2 + this.image.getWidth(), this.yPos + 2 + this.image.getHeight(),
                    CanvasColor.WHITE_HIGH);
        } else {
            CanvasUtils.clear(image, CanvasColor.CLEAR_FORCE);
        }
        if (this.image != null) {
            CanvasUtils.draw(image, this.xPos, this.yPos, this.image);
        }
        CanvasUtils.draw(this.canvas, 0, 0, image);
        this.canvas.sendUpdates();
    }

    public void setSize(int width, int height) {
        if (
                this.canvas.getWidth() < width + 256 || this.canvas.getHeight() < height + 256
                        || this.canvas.getWidth() > width * 2 || this.canvas.getHeight() > height * 2
        ) {
            this.resizeCanvas(MathHelper.ceil(width / 128d) + 2, MathHelper.ceil(height / 128d) + 2);
        }

        this.width = width;
        this.height = height;
        this.updateImage();
    }

    public void setDitherMode(Image2Map.DitherMode ditherMode) {
        this.ditherMode = ditherMode;
        this.updateImage();
    }

    public void setDrawGrid(boolean grid) {
        this.grid = grid;
        this.draw();
    }

    @Override
    public void executeCommand(String command) {
        try {
            COMMANDS.execute(command, this);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static LiteralArgumentBuilder<PreviewGui> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <T> RequiredArgumentBuilder<PreviewGui, T> argument(String name, ArgumentType<T> argumentType) {
        return RequiredArgumentBuilder.argument(name, argumentType);
    }

    static {
        COMMANDS.register(literal("exit").executes(x -> {
            x.getSource().close();
            return 0;
        }));

        COMMANDS.register(literal("save").executes(x -> {
            if (x.getSource().imageProcessing == null) {
                x.getSource().drawLoading();
                Image2Map.giveToPlayer(x.getSource().player,
                        MapRenderer.toVanillaItems(x.getSource().image, x.getSource().player.getServerWorld(), x.getSource().source),
                        x.getSource().source, x.getSource().width, x.getSource().height);

                x.getSource().close();
            } else {
                x.getSource().player.sendMessage(Text.literal("Image is still processed!"));
            }
            return 0;
        }));

        COMMANDS.register(literal("size")
                .then(argument("width", IntegerArgumentType.integer(1))
                        .then(argument("height", IntegerArgumentType.integer(1)).executes(x -> {
                            x.getSource().setSize(IntegerArgumentType.getInteger(x, "width"), IntegerArgumentType.getInteger(x, "height"));
                            return 0;
                        })))
                .executes(x -> {
                    x.getSource().player.sendMessage(Text.literal("Source: " + x.getSource().sourceImage.getWidth() + " x " + x.getSource().sourceImage.getHeight()));
                    x.getSource().player.sendMessage(Text.literal("MapImage: " + x.getSource().width + " x " + x.getSource().height));
                    return 0;
                })
        );

        COMMANDS.register(literal("dither")
                .then(literal("none").executes(x -> {
                    x.getSource().setDitherMode(Image2Map.DitherMode.NONE);
                    return 0;
                }))
                .then(literal("floyd").executes(x -> {
                    x.getSource().setDitherMode(Image2Map.DitherMode.FLOYD);
                    return 0;
                }))
        );

        COMMANDS.register(literal("grid")
                .then(argument("value", BoolArgumentType.bool()).executes(x -> {
                    x.getSource().setDrawGrid(BoolArgumentType.getBool(x, "value"));
                    return 0;
                }))
                .executes(x -> {
                    x.getSource().setDrawGrid(!x.getSource().grid);
                    return 0;
                })
        );
    }

}
