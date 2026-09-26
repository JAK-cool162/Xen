**Xen Companion 1.1.1**: AI players for Minecraft (Fabric) that live their own lives. They gather, mine, build their
own houses, farm, trade, form villages with their own rules, make friends and enemies, travel to the Nether and the
End, and talk with you. Like a real player, a Xen only knows what it can see and only acts through a player's
controls.

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
| `xen-companion-1.1.1+mc1.21.11-with-chat.jar` | **all in one** for Minecraft 1.21.11: the mod with its chat model inside |
| `xen-companion-1.1.1+mc26.x-with-chat.jar` | **all in one** for Minecraft 26.1 - 26.3 |
| `xen-companion-1.1.1+mc1.21.11.jar` | the light mod for 1.21.11 (the chat model downloads if you want it). **For phones** |
| `xen-companion-1.1.1+mc26.x.jar` | the light mod for 26.1 - 26.3 |
| `SmolLM2-135M-Instruct-Q8_0.gguf`, `smollm2-360m-instruct-q8_0.gguf` | the chat models on their own (small for phones, normal for PCs) |
| `TECHNICAL.txt` | **how it all works**: Xen 5.2, the settings, the commands, every change |
| `xen-brain*.bin`, `SHA256SUMS.txt` | the classic brain (already inside the jars), checksums |

**Install**: Fabric Loader 0.16+ and Fabric API, then one of the jars in your mods folder. Mod Menu (optional) gives a
settings screen. In game: `/xen summon`. Phones: the light jar, 2 GB for Minecraft.
Full guide: [dist/README.md](https://github.com/JAK-cool162/Xen/blob/main/dist/README.md).

The chat models are [SmolLM2](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct) by Hugging Face (Apache-2.0).
