**Prototype.** Xen Companion is a survival companion for Minecraft (Fabric): a player that learns, thinks, feels fear
and chats, and plays fair. It only knows what it can sense and acts only through a player's inputs.

### Downloads

| file | what |
|---|---|
| `xen-companion-0.4.1-alpha+mc1.21.11-with-chat.jar` | **all in one** for Minecraft 1.21.11 (Java 21): the mod, its brain and its chat model inside, about 400 MB |
| `xen-companion-0.4.1-alpha+mc26.x-with-chat.jar` | **all in one** for Minecraft 26.1 - 26.3 (Java 25) |
| `xen-companion-0.4.1-alpha+mc1.21.11.jar` | the light mod for 1.21.11 (7 MB; the chat model downloads when needed). **Use this one on phones** |
| `xen-companion-0.4.1-alpha+mc26.x.jar` | the light mod for 26.1 - 26.3 |
| `smollm2-360m-instruct-q8_0.gguf` | the chat model on its own (for the light jars): put it in `config/xen/`, or it downloads by itself |
| `xen-brain.bin` | Xen's trained brain, already inside the jars. Copy it to `<world>/xen/brain.bin` to reset a world's Xens to it |
| `xen-brain-30days-experimental.bin` | experimental: the same brain after 30 more days in real Minecraft with evolution. It fears zombies much more, but mines almost anything (even toward lava) and does worse on SimCraft's tests (reward per life 2.3 vs 32.6). Copy it to `<world>/xen/brain.bin` to experiment |
| `SHA256SUMS.txt` | checksums |

Use **one** mod jar. Needs Fabric Loader 0.16+ and Fabric API. Mod Menu is optional (settings screen). Install, phones and settings:
[dist/README.md](https://github.com/JAK-cool162/Xen/blob/main/dist/README.md).

### What's new in 0.4.1-alpha

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

It's a prototype. Xen still dies more than a good player and gets lost in tricky terrain. Its chat model is small and
its answers are simple. It only gathers what it can reach on foot (plus one block up with a pillar). A good PvP
player will still beat it: it can't combo or dodge arrows, and it doesn't use a mace, spear, crystals or pearls yet.
More days in the real game didn't make the brain better at SimCraft's tests, which is why the bundled brain stays
the SimCraft one.

The chat model is [SmolLM2-360M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct) by Hugging
Face (Apache-2.0).
