"""Command line: python -m xen {train,watch,play,swarm,build,rate,redstone,talk,evaluate,export,info}."""
import argparse
import os
import signal
import sys
import time

from . import __version__
from .actions import NUM_ACTIONS, Action
from .brain.agent import Xen, XenConfig
from .life import live
from .perception import OBS_DIM

TASTE_FILE = "xen_taste.json"
REDSTONE_FILE = "xen_redstone.json"
PRETRAINED = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "brains", "xen_simcraft.npz")


def _brain(path, obs_dim, seed, fresh=False):
    """Load a brain; with no brain file yet, start from the bundled pre-trained one."""
    if not fresh:
        for source in (path, PRETRAINED):
            if source and os.path.exists(source):
                xen = Xen.load(source, seed=seed)
                if xen.obs_dim != obs_dim:
                    if source == path:
                        sys.exit(f"{path} was trained for a different world (obs {xen.obs_dim} != {obs_dim}).")
                    continue
                what = "pre-trained brain" if source == PRETRAINED else f"brain from {path}"
                print(f"Loaded {what}: {xen.steps} steps lived, {xen.lives} lives.")
                return xen
    print("A new Xen is born.")
    return Xen(obs_dim, NUM_ACTIONS, XenConfig(), seed=seed)


def _bar(value, width=10):
    filled = int(round(max(0.0, min(1.0, value)) * width))
    return "#" * filled + "-" * (width - filled)


def _print_life(life, stats, xen, started):
    items = ", ".join(f"{k} x{v}" for k, v in sorted(stats.items.items())) or "nothing"
    end = f"died ({stats.cause})" if stats.died else "survived"
    print(f"life {life:4d} | {stats.steps:5d} ticks | reward {stats.reward:7.2f} | "
          f"harm {stats.harm:5.2f} | fear {stats.mean_fear:4.2f} | thought {stats.thoughts:4d} | "
          f"explore {xen.exploration:4.2f} | {end:14s} | {time.time() - started:6.0f}s | {items}",
          flush=True)


def _skills(args):
    from .building.taste import Taste
    from .redstone.learn import Library
    from .skills import Skills
    return Skills(Taste(args.taste), Library(args.redstone), build_mode=args.build_mode,
                  voice=getattr(args, "voice", False))


# --------------------------------------------------------------------- living
def cmd_train(args):
    from .worlds.simcraft import SimCraft
    world = SimCraft(max_steps=args.ticks, seed=args.seed)
    xen = _brain(args.brain, world.obs_dim, args.seed, args.fresh)
    started = time.time()
    try:
        live(world, xen, lives=args.lives, on_life=lambda *a: _print_life(*a, started),
             save_path=args.brain)
    except KeyboardInterrupt:
        print("\nInterrupted.")
    xen.save(args.brain)
    print(f"Brain saved to {args.brain}")


def _show(world, xen, thought, reward, harm, info, delay):
    f = thought.feelings
    out = ["\033[H\033[2J"]
    if hasattr(world, "render"):
        out.append(world.render())
    inventory = ", ".join(f"{k} {v}" for k, v in info.get("inventory", {}).items() if v)
    out.append(f"\nhealth {info.get('health', '?'):>4}  hunger {info.get('hunger', '?'):>4}  "
               f"tick {info.get('t', xen.steps)}  inventory: {inventory or '-'}")
    out.append(f"pain {_bar(f.pain)}  fear {_bar(f.fear)}  curiosity {_bar(f.curiosity)}  "
               f"satisfaction {_bar(f.satisfaction)}  caution {f.caution:4.1f}  mood: {f.mood}")
    out.append(f"action: {Action(thought.action).name:<10} ({thought.mode})")
    out.append(f"Xen: {thought.text}")
    if info.get("events"):
        out.append("events: " + ", ".join(info["events"]))
    print("\n".join(out), flush=True)
    time.sleep(delay)


def cmd_watch(args):
    from .worlds.simcraft import SimCraft
    world = SimCraft(max_steps=args.ticks, seed=args.seed)
    xen = _brain(args.brain, world.obs_dim, args.seed)
    try:
        live(world, xen, lives=args.lives, learn=args.learn, explore=False,
             on_step=lambda *a: _show(*a, args.delay),
             on_life=lambda life, stats, x: print(
                 f"\nlife over: {'died (' + stats.cause + ')' if stats.died else 'survived'}, "
                 f"reward {stats.reward:.2f}"))
    except KeyboardInterrupt:
        pass
    if args.learn and args.brain:
        xen.save(args.brain)


