# Xen Companion (Fabric mod), prototype 0.6.0-alpha

Xen as a survival companion: a player that joins your world, learns, thinks,
feels fear and chats. Ask it for things in plain words ("Xen, get me some
wood", "build a NOT gate") and it does them, or tells you why it won't. It
makes its own tools, trades with villagers and bargains with you, has goals
of its own, and talks on its own and with other Xens. Every Xen has its own name,
skin and personality, and with evolution the ones that do well pass their
nature on. It plays fair: it only knows what it can sense, and it acts only
through a player's inputs.

| file | Minecraft | Java | chat model |
|---|---|---|---|
| `xen-companion-0.6.0-alpha+mc1.21.11-with-chat.jar` | 1.21.11 | 21 or newer | **inside** (all in one, about 400 MB) |
| `xen-companion-0.6.0-alpha+mc26.x-with-chat.jar` | 26.1, 26.2, 26.3 | 25 or newer | **inside** (all in one, about 400 MB) |
| `xen-companion-0.6.0-alpha+mc1.21.11.jar` | 1.21.11 | 21 or newer | downloads when needed (7 MB jar; best for phones) |
| `xen-companion-0.6.0-alpha+mc26.x.jar` | 26.1, 26.2, 26.3 | 25 or newer | downloads when needed (7 MB jar) |

Use **one** of them. The **with-chat** jars are all in one: the mod, its brain
and its chat model (SmolLM2-360M), so Xen talks without downloading anything.
Get them from the GitHub **Releases** page (they're too big for this folder,
which has the light jars). This is a prototype, so expect rough edges.

**Every device**: the mod is plain Java with no native code, so the same jar
runs on x86-64 PCs and ARM64 (phones, Raspberry Pi, Apple Silicon Macs). The
chat model is plain Java too. Every change is checked on both an x86-64 and an
ARM64 machine (GitHub Actions: the tests, both builds, and the chat model).

## Requirements

* **Fabric Loader** 0.16 or newer (tested with 0.19.5): <https://fabricmc.net/use/>
* **Fabric API** for your Minecraft version (tested: 0.141.6+1.21.11,
  0.155.3+26.1.2, 0.161.0+26.3): <https://modrinth.com/mod/fabric-api>
* **Mod Menu** (optional) for the settings screen: <https://modrinth.com/mod/modmenu>
* **Java**: 21 for Minecraft 1.21.11, 25 for 26.x.
* **Memory**: 2 GB is enough for Xen itself. For the chat model, give the game
  3 GB or more; it takes about 500 MB. With less, Xen still chats and
  understands requests, only more simply.
* **Chat model**: `smollm2-360m-instruct-q8_0.gguf`, about 390 MB. The
  with-chat jar has it inside: the first time it's needed, the mod unpacks it
  once into `config/xen/` (it's read from a file there, which saves memory).
  With the light jar it downloads by itself into `config/xen/`, or take it from
  the release page and put it there yourself. Either way the mod checks the
  file's SHA-256 and never loads a damaged copy. It takes a minute or two to
  warm up; until then Xen answers in plain words.

Players don't need the mod on a server: it runs on the server, and vanilla
clients can join and play with Xen. For single player, put it (with Fabric API)
in your `mods` folder and the built-in server runs it.

## Install

1. Install Fabric Loader for your Minecraft version.
2. Put `fabric-api-*.jar` and the matching `xen-companion-*.jar` in `mods/`
   (and Mod Menu if you want the settings screen).
3. Start the game. Make a world and type `/xen summon`.

## On a phone (Android)

Android launchers run the Java Edition with Fabric. PojavLauncher itself is no
longer updated; use its successors **Zalith Launcher 2** or **Amethyst**. Use
the **1.21.11** jar, which needs Java 21 (these launchers include it).

**Zalith Launcher 2:**

1. Install a new version: Minecraft **1.21.11** with **Fabric** (the launcher
   has a Fabric installer built in).
2. Open that version's **Mods** page, tap **Add mod** and pick
   `fabric-api-...jar`, then `xen-companion-0.6.0-alpha+mc1.21.11.jar` (and Mod
   Menu if you like).
3. In the settings, give Minecraft as much memory as your phone allows (2 GB
   is fine; 3 GB or more if you want the chat model).
4. Play, make a world with cheats on if you want `/xen spawn`, and type
   `/xen summon`.

**PojavLauncher / Amethyst:** in the launcher, **Options → Launch mod installer**,
pick the Fabric installer jar (from fabricmc.net) and enter 1.21.11. Then copy
Fabric API and the Xen jar into the game folder's `mods` folder (in
PojavLauncher that's `games/PojavLauncher/.minecraft/mods` or
`/sdcard/games/.minecraft/mods`, depending on the version; turn on hidden
folders in your file manager). Pick the new Fabric version at the bottom of the
version list.

**On a phone:**

* Use the **light** jar, not `-with-chat`: phones usually give Minecraft less
  than 3 GB, so the chat model stays off anyway. Xen still understands requests
  by their keywords and answers in plain words.
* Slow phone? In Mod Menu (or `config/xen.json`) set **Decisions** to
  "2 a second" and turn **Learning** off.
* It was tested on PC servers, in a real (virtual) game client, and in the Java
  code on ARM64 machines, but not on a phone yet.

## How fast is it?

Measured on a 4-core cloud PC (x86-64):

* **Its brain**: a decision takes about 0.3 ms. A Xen decides 4 times a second
  (every tick in a fight). Learning (about 60 ms a step, one step every 4
  decisions) runs on its own thread, not the game's. With 8 Xens, a sped-up
  server still ran at 110-210 ticks per second (normal speed is 20). With
  **100 Xens** (`/xen spawn 100`), a tick took about 25 ms on average (the
  limit is 50), with a catch-up pause of several seconds about every 25
  seconds; 0.4.1 measured the same, so the new goals, trading, talking and
  watching cost little (under 3% of the server's time).
* **The chat model** (SmolLM2-360M, 8-bit, 360 million parameters):
  * loading takes 1-4 seconds; then it reads its two fixed prompts once (242
    and 367 tokens) at about 4, 7 or 9 tokens a second with 1, 2 or 3 threads,
    so it's ready after 1-2 minutes (until then Xen answers in plain words);
  * an answer reads about 50 new tokens (its notes and your message) and
    writes up to 40: 8 seconds with 3 threads, 10 with 2, 16 with 1;
  * `chatThreads` sets its threads (default: half your cores, 1-4). Its
    vocabulary is 49,152 tokens; the mod gives it a 1024-token window (the
    model can take 8192).
* **Phones** weren't measured. Their cores are slower: the brain is light
  enough, and the chat model stays off below about 3 GB anyway.

### The chat model on the graphics card

In single player (and on a LAN world you host) the chat model can run on your
graphics card: setting **Chat on GPU** (`gpu`), in the Talk tab. `auto`
(the default) uses it when there's a real graphics card with OpenGL 3.3;
`on` uses it even with a software renderer; `off` never does.

* **How**: Xen opens its own small hidden OpenGL 3.3 context, puts the model's
  weights there once (about 390 MB of graphics memory), and does the big
  matrix products with ordinary shaders (no compute shaders, so Macs and older
  cards work too). It reads the prompt 32 words at a time. The game's own
  rendering isn't touched, so it works the same with **vanilla, Sodium, Iris or
  a Vulkan renderer mod**: all it needs is a graphics driver with OpenGL 3.3
  (almost every PC and Mac). It uses the game's window library (GLFW up to
  26.2, SDL in 26.3).
* **When it can't** (no OpenGL 3.3, only a software renderer, not enough
  graphics memory, a dedicated server, or anything else going wrong), it says
  why in the log and the model stays on the CPU. `/xen settings` shows where
  it runs.
