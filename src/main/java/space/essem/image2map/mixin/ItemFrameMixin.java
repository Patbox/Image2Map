package space.essem.image2map.mixin;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.ListTag;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumSet;
import java.util.List;

@Mixin(ItemFrameEntity.class)
public abstract class ItemFrameMixin extends AbstractDecorationEntity {
	private ItemFrameMixin(EntityType<? extends AbstractDecorationEntity> entityType) {
		super(entityType, null);
		throw new IllegalStateException("this should never be called!");
	}

	@Inject(method = "setHeldItemStack(Lnet/minecraft/item/ItemStack;Z)V", at =
		@At(value = "INVOKE", shift = At.Shift.AFTER, target = "net/minecraft/world/World.updateComparators(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;)V"))
	private void checkForPosterMap(ItemStack value, boolean update, CallbackInfo ci) {
		if (value.getItem() != Items.FILLED_MAP ||
			  value.getTag() == null ||
			  this.facing.getAxis().isVertical() ||
			  !value.getTag().contains("i2mStoredMaps", 9) ||
			  !(value.getTag().getList("i2mStoredMaps", 9).get(0) instanceof ListTag)
		) return;
		ListTag maps = (ListTag) value.getTag().get("i2mStoredMaps");
		if (maps != null) {
			int hSize = ((ListTag)maps.get(0)).size();
			int vSize = maps.size();
			Vec3d origin = this.getPos();
			Vec3d hVec = Vec3d.of(facing.rotateYCounterclockwise().getVector());
			origin = verifyOrigin(hSize, origin, hVec);
			if (origin == null) {
				value.setCustomName(new LiteralText("Invalid Item Frame Structure"));
				return;
			}
			origin = verifyOrigin(vSize, origin, new Vec3d(0.0, 1.0, 0.0));
			if (origin == null) {
				value.setCustomName(new LiteralText("Invalid Item Frame Structure"));
				return;
			}
			ItemFrameEntity[][] posterFrames = new ItemFrameEntity[vSize][hSize];
			for (int y = 0; y < vSize; y++) {
				for (int x = 0; x < hSize; x++) {
					ItemFrameEntity frame = this.getAlignedFrameAt(
						  origin.add(hVec.multiply(x))
								.add(0.0, y, 0.0));
					if (frame == null || (!this.equals(frame) && !frame.getHeldItemStack().isEmpty())) {
						value.setCustomName(new LiteralText("Invalid Item Frame Structure"));
						return;
					}
					posterFrames[y][x] = frame;
				}
			}
			for (int y = 0; y < vSize; y++) {
				if (!(maps.get(y) instanceof ListTag) || ((ListTag) maps.get(y)).size() < hSize) {
					value.setCustomName(new LiteralText("Invalid Item NBT"));
					return;
				}
				for (int x = 0; x < hSize; x++) {
					ItemStack frameStack = new ItemStack(Items.FILLED_MAP, 1);
					frameStack.putSubTag("map", ((ListTag)maps.get(y)).get(x));
					posterFrames[vSize - y - 1][x].setHeldItemStack(frameStack, true);
				}
			}
			System.out.println("Magic Map put in item frame!\n");
			System.out.println("Origin Pos: " + origin);
			System.out.println("Image Size is: " + hSize + "x" + vSize);
			System.out.println("Facing " + this.facing.toString());

		}
	}

	/**
	 * Ensures there is at least `target` item frames in a row, returning the position of the first one in the row.
	 * @param target the target number of item frames
	 * @param origin the initial position for the search
	 * @param searchDir the direction to move each search
	 * @return the position of the first item frame.
	 */
	private Vec3d verifyOrigin(int target, Vec3d origin, Vec3d searchDir) {
		int found = 0;
		// ensure origin is unique since we mutate it
		origin = new Vec3d(origin.x, origin.y, origin.z);
		Vec3d searchPos = origin;
		boolean reverseSearch = false; // when we run out of item frames in forward search we reverse to find the new origin
		while (found < target) {
			if (!reverseSearch) {
				List<ItemFrameEntity> itemFramesInBlock = world.getEntitiesByType(EntityType.ITEM_FRAME, Box.method_29968(searchPos.floorAlongAxes(EnumSet.of(
					  Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z))), entity -> true);
				ItemFrameEntity alignedFrame = this.getAlignedFrameAt(searchPos);
				if (alignedFrame != null && (alignedFrame == (Object)this || alignedFrame.getHeldItemStack().isEmpty())) {
					searchPos = searchPos.add(searchDir);
					found++;
				} else {
					reverseSearch = true;
				}
			} else {
				//start searching left from the origin pos minus one (since we already searched the origin pos)
				// we also move the origin left because we want the detected maps to be between originPos and searchPos
				origin = origin.subtract(searchDir);
				if (this.getAlignedFrameAt(origin) != null) {
					searchPos = searchPos.subtract(searchDir);
					found++;
				} else break;
			}
		}
		return found == target ? origin : null;
	}
	private ItemFrameEntity getAlignedFrameAt(Vec3d pos) {
		List<ItemFrameEntity> itemFramesInBlock = world.getEntitiesByType(
			  EntityType.ITEM_FRAME,
			  Box.method_29968(pos.floorAlongAxes(EnumSet.of(
			  	  Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z))),
			  entity -> entity.getHorizontalFacing().equals(this.getHorizontalFacing()));
		return itemFramesInBlock.size() > 0 ? itemFramesInBlock.get(0) : null;
	}
}
