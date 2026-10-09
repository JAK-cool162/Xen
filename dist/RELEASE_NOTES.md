**Xen Companion 2.0.0-beta.24**: AI players for Minecraft (Fabric) that live their own lives. They gather, mine, build their
own houses, farm, trade, form villages with their own rules, make friends and enemies, travel to the Nether and the
End, and talk with you. Like a real player, a Xen only knows what it can see and only acts through a player's
controls.

### 2.0.0-beta.24: ways over the land, and into caves (pathfinder upgrade, part 2)

* **Ways over the land:** for a long trip over open land a Xen first reads the lay of the land, as a player looks at the
  landscape: a way over the ground's top (trees and cliffs to go round, a block up at a time, water slower, never across
  the open sea, never lava), then the steps to a point a couple of dozen blocks along it. Before, a long trip was
  guessed a piece at a time and could lead into a valley with no way out. Trips of 157 to 202 blocks over real land:
  4 of 6 there (2 of 6 before), the 202 block ones in 82 and 109 s; the two left both cross a frozen river by the sea.
* **Into caves:** in the dark it makes out a cave's shape 16 blocks off (8 before, the rest was rock to it, and it went
  back and forth working out the way). Sent down 25 real caves (found by the world truth tool): 23 there (21 before),
  most in under 20 seconds.
* **The edge instinct trusts its own way:** walking along a cliff, the deadly-drop instinct stopped it again and again;
  now it only stops what it didn't plan (a chase, a flight, going against its gut), not a step of its way onto ground
  it knows.
* **Sent somewhere, it goes:** no stopping on the way to mine ore it passes when it was told to go somewhere.
* Tested: the obstacle course 8 of 8 (the dead end in 27 s); 8 free Xens for 4 minutes, no errors, no deaths. 26.1,
  26.2 and 26.3 built and checked.

### 2.0.0-beta.23: believing in itself, asking others, food for later, and seeing the world as it is

* **It believes in itself:** each Xen has a self-belief, from who it is at first (brave and proud: more), then earned:
  its own calls that work out raise it (it went against its gut and was right, it finished what it set out to do, it
  built something, it beat a monster), the ones that don't lower it (hurt after going against its gut, a death). Bad
  days fade back toward who it is. It backs its mind against its gut, it hesitates less, and it says so ("I've got
  this.", "I'm getting good at this.").
* **It asks for an opinion:** a close call, or not sure of itself, and someone close: it asks. A player answers in chat
  ("yes, go for it" / "no, careful") and it goes with them, if it trusts them; another Xen answers from its own gut
  ("I'd go." / "Don't. Trust your gut."). Then it learns whose advice is good: advice that got it hurt, it trusts that
  one less; good advice, more. No answer in 15 seconds: its gut.
* **A deadly drop is instinct:** about to walk off an edge where the fall would kill it or take half its health (no water
  below), it stops, whatever its mind says (a Xen argued with its gut at a 24 block drop and died). A death after going
  against its gut now teaches it to trust its gut much more.
* **Food for later:** it wanted no animal when it wasn't hungry, so it never hunted for later, and "hunt for food" found
  none. Now it keeps some food on it (4 to 10, a glutton more): not busy, little food, a cow close, it hunts it
  ("Stocking up on food."), never a baby, a pet, a penned one or the last two of a herd.
* **It picks up what it kills:** raw meat, fish, bones, eggs, wool and other drops weren't worth picking up; and what
  drops where something it killed died is its own now, like what it mines.
* **No more towering up for nothing:** with no way to something above it (an item on the leaves, a log up a tree) it
  pillared straight up on dirt; now only out of a hole it's really in.
* **It sees the world as it is (the world generator as the simulator):** `/xen terrain sample` labels spots in land
  Minecraft generated from all their blocks (the inside of a cave, an entrance, a ravine, a river, ground), and the
  Xens learned from 15,000 of them in 6 places (`scripts/learn_terrain.py`). On land it never saw: caves named right
  66% (was 0%), plain ground left alone 95%, and when it names something it's right 71% (was 9%: it called oceans
  rivers). Cave entrances (12%), rivers and ravines need more land sampled. `/xen terrain caves` lists caves with a deep
  point, for a cave-diving test next.
* Tested: a Xen at the bottom of a 12 deep waterfall was out on top in 8 s; one told to go up a stream was there in 8 s;
  a full Xen with no food hunted a cow for later and took the beef; 8 Xens with a player for 6 minutes asked 7 times
  (each other and the player) and went with the answers; 8 free Xens for 4 minutes: no errors. 26.1, 26.2 and 26.3
  built and checked.

### 2.0.0-beta.22: Xens argue with their gut; instinct comes first

* **Instinct, first of all:** when the danger is clear and the way out plain, a Xen's instinct takes over everything
  it's doing, every tick: out of air under water (up, holding space, not into the wall), in lava (out to the nearest
  block it can stand on, up to 4 away, jumping), standing in fire (a step out of it). Nothing argues with it, not its
  gut and not its mind, and nothing new starts till it's safe.
* **Arguing with its gut:** when its gut (the fear it learned) says no, its mind makes its case: it was asked to, it
  can take a hit, it's braver than that, the feeling is faint. Against that: how sure the gut is, how dangerous it all
  looks, and how much it trusts its gut. It's a weighed chance, so now and then it goes on anyway ("My gut says no...
  going anyway."). Scared and badly hurt, it doesn't argue.
* **It learns from it:** after going against its gut it watches what happens. Hurt: it trusts its gut more ("Ow.
  Should've listened to my gut."). Fine: a little less. Each Xen keeps its own trust (saved with the world), so a bold
  one that keeps getting away with it grows bolder, and one that keeps getting burnt listens.
* Tested: from the bottom of a 21 deep shaft its instinct took over at 60% air and it came up with air left; dropped
  in a 3 x 3 lava pool it was out in about a second and ran for water; 8 free Xens for 3 minutes: a gut took over,
  another Xen argued with its gut ("the feeling is faint"), went on, was fine and trusts its gut a little less. 26.1,
  26.2 and 26.3 built and checked.

### 2.0.0-beta.21: no more drowning in place

From a player's recordings (thanks!):

* **Fixed: a Xen drowned holding still.** Under water, its gut (the fear it learned) came before its instinct to swim
  up, and drowning hurts whatever you do there, so every move "felt wrong" and it held still a block and a half under
  the top, against a wall, until it drowned. Now, under water and short of air, air comes first: the gut waits, and
  every tick (not only when it decides something) it holds space, like a player, and doesn't push into the wall.
* **Fixed: two answers to one ask.** "find me wood" got "I don't know where any trees are, I'll look around" and then
  "I haven't seen any trees yet, I'll look around." Now the second one only comes when it hasn't just answered.
* Its recordings say which version wrote them again (they all said beta 10).
* Tested: a Xen at the bottom of a 5 deep pit against a wall is out in 4 seconds and swims on at full air; the obstacle
  course 8 of 8 (river 48 s). 26.1, 26.2 and 26.3 built and checked.

### 2.0.0-beta.20: faster way finding (part 1 of the pathfinder upgrade)

* **Way finding about twice as fast:** each block a Xen thinks about on its way now costs a fraction of what it did.
  What a kind of block is (lava? water? something to stand on? diggable?) is worked out once for each block state, not
  asked of the game's tags and registry again and again; what it learned about each block while it plans is kept in
  one box for the plan; the moves it weighs are kept in maps that don't fall into slow buckets. Measured with 8 free
  Xens in the same hills: a plan took 21 ms and now about 11 ms, and the Xens' share of the server dropped from 9.8 to
  4.8 ms a tick in the first comparison (runs vary with what they choose to do).
* **No more long thinking for a spot it can't stand on:** a Xen right next to something it wanted (an item lying in a
  log or on leaves) thought about 10,000 blocks round it before giving up, again and again. Now it knows at once that
  it's as close as it gets.
* **`/xen goto <name> x y z`** sends just that Xen (without a name, all of them, as before).
* **`/xen perf`** also says how many ways are planned a second, how many blocks each one thinks about, how long one
  takes, and how many get only part of the way or find none.
* Tested on a new obstacle course (a U-shaped dead end, a maze, a long wall, a river, a cliff with one ramp, a pit
  with a ladder, a forest of pillars, a lava strip with one bridge): 8 of 8 there, as before, and faster (the dead end
  in 30 s instead of 51, the maze in 15 instead of 33). 1.21.11 in game; 26.1, 26.2 and 26.3 built and checked (their
  path tests too).
* Next: a way over the land first for long trips (as a player reads the landscape), then the steps, so it doesn't
  walk into dead-end valleys.

### 2.0.0-beta.19: a whole place of its own, pictured first

* **"Pip, build a place"** (or "set up your own place", "build a homestead"): a Xen lays out a whole place by itself,
  with no template. It looks over the ground round it (water, trees, anyone's build, what it can't see from there),
  pictures its house, a pool, a sitting area, a road out from its door with paths to each part and a wall round it
  all, in thousands of ways in its head, and keeps the one it likes best. It also does this on its own sometimes, when
  it builds its home.
* **Not copies:** from what it was shown it learned a style (what a road, a wall and a pool are made of, a lantern on a
  post, stairs for chairs, moss for a bush) and makes its own to fit the ground: a road dug into the ground (bumps
  mined down, dips filled, a slab where it climbs, lamps along it, on out past the gate), a pool dug in, a sitting
  area with a table and chairs, a wall that follows the land with posts and a gate, bushes by the door. The house can
  still be one it was shown (its own version) or one of its own designs.
* **Its own taste decides:** which parts it wants (silly Xens like a pool, fort builders a wall, chatty ones a sitting
  area), flat ground, the door facing the way in, a straight or winding road, tidy or loose, close or spread out. It
  learns from what you say about the place.
* **See what it pictured:** each part outlined in the world in its own colour (gold house, cyan pool, pink sitting
  area), the road and paths dotted white, the wall grey, the gate purple, the door red, while it pictures it and for a
  minute after. `/xen layout` shows it again. Its journal has a map of it.
* **Trained on the new Build Axe data** (21 builds, a tree, 8 caves, a river): its style is learned for each kind of
  place (in the snow, spruce walls with stripped spruce posts, from the snowy house; in the desert, what the desert
  buildings are made of), and in a place of its own it takes the house shown there first (the snowy house in the snow).
  It learned the tree's shape (the spruce: its trunk, how its leaves narrow) and grows its own like it in its garden,
  never the same twice.
