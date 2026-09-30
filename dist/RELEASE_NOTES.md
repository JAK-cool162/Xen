**Xen Companion 1.9.0**: AI players for Minecraft (Fabric) that live their own lives. They gather, mine, build their
own houses, farm, trade, form villages with their own rules, make friends and enemies, travel to the Nether and the
End, and talk with you. Like a real player, a Xen only knows what it can see and only acts through a player's
controls.

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
