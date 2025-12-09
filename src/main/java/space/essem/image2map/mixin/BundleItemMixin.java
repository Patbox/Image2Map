package space.essem.image2map.mixin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BundleItem.class)
public class BundleItemMixin {

  @Inject(method = "use", at = @At("HEAD"), cancellable = true)
  private void image2map$useBundle(Level world, Player user, InteractionHand hand,
      CallbackInfoReturnable<InteractionResult> cir) {
    ItemStack itemStack = user.getItemInHand(hand);
    var tag = itemStack.get(DataComponents.CUSTOM_DATA);

    if (tag != null && tag.copyTag().contains("image2map:quick_place") && !user.isCreative()) {
      cir.setReturnValue(InteractionResult.FAIL);
      cir.cancel();
    }
  }

  @Inject(method = "overrideStackedOnOther", at = @At("HEAD"), cancellable = true)
  private void image2map$addBundleItems(ItemStack bundle, Slot slot, ClickAction clickType, Player player,
      CallbackInfoReturnable<Boolean> cir) {
    var tag = bundle.get(DataComponents.CUSTOM_DATA);

    if (tag != null && tag.copyTag().contains("image2map:quick_place") && !player.isCreative()) {
      cir.setReturnValue(false);
      cir.cancel();
    }
  }

  @Inject(method = "overrideOtherStackedOnMe", at = @At("HEAD"), cancellable = true)
  private void image2map$removeBundleItems(ItemStack bundle, ItemStack otherStack, Slot slot, ClickAction clickType,
      Player player, SlotAccess cursorStackReference,
      CallbackInfoReturnable<Boolean> cir) {
    var tag = bundle.get(DataComponents.CUSTOM_DATA);

    if (tag != null && tag.copyTag().contains("image2map:quick_place") && !player.isCreative()) {
      cir.setReturnValue(false);
      cir.cancel();
    }
  }
}
