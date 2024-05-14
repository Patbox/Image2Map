package space.essem.image2map.mixin;

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

    public ServerPlayNetworkHandlerMixin(MinecraftServer server, ClientConnection connection, ConnectedClientData clientData) {
        super(server, connection, clientData);
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
                computerGui.onPlayerInput(packet.getForward(), packet.getSideways(), packet.isJumping(), packet.isSneaking());
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
