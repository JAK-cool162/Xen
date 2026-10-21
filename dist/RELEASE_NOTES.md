**Xen Companion 2.0.0-beta.46**: AI players for Minecraft (Fabric) that live their own lives. They gather, mine, build their
own houses, farm, trade, form villages with their own rules, make friends and enemies, travel to the Nether and the
End, and talk with you. Like a real player, a Xen's mind only knows what it can see and it only acts through a
player's controls.

### 2.0.0-beta.46: Xen gets a massive upgrade

* **Full diamond armor in under 25 minutes**: in a 20-minute test (3 Xens given an iron pickaxe, sword, shield and
  chestplate, "Knows the world" on everything), **2 of the 3 made a diamond pickaxe, a diamond sword and all four
  diamond armor pieces**. Counted from when each Xen joined: first diamonds at about 7 minutes, diamond pickaxe at
  about 9, the last armor piece at about 23 and 24. The third Xen found none (it started by the sea). **0 deaths** (beta.45 in the same test: 4 deaths,
  0 diamonds).
* **Diamond armor straight away**: each piece is made the moment it has the diamonds, even mid-mine (before, armor
  waited for a quiet moment that never came on a diamond trip), with two diamonds kept back for the enchanting table.
  The diamond pickaxe comes before the sword.
* **Mines like it means it**: on its way to diamonds it knows of it leaves the stone it digs lying and its pillars
  standing (that back-and-forth wore out pickaxes), lets a diamond go if it gets no closer in 15 seconds (under water
  all the way, walled off) and takes the next, and leaves diamonds under the sea for last.
* **No more broken-pickaxe dead ends**: a spare iron pickaxe before the old one wears out; a broken iron one is
  replaced with iron, not wood.
* **Safer**: at night its way to ore goes under the rock, not over open ground; deep under the ground it doesn't stop
  for the night (night up there changes nothing down there); out of air too long under water it digs its way up or
  towers up through the water.
* **Enchanting**: the enchanting table is now a step of its own once it has a diamond pickaxe and two diamonds (a book,
  four obsidian, then lapis), with obsidian and lapis found through what it knows of the world. Not seen working in a
  test yet: 0 enchants so far.

### 2.0.0-beta.45

* **Diamonds the way Baritone mines** (with "Knows the world" on its new top setting, **everything**): every
  diamond it knows of close by is one goal for its own way-finder, which digs the cheapest way there; diamonds in
  reach it mines at once. Deepslate diamonds and big clumps first. Test (3 Xens given iron gear): one got **7 diamonds
  in about 8 game minutes**, the first after about 9; the other two found none in the 20 game minutes, so it isn't
  reliable yet.
* **It wants diamond everything**: about 31 diamonds (pickaxe, enchanting table, sword, all four armor pieces), not
  3; its world knowledge now includes obsidian and lapis for the enchanting table. Diamond armor and enchanting
  haven't been seen in a test yet.
* **Stays on its goal**: choices stack up by how necessary they are (its life, then what can't wait, then the main
  goal, then the rest); a house, a farm or a mine entrance is dropped for the main goal; it doesn't wander off a
  diamond trip; boredom takes 15 to 35 minutes, not a few.
* **Fewer deaths**: it sees trouble coming before it's hurt (every monster about, the dark, its armor), keeps to a
  retreat until it's safe, puts blocks between itself and them when cornered, heals before going on, gets under the
  ground by dusk, digs in after dark, crouches at drops it didn't plan (no more long falls), blocks water and lava
  off when mining, knocks mobs off ledges. 10-day test runs: 10 and 5 deaths by game day 6 (the build before: 20 and 29
  by day 7 to 9), no falls.
* Knows what things are for: from village chests only what it needs; sugar cane early for books; some look for a cave
  before their first tree.

### 2.0.0-beta.44

* **Runs to eat**: hurt and hungry in a fight with monsters close, it sprints away first (monsters walk at about 2
  to 2.5 blocks a second, a sprinting player does about 5.6), opens a gap, then eats. It used to stand and take hits.
* **No more walking off cliffs**: whatever is moving it (mining, a cave, a fight, running away), it crouches at the
  edge of a deep drop it didn't plan, like a player does. In the last test a third of the deaths were long falls at
  full health (one Xen fell 75 blocks into a cave). The falls its pathfinder plans on purpose (a short drop, a jump
  across a gap, a water-bucket landing) still happen.
* Not benchmarked yet: both changes build and pass the 26.x compile check, but there was no server test before this
  release. Numbers come next.

### 2.0.0-beta.43


* **Hybrid Xen**: the runner's lesson for every Xen. When it would take a breather while something needed is waiting
  (eat, craft, smelt its iron, wood, stone, mining, food for the bag), it does that first. Test, 6 Xens, same world
  and seed, iron by day 2: **5 of 6** (beta.42: 2 of 6). A stronger version that also put off houses, farms and
  looking round until it had iron gear did worse (2 of 6) and isn't used.
* **Fewer silly deaths**: coming back from dying it drops what it was doing where it died (it used to walk back
  through the monsters to "hide in its hole"); running from a zombie it no longer flips between "take cover from the
  skeleton" and "run" every tick; it doesn't pillar up against a drowned with a trident; it only eats in a fight
  with nothing within 4.5 blocks of it.
* **Fighting monsters**: a sword **sweep** when they're bunched up (one swing hits them all and knocks them back;
  Sweeping Edge makes it hurt), a **sprint-hit knockback** against a crowd or to send one over an edge or into lava,
  then a step back while the sword recharges, and a bite of food between swings to keep the food bar full.
* **Diamonds**: with an iron pickaxe it goes to a cave first; with no cave about, its sense for where things are
  leads it straight to diamonds through the rock (and it keeps at a long dig for 90 s before giving up, not 30 s).
* **Runner mode** (Experimental tab, off by default): only the run to the Nether, in order and quick (wood, tools,
  stone, coal, iron, smelting, food, iron gear, a little sugar cane farm, diamonds, obsidian with water on lava, the
  portal), only ore in the open, caves first, a villager trapped in a pit (three deep, a barrel at the bottom) and
  traded with. Every runner's play is logged with what it sees, fights too, for training the vision model.
* Crouch spam only counts when the Xen can see it (not behind its back). `/xen spawn 1 <name>` names the Xen.

**Benchmarks (honest numbers).** Mob training: one Xen in each of 4 bedrock pens, iron sword, shield, full iron
armor, 1 to 5 mobs at once (zombies, husks, drowned, skeletons, strays, creepers, spiders, cave spiders, witches, an
enderman, vindicators, pillagers, slimes, and mixed swarms of 5), 60 game seconds each.

