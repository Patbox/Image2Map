package space.essem.image2map;

import net.fabricmc.api.ModInitializer;

import space.essem.image2map.renderer.MapRenderer;

import java.io.File;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;

public class Image2Map implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("Loading Image2Map...");

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(CommandManager.literal("mapcreate").requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("path", StringArgumentType.string()).executes(context -> {
                        ServerCommandSource source = context.getSource();
                        Vec3d pos = source.getPosition();
                        PlayerEntity player = source.getPlayer();
                        File file = new File(StringArgumentType.getString(context, "path"));

                        source.sendFeedback(new LiteralText("Generating image map..."), false);
                        ItemStack stack = MapRenderer.render(file, source.getWorld(), pos.x, pos.z, player);

                        source.sendFeedback(new LiteralText("Done!"), false);
                        if (!player.inventory.insertStack(stack)) {
                            ItemEntity itemEntity = new ItemEntity(player.world, player.getPos().x, player.getPos().y,
                                    player.getPos().z, stack);
                            player.world.spawnEntity(itemEntity);
                        }

                        return 1;
                    })));
        });
    }
}
