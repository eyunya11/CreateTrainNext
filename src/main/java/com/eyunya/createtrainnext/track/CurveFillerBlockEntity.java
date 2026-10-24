package com.eyunya.createtrainnext.track;

import java.util.Arrays;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What a {@link CurveFillerBlock} holds up, and the countdown that lets it clean up after itself.
 *
 * <p>The floor of a block is not one height but {@link CurveOccupancy#CELLS} by {@link CurveOccupancy#CELLS}
 * of them, a column a quarter of a block across for each, at the height the rails pass over it. One height
 * for the whole block was a step of most of a block wherever a curve climbs at 45 degrees, since the rails
 * rise a block in every block they cross, and it lay three quarters of a block under them at the high side.
 * A quarter of a block of rise is a step anyone walks up. A column the rails do not pass over is empty, so the
 * floor is the width of the rails and not of the blocks they cross.
 *
 * <p>The heights are saved and sent, since the client works out collision too and has to agree with the
 * server about where someone is standing. The countdown is not: a filler that survives a reload starts its
 * count again, and the anchor that owns the curve renews it within a few seconds if the curve is still there.
 * That is the whole point of the count — it exists to outlive a change made while this chunk was not loaded,
 * and a value carried across a reload would only make it outlive more.
 */
public class CurveFillerBlockEntity extends BlockEntity {

	private static final String TAG_FLOOR = "Floor";

	/**
	 * Random ticks, not game ticks. A block is picked for one rarely enough that three of them is
	 * several minutes, while the anchor renews the filler every five seconds — so a filler under a
	 * live curve never gets close to running out.
	 */
	private static final int LIVES = 3;

	private int remaining = LIVES;

	/**
	 * The height of the floor in each column, in pixels, {@link CurveOccupancy#EMPTY} where there is none;
	 * x along a row, rows along z. Null for a filler that has not been given one, which holds up the whole
	 * block at the height its state says, as every filler did before there were columns.
	 */
	@Nullable
	private byte[] floor;

	@Nullable
	private VoxelShape shape;

	public CurveFillerBlockEntity(BlockPos pos, BlockState state) {
		super(CTNRegistry.CURVE_FILLER_ENTITY.get(), pos, state);
	}

	public void keepAlive() {
		remaining = LIVES;
	}

	public void expire() {
		remaining--;
		if (remaining <= 0 && level != null)
			level.removeBlock(worldPosition, false);
	}

	/** The heights of the floor's columns, as {@link CurveOccupancy#rasterise} gives them; null for none. */
	@Nullable
	public byte[] floor() {
		return floor == null ? null : floor.clone();
	}

	/** Puts the floor at these heights, and tells the clients if that changed anything. */
	public void setFloor(byte[] heights) {
		if (Arrays.equals(floor, heights))
			return;
		floor = heights.clone();
		shape = null;
		setChanged();
		if (level != null && !level.isClientSide)
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
	}

	/** What holds someone up here, or null to take the whole block at the height in the block state. */
	@Nullable
	public VoxelShape collisionShape() {
		if (floor == null)
			return null;
		if (shape == null)
			shape = CurveOccupancy.shapeOf(floor);
		return shape;
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		if (floor != null)
			tag.putByteArray(TAG_FLOOR, floor);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		byte[] read = tag.getByteArray(TAG_FLOOR);
		floor = read.length == CurveOccupancy.CELLS * CurveOccupancy.CELLS ? read : null;
		shape = null;
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveCustomOnly(registries);
	}

	@Override
	public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
