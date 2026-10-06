**Xen Companion 2.0.0-beta.3**: AI players for Minecraft (Fabric) that live their own lives. They gather, mine, build their
own houses, farm, trade, form villages with their own rules, make friends and enemies, travel to the Nether and the
End, and talk with you. Like a real player, a Xen only knows what it can see and only acts through a player's
controls.

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

### 1.9.3: no more standing about, building that works, chat that remembers

* **No more standing about**:
  * At night, a Xen with a pickaxe digs down and mines (stone, coal, more iron), from the surface, its shelter or a
    cave. Before, some just stood "looking around" all night, or kept "taking a breather" with iron ore a block away.
  * With nothing to do for 20 seconds (it used to be a minute), it goes off exploring.
  * At a furnace it mines ore, stone or logs within reach while the iron smelts, instead of standing and waiting.
* **"go explore" means it**: told to explore, it explores for a few minutes, day or night. Before, it said "Okay!"
  and went back to smelting, or built a hut. "go explore?" with a question mark is an order too.
* **"go to the cave"**: "go to the cave", "take me to the village", "go home", "go to the portal" take it to places it
  remembers ("Okay, to the cave at 257 58 -190. Follow me!"). If it hasn't found one, it says so.
* **Building its shelter**:
  * It can put blocks against any block face (slabs, stairs, fences, leaves), not only full blocks.
  * It fills a gap under a wall first, and steps to another spot inside if it can't see where a block goes.
  * Under the ground it digs in instead of building a hut; if a hut can't be finished at night, it digs a hole.
  * "Stuck: nothing solid to place it against" was this.
* **Gathering stone**: it takes stone at its own level first instead of digging a 3x3 crater around its feet.
* **Skeletons**: between shots, armed and healthy, it goes in and hits the skeleton; weak or unarmed, it gets out of
  its sight. Before, it dodged, stood still, and dodged again.
* **Chat that remembers**:
  * Follow-ups are read with what went before: "do you like pigs?" then "what about cows?"; "how do I make a bed?"
    then "and a chest?"; "how many logs?" then "what about iron?".
  * "I'm good, and you?" and "wbu" ask it back.
  * "Is it hard?" is about what you were talking about.
  * With the AI chat model on, it's told what was just said too.
  * "I love diamonds" is talk now, not an order to go mining.
* **Grudges fade**: "sorry" mends things after a hit (a kind Xen forgives faster, a wrathful one slower), and old
  grudges fade by themselves. When it won't do something because you hurt it, it says a sorry would help.
* **Fixes**: "me'll come back to it" and "before me can make tools" read "I'll" and "I can" now; "I want to smelt your
  iron" reads "smelt the iron".

### 1.9.2: the right tool, useful nights, chat that counts

* **The right tool for the job**: a Xen picks the tool that breaks a block fastest (a shovel for dirt and sand, an axe
  for wood, a pickaxe for stone), from anywhere in its bag (a tool in its backpack goes to its hotbar first). It never
  digs with a sword, and it no longer swaps to its sword halfway through a block when something armed walks by. It
  also makes a stone shovel with its stone tools. In the last test runs (4 Xens, 665 blocks) it never dug with a
  sword; the few bare-handed digs were right after its pickaxe broke. It also holds the right tool as others see it
  (before, it could look like it was chopping with a sword).
* **A bed before night**: late in the day, with no bed, it goes for wool from sheep it can see, or walks back to where
  it saw sheep earlier (it remembers). It even stops work on its own house for this; the house picks up after.
  * It only counts wool when it's hunting for a bed, so it no longer stops after one sheep or kills cows instead.
  * With three wool it makes the bed at once, fetching a log first if it has no planks.
  * At night it puts the bed down wherever there's room nearby and sleeps.
  * At night it only goes after sheep if they're close and it's safe.
  * Ask it: "get wool for a bed", "make a bed".
  * In the test, Xens at dusk with sheep about went from sheep to wool to a log to a bed, and slept in it.
* **Useful nights, no standing about**:
  * In its shelter at night it no longer sits until morning: settled, fed and armed with a pickaxe, it digs down from
    inside and mines.
  * At home it crafts, smelts or sorts its chest, and rests only when there's nothing else to do.
  * Some Xens stood still for a minute or more, stuck on the same thing:
    * one wanted torches but had only logs, and tried again three times a second. Now it makes planks, then sticks,
      then the torches.
    * one kept failing to craft a bed while it had a crafting table down.
    * one was holding a bed in a tunnel too tight to put it down.
  * Now a failed craft waits 30 seconds. Anything that ends the moment it starts isn't picked again for 20 seconds.
* **Chat controls what it does**: suggestions are requests now, however you put them: "why don't you build a house",
  "want to get some iron?", "you should get some wood", "should we go mining?", "how about you get some food". It does
  them (if it's yours to ask), and tells you in its own words. With the AI chat model on too.
* **Harder questions**: it answers from what it really knows and does, in its own tone:
  * why it's doing that ("Because I need wool for a bed.");
  * what to do next, or tonight;
  * how to make or find things ("how do I make a bed?", "how do I get diamonds?", about 80 of them);
  * who its best friend is;
  * which is better ("iron or diamond?", "a sword or an axe?");
  * "should I go exploring?" (not at night, it'll say);
  * where it's going, what it did today, and whether it's safe out there.

### 1.9.1: Xens talk with their own words

* **Its own words, no big model**: with the AI chat model off (or not loaded yet, or on a phone without the memory for
  it), a Xen now builds what it says word by word instead of answering simply. It reads what you said (hello, a
  question, what it's about, whether it sounds good or bad, chat shorthand like "u", "wyd", "lol"), a tiny network picks
  what kind of reply fits (greet back, say how it feels or what it's doing, give its opinion, agree or not, ask back,
  cheer you up, crack a joke, take a compliment, answer an insult...), and it puts the sentence together from a word
  library. Light: about 8 KB of numbers and a word list, nothing to download, answers at once.
* **In its own style**: a shy Xen stammers and trails off ("U-um, I'm not sure..."), a cheerful one shouts ("Yay, let's
  go mining!! :)"), a grumpy one mutters ("Finally." to "bye"), a bold one doesn't hedge ("I know."), a silly one jokes.
  Its temper decides how it takes an insult. What it thinks of creepers, diamonds, the Nether or pigs is its own: its
  nature, its sins, its skills, plus a few tastes of its own.
* **Still does what you ask**: requests work as before, and the answer comes in its tone ("On it. I'll stay here.",
  "O-okay... I'll get some wood.", "Fine. I'll stay here."). "I found diamonds!" is news now, not an order to go mining.
* **Xens talk to each other with it**: when two meet, one starts (a question, what it thinks of something, its news)
  and the other reads it and answers ("Found anything good?" "Um, I'm not sure... I really want to grow a village with
  my tribe someday..." "Villages are super good for trades!").
* **It learns what you like**: a laugh, thanks or praise after a reply makes that kind of reply likelier with you;
  "what?" or an insult makes it less likely. Kept in config/xen/words.net.
* **Your own words**: put a words.json in config/xen/ (copy the one in the mod) to add words, topics and jokes.

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