* **How fast**: measured here only on Mesa's software renderer (no graphics
  card in the test machine): reading a 285-token prompt took 39-42 s on it
  against 73-79 s on the CPU path, so a real graphics card should be a lot
  faster. Both give the same next word; the numbers differ a little
  (at most 0.53 of about 19) because the CPU path rounds its inputs to 8 bits.
  Checked in real 1.21.11 and 26.1.2 clients. The 26.3 path (SDL) couldn't be
  tried here: 26.3 itself wouldn't open a window on the test machine.
* **Phones**: PojavLauncher's usual renderer (GL4ES) has no OpenGL 3.3, so it
  stays on the CPU; a renderer with OpenGL 3.3 or newer (on Zink/Vulkan) may
  work, but it's untested.

## Talking to Xen: it understands and does it

Say its name in chat. Only its owner can give it orders (anyone can, for the
ownerless Xens from `/xen spawn`). Anyone can chat with it.

| you say (any words like these) | Xen |
|---|---|
| "Pip, follow me" / "come here" / "let's go" (typos like "fallow me" work, and Thai: "ตามมา") | follows you (to about 4 blocks), and does things of its own nearby while you're close |
| "Pip, stay" / "wait here" | stays around here |
| "Pip, go explore" / "do your thing" | lives its own life |
| "Pip, get me 5 logs" / "chop some trees" | walks to a tree it knows, chops, climbs a block if it must |
| "Pip, get stone" / "find coal" / "find iron" / "go mining" | the same for stone, coal, iron or any ore |
| "Pip, kill a pig" / "get us food" | hunts an animal it sees and picks up the meat |
| "Pip, give me your wood" / "hand over 12 cobblestone" | walks over and tosses it to you (keeps its tools) |
| "Pip, craft a boat" / "make me 4 torches" / "craft a chest" / "make me a stone pickaxe" | crafts it with the recipe book (planks and sticks first, a crafting table if needed), or says what's missing |
| "Pip, build a shelter" / "hide!" | builds a little hut around itself (10 blocks) and stays in it until morning |
| "Pip, build a NOT gate" / "an OR gate" / "an AND gate" / "a long wire" | builds that redstone circuit from parts it carries |
| "Pip, eat something" | eats, if it has food and is hungry |
| "Pip, stop" / "cancel that" | stops what it's doing |
| "Pip, trade with the villager" | walks up to a villager, opens its trades and takes the good ones ([trading](#trading-villagers-and-you)) |
| "Pip, how much for your logs?" / "I'll give you 2 iron for 16 logs" | names a price, bargains, and trades with you |
| "Pip, what are you doing?" / "how are you?" / "what's your dream?" / "what do you have?" / "who are you?" | answers straight from what it knows (no chat model, so nothing made up) |
| "Pip, what do you see?" / "thanks!" | just talks |
| "yes" / "no" (no name needed) | answers a question it just asked you, or its trade offer |
| "Pip, watch this!" / "watch me" / "copy me" | keeps its eyes on you for a minute, to [learn from you](#learning-by-watching) |

How it understands: clear keywords decide first. When there are none ("go see
what's out there"), the chat model picks one of the things above, and only if
it's clearly more likely than just talking. Questions and thanks are always
just talk. `/xen status` shows what it's doing right now.

It does it fairly:

* It only goes for blocks and animals it knows about: felt within 6 blocks,
  spotted within 14 (a trunk between the trees, ore showing in a cliff: only
  what shows a face to the air), or seen further away in its 90° view. When it
  knows of none, it looks around and walks somewhere new. Huge mushrooms aren't
  trees to it (their stems give no wood).
* It mines what it can see and reach, like a player: from the side, diagonally,
  up the trunk, clearing leaves in the way. Then it picks up what dropped.
* It walks there itself, finding a way through the blocks it knows (up steps,
  down drops, around obstacles and lava), and digs only when there's no way.
  With good health it drops down 4 or 5 blocks (a little fall damage, like a
  player); when it's hurt, 3 at most. In a hole it digs a staircase out.
* It mines with the real break time and the best tool it has, places only
  blocks and parts it carries, hits with the normal reach and cooldown, and
  gives items by tossing them.
* It answers honestly. For a request it says what it will do, or exactly why
  it can't ("I need 10 dirt or cobblestone and have 3", "the ground isn't
  flat", "I need 2 more redstone"). In conversation, anything it claims must
  be in its own notes. It can't promise things, and it never writes commands.
* It needs the right tools, like you: stone and coal need a pickaxe, iron a
  stone one. Ask for stone without one and it gets wood first, makes the
  pickaxe, then gets the stone.

## Tools: it crafts like a new player

Xen makes its own tools the way a new player does: logs into planks, planks
into sticks and a crafting table, then a wooden pickaxe (three logs are
enough), and with cobblestone a stone pickaxe, a stone sword and a stone axe.
It crafts with the recipe book, like you: it clicks the recipe (which puts the
ingredients in the grid), shift-clicks the result, and puts back what's left.
Small things in its own 2x2 grid; tools at a crafting table it places next to
itself (or one that's already there), opened with a right-click. One step at a
time, so it takes a few seconds. It can't smelt yet, so iron tools are out of
reach for now.

## It acts like a player, not a digging machine

* **It only mines what's worth it**: wood, ore its pickaxe can mine, stone when
  it needs blocks. It never digs straight down under itself (it digs a
  staircase instead, and stops if lava or water is under the next step).
* **Next to you, it doesn't just stand there**: while you're within about 12
  blocks it gets on with what it needs (wood, stone, food, ore, a shelter at
  night: "I'll grab some wood while we're here.") and drops it to keep up when
  you're more than 24 blocks away ("Coming!"). Otherwise it watches you or
  looks around. Only a free Xen goes exploring on its own.
* **It swings only at something hostile in front of it**, never at the air.
* **It knows how mobs behave**: endermen, piglins, wolves, bees and the like
  leave you alone unless you provoke them, so it doesn't; spiders are calm in
  daylight; a mob that's after it or its owner is fought. It never hits
  villagers, golems or pets, and hunts only cows, pigs, sheep, chickens and
  rabbits (never a named one or one on a lead).
* **Creepers**: when one hisses close by, it turns and sprints away (about 4
  blocks in the 1.5 seconds before the blast, in tests). After hitting one, it
  always steps back.

## Learning by watching

Xen watches the players it can see and copies moves that **work out** for
them, clumsily at first and better each time it sees one done well (and each
time it pulls it off itself). Say "Pip, watch this!" first, so it keeps its
eyes on you.

* **The water clutch (MLG).** Jump from high up with a water bucket and empty
  it just before you land. If you land in the water unhurt, Xen saw it work:
  "Whoa, Steve, a water clutch! I want to learn that." From then on, when it
  falls with a water bucket, it looks down and uses it before it hits the
  ground, then scoops the water back up. How late it starts clicking depends
  on its skill, so at first it's often too late ("Too late! I'll get it next
  time.").
* **Your fighting style.** When you win a fight in front of it, how you fought
  (crits on the way down, full-charge swings, S-taps, jump resets, spacing,
  the shield) pulls its [fight genes](#teams-and-pvp) your way, more for a
  clean win: "Nice fight, Steve! I'll crit more, on the way down, like you."

It learns only from what it could see you do (your moves, what you hold, a mob
flinching), never from anything hidden, and it keeps what it learned.
`/xen status` shows it. Setting: **Learns by watching** (`copy`).

## Trading: villagers and you

**Villagers.** "Pip, trade with the villager" (or on its own, when it wants
to): it walks up, right-clicks the villager, reads the offers on the trading
screen, and takes the ones that are good for it, with the same clicks a
player makes. It knows about what things are worth (an emerald is about 15
coal or 5 bread) and what they're worth to *it* right now (food when it's
hungry, a tool it lacks, what its dream needs, less for what it has plenty
of). From a test on a real server:

```
<Steve> Clover, go trade with the villager
<Clover> Okay! I'll trade with the farmer 2 blocks away.
<Clover> I traded with the farmer: gave 30 coal and got 6 bread, 1 emerald.
```

**You.** Make it an offer, ask its price, or say yes or no to its offer. It
takes a fair deal, answers a poor one with a counter-offer (a bit high first,
then halfway, then its last offer), walks away from a bad one, gives friends a
better price, and never trades away what it needs. With someone it trusts, it
hands over its part first; otherwise it waits for yours (toss it over).

```
<Steve> Clover, how much for your logs?
<Clover> I'd trade 16 logs for 3 emeralds. Deal?
<Steve> Clover, i'll give you 2 iron for 16 logs
<Clover> Let's meet halfway: 16 logs for 6 iron?
<Steve> ok deal
<Clover> Deal! I trust you, so here are the 16 logs first. Toss me the 6 iron.
<Clover> Here you go!
<Clover> Thanks! Nice doing business with you.
```

**Trust.** Every Xen remembers how much it trusts each player and Xen it has
met: kind words, fair deals and chats make it trust them more; hitting it,
or taking its part of a deal and never paying, makes it trust them less. It
won't trade with someone who hurt it. Setting: **Trading** (`trading`).

## Saying no

Xen can refuse, and it says why:

* someone who hurt it: "No, I won't, because Alex hurt me." (no matter what;
  an ownerless Xen takes requests from anyone, an owned one only from its
  owner, and nobody who hurt it gets a trade);
* badly hurt: "No, not now: I'm badly hurt and need to heal first.";
* a timid Xen asked to explore in the dark: "No, I won't go exploring now,
  because it's dark and I'm scared.";
* asked for what it needs: its food when it's hungry, the blocks for its home,
  its emeralds when it dreams of trading ("No, I won't give my cobblestone
  away, because I need them for my home.");
* an ownerless Xen asked for its things by a stranger.

Saying **please** (or "I insist") changes its mind, except when you hurt it.
Only its owner can give it orders at all. Setting: **Can say no** (`refuse`).

## Redstone (small circuits)

Xen builds the circuits it worked out itself in its redstone lessons
(`xen/redstone`): a NOT gate (5 parts), an OR gate (7), an AND gate (22) and a
long wire with repeaters (20). It needs the parts in its bag (redstone dust,
redstone torches, repeaters, levers, lamps, and dirt or cobblestone for the
blocks), and flat, clear ground in front of it. It places every part by hand,
facing the right way, and sets the repeaters' delays. Tested in the game: the
NOT gate lights the lamp with the lever off and turns it off with the lever
on. **Biggest circuit** (24 parts by default) keeps anything big enough to
slow a server away. Turn **Redstone** off to disable it.

## Names, personalities and skins

* **Names**: new Xens get names that fit their nature, in the **Name style**
  (`nameStyle`) you like: `fun` (a silly Xen may be WobblyNoodle or LilPickle,
  a bold one IronComet, a grumpy one SaltyBadger), `gamer` (Pickle_42,
  xXWaffleXx, TheSneakyGoose), `fantasy` (Zorbax, Lumika), `classic` (Pip,
  Nova, Bramble) or `mixed` (all of them). Xen, Xen2... with **Random names**
  off. `/xen summon <name>` picks the name, and
  summoning the same name again brings back the same Xen with its nature,
  skin and things.
* **Personality**: every Xen is braver or more timid (how much fear holds it
  back), more or less curious (how often it tries new things), chatty or quiet,
  patient or impatient (how long it keeps at a chore), and has a tone of voice
  (cheerful, calm, grumpy, shy, bold, silly) that shows in what it says.
  `/xen status` and the summon message tell you who it is: "Rune (shy, timid
  and impatient; a skirmisher (hits and steps back), builds stone forts)".
* **Fighting**: every Xen has ten fight genes, the 1.9+ PvP skills a player
  learns (see [PvP](#teams-and-pvp)). They start from one of five styles and
  are inherited:
  * `brawler`: trades blows and jumps for critical hits a lot;
  * `rusher`: rushes in, keeps pressing, and jumps for criticals most;
  * `skirmisher`: hits, then steps back while its sword recharges, and backs
    off to heal when badly hurt;
  * `guard`: holds its ground behind its shield (give it one), waits for
    full-power swings and lets the foe swing first;
  * `dancer`: circles around its foe between swings.
* **Building style** (random, and inherited): the shelter it builds when you
  ask, a `hut` (2-high walls and a roof, 10 blocks), a `fort` (corners filled
  in, 18 blocks) or a `tower` (3-high walls, 14 blocks), in its favorite
  material: `stone` (cobblestone), `earth` (dirt) or `any`. With too few blocks
  for its style, it builds a hut.
* **Set them yourself**: `/xen style Pip fight guard` (also `build`,
  `material`, `tone`; `bravery`, `curiosity`, `chattiness`, `diligence` from 0
  to 1; and each fight gene: `crit`, `charge`, `spacing`, `wtap`,
  `jumpreset`, `strafe`, `counter`, `select`, `retreat`, `shield`). Its owner
  or an operator can do it, and it's saved with the world.
* **Skins** (`skins`, any mix of these):
  * `random` (the default): the mod's own 61 skins and Minecraft's 18;
  * `pack`: only the mod's 61 ([see them](../docs/skins/README.md); free to
    use, CC0). They're signed, so everyone sees them, with or without the mod;
  * `default`: Minecraft's 18 (Steve, Alex, Ari, Efe, Kai, Makena, Noor, Sunny,
    Zuri, in both arm widths);
  * `folder`: **your own skins**. Put PNG skin files in `config/xen/skins/`
    (download them from NameMC, Planet Minecraft, The Skindex, or draw your
    own). Each one is uploaded once to mineskin.org (unlisted), which signs it,
    and remembered in `config/xen/skins/signed.json`, so every player sees it;
  * `mineskin`: random skins from mineskin.org's public gallery (online);
  * `player:Name`: a Minecraft account's skin (`/xen set skins player:Dream`);
  * one skin by name (`steve`, `alex:slim`) or `texture:<value>:<signature>`.
* **Antics** (`antics`): Xens do unpredictable things for fun. Crouch up and
  down next to one and it dances along (Xens nearby join in); now and then it
  shows off a trick ("Watch this!": a sprint, a jump and a spin) that doesn't
  always work ("I meant to do that."); in a fight it may take a snack break in
  front of a foe that's nearly beaten, taunt it, or fake a retreat. Silly and
  cheerful Xens do it most, grumpy ones least; never in danger.

## Experimental: custom instructions and your own script

Both are in the settings screen's **Experimental** category (or `/xen set
instructions ...` and `/xen set script ...`).

**Custom instructions** tell Xens who they are and what they should know:

```
You love cats and hate the rain. You're scared of the dark.
Pip: you're a pirate and talk like one.
```

A line that starts with a Xen's name and a colon is only for that Xen. The chat
model reads them when it answers and talks. Without the chat model, Xen still
answers from them: "Pip, do you like cats?" "I love cats." "who are you?" ends
with "I'm a pirate and talk like one."

**Custom script**: your own rules, one per line, `when <something>: <what to do>`:

```
when night: do build a shelter
when morning: say Good morning, {player}!
when someone comes: wave
when hears hello: dance
when sees creeper: say RUN!
when hungry: say I'm starving!
when every 10 minutes: show off
# a note
```

| when | |
|---|---|
| `night`, `morning`, `rain` | it gets dark, light, or starts raining |
| `hungry`, `hurt`, `attacked`, `diamonds` | its hunger gets low, its health gets low, something hits it, it gets diamonds |
| `someone comes` | a player it knows comes within 12 blocks |
| `sees <a mob>` | it sees one (`sees creeper`, `sees cow`) |
| `hears <a word>` | someone within 16 blocks says it |
| `every <N> minutes` | now and then |

| do | |
|---|---|
| `say <words>` | says it (`{player}` is the player it's about, `{name}` its own name) |
| `do <a request>` | anything you could ask it (`do build a shelter`, `do get 5 wood`, `do follow me`), as if its owner asked, so it can still say no |
| `dance`, `spin`, `wave`, `show off` | antics |

Each rule runs at most once a minute. The script box shows which lines it
can't read. With `/xen set script`, put `|` between rules.

## Teams and PvP

* **Teams**: all Xens on one team, or split into 2-6 colored teams (red, blue,
  green, yellow, purple, aqua). Teammates can't hurt each other.
* **PvP**:
  * `off`: Xen fights only monsters.
  * `defend` (default): it also fights back against a player who hurts it or
    its owner. It never fights its owner or a teammate, and it draws its sword
    when an armed stranger comes close.
  * `teams`: Xens of different teams also fight each other.
* **How it fights**: like a 1.9+ player, with only a player's inputs, and by
  the server's own rules:
  * **critical hits**: it jumps and strikes on the way down, and lets go of
    sprint in the air (a crit doesn't count while sprinting);
  * **full-charge swings**: crits and extra knockback need the swing charged
    over 90%, so it times its swings;
  * **sprint hits and S-taps**: it sprints in for extra knockback, then steps
    back a moment so the next hit is a sprint hit again;
  * **jump resets**: it jumps toward a hit as it lands, for less knockback;
  * **spacing**: it keeps near the edge of its reach while its sword recharges;
  * **reading the foe**: it steps back when the foe jumps in for a crit, and
    can wait for the foe to swing first and punish it (hit selecting);
  * **shields**: it raises its shield while recharging, takes out its axe
    against a raised shield (an axe hit disables it) or circles around it;
  * **healing**: badly hurt, it backs off and eats a golden apple.
  It fights back at once when hit, even from behind (it knows everything
  within 6 blocks). How much of each it does is up to its fight genes.
* **How good is it?** Against a simple scripted fighter with the same iron
  sword that strikes first, a brawler Xen now wins about half its fights (6 of
  10) and a rusher 2 of 5, where the last version won none. A good player will
  still beat it: it can't combo or dodge arrows, and doesn't use a mace,
  spear, crystals or pearls yet.

## The PvP arena: red against blue

Xens can train their fighting against each other:

```
/xen arena start [xens per team] [generations] [kit]     (operators; e.g. /xen arena start 4 40 sword)
/tick sprint 1d                                          (optional: much faster)
/xen arena status
/xen arena stop
```

* It builds lanes high above you (barrier blocks with colored glass under the
  floor, so nothing spawns there and nothing gets broken). Red Xens `Red1`...
  and blue Xens `Blue1`... join, all with the same kit: iron armor, an iron
  sword and a golden apple (`shield` adds a shield, `axe` a shield and an axe).
* Every round each red Xen duels a random blue one in its own lane, until one
  falls (or 30 seconds). The teams swap ends every round, and nobody gets to act
  first.
* Every 3 rounds each team evolves: its worst quarter get the fight genes of
  children of its best (each gene from one of two parents, plus a small
  mutation). A team that wins under a quarter of its fights also learns from
  the enemy (one child gets a parent from the other team).
* Every generation is logged in `<world>/xen/arena.csv`. The best fighters'
  genes are kept in `arena_champions.json`, and **new Xens are born with
  them** (a little mutated). Arena Xens don't learn into the shared brain, so
  duels can't crowd out what it knows about lava and caves.

A 40-generation run (4 against 4, sword kit, about 8 minutes sprinted):
both teams ended up fighting the same way: always jump for critical hits,
swing as soon as the sword allows, fight near the edge of reach, S-tap and
jump-reset a lot, and never wait for the foe to swing or back off. Blue started
as circling dancers and lost 11 of 12 fights at generation 20; learning from
red, it caught up and was even (6-6) by generation 31.

How good is the champion it evolved? Against each of the five starting styles,
3 fights from each side (30 fights, arena kit), it won 15, lost 12 and drew 3.
It beat the careful styles (dancer 5-0, skirmisher 4-1, guard 3-2) but not the
aggressive ones (brawler 2-4, rusher 1-5). What the arena evolved is itself an
aggressive fighter, so self-play confirmed what works best with these ten
skills, but it hasn't yet found anything better than the best hand-made
style.

## Evolution

With **Evolution** on, every few days (default 3) the Xens without an owner are
ranked by how they did: what they gathered, how long they lived, how often
they died. The worst quarter leave, and children of the best half take their
places. Each gene (bravery, curiosity, chattiness, patience, tone) comes from
one of two parents, with a small mutation. All Xens keep sharing one brain
(what they learned). What evolves is their nature. Each generation is logged
in `<world>/xen/evolution.csv`. Your own Xens (with an owner) are never
replaced.

## Its goals: now, soon, and its dream

Every Xen has goals in three tiers (`/xen status` shows all three, and when you
chat with it, it knows them):

* **Instant** (seconds): staying alive and what it's doing this moment:
  swimming up for air, eating, fighting, getting away from a creeper, making a
  tool, keeping up with you.
* **Short** (minutes), when it's free (nobody to follow, nothing asked of it):
  find food, build a shelter for the night, get wood, get stone, look for ore,
  trade with a villager, or explore. It weighs what it needs right now (hungry
  with no food? dark with no roof? no pickaxe?), its personality (patient Xens
  like work, brave ones go for ore, curious ones explore), how well each goal
  has turned out for it before (it learns which it likes), and what its dream
  needs.
* **Long** (days): its dream, picked from its personality: build a home, a big
  stockpile of wood and stone, find diamonds, become a trader with 5
  emeralds, see places 300 blocks away, or make three friends. The dream
  steers its short goals (a Xen dreaming of a home gathers blocks, then builds
  a fort in daylight and remembers where it is). When a dream comes true, it's
  proud of it, says so, and picks a new one. Dreams are kept with the Xen.

Your requests always come first. Turn short goals off with **Own goals**
(`wants`).

## Talking on its own, and with other Xens

With **Talks on its own** (`talk`), a Xen says what's on its mind now and
then, and only what's true for it: that it's hungry, how far along its dream
is, that it needs wood for a pickaxe, that there's a villager. It greets people
it knows when they come near. And it asks things you can answer with a plain
**yes** or **no** in chat (no name needed): "It's dark. Should I build us a
shelter?", "I have lots of wood. Want some?", "Can I go exploring for a bit?",
"Want to trade?". Say yes and it does it.

With **Talks with Xens** (`talkToXens`), two Xens that meet have a short chat:
who they are, what they dream of, and tips about where they saw trees and ore,
which the other one then knows too (a little less sure than if it had seen it
itself). They trust each other a bit more after (that counts toward the
"three friends" dream):

```
<Clover> Hi! I'm Clover. Who are you?
<Bramble> I'm Bramble. Nice to meet you, Clover!
<Clover> I saw coal ore about 48 blocks north of here.
<Bramble> Thanks! I'll remember that.
<Clover> See you around!
```

How often it talks depends on how chatty it is (every minute or two for a
chatty one), with a limit for all Xens together so chat never floods. Only
players within 48 blocks hear it.

## The chat model only wakes when it's needed

The chat model loads only when someone a Xen knows (its owner, or anyone who
has talked to it) is within 32 blocks, or when someone talks to it. After 10
minutes with nobody around, it's unloaded again to free memory and CPU.
Meanwhile Xen talks in plain words. When nobody it knows is around to hear, it
leaves notes on signs instead (if it carries signs): "Day 12: Diamonds here!
-Pip", "Day 13: Careful, lava! -Pip", and a morning note saying what it's up to.

## Settings: Mod Menu or commands

![Xen Companion settings in Mod Menu](../docs/screenshots/settings.png)

With Mod Menu installed, open **Mods → Xen Companion → settings** (the screenshot
is from a real game client). The categories are down the left side (Talk, Xens,
Goals, PvP, Build, Speed, Experimental); hover a setting to read what it does,
and **Reset** puts the open category back to how it comes. Changes save to `config/xen.json` and apply right
away in single player. On a server, operators use:

* `/xen settings`: shows every setting;
* `/xen set <setting> <value>`: changes one (for example `/xen set teams 2`,
  `/xen set pvp teams`, `/xen set evolution true`, `/xen set skins alex,steve`).

| setting | default | meaning |
|---|---|---|
| `chat` | `true` | Xen answers and understands chat |
| `chatModel` | `"auto"` | the chat model: `"auto"` (with about 3 GB or more), `"on"` or `"off"` |
| `chatWakeDistance` | `32` | wake the chat model when someone Xen knows is this close |
| `chatIdleMinutes` | `10` | unload it after this long with nobody around |
| `downloadChatModel` | `true` | download the chat model the first time it's needed |
| `chatThreads` | half the CPU cores (1-4) | CPU threads for the chat model |
| `gpu` | `"auto"` | the chat model on the graphics card: `"auto"`, `"on"` or `"off"` ([more](#the-chat-model-on-the-graphics-card)) |
| `learn` | `true` | keep learning in the world |
| `maxPerPlayer` | `1` | Xens one player may summon (0 = no limit; operators have no limit) |
| `maxXens` | `0` | Xens the whole world may have (0 = no limit) |
| `randomNames` | `true` | names like Pip and Nova instead of Xen, Xen2... |
| `personalities` | `true` | each Xen has its own nature |
| `wants` | `true` | free Xens choose their own goals; following ones do things nearby on their own |
| `talk` | `true` | Xen talks on its own now and then (remarks, greetings, yes-or-no questions) |
| `talkToXens` | `true` | Xens that meet chat and share tips |
| `trading` | `true` | Xen trades with villagers and bargains with players |
| `refuse` | `true` | Xen may say no (and why) |
| `copy` | `true` | Xen copies moves that work out for players it watches (the water clutch, a winning fighting style) |
| `skins` | `["random"]` | `random`, `pack`, `default`, `folder`, `mineskin`, `player:Name`, skin names or textures ([more](#names-personalities-and-skins)) |
| `nameStyle` | `"mixed"` | `mixed`, `fun`, `gamer`, `fantasy` or `classic` |
| `antics` | `true` | dancing along, tricks, surprises in fights |
| `instructions` | `""` | [custom instructions](#experimental-custom-instructions-and-your-own-script) |
| `script` | `""` | [your own rules](#experimental-custom-instructions-and-your-own-script) |
| `teams` | `1` | 0 = none, 1 = one team, 2-6 = that many teams |
| `pvp` | `"defend"` | `"off"`, `"defend"` or `"teams"` |
| `evolution` | `false` | replace the worst ownerless Xens with children of the best |
| `generationDays` | `3` | Minecraft days per generation |
| `redstone` | `true` | Xen may build small circuits |
| `maxRedstoneParts` | `24` | the biggest circuit it will build |
| `signs` | `true` | leave notes on signs when nobody it knows is around |
| `decisionTicks` | `5` | ticks between decisions (5 = four a second; 10 or 20 for slow machines) |
| `followDistance` | `4` | follow mode: catch up when further than this |
| `leaveWithOwner` | `true` | Xen leaves when its owner leaves |
| `saveMinutes` | `5` | how often the brain is saved |

## Commands

| command | what it does |
|---|---|
| `/xen summon [name]` | Xen joins next to you (from the console: on the ground at world spawn, on its own) |
| `/xen spawn <count> [radius]` | operators: many ownerless Xens, scattered on the surface up to `radius` blocks away (default 300) |
| `/xen mode follow\|stay\|free` | the same as asking it to follow, stay or explore |
| `/xen status` | who it is, health, hunger, mood, what it knows and what it's doing |
| `/xen style <xen> <trait> <value>` | its owner or operators: set a trait by hand, e.g. `/xen style Pip fight skirmisher`, `/xen style Pip crit 0.9`, `/xen style Pip build tower`, `/xen style Pip bravery 0.9` |
| `/xen arena start [xens] [generations] [kit]`, `stop`, `status` | operators: [the PvP arena](#the-pvp-arena-red-against-blue) |
| `/xen chat on\|off`, `/xen learn on\|off` | quick switches |
| `/xen settings`, `/xen set <setting> <value>` | operators: all settings |
| `/xen save` | save the brain now (it also saves every 5 minutes and on shutdown) |
| `/xen dismiss` | it goes home (operators and the console send every Xen home) |

* **Its bag**: right-click your Xen to open its inventory.
* **Instincts**: it swims up in water, fights back against hostile monsters
  within reach (not neutral ones), runs from hissing creepers, makes its tools,
  and eats when it gets hungry.
* **Death**: it drops its items like a player, respawns at its bed or the
  world spawn, and remembers what hurt it.
* **Falling** hurts it like any player (before 0.4.0 it didn't count, which
  also meant its critical hits never landed).

## What it knows (it can't cheat)

* It knows every block **within 6 blocks** of it.
* Beyond that it only knows what it **sees**: its view is a 90° cone in front
  of it, up to **8 chunks (128 blocks)**. Walls, hills and the ground block the
  view. Glass, ice and water don't (leaves do), and unloaded chunks stay
  unknown.
* What it has seen becomes a **belief with a confidence** that fades when it
  looks away (blocks lose half their confidence in 2 minutes, mobs in 3
  seconds).
* It's a real server player: it shows in the player list, takes damage, gets
  hungry and can die.

## Its brain

The trained brain is inside the jar. It carries its latest 1000 trauma and joy
memories, so learning in a safe world doesn't make it forget what hurt it.
The world's brain is saved in `<world>/xen/brain.bin`, with `lives.csv` (every
life), `companions.json` (who each Xen is) and `evolution.csv`. Delete
`brain.bin` to start over from the pre-trained brain. The release also has
`xen-brain-30days-experimental.bin`, the same brain after 30 more days in real
Minecraft with evolution: it fears zombies much more, but it mines almost
anything (even down toward lava) and does worse on SimCraft's tests. Copy it
to `<world>/xen/brain.bin` if you want to experiment.

Troubleshooting: start the game with `-Dxen.debug=true` to log every decision.

## Build from source

```
cd mod/mc1.21.11     # or mod/mc26
./gradlew build      # needs JDK 25 to run Gradle; the jar lands in build/libs/
./gradlew crossCheck # the Java brain, senses, chat rules, paths and memories against the Python ones
./gradlew llmCheck -PchatModel=smollm2-360m-instruct-q8_0.gguf   # the chat model in Java against Python
./gradlew runClient -PuiTest   # a game client that opens Mod Menu and the settings by itself
```

The chat model is [SmolLM2-360M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct)
by Hugging Face, Apache-2.0 license.