def cmd_play(args):
    skills = None
    if args.world == "mineflayer":
        from .worlds.mineflayer import MineflayerWorld
        world = MineflayerWorld(port=args.bridge_port, name=args.name, speak=args.speak)
        brain = args.brain or "xen_brain.npz"
        skills = _skills(args)
    else:
        from .worlds.screen import ScreenWorld
        world = ScreenWorld(countdown=args.countdown)
        brain = args.brain or "xen_screen_brain.npz"
    xen = _brain(brain, world.obs_dim, args.seed)
    started = time.time()

    def on_step(world, xen, thought, reward, harm, info):
        world.feelings = thought.feelings
        if args.verbose:
            print(f"[{Action(thought.action).name:<10}] {thought.feelings.mood:<9} {thought.text}", flush=True)
        if hasattr(world, "say") and (thought.feelings.pain > 0.15 or thought.feelings.fear > 0.6):
            world.say(f"[{thought.feelings.mood}] {thought.text}")
        for message in info.get("heard", ()):
            try:
                answer = skills.handle(message["text"], world, speaker=message["from"]) if skills else None
            except Exception as err:                    # a failed skill must not stop Xen's life
                answer = f"Sorry, that didn't work: {err}"
            if answer:
                print(f"<{message['from']}> {message['text']}\n<Xen> {answer}", flush=True)
                world.say(answer, force=True)

    try:
        live(world, xen, lives=None, learn=True, explore=True, save_path=brain, save_every=500,
             on_step=on_step, on_life=lambda *a: _print_life(*a, started))
    except KeyboardInterrupt:
        print("\nStopping.")
    finally:
        world.close()
        xen.save(brain)
        print(f"Brain saved to {brain}")


def cmd_swarm(args):
    from .swarm import Swarm
    from .worlds.mineflayer import Bridge
    xen = _brain(args.brain, OBS_DIM, args.seed)
    bridge = Bridge(port=args.bridge_port)
    count = args.count if args.count > 0 else None
    print(f"Xen swarm: {'as many as the server allows' if count is None else count} bodies, one brain.")
    swarm = Swarm(xen, bridge, count=count, prefix=args.prefix, spawn_every=args.spawn_every,
                  spread=args.spread, skills=_skills(args), speak=args.speak)
    try:
        swarm.run(save_path=args.brain, save_every=1000)
    except KeyboardInterrupt:
        print("\nStopping the swarm.")
    finally:
        xen.save(args.brain)
        bridge.close()
        print(f"Brain saved to {args.brain}")


# ------------------------------------------------------------------- building
def _make_blueprint(args):
    from .building.designer import tags_for
    from .building.templates import HouseSpec, TEMPLATES, house
    if args.template == "house":
        spec = HouseSpec(width=args.width, length=args.length, wall_height=args.height, roof=args.roof,
                         palette=args.palette, overhang=args.overhang, porch=args.porch)
        return house(spec), tags_for(spec), True
    kwargs = {"palette": args.palette}
    if args.template == "arch":
        kwargs = {"style": args.arch, "width": args.width, "height": args.height, "depth": args.length}
    bp = TEMPLATES[args.template](**kwargs)
    return bp, (f"template:{args.template}", f"palette:{args.palette}"), args.template == "tower"


def cmd_build(args):
    from .building.designer import Designer
    from .building.rating import rate
    from .building.taste import Taste
    taste = Taste(args.taste)
    if args.design:
        designer = Designer(taste, seed=args.seed)
        score, spec, rating, bp = designer.design(
            generations=args.generations,
            on_generation=lambda g, s, sp: print(f"round {g + 1:3d}: best {s:.2f}  {sp.palette} {sp.roof} "
                                                 f"{sp.width}x{sp.length}x{sp.wall_height}", flush=True))
        taste.save()
        print(f"\nXen's design: {spec}")
        shelter = True
    else:
        bp, tags, shelter = _make_blueprint(args)
        rating = rate(bp, shelter=shelter)
        score = taste.predict(rating, tags, shelter)
    print(f"\n{bp.name}: {len(bp)} blocks, size {bp.size[0]}x{bp.size[1]}x{bp.size[2]} (x, y, z)\n")
    print("front view:\n" + bp.front_view() + "\n")
    print("side view:\n" + bp.side_view() + "\n")
    print(f"Xen rates it {score:.1f}/10")
    print(str(rating).split("\n", 1)[1])
    if args.out:
        bp.to_mcfunction(args.out)
        print(f"\nWrote {args.out}: put it in a datapack (data/<ns>/function/) and run /function <ns>:"
              f"{os.path.splitext(os.path.basename(args.out))[0]} where you stand.")


