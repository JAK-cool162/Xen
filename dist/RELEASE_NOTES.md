**Prototype.** Xen Companion is a survival companion for Minecraft (Fabric): a player that learns, thinks, feels fear
and chats, and plays fair. It only knows what it can sense and acts only through a player's inputs.

### Downloads

| file | what |
|---|---|
| `xen-companion-0.3.0-alpha+mc1.21.11.jar` | the mod for Minecraft 1.21.11 (Java 21). **Use this one on phones** |
| `xen-companion-0.3.0-alpha+mc26.x.jar` | the mod for Minecraft 26.1 - 26.3 (Java 25) |
| `smollm2-360m-instruct-q8_0.gguf` | the chat model (optional, about 390 MB): put it in `config/xen/`. Otherwise it downloads by itself the first time it's needed |
| `xen-brain.bin` | Xen's trained brain, already inside the jars. Copy it to `<world>/xen/brain.bin` to reset a world's Xens to it |
| `xen-brain-30days-experimental.bin` | experimental: the same brain after 30 more days in real Minecraft with evolution. It fears zombies much more, but mines almost anything (even toward lava) and does worse on SimCraft's tests (reward per life 2.3 vs 32.6). Copy it to `<world>/xen/brain.bin` to experiment |
| `SHA256SUMS.txt` | checksums |

Needs Fabric Loader 0.16+ and Fabric API. Mod Menu is optional (settings screen). Install, phones and settings:
[dist/README.md](https://github.com/JAK-cool162/Xen/blob/main/dist/README.md).

### What's new in 0.3.0-alpha

* **Runs on x86-64 and ARM64.** The mod and its chat model are plain Java with no native code, so one jar runs on
  PCs, phones, Raspberry Pi and Apple Silicon. Every push is now checked on an x86-64 and an ARM64 machine (tests,
  both builds, the Java == Python checks and the chat model).
* **Settings screen in Mod Menu** (Mods → Xen Companion): how many Xens, teams, PvP, evolution, redstone and its
  size limit, names, personalities, skins, chat. On servers: `/xen settings` and `/xen set <setting> <value>`.
* **Names, personalities and skins.** New Xens get a random name (Pip, Nova, Waffle...), a personality (brave or
  timid, curious, chatty or quiet, patient or impatient, and a tone of voice that shows in what it says) and one of
  Minecraft's 18 built-in skins. Custom skins from mineskin.org work too. The same name brings back the same Xen.
* **Fighting and building styles.** Every Xen also gets a fighting style: brawler, rusher, skirmisher (hits and
  steps back), guard (holds its ground behind a shield) or dancer (circles its foe). It also gets a building style: its shelter is a
  hut, a fort or a tower, of stone, dirt or anything. Children inherit them. `/xen style Pip fight guard` sets any
  trait by hand (also build, material, tone, bravery, curiosity, chattiness, diligence).
* **Teams and PvP.** One team or 2-6 colored teams (no friendly fire). PvP `off`, `defend` (default: fights back
  against players who hurt it or its owner) or `teams` (teams fight each other). It times its swings, jumps for
  critical hits and sprints in. It's a companion, not a PvP bot: it can't block, strafe or combo.
* **Evolution.** Every few days the worst ownerless Xens are replaced by children of the best, whose genes mix and
  mutate. Logged in `<world>/xen/evolution.csv`. Your own Xens are never replaced.
* **Small redstone.** "Xen, build a NOT gate" (also OR, AND and a repeater wire): circuits Xen worked out itself in
  its redstone lessons, placed part by part by hand. Capped at 24 parts by default so nothing big slows the server.
* **The chat model only wakes when it's needed**: when someone the Xen knows is within 32 blocks or someone talks to
  it. After 10 quiet minutes it unloads. When nobody it knows is around, Xen leaves notes on signs instead ("Day 12:
  Diamonds here! -Pip").
* A mod icon, `/xen status` shows each Xen's personality and styles, and console-summoned Xens now land on the surface.
* Fixes: a Xen that was mining or walking when attacked now fights back at once. It notices attackers within 6 blocks
  even behind its back (it knows everything that close). Dismissing a Xen while it was dead no longer leaves a ghost
  player online. On 26.3, teams, sign notes, tossing items and the settings screen now work.

### Phones (Zalith Launcher 2, PojavLauncher / Amethyst)

Install Fabric for 1.21.11 in the launcher, then put Fabric API and the 1.21.11 jar in the mods folder (steps in
[dist/README.md](https://github.com/JAK-cool162/Xen/blob/main/dist/README.md#on-a-phone-android)).
Give Minecraft 2 GB or more (3 GB for the chat model). With less memory the chat model stays off, but Xen still
understands requests and answers in plain words. Not tested on a real phone yet: the ARM64 checks run on a Linux
ARM64 machine.

### Known limits

It's a prototype. Xen still dies more than a good player and gets lost in tricky terrain. Its chat model is small and
its answers are simple. It only gathers what it can reach on foot (plus one block up with a pillar). In PvP it loses
to a simple scripted fighter that strikes first with the same sword (leaving it at 2-14 of 20 health). Xen vs Xen,
brawlers and rushers win most fights, and a guard needs a shield. More days in the real game didn't make the brain
better at SimCraft's tests, which is why the bundled brain stays the SimCraft one.

The chat model is [SmolLM2-360M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct) by Hugging
Face (Apache-2.0).
