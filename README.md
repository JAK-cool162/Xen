# Xen

light weight DMM for Minecraft named Xen

Xen is a decision-making model that plays Minecraft. It makes a new decision
every moment (press keys, mine blocks, fight, eat, place blocks), and it
learns from everything that happens. It **thinks**: it imagines what its
options lead to before choosing. It **feels**: pain, fear, curiosity,
satisfaction and boredom, because fear is how it learns to stay alive.

On top of survival it has skills: it **builds** from templates (roofs, arches,
houses, towers, bridges), **rates** builds and learns your taste in buildings,
and **learns redstone** by experimenting. In real Minecraft you can bring in
as many Xens as the server allows. Each one is a real player, and they all
share one brain.

Only needs Python 3.9+ and numpy. No GPU and no deep-learning framework.

```
pip install -r requirements.txt
python -m xen train --lives 50      # grow up in SimCraft (built-in Minecraft-like world)
python -m xen watch                 # watch it live, with its inner monologue
python -m xen evaluate --baseline   # what it learned, compared with a newborn
```

---

## How Xen works

Every tick Xen goes through the same loop (`xen/brain/agent.py`):

| step | brain part | what happens |
|---|---|---|
| perceive | `perception.py` | the blocks around it (a 17×13×17 cube), turned so "forward" is where it looks; pointers to the nearest treasure, lava and mob; its own body (health, hunger, burning...) |
| feel | `brain/emotions.py` | the **amygdala** predicts how much harm each action leads to; that becomes felt **fear** |
| think | `brain/cortex.py` | if afraid (or unsure), it **imagines** its best options a few steps ahead with its world model and weighs imagined reward against imagined harm |
| decide | `brain/agent.py` | picks the action with the best *reward − caution × fear*; when exploring it's curious but cautious |
| act | `actions.py` | real Minecraft inputs: W/A/S/D, jump, turn, look, mine (hold left click), place, attack, eat |
| learn | `brain/critic.py`, `brain/memory.py`, `brain/world_model.py` | stores what happened and trains everything, continuously |

### Why Xen needs fear

Fear is the signal that teaches it to survive:

* **Pain**: harm happening right now (damage, burning, death). This is the teacher.
* **Fear**: harm it *expects*. The amygdala is a critic trained on harm instead of
  reward. It learns cue → consequence one step at a time, so fear stays specific
  (walking *into* lava is terrifying, stepping away from it is not).
* **Trauma memory**: moments that hurt, together with the moments that led up to
  them, are replayed far more often than ordinary memories. One burn is enough to
  start fearing lava (fear conditioning).
* **Caution**: being hurt makes Xen more careful for a while (sensitisation); safe
  time calms it down again (habituation).
* **Exposure**: it sometimes tries even what it fears. Without that, avoidance would
  keep an unfounded fear alive forever. With it, harmless things stop being scary
  (extinction).
* **Fear makes it think**: when afraid, it stops acting on instinct and imagines
  where each option leads before choosing.

The other feelings: **curiosity** (its world model was surprised) pushes it to
explore; **joy memory** replays big rewards (a diamond makes digging feel worth
it); **boredom** builds up when nothing changes, so it never loops on a
pointless action.

Watching it (`python -m xen watch`) shows the feelings and the inner monologue:

```
pain ---------- fear ######---- curiosity #--------- satisfaction ---------- caution  4.0  mood: afraid
action: BACK       (thought)
Xen: I'm afraid; I don't want to walk forward. Thinking it through (step back +0.41, turn left +0.12, ...) -> I'll step back.
```

### SimCraft: where Xen grows up

`xen/worlds/simcraft.py` is a small, fast voxel world with the parts of Minecraft
that matter for learning: terrain with trees, dirt and stone; ores that get
richer (and more dangerous) the deeper it goes; caves; lava (often hidden right
next to diamonds); water; fall damage; day and night; zombies; hunger and food.
It uses the same actions and the same perception as the real game, so what Xen
learns there carries over.

```
python -m xen train --lives 200 --brain xen_brain.npz    # keeps learning where it left off
python -m xen evaluate --brain xen_brain.npz --baseline
```

A pre-trained brain comes with the repo (`brains/xen_simcraft.npz`, 200 lives,
about 150k decisions). Every command starts from it when you don't have your
own brain file yet (use `train --fresh` for a newborn).

**What it learned** (reward = value of what it mined and collected in one life):

| lives | average reward per life |
|---|---|
| 1-40 | 13.4 |
| 41-80 | 24.6 |
| 81-120 | 30.3 |
| 121-160 | 62.5 |
| 161-200 | 85.3 |

`python -m xen evaluate --baseline` (8 fixed worlds, no exploring):

```
trained  reward per life  74.08 | survived 3/8 (deaths: lava x2, starvation x1, zombie x2)
newborn  reward per life   0.78 | survived 0/8 (deaths: starvation x2, zombie x6)
```

