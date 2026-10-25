package com.eyunya.createtrainnext.signal;

/**
 * The plates a signal's pole carries, which are how signals that look alike are told apart.
 *
 * <p>場内, 出発 and 閉そく signals have the same heads; what says which is which is on the pole. A
 * 閉そく signal has a number plate under its head (the national standard asks for one, 解釈基準 Ⅹ－26), and
 * at a station with many tracks the 場内 and 出発 signals have a crown above theirs saying which track they
 * are for. Both are a plate with a few characters on it, so they are one block class with this to tell
 * them apart. A plate saying 場内 or 出発, as some private railways put on, is a number plate with that
 * written on it.
 *
 * <p>Each kind says where its plate is in its model, as drawn facing south, unturned: the renderer writes
 * on the plate's front face from these numbers, the block's shape is made from them, and a game test reads
 * the model and holds the two together, as {@code SignalModelTests} does for the heads. The colours of the
 * plates are the models' textures, and only the colour of the writing is here.
 */
public enum PlateKind {

	/** 閉そく信号機標識. Under the head, white with black figures until the colours are known. */
	NUMBER("signal_number_plate", 4, 0xFF000000, 5, 5, 9, 11, 11, 10),

	/** 冠, the track a 場内 or 出発 signal is for. Over the head, black with white figures. */
	CROWN("signal_crown_plate", 6, 0xFFFFFFFF, 4, 3, 9, 12, 8, 10);

	private final String blockName;
	private final int maxLength;
	private final int textColour;
	private final double[] bounds;

	PlateKind(String blockName, int maxLength, int textColour, double x0, double y0, double z0, double x1,
		double y1, double z1) {
		this.blockName = blockName;
		this.maxLength = maxLength;
		this.textColour = textColour;
		this.bounds = new double[] { x0, y0, z0, x1, y1, z1 };
	}

	/** The name of the block, and of its item, in the registry; also the folder its models are in. */
	public String blockName() {
		return blockName;
	}

	/** How many characters fit on the plate. Longer text is cut to this when it is set. */
	public int maxLength() {
		return maxLength;
	}

	/** The colour the text is written in, as ARGB. */
	public int textColour() {
		return textColour;
	}

	/**
	 * The plate as drawn and unturned: x0, y0, z0, x1, y1, z1 in pixels. Its front, which the text is
	 * written on, is the face at z1, towards the south.
	 */
	public double[] plateBounds() {
		return bounds.clone();
	}
}