def cmd_rate(args):
    from .building.rating import rate
    from .building.taste import Taste
    taste = Taste(args.taste)
    bp, tags, shelter = _make_blueprint(args)
    rating = rate(bp, shelter=shelter)
    predicted = taste.predict(rating, tags, shelter)
    if args.score is None:
        print(f"{bp.name}: Xen rates it {predicted:.1f}/10")
        print(str(rating).split("\n", 1)[1])
        return
    error = taste.learn(rating, tags, args.score, shelter)
    taste.save()
    print(f"{bp.name}: Xen predicted {args.score - error:.1f}, you said {args.score:.1f}. "
          f"Taste updated ({taste.ratings} ratings).")
    liked = {t: round(v, 2) for t, v in sorted(taste.tags.items(), key=lambda kv: -kv[1])}
    print(f"Style preferences: {liked}")


# ------------------------------------------------------------------- redstone
def cmd_redstone(args):
    from .redstone.learn import TASKS, Library, RedstoneLearner, describe
    library = Library(args.redstone)
    learner = RedstoneLearner(library, seed=args.seed)
    names = list(TASKS) if args.task == "all" else [args.task]
    for name in names:
        if name not in TASKS:
            sys.exit(f"Unknown task {name!r}. Tasks: {', '.join(TASKS)}, all")
        print(f"Task {name}: {TASKS[name].description}", flush=True)
        started = time.time()
        result = learner.solve(name, generations=args.generations)
        print(describe(result))
        print(f"  ({time.time() - started:.1f}s)\n", flush=True)
        if result.solved and args.out:
            path = args.out if len(names) == 1 else f"{os.path.splitext(args.out)[0]}_{name}.mcfunction"
            result.circuit.to_blueprint(name).to_mcfunction(path)
            print(f"  wrote {path}")
    library.save()
    print(f"Redstone knowledge saved to {args.redstone}")


def evaluate(xen, lives=6, ticks=1500, seed=1000):
    """Play without exploring on fixed worlds; returns a summary of skill and fear."""
    from . import blocks as B
    from .perception import DOWN, R
    from .worlds.simcraft import SimCraft
    rewards, deaths, causes, near, far = [], 0, {}, [], []
    for i in range(lives):
        world = SimCraft(max_steps=ticks, seed=seed + i)
        obs, done, total = world.reset(), False, 0.0
        body = xen.new_body()
        while not done:
            thought = xen.decide(obs, explore=False, emotions=body)
            cube = world.local_cube()
            danger = (cube[R - 2:R + 3, DOWN - 2:DOWN + 3, R - 2:R + 3] == B.LAVA).any()
            (near if danger else far).append(thought.feelings.fear)
            obs, reward, harm, done, info = world.step(thought.action)
            total += reward
            if info["terminal"]:
                deaths += 1
                cause = next((e[6:-1] for e in info["events"] if e.startswith("died")), "?")
                causes[cause] = causes.get(cause, 0) + 1
        rewards.append(total)
    mean = lambda v: sum(v) / len(v) if v else 0.0
    return {"reward": mean(rewards), "survived": lives - deaths, "lives": lives, "causes": causes,
            "fear_near_lava": mean(near), "fear_elsewhere": mean(far)}


def fear_probes(xen, worlds=12):
    """How much harm the amygdala expects in controlled situations (dangerous vs the same made safe)."""
    from . import blocks as B
    from .worlds.simcraft import Mob, SimCraft
    rows = []
    for seed in range(worlds):
        w = SimCraft(seed=seed)
        x, y, z = w.pos
        w.blocks[x - 5:x + 6, y:y + 6, z - 5:z + 6] = B.AIR
        w.blocks[x - 5:x + 6, y - 3:y, z - 5:z + 6] = B.STONE
        w.mobs, w.yaw, w.t = [], 2, 1
        fear = lambda: xen.fears(w.observe()[None])[0]
        ground = fear()[Action.FORWARD]
        w.blocks[x, y, z + 1] = w.blocks[x, y - 1, z + 1] = B.LAVA
        lava = fear()[Action.FORWARD]
        w.blocks[x, y, z + 1], w.blocks[x, y - 1, z + 1] = B.AIR, B.STONE
        w.pitch = -1
        stone = fear()[Action.MINE]
        w.blocks[x, y - 2, z] = B.LAVA
        dig_lava = fear()[Action.MINE]
        w.blocks[x, y - 2, z], w.pitch = B.STONE, 0
        calm = fear().mean()
        w.mobs = [Mob(x, y, z + 1)]
        zombie = fear().mean()
        rows.append((ground, lava, stone, dig_lava, calm, zombie))
    return [float(sum(r[i] for r in rows) / len(rows)) for i in range(6)]


