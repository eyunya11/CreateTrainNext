package com.eyunya.createtrainnext.signal;

/**
 * The seven lamp sockets on a relay head, and where each sits on the face.
 *
 * <p>Seven for three bars of three because {@link #CENTER} belongs to all of them: across, on the
 * diagonal and upright all pass through the middle. That is what lets one head show three
 * indications without seven lamps' worth of clutter, and it is why the centre lamp is lit in every
 * indication but {@link RelayIndication#DARK}.
 *
 * <p>The offsets are pixels from the centre socket, read straight off the lamp groups in
 * relay_traffic_signal.json rather than chosen here — the housing is the model's, and a lamp drawn
 * anywhere else would sit beside its bezel instead of in it. The diagonal pair is not quite
 * symmetric with the others because it is not quite symmetric in the model either; it is copied as
 * it stands rather than tidied, so the lamps land where the sockets actually are.
 */
public enum RelaySocket {

	CENTER(0F, 0F, RelayIndication.STOP, RelayIndication.RESTRICTED, RelayIndication.PROCEED),

	LEFT(-4F, 0F, RelayIndication.STOP),
	RIGHT(4F, 0F, RelayIndication.STOP),

	LOWER_LEFT(-2.925F, -2.525F, RelayIndication.RESTRICTED),
	UPPER_RIGHT(2.7625F, 2.50089F, RelayIndication.RESTRICTED),

	BOTTOM(0F, -3.5F, RelayIndication.PROCEED),
	TOP(0F, 3.5F, RelayIndication.PROCEED);

	private final float localX;
	private final float localY;
	private final int litBy;

	RelaySocket(float localX, float localY, RelayIndication... litBy) {
		this.localX = localX;
		this.localY = localY;
		int mask = 0;
		for (RelayIndication indication : litBy)
			mask |= 1 << indication.ordinal();
		this.litBy = mask;
	}

	/** How far right of the centre socket this one sits, in blocks, in the model's own local space. */
	public float localX() {
		return localX / 16F;
	}

	/** How far above the centre socket this one sits, in blocks. */
	public float localY() {
		return localY / 16F;
	}

	/** Whether this socket is one of the three an indication lights. */
	public boolean litBy(RelayIndication indication) {
		return (litBy & (1 << indication.ordinal())) != 0;
	}
}
