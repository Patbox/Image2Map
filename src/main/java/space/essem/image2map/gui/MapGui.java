package space.essem.image2map.gui;

import com.google.common.base.Predicates;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import eu.pb4.mapcanvas.api.core.*;
import eu.pb4.mapcanvas.api.utils.VirtualDisplay;
import eu.pb4.sgui.api.gui.HotbarGui;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.player.PlayerPosition;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import java.util.Collections;
import java.util.EnumSet;
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

    public MapGui(ServerPlayerEntity player, int width, int height) {
        super(player);
        var pos = player.getBlockPos().withY(2048);
        this.pos = pos;

        this.entity = new HorseEntity(EntityType.HORSE, player.getServerWorld());
        this.entity.setYaw(0);
        this.entity.setHeadYaw(0);
        this.entity.setNoGravity(true);
        this.entity.setPitch(0);
        this.entity.setInvisible(true);
        this.initialize(width, height);

        //this.cursorX = this.canvas.getWidth();
        //this.cursorY = this.canvas.getHeight(); // MapDecoration.Type.TARGET_POINT
        //this.cursor = null;//this.canvas.createIcon(MapIcon.Type.TARGET_POINT, true, this.cursorX, this.cursorY, (byte) 14, null);
        player.networkHandler.sendPacket(new EntitySpawnS2CPacket(this.entity.getId(), this.entity.getUuid(),
                this.entity.getX(), this.entity.getY(), this.entity.getZ(), this.entity.getPitch(), entity.getYaw(), entity.getType(), 0, Vec3d.ZERO, entity.getHeadYaw()));

        player.networkHandler.sendPacket(new EntityTrackerUpdateS2CPacket(this.entity.getId(), this.entity.getDataTracker().getChangedEntries()));
        player.networkHandler.sendPacket(new SetCameraEntityS2CPacket(this.entity));
        //this.xRot = player.getYaw();
        //this.yRot = player.getPitch();
        var buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeVarInt(this.entity.getId());
        buf.writeIntArray(new int[]{player.getId()});
        player.networkHandler.sendPacket(EntityPassengersSetS2CPacketAccessor.createEntityPassengersSetS2CPacket(buf));
        player.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.GAME_MODE_CHANGED, GameMode.SPECTATOR.getId()));
        player.networkHandler.sendPacket(new EntityS2CPacket.Rotate(player.getId(), (byte) 0, (byte) 0, player.isOnGround()));

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
        this.player.networkHandler.sendPacket(new EntityPositionS2CPacket(this.entity.getId(), new PlayerPosition(this.entity.getPos(), Vec3d.ZERO, this.entity.getYaw(), this.entity.getPitch()), Set.of(), false));
    }

    protected void initialize(int width, int height) {
        this.canvas = DrawableCanvas.create(width, height);
        this.virtualDisplay = VirtualDisplay.of(this.canvas, pos, Direction.NORTH, 0, true);
        //this.renderer = CanvasRenderer.of(new CanvasImage(this.canvas.getWidth(), this.canvas.getHeight()));

        this.canvas.addPlayer(player);
        this.virtualDisplay.addPlayer(player);

        this.entity.setPos(pos.getX() - width / 2d + 1, pos.getY() - height / 2d - 0.5, pos.getZ());
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
        this.player.server.getCommandManager().sendCommandTree(this.player);
        this.player.networkHandler.sendPacket(new SetCameraEntityS2CPacket(this.player));
        this.player.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(this.entity.getId()));
        if (!this.additionalEntities.isEmpty()) {
            this.player.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(this.additionalEntities));
        }
        this.player.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.GAME_MODE_CHANGED, this.player.interactionManager.getGameMode().getId()));
        this.player.networkHandler.sendPacket(new PlayerPositionLookS2CPacket(this.entity.getId(), new PlayerPosition(this.entity.getPos(), Vec3d.ZERO, this.entity.getYaw(), this.entity.getPitch()), Set.of()));

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
    public boolean onClickEntity(int entityId, EntityInteraction type, boolean isSneaking, @Nullable Vec3d interactionPos) {
        /*if (type == EntityInteraction.ATTACK) {
            this.renderer.click(this.cursorX / 2, this.cursorY / 2, ScreenElement.ClickType.LEFT_DOWN);
        } else {
            this.renderer.click(this.cursorX / 2, this.cursorY / 2, ScreenElement.ClickType.RIGHT_DOWN);
        }*/

        return super.onClickEntity(entityId, type, isSneaking, interactionPos);
    }

    public void setDistance(double i) {
        this.entity.setPos(this.entity.getX(), this.entity.getY(), this.pos.getZ() - i);
        this.player.networkHandler.sendPacket(new EntityPositionS2CPacket(this.entity.getId(), new PlayerPosition(this.entity.getPos(), Vec3d.ZERO, this.entity.getYaw(), this.entity.getPitch()), Set.of(), false));
    }

    @Override
    public boolean onPlayerAction(PlayerActionC2SPacket.Action action, Direction direction) {
        if (action == PlayerActionC2SPacket.Action.DROP_ALL_ITEMS) {
            this.close();
        }
        return false;
    }

    public void onPlayerInput(PlayerInput input) {

    }

    public void onPlayerCommand(int id, ClientCommandC2SPacket.Mode command, int data) {
    }

    static {
        var commandNode = new RootCommandNode<CommandSource>();

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

        COMMAND_PACKET = new CommandTreeS2CPacket(commandNode);
    }


    public void executeCommand(String command) {
    }
}
