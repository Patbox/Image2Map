package space.essem.image2map.mixin;

import eu.pb4.sgui.virtual.VirtualScreenHandlerInterface;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;

import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.essem.image2map.gui.MapGui;

@Mixin(PlayerEntity.class)
public class PlayerEntityMixin {

    @Shadow public ScreenHandler currentScreenHandler;

    @Inject(method = "damage", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;damage(Lnet/minecraft/entity/damage/DamageSource;F)Z", shift = At.Shift.BEFORE))
    private void image2map$closeOnDamage(DamageSource damageSource, float f, CallbackInfoReturnable<Boolean> cir) {
        if (f > 0 && this.currentScreenHandler instanceof VirtualScreenHandlerInterface handler && handler.getGui() instanceof MapGui computerGui) {
            computerGui.close();
        }
    }
}
