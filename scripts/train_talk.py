"""Train Xen's talk model (Banter): what kind of thing a player just said, from how players really type.

    python scripts/train_talk.py            # train, measure on the held-out lines, write the mod's files

Reads data/talk/corpus.txt (the kinds of lines, many ways to say each, and the answers) and data/talk/heldout.txt
(lines it never trains on: how well it does on those is the number that counts). Each training line is also typed
the ways players type it (lowercase, "u" for "you", "ur", "wanna", typos, a name in front, "lol" or "bro" after,
stretched letters...), so it reads chat it has never seen.

The model: every line becomes features (its words, word pairs, and the letter triples of each word: "heyyy" and
"hey" share "<he", "hey"), hashed into 32768 rows of a 64-number embedding; the line is the mean of its rows; one
layer on top says which kind of line it is. Trained with Adam; the embedding is kept as bytes (one scale per row).

Writes mod/common/resources/assets/xen/talk.json (the kinds and their answers), talk.bin (the model), and
mod/common/test/fixtures/talk.json (lines and what this model makes of them: the Java side must agree).
"""
import json
import os
import random
import re
import struct
import sys
import unicodedata

import numpy as np

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
CORPUS = os.path.join(ROOT, "data", "talk", "corpus.txt")
HELDOUT = os.path.join(ROOT, "data", "talk", os.environ.get("HELD", "heldout.txt"))
ASSETS = os.path.join(ROOT, "mod", "common", "resources", "assets", "xen")
FIXTURE = os.path.join(ROOT, "mod", "common", "test", "fixtures", "talk.json")

BUCKETS = 1 << 15
DIM = 64
SEED = 20261013

# ------------------------------------------------------------------------------------------- reading the corpus
SLOTS = {"player", "me", "doing", "mood", "pick", "death", "place", "dream", "friend", "biome", "did", "day", "answer", "xyz"}
COUNTS = {"iron", "diamond", "wood", "stone", "food", "coal", "gold", "emerald"}
FAVS = {"block", "food", "mob", "color", "biome", "weapon", "thing"}
FLAGS = {"pick", "nopick", "night", "day", "hurt", "hungry", "fine", "busy", "idle", "home", "nohome", "died", "place", "friend",
         "dream", "did", "underground"}
TONES = {"cheerful", "calm", "grumpy", "shy", "bold", "silly"}
REPLY = re.compile(r"^(?:<([a-z,]+)>\s*)?-\s*(.*)$")


def parse(path):
    """The corpus: {kind: {"pass": bool, "heard": [...], "replies": [{"text", "need", "tones", "ask"}]}} in order."""
    kinds, cur = {}, None
    for n, raw in enumerate(open(path, encoding="utf-8"), 1):
        line = raw.rstrip("\n")
        if not line.strip() or line.startswith("#") and not line.startswith("## "):
            continue
        if line.startswith("## "):
            parts = line[3:].split()
            cur = parts[0]
            if cur in kinds:
                sys.exit(f"{path}:{n}: {cur} twice")
            kinds[cur] = {"pass": "pass" in parts[1:], "heard": [], "replies": []}
            continue
        if cur is None:
            sys.exit(f"{path}:{n}: before any ## kind")
        if line.startswith("> "):
            kinds[cur]["heard"].append(line[2:].strip())
            continue
        m = REPLY.match(line)
        if not m:
            sys.exit(f"{path}:{n}: not a heard line (> ...) or an answer (- ...): {line}")
        tones = [t for t in (m.group(1) or "").split(",") if t]
        text = m.group(2).strip()
        need, ask = [], None
        while True:
            t = re.match(r"^\[([a-z:]+)\]\s*", text)
            if not t:
                break
            need.append(t.group(1))
            text = text[t.end():]
        a = re.search(r"\s*\(ask:([a-z_]+)\)\s*$", text)
        if a:
            ask = a.group(1)
            text = text[:a.start()]
        for tone in tones:
            if tone not in TONES:
                sys.exit(f"{path}:{n}: unknown tone {tone}")
        for need_ in need:
            k, _, v = need_.partition(":")
            if not (need_ in FLAGS or k in ("has", "no") and v in COUNTS):
                sys.exit(f"{path}:{n}: unknown condition [{need_}]")
        for slot in re.findall(r"\{([a-z:]+)\}", text):
            k, _, v = slot.partition(":")
            if not (slot in SLOTS or k == "n" and v in COUNTS or k == "fav" and v in FAVS):
                sys.exit(f"{path}:{n}: unknown word to fill in {{{slot}}}")
        if "[" in text or "<" in text:
            sys.exit(f"{path}:{n}: a tag in the middle of an answer: {text}")
        kinds[cur]["replies"].append({"text": text, "need": need, "tones": tones, "ask": ask})
    for k, v in kinds.items():
        if not k.startswith("answer:") and not v["heard"]:
            sys.exit(f"{k}: no heard lines")
    return kinds