| Build | Overworld trials | Deaths | Health left |
|---|---|---|---|
| beta.42 fighting + this version's survival fixes | 76 | **0** | 19.4 |
| first try of the new moves (with an early pillar-up) | 84 | 4 | 17.8 |

The 4 deaths were 2 pillaring up early against 3+ mobs (taken back out) and 2 eating with mobs close (now it only eats
with none within 4.5 blocks). Against 5 zombies the new moves did better (20 health left against 16.5); the final
build with those two fixes was not swept again before this release. The Nether pens in this test didn't get the mobs
to attack, so there are no Nether numbers yet.

### 2.0.0-beta.42: Xen gets a massive upgrade

* **Skeletons**: backing off from one with a shield, the shield stays up facing it (test, 8 health, two skeletons:
  2 of 6 died before, none now, 13.5 health left against 9.1).
* **Creepers**: the shield up to a hissing one, a hit to knock it back, the creeper kept between it and a skeleton.
* **Attention**: it keeps to where it's going for 2 seconds instead of changing its mind every moment (a quarter fewer
  sudden turns), a staircase for stone goes one way (no more digging about in the dirt for minutes), and it gives a
  chore at least 2.4 minutes before giving up.
* Everything in beta.41 below, with the bare-handed "sprint away" taken back out (it lived less, not more).

### 2.0.0-beta.41: Xen gets a massive upgrade

* **Xens know the world** (the "knows the world" setting, on by default, off any time): where the caves, villages,
  herds and other players are, and ore that shows to the air or sits one block behind a wall (not ore deep in solid
  rock). They don't beeline: they wander that way, in legs that bend off the line, as if they found it by chance. A
  fight, a greeting, anything they do about it is still their own call and still needs sight to land.
* **Water**: real swimming (sprint-swim, up for air at 70%, a door as an air pocket), a boat for long crossings, no
  long ways under water, respawning on dry land, out from under an overhang (3 of 3 got out in the test; the old jar
  stayed pinned), and a step sideways out of gravel instead of suffocating in it.
* **The road to the Nether**: a shield, then armor; 2 diamonds kept for the enchanting table; enchanting comes before
  crafting more gear; obsidian (14, looking for lava deep down, water on lava), flint from gravel, the portal built,
  its frame cleared, lit and walked into. Tested with the gear given: it enchants, builds and lights the portal and
  goes through.
* **The Nether**: ghast fireballs punched back (or blocked, or dodged), blazes, hoglins, wither skeletons, magma
  cubes, piglin anger, the lava seas; gold traded with piglins for ender pearls; piglin brutes kept clear of until it's
  in diamond.
* **Fights**: the axe when it kills a skeleton in fewer crits than the sword, a rush at a skeleton close by, five
  fighting styles, crouching at cliff edges, and a skeleton shooting from up to 16 blocks is a fight it's in (no
  turning its back on it for a chore). Creepers: the shield up to a hissing one (it takes the whole blast), a hit to
  knock it back and away till the hissing stops, and with a skeleton about, the creeper kept between them so the
  arrows hit it.
* **Staying alive**: no hunting in the dark bare-handed, no hunting for animals deep in the caves (where one walked into
  lava), a build that's stuck for 4 minutes is given up (one Xen with an iron pickaxe had stood 35 minutes at one
  block), and the urge to dig in for the night no longer fires with a skeleton shooting at it.
* **Jokes**: "Hey, guess what?", "wanna hear a joke?", knock knock, with players and with each other. A "No" or a
  "bruh" and it's embarrassed ("oh. ok 😿"), sometimes mad ("😿😾"); called geeked while it's embarrassed, it snaps:
  anger and bloodlust 100%, and it comes for you.
* **Together**: "let's make a team called ..." (each Xen decides), roles on a team (keeper, miner, fighter, explorer),
  "come here" that really comes, back for its things after dying (a friend along, if they want), sugar cane, animals
  led into its pen.
* **Benchmark** (6 Xens, normal difficulty, the test worlds): beta.40 over 2.1 days: 1.05 deaths a Xen a day, 1 Xen
  with iron. beta.41's runs over 4 days: 0.5 to 1.2 deaths a Xen a day, 3 Xens with iron in each world, 2 with an
  iron pickaxe, up to 2 in full iron armor. No Xen has got to diamonds on its own yet: that's next.

### 2.0.0-beta.40: Xen Ex1 v5, learned from a player building a house

* **Ex1 v5.** Ex1 (how a person moves, turns and looks, and when a hit is coming) learned from 55 more minutes of a
  player's recordings (the light F8 kind): building a house from scratch, 2,231 blocks placed and 1,059 broken. On
  held-out play it never trained on (the same network trained without those recordings, against with them):
  * building: forward key 79.2% -> **89.1%**, sprint 84.3% -> **99.5%**, attack 96.6% -> 97.5%, where it looks next
    11.3 -> 11.0 deg off.
  * the older recordings (mining, fights, an XP farm, elytra): forward 92.9% -> 92.5%, attack 92.9% -> 93.9%, seeing a
    hit coming 0.890 -> 0.884 (AUC), where it looks 7.9 -> 8.0 deg: about the same.
  * In a world (6 Xens, 2 game days, the same test as before; one run): 10 deaths (12 the run before), the gut dug in 12
    times, 54% of their choices came to something (55%), Acquire Hardware, Isn't It Iron Pick, Sweet Dreams and A
    Seedy Place 1 each: about the same, as it should be (it's how a person moves while building it learned).
* **A new house palette, "cabin", from that player's house**: oak plank walls in a spruce log frame, a deepslate tile
  roof trimmed with spruce stairs, spruce floor, doors and trapdoors, glass panes, lanterns. Xens building a designed
  house (creative, or upgrading theirs) pick it in forests, taiga, snowy places and the plains. (Survival Xens still
  build with the wood they have.) The house itself could be read back from the recording (where each block went:
  about 1,190 placements) but not which way each stair faces, so it's the materials it learned, not a copy.

### 2.0.0-beta.39: Focus (Xens keep their eye on the goal), and they swear like players

* **Xen Focus, a new part of its brain.** Its mind picks what to do (wood, a mine, food, a shelter) and a chore does
  it, and a lot of things cut a chore short on the way: a fight, a hit, its gut, something lying on the ground,
  someone talking to it. Until now a chore cut short was the end of the goal: the Xen forgot what it was doing and
  chose again, often something else. Focus tells a goal that's over (done, or given up on) from one that was cut off,
  holds on to the cut-off one while the interruption lasts, and goes back to it when it's quiet ("back to get some
  wood"). Whether it's still worth it, it learns: a small model for each kind of goal of how likely a goal like this
  comes to something (how long it's been at it, how much ground it covered, how often it was cut off, night, danger,
  health, hunger, underground or not), trained on how every goal ended and shared by all Xens in the world. It lets go
  when that's below 25%, after 4 cut-offs, after 20 seconds of waiting, or when night falls on work out in the open (a
  shelter matters more then).
