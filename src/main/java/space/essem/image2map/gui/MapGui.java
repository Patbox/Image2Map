package space.essem.image2map.gui;

import com.google.common.base.Predicates;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import eu.pb4.mapcanvas.api.core.*;
import eu.pb4.mapcanvas.api.utils.VirtualDisplay;
import eu.pb4.sgui.api.gui.HotbarGui;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import space.essem.image2map.mixin.EntityPassengersSetS2CPacketAccessor;


public class MapGui extends HotbarGui {
    private static final Packet<?> COMMAND_PACKET;

    public final Entity entity;
    public CombinedPlayerCanvas canvas;
    public VirtualDisplay virtualDisplay;
    //public CanvasRenderer renderer;
    public final BlockPos pos;
    //public final CanvasIcon cursor;

    public final IntList additionalEntities = new IntArrayList();

    //public float xRot;
    //public float yRot;
    //public int cursorX;
    //public int cursorY;
    //public int mouseMoves;

    public MapGui(ServerPlayer player, int width, int height) {
        super(player);
        var pos = player.blockPosition().atY(2048);
        this.pos = pos;

        this.entity = new Horse(EntityType.HORSE, player.level());
        this.entity.setYRot(0);
        this.entity.setYHeadRot(0);
        this.entity.setNoGravity(true);
        this.entity.setXRot(0);
        this.entity.setInvisible(true);
        this.initialize(width, height);

        //this.cursorX = this.canvas.getWidth();
        //this.cursorY = this.canvas.getHeight(); // MapDecoration.Type.TARGET_POINT
        //this.cursor = null;//this.canvas.createIcon(MapIcon.Type.TARGET_POINT, true, this.cursorX, this.cursorY, (byte) 14, null);
        player.connection.send(new ClientboundAddEntityPacket(this.entity.getId(), this.entity.getUUID(),
                this.entity.getX(), this.entity.getY(), this.entity.getZ(), this.entity.getXRot(), entity.getYRot(), entity.getType(), 0, Vec3.ZERO, entity.getYHeadRot()));

        player.connection.send(new ClientboundSetEntityDataPacket(this.entity.getId(), this.entity.getEntityData().getNonDefaultValues()));
        player.connection.send(new ClientboundSetCameraPacket(this.entity));
        //this.xRot = player.getYaw();
        //this.yRot = player.getPitch();
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(this.entity.getId());
        buf.writeVarIntArray(new int[]{player.getId()});
        player.connection.send(EntityPassengersSetS2CPacketAccessor.createEntityPassengersSetS2CPacket(buf));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, GameType.SPECTATOR.getId()));
        player.connection.send(new ClientboundMoveEntityPacket.Rot(player.getId(), (byte) 0, (byte) 0, player.onGround()));

        //player.networkHandler.sendPacket(COMMAND_PACKET);

        for (int i = 0; i < 9; i++) {
            this.setSlot(i, new ItemStack(Items.STICK));
        }

        //player.networkHandler.sendPacket(new GameMessageS2CPacket(Text.translatable("polyport.cc.press_to_close", "Ctrl", "Q (Drop)"/*new KeybindComponent("key.drop")*/).formatted(Formatting.DARK_RED), true));
        this.open();
    }

    protected void resizeCanvas(int width, int height) {
        this.destroy();
        this.initialize(width, height);
        this.player.connection.send(new ClientboundTeleportEntityPacket(this.entity.getId(), new PositionMoveRotation(this.entity.position(), Vec3.ZERO, this.entity.getYRot(), this.entity.getXRot()), Set.of(), false));
    }

    protected void initialize(int width, int height) {
        this.canvas = DrawableCanvas.create(width, height);
        this.virtualDisplay = VirtualDisplay.builder(this.canvas, pos, Direction.NORTH).glowing().build();
        //this.renderer = CanvasRenderer.of(new CanvasImage(this.canvas.getWidth(), this.canvas.getHeight()));

        this.canvas.addPlayer(player);
        this.virtualDisplay.addPlayer(player);

        this.entity.setPosRaw(pos.getX() - width / 2d + 1, pos.getY() - height / 2d - 0.5, pos.getZ());
    }

    protected void destroy() {
        this.virtualDisplay.removePlayer(this.player);
        this.virtualDisplay.destroy();
        //this.virtualDisplay2.destroy();
        this.canvas.removePlayer(this.player);
        this.canvas.destroy();
    }

    /*public void render() {
        this.renderer.render(this.player.world.getTime(), 0, 0/*this.cursorX / 2, this.cursorY / 2);
        // Debug maps
        if (false && FabricLoader.getInstance().isDevelopmentEnvironment()) {
            for (int x = 0; x < this.canvas.getSectionsWidth(); x++) {
                CanvasUtils.fill(this.renderer.canvas(), x * 128, 0, x * 128 + 1, this.canvas.getHeight(), CanvasColor.RED_HIGH);
            }
            for (int x = 0; x < this.canvas.getSectionsHeight(); x++) {
                CanvasUtils.fill(this.renderer.canvas(), 0,x * 128, this.canvas.getWidth(), x * 128 + 1, CanvasColor.BLUE_HIGH);
            }
        }

        CanvasUtils.draw(this.canvas, 0, 0, this.renderer.canvas());
        this.canvas.sendUpdates();
    }*/

    /*@Override
    public void onTick() {
        this.render();
    }*/

    @Override
    public void onClose() {
        //this.cursor.remove();
        this.destroy();
        this.player.level().getServer().getCommands().sendCommands(this.player);
        this.player.connection.send(new ClientboundSetCameraPacket(this.player));
        this.player.connection.send(new ClientboundRemoveEntitiesPacket(this.entity.getId()));
        if (!this.additionalEntities.isEmpty()) {
            this.player.connection.send(new ClientboundRemoveEntitiesPacket(this.additionalEntities));
        }
        this.player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, this.player.gameMode.getGameModeForPlayer().getId()));
        this.player.connection.send(new ClientboundPlayerPositionPacket(this.player.getId(), new PositionMoveRotation(this.player.position(), Vec3.ZERO, this.player.getYRot(), this.player.getXRot()), Set.of()));
        if (this.player.isPassenger()) {
            this.player.connection.send(new ClientboundSetPassengersPacket(Objects.requireNonNull(this.player.getVehicle())));
        }
        super.onClose();
    }

    public void onCommandSuggestion(int id, String fullCommand) {

    }

    public void onCameraMove(float xRot, float yRot) {
        /*this.mouseMoves++;

        if (this.mouseMoves < 16) {
            return;
        }

        this.xRot = xRot;
        this.yRot = yRot;

        this.cursorX = this.cursorX + (int) ((xRot > 0.3 ? 3: xRot < -0.3 ? -3 : 0) * (Math.abs(xRot) - 0.3));
        this.cursorY = this.cursorY + (int) ((yRot > 0.3 ? 3 : yRot < -0.3 ? -3 : 0) * (Math.abs(yRot) - 0.3));

        this.cursorX = MathHelper.clamp(this.cursorX, 5, this.canvas.getWidth() * 2 - 5);
        this.cursorY = MathHelper.clamp(this.cursorY, 5, this.canvas.getHeight() * 2 - 5);

        //this.cursor.move(this.cursorX + 4, this.cursorY + 4, this.cursor.getRotation());*/
    }

    @Override
    public boolean canPlayerClose() {
        return false;
    }

    @Override
    public boolean onClickEntity(int entityId, EntityInteraction type, boolean isSneaking, @Nullable Vec3 interactionPos) {
        /*if (type == EntityInteraction.ATTACK) {
            this.renderer.click(this.cursorX / 2, this.cursorY / 2, ScreenElement.ClickType.LEFT_DOWN);
        } else {
            this.renderer.click(this.cursorX / 2, this.cursorY / 2, ScreenElement.ClickType.RIGHT_DOWN);
        }*/

        return super.onClickEntity(entityId, type, isSneaking, interactionPos);
    }

    public void setDistance(double i) {
        this.entity.setPosRaw(this.entity.getX(), this.entity.getY(), this.pos.getZ() - i);
        this.player.connection.send(new ClientboundTeleportEntityPacket(this.entity.getId(), new PositionMoveRotation(this.entity.position(), Vec3.ZERO, this.entity.getYRot(), this.entity.getXRot()), Set.of(), false));
    }

    @Override
    public boolean onPlayerAction(ServerboundPlayerActionPacket.Action action, Direction direction) {
        if (action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS) {
            this.close();
        }
        return false;
    }

    public void onPlayerInput(Input input) {

    }

    public void onPlayerCommand(int id, ServerboundPlayerCommandPacket.Action command, int data) {
    }

    static {
        var commandNode = new RootCommandNode<SharedSuggestionProvider>();

        commandNode.addChild(
            new ArgumentCommandNode<>(
                "command",
                StringArgumentType.greedyString(),
                null,
                Predicates.alwaysTrue(),
                null,
                null,
                true,
                (ctx, builder) -> null
            )
        );

        COMMAND_PACKET = new ClientboundCommandsPacket(commandNode, new ClientboundCommandsPacket.NodeInspector<SharedSuggestionProvider>() {
            @Nullable
            @Override
            public Identifier suggestionId(ArgumentCommandNode<SharedSuggestionProvider, ?> node) {
                return null;
            }

            @Override
            public boolean isExecutable(CommandNode<SharedSuggestionProvider> node) {
                return true;
            }

            @Override
            public boolean isRestricted(CommandNode<SharedSuggestionProvider> node) {
                return false;
            }
        });
    }


    public void executeCommand(String command) {
    }
}
