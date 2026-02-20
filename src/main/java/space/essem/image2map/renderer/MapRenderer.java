package space.essem.image2map.renderer;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.List;
import eu.pb4.mapcanvas.api.core.CanvasColor;
import eu.pb4.mapcanvas.api.core.CanvasImage;
import eu.pb4.mapcanvas.api.utils.CanvasUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import space.essem.image2map.Image2Map.DitherMode;
import space.essem.image2map.ImageData;

public class MapRenderer {
    public static CanvasImage render(BufferedImage image, DitherMode mode, int width, int height) {
        Image resizedImage = image.getScaledInstance(width, height, Image.SCALE_DEFAULT);
        BufferedImage resized = convertToBufferedImage(resizedImage);
        return switch (mode) {
            case NONE -> CanvasImage.from(resized);
            case FLOYD -> CanvasImage.fromWithFloydSteinbergDither(resized);
        };
    }

    public static List<ItemStack> toVanillaItems(CanvasImage image, ServerLevel world, String url) {
        var xSections = Mth.ceil(image.getWidth() / 128d);
        var ySections = Mth.ceil(image.getHeight() / 128d);

        var xDelta = (xSections * 128 - image.getWidth()) / 2;
        var yDelta = (ySections * 128 - image.getHeight()) / 2;

        var items = new ArrayList<ItemStack>();

        for (int ys = 0; ys < ySections; ys++) {
            for (int xs = 0; xs < xSections; xs++) {
                var id = world.getFreeMapId();
                var state = MapItemSavedData.createFresh(0, 0, (byte) 0, false, false, ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("image2map", "generated")));

                for (int xl = 0; xl < 128; xl++) {
                    for (int yl = 0; yl < 128; yl++) {
                        var x = xl + xs * 128 - xDelta;
                        var y = yl + ys * 128 - yDelta;

                        if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight()) {
                            state.colors[xl + yl * 128] = image.getRaw(x, y);
                        }
                    }
                }

                world.setMapData(id, state);

                var stack = new ItemStack(Items.FILLED_MAP);
                stack.set(DataComponents.MAP_ID, id);
                var data = ImageData.ofSimple(xs, ys, xSections, ySections);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(ImageData.CODEC.codec().encodeStart(NbtOps.INSTANCE, data).result().orElseThrow().asCompound().orElseThrow()));
                stack.set(DataComponents.LORE, new ItemLore(List.of(
                        Component.literal(xs + " / " + ys).withStyle(ChatFormatting.GRAY),
                        Component.literal(url)
                )));
                items.add(stack);
            }
        }

        return items;
    }

    private static BufferedImage convertToBufferedImage(Image image) {
        BufferedImage newImage = new BufferedImage(image.getWidth(null), image.getHeight(null),
                BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g = newImage.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return newImage;
    }
}