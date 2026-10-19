package com.eyunya.createtrainnext;

import org.apache.commons.lang3.tuple.Pair;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The mod's configuration.
 *
 * <p>Split the way the settings themselves divide. Everything under {@link Client} changes how
 * something is drawn and nothing else, so each is a matter of taste per player. {@link Server}
 * holds the settings that change what is actually in the world, which every player connected has
 * to agree on — the one there decides whether a curve is something you fall through or something
 * you walk along.
 */
public class CTNConfig {

	public static final Client CLIENT;
	public static final ModConfigSpec CLIENT_SPEC;

	public static final Server SERVER;
	public static final ModConfigSpec SERVER_SPEC;

	static {
		Pair<Client, ModConfigSpec> client = new ModConfigSpec.Builder().configure(Client::new);
		CLIENT = client.getLeft();
		CLIENT_SPEC = client.getRight();

		Pair<Server, ModConfigSpec> server = new ModConfigSpec.Builder().configure(Server::new);
		SERVER = server.getLeft();
		SERVER_SPEC = server.getRight();
	}

	public static class Server {

		public final ModConfigSpec.BooleanValue curveCollision;

		public final ModConfigSpec.IntValue maxGrade;

		public final ModConfigSpec.IntValue snapAngle;

		public final ModConfigSpec.IntValue restrictedSpeed;

		public final ModConfigSpec.IntValue approachSpeed;

		public final ModConfigSpec.IntValue reducedSpeed;

		private Server(ModConfigSpec.Builder builder) {
			builder.comment("Free angle curves")
				.push("curves");

			curveCollision = builder
				.comment("Whether a curve is something you can stand on.",
					"Create's own curves are not. Its straight track carries a two pixel collision",
					"slab, but a curve between two track blocks is drawn and nothing more, so walking",
					"onto one drops you through it. With this on, a curve laid by this mod puts an",
					"invisible floor along itself at the height the rails are drawn - the same slab",
					"straight track already has, following the bend.",
					"That floor is small blocks placed along the curve and renewed every few seconds",
					"by the anchors at either end. They cannot be seen, looked at or mined, and",
					"building over one simply takes it out. Turning this off stops new ones being",
					"placed; the ones already down expire on their own within a few minutes.")
				.define("collision", true);

			maxGrade = builder
				.comment("The steepest a curve may climb, in permille: metres of rise per thousand metres of run.",
					"It is measured along the curve at its steepest point, so a gentle slope that has to",
					"steepen to meet the track at either end is held to the steepest part of it, and the",
					"number is rounded to a whole permille, which is the figure the wand shows.",
					"333 is one block of rise in three of run, which is as steep as this mod has always",
					"let a curve climb.")
				.defineInRange("maxGrade", 333, 10, 1000);

			snapAngle = builder
				.comment("The step, in degrees, of the angles the wand prefers for the free end of a curve.",
					"Compass angles that are a multiple of it - 0, 15, 30, 45 and so on for 15 - win over",
					"the angle the aimed block would give, when that is within a quarter of the step of one.",
					"The block aimed at is moved up to two blocks if that makes the angle exact, and the",
					"angle is turned to it if not. A step that divides 45 keeps 45 and 90 degrees among them.",
					"0 turns it off, and the curve takes whatever angle the aimed block gives.")
				.defineInRange("snapAngle", 15, 0, 45);

			builder.pop();

			builder.comment("How fast a five-aspect signal lets a train go, in km/h",
					"A train is held to the speed of the last signal it passed until it passes the next, and",
					"a green signal holds it to nothing. A train passing a signal at red is held to the",
					"restricted speed. The speed limit signs hold a train separately, and the slower wins.")
				.push("signals");

			restrictedSpeed = builder
				.comment("The speed at 警戒, the two outer yellow lamps.")
				.defineInRange("restrictedSpeed", 25, 1, 1000);

			approachSpeed = builder
				.comment("The speed at 注意, the lower yellow lamp.")
				.defineInRange("approachSpeed", 45, 1, 1000);

			reducedSpeed = builder
				.comment("The speed at 減速, the upper yellow lamp and the green.")
				.defineInRange("reducedSpeed", 75, 1, 1000);

			builder.pop();
		}
	}

	public static class Client {

		public final ModConfigSpec.DoubleValue signFontScale;

		public final ModConfigSpec.IntValue signViewDistance;

		public final ModConfigSpec.DoubleValue signalLitLampScale;

		public final ModConfigSpec.BooleanValue signalGlow;

		public final ModConfigSpec.DoubleValue signalGlowSize;

		public final ModConfigSpec.DoubleValue signalGlowBrightness;

		public final ModConfigSpec.IntValue signalViewDistance;

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

			signViewDistance = builder
				.comment("How far away a speed limit sign is still drawn, in blocks.",
					"Worth more here than on a signal. A signal past this distance loses its lamps but",
					"keeps its housing, which is terrain; a sign has no terrain at all - its block model",
					"is empty so the plate can be turned 22.5 degrees at a time - so past this distance",
					"the whole sign vanishes and there is nothing standing beside the track.",
					"0 means as far as there is anything to draw: the render distance, past which the",
					"chunk is not loaded and the sign does not exist on this client at all. Any other",
					"value is a limit in blocks, for putting the cost back - the number is drawn with",
					"the font, which is not free, and a long line of signs pays for all of them.")
				.defineInRange("viewDistance", 0, 0, 1024);

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
					"it survives being shrunk to a few pixels on screen when the lamp itself does not.",
					"Turning this off is also the quickest way to tell whether something odd on the face",
					"of a signal is the halo or the model underneath it.")
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

			signalViewDistance = builder
				.comment("How far away the lamps are still drawn, in blocks.",
					"The housing is part of the terrain and is drawn as far as the render distance",
					"reaches, but the lamps are drawn by a block entity, and Minecraft stops drawing",
					"those at 64 blocks - so past four chunks a signal turns into an unlit lump.",
					"0 means as far as there is anything to draw: the render distance, past which the",
					"chunk is not loaded and the signal does not exist on this client at all. Any other",
					"value is a limit in blocks, for putting the cost back if a line with a great many",
					"signals on it is expensive to look down.")
				.defineInRange("viewDistance", 0, 0, 1024);

			builder.pop();
		}
	}

	private CTNConfig() {}
}
