package space.essem.image2map.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientboundSetPassengersPacket.class)
public interface EntityPassengersSetS2CPacketAccessor {
    @Invoker("<init>")
    static ClientboundSetPassengersPacket createEntityPassengersSetS2CPacket(FriendlyByteBuf buf) {
        throw new UnsupportedOperationException();
    }
}
