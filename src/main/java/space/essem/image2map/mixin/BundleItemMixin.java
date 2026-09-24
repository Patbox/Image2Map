package space.essem.image2map.mixin;

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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import space.essem.image2map.Image2Map;

@Mixin(BundleItem.class)
public class BundleItemMixin {
    /**
     * When holding a bundle in your hand in-world,
     * and right-clicking to drop an item, destroy the bundle if it ends up empty.
     * Overridden if the player is in creative mode.
     */
    @Inject(method = "dropContent(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"))
    private void image2map$dropContent(Level level, Player player, ItemStack stack, CallbackInfo ci) {
        if (player.isCreative()) {
            return;
        }

        Image2Map.destroyBundleOnEmpty(stack);
    }

    /**
     * When holding a bundle with the mouse in the inventory,
     * and left-clicking a slot with another item, only place
     * the item into the bundle if it's a valid image2map map for the bundle.
     * Overridden if the player is in creative mode.
     */
    @Inject(method = "overrideStackedOnOther", at = @At("HEAD"), cancellable = true)
    private void image2map$overrideStackedOnOtherHead(ItemStack itemStack, Slot slot, ClickAction clickAction, Player player, CallbackInfoReturnable<Boolean> cir) {
        if (player.isCreative()) {
            return;
        }

        if (clickAction != ClickAction.PRIMARY) {
            return;
        }

        if (Image2Map.isInvalidMapForBundle(itemStack, slot.getItem())) {
            cir.setReturnValue(false);
        }
    }

    /**
     * When holding a bundle with the mouse in the inventory,
     * and right-clicking a slot with no item,
     * destroy the bundle if it ends up empty.
     * Overridden if the player is in creative mode.
     */
    @Inject(method = "overrideStackedOnOther", at = @At("RETURN"))
    private void image2map$overrideStackedOnOtherReturn(ItemStack itemStack, Slot slot, ClickAction clickAction, Player player, CallbackInfoReturnable<Boolean> cir) {
        if (player.isCreative()) {
            return;
        }

        if (clickAction != ClickAction.SECONDARY) {
            return;
        }

        Image2Map.destroyBundleOnEmpty(itemStack);
    }

    /**
     * When holding an item with the mouse in the inventory,
     * and left-clicking a slot with a bundle, only place
     * the item into the bundle if it's a valid image2map map for the bundle.
     * Overridden if the player is in creative mode.
     */
    @Inject(method = "overrideOtherStackedOnMe", at = @At("HEAD"), cancellable = true)
    private void image2map$overrideOtherStackedOnMeHead(
        ItemStack bundle,
        ItemStack otherStack,
        Slot slot,
        ClickAction clickAction,
        Player player,
        SlotAccess slotAccess,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (player.isCreative()) {
            return;
        }

        if (clickAction != ClickAction.PRIMARY) {
            return;
        }

        if (Image2Map.isInvalidMapForBundle(bundle, otherStack)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * When holding no item with the mouse in the inventory,
     * and right-clicking a slot with a bundle,
     * destroy the bundle if it ends up empty.
     * Overridden if the player is in creative mode.
     */
    @Inject(method = "overrideOtherStackedOnMe", at = @At("RETURN"))
    private void image2map$overrideOtherStackedOnMeReturn(
        ItemStack bundle,
        ItemStack otherStack,
        Slot slot,
        ClickAction clickAction,
        Player player,
        SlotAccess slotAccess,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (player.isCreative()) {
            return;
        }

        if (clickAction != ClickAction.SECONDARY) {
            return;
        }

        Image2Map.destroyBundleOnEmpty(bundle);
    }
}
