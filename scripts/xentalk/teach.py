"""XenTalk's teacher: example replies for real Xen situations, written by a bigger open model (Apache-2.0), kept only
if they stick to the facts.

    python scripts/xentalk/teach.py FACTS.txt [--n 6000] [--server http://127.0.0.1:8080] [--out data/xentalk/pairs.jsonl]

FACTS.txt is what Xens wrote about themselves in a game (-Dxen.dumpFacts=true: the world's xen/talkfacts.txt). Each
situation gets someone to talk to (a player or another Xen, a friend or a stranger) and something they said (lines
players really type, from data/talk/corpus.txt), or nothing (it starts the chat, or says something to itself). Some
situations are moved on a little (later in the game: iron, diamonds; night; hurt) so the model sees more than a first
day. The teacher (any OpenAI-style server: llama.cpp's llama-server with Qwen2.5-1.5B-Instruct here) writes the reply
in the Xen's tone; a reply is kept only if it's short, sounds like a player and names nothing (an item, a mob, a place,
a number) that isn't in the facts or what was said.
"""
import argparse
import json
import os
import random
import re
import sys
import threading
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))

TONES = {
    "cheerful": "upbeat and friendly, uses ! and sometimes lol",
    "calm": "relaxed and plain, polite, never shouts",
    "grumpy": "short and blunt, a bit annoyed, never rude for no reason",
    "shy": "quiet and hesitant, says um, trails off with ...",
    "bold": "confident, brags a little, likes a challenge",
    "silly": "goofy and playful, makes little jokes",
}

# Things a reply mustn't name unless the facts (or what was said) do.
THINGS = """diamond diamonds iron gold emerald emeralds coal redstone lapis netherite copper quartz amethyst wood log logs plank planks stone
cobble cobblestone dirt sand gravel obsidian clay glass wool zombie zombies skeleton skeletons creeper creepers spider spiders enderman
endermen witch slime blaze ghast piglin piglins villager villagers golem cow cows pig pigs sheep chicken chickens horse wolf cat fish
nether end dragon village temple stronghold mineshaft portal cave caves ravine ocean desert jungle forest mountain swamp sword pickaxe
axe shovel hoe bow arrow arrows shield armor helmet chestplate boots elytra bed chest furnace torch torches bucket lava water wheat
bread apple apples steak beef pork porkchop carrot carrots potato potatoes house base farm mine trident totem enchant enchanting
anvil beacon""".split()
NUMBER = re.compile(r"\b\d+\b")
BAD = re.compile(r"\b(as an ai|assistant|language model|i'm here to help|how can i (help|assist)|feel free|let me know|happy to help|facts?|mood|lately|personality|chat message)\b|[#*\"]|:\)|[^\x00-\x7f]", re.I)


