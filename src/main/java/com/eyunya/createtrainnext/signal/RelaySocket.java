package com.eyunya.createtrainnext.signal;

/**
 * The seven lamp sockets on a relay head, and where each one sits on the face.
 *
 * <p>Seven for three bars of three because {@link #CENTER} belongs to all of them: across, on the
 * diagonal and upright all pass through the middle. That is what lets one head show three
 * indications without seven lamps' worth of clutter, and it is why the centre lamp is lit in every
 * indication but {@link RelayIndication#DARK}.
 *
 * <p><b>This is the only place a lamp's position lives.</b> The lamp models are drawn centred on
 * the model origin and carry no position of their own, so moving a lamp is one pair of numbers
 * here — nothing else has to be regenerated, and nothing can drift out of step with anything else.
 *
 * <p>To re-measure after moving a socket in the housing: the numbers are the centre of that
 * socket's bezel ring, in pixels, in the housing model's own south-facing space, with x running
 * right and y running up from the bottom of the block. Depth is not here because every socket
 * shares it; that is {@code RelaySignalRenderer.LENS_Z}, and it is the ring's own front face.
 *
 * <p>The diagonal pair is not symmetric with the others — and is not symmetric with itself, sitting
 * 2.825 right of centre but 2.925 left of it. That is how the housing is built; the numbers are
 * copied from it rather than tidied, so the lamps land in their sockets rather than near them.
 */
public enum RelaySocket {

	CENTER(8F, 7.875F, RelayIndication.STOP, RelayIndication.RESTRICTED, RelayIndication.PROCEED),

	LEFT(4F, 7.875F, RelayIndication.STOP),
	RIGHT(12F, 7.875F, RelayIndication.STOP),

	LOWER_LEFT(5.075F, 5.35F, RelayIndication.RESTRICTED),
	UPPER_RIGHT(10.825F, 10.35F, RelayIndication.RESTRICTED),

	BOTTOM(8F, 4.375F, RelayIndication.PROCEED),
	TOP(8F, 11.375F, RelayIndication.PROCEED);

	private final float x;
	private final float y;
	private final int litBy;

	RelaySocket(float x, float y, RelayIndication... litBy) {
		this.x = x;
		this.y = y;
		int mask = 0;
		for (RelayIndication indication : litBy)
			mask |= 1 << indication.ordinal();
		this.litBy = mask;
	}

	/** How far right of the block's west face this socket's centre sits, in blocks. */
	public float x() {
		return x / 16F;
	}

	/** How far above the block's floor this socket's centre sits, in blocks. */
	public float y() {
		return y / 16F;
	}

	/** Whether this socket is one of the three an indication lights. */
	public boolean litBy(RelayIndication indication) {
		return (litBy & (1 << indication.ordinal())) != 0;
	}
}