# ------------------------------------------------------------------------------------------- reading a line
def normalize(text, name=""):
    """Lowercase; its own name is "@x"; letters stretched past two kept at two ("heyyyy" is "heyy")."""
    t = text.lower()
    if name:
        t = re.sub(r"(?<![0-9a-z_])" + re.escape(name.lower()) + r"(?![0-9a-z_])", " @x ", t)
    t = re.sub(r"(.)\1{2,}", r"\1\1", t)
    return t


def tokens(text, name=""):
    """Words (letters and digits; ' and ’ dropped inside), "@x", "?" and "!" (once each), and emoji/symbols one by one."""
    t = normalize(text, name)
    out, word, q, bang = [], [], False, False
    i = 0
    chars = list(t)
    while i < len(chars):
        ch = chars[i]
        if ch == "@" and i + 1 < len(chars) and chars[i + 1] == "x" and (i + 2 >= len(chars) or not chars[i + 2].isalnum()):
            if word:
                out.append("".join(word))
                word = []
            out.append("@x")
            i += 2
            continue
        if ch.isalnum():
            word.append(ch)
        elif ch in "'’" and word:
            pass
        else:
            if word:
                out.append("".join(word))
                word = []
            if ch == "?" and not q:
                out.append("?")
                q = True
            elif ch == "!" and not bang:
                out.append("!")
                bang = True
            elif ord(ch) >= 0x2190 and ch not in "️‍" and not ch.isspace():
                out.append(ch)
        i += 1
    if word:
        out.append("".join(word))
    return out


def features(text, name=""):
    toks = tokens(text, name)
    f = []
    for w in toks:
        f.append("w|" + w)
        if len(w) >= 2 and w != "@x" and w[0].isalnum():
            p = "<" + w + ">"
            for i in range(len(p) - 2):
                f.append("c|" + p[i:i + 3])
    seq = ["<s>"] + toks + ["</s>"]
    for a, b in zip(seq, seq[1:]):
        f.append("b|" + a + " " + b)
    if not toks:
        f.append("w|<empty>")
    return f


def fnv(s):
    h = 0x811C9DC5
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def ids(text, name=""):
    return [fnv(x) & (BUCKETS - 1) for x in features(text, name)]


# ------------------------------------------------------------------------------------------- typing it like players
SHORT = [(r"\byou\b", "u"), (r"\byour\b", "ur"), (r"\byou're\b", "ur"), (r"\byoure\b", "ur"), (r"\bare\b", "r"), (r"\bwhat's\b", "whats"),
         (r"\bwhat\b", "wat"), (r"\bokay\b", "ok"), (r"\bgoing to\b", "gonna"), (r"\bwant to\b", "wanna"), (r"\bbecause\b", "cuz"),
         (r"\bplease\b", "pls"), (r"\bthough\b", "tho"), (r"\breally\b", "rly"), (r"\bdon't\b", "dont"), (r"\bi'm\b", "im"),
         (r"\bthanks\b", "thx"), (r"\bthat's\b", "thats"), (r"\bi\b", "i"), (r"\bthe\b", "da"), (r"\bwhy\b", "y"), (r"\bfor\b", "4"),
         (r"\bto\b", "2"), (r"\bsee\b", "c"), (r"\bbe\b", "b"), (r"\bok\b", "k"), (r"\bdoing\b", "doin"), (r"\bnothing\b", "nothin"),
         (r"\bwith\b", "w"), (r"\bfavorite\b", "fav"), (r"\bfavourite\b", "fav"), (r"\bminecraft\b", "mc"), (r"\bdiamonds\b", "dias"),
         (r"\bseriously\b", "srsly"), (r"\bpeople\b", "ppl"), (r"\bsomething\b", "smth"), (r"\bbrother\b", "bro")]
BEFORE = ["bro ", "yo ", "hey ", "ok ", "so ", "dude ", "lol ", "omg ", "man ", "wait ", "ngl ", "bruh ", "um ", "and "]
AFTER = [" lol", " bro", " lmao", " ngl", " fr", " tho", " man", " dude", " ?", "!", "!!", " 😭", " 💀", " xd", " haha", " rn", " pls",
         " :)", " :(", " ...", "??"]
