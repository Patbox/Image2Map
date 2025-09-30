package space.essem.image2map.mixin;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.StackReference;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.ClickType;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

@Mixin(BundleItem.class)
public class BundleItemMixin {

  @Inject(method = "use", at = @At("HEAD"), cancellable = true)
  private void image2map$useBundle(World world, PlayerEntity user, Hand hand,
      CallbackInfoReturnable<ActionResult> cir) {
    ItemStack itemStack = user.getStackInHand(hand);
    var tag = itemStack.get(DataComponentTypes.CUSTOM_DATA);

    if (tag != null && tag.copyNbt().contains("image2map:quick_place") && !user.isCreative()) {
      cir.setReturnValue(ActionResult.FAIL);
      cir.cancel();
    }
  }

  @Inject(method = "onStackClicked", at = @At("HEAD"), cancellable = true)
  private void image2map$addBundleItems(ItemStack bundle, Slot slot, ClickType clickType, PlayerEntity player,
      CallbackInfoReturnable<Boolean> cir) {
    var tag = bundle.get(DataComponentTypes.CUSTOM_DATA);

    if (tag != null && tag.copyNbt().contains("image2map:quick_place") && !player.isCreative()) {
      cir.setReturnValue(false);
      cir.cancel();
    }
  }

  @Inject(method = "onClicked", at = @At("HEAD"), cancellable = true)
  private void image2map$removeBundleItems(ItemStack bundle, ItemStack otherStack, Slot slot, ClickType clickType,
      PlayerEntity player, StackReference cursorStackReference,
      CallbackInfoReturnable<Boolean> cir) {
    var tag = bundle.get(DataComponentTypes.CUSTOM_DATA);

    if (tag != null && tag.copyNbt().contains("image2map:quick_place") && !player.isCreative()) {
      cir.setReturnValue(false);
      cir.cancel();
    }
  }
}
