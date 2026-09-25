**Prototype.** Xen Companion is a survival companion for Minecraft (Fabric): a player that learns, thinks, feels fear
and chats, and plays fair. It only knows what it can sense and acts only through a player's inputs.

### Downloads

| file | what |
|---|---|
| `xen-companion-0.5.1-alpha+mc1.21.11-with-chat.jar` | **all in one** for Minecraft 1.21.11 (Java 21): the mod, its brain and its chat model inside, about 400 MB |
| `xen-companion-0.5.1-alpha+mc26.x-with-chat.jar` | **all in one** for Minecraft 26.1 - 26.3 (Java 25) |
| `xen-companion-0.5.1-alpha+mc1.21.11.jar` | the light mod for 1.21.11 (7 MB; the chat model downloads when needed). **Use this one on phones** |
| `xen-companion-0.5.1-alpha+mc26.x.jar` | the light mod for 26.1 - 26.3 |
| `smollm2-360m-instruct-q8_0.gguf` | the chat model on its own (for the light jars): put it in `config/xen/`, or it downloads by itself |
| `xen-brain.bin` | Xen's trained brain, already inside the jars. Copy it to `<world>/xen/brain.bin` to reset a world's Xens to it |
| `xen-brain-30days-experimental.bin` | experimental: the same brain after 30 more days in real Minecraft with evolution. It fears zombies much more, but mines almost anything (even toward lava) and does worse on SimCraft's tests (reward per life 2.3 vs 32.6). Copy it to `<world>/xen/brain.bin` to experiment |
| `SHA256SUMS.txt` | checksums |

Use **one** mod jar. Needs Fabric Loader 0.16+ and Fabric API. Mod Menu is optional (settings screen). Install, phones and settings:
[dist/README.md](https://github.com/JAK-cool162/Xen/blob/main/dist/README.md).

### What's new in 0.5.1-alpha

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

It's a prototype. Xen still dies more than a good player and gets lost in tricky terrain. It can't smelt yet (so no iron
tools), and it leaves its crafting tables where it used them. Its chat model is small and its answers are simple (plain
questions are now answered without it). It only gathers what it can reach on foot (plus one block up with a pillar). A good PvP
player will still beat it: it can't combo or dodge arrows, and it doesn't use a mace, spear, crystals or pearls yet.
More days in the real game didn't make the brain better at SimCraft's tests, which is why the bundled brain stays
the SimCraft one.

The chat model is [SmolLM2-360M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct) by Hugging
Face (Apache-2.0).
