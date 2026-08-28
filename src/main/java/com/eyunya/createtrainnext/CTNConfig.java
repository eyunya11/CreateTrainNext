package com.eyunya.createtrainnext;

import org.apache.commons.lang3.tuple.Pair;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The mod's configuration.
 *
 * <p>Client only so far, and deliberately: every setting here changes how something is drawn and
 * nothing else, so each is a matter of taste per player rather than something a server has to agree
 * on. A speed limit that actually governed trains would belong in a server config instead.
 */
public class CTNConfig {

	public static final Client CLIENT;
	public static final ModConfigSpec CLIENT_SPEC;

	static {
		Pair<Client, ModConfigSpec> client = new ModConfigSpec.Builder().configure(Client::new);
		CLIENT = client.getLeft();
		CLIENT_SPEC = client.getRight();
	}

	public static class Client {

		public final ModConfigSpec.DoubleValue signFontScale;

		public final ModConfigSpec.DoubleValue signalLitLampScale;

		public final ModConfigSpec.BooleanValue signalGlow;

		public final ModConfigSpec.DoubleValue signalGlowSize;

		public final ModConfigSpec.DoubleValue signalGlowBrightness;

		private Client(ModConfigSpec.Builder builder) {
			builder.comment("Speed limit signs")
				.push("speed_limit_sign");

			signFontScale = builder
				.comment("How large the limit is drawn on the face of a speed limit sign, as a multiplier.",
					"Every limit is drawn at the same size rather than stretched to fill the face, and",
					"that size is set by the widest one that has to fit, which is '100'.",
					"At the default of 1.7 a limit of 100 spans about 10 of the face's 11 pixels, so it",
					"overlaps the red ring; below about 1.15 three digits stay inside the white centre.",
					"Above 2.0 a three digit limit runs off the edge of the sign altogether.")
				.defineInRange("fontScale", 1.7, 0.25, 4.0);

			builder.pop();

			builder.comment("Multi aspect signals",
					"Both settings below exist for the same reason: a lamp is 1.7 pixels across and the",
					"bezel around it hides all but a 1.6 pixel circle of that, so from far enough away a",
					"lit lamp covers less than a screen pixel and its colour stops being readable. They",
					"attack it from opposite ends and can be used together or separately.")
				.push("multi_aspect_signal");

			signalLitLampScale = builder
				.comment("How much larger a lit lamp is drawn than an unlit one, as a multiplier.",
					"Above 1 the lit lamp is also brought out in front of its bezel, since the bezel",
					"is what was cropping it - without that the extra size would only be drawn behind",
					"the housing and nothing would look any different.",
					"1.0 leaves the lamp exactly as it is and stays behind the bezel. Beyond about 1.35",
					"the corners of one lamp start to reach over its neighbours, which are 2.25 pixels",
					"apart.")
				.defineInRange("litLampScale", 1.0, 1.0, 2.0);

			signalGlow = builder
				.comment("Whether a lit lamp is surrounded by a halo of its own colour.",
					"Drawn additively, the way Create lights its nixie tubes and display links, so it",
					"adds colour to whatever is behind it rather than covering it. This is what makes",
					"an aspect readable at range: the halo is several times the width of the lamp, so",
					"it survives being shrunk to a few pixels on screen when the lamp itself does not.")
				.define("glow", true);

			signalGlowSize = builder
				.comment("How wide the halo is, in pixels. The lamp it sits on is 1.7 wide and the",
					"sockets are 2.25 apart, so anything above about 2.25 spills onto the neighbouring",
					"lamps - which is intended, and is how a real signal looks at night.")
				.defineInRange("glowSize", 5.0, 1.0, 16.0);

			signalGlowBrightness = builder
				.comment("How strong the halo is, from 0 to 1. Because it is added to what is behind it,",
					"high values wash the housing out to white near the lamp.")
				.defineInRange("glowBrightness", 0.5, 0.0, 1.0);

			builder.pop();
		}
	}

	private CTNConfig() {}
}
