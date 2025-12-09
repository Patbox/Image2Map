package space.essem.image2map.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.essem.image2map.Image2Map;

@Mixin(ItemFrame.class)
public class ItemFrameMixin {
    @Shadow
    private boolean fixed;

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void image2map$fillMaps(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (!this.fixed && Image2Map.clickItemFrame(player, hand, (ItemFrame) (Object) this)) {
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }

    @Inject(method = "dropItem(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;Z)V", at = @At("HEAD"), cancellable = true)
    private void image2map$destroyMaps(ServerLevel world, Entity entity, boolean dropSelf, CallbackInfo ci) {
        var frame = (ItemFrame) (Object) this;

        if (!this.fixed && Image2Map.destroyItemFrame(entity, frame)) {
            if (dropSelf) {
                frame.spawnAtLocation(world, new ItemStack(Items.ITEM_FRAME));
            }
            ci.cancel();
        }
    }
}