def cmd_evaluate(args):
    from .worlds.simcraft import SimCraft
    minds = [("trained", _brain(args.brain, SimCraft.obs_dim, args.seed))]
    if args.baseline:
        minds.append(("newborn", Xen(SimCraft.obs_dim, NUM_ACTIONS, XenConfig(), seed=args.seed)))
    for label, xen in minds:
        r = evaluate(xen, lives=args.lives, ticks=args.ticks)
        causes = ", ".join(f"{k} x{v}" for k, v in r["causes"].items()) or "none"
        print(f"{label:8s} reward per life {r['reward']:6.2f} | survived {r['survived']}/{r['lives']} "
              f"(deaths: {causes})", flush=True)
        g, l, s, d, c, z = fear_probes(xen)
        print(f"         fear of walking into lava {l:.3f} vs onto ground {g:.3f} | digging down onto lava {d:.3f} "
              f"vs onto stone {s:.3f} | zombie in its face {z:.3f} vs nothing {c:.3f}", flush=True)


def cmd_talk(args):
    """Chat with Xen's voice, fed only what Xen perceives in a SimCraft world."""
    from .talk.voice import Voice, carrying, notes
    from .worlds.simcraft import SimCraft
    world = SimCraft(seed=args.seed)
    for _ in range(8):                                         # look around a little first
        world.step(Action.TURN_RIGHT if _ % 2 else Action.IDLE)
    context = notes("calm", False, world.health, world.hunger, carrying(world.inventory), world.senses.describe())
    print(f"Xen's notes: {context}")
    voice = Voice(path=args.model)
    for message in args.message or ["Xen, what do you see?"]:
        print(f"<{args.speaker}> {message}")
        print(f"<Xen> {voice.reply(args.speaker, message, context, seed=args.seed)}")


def cmd_export(args):
    """Write a brain in the mod's format (<world>/xen/brain.bin in Minecraft)."""
    from .worlds.simcraft import SimCraft
    xen = _brain(args.brain, SimCraft.obs_dim, args.seed)
    xen.export(args.out)
    print(f"Wrote {args.out}. Copy it to <world>/xen/brain.bin (with the server stopped) to use it in the mod.")


def cmd_info(args):
    print(f"Xen {__version__}")
    print(f"observation size: {OBS_DIM}, actions: {', '.join(a.name for a in Action)}")
    if args.brain and os.path.exists(args.brain):
        xen = Xen.load(args.brain)
        print(f"{args.brain}: {xen.steps} steps, {xen.updates} updates, {xen.lives} lives, "
              f"caution {xen.emotions.caution:.2f}")


