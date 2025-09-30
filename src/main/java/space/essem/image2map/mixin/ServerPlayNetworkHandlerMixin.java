package space.essem.image2map.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import eu.pb4.sgui.virtual.VirtualScreenHandlerInterface;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.message.LastSeenMessageList;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.essem.image2map.gui.MapGui;


@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin extends ServerCommonNetworkHandler {
    @Shadow
    public ServerPlayerEntity player;

    @Shadow private double lastTickX;

    @Shadow private double lastTickY;

    @Shadow private double lastTickZ;

    @Shadow public abstract void syncWithPlayerPosition();

    public ServerPlayNetworkHandlerMixin(MinecraftServer server, ClientConnection connection, ConnectedClientData clientData) {
        super(server, connection, clientData);
    }

    @WrapWithCondition(method = "method_73086", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;updatePositionAndAngles(DDDFF)V"))
    private boolean image2map$allowMovement(ServerPlayerEntity instance, double x, double y, double z, float p, float yaw) {
        if (this.player.currentScreenHandler instanceof VirtualScreenHandlerInterface handler && handler.getGui() instanceof MapGui computerGui) {
            double l = instance.getX() - this.lastTickX;
            double m = instance.getY() - this.lastTickY;
            double n = instance.getZ() - this.lastTickZ;
            this.player.getEntityWorld().getChunkManager().updatePosition(this.player);
            this.player.handleFall(l, m , n, player.isOnGround());
            this.player.setOnGround(player.isOnGround());
            this.syncWithPlayerPosition();
            return false;
        }
        return true;
    }

    /*@Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void image2map$onMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if (this.player.currentScreenHandler instanceof VirtualScreenHandlerInterface handler && handler.getGui() instanceof MapGui computerGui) {
            this.sendPacket(new EntityS2CPacket.Rotate(player.getId(), (byte) 0, (byte) 0, player.isOnGround()));
            this.server.execute(() -> {
                var xRot = packet.getPitch (computerGui.xRot);
                var yRot = packet.getYaw(computerGui.yRot);
                if (xRot != 0 || yRot != 0) {
                    computerGui.onCameraMove(yRot, xRot);
                }
            });
            ci.cancel();
        }
    }*/

    @Inject(method = "onRequestCommandCompletions", at = @At("HEAD"), cancellable = true)
    private void image2map$onCustomSuggestion(RequestCommandCompletionsC2SPacket packet, CallbackInfo ci) {
        if (this.player.currentScreenHandler instanceof VirtualScreenHandlerInterface handler && handler.getGui() instanceof MapGui computerGui) {
            this.server.execute(() -> {
                computerGui.onCommandSuggestion(packet.getCompletionId(), packet.getPartialCommand());
            });
            ci.cancel();
        }
    }

    @Inject(method = "executeCommand", at = @At("HEAD"), cancellable = true)
    private void image2map$onCommandExecution(String command, CallbackInfo ci) {
        if (this.player.currentScreenHandler instanceof VirtualScreenHandlerInterface handler && handler.getGui() instanceof MapGui computerGui) {
            computerGui.executeCommand(command);
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerInput", at = @At("HEAD"), cancellable = true)
    private void image2map$onPlayerInput(PlayerInputC2SPacket packet, CallbackInfo ci) {
        if (this.player.currentScreenHandler instanceof VirtualScreenHandlerInterface handler && handler.getGui() instanceof MapGui computerGui) {
            this.server.execute(() -> {
                computerGui.onPlayerInput(packet.input());
            });
            ci.cancel();
        }
    }

    @Inject(method = "onClientCommand", at = @At("HEAD"), cancellable = true)
    private void image2map$onClientCommand(ClientCommandC2SPacket packet, CallbackInfo ci) {
        if (this.player.currentScreenHandler instanceof VirtualScreenHandlerInterface handler && handler.getGui() instanceof MapGui computerGui) {
            this.server.execute(() -> {
                computerGui.onPlayerCommand(packet.getEntityId(), packet.getMode(), packet.getMountJumpHeight());
            });
            ci.cancel();
        }
    }
}