* **No more trying the same thing over and over.** A goal that comes to nothing the moment it starts (no animals about
  to hunt) isn't chosen again straight away, and the more often it does that, the longer it waits (20 seconds, up to
  5 minutes).
* **Swearing like players, not only when angry.** A foul-mouthed Xen swears just talking too: "this shit is so far",
  "hell yeah", "why the hell is it dark already", "fucking awesome", "damn, I'm hungry". Anger makes it worse. A
  clean-mouthed one still hardly ever does (it's each Xen's own hidden stat), and still never a slur.
  (CrossCheck: a clean mouth 0 of 500 lines, a foul mouth just talking 277, furious 428.)
* **Measured** (6 Xens, 2 game days, the same world; one run each and different Xens each run, so luck plays a part):

  | | 2.0.0-beta.38 | 2.0.0-beta.39 |
  |---|---|---|
  | choices that came to something | 26% (41 of 157) | **55%** (53 of 97) |
  | going for food | 57 times, 4% came to something | **3 times, 100%** |
  | mining | 41 times, 24% | **32 times, 34%** |
  | wood | 100% (10) | 87% (23) |
  | deaths | 6 | 12 (none from going back to a goal: drowning, a wall, skeletons and zombies while mining) |

  Deaths go 6 to 13 from run to run without Focus too (11 and 12 in the two runs with it), so that isn't a change
  either way yet. A first version held goals for up to a minute and kept a Xen out at dusk (9 deaths the first night):
  fixed before release.

### 2.0.0-beta.38: Xens swear, get angry, and some want blood

* **Swearing.** Xens swear the way players do in chat: "oh shit, a creeper", "wtf was that", "Ow, fuck!", "this is so
  damn far", "ffs stop!!". How much is each Xen's own (a new hidden stat, a foul mouth: some never swear, some do it
  all the time) and goes up with its anger. Only the words players use in a heated moment: never a slur. Their chats
  with each other, remarks, answers and whispers all swear. `/xen set swearing false` (or the Chat tab) turns it off.
  Swearing at a Xen ("fuck you", "stfu", "fuck off") is an insult now.
* **Anger** (hidden, never shown). It stacks: a hit, a hard hit (two), an insult, dying (two; killed by someone: four),
  someone getting away, now and then a monster's hit. Each stack is 1 to 6 anger. Anger cools in about a quarter of an
  hour (slower for a wrathful one, quicker for a cool head). An angry Xen swears more, hits back sooner (at 60%, even
  at its owner when they keep hitting it) and doesn't let a friend's hit go (at 70%).
* **Bloodlust** (hidden). Each stack of anger also adds 1 to 3 bloodlust, so it climbs about half as fast: 100% anger
  isn't 100% bloodlust. It lingers for an hour or two, and a bloodthirsty Xen (a new hidden stat) never loses all of
  it. From 20%, a smaller grudge is reason enough to fight someone (from 60%, any). From 50%, now and then (up to 3 in
  10 a minute) it picks someone near it that it has nothing against and goes after them, for no reason at all, for
  two minutes. Never its owner or a close friend for no reason (its own village only at its worst, 85%), never in a
  truce, never with PvP off. It's still its
  own call: its mind weighs the fight like anything else. A kill takes the edge off both.
* **Sight.** Xens see 128 blocks now (8 chunks, as most players play): what they notice and remember (trees, water,
  lava, houses) up from 96 blocks, the picture of their view up from 64 (half of it while no player is near). New
  setting `sight` (64 to 256 blocks; never past the server's view distance). What it makes of its view (a cave mouth, a
  village, dark ground, what it turns its head to) still reads as far as its mind was trained on.
* **Back from dying in the dark with nothing, it digs in.** Its gut also counts having no weapon: at night with a
  monster near and no sword or axe, or just respawned without one, it digs in instead of punching zombies. (In one
  test two Xens kept respawning at the spawn into zombies: 11 deaths in a few minutes.)
* **Measured** on the test server:
  * 4 Xens, a player hitting them with a sword and insulting them for 3 minutes, another standing by: anger 100%
    within about a minute and a half of hits, bloodlust 60 to 100%; they went after the one who hit them (3 to 6
    fights picked) and, 1 to 3 times a run, after someone they had nothing against (another Xen); 17% to 60% of their
    lines swore, depending on whose mouths they had; no errors; 6.0 to 6.6 ms a tick.
  * 6 Xens on their own, 2 game days, the same world as before (one run each, different Xens each run, so luck plays
    a part): 2.0.0-beta.37 8 deaths (one Xen 4), 2.0.0-beta.38 **6** (none more than 2); the gut dug in 12 times;
    Stone Age and Getting an Upgrade 6 of 6, Monster Hunter 5, Acquire Hardware, Isn't It Iron Pick, Sweet Dreams and
    A Seedy Place 1 each; 2 alive the whole 2 days (3 before). Nobody picked a fight out of anger there: nobody hurt
    them but monsters. 14 to 21 ms a tick at 2x speed, as before.
  * Sight 128 against 64: eyes and vision 0.76 against 1.01 ms a tick (different Xens: no cost that shows).

### 2.0.0-beta.37: Xens get through the night

* **Its gut can make it dig in.** Out in the dark when it can tell a hit is coming (Ex1's danger high, or monsters
  close with its health low, or three or more around it), a Xen doesn't wait for the next decision or a coin flip: it
  digs into the ground or the hillside, closes the hole behind it, and waits for the day. With a pickaxe, food and
  health it mines from there through the night, then mines its way up when it's light. When danger is that high its
  gut also wins every argument with its plan.
