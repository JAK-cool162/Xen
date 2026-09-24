"""Skills Xen can use on request in a real world, driven by players' chat.

    xen help
    xen build [house|tower|bridge|arch] [roof style] [palette]
    xen design                 Xen designs its best house (by its taste) and builds it
    xen rate <0-10>            tell Xen how much you like its last build; it learns your taste
    xen roofs | xen palettes   list the options
    xen redstone <task>        work out a circuit (not, or, and, nand, nor, wire, ...) and build it
    xen spawn <n>              (swarm) bring n more Xens into the world
    xen <anything else>        Xen answers with its voice (a small local language model that only
                               knows what Xen perceives), if talking is on
"""
from .building.designer import Designer, tags_for
from .building.rating import rate
from .building.taste import Taste
from .building.templates import PALETTES, ROOF_STYLES, HouseSpec, TEMPLATES, house
from .redstone.learn import TASKS, Library, RedstoneLearner
from .redstone.sim import truth_table


class Skills:
    def __init__(self, taste=None, library=None, build_mode="commands", voice=False):
        self.taste = taste or Taste()
        self.library = library or Library()
        self.designer = Designer(self.taste)
        self.redstone = RedstoneLearner(self.library)
        self.build_mode = build_mode
        self.last_build = None           # (rating, tags) awaiting feedback
        self.talk = voice
        self._voice = None

    def voice(self):
        if self._voice is None:
            from .talk.voice import Voice
            self._voice = Voice()
        return self._voice

    def blueprint(self, words):
        """Parse 'house gambrel spruce' / 'tower stone' / 'bridge' into (blueprint, tags, shelter)."""
        kind = next((w for w in words if w in TEMPLATES), "house")
        roof = next((w for w in words if w in ROOF_STYLES), None)
        palette = next((w for w in words if w in PALETTES), None)
        if kind == "house":
            liked_roof = self.taste.favourite("roof:")
            liked_palette = self.taste.favourite("palette:")
            spec = HouseSpec(roof=roof or (liked_roof[len("roof:"):] if liked_roof else "gable"),
                             palette=palette or (liked_palette[len("palette:"):] if liked_palette else "oak"))
            return house(spec), tags_for(spec), True
        kwargs = {"palette": palette} if palette else {}
        bp = TEMPLATES[kind](**kwargs)
        return bp, (f"template:{kind}",) + ((f"palette:{palette}",) if palette else ()), kind == "tower"

    def handle(self, text, body=None, swarm=None, speaker="Player"):
        """Run a chat command. Returns Xen's reply (or None if it isn't addressed to Xen)."""
        words = text.lower().replace(",", " ").split()
        if not words or words[0] not in ("xen", "!xen", "@xen"):
            if self.talk and "xen" in words:                       # talking about/to Xen
                return self.voice().reply(speaker, text, body.notes() if body is not None else "")
            return None
        cmd, args = (words[1], words[2:]) if len(words) > 1 else ("help", [])
        if cmd == "help":
            return "I can: build [house|tower|bridge|arch] [roof] [palette], design, rate <0-10>, roofs, palettes, redstone <task>, spawn <n>"
        if cmd == "roofs":
            return "Roofs: " + ", ".join(ROOF_STYLES)
        if cmd == "palettes":
            return "Palettes: " + ", ".join(PALETTES)
        if cmd in ("build", "design"):
            if cmd == "design":
                score, spec, rating, bp = self.designer.design(generations=10)
                tags, shelter = tags_for(spec), True
                what = f"a {spec.palette} house with a {spec.roof} roof ({spec.width}x{spec.length})"
            else:
                bp, tags, shelter = self.blueprint(args)
                rating = rate(bp, shelter=shelter)
                what = bp.name
            predicted = self.taste.predict(rating, tags, shelter)
            if body is not None:
                result = body.build(bp, mode=self.build_mode)
                done = f"placed {result.get('placed', 0)} blocks"
                if result.get("skipped"):
                    done += f", skipped {len(result['skipped'])}"
            else:
                done = f"{len(bp)} blocks planned"
            self.last_build = (rating, tags, shelter)
            return f"Building {what}: {done}. I rate it {predicted:.1f}/10. Tell me 'xen rate <0-10>'!"
        if cmd == "rate":
            if not self.last_build or not args:
                return "Rate what? Ask me to build something first, then 'xen rate 0-10'."
            try:
                score = max(0.0, min(10.0, float(args[0])))
            except ValueError:
                return "Give me a number from 0 to 10."
            rating, tags, shelter = self.last_build
            error = self.taste.learn(rating, tags, score, shelter)
            self.taste.save()
            feeling = "Glad you like it!" if score >= 7 else ("Noted, I'll do better." if score < 4 else "Thanks!")
            return f"{feeling} I thought {score - error:.1f}, you said {score:.0f}. Learning your taste ({self.taste.ratings} ratings so far)."
        if cmd == "redstone":
            name = args[0] if args else "and"
            if name not in TASKS:
                return "Redstone tasks: " + ", ".join(TASKS)
            result = self.redstone.solve(name)
            self.library.save()
            if not result.solved:
                return f"I'm still experimenting with {name} ({result.experiments} tries so far)."
            rows = "; ".join(f"{''.join(str(int(v)) for v in i)}->{int(o[0])}" for i, o, _ in truth_table(result.circuit))
            built = ""
            if body is not None:
                reply = body.build(result.circuit.to_blueprint(name), mode=self.build_mode)
                built = f" Built it ({reply.get('placed', 0)} blocks): levers on the left, lamp on the right."
            return f"{name.upper()} gate worked out: {result.circuit.components()} parts, truth table {rows}.{built}"
        if cmd == "spawn" and swarm is not None:
            n = int(args[0]) if args and args[0].isdigit() else 1
            swarm.target = None if swarm.target is None else swarm.target + n
            return f"Calling {n} more Xen{'s' if n != 1 else ''}!"
        if cmd in ("count", "status") and swarm is not None:
            return f"{len(swarm.bodies)} Xens online."
        if self.talk:
            return self.voice().reply(speaker, text, body.notes() if body is not None else "")
        return "Hm? Try 'xen help'."