def heard_lines():
    """Lines players type, from the talk corpus (not the kinds another part of Xen answers: requests, recipes)."""
    out, skip = [], False
    for line in open(os.path.join(ROOT, "data", "talk", "corpus.txt"), encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("## "):
            skip = line.endswith(" pass")
        elif line.startswith("> ") and not skip:
            out.append(line[2:].strip())
    return out


def parse(facts):
    return dict(kv.split("=", 1) for kv in facts.split("|") if "=" in kv)


def unparse(f):
    order = ["me", "tone", "mood", "feel", "time", "danger", "doing", "why", "next", "has", "saw", "lately", "dream", "friend", "to", "trust", "heard"]
    return "|".join(f"{k}={f[k]}" for k in order if f.get(k))


def later(f, rng):
    """The same Xen a little later in the game (more stuff, a different time, a scrape)."""
    f = dict(f)
    if rng.random() < 0.5:
        pick = rng.choice(["a stone pickaxe", "an iron pickaxe", "an iron pickaxe", "a diamond pickaxe"])
        stuff = [pick] + [f"{rng.randint(1, 40)} {k}" for k in rng.sample(["logs", "cobblestone", "coal", "iron", "diamonds"], rng.randint(1, 4))]
        stuff.append(rng.choice(["no food", f"{rng.randint(1, 20)} food"]))
        if rng.random() < 0.5:
            stuff.append("a bed")
        f["has"] = ", ".join(stuff)
    if rng.random() < 0.3:
        f["time"] = "night" if f.get("time") == "day" else "day"
    if rng.random() < 0.2:
        f["feel"] = rng.choice(["hurt", "hungry", "hurt,hungry"])
        f["mood"] = rng.choice(["hurt", "uneasy", "afraid"])
    if rng.random() < 0.15:
        f["danger"] = rng.choice(["some", "high"])
    return f


def situations(facts_file, n, seed):
    rng = random.Random(seed)
    base = [parse(l.strip()) for l in open(facts_file, encoding="utf-8") if l.strip()]
    heard = heard_lines()
    names = ["Steve", "Alex", "Kai", "mia_mc", "xXDarkXx", "Noodle", "Bob", "lena", "Rexa", "Tomi", "Juno", "Pixel"]
    out = []
    for _ in range(n):
        f = dict(rng.choice(base))
        if rng.random() < 0.5:
            f = later(f, rng)
        r = rng.random()
        if r < 0.15:
            kind = "remark"
        else:
            f["to"] = rng.choice([x for x in names if x != f.get("me")])
            f["trust"] = rng.choices(["stranger", "friend", "best friend", "enemy"], [4, 4, 2, 1])[0]
            kind = "opener" if r < 0.3 else "reply"
            if kind == "reply":
                f["heard"] = rng.choice(heard).lower()
        out.append((kind, f))
    return out


def prompt(kind, f):
    tone = f.get("tone", "calm")
    sys_msg = ("You write one Minecraft chat message as a player (you are told who, and their personality). "
               "Type like real players in game chat: short (3 to 12 words), casual, lowercase, no emojis, no quotes. "
               "Answer what was said. Use the facts, in your own words, but don't list them. Never mention an item, block, mob, place "
               "or number that isn't in the facts. You are a player, not an assistant. Output only the message.\n\n"
               "Examples of the style:\n"
               "(chopping a tree, 4 logs; a friend says: hey whatcha doing) just chopping wood, need more logs lol\n"
               "(night, hiding in its dugout, uneasy; someone says: wanna go mining?) nah its night, staying in my hole till morning\n"
               "(an iron pickaxe, 3 diamonds; a friend says: find anything good?) yeah 3 diamonds!! the iron pick paid off\n"
               "(bored, digging down for stone; a stranger says: hi) hey. kinda busy digging\n"
               "(no food, hungry; starting a chat) yo you got any food? im starving\n"
               "(saw a village, wants to explore; to nobody) ok that village looked cool, going back later")
    facts = "\n".join(f"- {k}: {v}" for k, v in f.items() if k not in ("heard", "to", "trust", "me", "tone"))
    if kind == "remark":
        ask = "Say something out loud to nobody in particular, about what you're doing or how you feel."
    elif kind == "opener":
        ask = f"You walk up to {f['to']} ({f['trust']}). Start a chat with them."
    else:
        ask = f"{f['to']} ({f['trust']}) says to you: {f['heard']}\nReply to them."
    who = f"You are {f.get('me', 'Xen')}. Personality: {TONES.get(tone, TONES['calm'])}."
    return [{"role": "system", "content": sys_msg}, {"role": "user", "content": f"{who}\nFacts about you right now:\n{facts}\n\n{ask}"}]


def grounded(reply, f):
    """Nothing in the reply that the facts (or what was said) don't have: things, numbers."""
    known = " ".join(f.values()).lower()
    words = re.findall(r"[a-z]+", reply.lower())
    for w in words:
        if w in THINGS and w not in known and w.rstrip("s") not in known:
            return False
    for num in NUMBER.findall(reply):
        if num not in known:
            return False
    return True


def ok(reply, f):
    reply = reply.strip().strip('"').strip()
    if not reply or len(reply) > 80 or len(reply.split()) > 15 or "\n" in reply or BAD.search(reply):
        return None
    if reply.lower().startswith(("reply:", "message:", f.get("me", "?").lower() + ":")):
        return None
    return reply if grounded(reply, f) else None


def ask_teacher(server, messages, temperature):
    body = json.dumps({"messages": messages, "temperature": temperature, "top_p": 0.95, "max_tokens": 40, "cache_prompt": True}).encode()
    req = urllib.request.Request(server + "/v1/chat/completions", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())["choices"][0]["message"]["content"]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("facts")
    ap.add_argument("--n", type=int, default=6000)
    ap.add_argument("--server", default="http://127.0.0.1:8080")
    ap.add_argument("--out", default=os.path.join(ROOT, "data", "xentalk", "pairs.jsonl"))
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--seed", type=int, default=20261017)
    a = ap.parse_args(argv)
    sits = situations(a.facts, a.n, a.seed)
    done = 0
    if os.path.exists(a.out):                                  # (carries on where it stopped)
        done = sum(1 for _ in open(a.out, encoding="utf-8"))
    lock = threading.Lock()
    kept = [0]
    tried = [0]
    out = open(a.out, "a", encoding="utf-8")

    def one(i):
        kind, f = sits[i]
        for attempt in range(2):
            try:
                text = ask_teacher(a.server, prompt(kind, f), 0.8 + 0.2 * attempt)
            except Exception as e:                              # (the server busy or gone: skip it)
                print("teacher:", e, file=sys.stderr)
                return
            reply = ok(text, f)
            with lock:
                tried[0] += 1
            if reply:
                with lock:
                    out.write(json.dumps({"kind": kind, "facts": unparse(f), "reply": reply}) + "\n")
                    out.flush()
                    kept[0] += 1
                    if kept[0] % 100 == 0:
                        print(f"{kept[0]} kept of {tried[0]} tried", flush=True)
                return

    with ThreadPoolExecutor(a.threads) as ex:
        list(ex.map(one, range(done, len(sits))))
    print(f"done: {kept[0]} kept of {tried[0]} tried -> {a.out}")


if __name__ == "__main__":
    main()