* **It knows what it's looking at:** the caves and the river you marked aren't builds, they're for seeing. From them a
  Xen learned what a cave entrance, the inside of a cave, a ravine, a river and a frozen river look like (the ground on
  top, the hollow under it, how steeply it drops). Every few seconds it takes in the spot its eyes are on, names it if
  it's one of those, remembers it ("the ravine", "the river 2") and now and then says so ("Whoa, a ravine over there.").
  On what it was shown, it names caves, rivers and frozen rivers right 95 to 100 times in 100, cave entrances and
  ravines about half the time (`scripts/learn_sights.py`). Big caves like the ones shown it goes to first for ore
  (`scripts/learn_caves.py`).
* **Fixed:** a house marked a block too high (its door's lower half left out) was built with a hole a block high for a
  door, and the Xen shut itself in: the door and the bottom row are put back now.
* **Fixed:** grass in a build could be put back again and again (grass under a block turns to dirt): any dirt counts
  now, and a block that won't stay is left out after a few tries. A build it was shown with no door block has its door
  on its front edge.
* **Fixed:** a Xen building in creative could end up hovering in a window hole of its own wall, where its way out
  was looked for from the one block of its body that was shut in: it stood there skipping a block every half minute
  (10 minutes for the back of a roof). Now its way is looked for from every block its body is in, and when it really is
  shut in by its own build it takes a block of it out (a window first), gets out and puts it back at the end. Garden
  trees made a Xen stumble (a plan with no inside): fixed.
* **`/xen perf`:** where the server's time goes for the Xens (ms a tick in all, for each Xen, and by part). With 8 free
  Xens: 12 ms a tick (a quarter of the 50 ms a tick has), 1.5 ms each, 8.7 ms of it planning their ways. That's what
  the next update works on.
* Tested in game (1.21.11): Pip, told "build a place", pictured its place (1,445 ways in 106 ms, shown in the world),
  then built its own house (430 blocks), a road dug through a rise (71), a pool with a rim (101: dug in, water on the
  ground, the rim from outside) and a garden of 3 spruces it grew its own way (127, none left out); an earlier run had
  a sitting area and a three-wide road with lamps too. 8 free Xens by a frozen river named it and ravines within 2
  minutes. 26.1, 26.2 and 26.3 built and checked.

### 2.0.0-beta.18: see the Build Axe's box, Xens pick up what they mine, bored fidgets

* **The Build Axe's box, in the world:** while you hold the axe, the box shows as gold dust along its edges (seen from
  far off), the blocks you left out are marked in red, and its size is above the hotbar ("5 x 5 x 4, left out: 1").
  After the first hit, a cyan box goes from that corner to wherever you look, with its size, so you see how big it
  will be before the second hit (red when it's too big).
* **Fixed:** hitting the block that was the last corner did nothing (it now starts a new box); a right-click on a far
  block also brought the screen back up (the game repeats a held click: one click is one use now).
* **Xens pick up what they mine:** everything, not only ore and wood (the cobblestone from digging too), walking over
  to it when there's room in the bag; after chopping a tree, the saplings, sticks and apples its leaves drop for a few
  minutes after.
* **Bored fidgets:** a Xen with nothing to do for a while fidgets like a player: a jiggle side to side (often
  crouching, at you), a hop, a few crouches, a look all around, punching the air, flicking through its hotbar, a spin.
  Silly ones sooner and more, grumpy ones hardly; never in danger or with a job to do.
* **Its own version of what it was shown, not a copy:** a taught build comes out in its own wood (in creative, the
  wood it likes) and at some places the other way round (mirrored). The same place always gets the same version.
* Tested in the game: a Xen chopped a tree and dug 20 stone with nothing left lying on the ground; a silly Xen standing
  about looked around, hopped, jiggled and spun; in a real game client the axe drew the cyan box with its size, then
  the gold box (5 x 5 x 4) with the left-out glowstone in red; a Xen built the couch it was shown as shown in birch and
  the pool it was shown mirrored in birch.

### 2.0.0-beta.17: Xens know what they were shown

* **Trained on Build Axe data:** 14 builds marked with the Build Axe (two houses, six pieces of furniture, a pool, a
  road, an entrance, walls, a staircase). They're in the mod; every Xen knows them.
* **Ask for any of them by name:** "build a pool", "build the bar", "make a couch", "build a 2 story house". (A plain
  "build a house" is still the Xen's own choice.)
* **Its own choice too:** the two houses are among the houses a Xen may build when it likes copying.
* **Furniture in its house:** when a Xen finishes a house, it puts in a couch, a table, a bar or a counter that fits
  inside (in survival when it has most of the blocks).
* The ground they were marked on (grass and dirt under them) is left off: they're built on the ground where they go.
* Tested in the game (a Xen in creative): "build a pool" started the pool it was shown (547 blocks); "build a 2 story
  house" built it (271 blocks in 268 s), then it found room for a couch inside and built that (12 blocks, 12 s).

### 2.0.0-beta.16: a bigger Build Axe

* **It reaches 160 blocks.** With Xen in your game too, the axe marks corners and leaves blocks out as far as 160
  blocks away: aim and click (from outside a house, or across a cave). Look at the sky and right-click for the screen.
* **Bigger boxes:** up to 256 blocks a side and 4,194,304 in all (256 x 64 x 256), was 64 a side. Saving is quick
  and the line small: the blocks go straight into the file.
* Tested in a real game client: corners and a left-out glowstone marked from 27 blocks away (the screen said 1 x 4 x 4,
  left out: 1 block); a box of 150 x 26 x 217 (846,300 blocks) saved in a moment, 1.7 MB, the server never fell behind.

### 2.0.0-beta.15: the Build Axe's screen, leaving blocks out, your own types

* **A screen instead of a command.** Hit a block for one corner and another for the other corner, and a screen comes
  up: the size of the box and what's left out, its type, its name, and Save (once it has a type and a name) or
  Cancel. No typing commands, easier on a phone. The last type and name are filled in for the next one.
* **Leave blocks out.** Right-click a block with the axe and it's saved as air (the grass round a tree, a torch you
  put there); right-click it again to put it back. Crouch and right-click to leave out every block of that kind in the
  box. Right-click the air to bring the screen back.
* **Your own types.** Tap Build, Tree or Cave, or type your own (house, farm, bridge...): each type goes in its own
  file, `config/xen/buildaxe/<type>.jsonl`. Without the screen: `/xen BuildAxe type <your type>`.
* Fixed: with Xen in your game too, the axe's clicks never reached the server (nothing was marked).
* Tested in a real game client: two hits marked a 5 x 4 x 4 hut, a right-click left out the glowstone in its wall (the
  screen said "left out: 1 block"), typed "house" and "glow hut", Save: the line was in `house.jsonl` with the
  glowstone saved as air. Without Xen in the game: the same with `/xen BuildAxe type shed` and `save my shed`.

### 2.0.0-beta.14: the Build Axe (training data)

* **Build Axe**, a developer's tool for cheats only. `/xen BuildAxe` gives an enchanted wooden axe (`/xen BuildAxe
  tree` or `cave` to mark those instead of a build). Hit a block for one corner, right-click a block for the other,
  then `/xen BuildAxe save <name>`: the box is saved, under its name, as one line of JSON in
  `config/xen/buildaxe/build.jsonl` (or `tree.jsonl`, `cave.jsonl`). Every block in it is there (a palette of block
  states, one index per block, air too), with the dimension, the biome, and how dark and how deep it is. Up to 64 a
  side. It never breaks or strips the blocks you click, and keeps nothing about you or where it was.
* What it's for: builds, trees and caves to train Xens on. They don't learn from it in the game by themselves.
* Tested in the game: a 4 x 4 x 4 hut marked (corners on logs: not broken, not stripped), saving without a name
  refused, saved with one: 64 blocks in the line, 56 solid and 8 air inside.

### 2.0.0-beta.13: mining like a player (and nobody falls in the lava)

* **Staircases, not shafts.** Going down through rock, a Xen now counts what each level really costs it (a step of a
  staircase with the pickaxe it has), so it digs a staircase or takes a cave it has seen, instead of holes straight
  down. Straight down only when there's no other way.
* **It never digs out the block someone is standing on**, a player's or another Xen's. (A Xen mining beside you could
  take the block under your feet and drop you in lava.)
* **It picks up what it mines right away**: it walks over to its drops instead of standing there, straight to them
  when nothing's in the way, around when something is.
* **No seeing through rock.** Its way finding knows only what the Xen could know: out under the sky, the lay of the
  land; under the ground, the open space it has seen, what's right by it, and the way it came. The rest is rock to it
  until it gets there (a lit cave behind a hill, the floor of a lake, a ravine under the grass). The way finder helps
  with the next steps; where to go is the Xen's own choice.
* **Its own words.** The crouch greeting, tricks and reactions are now put in the Xen's own words by its voice, not
  picked from fixed lines, and there are no typed emotes like *crouch* or *munch*. (Some other lines are still fixed;
  more to come.)
* Tested in the game: told to go 16 blocks down, on a hill it went by a cave and on a mountain by a ravine and a
  staircase, both to the spot, with no shafts; a player standing over the spot and one beside a Xen mining stone stayed
  on their feet at full health; mining stone, it stood beside its own drops 87 seconds out of 90 before, 1 to 3
  seconds at a time now.

### 2.0.0-beta.12: whispers, and it finishes the house it started

* **Whispers.** Xens use the game's own `/msg`, like a player: only the one it's for sees it. `/msg` a Xen and it hears
  you alone and whispers back (a request too: "can you follow me?" gets a whispered "sure!"). A Xen warning you about a
  plot it overheard whispers it, so the plotter doesn't hear; gossip between two Xens is whispered when someone else is
  in earshot and the Xen is the discreet kind.
* **Chats between Xens go on as long as they have something to say** (no set length): each line a little less to say,
  and one of them winds it up ("Anyway, I should get going.").
* **It finishes the house it started.** Stopped half way (asked to stop, called away, hungry, a restart), a Xen
  remembers its unfinished house; asked for a house again near it, it goes back and finishes that one (what's built
  counts) instead of starting another somewhere else. Kept with the Xen.
* Tested in the game: `/msg` to a Xen: whispered answers, and the other player nearby saw none of it; stopped house,
  asked again from elsewhere: it went back to the same spot and carried on. Not working yet: winding up a long chat
  with a player (it still answers every line).

### 2.0.0-beta.11: hardcore Xens, who Xen is, village houses, and lessons from a run that beat the game

* **Hardcore (new setting, Basics page): one life.** A hardcore Xen that dies is **gone for good**: it doesn't come
  back, nothing of it is kept (a Xen by that name later is someone new), the players are told, the world's history
  records it, and the Xens that knew it remember it ("Rest in peace, ..."). It lives like it, too: it minds every
  danger more, backs off from the dragon sooner, and won't go off on a risky errand while hurt, however nicely it's
  asked ("No, not hurt like this: I have only one life"). `auto` (the default) is hardcore in a hardcore world.
* **Who Xen is to itself.** The chat model's prompt is now a short version of the maker's XEN_CORE: a player living in
  this world, who knows only what it has seen, remembers or was told, has goals, friends and fears of its own, and can
  say no. A reply that talks about itself as an AI, a bot or a program is thrown away. Asked "are you a bot?", a Xen
  says something like "No, I'm Xen. I live out here, same as you."
* **Village houses.** A Xen that comes to a village looks at its houses, and a little of their style rubs off on its
  own designs. It may also build one of them: a copy, the way a player copies a house they liked, out of what it has
  (its own wood; cobblestone for the sandstone, terracotta and ice it can't make), or block for block in creative.
  Whether it copies or designs its own is its taste (curious Xens like their own ideas), and it learns from how the
  copy went and what you say about it. Ask for one: "build a village house". The houses come from the game's own
  structure files, read from your game: nothing of Mojang's is copied into Xen.
* **The End, from a run that beat the game** (an AI agent's full playthrough, shared as a dataset): its arrows at the
  flying dragon all missed, so a Xen now keeps count of what its own arrows do and waits for her to land when they
  miss (a Xen that knows it waits from the start); it waits out her breath where it means to stand; it backs off to
  heal when it's low (down to half a heart, the player got away from the portal, ate and came back full); and before
  jumping in, it puts its bed down by the portal and uses it, so dying in the End brings it back there.
* **End stone counts.** A Xen couldn't build with end stone (or netherrack): in the End, once its cobblestone ran out,
  it gave up on the caged crystals. Now it mines end stone when it's short (the island is all end stone) and builds
  its towers and bridges with it, so it brings only 64 blocks from home.
* **It turns its head like a person.** Fitted to 600 recorded runs of people walking and chopping trees: a turn speeds
  up and slows down (a 90 degree turn takes about half a second, not three ticks).
* **It learns where ore is by watching you.** A Xen that sees you mine diamonds or iron moves its idea of where they are
  a little toward that height (and its own finds still count for more).
* **Fixed:** a Xen short of stone or wood for its house went and dug up its own house (its cobblestone base, its log
  frame). It never takes from its own build now.
* Tested in the game:

  | Test | Result |
  |---|---|
  | Hardcore Xen killed | "gone for good", never came back, not kept for next time |
  | Hurt hardcore Xen, "please get some wood" | "No, not hurt like this: I have only one life..."; with hardcore off it went |
  | "are you a bot?" | "Me? No. I see what I see and do things with my own two hands, like you." |
  | Steve mines a diamond in front of a Xen | it noted where (its idea of the height moved a few blocks) |
  | "build a village house" in creative | a snowy village house from the game's own file, finished in 37 to 144 s |
  | "build a village house" in survival (spruce logs, some cobblestone) | a snowy small house in spruce and cobblestone: 155 blocks in 226 s (26 decorations it had nothing to make from left out) |

* **Credits**: the End lessons come from *Mine AI MCP: the run that beat Minecraft* by AI Bengineering
  ([aibengineering/beat-the-game-minecraft](https://huggingface.co/datasets/aibengineering/beat-the-game-minecraft),
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)); the head turning is fitted to
  [OpenBlock-Team/Minecraft-Navigation](https://huggingface.co/datasets/OpenBlock-Team/Minecraft-Navigation) and
  [OpenBlock-Team/Minecraft-ChopTree](https://huggingface.co/datasets/OpenBlock-Team/Minecraft-ChopTree) by NathMen
  ([CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)). Xen holds none of their data: only what was learned
  from them (a few numbers and rules, written in Xen's own code).

### 2.0.0-beta.10: the End, from a recorded dragon fight

From 5 minutes of a player fighting the Ender Dragon (with endermen all round, flung once, nearly killed by endermen):

* **No more being flung.** To hit her head, a Xen walked right under it when she perched on the portal. That's where
  the recorded player stood when her wings threw them thirty blocks up as she took off. Now it hits her from the side,
  three blocks out from her head, never under her.
* **It glances at the dragon, not stares.** The player looked her way only now and then (within 15 degrees of her 18%
  of the time) and watched the ground the rest. Waiting for her to land, a Xen now does the same, instead of staring
  up at her the whole time.
* **Endermen**: they did the player more harm than the dragon (49 of the 71 damage). The player kept them out of
  view. A Xen now keeps its eyes on the ground a few blocks ahead when endermen are about, and never rests its eyes on
  one when idle.
* **The pearl clutch**: flung high, the player looked straight down and threw an ender pearl at the ground, landing
  with a scratch. A Xen that knows it (about half do; tell one "if you get flung, throw an ender pearl down") does too.
  Off an edge (the island, a bridge, a tower), it throws a pearl back onto the land it came off. It works out the
  throw by flying the pearl the game's way through the real blocks, landing it on top of a block well in from the edge.
* Tested in the game:

  | Test | Result |
  |---|---|
  | Dropped 40 blocks onto the ground with 4 pearls (the fall alone kills) | lived every time, 18 of 20 health, 1 pearl used |
  | Off a sky platform's edge (east, south, west), 40 blocks of air below | a pearl back onto it, 6 of 6 |
  | An enderman 10 blocks right in front | looked at it 0 to 5 times in 20 checks (it used to stare) |

  The side-on hits and the glances at the dragon have not been tried on a live dragon yet.

### 2.0.0-beta.9: boredom and whims, trust, trial chamber walls, simpler settings, no cheats needed

* **Bored? It does something nobody needs.** Boredom fills while a Xen does the same thing, or nothing much. When it's
  full, it gets a whim, picked by its nature:
  * it **makes a plan** out loud ("a castle with a moat, after I beat the dragon"), which it may never do;
  * it **throws a party**, and Xens nearby that like it come over and dance;
  * it **sorts its bag**, or **picks a favourite block** and tells you.
* **...unless it has to keep going.** When what it's doing is needed, a bored Xen holds out:
  * always, when someone asked it, or it's in danger;
  * almost always, when it's starving and getting food, or sheltering at night;
  * often, with no tools, or no stone tools yet;
  * sometimes, on its way to iron.

  A diligent, patient Xen holds out more often than a lazy one, and it says so ("Ugh, this is so boring. But I have no
  tools yet."). The old "bored of this, on to something else" doesn't drop needed work either now.
* **Trust, like a person.** A Xen of its own (with no owner) may say no to someone it hardly knows ("No, I won't: idk
  Steve well enough yet"). It only gives help, like food to someone who says they're hungry, to people it trusts
  enough; a kind Xen trusts more easily.
* **A flat wall in a cave means a trial chamber.** The world builds a solid shell around a trial chamber, so a cave
  that runs into one ends in a dead-flat wall. A Xen that knows this (3 in 10 start knowing; tell one "flat walls in a
  cave mean a trial chamber") notices such a wall. It remembers a trial chamber behind it, and goes there when it's
  ready for one.
* **A map in its head**: exploring a cave, it goes where it hasn't been (the exact blocks it walked, for 20 minutes),
  so it doesn't go round in circles in a maze of tunnels.
* **No cheats needed**: in single player, or on a world you opened to LAN, you can use every /xen command on your own
  world with cheats off. On a server, the commands that change things still need an operator.
* **Simpler settings**: the settings open on **Basics**: chat, talking on its own, Xens per player, own goals, PvP,
  names, skins and AI chat. **More settings** shows every category, as before.
* Tested in the game:

  | Test | Result |
  |---|---|
  | A room carved 25 blocks down, a Xen told about flat walls | it noted "a dead-flat wall: a trial chamber behind it" |
  | 5 Xens of their own, asked by a stranger to follow | 1 said no ("idk Steve well enough yet"), 4 went |
  | Bored Xens (boredom sped up for the test) | plans, a party with a guest who came over, sorting, a favourite block |
  | Bored Xens with no tools yet | "bored stiff, but keeps at it: it has no tools yet" |

### 2.0.0-beta.8.1: proven in the game (and the fixes that took)

Each of beta.8's tactics was tested in the game, with a pass or fail check. The first run found four that didn't work,
now fixed:

| Test | Result |
|---|---|
| Water let into its tunnel right next to it | **pass**: it put a block on the water |
| Lava put in the floor beside it | **pass**: covered |
| A lit creeper 2 blocks away in a 1-wide tunnel | **pass**: a block between them; it had 19 of 20 health after the blast |
| A skeleton shooting from 10 blocks | **pass**: it came in side to side (more than a block each way, 3 turns) |
| A full bag (sticks, logs, dirt, cobblestone, sand, gravel) | **pass**: gravel went, sticks and logs stayed |
| A thunderstorm (a Xen living its own life, told the tactic) | **pass**: it stopped gathering wood and dug down to wait under the ground |
| 5 Xens with 2 diamonds each asked for them | 2 said they had none (they had 2), 1 said no because it needs them, 2 gave them |

Fixed:
* **Telling a Xen how to handle something** now teaches it, even when the sentence sounds like an order or an
  opinion. Before, "in a thunder storm wait in a shelter" made it build a hut on the spot, and "if a creeper hisses,
  put a block between you" got "eww, creepers".
* **Water first**: its "back to shore" reflex came before the tactics, so a Xen with water at its feet swam about
  instead of blocking it.
* **A creeper lit with flint** (not only one hissing at it) counts as about to blow.
* **Skeletons**: the side-to-side dodge is now part of its usual way of going in on an archer between shots.
* Xens teach each other these, too: in the test, a new Xen learned the mace and wind charge tricks from two others.

### 2.0.0-beta.8: what players do (your answers), spears and maces, PvP tiers

* **Taught by a player.** A player's own answers to "how do you survive this?" are now things Xens know, and do. Most
  Xens start out knowing each one (about 6 in 10, different ones for different Xens). One that doesn't can be told in
  chat, or learn it from a Xen who knows.
  * **Water flooding** into its tunnel: it puts a block on it and goes another way.
  * **Lava** next to it underground: it covers it with blocks.
  * **A creeper** hissing right by it (a tunnel, no room to run): a hit to knock it back, then a block between them,
    or the shield.
  * **Skeletons**: on the way in it moves side to side, so the arrows miss.
  * **Phantoms** about, or **a thunderstorm**: it goes in until it's over.
  * **Powder snow**: it mines its way out.
  * **A spawner**: a torch on top (no monsters from it), and it remembers the spot for a mob farm, without breaking it.
  * **A full bag**: the extra stone, dirt, sand and gravel go. Sticks and logs stay.
  * **A friend asks for its diamonds**: it gives them if it has plenty. If not, a greedy Xen may say it has none.
* **Spears** (1.21.11): a spear's jab reaches 2 to 4.5 blocks, further than a sword's 3, but not closer than 2. With a
  spear and a sword, a Xen jabs from out of a sword's reach and takes the sword up close. In a test it held the spear
  at 2.7 to 3.4 blocks from a zombie, the sword closer, and killed it in about 10 seconds.
* **Maces**: with a wind charge, it throws one at its feet next to the foe, goes up, and comes down on it with the mace.
  A mace hits harder for each block it falls. In a test it killed a full-health zombie with one smash. It needs to
  know both tricks: tell it "a mace hits harder the farther you fall" and "a wind charge at your feet launches you
  up".
* **Shield after a combo** (from the Theobald practice bot): hit three times in a row, it puts its shield up until it
  can hit back.
* **PvP tiers**: a Xen with a real player's name (the accurate name style) fights as well as that player is ranked.
  The tier comes from `config/xen/tiers.txt` (`Name HT1`, one a line) or, when there's internet, from MCTiers. In a
  test, `jeb_ HT1` in tiers.txt gave that Xen the best fighting skill.
* Fixed: right before a hit, a Xen picked its sword again, so the axe it took out for a raised shield was never used.

### 2.0.0-beta.7: faster to iron, no hiding in the daytime, fights first, curious exploring

From your play-test reports (slow stone and iron, hiding in a hole in the daytime, not much exploring, chopping wood
while a zombie hits it), and a 12-minute benchmark of 3 new Xens:

* **The furnace stall is fixed.** A Xen with stone in its pockets could stand "making a furnace" for minutes. When
  something it makes needs a crafting table it hasn't put down, two "getting ready" moments kept starting each other
  over. Now it puts the table down, then crafts. This also fixes the builder's doors, stairs and chests. In a test
  it went from raw iron to an iron pickaxe in about 1.5 minutes; before, it never got there.
* **Stone for the furnace first**: with fewer than 8 cobblestone, it mines some instead of waiting.
* **Its first house waits for iron** (or for its second day). On day 1 its Mind could choose a house with stone
  tools and spend the day on wood. Now the first day is for tools and iron, like a player's.
* **Houses without stone use planks.** With no stone, a house's walls were made of whole logs, and one 7 by 5
  cottage asked for 352 logs. Now the walls are planks, with no wooden foundation under the ground.
* **No hiding in the daytime.** It went into a shelter from late afternoon (the game's "evening" starts while it's
  still bright). Now, in the late afternoon it gets ready: it heads home if it's far, or gets blocks for a hut. It
  goes in when the sun really goes down.
* **Fights first.** A monster coming for it, in sight, is dealt with before its chore, so no more chopping wood
  while a zombie hits it. Once in a fight, it stays in it: a step back or a moment out of sight doesn't send it back
  to its tree.
* **No mining into water**: underground, it doesn't break a block with water behind it (a Xen drowned in a flooded
  tunnel).
* **Curious exploring**: places it has spotted and not been to (a village, a temple, ruins...) within 160 blocks, it
  goes to see, and it has a good look around when it gets there.
* **It thinks about the way back up.** Down a mine, its own stairs further than a few blocks off were "unknown rock"
  to it, so climbing out by putting blocks under its feet looked easier. Now it remembers the way it came (its last
  few thousand blocks), so it walks back up its own staircase. In a test it went 17 blocks down and came back up its
  stairs to the surface in 13 seconds, keeping all its stone.
* **It counts what a block is worth to it.** Climbing or bridging with stone it still needs (for a furnace or its
  tools) now costs as much as mining that stone again. So it would rather dig steps up, which gives it stone, and it
  puts down dirt before cobblestone.
* **Accurate names and skins (the new default).** Like the Carpet mod's fake players: a Xen you summon by a real
  account's name (`/xen summon jeb_`), or one from your `config/xen/real_names.txt`, gets that account's real skin.
  Other Xens get made-up names like real players' (never someone's account) and skins real players made (from
  mineskin.org's gallery; the modern skins when offline). It picks real accounts only when you name them.

  Settings you never changed move to this. To get the old look back, use `/xen set nameStyle player` and
  `/xen set skins blob`.
* **Benchmark** (3 new Xens, 12 minutes at double speed, a full day; the same test as before the fixes):

  | | before | now |
  |---|---|---|
  | Stone tools (all 3) | 30 to 45 s | 37 to 42 s |
  | First iron | none in 12 min | 5:07 (iron pickaxe at 5:18); a second Xen at 10:57 |
  | Deaths | 2 (zombies) | 1 (a zombie, at 11:37) |
  | Into a shelter | from late afternoon, sun still up | at sundown |

### 2.0.0-beta.6: Xen's own words, grammar and memory; mining fixes

* **Words, grammar and a memory (no AI model needed)**:
  * **Vocabulary**: about 900 words of its own (with their verb forms and plurals, and chat shorthand: "hw r u" is "how
    are you"), plus every creature, item and block in the game: over 4,000 words. Words it hears and doesn't know, it
    learns: it guesses what kind of word each is from where it stands ("a blorp" is a thing).
  * **Grammar**: it reads what you say as a sentence: a greeting, a question (yes or no, or what, where, who), something
    you tell it, or a request.
  * **Memory (RAG)**: what you tell it, it keeps as facts, with who said them. Questions are answered by finding the
    facts that fit, and from what the game itself knows (what a creature drops, what something is, where a place it
    knows is).
  * **Its own sentences**, word by word, with the grammar right ("a cat likes", "cats don't", "is", "aren't").
  * What it doesn't know, it asks back, and learns your answer. Xens pass on what they were told ("did you know cats
    like fish?").
* From a test in the game:

  | You say | Xen says |
  |---|---|
  | cats like fish | oh, cats like fish? good to know |
  | do cats like fish? | yep, cats like fish (you told me) |
  | what do cows drop? | cows drop beef and leather |
  | what is a creeper? | a creeper is a hostile mob |
  | what do wolves eat? | not sure what wolves eat. what? |
  | bones | ok so wolves eat bones. good to know |
  | the village is at 120 64 -30 | really? the village is at 120 64 -30? cool |
  | where is the village? | the village is at 120 64 -30 (you told me) |
  | what is a blorp? | never heard of a blorp. what is it? |
  | it is a mob | ahh a blorp is a mob! thanks |
  | can pigs fly? / no | not sure... can they? / got it: pigs can't fly |

* **Telling isn't asking**: before, "cows drop leather" and "pigs can't fly" were read as asking for food, and
  "diamonds are at y -58" as "go mining". Now a sentence about a thing is something it's told. A place you tell it
  ("the village is at ...") it remembers, so "take me to the village" works.
* **Diamonds it can't mine yet**: with a stone pickaxe it can't take diamonds. Now, like a player, it remembers where
  they are and says so. It goes back for them once it has an iron pickaxe; this was tested in the game, and it went
  back and mined them.
* **Staircases go straight down**: it plans its mine's staircase a few steps at a time. It used to plan toward one far
  point and could wander off sideways like a tunnel.
* **Xen Ex1 stays v3.** A v4 trained with the spear-practice session too felt danger a little better (0.84 vs 0.82) but
  sprinted worse (72% vs 76%): that session is mostly standing and swinging. The recording is kept for fight learning:
  55 of 91 swings were followed by a swap, mostly spear to hoe or axe, the fastest one tick later.

### 2.0.0-beta.5: fixes from a real play-test (and Ex1 v3)

From 50 minutes of play with a friend and 15 Xens:

* **No more dance-party loops.** Two Xens (or a player crouching at them) set each other off, and "LET'S GOOO!" came
  about 25 times in 6 seconds. Now:
  * a dance takes a new round of crouches;
  * a Xen waits 30 to 60 seconds before it dances again;
  * it says something about dancing at most once in 2 minutes.

  In a test with 30 seconds of crouching next to two Xens, each said one dance line.
* **Crouch hellos**: it still crouches back every time, but says hi to the same person at most once in 2 minutes.
* **Just summoned**:
  * no rush of hellos: a Xen introduces itself, and greets you later;
  * "I missed you!" only to someone it has met before.
* **Tips, one at a time**:
  * a new Xen starts teaching a few minutes after it arrives;
  * only one lesson is said at a time, 45 seconds apart, so 8 new Xens don't all give a "Pro tip" in the same second.
* **Xen Ex1 v3**, trained on that session too: 7 recordings, 24,496 moments, 575 of them just before a hurt. Tested
  on minutes of play neither version saw:

  | | v2 | v3 |
  |---|---|---|
  | Sprint key right | 75.8% | 77.5% |
  | Jump right | 90.2% | 91.0% |
  | Where it looks | within 7.5° | within 7.3° |
  | Danger AUC | 0.77 | 0.80 |

  On the new session's own last minutes it senses danger better (0.75 vs 0.71). Its sprint key there is a little
  worse (71.5% vs 73.4%). A world's learned danger from Ex1 v2 is kept aside, as before.

### 2.0.0-beta.4.2: save gameplay logs anywhere

* **Logs go to** (`gameplayLogFolder`), where the .jsonl files are saved:
  * empty: `config/xen/gameplay_logs` (the default);
  * `gameplay_logs`: in the game folder, next to the gameplay recorder's;
  * any other folder: `/xen set gameplayLogFolder <folder>`. A full path works for a shared or synced folder, spaces
    included.
* A folder it can't write to: the logs go to the default, and the server log says why.
* Changing it mid-game starts new files in the new folder.

### 2.0.0-beta.4.1: gameplay logs for Xens and players

* **Gameplay logs, built in**: the mod writes play in the gameplay recorder's own format (JSONL), one file for each
  Xen and one for each player, in `config/xen/gameplay_logs/`. No separate recorder mod is needed.
  * 4 moments a second, 20 in a fight: where it is, how it moves, health and food, the keys held, hands and armor, what
    it looks at, the blocks at its feet, creatures nearby, and twice a second its view (the recorder's 14 × 24 sight
    lines).
  * Events: damage taken (and from what), deaths, hits, blocks broken (and how long they took), chat.
  * Setting **Gameplay logs** (`gameplayLog`, in the Xen 2.0 tab): off (default), xens, players or both. A player whose
    play is logged is told so in chat.
* **Train on them**: `python -m xen.ex1.train recordings/*.jsonl config/xen/gameplay_logs/*.jsonl`. Xens' own logs
  are left out unless you add `--with-xen`, so Ex1 keeps learning how people play, not how Xens do.
* **Compare a Xen with a player**: `python -m xen.ex1.compare config/xen/gameplay_logs/*.jsonl` puts them side by side:
  how much they move and sprint, jumps a minute, hits and the charge they hit with, how fast they turn to a hit, damage
  and from what, blocks mined, and how well Ex1 predicts their keys (for a Xen: how much it plays like a person).

### 2.0.0-beta.4: corrected notes

* The x-ray talked about in the recorded chat was the friend's, not the recording player's. The recordings are from
  the recording player's own view, so every ore in them was found fairly. Ex1 is the same as in beta.3.

### 2.0.0-beta.3: Xen Ex1 v2, trained on an hour more play

* **Xen Ex1 v2**, trained again on everything recorded: the first 22 minutes plus the new hour with a friend (mining,
  chopping, fights with mobs, a bit of PvP). That's 15,036 moments, 322 of them just before a hurt.
  * Tested on minutes of play it never saw, the same minutes for v1 and v2:

    | | v1 | v2 |
    |---|---|---|
    | Sprint key right | 74% | 77% |
    | Jump right | 90% | 92% |
    | Where it looks | within 8.1° | within 5.9° |
    | Danger AUC | 0.74 | 0.79 |

  * Those minutes are harder now (arrows, a creeper, PvP), so the scores sit below beta.2's own test.
* **Reaction time, measured**: in the recorded fights a player turned to whoever hit them in a median 251 ms. A Xen
  hit by something it didn't see now reacts in about 280 to 380 ms (beta.2: 380 to 530).
* **The new recorder format (1.2)** works for training:
  * state frames 4 times a second, 20 in fights;
  * events for hits, damage, blocks and chat;
  * moments with a screen open (inventory, chests) are left out.
* **A world keeps what it learned for the right Ex1**: what Xens learned in a world on top of Ex1 v1 isn't put onto
  v2, which would undo the new training. It's kept as `ex1-learned.json.old`, and v2 starts fresh.

### 2.0.0-beta.2: Xen Ex1, trained on recorded play; learning instead of rules

* **Xen Ex1**: the first Xen brain trained on a person's recorded game (22 minutes of survival from the gameplay
  recorder).
  * It sees what the recorder saw: 119 senses, from its body and the blocks at its feet to 56 sight lines across its
    view.
  * It learned how that player sprints, sprint-jumps and looks while walking. Xens move that way now.
  * It also learned a feeling of danger: will I get hurt in the next two seconds?
  * On minutes of play it never saw: the sprint key right 84% of the time (vs 72% by always guessing the same), jump
    86% (vs 81%), where it looks within 6° (vs 14°), and danger AUC 0.94 (0.5 would be no better than chance).
  * In the game its danger keeps learning from every Xen's own hurts (they share one Ex1).
  * Train it again on more recordings: `python -m xen.ex1.train recordings/*.jsonl`.
* **No rules for danger: it learns.** The gut's hand-written lava, fire and badly-hurt rules are gone. Now the gut
  goes by what Xen learned:
  * the fear its brain learned for each move;
  * Ex1's danger;
  * **once burnt, twice shy**: what hurt it (lava, fire, magma, cactus, a berry bush, a creature), it remembers, and
    from then on keeps away from it.

  A newborn Xen doesn't fear lava. In a lava school (lava flowing toward a Xen in a corridor), new Xens learned from
  their first burn, and after that only 6% of meetings with lava hurt them (22–33% without it).
* **Teaching**:
  * Xens born with different skills teach each other ("hey Miren, lemme show you something about moving"), and the
    other gets better.
  * A Xen that was hurt by something warns the others ("careful with lava, trust me"). They keep away without being
    hurt first.
  * Ask it: "teach me mining", "any tips for fighting?". One that's bad at it says so ("idk, I'm still learning mining
    myself").
  * Tell it: "lava burns", "stay away from cactus". It minds that from then on.
* **Fights talk like fights**: hit it, and it says "bro what", "you started it", "ez? we'll see". Crouch at it after
  hitting it (a player's sorry), and it decides for itself, from its kindness, its temper, trust and how many times
  you hit it: "fine. don't do that again", or "you ain't my friend after attacking me".
* **It sees past small things**: leaf litter, glow lichen on cave walls, grass, flowers and torches no longer block
  what it can see or aim at.
* **Settings**: "Xen Ex1" on or off, in the Xen 2.0 tab.

### 2.0.0-beta.1: Xen 2.0 (first beta, to test while gameplay is recorded for training)

* **Three brains in one**: everything a Xen could do with a tool, a block or an animal is a choice: **do it, don't,
  or later** (and when, at most 30 minutes on). Each choice goes through three brains:
  * the **Doer**: what it would get, and how much it needs that now;
  * the **Doubter**: what could go wrong (dark coming, monsters, lava behind the block, a drop under it, a pickaxe
    too weak, the last two of a kind);
  * the **Gut**: what happens next if it does it.
  A close call makes it a little confused; a timid Xen waits where a brave one goes ahead.
* **Fishing rod**: it fishes when there's water close by and it's looking at it (it looks over at the pond first),
  doesn't when there's no water or rod, and fishes later when it's dark, a monster is about, it's busy, or it has
  plenty of food. Starving by a pond with no animal close, it fishes for dinner. It reels in a reaction time after the
  bobber goes under.
* **Every block around it**: mine, don't, or later, the way a player mines. From a recorded game: feet and head, right
  next to it (a tunnel two high), hardly ever straight down, copper left in the wall. Never with lava behind the
  block, or the block under its feet with a drop, water or lava under it. Later with a pickaxe that's too weak. Never
  what someone built.
* **Animals: facts, not guesses**: what an animal drops comes from the game's own loot tables, rolled many times.
  "Kill the cow: it drops 2.2 beef (always), 0.9 leather (71%)". "A pig is about 16 hunger cooked".
  * A sheep with shears in its bag is sheared, not killed: about 2 wool, and it lives.
  * Not hungry: later.
  * The last two of a kind: they could breed.
  * Fenced in, named or on a lead: someone keeps it.
* **The Gut takes over to survive**, before anything it was asked, and says so once:
  * lava beside its feet: it steps back, looking at it;
  * in lava: it jumps for the nearest safe block;
  * on fire: into the water close by;
  * three hearts or less with a monster on it: it backs off out of reach, then eats.
* **Reaction time**: it notices new things about a quarter second later (slower for what it didn't see coming, when
  tired or confused; quicker when it's focused in a fight). Its turns are a hand on a mouse: quick through the middle,
  slowing into the target.
* **Confusion**: it builds up from close calls, the dark, a crowd of monsters, a hit from behind, or coming back from
  death. A confused Xen hesitates, looks around, sometimes goes with its other answer, and may ask "wait... which way?"
  It never slows its survival plan.
* **Hidden stats**: each Xen is born with stats nobody sees, which shape it. Children get them from their parents.
  * reflexes;
  * composure;
  * humor;
  * how casually it types;
  * appetite;
  * how picky a miner it is;
  * love of fishing;
  * night owl;
  * stubbornness.
* **Talks like a player, less often**:
  * Typing: "gonna", "wanna", "ngl", "idk", no full stop, no capital, each Xen in its own way.
  * No more "2.7 blocks away", "Fun fact," or "I'm 11% there".
  * Short replies to short lines: "lol" gets "LMAO", "true" gets "fr".
  * It knows "this is peak", "fire bro" and "W build" are praise.
  * A friend's "I HATE YOU" in capitals or with a "lol" is banter, not a fight.
  * On its own it says at most one line every 45 seconds (the "Says on its own" setting: quiet, normal, chatty), and
    in a conversation it talks.
* **More signs**:
  * It makes signs from planks and leaves notes when nobody's around.
  * Where it died, with what killed it: "I died here (skeleton). Careful."
* **Blob skins** (the new default): simple skins in flat colours with two plain eyes, 24 of them, signed so every
  player sees them. Older settings move to them unless you picked other skins.
* **Real names** (name style "real"): Xens take real Minecraft names you list in `config/xen/real_names.txt`, with
  those accounts' real skins (like the Carpet mod's fake players). Only names you put there.
* **Settings**: a new "Xen 2.0" tab with these options:
  * reaction time (human, fast, slow, instant);
  * confusion (human, low, high, off);
  * how much it says on its own;
  * notes on signs.
* **Beta**: the brains use what the game says plus the brains Xen already had. They'll be retrained on the gameplay
  you record. Tell us what feels off.

### 1.9.3: no more standing about, building that works, chat that remembers

* **No more standing about**:
  * At night, a Xen with a pickaxe digs down and mines (stone, coal, more iron), from the surface, its shelter or a
    cave. Before, some just stood "looking around" all night, or kept "taking a breather" with iron ore a block away.
  * With nothing to do for 20 seconds (it used to be a minute), it goes off exploring.
  * At a furnace it mines ore, stone or logs within reach while the iron smelts, instead of standing and waiting.
* **"go explore" means it**: told to explore, it explores for a few minutes, day or night. Before, it said "Okay!"
  and went back to smelting, or built a hut. "go explore?" with a question mark is an order too.
* **"go to the cave"**: "go to the cave", "take me to the village", "go home", "go to the portal" take it to places it
  remembers ("Okay, to the cave at 257 58 -190. Follow me!"). If it hasn't found one, it says so.
* **Building its shelter**:
  * It can put blocks against any block face (slabs, stairs, fences, leaves), not only full blocks.
  * It fills a gap under a wall first, and steps to another spot inside if it can't see where a block goes.
  * Under the ground it digs in instead of building a hut; if a hut can't be finished at night, it digs a hole.
  * "Stuck: nothing solid to place it against" was this.
* **Gathering stone**: it takes stone at its own level first instead of digging a 3x3 crater around its feet.
* **Skeletons**: between shots, armed and healthy, it goes in and hits the skeleton; weak or unarmed, it gets out of
  its sight. Before, it dodged, stood still, and dodged again.
* **Chat that remembers**:
  * Follow-ups are read with what went before: "do you like pigs?" then "what about cows?"; "how do I make a bed?"
    then "and a chest?"; "how many logs?" then "what about iron?".
  * "I'm good, and you?" and "wbu" ask it back.
  * "Is it hard?" is about what you were talking about.
  * With the AI chat model on, it's told what was just said too.
  * "I love diamonds" is talk now, not an order to go mining.
* **Grudges fade**: "sorry" mends things after a hit (a kind Xen forgives faster, a wrathful one slower), and old
  grudges fade by themselves. When it won't do something because you hurt it, it says a sorry would help.
* **Fixes**: "me'll come back to it" and "before me can make tools" read "I'll" and "I can" now; "I want to smelt your
  iron" reads "smelt the iron".

### 1.9.2: the right tool, useful nights, chat that counts

* **The right tool for the job**: a Xen picks the tool that breaks a block fastest (a shovel for dirt and sand, an axe
  for wood, a pickaxe for stone), from anywhere in its bag (a tool in its backpack goes to its hotbar first). It never
  digs with a sword, and it no longer swaps to its sword halfway through a block when something armed walks by. It
  also makes a stone shovel with its stone tools. In the last test runs (4 Xens, 665 blocks) it never dug with a
  sword; the few bare-handed digs were right after its pickaxe broke. It also holds the right tool as others see it
  (before, it could look like it was chopping with a sword).
* **A bed before night**: late in the day, with no bed, it goes for wool from sheep it can see, or walks back to where
  it saw sheep earlier (it remembers). It even stops work on its own house for this; the house picks up after.
  * It only counts wool when it's hunting for a bed, so it no longer stops after one sheep or kills cows instead.
  * With three wool it makes the bed at once, fetching a log first if it has no planks.
  * At night it puts the bed down wherever there's room nearby and sleeps.
  * At night it only goes after sheep if they're close and it's safe.
  * Ask it: "get wool for a bed", "make a bed".
  * In the test, Xens at dusk with sheep about went from sheep to wool to a log to a bed, and slept in it.
* **Useful nights, no standing about**:
  * In its shelter at night it no longer sits until morning: settled, fed and armed with a pickaxe, it digs down from
    inside and mines.
  * At home it crafts, smelts or sorts its chest, and rests only when there's nothing else to do.
  * Some Xens stood still for a minute or more, stuck on the same thing:
    * one wanted torches but had only logs, and tried again three times a second. Now it makes planks, then sticks,
      then the torches.
    * one kept failing to craft a bed while it had a crafting table down.
    * one was holding a bed in a tunnel too tight to put it down.
  * Now a failed craft waits 30 seconds. Anything that ends the moment it starts isn't picked again for 20 seconds.
* **Chat controls what it does**: suggestions are requests now, however you put them: "why don't you build a house",
  "want to get some iron?", "you should get some wood", "should we go mining?", "how about you get some food". It does
  them (if it's yours to ask), and tells you in its own words. With the AI chat model on too.
* **Harder questions**: it answers from what it really knows and does, in its own tone:
  * why it's doing that ("Because I need wool for a bed.");
  * what to do next, or tonight;
  * how to make or find things ("how do I make a bed?", "how do I get diamonds?", about 80 of them);
  * who its best friend is;
  * which is better ("iron or diamond?", "a sword or an axe?");
  * "should I go exploring?" (not at night, it'll say);
  * where it's going, what it did today, and whether it's safe out there.

### 1.9.1: Xens talk with their own words

* **Its own words, no big model**: with the AI chat model off (or not loaded yet, or on a phone without the memory for
  it), a Xen now builds what it says word by word instead of answering simply. It reads what you said (hello, a
  question, what it's about, whether it sounds good or bad, chat shorthand like "u", "wyd", "lol"), a tiny network picks
  what kind of reply fits (greet back, say how it feels or what it's doing, give its opinion, agree or not, ask back,
  cheer you up, crack a joke, take a compliment, answer an insult...), and it puts the sentence together from a word
  library. Light: about 8 KB of numbers and a word list, nothing to download, answers at once.
* **In its own style**: a shy Xen stammers and trails off ("U-um, I'm not sure..."), a cheerful one shouts ("Yay, let's
  go mining!! :)"), a grumpy one mutters ("Finally." to "bye"), a bold one doesn't hedge ("I know."), a silly one jokes.
  Its temper decides how it takes an insult. What it thinks of creepers, diamonds, the Nether or pigs is its own: its
  nature, its sins, its skills, plus a few tastes of its own.
* **Still does what you ask**: requests work as before, and the answer comes in its tone ("On it. I'll stay here.",
  "O-okay... I'll get some wood.", "Fine. I'll stay here."). "I found diamonds!" is news now, not an order to go mining.
* **Xens talk to each other with it**: when two meet, one starts (a question, what it thinks of something, its news)
  and the other reads it and answers ("Found anything good?" "Um, I'm not sure... I really want to grow a village with
  my tribe someday..." "Villages are super good for trades!").
* **It learns what you like**: a laugh, thanks or praise after a reply makes that kind of reply likelier with you;
  "what?" or an insult makes it less likely. Kept in config/xen/words.net.
* **Your own words**: put a words.json in config/xen/ (copy the one in the mod) to add words, topics and jokes.

### 1.9.0: eyes on its work, safe at sea, ready for the worst

* **It looks at what it mines and places**: it turns to a block before it digs (a quick flick, not a snap) and its eyes
  stay on a block it just put down, a table it uses, a chest it opens. Before, the view jumped back the next tick, so
  it looked like it built without looking. Measured on four Xens over 7 minutes: 1 degree off the block, typically
  (placing used to be off by 60 to 130 degrees at times).
* **Never through walls**: it only mines a block it can see and only clicks a face it can see. Something in the way that's
  ground, rock or plants, it digs through first, like a player; something someone built (planks, cobblestone, glass)
  it never breaks to get past: it goes round or leaves it.
* **Safe at sea**: out in open water (a sea, a big lake) it swims for the nearest real shore (not a rock sticking out),
  or back the way it came, or toward the world spawn if it has never stood on land. Exploring won't take it out to sea
  any more. A Xen dropped in the middle of the ocean now swims steadily for land and stays alive.
* **Picking things up**: the best first (diamonds, then tools and armor, then iron, then food, then the rest), the near
  ones before the far, and it sticks with the one it went for instead of turning between two. The same when it picks
  its things back up after dying.
* **No more standing about**: a Xen that had been in a scuffle stood there taking "a breather" twelve times in a row.
  Now it rests only when resting helps (hurt and fed enough to heal, at night), not twice in a row by day; hurt and too
  hungry to heal, it gets food. A rival just being nearby no longer puts its plans on hold. In the same test, time
  with its hands idle went from up to 82% to 5-15%.
* **Fewer pointless fights**: no picking fights in its first minutes in the world, and a bully doesn't pick on its own
  village.
* **Ready for the worst**: before a trip it packs food and blocks (to pillar up or wall off) and, going down a mine, a
  spare pickaxe. Before a risky one (diamonds, the End) it leaves its valuables and spare tools in its chest at home
  (it keeps its best tools). If it dies and its things are gone, it goes home and takes its spares out of that chest
  instead of starting over with nothing.
* **Fixes**: a new recipe was "worked out" again at every step of an ordered craft (a wooden hoe took 38 s); it no
  longer tries for ten seconds at a time to dig while swimming (it stops and tries from better footing), and leaves a
  crafting table it can't pick up.

### 1.8.0: more like a person

* **Why it does things**: every Xen has a role (its job in its village, or the kind of player its plan makes it), a
  purpose (what that role is for), a goal right now, and the action it's on. Ask "what's your role?".
* **The leader sets the village's priorities**: a cautious leader wants food, beds and walls first; an ambitious one
  iron, diamonds and new land; a builder houses. It says so, jobs lean that way, and members follow as far as they're
  loyal and trust the leader. One whose own plan pulls the other way mostly doesn't, and may say so ("Mira wants food
  first. I need iron.").
* **Stone tools are a decision, not a script**: iron, food, wool for a bed, a home, or a look around are weighed by how
  it stands (food left, evening coming, sheep about, a cave it knows, how sure it is where iron is), its plan, nature
  and skills, and its leader's wishes. It sticks with its pick until it's done, then weighs again. Three Xens with stone
  tools can go three different ways (in a test: exploring, food, iron). What it weighed is in the journal.
* **What it knows about ore**: each Xen is born with a rough idea of where iron, diamonds and coal are (a later
  generation knows better), learns from what it actually finds, and changes its mind when the evidence disagrees. You
  can tell it ("diamonds are at y -58") and it takes that in as far as it trusts you; Xens pass on what they've found or
  heard when they chat, right or wrong ("Heard from Steve: iron at y 12."), and it gets corrected by experience. It
  mines where it believes the ore is. Ask "where do you mine iron?": it says how sure it is and who told it.
* **A first look around**: new in the world (or back after dying) it doesn't stand and spin. It walks 10 to 16 blocks
  the safest-looking way (no water, lava or drops; animals, trees, stone), its head turning to what it sees and now and
  then over its shoulder, and then decides what to do.
* **A moment to get ready**: crafting something it has made before is quick; something new takes a moment to work
  out; a new tool gets a look. When its tool breaks it notices, stops, looks at its hands ("Ah, my pickaxe broke."),
  makes a new one if it can and carries on. Never in a fight, in water, at low health or with monsters close. All of it
  counts game ticks; nothing waits or sleeps.
* **Fixes**: with cobblestone and sticks but no planks it makes a stone pickaxe instead of going for wood first; a job
  it was asked to do no longer gives up when its pickaxe breaks and it can make another; it stops at the edge instead
  of stepping off a drop when it's just walking about (two Xens walked off a 30-block drop in a test).

### 1.7.1: fixes

* **Crash fixed**: in single player, leaving a world and opening one again (without restarting the game) could crash
  the game a little later ("RejectedExecutionException ... Chat.sleep" in the crash report). Closing a world shut Xen's
  chat thread down for good, and the next world used the dead thread. Now each world gets a new one, the chat model is
  fully unloaded when a world closes, and nothing Xen does in a server tick can take the game down with it any more
  (a part that fails is logged and the game goes on).
* **Walking like a player**: while running and jumping it no longer turns round mid-jump to look back at the spot it
  just passed, or stares down at its feet: its body turns to where it's going, its eyes look a few steps ahead. Turning
  round also braked it (the keys push the way it faces), so it's faster too (about 20% on a test course).
* **No more backing off at a two-high wall**: it digs a step into it and goes up, like a player, instead of finding
  "no way" and walking back and forth.
* The settings show the brain as **Xen 6.0** (the mind 1.5.0 and later ship); it said "Xen 5.2".

### 1.7.0: houses that look built by a builder, villages with a plan, a world with a story

* **Houses in stages**, the way building guides teach it: basic (the shape), simple (depth: a log frame, a stone ground
  floor), good (details: a light infill between the timbers, darker roof edges, trims under the eaves, sills, lamps, a
  real chimney) and perfect (polish: flower boxes, bushes, a tree, barrels and hay, moss in clusters, a textured roof,
  a winding path). A less skilled Xen stops earlier and upgrades later ("decorate your house").
* **Real shapes, not boxes**: a front gable with the upper floor jutting out over the door, a long house with a cross
  gable and dormers, or an L with a lower wing. Roofs meet like real ones. No more lightning rods on the gables; the
  chimney is a stone stack with its fire in a pot at the top (the smoke rises, no bare campfire on the roof).
* **The house fits the place**: palettes by biome (teal roofs, autumn orange, cherry, swamp mud brick, desert
  sandstone, snowy spruce) with a dark frame and a light infill for contrast; on a slope a narrow gable-front house,
  on flat ground a long one or an L, on water stilts.
* **Paths**: winding, two or three wide, never broken, worn-looking patches of dirt path, coarse dirt and gravel, with
  bushes and flowers, rocks, lamp posts, bits of fence and benches along them.
* **Building together**: Xens help a friend build (their village, team, or someone they trust), or when you ask ("Pip,
  help Aria build"). One plan, shared progress: a block either one puts down counts for both.
* **Village layout by its leader**: modern (straight streets, houses in rows facing them) or freeform (like a Minecraft
  village, round the middle).
* **Places and biomes**: it remembers villages, strongholds, temples, mineshafts, ancient cities, trial chambers,
  monuments and more, each with coordinates and biome ("where's the nearest village?", "what places do you know?",
  "what biome is this?").
* **Server lore**: the world keeps its history (builds, villages founded, discoveries, deaths, the dragon). A
  chronicler Xen writes it into written books, a volume at a time; ask "what happened on the server?".
* **Spawn as Xen** (Experimental): a Xen plays your character and you watch through its eyes. Single player and LAN
  host only.
* **Scrollable settings**: the settings screen scrolls when it doesn't fit (small screens, phones).

### 1.6.0: plays like a person on a server

* **Hits read right**: only one light tap with an empty hand while you're talking to it is a poke. A critical hit, a
  crouching hit, a weapon, a hard hit, a second hit, or a hit out of nowhere is an attack. It reads how you hit it at
  that very moment (a crit before you land), and tells its owner off, worse each time. A wrathful Xen hits back, a
  gentle one keeps away.
* **Boats and horses**: "get in my boat", "hop on", "ride with me", "get on the horse", "get out". Following you, it
  hops into your boat when there's room and gets out when you do. With no seat, it puts its own boat on the water,
  paddles after you and takes the boat back at the shore. A wild horse bucks it off until it's tamed; with a saddle
  it rides after you.
* **Follows when told**: "follow me" means now. It drops its own errands to keep up, and doesn't go back to an old
  task while you're walking.
* **Crouching back and arm swings players can see**: a crouch hello is answered with two to four real crouches, and
  arm swings and block cracks show while it mines and fights.
* **Hunting when hungry**: starving with nothing to eat, its errands wait and it hunts, then eats (before, it could
  starve over a wood errand with cows right there). With no food on it and an animal close by, it takes it.
* **Real night shelters**: no more 1x1 tower. At nightfall out in the open it drops what it's doing and goes home, digs
  into a hillside, builds a little hut with room inside (on a flat spot close by, with a real roof), or digs a hole
  and covers it. Standing under a tree isn't a roof. It stays in until morning.
* **Sharing with other Xens**: spare armor (it already wears its best), a second sword or pickaxe, blocks and food it
  has plenty of go to a Xen close by who needs them. It walks over and tosses it to them (only they can pick it up);
  they put it on or use it and say thanks. Its team, its village and Xens with the same owner get what they lack;
  others only what anyone can see they need (a missing or weaker piece of armor, fighting bare-handed), and only
  from a kind Xen that trusts them. Greedy ones give less, and never to an enemy.
* **Using items**: "light the tnt" (and it runs), "burn that", "use the flint and steel", "bone meal that", "put that
  out": on the block you're looking at.
* **Griefing (setting `grief`)**: `revenge` (default): a Xen that holds grudges, badly hurt by someone whose house
  it knows, may set it on fire while they're away. `off`, or `chaos` (a mean one may burn a stranger's house too).
  Never its owner's, its village's or a friend's.
* **Habits**: it looks around when it arrives, gets bored of doing the same thing too long, grumbles and plays safer
  after dying (and takes a breather after two quick deaths), takes a short detour to look at a village or temple it
  spots, tosses junk when its bag is full, and builds up its own routine for each time of day ("what do you usually
  do?").
* Its head stuck in a block (it woke up in a wall, sand fell on it): it digs out instead of suffocating.
* Older versions that never had a release now have one: 1.4.0 and 0.2.0-alpha.

### 1.5.0: born with a nature, their own plans, Xen 6.0

* **The seven deadly sins**: every Xen is born with pride, greed, lust, envy, gluttony, wrath and sloth (0 to 1),
  one strong and one behind it, fixed for life (a child gets a mix of its parents'). They change what it does: a
  proud Xen fears less, won't run, builds big and boasts; a greedy one mines twice as much, hoards and shares little;
  a lustful one seeks company and pretty things; an envious one gears up and resents better gear; a glutton eats
  early and farms; a wrathful one fights, stands its ground and holds grudges; a lazy one rests, gives up sooner and
  stays near home.
* **60 beliefs**: each Xen is born believing four to six things ("A true warrior never runs", "The night belongs to
  the monsters", "Trust no one", "Animals deserve kindness", "Gold is for fools", "The dragon must fall"...), the
  ones its sins make likely, never two that contradict. Each changes how it plays (fear, patience, risk, when it
  eats, trust, sharing, nights, what it goes for), and many have rules of their own. Ask a Xen "what do you
  believe?" or "what's your sin?".
* **Its own game plan**: a Xen works out how it will play (rush the dragon, build a home first, settle down and
  farm, get rich underground, see the world, trade, fight, play it safe) from its nature and from what worked for
  Xens like it (every Xen's days are scored and shared). It keeps to it, and after two days of getting nowhere it
  says so and changes plans. Ask "what's your strategy?".
* **Xen 6.0**: the new mind, trained on from Xen 5.2 (everything 5.2 learned is kept) with its sins and its plan as
  inputs: the same day feels different to a greedy Xen and a lazy one, and a speedrunner feels the clock.
* **From the start to the end**: every plan now ends with the Ender Dragon (eyes of ender, the stronghold, the End):
  a speedrunner as soon as it has iron gear, the others once they have a home and diamonds, and beliefs can hold it
  back, never for ever.
* **Reacting to people**: someone comes into view and it reacts, in its own way: a crouch hello, a wary step back,
  a stare with its sword out, a boast, an envious look, an offer to trade, or a lazy nod.
* **No standing about**: a minute in the same spot with nothing to show (not asleep, building, farming or fishing)
  and it moves on.
* **Fixed: odd choices after a while**: what Xens learned while playing made their mind's values run away over
  time (then they chose strangely). Learning in the game is steady now, and a mind that drifts goes back to the
  trained one.
* Diamond hunts last longer before it gives up (diamonds are rare); how long depends on its patience.

### 1.4.0: walking with a goal (Baritone style, our own code)

* **Goals, not points**: a Xen walks to a goal the way Baritone does ("next to that ore", "any of these ores"). The
  search ends where the goal says "you're there", so it stops where it can mine the ore, not on top of it.
* **The ore that's quickest to get to**: with several ores in view, it plans one way to all of them at once and goes
  for the one its legs get to first (not just the nearest as the crow flies).
* **Long ways in pieces**: a far goal is planned a piece at a time, and the next piece is ready before the last one
  runs out, so it never stops to think.
* **Smarter parkour**: it sprint-jumps up onto a ledge one block higher across a gap (only where a miss is a fall it
  survives), and runs through bends without slowing down (it only slows for sharp turns).
* **Things on the way**: walking somewhere, it mines ore it passes within reach (not in a fight, in water, at night
  outside or when scared).
* **A short, real plan**: iron first; with iron, diamonds (down at y -58 once it has an iron chestplate or plenty of
  iron); then a house; then netherite.
* **Walks next to what it mines**: to mine a block it walks to where it can reach it, not into it.
* **Fewer false "stuck"s**: something right at its feet (a drop from the block it just mined) is a step, not a reason
  to tower up; a block it can't get at from where it stands is let go after 15 seconds (before: minutes of hitting at it).
* **No death loops**: killed a second time going back for its things (monsters, a fall), it lets them go.
* **Out from under water, for real**: under a roof with the way out going down first (under a ledge, then up), it
  now dives, swims along (flat through a one-block gap) and then up, instead of pushing up into the roof until it
  drowned. Half its air gone, it drops whatever it's doing (mining under water is very slow); after a close call it
  plans no way under water for 30 seconds; and it doesn't dive for sunken drops or ore under water.
* Its own planner is the default again (setting pathMode: xen). The mob pathfinder is still there (pathMode: mob).

### 1.3.1

* **Ore sense**: dark stone or deepslate means ore may be close, and a big dark space walled with stone or deepslate
  is a real cave (the best place for ore). A dark forest or a cellar doesn't count as a cave any more.
* **Giving up, like a player**: a chase ends after 15 seconds without getting closer (or a minute in all); mining or
  gathering with nothing for 3 minutes stops (and a mine that gave nothing is forgotten: next time, somewhere new).
* **Old worlds upgrade**: Xens left in a world from an older version come back and just upgrade. A brain or mind
  file it can't use is kept aside (.old) and the one that ships takes over; an older mind is upgraded to Xen 5.2;
  a saved part it can't read is started fresh without losing the rest; old minions come back as Xens of their own.
* **Settings are saved**: every change is saved right away, there's a Save button, and before an update changes your
  settings file it keeps a copy (xen.json.bak).

### New in 1.3.0

* **Walking like mobs do, running**: Xens plan their way with Minecraft's own mob pathfinder (the one zombies and
  villagers use) and sprint along it. Their own planner (digging through, towering up, bridging) is only for where
  that finds no way. Setting: pathMode (mob, xen, or ab to compare).
* **Eyes on every block**: a yes or no for every block in view (out to 48 blocks, a full sweep every 1.5 s): ore,
  trees, grass for seeds, chests, water, crops, what people build. Caves and other people's houses are spotted from
  what they see, and chores go for what they saw (no more "looking for tall grass" for minutes).
* **A simple rule for ore**: dark stone or deepslate under the grass is where ore hides; with no ore in sight, they
  go and mine into it (cave walls, dark rock they saw).
* **Swimming**: they swim up waterfalls and flowing water (holding space), and swim fast against a current.
* **Smarter when stuck**: they never ask you for help; after three failed tries they pick another target.
* **No more cliff deaths**: they crouch at edges they don't mean to go over, and a dark pit counts as deep.
* **Mobs**: shield up when a skeleton draws its bow (a side step without one); out of the water when drowned are
  about; milk a cow when poisoned; breed animals by their home; shear sheep (they make shears); tame cats.
* **Shield**: always in the off hand, never the sword hand.
* **Real progression**: no crop farm before the iron pickaxe (unless food runs out); after diamond gear they go to
  the Nether for ancient debris, smelt it, and make netherite ingots.
* **Teams: auto** (the new default): like an SMP, Xens start and join their own teams, and their names take the
  team's color. They fight players too: near their base, fighters looking for a duel, bullies. Say "1v1 me".
* **Talk**: a question to a Xen by name is only answered by that Xen. They answer from what they're really doing: "what are you doing?", "why?", "what's your plan?", "what
  do you need?", "how many logs do you have?", "what's in your bag?", "found anything?", "what team are you on?".
* **/xen summon random 5**: five Xens with random names and skins.
* **Lighter**: less RAM (the brain's replay memory is half the size, learning state only when learning), and the
  lag spikes with several Xens are gone (planning results are cached, sight lines read chunks directly).

### 1.2.2: real progress, far fewer deaths

* **A player's plan**: Xens now follow the steps every player knows (eat when hungry; at night a bed, a roof or the
  mine, never wandering in the dark; the next tool; iron; a house; armor; diamonds). Before, they gathered wood and
  stone over and over and got nowhere in 3 days. Their own mind still decides everything else (farms, trading,
  exploring, adventures), and it learns from the plan.
* **No more drowning**: under water with a roof overhead (a flooded tunnel), they swim to the nearest air instead of
  bumping the ceiling; they don't dig into water that would flood a tunnel, don't start a mine next to a lake, and
  don't walk along lake bottoms.
* They stop gathering what they have plenty of, don't explore at night, and don't walk far to a mine in the dark.
* Less "pacing back and forth" when it's really just working around a tree or a rock.
* New mod icon.

Same 3-day test, 3 new Xens: before, stone tools only and 5.2 deaths a day; now all 3 have iron pickaxes (the first
before noon of day 1) and 2.4 deaths a day, none drowned.

### 1.2.1

* Fixed for Minecraft 26.3: hunting endermen for pearls (after the dragon) no longer crashes there.

### New in 1.2.0

* **Caves**: Xens spot caves they can see, remember them, and go mining in them (torches as they go, following the
  cave down, mining the ore in the walls). They take any ore they see on the way (no more walking past iron for stone).
* **Real survival sense**: starving with no food, they hunt (or fish); before night with no bed they hunt sheep for
  wool; out in the wild they put their bed down, sleep, and take it with them in the morning. Their night shelter is
  a little tunnel dug into a hill, sealed behind them. They sprint-jump on long flat stretches, and look up to mine a
  block above instead of stacking blocks to reach it.
* **Fishing**: "go fishing", or on their own when hungry by water (they make the rod from sticks and string).
* **SMP life with 2+ Xens**: they found their own teams (with names like the "Iron Wolves") and invite friends, who
  decide for themselves; they build their bases apart, not on top of each other, and visit friends' bases. Ask one:
  "make a team called Night Owls with me". A truce ends a fight but doesn't make them friends.
* **Chests**: they peek into chests they pass; a greedy one helps itself when nobody's watching.
* **Places**: they recognize villages, mineshafts, dungeons, ruined portals, temples, strongholds, fortresses,
  bastions, End cities and other people's houses from what they see.
* **The End**: they shoot the crystals first, keep clear of the dragon's wings (no more flying across the island),
  and after the dragon they go for an elytra: pearls from endermen, through the gateway, End city chests, the ship.
* **Talk**: many more everyday replies without any chat model (hellos, jokes, "where are you", "do you like me",
  favorites, skills, "are you a bot", comfort when you're sad). Pick the chat model in the Experimental tab: 135M
  (fast, phones), 360M (better) or off; you're told when it's ready.
* **Fixes**: they no longer glance at you in the middle of a job, and get back to a job after catching up with you.
  Minions are gone.

### Fixed in 1.1.1

* **No more walking back and forth**: a Xen keeps to the way it planned instead of re-planning every few seconds,
  doesn't walk back over the blocks it just crossed, and if it notices it's pacing it commits to one way. Going back
  for its things after dying: only if something really dropped there (not with keepInventory), only for things it
  can see, and after half a minute without getting closer it gives up instead of pacing for five minutes.
* **Go to a block**: "Pip, go to 120 64 -40" (or "go to 120 -40"), or `/xen goto 120 64 -40`: it walks to that exact
  block and stays there.

### New in 1.1.0

* **Xen 5.2**: the mind that decides what a Xen does next, made from the two best trained versions merged. In the
  same 300 test lives it dies less (17% vs 23%), gets iron in 84% of lives (was 71%) and diamonds in 63% (was 51%).
* **More human**: Xens nod when they agree, shake their heads when they refuse and wave hello; grudges fade with time;
  kind Xens give close friends gifts; they tame dogs and name them; each has a hobby (flowers, stargazing, sunsets,
  dogs); they notice how many days they've lived in your world.
* **Fixes**: no truce arguments after a friendly spar, no "joined the game" when a Xen respawns, lily pads and
  building next to chests work properly.

### What Xens can do

* **Get on in the world like players**: wood, tools, stone, iron, diamonds, armor, enchanting; their own mine they keep
  going back to; crop farms; furnaces; chests; they pick up useful things lying around.
* **Think for themselves (Xen 5.2)**: a bigger mind trained from scratch in a simulated survival life. It chooses
  what to do next by what it is: kind, loyal, power-hungry or money-driven; friendly, passive or aggressive. When it
  isn't sure, it thinks ahead, and it keeps learning in your world. Far less standing around or wandering at random.
* **Build their own houses**, no templates: cottages, modern houses, houses on stilts, towers, with porches, chimneys,
  lofts, gardens, ponds, workshops, fenced yards and paths. Their taste learns from what you and their village say.
  They may decide to move in together.
* **Build villages**: they share, guard each other, pick a money and open shops, vote on their own rules, give each
  other jobs, and punish rule breakers.
* **Have their own minds about people**: trust is per person. Aggressive ones pick fights; truces are kept. Minions can
  betray their boss and go free. They choose their teams (and team members can fight).
* **Travel**: through Nether portals (they follow you), highways (underground, or under the Nether roof), the
  Ender Dragon, elytra from End cities, and flying.
* **Talk**: they understand plain requests even without the AI model ("come", "I'm hungry", "sleep", "join my team",
  "new rule: no fighting", "build a highway north"), answer to the first 3 letters of their name, and chat with a
  small AI model on your device if you want (off, small or normal).
* **Have skills and a past**: every Xen is good at different things (fighting, building, mining, farming, trading,
  moving) and gets better by training, when it decides to or you ask ("train your fighting"; sparring needs a willing
  partner). Skilled fighters use cobwebs, ender pearls, potions, golden apples, shields and water clutches. They hear
  rumors, are shocked to see someone they killed walk back in, and some are loners who never join a team.
* **Local chat (option, on by default)**: chat and death messages only reach people within 2 chunks, so a Xen standing
  nearby can overhear a plan and warn its target.
* **Are saved** when you quit, with their things.

### Downloads

| file | what |
|---|---|
| `xen-companion-1.7.1+mc1.21.11-with-chat.jar` | **all in one** for Minecraft 1.21.11: the mod with its chat model inside |
| `xen-companion-1.7.1+mc26.x-with-chat.jar` | **all in one** for Minecraft 26.1 - 26.3 |
| `xen-companion-1.7.1+mc1.21.11.jar` | the light mod for 1.21.11 (the chat model downloads if you want it). **For phones** |
| `xen-companion-1.7.1+mc26.x.jar` | the light mod for 26.1 - 26.3 |
| `SmolLM2-135M-Instruct-Q8_0.gguf`, `smollm2-360m-instruct-q8_0.gguf` | the chat models on their own (small for phones, normal for PCs) |
| `TECHNICAL.txt` | **how it all works**: Xen 6.0, the settings, the commands, every change |
| `xen-brain*.bin`, `SHA256SUMS.txt` | the classic brain (already inside the jars), checksums |

**Install**: Fabric Loader 0.16+ and Fabric API, then one of the jars in your mods folder. Mod Menu (optional) gives a
settings screen. In game: `/xen summon`. Phones: the light jar, 2 GB for Minecraft.
Full guide: [dist/README.md](https://github.com/JAK-cool162/Xen/blob/main/dist/README.md).

The chat models are [SmolLM2](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct) by Hugging Face (Apache-2.0).
