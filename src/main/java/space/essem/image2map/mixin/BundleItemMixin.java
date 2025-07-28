package space.essem.image2map.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.StackReference;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.ClickType;
import net.minecraft.world.World;
import space.essem.image2map.Image2Map;

@Mixin(BundleItem.class)
public class BundleItemMixin {
    /**
     * When holding a bundle in your hand in-world,
     * and right-clicking to drop an item, destroy the bundle if it ends up empty.
     * Overridden if the player is in creative mode.
     */
    @Inject(method = "dropContentsOnUse", at = @At("RETURN"))
    private void image2map$dropContentsOnUse(World world, PlayerEntity player, ItemStack stack, CallbackInfo ci) {
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
    @Inject(method = "onStackClicked", at = @At("HEAD"), cancellable = true)
    private void image2map$onStackClickedHead(ItemStack bundle, Slot slot, ClickType clickType, PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        if (player.isCreative()) {
            return;
        }

        if (clickType != ClickType.LEFT) {
            return;
        }

        if (Image2Map.isInvalidMapForBundle(bundle, slot.getStack())) {
            cir.setReturnValue(false);
        }
    }

    /**
     * When holding a bundle with the mouse in the inventory,
     * and right-clicking a slot with no item,
     * destroy the bundle if it ends up empty.
     * Overridden if the player is in creative mode.
     */
    @Inject(method = "onStackClicked", at = @At("RETURN"))
    private void image2map$onStackClickedReturn(ItemStack bundle, Slot slot, ClickType clickType, PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        if (player.isCreative()) {
            return;
        }

        if (clickType != ClickType.RIGHT) {
            return;
        }

        Image2Map.destroyBundleOnEmpty(bundle);
    }

    /**
     * When holding an item with the mouse in the inventory,
     * and left-clicking a slot with a bundle, only place
     * the item into the bundle if it's a valid image2map map for the bundle.
     * Overridden if the player is in creative mode.
     */
    @Inject(method = "onClicked", at = @At("HEAD"), cancellable = true)
    private void image2map$onClickedHead(
        ItemStack bundle,
        ItemStack otherStack,
        Slot slot,
        ClickType clickType,
        PlayerEntity player,
        StackReference cursorStackReference,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (player.isCreative()) {
            return;
        }

        if (clickType != ClickType.LEFT) {
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
    @Inject(method = "onClicked", at = @At("RETURN"))
    private void image2map$onClickedReturn(
        ItemStack bundle,
        ItemStack otherStack,
        Slot slot,
        ClickType clickType,
        PlayerEntity player,
        StackReference cursorStackReference,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (player.isCreative()) {
            return;
        }

        if (clickType != ClickType.RIGHT) {
            return;
        }

        Image2Map.destroyBundleOnEmpty(bundle);
    }
}