NAMES = ["{X}", "steve", "alex", "notch", "pip", "kai", "luna", "max", "sam", "zed"]


def typo(w, r):
    if len(w) < 4 or not w.isalpha():
        return w
    i = r.randrange(1, len(w) - 1)
    k = r.random()
    if k < 0.35:
        return w[:i] + w[i + 1] + w[i] + w[i + 2:]           # swapped
    if k < 0.65:
        return w[:i] + w[i + 1:]                               # dropped
    if k < 0.85:
        return w[:i] + w[i] + w[i:]                            # doubled
    return w + w[-1] * r.randint(2, 4)                          # stretched ("heyyy")


def variants(line, r, n):
    """The line as n players might type it (its own name as {X}: filled with the name it's called)."""
    out = [line, line.lower()]
    for _ in range(n):
        s = line.lower() if r.random() < 0.85 else line.upper()
        for pat, rep in SHORT:
            if re.search(pat, s) and r.random() < 0.35:
                s = re.sub(pat, rep, s)
        if r.random() < 0.25:
            ws = s.split()
            if ws:
                j = r.randrange(len(ws))
                ws[j] = typo(ws[j], r)
                s = " ".join(ws)
        if r.random() < 0.3:
            s = re.sub(r"[?.!,]", "", s)
        if "{X}" not in s and r.random() < 0.25:
            s = ("{X} " + s) if r.random() < 0.6 else (s + " {X}")
        if r.random() < 0.25:
            s = r.choice(BEFORE) + s
        if r.random() < 0.3:
            s = s + r.choice(AFTER)
        if r.random() < 0.08:
            s = s.replace("{X}", r.choice(NAMES[1:]))
        out.append(s)
    return out


def fill(s, r):
    return s.replace("{X}", r.choice(["@x", "@x", "@x", ""])).replace("{x}", "@x").strip() or "@x"


# ------------------------------------------------------------------------------------------- the model
class Model:
    def __init__(self, k, seed=SEED):
        g = np.random.default_rng(seed)
        self.E = (g.standard_normal((BUCKETS, DIM)) * 0.1).astype(np.float32)
        self.W = (g.standard_normal((k, DIM)) * (1 / np.sqrt(DIM))).astype(np.float32)
        self.b = np.zeros(k, np.float32)

    def embed(self, rows):
        return self.E[rows].mean(0)

    def logits(self, rows):
        return self.W @ self.embed(rows) + self.b

    def probs(self, rows):
        z = self.logits(rows)
        z = np.exp(z - z.max())
        return z / z.sum()


def train(model, data, epochs, lr=0.01, batch=64, seed=SEED):
    """Adam on mean-of-rows -> softmax, minibatches; sparse updates for the embedding (only the rows a batch used)."""
    r = random.Random(seed)
    params = {"W": model.W, "b": model.b}
    m = {k: np.zeros_like(v) for k, v in params.items()}
    v = {k: np.zeros_like(v) for k, v in params.items()}
    mE, vE = np.zeros_like(model.E), np.zeros_like(model.E)
    t = 0
    b1, b2, eps = 0.9, 0.999, 1e-8
    for e in range(epochs):
        r.shuffle(data)
        loss = 0.0
        for s in range(0, len(data), batch):
            chunk = data[s:s + batch]
            t += 1
            gW = np.zeros_like(model.W)
            gb = np.zeros_like(model.b)
            gE = {}
            for rows, y in chunk:
                h = model.E[rows].mean(0)
                z = model.W @ h + model.b
                z = np.exp(z - z.max())
                p = z / z.sum()
                loss -= np.log(p[y] + 1e-9)
                d = p.copy()
                d[y] -= 1
                gW += np.outer(d, h)
                gb += d
                dh = (model.W.T @ d) / len(rows)
                for row in rows:
                    if row in gE:
                        gE[row] += dh
                    else:
                        gE[row] = dh.copy()
            n = len(chunk)
            for k, g in (("W", gW / n + 1e-5 * model.W), ("b", gb / n)):
                m[k] = b1 * m[k] + (1 - b1) * g
                v[k] = b2 * v[k] + (1 - b2) * g * g
                params[k] -= lr * (m[k] / (1 - b1 ** t)) / (np.sqrt(v[k] / (1 - b2 ** t)) + eps)
            rows = np.fromiter(gE.keys(), np.int64)
            g = np.stack([gE[int(x)] for x in rows]) / n
            mE[rows] = b1 * mE[rows] + (1 - b1) * g
            vE[rows] = b2 * vE[rows] + (1 - b2) * g * g
            model.E[rows] -= lr * (mE[rows] / (1 - b1 ** t)) / (np.sqrt(vE[rows] / (1 - b2 ** t)) + eps)
        print(f"  epoch {e + 1}/{epochs}: loss {loss / len(data):.3f}", flush=True)


