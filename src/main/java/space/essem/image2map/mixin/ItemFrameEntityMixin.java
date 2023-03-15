package space.essem.image2map.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.essem.image2map.Image2Map;

@Mixin(ItemFrameEntity.class)
public class ItemFrameEntityMixin {
    @Shadow
    private boolean fixed;

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void image2map$fillMaps(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (!this.fixed && Image2Map.clickItemFrame(player, hand, (ItemFrameEntity) (Object) this)) {
            cir.setReturnValue(ActionResult.SUCCESS);
        }
    }

    @Inject(method = "dropHeldStack", at = @At("HEAD"), cancellable = true)
    private void image2map$destroyMaps(@Nullable Entity entity, boolean alwaysDrop,
            CallbackInfo ci) {
        var frame = (ItemFrameEntity) (Object) this;

        if (!this.fixed && Image2Map.destroyItemFrame(entity, frame)) {
            if (alwaysDrop)
                frame.dropStack(new ItemStack(Items.ITEM_FRAME));
            ci.cancel();
        }
    }
}
