**Prototype.** Xen Companion is a survival companion for Minecraft (Fabric): a player that learns, thinks, feels fear
and chats, and plays fair. It only knows what it can sense and acts only through a player's inputs.

### Downloads

| file | what |
|---|---|
| `xen-companion-0.6.1-alpha+mc1.21.11-with-chat.jar` | **all in one** for Minecraft 1.21.11 (Java 21): the mod, its brain and its chat model inside, about 400 MB |
| `xen-companion-0.6.1-alpha+mc26.x-with-chat.jar` | **all in one** for Minecraft 26.1 - 26.3 (Java 25) |
| `xen-companion-0.6.1-alpha+mc1.21.11.jar` | the light mod for 1.21.11 (7 MB; the chat model downloads when needed). **Use this one on phones** |
| `xen-companion-0.6.1-alpha+mc26.x.jar` | the light mod for 26.1 - 26.3 |
| `smollm2-360m-instruct-q8_0.gguf` | the chat model on its own (for the light jars): put it in `config/xen/`, or it downloads by itself |
| `xen-brain.bin` | Xen's trained brain, already inside the jars. Copy it to `<world>/xen/brain.bin` to reset a world's Xens to it |
| `xen-brain-30days-experimental.bin` | experimental: the same brain after 30 more days in real Minecraft with evolution. It fears zombies much more, but mines almost anything (even toward lava) and does worse on SimCraft's tests (reward per life 2.3 vs 32.6). Copy it to `<world>/xen/brain.bin` to experiment |
| `SHA256SUMS.txt` | checksums |

Use **one** mod jar. Needs Fabric Loader 0.16+ and Fabric API. Mod Menu is optional (settings screen). Install, phones and settings:
[dist/README.md](https://github.com/JAK-cool162/Xen/blob/main/dist/README.md).

### What's new in 0.6.1-alpha

**It behaves more like a player (on by default)**

* **It takes knockback.** Hits and explosions didn't push it at all: the server leaves a player's knockback to their
  own game client, and a Xen has none. Now it gets the same push a game client would (a punch sends it a couple of
  blocks, a Knockback sword further). Checked on 1.21.11 and 26.1.2.
* **It runs**: it sprints when it has far to go (catching up with you, walking to a tree), and walks the last bit.
  It steers for the middle of the next block, so it no longer gets caught on block corners.
* **It gives up on blocks it can't break** (bedrock, or something that would take forever with what it has) instead
  of standing there swinging.
* **It understands the dark.** Beyond 5 blocks it can't make out unlit blocks or mobs (like a player looking into a
  dark cave), and in dark caves and tunnels it **places torches** on the floor or the walls. With coal and no
  torches, it makes some ("It's dark here. I'll make some torches.").
* **No more knowing where buried ore is.** It used to feel all ore within 6 blocks, even deep in stone. Now it only
  knows ore that shows a face (in a cave, a cliff, a tunnel it digs).