def quantize(model):
    """The embedding as bytes, a scale per row (what ships), and the model as it then is (what's measured)."""
    scale = np.abs(model.E).max(1) / 127.0
    scale[scale == 0] = 1e-8
    q = np.clip(np.round(model.E / scale[:, None]), -127, 127).astype(np.int8)
    model.E = (q.astype(np.float32) * scale[:, None]).astype(np.float32)
    return q, scale.astype(np.float32)


def main():
    kinds = parse(CORPUS)
    names = [k for k in kinds if not k.startswith("answer:")]
    index = {k: i for i, k in enumerate(names)}
    r = random.Random(SEED)
    seen = {}
    for k in names:
        for h in kinds[k]["heard"]:
            key = " ".join(tokens(h.replace("{X}", "")))
            if key in seen and seen[key] != k:
                print(f"note: \"{h}\" is in both {seen[key]} and {k}")
            seen[key] = k
    data = []
    for k in names:
        lines = kinds[k]["heard"]
        per = max(6, 260 // max(1, len(lines)))                          # (every kind about as many examples in all)
        for h in lines:
            for s in variants(h, r, per):
                data.append((np.array(ids(fill(s, r)), np.int64), index[k]))
    print(f"{len(names)} kinds, {sum(len(kinds[k]['heard']) for k in names)} lines, {len(data)} typed ways, "
          f"{sum(len(v['replies']) for v in kinds.values())} answers")
    model = Model(len(names))
    train(model, data, epochs=int(os.environ.get("EPOCHS", "8")))
    q, scale = quantize(model)

    held = []
    for raw in open(HELDOUT, encoding="utf-8"):
        if raw.startswith("#") or not raw.strip():
            continue
        k, text = raw.rstrip("\n").split("\t", 1)
        if k not in index:
            sys.exit(f"held out: unknown kind {k}")
        held.append((k, text))
    right, wrong = 0, []
    for k, text in held:
        p = model.probs(np.array(ids(fill(text, r)), np.int64))
        got = names[int(p.argmax())]
        if got == k:
            right += 1
        else:
            wrong.append(f"    {text!r}: {got} ({p.max():.2f}), not {k}")
    print(f"held out: {right}/{len(held)} right ({100 * right / len(held):.0f}%)")
    for w in wrong:
        print(w)

    os.makedirs(ASSETS, exist_ok=True)
    lib = {"about": "Xen's talk: the kinds of lines players say and how a Xen answers (built from data/talk/corpus.txt by "
                    "scripts/train_talk.py; the model is talk.bin)",
           "buckets": BUCKETS, "dim": DIM, "kinds": names,
           "pass": [k for k in names if kinds[k]["pass"]],
           "replies": {k: v["replies"] for k, v in kinds.items() if v["replies"]}}
    with open(os.path.join(ASSETS, "talk.json"), "w", encoding="utf-8") as f:
        json.dump(lib, f, ensure_ascii=False, indent=0)
    with open(os.path.join(ASSETS, "talk.bin"), "wb") as f:
        f.write(struct.pack(">iiiii", 0x58544C4B, 1, BUCKETS, DIM, len(names)))     # "XTLK"
        f.write(scale.astype(">f4").tobytes())
        f.write(q.tobytes())
        f.write(model.W.astype(">f4").tobytes())
        f.write(model.b.astype(">f4").tobytes())
    cases = []
    for k, text in held[:60] + [("", "yo Pip wyd"), ("", "PIP U GOT IRON??"), ("", "heyyyy pip 😭"), ("", "what's up, pip!")]:
        rows = ids(text, "pip")
        p = model.probs(np.array(rows, np.int64))
        top = int(p.argmax())
        cases.append({"text": text, "tokens": tokens(text, "pip"), "ids": rows, "top": names[top], "p": round(float(p[top]), 5)})
    with open(FIXTURE, "w", encoding="utf-8") as f:
        json.dump({"name": "pip", "cases": cases}, f, ensure_ascii=False, indent=0)
    size = os.path.getsize(os.path.join(ASSETS, "talk.bin"))
    print(f"wrote talk.json, talk.bin ({size // 1024} KB) and the fixture")


if __name__ == "__main__":
    main()
