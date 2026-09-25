# Xen Companion (Fabric mod), prototype 0.4.0-alpha

Xen as a survival companion: a player that joins your world, learns, thinks,
feels fear and chats. Ask it for things in plain words ("Xen, get me some
wood", "build a NOT gate") and it does them. Every Xen has its own name,
skin and personality, and with evolution the ones that do well pass their
nature on. It plays fair: it only knows what it can sense, and it acts only
through a player's inputs.

| file | Minecraft | Java |
|---|---|---|
| `xen-companion-0.4.0-alpha+mc1.21.11.jar` | 1.21.11 | 21 or newer |
| `xen-companion-0.4.0-alpha+mc26.x.jar` | 26.1, 26.2, 26.3 | 25 or newer |

Get them from the GitHub **Releases** page (with the chat model and Xen's
brains as separate downloads) or from this folder. This is a prototype, so
expect rough edges.

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
* **Chat model** (optional): `smollm2-360m-instruct-q8_0.gguf`, about 390 MB. It
  downloads by itself into `config/xen/` the first time it's needed, or take it
  from the release page and put it in `config/xen/` yourself.

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
   `fabric-api-...jar`, then `xen-companion-0.4.0-alpha+mc1.21.11.jar` (and Mod
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

* The chat model normally stays off (phones give Minecraft less than 3 GB).
  Xen still understands requests by their keywords and answers in plain words.
* Slow phone? In Mod Menu (or `config/xen.json`) set **Decisions** to
  "2 a second" and turn **Learning** off.
* It was tested on PC servers, in a real (virtual) game client, and in the Java
  code on ARM64 machines, but not on a phone yet.

## Talking to Xen: it understands and does it

Say its name in chat. Only its owner can give it orders (anyone can, for the
ownerless Xens from `/xen spawn`). Anyone can chat with it.

| you say (any words like these) | Xen |
|---|---|
| "Pip, follow me" / "come here" / "let's go" | follows you |
| "Pip, stay" / "wait here" | stays around here |
| "Pip, go explore" / "do your thing" | lives its own life |
| "Pip, get me 5 logs" / "chop some trees" | walks to a tree it knows, chops, climbs a block if it must |
| "Pip, get stone" / "find coal" / "find iron" / "go mining" | the same for stone, coal, iron or any ore |
| "Pip, kill a pig" / "get us food" | hunts an animal it sees and picks up the meat |
| "Pip, give me your wood" / "hand over 12 cobblestone" | walks over and tosses it to you (keeps its tools) |
| "Pip, build a shelter" / "hide!" | builds a little hut around itself (10 blocks) and stays in it until morning |
| "Pip, build a NOT gate" / "an OR gate" / "an AND gate" / "a long wire" | builds that redstone circuit from parts it carries |
| "Pip, eat something" | eats, if it has food and is hungry |
| "Pip, stop" / "cancel that" | stops what it's doing |
| "Pip, what do you see?" / "thanks!" | just talks |

How it understands: clear keywords decide first. When there are none ("go see
what's out there"), the chat model picks one of the things above, and only if
it's clearly more likely than just talking. Questions and thanks are always
just talk. `/xen status` shows what it's doing right now.

It does it fairly:

* It only goes for blocks and animals it knows about: felt within 6 blocks, or
  seen in its 90° view. When it knows of none, it looks around and walks
  somewhere new.
* It walks there itself, finding a way through the blocks it knows (up steps,
  down drops, around obstacles and lava), and digs only when there's no way.
* It mines with the real break time and the best tool it has, places only
  blocks and parts it carries, hits with the normal reach and cooldown, and
  gives items by tossing them.
* It answers honestly. For a request it says what it will do, or exactly why
  it can't ("I need 10 dirt or cobblestone and have 3", "the ground isn't
  flat", "I need 2 more redstone"). In conversation, anything it claims must
  be in its own notes. It can't promise things, and it never writes commands.

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

* **Names**: new Xens get names like Pip, Nova, Bramble or Waffle (or Xen,
  Xen2... with **Random names** off). `/xen summon <name>` picks the name, and
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
* **Skins**: built-in skins are Minecraft's own 18 default skins (Steve, Alex,
  Ari, Efe, Kai, Makena, Noor, Sunny, Zuri, in both arm widths), so there's
  nothing to download and every game can show them. "random" picks one per Xen.
  Custom skins: make one on mineskin.org and add
  `"texture:<value>:<signature>"` to `skins` in `config/xen.json`.

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

## Its own goals

A Xen that's free (nobody to follow, nothing asked of it) chooses what it wants
to do, every 20 seconds or so: find food, build a shelter for the night, get
wood, get stone, look for ore, or just explore. It weighs three things: what it
needs right now (hungry with no food? dark with no roof? few blocks?), its
personality (patient Xens like work, brave ones go for ore, curious ones
explore), and how well each goal has turned out for it before. It says what it
wants ("I want to get some wood."), does it with the same chores you can ask
for, and learns: goals that pay off become ones it likes. `/xen status` shows
what it wants and likes, and when you chat with it, it knows its own goal.
Your requests always come first. Turn it off with **Own goals** (`wants`).

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
is from a real game client). Changes save to `config/xen.json` and apply right
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
| `learn` | `true` | keep learning in the world |
| `maxPerPlayer` | `1` | Xens one player may summon (0 = no limit; operators have no limit) |
| `maxXens` | `0` | Xens the whole world may have (0 = no limit) |
| `randomNames` | `true` | names like Pip and Nova instead of Xen, Xen2... |
| `personalities` | `true` | each Xen has its own nature |
| `wants` | `true` | free Xens choose their own goals |
| `skins` | `["random"]` | built-in skin names, "random", or custom textures |
| `teams` | `1` | 0 = none, 1 = one team, 2-6 = that many teams |
| `pvp` | `"defend"` | `"off"`, `"defend"` or `"teams"` |
| `evolution` | `false` | replace the worst ownerless Xens with children of the best |
| `generationDays` | `3` | Minecraft days per generation |
| `redstone` | `true` | Xen may build small circuits |
| `maxRedstoneParts` | `24` | the biggest circuit it will build |
| `signs` | `true` | leave notes on signs when nobody it knows is around |
| `decisionTicks` | `5` | ticks between decisions (5 = four a second; 10 or 20 for slow machines) |
| `followDistance` | `10` | follow mode: catch up when further than this |
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
* **Instincts**: it swims up in water, fights back against monsters within
  reach, and eats when it gets hungry.
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
