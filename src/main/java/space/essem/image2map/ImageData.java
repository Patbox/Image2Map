package space.essem.image2map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.math.Direction;

import java.util.Optional;

public record ImageData(int x, int y, int width, int height, boolean quickPlace, Optional<Direction> right, Optional<Direction> down, Optional<Direction> facing) {
    public static final MapCodec<ImageData> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.optionalFieldOf("image2map:x", 0).forGetter(ImageData::x),
            Codec.INT.optionalFieldOf("image2map:y", 0).forGetter(ImageData::y),
            Codec.INT.optionalFieldOf("image2map:width", 0).forGetter(ImageData::width),
            Codec.INT.optionalFieldOf("image2map:height", 0).forGetter(ImageData::width),
            Codec.BOOL.optionalFieldOf("image2map:quick_place", false).forGetter(ImageData::quickPlace),
            Direction.CODEC.optionalFieldOf("image2map:right").forGetter(ImageData::right),
            Direction.CODEC.optionalFieldOf("image2map:down").forGetter(ImageData::down),
            Direction.CODEC.optionalFieldOf("image2map:facing").forGetter(ImageData::facing)
    ).apply(instance, ImageData::new));

    public static ImageData ofSimple(int x, int y, int width, int height) {
        return new ImageData(x, y ,width, height, false, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public ImageData withDirection(Direction right, Direction down, Direction facing) {
        return new ImageData(x, y, width, height, quickPlace, Optional.of(right), Optional.of(down), Optional.of(facing));
    }

    public static ImageData ofBundle(int width, int height) {
        return new ImageData(0, 0, width, height, true, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public boolean isReal() {
        return this.width != 0 && this.height != 0;
    }
}
