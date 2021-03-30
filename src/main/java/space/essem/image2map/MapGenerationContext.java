package space.essem.image2map;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.ServerCommandSource;
import org.jetbrains.annotations.NotNull;

class MapGenerationContext {
    public ServerCommandSource getSource() {
        return source;
    }

    public MapGenerationContext source(ServerCommandSource source) {
        this.source = source;
        return this;
    }

    ServerCommandSource source;
    private Image2Map.DitherMode dither = Image2Map.DitherMode.FLOYD;
    private Image2Map.ScaleMode scaleMode = Image2Map.ScaleMode.STRETCH;
    private String path;

    public boolean shouldMakePoster() {
        return makePoster;
    }

    private boolean makePoster = true;
    private int countX = 1;
    private int countY = 1;

    public static MapGenerationContext getBasicInfo(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return new MapGenerationContext(StringArgumentType.getString(context, "path"))
                .dither(Image2Map.DitherMode.fromString(StringArgumentType.getString(context, "dither")))
                .source(context.getSource());
    }

    public MapGenerationContext getSize(CommandContext<ServerCommandSource> context) {
        return this
                .countX(IntegerArgumentType.getInteger(context, "width"))
                .countY(IntegerArgumentType.getInteger(context, "height"));
    }

    public MapGenerationContext getScaleMethod(CommandContext<ServerCommandSource> context) {
        return this
                .scaleMode(Image2Map.ScaleMode.fromString(StringArgumentType.getString(context, "scale")));
    }

    public MapGenerationContext getMakePoster(CommandContext<ServerCommandSource> context) {
        return this
                .makePoster(BoolArgumentType.getBool(context, "makePoster"));
    }

    public MapGenerationContext makePoster(boolean makePoster) {
        this.makePoster = makePoster;
        return this;
    }


    public Image2Map.DitherMode getDither() {
        return dither;
    }

    public MapGenerationContext dither(Image2Map.DitherMode dither) {
        this.dither = dither;
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

    public Image2Map.ScaleMode getScaleMode() {
        return scaleMode;
    }

    public MapGenerationContext scaleMode(Image2Map.ScaleMode scaleMode) {
        this.scaleMode = scaleMode;
        return this;
    }

}
