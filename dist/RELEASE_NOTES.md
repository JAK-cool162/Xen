**Xen Companion 1.3.0**: AI players for Minecraft (Fabric) that live their own lives. They gather, mine, build their
own houses, farm, trade, form villages with their own rules, make friends and enemies, travel to the Nether and the
End, and talk with you. Like a real player, a Xen only knows what it can see and only acts through a player's
controls.

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
| `xen-companion-1.2.1+mc1.21.11-with-chat.jar` | **all in one** for Minecraft 1.21.11: the mod with its chat model inside |
| `xen-companion-1.2.1+mc26.x-with-chat.jar` | **all in one** for Minecraft 26.1 - 26.3 |
| `xen-companion-1.2.1+mc1.21.11.jar` | the light mod for 1.21.11 (the chat model downloads if you want it). **For phones** |
| `xen-companion-1.2.1+mc26.x.jar` | the light mod for 26.1 - 26.3 |
| `SmolLM2-135M-Instruct-Q8_0.gguf`, `smollm2-360m-instruct-q8_0.gguf` | the chat models on their own (small for phones, normal for PCs) |
| `TECHNICAL.txt` | **how it all works**: Xen 5.2, the settings, the commands, every change |
| `xen-brain*.bin`, `SHA256SUMS.txt` | the classic brain (already inside the jars), checksums |

**Install**: Fabric Loader 0.16+ and Fabric API, then one of the jars in your mods folder. Mod Menu (optional) gives a
settings screen. In game: `/xen summon`. Phones: the light jar, 2 GB for Minecraft.
Full guide: [dist/README.md](https://github.com/JAK-cool162/Xen/blob/main/dist/README.md).

The chat models are [SmolLM2](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct) by Hugging Face (Apache-2.0).