Its fear is learned, and it's specific (how much harm the amygdala expects):

| situation | trained Xen | newborn |
|---|---|---|
| walk forward into lava vs onto ground | 0.117 vs 0.003 | 0 vs 0 |
| dig straight down onto lava vs onto stone | 0.041 vs 0.005 | 0 vs 0.008 |
| a zombie in its face vs nothing there | 0.018 vs 0.001 | 0.010 vs 0.014 |

It still dies more often than a good player. Deep digging for ore means lava
and zombies, and more training makes it better.

---

## Playing real Minecraft

### As a real player (Java Edition, recommended)

Each Xen is a [mineflayer](https://github.com/PrismarineJS/mineflayer) bot: a real
player connection. The server treats it like anyone else: it shows up in the
player list, takes damage, has an inventory, can chat, and can be given /op.
Its perception is the same block cube as in SimCraft, so the trained brain
works in the real game and keeps learning there.

1. Run a server (or open a single-player world to LAN) with `online-mode=false`
   in `server.properties`, so Xens can join without Microsoft accounts.
2. Start the bridge (Node.js 18+):
   ```
   cd bridge && npm install
   node xen_bridge.js --host localhost --port 25565
   ```
3. Start Xen:
   ```
   python -m xen play --world mineflayer
   ```

### Swarm: as many Xens as the server holds

```
python -m xen swarm --count 0 --spread 300
```

* `--count 0` keeps bringing in new Xens (`Xen`, `Xen_2`, `Xen_3`, ...) until the
  server is full, then keeps trying now and then as slots free up. Use a
  number for a fixed swarm. Raise `max-players` in `server.properties` for a
  bigger swarm.
* `--spread 300` scatters each new Xen up to 300 blocks away across the world
  (uses `/spreadplayers`, so the Xens need op; `/op Xen_2` etc., or use the
  `spawnRadius` gamerule).
* One brain drives all of them: every body's experience, and every body's pain,
  trains the same mind, so the swarm learns faster the bigger it gets. Each body
  still has its own feelings.

### Talk to Xen in chat

Any player can type:

| chat | what Xen does |
|---|---|
| `xen build house gambrel spruce` | builds a house (any roof and palette below) next to itself |
| `xen build tower` / `bridge` / `arch` | other templates |
| `xen design` | designs its best house (by its taste) and builds it |
| `xen rate 8` | your score (0-10) for its last build; it learns your taste |
| `xen redstone and` | works out an AND gate and builds it (levers on the left, lamp on the right) |
| `xen roofs`, `xen palettes`, `xen count`, `xen spawn 5`, `xen help` | ... |

Building uses `/fill` and `/setblock`, so give the builder op (`/op Xen`). With
`--build-mode hands` it places blocks by hand like a creative-mode player
instead.

### With real keyboard and mouse (any edition)

```
pip install mss pynput
python -m xen play --world screen
```

Xen presses W/A/S/D and space, moves the mouse and clicks on your actual
screen. It sees a downscaled picture of the screen and feels pain when red
hearts disappear from the health bar. It learns from scratch in this mode (it
sees pixels, not blocks). Use default controls, put food in hotbar slot 9, and
turn Raw Input off. The health-bar position can be adjusted in
`xen/worlds/screen.py` (`ScreenConfig`).

---

## Building

```
python -m xen build house --roof gambrel --palette spruce --out house.mcfunction
python -m xen build --design            # let Xen design one to its (your) taste
python -m xen rate house --roof a_frame --score 9     # teach it your taste
```

**Roof profiles** (`xen/building/templates.py`): `gable`, `gable_low`, `a_frame`,
`gambrel`, `mansard`, `curved`, `saltbox`, `mono`, `butterfly`, `m_shaped`,
`clerestory`, `flat`. Each is a height line across the roof, like the classic
roof-profile charts. Slopes become stairs, flat parts become slabs, steep parts
become full blocks, and the gable ends and high eaves get walled in.

```
gable            gambrel          a_frame (top)    butterfly
      _             /_\                 _          \\         //
     /#\          //###\\               |           #\\     //#
    /###\        /#######\             /#\          ###\\ //###
   /#####\       |###o###|             |#|          |||||_|||||
  /###o###\     /#########\           /###\         |#o#*o*#o#|
 /#########\    |||||||||||           |###|         |#o##D##o#|
/|||||||||||\  /|#o#*o*#o#|\          ...           |####D####|
 |#o#*o*#o#|    |#o##D##o#|                         ###########
```

Also **arches** (`round`, `pointed`, `segmental`, `flat`, `horseshoe`, `tudor`) for
hallways and bridges, **towers** with cone roofs, **bridges**, and **palettes**
(`oak`, `spruce`, `birch`, `stone`, `sandstone`, `fantasy`, `medieval`) with
contrasting log frames, stone bases, windows, doors and lights.

**Rating** (`xen/building/rating.py`) scores any build 0-10 from its blocks alone,
so it also works on real builds scanned from a world:

* how it looks: roof shape and overhang, window balance and a door, 3-6
  materials, detail (stairs, slabs, fences, lights, frames), symmetry,
  proportion, lighting;
* whether it works: can you shelter inside, and is it connected to the ground.

**Taste** (`xen/building/taste.py`): when you rate a build, Xen compares your
score with its prediction and adjusts its preferences for roof styles,
palettes and qualities. The designer then evolves designs towards what you
like and starts from your favourites.

Any build exports to a `.mcfunction`: put it in a datapack
(`data/<namespace>/function/`) and run `/function <namespace>:<name>` to paste it
where you stand.

---

## Redstone

```
python -m xen redstone all
python -m xen redstone and --out and.mcfunction
```

`xen/redstone/sim.py` simulates redstone on a floor, following Java Edition rules:
dust carries strength 15 and fades 1 per block (a line dies after 15 blocks),
and only powers what it points into; blocks are weakly powered by dust and
strongly powered by repeaters; torches invert the block they hang on, one tick
later; repeaters (1-4 ticks) and comparators (compare/subtract); levers and lamps.

Xen **learns** circuits (`xen/redstone/learn.py`):

1. **By experiment**: it tries circuits, keeps the best, and learns which part
   belongs on which cell (cross-entropy method). It also learns from near misses:
   how close the lever-controlled signal gets to the lamp. That's how it works
   out the NOT gate, OR, and an 18-block wire (it discovers it needs a repeater).
2. **By combining what it knows**: AND = NOT(OR(NOT a, NOT b)), NAND, NOR... are
   built from its own NOT and OR modules, joined with repeaters.
3. It **remembers**: solved circuits go into `xen_redstone.json`, and the parts
   that worked become a prior that speeds up new tasks.

XOR/XNOR are still being experimented with: the learner tries them, but they
need signals to branch, which it can't compose yet.

---

## Checked against real Minecraft

`scripts/verify_real_world.py` runs against a real server (vanilla 1.21.11,
offline mode). The last run:

```
Redstone: learned circuits, real levers, real lamps
  not   simulator [1, 0]  real game [1, 0]  OK
  or    simulator [0, 1, 1, 1]  real game [0, 1, 1, 1]  OK
  and   simulator [0, 0, 0, 1]  real game [0, 0, 0, 1]  OK
  nand  simulator [1, 1, 1, 0]  real game [1, 1, 1, 0]  OK
  nor   simulator [1, 0, 0, 0]  real game [1, 0, 0, 0]  OK
  wire  simulator [0, 1]  real game [0, 1]  OK
Building
  house: 357/357 blocks as designed; the real build rates 9.77/10
Swarm
  Xen_2: joined
  Xen_3: joined
  Xen_4: joined

ALL GOOD
```

The swarm test kept adding Xens until the server was full (11 Xens plus one
human player on a 12-slot server), and they answered chat requests while the
rest kept playing.

---

## Project layout

```
xen/
  actions.py          the actions and the keys/mouse behind them
  blocks.py           block categories, values, drops
  perception.py       egocentric block cube -> observation
  brain/
    agent.py          Xen: perceive, feel, think, decide, learn; save/load
    critic.py         TD critics (reward = striatum, harm = amygdala), dueling heads
    emotions.py       pain, fear, caution, curiosity, satisfaction, boredom
    memory.py         experience + trauma + joy memories
    world_model.py    imagination (predicts next observation, reward, harm)
    cortex.py         thinking: imagined rollouts before hard choices
    nn.py             small numpy neural network + Adam
  worlds/
    simcraft.py       the built-in Minecraft-like world
    mineflayer.py     real Minecraft via the bridge (one Xen)
    screen.py         real Minecraft via keyboard, mouse and screen pixels
  swarm.py            many Xens, one brain
  skills.py           chat commands: build, design, rate, redstone, spawn
  building/           blueprints, templates, rating, taste, designer
  redstone/           simulator and learner
  life.py             the continuous life loop
  cli.py              python -m xen ...
bridge/xen_bridge.js  mineflayer bots <-> Xen (hosts the whole swarm)
brains/               pre-trained brain
scripts/              real-server verification
tests/                python -m unittest discover -s tests -t .
```

## Tests

```
python -m unittest discover -s tests -t .
```

The tests cover the network (gradient checks), perception, the simulator,
fear conditioning (Xen learns to fear walking into lava, and only that),
shared-brain swarms, the real-world protocol against a fake bridge, the
keyboard/mouse backend against a fake screen, building, taste, redstone rules
and learning, and the command line.