* **A crouch greeting is a friendly hello.** Crouch at it quickly a few times and it crouches back ("Hi hi!
  *crouches back*") and trusts you a little more, but never fully (anyone can crouch). Keep going and it dances.
* **A punch with an empty hand gets its attention, not a fight**: it turns to you ("Hey Alex! What's up?"). Hit
  with a weapon, or poked on and on, and it's an attack.
* **PvP is its own call** (new default `own`): it fights back when someone attacks it or its owner with a weapon,
  lets a friend's mistake go, and gets away instead when it's losing. (`defend` and `teams` still work.)
* **It doesn't give things away instantly**: it thinks for a moment, keeps what it needs itself and says so ("I'll give
  Steve 6 cobblestone, but keep 10 for a shelter tonight"), then hands them over aimed at your feet and waits for you
  to pick them up.
* **You don't have to say its name** when it's clear you're talking to it: you're in a conversation with it, you're
  looking right at it, or it's your Xen and nobody else is around.
* **It remembers what you tell it**: "Pip, remember that the base is by the big oak", then "what did I tell you?" or
  "where is the base?" ("Steve told me: the base is by the big oak."). Saved with the Xen; "forget what I told you"
  clears it.
* **It reads signs** it can see ("This sign says: "Welcome to Steve's base""), and knows what they said.
* **It doesn't forget your orders**: after a night in its shelter it goes back to following you, and its own ideas
  never override "stay". A question ("any diamonds?") is no longer taken as an order to go mining, and "please give me
  ..." is no longer taken as a "yes" to something it asked.

### New in 0.6.0-alpha

**Fixes for what players saw**

* **"I found a tree N blocks away" all the time**: with the chat model off or still waking up, Xen answered anything
  with its mood and the first thing it had seen. Now it answers what you asked ("what are you doing?" "I'm looking
  around."; "any trees?" "I haven't seen any trees."), and says when it didn't get you ("Sorry, I didn't get that. You
  can ask me to follow you, stay, get wood or stone..."). The first time you talk to a Xen without the model, a grey
  line says why its answers are simple and whether the model is off, still loading, or short on memory.
* **"fallow me" did nothing**: typos are understood now (fallow, folow, cmere, wod...), and so is Thai
  (ตามมา, หยุด, ขอไม้ 5 ชิ้น...). And a Xen told to follow that was already within 10 blocks just stood there: it
  now comes to about 4 blocks and keeps up (old config files are updated).
* **It stood still unless told what to do**: a Xen following you now gets on with things by itself while you're
  close (wood, stone, food, ore, a shelter at night: "I'll grab some wood while we're here.") and drops them to keep up
  when you leave ("Coming!"). It no longer asks "Should I go get some wood?" and waits.
* **It walked around trees without chopping them**: it only mined a log standing right next to it, straight ahead.
  Now it mines whatever it can see and reach, like a player (from the side, diagonally, up the trunk, clearing leaves
  in the way). It **spots trees, ore and stone within 14 blocks** (anything showing a face to the air) and goes by
  what its eyes saw beyond that. **Huge mushrooms aren't trees** (their stem gives no wood), and neither are pumpkin
  and melon stems.
* **It left what it chopped on the ground**: it now walks over its drops (and saplings, sticks, apples) to pick them up.
* **Too scared to drop down**: with good health it now drops 4 or 5 blocks (taking a little fall damage, like a
  player) instead of refusing anything over 3; when it's hurt it's careful again. **In a hole** it digs a staircase up
  and out instead of tunnelling sideways.
* **"Getting stone" forever**: anything solid counted as stone to it, so it could spend minutes hitting a mushroom
  cap or a fence. It only mines real stone now.
* **Jumping for no reason**: its brain's jumps now happen only with something to jump onto (or in water). And
  jumping on the spot no longer counts as moving: if it gets no closer to where it's going for 3 seconds, it tries
  other ways (digging through, stepping aside, pillaring), then another target after 10 seconds.
* **No base for the night**: in the evening, if it has too few blocks for a shelter, it digs up some dirt first
  ("It'll be dark soon. I'll dig up some dirt for a shelter."), then builds the shelter when night falls.
* **"random craft boat"** made it chop wood. Now **"craft ..."** / **"make me ..."** crafts it: a boat, torches, a
  chest, a door, a bed, tools ("make me a stone pickaxe")... It works out the recipe from what it carries (a spruce
  boat with spruce wood), makes the planks and sticks first, puts down a crafting table when the recipe needs one,
  and says exactly what's missing ("I need 2 diamond (I have 0) and 1 stick (I have 0)"). "you had 20 wood" is now
  just talk, not an order to get 20 more.
* Switching the chat model on in game now loads it right away (it waited 10 minutes).

**New**

* **Skins: 61 new ones, and yours.** The mod comes with a pack of 61 original skins (48 varied people and 13 themed:
  miner, knight, farmer, chef, astronaut, ninja, pirate, scientist, robot, wizard...), free to use (CC0, in
  `docs/skins/`), signed so every player sees them, with or without the mod. **Skins** setting: `random` (the pack
  and Minecraft's 18), `pack`, `default`, **`folder`** (put PNG skins in `config/xen/skins/`, from NameMC, Planet
  Minecraft, The Skindex or your own: each is signed once through mineskin.org and remembered), `mineskin` (random
  skins from mineskin.org's gallery), `player:Name` (a Minecraft account's skin), or one skin by name.
* **Better names**: fun ones that fit its nature (a silly Xen may be WobblyNoodle, a grumpy one SaltyBadger), gamer
  tags (Pickle_42, xXWaffleXx), made-up words (Zorbax) or the classic little names. Setting: **Name style**.
* **Antics**: it's unpredictable now. Crouch up and down next to it and it dances along (other Xens join in); now and
  then it shows off ("Watch this!": a sprint, a jump, a spin) and sometimes nails it, sometimes stumbles ("I meant to
  do that."); in a fight it may take a snack break in front of a nearly beaten foe, taunt, or fake a retreat. Playful
  natures do it more. Setting: **Antics**.
* **Settings with categories down the left** (Talk, Xens, Goals, PvP, Build, Speed, **Experimental**), one or two
  columns depending on the screen.
* **Experimental: custom instructions**: tell Xens who they are ("You love cats and hate the rain. Pip: you're a
  pirate and talk like one."). A line starting with a Xen's name is only for that Xen. The chat model reads it, and
  without the model Xen still answers from it ("do you like cats?" "I love cats.").
* **Experimental: custom script**: your own rules, one per line, `when <something>: <what to do>`. When: night,
  morning, rain, hungry, hurt, attacked, diamonds, someone comes, sees a mob, hears a word, every N minutes. Do: say
  something (`{player}` and `{name}` are filled in), any request (`do build a shelter`), dance, spin, wave, show off.
  The box says which lines it can't read. From the console: `/xen set script when night: do build a shelter | when
  hears hello: say Hi {player}!`

### New in 0.5.1-alpha

* **The chat model on the graphics card.** New setting **Chat on GPU** (`gpu`: auto, on, off). In single player Xen
  opens its own hidden OpenGL 3.3 context, puts the model there (about 390 MB of graphics memory) and does the big
  maths with ordinary shaders, reading prompts 32 words at a time. It doesn't touch the game's rendering, so it works
  with vanilla, Sodium, Iris or a Vulkan renderer, on any PC or Mac with OpenGL 3.3. `auto` uses it only with a real
  graphics card; if anything is missing it says why in the log and stays on the CPU. On the test machine (no graphics
  card, Mesa's software renderer) it already read prompts about twice as fast as the CPU path; a real card should be
  much faster, but that wasn't measured. Checked in real 1.21.11 and 26.1.2 clients; the 26.3 path couldn't be tried.

### New in 0.5.0-alpha

* **It acts like a player, not a digging machine.** Xen used to punch stone with bare hands (it had no way to make
  tools, so it got nothing), dig pits under itself, swing at the air and wander off. Now:
  * **It crafts its tools** like a new player, with the recipe book: planks, sticks, a crafting table, a wooden
    pickaxe (three logs), then a stone pickaxe, sword and axe. Ask for stone without a pickaxe and it gets wood first.
  * It **mines only what's worth it** (wood, ore its pickaxe can mine, stone when it needs blocks), and **never digs
    straight down**: it digs a staircase and stops if lava or water is under the next step.
  * Next to you it **waits and watches you** instead of wandering; it swings only at something hostile.
* **It knows how mobs behave.** It leaves endermen, piglins, wolves and other neutral mobs alone unless they come after
  it, never hits villagers, golems or pets, hunts only farm animals (never named ones), and **runs from a hissing
  creeper** (about 4 blocks in the 1.5 seconds before the blast).
* **Trading.** With villagers on their real trading screen (it picks the offers that are good for it), and with
  players: it names prices, bargains (a high first offer, then halfway, then its last offer), walks away from bad deals,
  gives friends a better price, and never trades away what it needs. It remembers whom it trusts.
* **It can say no**, and says why: badly hurt, scared of the dark, needs what you ask for, or you hurt it. "Please"
  changes its mind (unless you hurt it).
* **Goals in three tiers**: what it's doing now, what it wants in the next minutes, and a **dream** it works toward for
  days (a home, a stockpile, diamonds, being a trader, far places, three friends). It's proud when one comes true.
* **It talks on its own**, only about what's true for it, greets people it knows, and asks yes-or-no questions it acts
  on ("I have lots of wood. Want some?" "yes"). **Two Xens that meet chat** and tell each other where they saw trees and
  ore. Everyday questions ("what are you doing?", "what do you have?") are answered from what it knows, not by the
  chat model, so nothing is made up.
* **It learns by watching you.** When a move works out for a player it can see, it copies it, clumsily at first and
  better each time: a **water-bucket clutch** (then it clutches when it falls with a water bucket, and scoops the water
  back up), and **how you fight when you win** (crits, full-charge swings, S-taps, jump resets, spacing, the shield pull
  its fight genes your way). Say "Pip, watch this!" first. Setting: **Learns by watching**.
* **A tabbed settings screen**: Talk, Xens, Goals, PvP, Build, Speed, with a line about each tab and Reset tab. New
  settings: **Talks on its own**, **Talks with Xens**, **Trading**, **Can say no**, **Learns by watching**.

Tested on real 1.21.11 and 26.1.2 servers with a scripted player (crafting, a villager trade, a full bargain, refusals,
questions, Xen-to-Xen chat, an enderman left alone, creepers), and the 26.x jar is checked against 26.3.

### New in 0.4.1-alpha

* **All in one: the chat model inside the mod.** The `-with-chat` jars carry Xen's chat model (SmolLM2-360M), so it
  talks with nothing else to download. The first time it's needed, the mod unpacks it once into `config/xen/` (read
  from a file there, which saves memory). Every unpacked or downloaded copy is checked against the model's SHA-256,
  and a damaged one is never loaded. The model takes a minute or two to warm up; until then Xen answers in plain words.

### New in 0.4.0-alpha

* **PvP like a 1.9+ player.** Xen now fights by the server's own rules with a player's inputs: critical hits on the
  way down with sprint released, full-charge swings, sprint hits with S-taps in between, jump resets, spacing at the
  edge of its reach, stepping out of the foe's crit jump, hit selecting, shields (raised while its sword recharges,
  an axe against the foe's shield, or going around it), and golden apples when badly hurt. How much of each it does is
  set by ten **fight genes**, and the five fighting styles are starting points. Against a scripted fighter with the
  same sword that strikes first, a brawler Xen now wins about half its fights (the last version won none).
* **The PvP arena: red against blue.** `/xen arena start [xens per team] [generations] [sword|shield|axe]` builds an
  arena in the sky and trains Xens against each other in duels. Each team evolves its fight genes, a team that falls
  behind learns from the enemy, and new Xens are born with the champions' genes. Logged in `<world>/xen/arena.csv`.
  Arena Xens don't learn into the shared brain. In a 40-generation run both teams found the same way to fight (always crit, swing as soon as possible,
  fight at the edge of reach, S-tap and jump-reset, never wait or back off); the team that fell behind caught up by
  learning from the other. The evolved champion beats the careful styles but not yet the best aggressive ones.
* **Its own goals.** A free Xen chooses what it wants (food, a shelter for the night, wood, stone, ore, or to
  explore) from what it needs, its personality and what worked for it before; it says so, does it, and learns which
  goals it likes. `/xen status` shows its goal and likes. Your requests come first. Setting: **Own goals** (`wants`).
* **Falling counts now.** Xen's fall distance wasn't tracked (the server does that from a game client's moves, and
  Xen has none), so it took no fall damage and **its critical hits never landed**. Fixed: it falls like any player.
* Fixes: a Xen in a fight kept letting go of its movement keys and could get stuck in place; it now keeps them held.
  Xens act in a random order each tick, so none always gets the first hit. `/xen style` sets single fight genes
  (`/xen style Pip crit 0.9`).

### Phones (Zalith Launcher 2, PojavLauncher / Amethyst)

Install Fabric for 1.21.11 in the launcher, then put Fabric API and the 1.21.11 jar in the mods folder (steps in
[dist/README.md](https://github.com/JAK-cool162/Xen/blob/main/dist/README.md#on-a-phone-android)).
Give Minecraft 2 GB or more (3 GB for the chat model). With less memory the chat model stays off, but Xen still
understands requests and answers in plain words. Not tested on a real phone yet: the ARM64 checks run on a Linux
ARM64 machine.

### Known limits

It's a prototype. Xen still dies more than a good player and gets lost in tricky terrain. It doesn't go to the
Nether or the End or fight the Ender Dragon, and it doesn't follow you through portals yet. It builds only small
shelters so far (houses and bases are next). It can't smelt yet (so no iron
tools), and it leaves its crafting tables where it used them. Its chat model is small and its answers are simple (plain
questions are now answered without it). It only gathers what it can reach on foot (plus one block up with a pillar). A good PvP
player will still beat it: it can't combo or dodge arrows, and it doesn't use a mace, spear, crystals or pearls yet.
More days in the real game didn't make the brain better at SimCraft's tests, which is why the bundled brain stays
the SimCraft one.

The chat model is [SmolLM2-360M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct) by Hugging
Face (Apache-2.0).