* **Caught outside after dark, it digs in** instead of trying to build a hut it has no time for.
* **Zig-zag.** Running from a skeleton or anything else that shoots, it weaves left and right every third of a second.
* **Its things can wait till morning.** After dying at night without a weapon, it doesn't run back into the dark for
  its things (that's how one Xen died 13 times in a row in the last test): it digs in where it is and goes back when
  it's light. Items left on the ground only age while a player is close enough to keep that ground running (the
  server's simulation distance), so the Xen counts only that time against the 5 minutes they last, and knows when
  they're really gone.
* **Measured** (6 Xens, 2 game days, the same world seed, one run each, so luck plays a part):

  | | 2.0.0-beta.36 | 2.0.0-beta.37 |
  |---|---|---|
  | deaths | 21 (18 at night) | **8** (all at night) |
  | Xens alive the whole 2 days | 1 | **3** |
  | Acquire Hardware (iron) | 1 | **2** |
  | iron pickaxe | 0 | **1** |
  | iron on hand at the end | 0 | **11** |
  | dug in for the night | 0 | 10 (8 by its gut, 2 waiting to go back for its things) |
  | time per game tick | 14 to 21 ms | 15 to 18 ms |

  One Xen still died 4 times, and no one got diamonds or went to the Nether in 2 days.

### 2.0.0-beta.36: Xen Ex1 v4, learned from a player's endgame play

* **Ex1 v4.** Ex1 (how a person moves, turns and looks, and when a hit is coming) learned from 26 minutes of a
  player's recordings on top of what it had: diamond mining at y -55 with fights (386 moments just before a hit), an
  enderman XP farm, a base, a mace and wind charges, elytra. On held-out play it never trained on (before: the same
  network trained on the older recordings alone):
  * that player's: seeing a hit coming 0.80 -> **0.92** (AUC); forward key 85.7% -> 91.5%, jump 86.6% -> 88.7%,
    attack 88.7% -> 90.0%; where it looks next 10.2 -> 8.7 deg off.
  * the older players': seeing a hit coming 0.844 -> 0.856; forward 92.7% -> 93.1%; attack 94.8% -> 93.3% and where it
    looks 7.3 -> 7.8 deg (a little worse).
  * How that plays out in a world wasn't measured.
* **F7 records with pictures, F8 without** (in 2.0.0-beta.35 it was sneak + F8, so a toggled or held sneak picked the
  heavy one by accident).

### 2.0.0-beta.35: record hours of play without the lag

* **F8 is light now.** It records what you do and see in the form Xen Ex1 learns from as it is: your keys, where you
  look, what's in your hands, health and hunger, what you mine and hit, the creatures around you, the blocks at your
  feet, and a small view (14 by 24 sight lines twice a second). No pictures, no screen grabs. One file,
  `.minecraft/xen_recordings/play_<date>.jsonl.gz`, about 1 MB an hour (the old recording: over 100 MB). On a test
  client: 0.42 ms of the game's time a tick on average, against 1.15 ms with pictures.
* **Hold sneak (Shift) and press F8** for the old recording with pictures (Xen's eyes, `/xen tour`).
* **On your own server or in singleplayer, nothing runs on your game at all:** `/xen set gameplayLog players` has the
  server write every player's play in the same form (`config/xen/gameplay_logs/`). These logs have chat in them; look
  before you share one.
* **`/xen note <text>`** puts a note ("trading with villagers") in your gameplay log, like F9 does in a recording.
* **Older recordings still count:** `python -m xen.ex1.train` reads 2.0.0-beta.33/.34 session folders too (what they
  didn't write down, like creatures behind you, is worked out from the view).
* Tried on a player's first recordings (8.6 minutes): Ex1 trained with them scored the same as without (93% on forward
  either way on older held-out play; danger AUC 0.84 either way), so this release doesn't change Ex1. That much is too
  little to move it: about 30 to 60 minutes of each kind of play is what would.

### 2.0.0-beta.34: XenTalk, Xens talk in their own words

* **XenTalk: a small language model of Xen's own**, made for this and nothing else: 4 layers, about 2 million
  numbers, 2 MB in the jar, no download, no GPU, no internet. It reads what's true of the Xen right now (what it's
  doing and why, what it has, what it saw, how it feels, its nature, who it's talking to and how much it trusts them,
  what they just said) and writes a line word by word. Nothing is picked from a list. It speaks in chats between Xens
  (each line answers the last), in its remarks, and in answers to you when its word library has nothing that fits.
  ```
  <mikomisz> hey, Hiemberz__. got wood for my pickaxe.
  <Hiemberz__> got some wood, gonna get stone.
  <mikomisz> um, see you later.
  <soda99> um, i'm bored digging. want to go mining?
  <mikomisz> get more wood for a pickaxe?
  <Hiemberz__> i'm chopping wood for my pickaxe. feeling a bit uneasy at night, now.
  <byte_nova> i'm swimming back to heal. the cave. it's too far out?
  ```
* **It can't make things up.** A line that names something that isn't true of it (iron it doesn't have, a creeper
  that isn't there, a number it doesn't have) is thrown away and it tries again; after 3 tries its old voice answers.
* **It never slows the game**: it works on its own low-priority thread, about 140 ms a line on one core.
  `xentalk` in the settings turns it off.
* **How it was made**: a bigger open model (Qwen2.5-1.5B-Instruct, Apache-2.0) ran once on a computer of ours and wrote
  replies for situations made from 631 real Xen states on a test server. Replies that named anything not in the facts,
  ran long or sounded like an assistant were thrown out (about 7 in 10 kept, 4,246 in all), and XenTalk learned from
  the rest. The big model isn't in the jar. `scripts/xentalk/` has the scripts to make your own.
* **Honest limits**: it's small. Lines are short (about 9 words) and lowercase, and sometimes don't follow what
  was said. On situations it never trained on, 91% of its first tries stuck to the facts (only those are said).
* **`/xen tour [minutes]`: training data without playing for hours.** You float through the world as a spectator, a new
  view every 3 seconds (out on the land, inside caves, high over it), your view turning slowly, further and further
  from where you started. Press F8 and the recorder saves it while you're away from the keyboard. These are marked as
  spectator views (for Xen's eyes, not for how a person plays). `/xen tour stop` puts you back where you were.
* In tests:
  * XenTalk on its held-out situations (never trained on): 91% of first tries stick to the facts, about 9 words, 97%
    different. Java gives the same words as Python (12 of 12) at about 140 ms a line on one core.
  * In game (4 Xens kept close on flat ground with trees, a player talking to one, 5 minutes a run): 9 to 11 chats and 20
    to 34 remarks a run, no errors, the server at 5.4 ms a tick (the limit is 50). Words spelt out letter by letter
    ("icky.") went from 6 lines in 69 to none in 99 with the rule that stops it; broken contractions ("i's") from 5 in
    56 to 1 in 43, and the rule was tightened after that.
  * 10 Xens on a normal world for 4 minutes never met, so no chats: XenTalk talks when Xens or players are together.
  * Tour (a test client, 1.8 minutes): 36 places, 411 of 440 frames marked spectator, about 134 MB an hour.

### 2.0.0-beta.33: eyes, rules of its own, and chats that mean something

* **Xen vision.** Each Xen now has a picture of what it sees: 64 by 36 sight lines over a player's field of view, from
  where its head points. Each line stops at the first block or creature it meets, so nothing shows through a wall. No
  textures and no GPU: a pixel's colour is the block's map colour, shaded the way the game shades it (tops brightest,
  sides darker), darker in low light, hazier far off, and the sky's colour by the time of day. Every pixel also knows
  what it is (the block or creature, how far, how light). Sharp in the middle, coarse at the edges. Near a player it
  looks at one row a tick (a whole view in about 1.4 s); out of everyone's sight it's slower and shorter-sighted.
  Measured on a test server: about 1 ms of server time per whole view per Xen.
* **What it makes of it**, by simple rules: a cave mouth, a drop in front of it, a ravine, something people built (a
  village with villagers), dark ground where monsters spawn, a monster that went behind cover, a mess near its home.
  That goes into its mind's inputs, its own rules (below) and its chat. A cave mouth it sees becomes a cave it can mine
  in. Its head turns to what moves or stands out, when its hands are free. Hints, not orders: its mind still decides.
  `/xen vision` shows what each Xen sees and what that costs; `/xen vision dump` saves its view as a picture.
* **It writes its own if/then rules.** Every choice it finishes is counted against what was true when it chose (night,
  underground, hurt, no food, its pickaxe, monsters about, what it sees, being stuck). When a choice clearly goes better
  or worse in some situation than it usually does, it writes a rule ("if monsters are about, then don't get wood") and
  leans that way, as much as it's sure of it. When later times don't bear a rule out, it crosses it out. It tells other
  Xens its rules when they chat; they take them as hearsay. Kept with the world. `/xen rules` lists them.
* **It notices when it's going round in circles.** The same choice again and again with nothing to show for it, or two
  minutes in one spot with nothing gained: that choice is off for a while, it tries something it hasn't tried, and
  whatever gets it out can become a rule for being stuck.
* **Chats come from what it's really doing.** Xens remember what the other was off to do last time and ask how it went;
  the answer is what really happened ("Not great. A skeleton got me. Lost my stuff."). They say what they're doing and
  why. One asks for what its plan is short of: the other hands it over, promises to bring some (and does when it can), or
  says no. They invite each other along ("Want to come mining with me?") and, if yes, lean the same way for a while.
* **Chests it sees, it goes for.** It used to notice a chest only within 12 blocks; now it goes for one its eyes saw up
  to 40 blocks off (not on a long walk in the dark). One it can't get to, it remembers and leaves alone.
* **A recorder for training data** (in the same jar, client side, off until you press F8; F9 adds a note like "going
  for iron"). A few times a second it saves a small picture of your screen (128 by 72), what each part of it is, Xen's
  own picture of the same view, where you look, your keys, the item in hand, health, hunger, time of day, and what you
  mine, place and hit. No chat, no names, no server address. It saves to `.minecraft/xen_recordings/` on your own
  device. `python -m xen.vision.tune` fits Xen's colours and shading to your real screen; `python -m
  xen.vision.recordings summary` says what's in a recording.
* In tests (10 Xens, a fresh world, one game day each, the same seed):
  * Rules: 6 written and 1 crossed out in a day.
  * Vision: in two runs with it, cave trips 26 and 16 (8 in a run without it), and deaths 4 and 8 (10 without). That
    is too few runs to call it better.
  * Recorder (2 minutes on a test client): 240 pictures, 4.4 MB, about 131 MB an hour. Tuning on that recording: Xen's
    colours were off by 47 a channel, 21 after (on the same pixels, so a fit, not a test).

### 2.0.0-beta.32.1: /xen time, where their time goes

* **`/xen time`**: for each Xen, and all of them added up, the game minutes it spent on each kind of thing its mind
  chose (mining, a shelter for the night, wood, exploring...), how many times, how often that got it somewhere, and how
  often it ended in its death. The honest answer to "what does it actually do all day?".
* What it showed (5 Xens, a fresh world, 3 game days): mining 34% of their time (28% of trips found something), the
  night's shelter 25%, stone 11%, exploring 11%, wood 10%, a house 1%; 15 deaths (skeletons 6, falls 4, creepers 2,
  drowned 2, a zombie), and no Xen lived a whole day. Keeping them alive comes next.
* Everything else is as in 2.0.0-beta.32.

### 2.0.0-beta.32: a society of their own, the way people make one

Inspired by Altera's Project Sid (1,000 AI agents in one Minecraft world forming a civilization). None of it is
scripted: each Xen weighs every part by its own nature, the way a person would.

* **A constitution with a tax.** A village that votes a tax in writes a rate into its constitution (5 to 30%, what its
  proposer would pay): that share of everyone's village money each morning, paid to the leader. One that won't pay
  breaks the rule (a warning, a fine, then the village turns its back on it).
* **They change it by vote.** Each Xen feels its own way about the tax: power and loyalty for it, a love of money
  against it, and what paying it costs it. When the village's feelings drift away from the rate, the one who feels it
  most proposes an amendment ("This tax is too much. I say 9%. Let's vote.") and each votes for the rate closer to what
  it would pay. You can propose one too: "new rule: tax 10%".
* **Opinions spread by talk.** In their chats with each other, a chatty Xen with a strong view argues it ("Taxes help
  everyone!", "20% is too much.") and the other moves toward it as far as it trusts the speaker.
* **Faiths.** Now and then a chatty Xen with a kind heart (or a proud one) founds a faith and becomes its priest: in
  the tests, Breadism ("The Bread lights the way down"), the Order of the Gentle Golden Carrot ("The Golden Carrot
  rewards those who share"), the Followers of the Furnace. It preaches in its chats and may bring a gift with the
  blessing ("I trade not just goods but the blessings of..."); followers spread it too, less keenly. Each one decides
  for itself: trust in the preacher, curiosity and a gift weigh against pride and a faith of its own. Fellow followers
  trust each other more, and that shows wherever trust does (sharing, votes, trades).
* **New jobs:** priest, engineer (a farm to make run by itself), decorator, alongside woodcutter, miner, farmer,
  builder, guard and trader.
* **`/xen society`:** each village's constitution, tax, what its members would pay on average, its amendments and jobs,
  and each faith with its followers (and their share of all Xens). A history for graphs is kept in
  `<world>/xen/society.csv` (followers of each faith, each village's tax, over time).
* **Fixed:** Xen-to-Xen chats could stop for good if a Xen died in the middle of one.
* **Tested** (10 Xens close together, sped up): in one game day three faiths were founded and 2 of 10 and 3 of 10 Xens
  joined two of them; three villages voted in their first rules; in another run a village voted in an 18% tax (2 for,
  1 against) and another voted one down (2 for, 3 against). An amendment needs a village with a tax that holds
  together for a couple of days: not seen in the tests yet.
* Everything else is as in 2.0.0-beta.31.

### 2.0.0-beta.31: it goes looking for iron like a player (caves, deeper, longer)

From 10 recorded Xens: they reached a stone pickaxe, then dug stone and coal near the top of the mountain, gave up
("Giving up on this spot") and started over, walked past the caves they pointed out, and got bored within minutes.
What was going on, and what changed:

* **Caves it sees are caves it uses.** "Cave entrance over there" was only a remark: that cave never made it into the
  list its mining trips look at. Now a cave mouth, the inside of a cave or a ravine it recognises goes there, and its
  next trip for iron heads to the nearest one (the walls show the ore). A cave it breaks into on its way down, it now
  explores for ore instead of digging on past it.
* **A look round first.** With no cave known, a curious Xen often has a look round low ground for one (up to about 45
  seconds) before it digs its own staircase; a lazy one seldom bothers. A cave it can only get to through water it
  crosses off, and it digs its own mine instead.
* **It keeps going down.** On an iron trip, coal it already has plenty of and plain stone ("there might be ore in
  there") no longer pull it off its staircase (ore right next to it it still takes). Every step down counts as getting
  somewhere: it used to give up after "nothing for 3 minutes" while still on its way down. Even a lazy one gives a
  mining trip about two minutes now.
* **Staircases that look ahead.** Water, or daylight on the steps ahead (the staircase about to come out of a hill's
  side, a lake in the valley): it turns the staircase another way, like a player does. Flooded anyway: it starts again
  on the highest dry ground close by, not on the river bank. Stone or ore on the bottom of a lake it leaves (it used to
  swim after it for minutes).
* **A longer attention span.** Boredom now builds only when nothing new is happening: something it never had before
  keeps it interested, so it no longer throws a party five minutes into its life. The mine-entrance hut waits until
  its mine has paid off (an iron pickaxe); it used to spend a fifth of its first day building one.
* **Tested** (10 Xens from a fresh world, the same spot in a cherry grove mountain, against 2.0.0-beta.30.2): mining
  trips given up in the first 10 minutes 10 -> 3 (4 -> 1 in another run); trips into caves 0 -> 15; caves noticed
  22 -> 41; 2 iron pickaxes against 1. Over a whole first day (night included) deaths went from 22 to 9 and 10 in two
  runs, give-ups from 11 to 5 and 8. In one 10-minute run 3 of the 10 had an iron pickaxe (none before).
* Everything else is as in 2.0.0-beta.30.2.

### 2.0.0-beta.30.2: loop until every Xen has a real player's skin

* **Loop until real player skin.** A new switch next to Skins (Basics and Xens tabs; `/xen set realSkins true`). On:
  it keeps looking until every Xen wears a skin a real player made (mineskin.org's public gallery) instead of one of
  the mod's own. Offline, or the gallery ran short, it tries again later and further back through the gallery. A Xen
  still in a stock skin changes into a real one as soon as there is one: out of the game and straight back in, like a
  player changing skins, with its bag, health and place kept and no "joined the game". New Xens get a real one at once
  when one is in. A Xen named after a real account keeps that account's skin.
* Tested: four Xens in the mod's modern skins all changed within 30 s of switching it on (a diamond in a bag stayed
  there); one in Minecraft's own Steve skin changed too, its things kept. Two new Xens came in real skins straight away.
* Everything else is as in 2.0.0-beta.30.1.

### 2.0.0-beta.30.1: a How to page in the settings

* **How to tab.** In the settings (Mod Menu → Xen Companion), right under Basics, so it's there before "More
  settings": the few commands and chat lines to get going on one short page. `/xen summon`, `/xen spawn 5`,
  `/xen dismiss`; "Pip, follow me", "get me 10 logs", "build a house", "what are you doing?"; `/xen status`,
  `/xen plan <item>`, `/xen log`, `/xen set`. Each one has a few words on what it does. For anyone who doesn't read
  the README.
* Everything else is as in 2.0.0-beta.30.

### 2.0.0-beta.30: it leaves the land the way a player would

* **Pillars come down.** The dirt pillar it jumped up on (to reach the top of a tree, to get out of a zombie's reach)
  comes down again once it's done up there: still on it, it digs the block under its feet; down already, it takes the
  pillar apart from the ground, top first. In a test field it took down 8 of 11 pillars right away (2 stray dirt blocks
  left, 9 before); a 3-day test world logged 34 taken down in its last 5 minutes alone.
* **Its night's hut** it may take down again in the morning, roof first (not its home).
* **Mines into hillsides.** A new mine starts at the foot of a hillside close by, two blocks straight in, then the
  staircase down; not a hole in the flat ground. In the 3-day test worlds 19 of 34 new mines went into a hillside; the
  rest had flat land all round.
* **Habits, not rules.** A diligent Xen nearly always tidies up and walks further for a hillside; a lazy, slothful one
  leaves a pillar or two and digs where it stands.
* **Houses in clearings**, not wedged between tree trunks under the leaves (it looks further out when everything close is
  forest). **A house it agreed to build stays its goal till it's built**, and inside its own half-built house it steps
  out to put its crafting table down (it used to get stuck there). A forest test house went from barely started to
  finished in 8 minutes.
* **Fixes.** Mostly see-through skins from mineskin.org's gallery are left out (Xens looked like black shadows in them).
  "My farm is planted!" is said once, not over and over.
* **Survival a little better** over 3-day test worlds (5 Xens each): 4 deaths on the usual seed (beta.29: 7 to 22), 24
  on a seed neither was tuned on (beta.29: 30).

### 2.0.0-beta.29: it plans what it was never shown, and reads biomes

* **Know-how and plans.** Xen knows how things are made and where they come from: the game's own crafting recipes,
  what a furnace makes of what, and a few hints a player picks up ("iron ore wants a stone pickaxe", "sand: beaches,
  deserts, rivers"). Give it a goal (`/xen plan bucket`, or "make me a shield" in chat) or let it pick its own (an iron
  pickaxe, a shield, a bucket...) and it works back from what it has to a plan, for example: "1. chop a tree, 2. craft
  planks, ... 8. mine iron ore with a stone pickaxe, 9. craft a furnace, 10. smelt 3 raw iron, 11. craft a bucket".
  Nothing about buckets was written for it.
* **Advice, not orders.** The plan's next step weighs in with its other choices, and its mind decides. The plan is
  worked out again whenever what it has changes, so a skipped step, a find or a failed way just change it. What each
  kind of step costs it is learned and shared across every plan (iron mined for a bucket counts toward a shield), it
  takes the cheaper of two ways (coal or planks to burn), and a step that keeps coming to nothing it pushes less.
  `/xen plan` shows the plan.
* **It reads biomes.** Where its sight lines end it notes the biome and remembers where it saw each one, and what each
  tends to have (a desert or beach: sand; a swamp: clay; badlands: gold; the mountains: emeralds and iron). A plan that
  wants sand it hasn't seen goes to the beach it saw. In lush caves, dripstone caves or the deep dark it knows it's in a
  cave at once, and a river biome helps it name a river. `/xen g1x` shows the biomes.
* **Survival about the same as beta.28** over paired 3-day test worlds (5 Xens each): 30 and 28 deaths on a seed
  neither was tuned on, 7 to 22 on the usual one for both. Falls are the most common death on hilly ground; that's
  next.

### 2.0.0-beta.28: survival tips it weighs for itself, fewer deaths

* **Tips, not rules.** Xen gets survival tips the way a new player does: "can't win this fight: get out of reach (two
  blocks up) or away, heal, then come back", "night's here and you're out working: a roof (or a hole) before it gets
  worse", "hurt and out in the dark: a roof first, heal, then go on", "no sword and it's night: let your things wait
  till morning". When one comes to mind it weighs it (how far it trusts that tip, how bad things look, its nerve) and
  takes it or goes its own way. Then it sees what happened and trusts the tip more or less ("went its own way and died:
  should've listened"). Each Xen ends up with its own idea of which tips are worth it. `/xen tips` shows it.
* **It knows what a fight will cost.** It learns what beating each kind of mob costs it ("a zombie costs it about 4
  health to beat now"), for its sword and armor (no sword: a lot more), and holds that against its health and the
  others coming at it.
* **Better ways out.** Taking the tip, it pillars two blocks up out of a zombie's reach and hits down at it, heals and
  comes down; from a skeleton it ducks out of its sight (behind a tree, a hill) instead of running in the open; and it
  runs away from all of them at once instead of zig-zagging between them. Its gut feeling in a fight goes into that
  call instead of a step back on the spot that only got it hit again.
* **Fewer deaths.** Two 3-day test worlds, 5 Xens each: 15 to 8 deaths and 28 to 20 (43 to 28 in all; skeletons 10 to
  5, zombies 19 to 8), with more iron found.

### 2.0.0-beta.27: Xen talks like a player (its own trained talk model), decides for itself, G1X vision

* **It talks like a player.** Xen has its own talk model now, trained on how players really type: "wsg", "wyd",
  "u got iron??", "bro i just died to a creeper", "LMAOOO", "whats ur fav block", typos and stretched letters, with
  its name or without. It knows about 90 kinds of lines and answers from a library of about 800 player-style lines, in
  its own tone, with only what's true of it ("no iron yet, just 4 wood lol", "getting wood rn", "rip. creepers have no
  chill", "copper for sure. yours?" and then it reacts to your answer). It learns which answers land (a laugh after
  one, it says it more), and a chat goes on as long as it's fun instead of ending after four lines. "im gonna go to
  sleep" isn't an order to sleep any more, and "wanna go mining later" is an idea, not a request it refuses.
  "Where are you?" gets its real coordinates. On lines it never trained on it reads the kind right about 9 times in 10.
  What it says on its own comes from the same library and is said like a player, less often: "finally a stone
  pickaxe", "getting stone, kinda boring ngl", "anyone wanna mine later?", "bruh. i died to a skeleton lol" (it no
  longer announces every stick and plank it makes).
* **The optional AI chat model is gone** (SmolLM2, its 145-390 MB download, the GPU option and its settings): it was
  buggy and slow, and the new talk model is in the jar (2 MB) and answers at once. No more -with-chat jars.
* **It backs off from mobs sooner** when it's outnumbered or in the dark (most of its deaths were two or three mobs at
  night, at 2 to 6 health, while it went on chopping or picking things up).
* **Xen decides.** Before, a hand-written plan made most of its choices (79 of 115 in one game) and its learned mind
  only filled the gaps. Now every choice is its own, weighed from three things: what its mind makes of each choice, the
  usual plan (as advice: how much it listens is learned from how the plan has worked out for it), and what happened the
  last times it chose that in a spot like this one.
* **It remembers how things went** (outcome memory): every choice it finishes is kept with the situation, what it got,
  what it cost, a death. "Mined at night here, died twice": next time it says "I'd usually go mining now, but the last
  times like this didn't end well. I'll make a shelter for the night instead." Sitting about while healthy counts as time
  lost, so it doesn't rest all day. Kept with the world (400 cases a Xen). A /kill isn't a lesson.
* **It predicts, then reflects.** Before it starts something it says what it expects: how likely it goes well, how
  risky, how long. When it's over it holds that against what happened. Way off, it works out why, in words: what changed
  while it was at it ("Mining went worse than I thought: night came while I was at it. I didn't think about it getting
  dark. I will next time.") and what it hadn't weighed, and from then on that counts for more for that choice,
  everywhere (not one cave: "mining at dusk goes badly" carries to every mine). How far off its guesses are is kept, so
  you can see it get better. `/xen predict` shows each choice it could make now with its real numbers, the one it
  would pick, what it has learned to weigh, and how good its guesses have been.
* **It learns its own moves.** Before a gap it weighs jumping, bridging and walking round by how those have gone for
  it: a jump it keeps missing costs it more (it bridges or goes round), a gap it misses more often than not it stops
  trying, and the longest jump it always makes, it dares one longer. Kept with the world.
* **Its own time to think.** How long it takes to decide is its own (not a setting any more): quick in danger, when it
  knows the spot, sure of itself or hot-headed; longer when the choice is close, the place is new, or it's careful,
  rattled or lazy. While it thinks it looks around. Reaction time and confusion are its own too (the settings are gone).
* **Goals as long as they take.** No more "six minutes and stop": mining, gathering, a village visit, exploring go on
  while they get somewhere (more of what it's after, new ground, new places seen) and stop when nothing comes of them
  for a while (its own patience: two to four minutes).
* **G1X, geometry vision.** What its eyes see is kept as shape, not just block kinds: how far each sight line went and
  what stopped it (floor, wall, ceiling, water, lava, leaves), and which blocks it has seen open or solid. From that it
  knows how far it can walk each way, where ledges are (and when it can't see the bottom of one), water and lava round
  it, a roof over it, what's in the way, whether a cave joins up with where it has been, and how much of each way it
  hasn't seen; with nothing to do it looks the way it knows least about. The place's shape goes into its outcome
  memory (a river ahead, a cliff, a roof), and a curious one goes to look at what it hasn't seen. /xen g1x shows it.
* **Chat that says what it means.** It suggests things from what it actually knows ("I saw a village about 140 blocks
  northeast earlier. Want to check it out?", "I haven't seen what's west of here yet", "last time I went mining at night
  I died. Not doing that again"); "why are you doing that?" gets the real reason for its choice; and it never says the
  same thing twice in a few minutes (asked again, "like I said, ...").
* **The journal keeps each death's last moments** (the 40 lines before it, by that Xen, and every hit it took: by what,
  its health, day or night, the light, what it was doing), so a death can be read back after the journal rolls over.
* Fixes: standing where its own hut's wall goes, it steps inside (it used to say "lazy2005 is in the way" about itself).
* Tested: the same world (seed 20261009), 5 Xens, normal difficulty, to day 3, each build alone on its server:
  beta.26.1 had 22 deaths (5 of them falls) and no iron pickaxe; beta.27 had 15 deaths (no falls) and an iron pickaxe
  (an earlier beta.27 build: 8 deaths and an iron pickaxe). Talk: a player saying 26 everyday lines next to a Xen
  (hello, wsg, wyd, u got iron?, died to a creeper, wanna go mining later, ur bad lol, fav block, are you a bot, a joke,
  gg, gn...) got a fitting answer to every one; the Java talk model reads all 64 test lines exactly as the trainer does.

### 2.0.0-beta.26.1 (hotfix): a shield first

* **The first iron after the pickaxe goes into a shield** (one ingot and six planks), before a sword or any armor: it
  stops nearly everything early on (arrows, creepers, zombies). Before, it came after the iron sword and the armor.

### 2.0.0-beta.26: villages and ruined portals used, iron golems, boxed villagers, a quicker start

* **Villages it finds, it uses:** it goes in (by day, within 192 blocks, once each, four minutes at most) and gets what
  a village is good for: its chests (it walks past the houses and loots what it sees), a bed off a villager if it has
  none, hay bales (wheat, then bread), the iron golem's iron (the speedrunner's way: up three blocks by it, where it
  can't reach, and hit it from above; only with a weapon, blocks and its health up), and a villager boxed in where it
  stands (feet, three sides at its head, a roof: it can't get out and can still be traded with).
* **Ruined portals:** it goes and loots the chest.
* **A quicker start:** no hunting cows "for later" before it has stone tools, and with no stone close by it digs down
  where it stands (stone is a few blocks under) instead of wandering off to look for some.
* Pickaxes crafted are logged with how long into its life (game seconds), to measure the start.
* Built and checked on 1.21.11 and 26.1 to 26.3. The village visits are new and not yet tested in a long game.

### 2.0.0-beta.25.1 (hotfix): Xens see further

* **Twice as far:** a Xen's eyes now reach 96 blocks (48 before): trees, water, lava, animals, houses and villages that
  far off, so it finds what it needs sooner and wanders less. Ore it makes out to 64 blocks (past that, a player sees
  stone: the speckles don't show). Still only what a player would see: nothing through rock, little in the dark.
* `/xen progress`: one line per Xen (where, health, pickaxe, iron, diamonds, obsidian, blaze rods, pearls, eyes of ender,
  its adventure's stage) for watching a long game.

### 2.0.0-beta.25: no more reaching through walls, beds it understands, and working up to diamonds

From the three new recordings and the journal:

* **No reaching through walls:** a Xen could mine a block behind another one (in one recording, iron ore 5.4 blocks
  off through stone) and click a chest, furnace, table or bed it couldn't see. Now it only digs and clicks what it can
  see, within reach, every moment of it: if it moves, or something comes between, it stops and finds a way round (rock
  in the way it digs first, as a player does). A crafting table behind a wall isn't "nearby" any more.
* **Beds it understands:** when a bed doesn't work, a player reads the line over the hotbar. Now a Xen does too
  ("monsters nearby", "you can only sleep at night", "this bed is obstructed", "occupied", "too far away") and acts on it
  (waits, says why, puts its camp bed somewhere else) instead of clicking it over and over. It stands where the game lets
  it sleep, never puts a bed down outside the Overworld (they blow up there), never digs a bed out while building, and
  gives up packing its camp bed if it can't get to it.
* **Diamonds it can't mine yet: it works up to them.** Before, it said "I need an iron pickaxe, I'll be back" and
  never did anything about it. Now, with diamonds close by, it works its way up on the spot: stone for a stone pickaxe,
  iron from round about, a furnace and smelting, the iron pickaxe, then the diamonds. Tested: a stone pickaxe and iron
  ore in the wall, then mined the iron, smelted it, made the iron pickaxe and mined the diamonds.
* **It smelts its iron:** one Xen carried 7 raw iron and 24 coal for 10 minutes and never smelted, because it had 7
  cobblestone, not 8. With a pickaxe it now gets the stone for a furnace first. Mining for iron with stone tools, it
  stops at 3 to smelt them for the pickaxe.
* **It eats to heal:** two Xens spent 10 minutes on 2 to 5 health with food at 11 to 15 (under 18, nothing heals), one
  with mutton on it. Now, two hearts down and under 18 food, it eats; hurt with nothing to eat, it drops its own errand
  and finds food.
* Less journal spam: "water coming in, can't block it" once, not twice a second.
* Tested: the obstacle course 8 of 8; down 25 real caves 23 there (as before). 26.1, 26.2 and 26.3 built and checked.

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

* The cheating talked about in the recorded chat was the friend's, not the recording player's. The recordings are from
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

### Older versions

1.9.3 and before: [dist/RELEASE_HISTORY.md](https://github.com/JAK-cool162/Xen/blob/main/dist/RELEASE_HISTORY.md), and each
version's own release page. Every change, in depth: `TECHNICAL.txt` below.

**Install**: Fabric Loader 0.16+ and Fabric API, then one of the jars in your mods folder. Mod Menu (optional) gives a
settings screen. In game: `/xen summon`. Phones: the 1.21.11 jar, 2 GB for Minecraft.
Full guide: [dist/README.md](https://github.com/JAK-cool162/Xen/blob/main/dist/README.md).
