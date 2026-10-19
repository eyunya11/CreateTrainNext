# Create Train Next

[日本語](README.ja.md)

An addon for [Create](https://github.com/Creators-of-Create/Create) that lets train track run at any curve angle, instead of the 45 and 90 degree steps Create's own placement tools allow. Besides the wand that draws the curves, it adds trackside equipment: a Japanese-style five-aspect signal, a relay signal, a speed limit sign and the poles that carry them.

| Item | Supported |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 or later |
| Create | 6.0.x (verified on 6.0.10) |

## Features

- Lay a curve at any angle with two right clicks. A ghost shows what will be built before you build it: green means it can be laid, red means it cannot. Angles near a multiple of 15 degrees are drawn to it, so 45 and 90 degrees come out exact.
- Trains run on these curves as they do on any Create track. The five-aspect signal is built on Create's own signal system.
- Two ends at different heights are joined at one steady grade, which the anchors remember, so a climb is a slope and not a staircase. Hold the sprint key and scroll to set a grade, and the wand lays the whole route at it.
- A Survey Marker lays a dead straight line at a bearing, length and grade you set.
- An invisible floor is laid along each curve at rail height, so you can walk on curves.
- Adds a Japanese-style five-aspect signal, a relay signal and a speed limit sign, and a pole that carries the signals.
- Commands inspect and repair the track.

## Installation

You need Minecraft 1.21.1, NeoForge and Create itself. Ponder and Flywheel ship inside Create, so they need no separate install. On multiplayer, install the same version on the server and on every client.

There is no download page yet. Build the jar from source and put it in the `mods` folder. The steps are in the Development section.

There are no crafting recipes yet either. Take the items from the "Create Train Next" creative tab, or use `/give`, for example `/give @s createtrainnext:curve_wand`. There are seven items.

| Name | Japanese name | ID | Purpose |
|---|---|---|---|
| Curve Wand | 曲線レール設置杖 | `createtrainnext:curve_wand` | Draws curves |
| Angled Track Anchor | 自由角度レールアンカー | `createtrainnext:angled_track_anchor` | The track block at the end of a curve |
| Survey Marker | 測量標 | `createtrainnext:survey_marker` | Lays a straight line at a set bearing, length and grade |
| Track Speed Limit | 速度制限標識 | `createtrainnext:track_speed_limit` | A sign that shows a speed limit |
| Signal Pole | 信号柱 | `createtrainnext:signal_pole` | A pole that carries signals |
| 5-Aspect Railway Signal | 5灯式信号機 | `createtrainnext:multi_aspect_signal` | A Japanese-style five-aspect signal |
| Relay Signal | 中継信号機 | `createtrainnext:relay_signal` | Repeats the next signal's aspect in advance |

Create 6.0.10 is the version this has been verified on. The declared range is 6.0 and later, but other versions have not been tried.

## Usage

### Laying curves with the Curve Wand

Curves are laid with the Curve Wand (the wand, below). The wand places the anchors a curve needs at its ends by itself, so you do not place them first.

1. Hold the wand and right click the track the curve starts from (Create track, or an anchor).
2. Look at where the curve should go. A ghost is drawn from the start to the block you are looking at.
3. When the ghost is green, right click again. The curve is built exactly as the ghost showed it.

The selection stays on the end you just reached, so each further right click extends the route by one curve.

When something already fixes the direction at one end (Create track, or an anchor a curve already arrives at), the direction at the other end is chosen so that the whole curve is a circular arc. You do not enter any angle, and the result is close to what a real railway would build.

The message above the hotbar gives the length of the curve, how far it turns and the bearing it arrives on. Bearings count the way Minecraft does: 0 degrees is south, and the number grows clockwise. Compass points such as NE are shown beside it.

When the curve climbs or falls, another line gives its grade in permille (‰, thousandths of rise over run), how many blocks of run it takes to gain one block, the horizontal length and the height gained. That grade is the average over the whole curve. A curve is refused for steepness by its steepest point, which for a curve that eases into a climb is more than the average.

| Action | What it does |
|---|---|
| Right click track or an anchor | Starts a curve there. An anchor can start one if either side is free. |
| Right click the destination block (while selecting) | Builds the curve as the ghost shows it and moves the selection to the new end. |
| Right click the end of another route (while selecting) | Joins the two routes. |
| Scroll while sneaking (while selecting) | Turns the far end by hand. 2.5 degrees per notch, 15 while the sprint key is held, up to ±85. |
| Scroll while holding the sprint key and not sneaking (while selecting) | Sets the grade the route is laid at, 5 permille per notch. See "Laying at a grade" below. |
| Right click the air (after turning by hand) | Returns to the automatic arc. |
| Right click the air while sneaking (while selecting, not in grade mode) | Drops the selection. |
| Right click the air while sneaking (in grade mode) | Opens a screen to type a grade and a distance. |
| Right click an anchor while sneaking | Takes apart the curves linked to that anchor. |
| Right click any other block while sneaking (while selecting) | Drops the selection. This also works in grade mode. |
| Right click near a curve while sneaking (nothing selected) | Puts a point (an anchor) on the curve. |

When a curve cannot be built, the ghost turns red and the message says why.

| Message | Meaning |
|---|---|
| "Too close" | The two points are less than 1.5 blocks apart horizontally. |
| "Too far" | The distance exceeds Create's maximum track placement length (32 blocks by default). |
| "Too sharp for a train to take" | The curve turns more than 135 degrees, its radius is under 2 blocks, or it differs from the existing track by more than about 26 degrees where they meet. |
| "Too steep" | The curve is steeper than the limit somewhere along it. The message gives how steep it is at its steepest and the limit, `curves.maxGrade`, which is 333 permille by default. |
| "The curve would double back on itself" | One end points against the direction of the line joining the two points. |
| "The ends point too differently for one curve…" | One curve cannot join them, and there is no free block to put an anchor in between two arcs. |
| "No room for an anchor there" | The block the anchor needs is occupied. In grade mode that is the block at the height the grade gives, not the one you look at. |
| "Both sides of this anchor already have a curve" | An anchor with a curve on both sides cannot be a start or an end. |
| "Both ends would be the same anchor" | The same anchor was picked twice. |
| "Cannot start from a junction" | Create track that has more than one direction cannot be an end. |
| "Cannot start from sloped track" | Sloped Create track cannot be an end. |
| "Part of the route that would have to be turned round is not loaded" | Move close enough that the whole route is loaded. |
| "The track this curve started from is gone" | The track you started from has been broken. |

### Round angles

The direction at the free end of a curve is drawn to a multiple of 15 degrees when the block you aim at gives an angle close to one, so a right angle or a 45 degree bend is exact and not a block out. The step is the server setting `curves.snapAngle`, and 0 turns it off. Close means within a quarter of the step either side, which is 3.75 degrees for 15.

It is done in one of two ways. If a block up to 2 blocks from the one you aim at gives the angle exactly, the curve ends on that block. Every multiple of 45 degrees is like this, so a bend that is a block short of a right angle becomes one clean arc. Otherwise the end stays on the block you aimed at and only its direction is turned to the angle. Angles such as 30 and 60 degrees are done this way, and the curve may need an anchor between two arcs.

The end you turn by hand is drawn to the grid as well, so a notch that lands near an angle stops on it. While you are turning by hand the block you aim at does not move. A line from an anchor that is on no route yet is drawn to the grid at both ends. A distance typed into the screen and a Survey Marker keep the direction they were given, and so do Create track and an anchor that already has a curve. The ghost and the message show the angle that will be built. If the grid would make a curve impossible, the curve is the one the block you aim at gives.

### Laying at a grade

The far end of a curve is normally at the height of the block you look at. In grade mode you set a grade instead, and the wand works out the height.

1. Start a curve as usual. Then hold the sprint key and scroll, 5 permille per notch. The grade goes up to the limit `curves.maxGrade` either way, and a minus sign is a descent. Moving off 0 turns grade mode on.
2. Look at where on the ground the curve should arrive. The height of that block no longer matters: the wand puts the far end at the height the grade gives, and outlines the block the anchor will take. If that block is occupied, the ghost turns red.
3. Right click to build it. The selection keeps the grade, so each further right click lays the next curve at it as well.

A line of the message gives the grade you set next to the grade the curve really comes to, for example "Grade mode · set +35‰ → actual +40‰ at the far end".

A curve leaves its start at the grade the route already has there, which is level on level ground and on Create track, and changes at a steady rate until it arrives at the grade you set. A curve that leaves level ground therefore eases up to the grade, and gains about half the height of a curve that is at that grade all the way.

To level off, scroll the grade back to 0. The next curve arrives level, and grade mode turns off after it. If the start is already level, scrolling back to 0 turns grade mode off at once.

In grade mode the air is used for the screen below, so to drop the selection, sneak and right click a block.

When the far end is Create track or an anchor that already exists, its height is already decided. The curve is then laid as it would be without grade mode, and the message says so.

#### Rounding to whole blocks

A node is always at the height of a block, so a curve gains a whole number of blocks. The grade it arrives at is the grade you set rounded to what that allows, and the rounding is large for a short curve or a gentle grade.

From level ground a curve gains about half its length times the grade, to the nearest block. So a 32-block curve, the longest Create allows by default, stays level for a grade under about 31 permille, and at 35 permille it gains one block and arrives at 63 permille.

The next curve starts from the grade the last one arrived at and aims at the grade you set again. Laid one after another, curves of equal length settle on the grades that gain 2, 4, 6 and so on blocks per curve, which for 32-block curves are 62.5, 125 and 187.5 permille. The message shows what a curve comes to before you build it.

Longer curves come closer. The longest is set by Create's `trains.maxTrackPlacementLength`.

A link of a hundred blocks is six or seven chunks long. The track graph takes in a link only while both of its anchors are loaded, so one laid with its far end beyond the loaded area joins the graph once you are near enough to load both ends. If a link still does not work with both ends loaded, `/createtrainnext rebuild` puts it back. It is described under Commands.

#### Typing a grade and a distance

Sneak and right click the air in grade mode, and a screen opens with two boxes: the grade in permille, and a distance in blocks. With the distance left empty, only the grade is set.

With a distance, the wand lays a curve from the selected end, that far along the ground and at that grade, and carries the selection on from its end. There is no block to aim at, so the curve goes straight ahead from the start. Straight ahead is the way the route already runs out of an anchor, and away from Create track on the side you face. For an anchor with no curve on it yet, it is the way the anchor points or the opposite, whichever you face.

The end is the block nearest that line, within 1.5 blocks of the distance, and the distance cannot exceed Create's maximum track placement length. Nothing is shown beforehand, so the message afterwards gives the grade, the horizontal length and the height it came to.

### Joining, reversing and splitting routes

An anchor holds only one curve on each side. A curve can leave by whichever side is free, so a route can also be extended backwards out of its first anchor.

Two routes laid separately join when you right click their ends. If they run against each other, the wand turns the far route end for end before it joins them. Reversing moves no track and does not change which way the signals on it face. The message says "turns the far route round" before you build, so nothing comes as a surprise.

If a curve between ends that point very differently were built as one curve, trains running the other way would follow a path different from the rails that are drawn. So the wand builds such a curve as two circular arcs with an anchor between them. The block for that anchor is outlined on the ghost and named in the message. If no block is free, the curve cannot be built.

Signals and the like cannot be placed directly on the stretch of a curve. Sneak and right click near the place you want, and a point is added. The point is an anchor, which is a track block, so a signal can be placed on it. A point cannot be added too near the end of a curve, or where there already is one.

The ends of ordinary Create track (flat and straight) can be a start or an end. The wand puts an anchor in the neighbouring block and joins the Create track at the same point. The selection ends at an end joined to Create track, and the route cannot be extended past it. Track with a junction, and sloped track, cannot be used.

Two points at different heights can be joined. The curve climbs at one steady grade from end to end, and the anchors at its ends remember it, so a route carried on from them continues at that grade instead of starting level again. Where one end is already fixed, the other takes the grade that makes the gentlest bend, so a curve that leaves level track and has to climb eases into the climb. The steepest a curve may be anywhere along it is the limit `curves.maxGrade`, which is one block of rise in three blocks of run by default.

Breaking an anchor takes apart the links on both sides of it, and so does right clicking it with the wand while sneaking.

### Straight lines (Survey Marker)

A Survey Marker lays a dead straight line at a bearing, length and grade you give it. Unlike the wand, which you aim by eye, it suits a line that has to leave at a stated angle towards something far away.

1. Place a Survey Marker where the line should start.
2. Set the bearing, length and grade. Right click the marker with an empty hand to open a screen where all three can be typed in.
3. Look at the marker and the line it would lay is drawn as a ghost. Right click the marker with the wand to lay it.

The bearing is 0 to 359 degrees, with 0 at south and growing clockwise. It is the same bearing the wand shows, so you can read one and type it into the other. The length is 3 to 128 blocks, 16 by default. The real upper limit is Create's maximum track placement length (32 blocks by default). The grade is in permille, 0 by default, which is level, and a minus sign is a descent. It can go up to the limit `curves.maxGrade` either way.

The line is exactly straight, but its bearing comes out a little off. The anchor nodes sit at block centres, and the line joins two of them. The longer the line, the smaller the error: roughly under 0.5 degrees on average at 16 blocks, and under 0.25 at 32. The message after laying gives the real bearing and length. The marker is used up by laying, and becomes the anchor at that place.

The grade is rounded in the same way, because a line gains a whole number of blocks. 100 permille over 16 blocks is 1.6 blocks, which comes out as 2 blocks, or 125 permille, and a grade too gentle to gain a block over the line leaves it level. The ghost and the message after laying give the grade the line really has, and a line steeper than the limit is refused.

### Anchors

An anchor is the track block at the end of a curve. The wand places them where they are needed, so you do not normally place one by hand. Place one by hand when you want to fix the block first. Right click a placed anchor with the wand to start a curve from it.

An anchor behaves as a Create track block, so it can also be the target of a signal and the like. It also remembers the grade the route has at that point, so a route carried on from it continues at that grade.

### 5-Aspect Railway Signal

There are five lamps, from the top: yellow, yellow, red, yellow, green. The aspect depends on how many consecutive signal sections (the stretches Create's signals divide the track into) are clear beyond the signal. On a line with no signal ahead, it shows Proceed.

| Aspect | Lamps lit | Clear sections (normal) | Clear sections (with Restricted on) |
|---|---|---|---|
| Stop (停止) | red | 0 | 0 |
| Restricted (警戒) | top yellow and bottom yellow | not shown | 1 |
| Approach (注意) | bottom yellow | 1 | 2 |
| Reduced speed (減速) | second yellow and green | 2 | 3 |
| Proceed (進行) | green | 3 or more | 4 or more |

Restricted is a step for places where the section ahead is too short to stop in from Approach speed. Each signal decides whether to use it, and a right click with a wrench switches it. After you switch, the message shows the order of aspects.

It is placed as Create's signals are. Hold the signal, right click the track, then right click where it should stand. The placed signal is a signal boundary just like Create's own, and trains stop at red. It can be used together with Create's signals.

The housing can be moved to the centre, left or right. That is handy when a pole stands between two tracks and heads are shared out to either side. Pick it in the value box. The direction is set by which way you face when you place it, in 22.5 degree steps. A placed signal cannot be turned.

Stopping trains and reserving sections is Create's job, and this signal only decides its lamps. So there is no slowing down to match an aspect yet. The lamps refresh about every 0.5 seconds.

### Relay Signal

A relay signal shows the aspect of the next signal ahead of time. Use it where that signal is hard to see, such as beyond a curve or behind a platform roof. It does not stop trains or reserve sections, and it is not in the track graph. It is placed like a signal.

All lamps are the same white, and three of the seven light up. The meaning is read from the angle of the pattern.

| Pattern | Meaning | Aspect of the next signal |
|---|---|---|
| Three across | Stop relay (停止中継) | Stop |
| Three on the diagonal | Restricted relay (制限中継) | Restricted, Approach or Reduced speed |
| Three upright | Proceed relay (進行中継) | Proceed |
| Dark | Nothing to show | There is no next signal |

Where the line divides ahead, it shows the strictest aspect among the signals on each branch.

### Track Speed Limit

A sign stood beside the track that shows a speed limit. It is placed like a signal: right click the track, then where it should stand. Hold right click on the sign to open the slider and set the limit, from 0 to 100, 40 by default, in the same unit as Create's train speed readout. The direction is set by which way you face when you place it, in 22.5 degree steps.

**For now it only displays and stores the number, and does not affect trains.** Applying it to trains is planned.

### Signal Pole

A thin pole that carries signals. One block serves as a mast and as a crossbeam between masts. The direction it runs is set by the face you place it on, and it joins neighbouring poles and signals by itself. It joins the 5-Aspect Railway Signal and the Relay Signal, and does not join the Track Speed Limit.

It joins a 5-Aspect Railway Signal from behind, below and either side, and not from the front. A right click with a wrench changes the direction. To run a long length, hold a pole and right click a pole already placed, and Create's placement assist suggests the position at the far end.

### Curve floor

Create's curves have no collision, so stepping onto one drops you through it. For curves laid by this mod, an invisible floor is laid at rail height, so you can walk on them. The floor is made of small blocks that the anchors refresh every few seconds. They cannot be seen or broken, and building over one removes it.

To turn it off, set the server config `curves.collision` to false. New floor is no longer placed, and the floor already there disappears within a few minutes.

## Commands

All start with `/createtrainnext` and need permission level 2 (op).

| Command | Description |
|---|---|
| `junction <pos> [radius]` | Lists what the track blocks near the position report (their axes), whether the graph holds a node there, and whether a train could pass at each angle. The radius is 0 to 8, 2 by default. Use it when trains stop or a signal cannot be placed. |
| `probe <pos>` | For one track block, prints the values Create looks at when it decides where a station or signal goes, and how they compare. It works on Create's own track as well as on anchors. |
| `curves [radius]` | Lists curves whose shape differs when read the other way round. The wand no longer lays them, but curves laid by older versions can have them. The radius is 1 to 1024, 128 by default. |
| `repair [radius]` | Rebuilds those curves as two arcs, up to 16 per run. The rails move a little: under half a block for an east-into-north curve across about 20 blocks. The output says how far each one moved. Trains near a repaired curve are derailed. |
| `prune [radius]` | Removes graph nodes that no block reports any more, the coloured posts left in the F3 graph. Other track, trains and signals are left as they are. |
| `rebuild [radius]` | Puts back the links that are missing from the track graph: the anchors and the curve are there, but no train can drive along it. A link that works is not touched. Up to 32 per run. A link whose far end is not loaded cannot be judged, and the output says how many. The radius is 1 to 1024, 128 by default. |
| `reverse <pos>` | Turns the route the anchor belongs to end for end. Running it twice puts it back. |
| `resetgraph` | **Deletes every train and every track graph in the world.** Nothing is rebuilt automatically. |

`resetgraph` is only for isolating problems during development. Afterwards, break and replace one block of each track network and link the anchors again, or the track has no edges. Back up the world before running it.

Enable Create's client config `showTrackGraphOnF3` and open the F3 debug screen, and the nodes and edges of the track graph are drawn.

## Configuration

Edit the config files directly. The client settings are in `config/createtrainnext-client.toml`, and the server setting is in the world's `serverconfig/createtrainnext-server.toml`.

The server setting affects what is in the world, so the same value applies to every connected player.

| Key | Default | Range | Description |
|---|---|---|---|
| `curves.collision` | true | true, false | Whether to lay the floor along curves |
| `curves.maxGrade` | 333 | 10 to 1000 | The steepest any curve or straight line may be anywhere along it, in permille. 333 is one block of rise in three blocks of run. |
| `curves.snapAngle` | 15 | 0 to 45 | The step, in degrees, of the angles the free end of a curve is drawn to. 0 turns it off. Use a step that divides 45, so that 45 and 90 are on the grid. |

The client settings change only how things are drawn, so each player can pick their own.

| Key | Default | Range | Description |
|---|---|---|---|
| `speed_limit_sign.fontScale` | 1.7 | 0.25 to 4.0 | Size of the number on a sign (multiplier). At 1.15 or less three digits fit inside the white area, and above 2.0 three digits run off the sign. |
| `speed_limit_sign.viewDistance` | 0 | 0 to 1024 | How far a sign is drawn, in blocks. 0 draws as far as the render distance. |
| `multi_aspect_signal.litLampScale` | 1.0 | 1.0 to 2.0 | How much larger a lit lamp is drawn. Above 1 the lamp comes out in front of its bezel, and above 1.35 it reaches over the neighbouring lamps. |
| `multi_aspect_signal.glow` | true | true, false | Whether a lit lamp gets a halo of its own colour |
| `multi_aspect_signal.glowSize` | 5.0 | 1.0 to 16.0 | Width of the halo, in pixels |
| `multi_aspect_signal.glowBrightness` | 0.5 | 0.0 to 1.0 | Strength of the halo. At high values the housing washes out to white near the lamp. |
| `multi_aspect_signal.viewDistance` | 0 | 0 to 1024 | How far the lamps are drawn, in blocks. 0 draws as far as the render distance. |

## How it works

Create rounds the X and Z of every node in its track graph onto a half-block lattice. A node has to land on that lattice exactly, and only four directions, in 45 degree steps, can do so. That is why Create's own track comes in 45 and 90 degree steps.

An anchor in this mod keeps its node on one of those four directions, and holds the direction of the curve as a free value of its own. The node sits on the lattice while the tangent of the curve is at any angle. The curve is a Create bezier curve, and Create's own machinery runs the trains, the signals and the section reservation as it is.

A slope is held the same way. Create works out a bezier's handles from the horizontal part of the direction it is given, so an anchor writes the direction of its curve as a horizontal direction plus a climb. Seen from above the curve keeps its shape, to within about a quarter of a block at the default limit and far less at ordinary grades, and only how it climbs changes. Create also puts every node at a whole block height, which is why a curve gains a whole number of blocks.

A bezier curve is worked out again from the other end for trains running the opposite way. A circular arc, whose end angles are symmetric, gives the same shape both ways. Other curves can differ by several blocks between the two directions. The wand builds such a curve as two arcs, which keeps the difference within 0.1 block.

The ghost and the curve the server builds come out of the same calculation (`CurvePlanner`), so that what you see and what is built cannot disagree.

## Limitations and known issues

These limitations apply.

| Item | Details |
|---|---|
| Recipes | None. Take items from the creative tab or with `/give`. |
| Create track that cannot be an end | Track with a junction and sloped track cannot be a start or an end of the wand. |
| Heights | A curve gains a whole number of blocks, so the grade it really has differs from the one set, much more for a short curve or a gentle grade. A grade under one block per curve length, about 31 permille for 32 blocks, stays level. |
| Speed limits | The number on a sign is not applied to trains. There are no speed limits per signal aspect either. |
| Signals along a curve | They cannot be placed directly on the stretch of a curve. Add a point first, then place them. |
| Junctions | An anchor holds only one curve on each side, so anchors alone cannot make a junction. |
| Old worlds | They may hold coloured posts left where anchors were broken, and curves whose shape changes when read the other way. `prune`, `curves` and `repair` fix these. |
| Multiplayer | It has not been tested enough. |

## Roadmap

### Planned

These three are planned.

| Item | Where it stands | Aim |
|---|---|---|
| Sloped Create track | Routes made with this mod climb at any grade, but sloped Create track cannot be a start or an end of the wand. | Join sloped Create track, the 45 degree kind. |
| Enforcing speed limits | The number on a sign can be set, but does not affect trains. There are no limits per signal aspect either. | Apply the number on a sign, and a limit per aspect, to trains that pass. The per-aspect values will be configurable. |
| Placing directly on a curve | A point has to be added with the wand before a signal can be placed. | Support Create's own curve targeting, so signals and stations can be placed along a curve without a point. |

### Under consideration

Whether to build these is still being considered.

| Item | Details |
|---|---|
| A "train passed" wait condition | A condition for Create's schedules that waits until a named train has passed. The intended use is a passing loop, where a local waits for an express to go through. A prototype exists, and whether to bring it in is undecided. |
| Recipes for survival | Items can only be taken from the creative tab or with `/give` for now. |
| Laying from Create track with a junction | It cannot be an end for now. |
| Cleaning up the English text | Some wording, such as the names of the signal aspects, shows in Japanese even in English. |

## Development

You need JDK 21.

```
./gradlew build              # build the jar (output in build/libs)
./gradlew compileJava        # compile only
./gradlew runClient          # launch a client
./gradlew runServer          # launch a dedicated server
./gradlew runGameTestServer  # run the game tests
```

If the IDE reports missing libraries, run `./gradlew --refresh-dependencies` to refresh the local cache. `./gradlew clean` resets the build output. Neither touches your code.

The game tests are in `src/main/java/com/eyunya/createtrainnext/gametest/`. They check curve planning, angles and grades, long links, the survey marker, the wand's click handling, reversing and joining routes, and cleaning and repairing the graph, on a real server. The test world is created in `build/gametest/world` and deleted on every run. GitHub Actions runs the build and the game tests on every push and pull request. Drawing, and trains actually driving over the result, cannot be checked automatically, so check them in a client.

The build is against the Create 6.0.10 release. The Maven build `6.0.10-281` matches the distributed `create-1.21.1-6.0.10.jar` apart from the build information (`META-INF/MANIFEST.MF` and `CreateBuildInfo.class`). `6.0.11-N` builds are Create's development builds and are not used; as of October 2026 the latest release is 6.0.10. The 6.0.10 POM declares Registrate, Ponder and Flywheel only as runtime dependencies. So `build.gradle` takes Create itself alone, and the three versions are pinned in `gradle.properties`.

The main packages are as follows.

| Package | Role |
|---|---|
| `track/` | Curve planning (`CurvePlanner`), anchors, the wand, survey markers, the curve floor, graph cleaning and repair |
| `signal/` | The five-aspect signal and the relay signal |
| `speed/` | The speed limit sign and its edge point |
| `pole/` | The signal pole |
| `client/` | Rendering, the ghost, screens |
| `gametest/` | Game tests |

The registry, config, networking and commands sit directly in the base package.

Two models the game never uses, `relay_traffic_signal.json` (the Blockbench source of the relay signal's models) and `housing_left.json`, are kept in `src/main/blockbench/`, outside the resources. They contain free rotations that Minecraft 1.21.1 cannot read, and the game logs an error for every unreadable JSON file under `assets/createtrainnext/models/`. Keep other Blockbench sources that use free rotation there too.

Minecraft's names come from Mojang's official mappings. Their license is described at:

https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

General NeoForged documentation is at:

https://docs.neoforged.net/

## License and credits

The code is MIT, and the assets under `src/main/resources/assets/` are All Rights Reserved. [LICENSE.md](LICENSE.md) has the split, and the notice for the files that carry logic adapted from Create's own MIT-licensed source.

This mod is an addon for Create by The Creators of Create. It is not an official Create product, and is not affiliated with the Create team. The project began from the NeoForged MDK, and the template's license is in [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt).