def main(argv=None):
    from .building.templates import ARCH_STYLES, PALETTES, ROOF_STYLES
    parser = argparse.ArgumentParser(prog="xen", description="Xen: a decision-making model that plays Minecraft.")
    parser.add_argument("--seed", type=int, default=0)
    sub = parser.add_subparsers(dest="command", required=True)

    def knowledge(p):
        p.add_argument("--taste", default=TASTE_FILE, help="where Xen keeps its building taste")
        p.add_argument("--redstone", default=REDSTONE_FILE, help="where Xen keeps its redstone knowledge")
        p.add_argument("--build-mode", choices=("commands", "hands"), default="commands",
                       help="commands: /setblock (Xen needs op); hands: place blocks like a player (creative)")
        p.add_argument("--voice", action="store_true",
                       help="answer chat with Xen's voice (a 360M local language model; downloads ~390 MB once)")

    p = sub.add_parser("train", help="grow up in the SimCraft world")
    p.add_argument("--brain", default="xen_brain.npz")
    p.add_argument("--lives", type=int, default=100)
    p.add_argument("--ticks", type=int, default=1500, help="max ticks per life")
    p.add_argument("--fresh", action="store_true", help="ignore an existing brain file")
    p.set_defaults(func=cmd_train)

    p = sub.add_parser("watch", help="watch Xen live in SimCraft with its inner monologue")
    p.add_argument("--brain", default="xen_brain.npz")
    p.add_argument("--lives", type=int, default=1)
    p.add_argument("--ticks", type=int, default=1500)
    p.add_argument("--delay", type=float, default=0.08)
    p.add_argument("--learn", action="store_true", help="keep learning while watching")
    p.set_defaults(func=cmd_watch)

    p = sub.add_parser("play", help="play real Minecraft, learning continuously")
    p.add_argument("--world", choices=("mineflayer", "screen"), default="mineflayer",
                   help="mineflayer: a real player via bridge/xen_bridge.js; screen: real keyboard+mouse on your screen")
    p.add_argument("--brain", default=None)
    p.add_argument("--name", default=None, help="player name (mineflayer)")
    p.add_argument("--bridge-port", type=int, default=8765)
    p.add_argument("--speak", action="store_true", help="say feelings in game chat (mineflayer)")
    p.add_argument("--countdown", type=int, default=5, help="seconds to focus the Minecraft window (screen)")
    p.add_argument("--verbose", action="store_true", help="print every decision")
    knowledge(p)
    p.set_defaults(func=cmd_play)

    p = sub.add_parser("swarm", help="many Xen players on a server, one shared brain")
    p.add_argument("--count", type=int, default=0, help="how many Xens (0 = as many as the server allows)")
    p.add_argument("--prefix", default="Xen", help="player names: Xen, Xen_2, Xen_3, ...")
    p.add_argument("--spawn-every", type=float, default=5.0, help="seconds between new Xens joining")
    p.add_argument("--spread", type=int, default=0,
                   help="scatter each new Xen up to this many blocks away (/spreadplayers, needs op)")
    p.add_argument("--brain", default="xen_brain.npz")
    p.add_argument("--bridge-port", type=int, default=8765)
    p.add_argument("--speak", action="store_true", help="say feelings in game chat")
    knowledge(p)
    p.set_defaults(func=cmd_swarm)

    def build_args(p):
        p.add_argument("template", nargs="?", default="house", choices=("house", "tower", "bridge", "arch"))
        p.add_argument("--roof", default="gable", choices=ROOF_STYLES)
        p.add_argument("--palette", default="oak", choices=sorted(PALETTES))
        p.add_argument("--arch", default="round", choices=ARCH_STYLES)
        p.add_argument("--width", type=int, default=9)
        p.add_argument("--length", type=int, default=7)
        p.add_argument("--height", type=int, default=4, help="wall height (house) / arch height")
        p.add_argument("--overhang", type=int, default=1)
        p.add_argument("--porch", action="store_true")
        p.add_argument("--taste", default=TASTE_FILE)

    p = sub.add_parser("build", help="make a blueprint from a template (or let Xen design one) and rate it")
    build_args(p)
    p.add_argument("--design", action="store_true", help="let Xen design a house to its (your) taste")
    p.add_argument("--generations", type=int, default=15)
    p.add_argument("--out", help="write a .mcfunction to paste the build into a world")
    p.set_defaults(func=cmd_build)

    p = sub.add_parser("rate", help="see Xen's rating of a build, or give yours (--score) to teach its taste")
    build_args(p)
    p.add_argument("--score", type=float, help="your rating 0-10")
    p.set_defaults(func=cmd_rate)

    p = sub.add_parser("redstone", help="let Xen work out a redstone circuit")
    p.add_argument("task", nargs="?", default="all", help="not, or, and, nand, nor, xor, xnor, wire, ... or all")
    p.add_argument("--generations", type=int, default=150)
    p.add_argument("--redstone", default=REDSTONE_FILE)
    p.add_argument("--out", help="write the circuit as a .mcfunction")
    p.set_defaults(func=cmd_redstone)

    p = sub.add_parser("talk", help="talk with Xen's voice (downloads the 360M model on first use)")
    p.add_argument("message", nargs="*", help="what you say")
    p.add_argument("--speaker", default="You")
    p.add_argument("--model", default=None, help="path to a GGUF model (default ~/.xen/models)")
    p.set_defaults(func=cmd_talk)

    p = sub.add_parser("evaluate", help="measure what Xen has learned (no exploring, fixed worlds)")
    p.add_argument("--brain", default="xen_brain.npz")
    p.add_argument("--lives", type=int, default=6)
    p.add_argument("--ticks", type=int, default=1500)
    p.add_argument("--baseline", action="store_true", help="compare with a newborn Xen")
    p.set_defaults(func=cmd_evaluate)

    p = sub.add_parser("export", help="save a brain for the Minecraft mod (Xen Companion)")
    p.add_argument("--brain", default="xen_brain.npz")
    p.add_argument("--out", default="brain.bin")
    p.set_defaults(func=cmd_export)

    p = sub.add_parser("info", help="show details about Xen or a saved brain")
    p.add_argument("--brain", default="xen_brain.npz")
    p.set_defaults(func=cmd_info)

    args = parser.parse_args(argv)
    # Being stopped (kill, systemd, docker) is handled like Ctrl-C: save the brain, leave cleanly.
    signal.signal(signal.SIGTERM, _stop)
    args.func(args)


def _stop(signum, frame):
    raise KeyboardInterrupt
