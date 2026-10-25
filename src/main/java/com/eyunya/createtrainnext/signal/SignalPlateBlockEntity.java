package com.eyunya.createtrainnext.signal;

import java.util.List;

import com.eyunya.createtrainnext.CTNRegistry;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.StringUtil;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a {@link SignalPlateBlock} says.
 *
 * <p>Nothing else: a plate tells people which signal is which and tells trains nothing.
 */
public class SignalPlateBlockEntity extends SmartBlockEntity {

	private static final String TAG_TEXT = "Text";

	private String text = "";

	public SignalPlateBlockEntity(BlockPos pos, BlockState state) {
		super(CTNRegistry.SIGNAL_PLATE_ENTITY.get(), pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

	/** The kind of plate this stands in, from its block. */
	public PlateKind kind() {
		return getBlockState().getBlock() instanceof SignalPlateBlock plate ? plate.kind() : PlateKind.NUMBER;
	}

	public String getText() {
		return text;
	}

	/**
	 * Writes on the plate, and sends what it says to the players who can see it.
	 *
	 * <p>The text is tidied here, so that whatever asked for it — the screen, a packet from something that
	 * never had one, a saved plate — the plate holds the same: no characters chat would refuse, no spaces at
	 * either end, and no more characters than {@link PlateKind#maxLength()}, counted as a person counts them,
	 * so a kanji is one.
	 */
	public void setText(String requested) {
		String tidied = tidy(requested, kind().maxLength());
		if (tidied.equals(text))
			return;
		text = tidied;
		notifyUpdate();
	}

	static String tidy(String requested, int maxLength) {
		String filtered = StringUtil.filterText(requested == null ? "" : requested)
			.strip();
		if (filtered.codePointCount(0, filtered.length()) <= maxLength)
			return filtered;
		return filtered.substring(0, filtered.offsetByCodePoints(0, maxLength))
			.strip();
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		tag.putString(TAG_TEXT, text);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		text = tidy(tag.getString(TAG_TEXT), kind().maxLength());
	}
}
