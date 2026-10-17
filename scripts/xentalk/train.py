"""Train XenTalk, Xen's own talk model, and write it for the mod.

    python scripts/xentalk/train.py [data/xentalk/pairs.jsonl] [--epochs 30]

A small decoder-only transformer (4 layers, 256 wide, 4 heads, about 4M numbers) that reads a line of facts and writes
the reply. Its pieces are whole words where it knows the word, letters where it doesn't (a name it's never seen is
copied letter by letter). Trained on the teacher's kept replies (scripts/xentalk/teach.py), the loss on the reply only;
5% of the situations are held out, and the loss on those is the number that counts. Written as bytes (one scale per
row) to mod/common/resources/assets/xen/xentalk.bin with its words in xentalk.json, and a fixture
(mod/common/test/fixtures/xentalk.json: some inputs and what this model makes of them, greedy) that the Java side
must agree with.
"""
import argparse
import json
import math
import os
import random
import re
import struct
from collections import Counter

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "mod", "common", "resources", "assets", "xen")
FIXTURE = os.path.join(ROOT, "mod", "common", "test", "fixtures", "xentalk.json")
PAD, BOS, SEP, EOS = 0, 1, 2, 3
SPECIAL = ["<pad>", "<bos>", "<sep>", "<eos>"]
CHARS = list("abcdefghijklmnopqrstuvwxyz0123456789 .,!?'-:;()/=|_&+%")
PIECE = re.compile(r"[a-z]+|[0-9]|[^a-z0-9]")
SEED = 20261017


def pieces(text):
    """Words (with the space before them) and single characters: the same split the Java side makes."""
    out = []
    for m in PIECE.finditer(text.lower()):
        out.append(m.group(0))
    merged, space = [], False
    for p in out:                                         # " word": a space is part of the word after it
        if p == " ":
            if space:
                merged.append(" ")
            space = True
            continue
        merged.append((" " + p) if space else p)
        space = False
    if space:
        merged.append(" ")
    return merged


class Vocab:
    def __init__(self, words):
        self.items = SPECIAL + CHARS + [" " + c for c in CHARS if c != " "] + words
        self.index = {w: i for i, w in enumerate(self.items)}

    def encode(self, text):
        ids = []
        q = self.index["?"]
        for p in pieces(text):
            if p in self.index:
                ids.append(self.index[p])
            elif p.startswith(" "):                          # (a word it doesn't know: " w", then letter by letter)
                ids.append(self.index.get(p[:2], q))
                ids.extend(self.index.get(ch, q) for ch in p[2:])
            else:
                ids.extend(self.index.get(ch, q) for ch in p)
        return ids

    def decode(self, ids):
        return "".join(self.items[i] for i in ids if i >= len(SPECIAL))


def build_vocab(texts, size):
    counts = Counter(p for t in texts for p in pieces(t) if len(p.strip()) > 1)
    words = [w for w, n in counts.most_common() if n >= 2]
    base = len(SPECIAL) + 2 * len(CHARS) - 1
    return Vocab(words[:max(0, size - base)])


class Block(nn.Module):
    def __init__(self, d, heads, ff):
        super().__init__()
        self.ln1, self.ln2 = nn.LayerNorm(d), nn.LayerNorm(d)
        self.qkv, self.out = nn.Linear(d, 3 * d), nn.Linear(d, d)
        self.ff1, self.ff2 = nn.Linear(d, ff), nn.Linear(ff, d)
        self.heads = heads

    def forward(self, x, mask):
        b, t, d = x.shape
        q, k, v = self.qkv(self.ln1(x)).split(d, dim=2)
        q, k, v = (z.view(b, t, self.heads, d // self.heads).transpose(1, 2) for z in (q, k, v))
        a = (q @ k.transpose(-2, -1)) / math.sqrt(d // self.heads)
        a = a.masked_fill(mask[:, :, :t, :t] == 0, float("-inf"))
        y = (F.softmax(a, dim=-1) @ v).transpose(1, 2).reshape(b, t, d)
        x = x + F.dropout(self.out(y), 0.2, self.training)
        return x + F.dropout(self.ff2(F.gelu(self.ff1(self.ln2(x)), approximate="tanh")), 0.2, self.training)


class XenTalk(nn.Module):
    def __init__(self, vocab, d=256, layers=4, heads=4, ff=1024, ctx=192):
        super().__init__()
        self.tok, self.pos = nn.Embedding(vocab, d), nn.Embedding(ctx, d)
        self.blocks = nn.ModuleList(Block(d, heads, ff) for _ in range(layers))
        self.ln = nn.LayerNorm(d)
        self.register_buffer("mask", torch.tril(torch.ones(ctx, ctx)).view(1, 1, ctx, ctx))
        self.ctx = ctx
        for name, p in self.named_parameters():                 # (small to start, like GPT-2: the word table is the output too)
            if name.endswith("bias"):
                nn.init.zeros_(p)
            elif p.dim() == 2:
                std = 0.02 / math.sqrt(2 * layers) if name.endswith(("out.weight", "ff2.weight")) else 0.02
                nn.init.normal_(p, std=std if not name.startswith("pos") else 0.01)

    def forward(self, ids):
        t = ids.shape[1]
        x = self.tok(ids) + self.pos(torch.arange(t, device=ids.device))
        for blk in self.blocks:
            x = blk(x, self.mask)
        return self.ln(x) @ self.tok.weight.T


KEEP = ("me", "tone", "to", "trust", "heard")
NAMES = ("me", "to", "friend")
MASK_CASES = ("me=Meelu|tone=bold|friend=bfnuf|to=Alex|trust=friend|heard=hey meelu, seen bfnuf? alexander says hi",
              "me=xLyzeyx|tone=shy|to=Milkghost941|trust=stranger|heard=milkghost941 here. xlyzeyx?",
              "me=Kai|tone=silly|time=day|doing=getting wood")


def masked(facts, reply=""):
    """Names out: a model this small can't spell a name back, so me, to and friend become the words xme, xto and
    xfriend in the facts, what was heard and the reply (XenTalk.java does the same and puts the names back)."""
    parts = [p.partition("=") for p in facts.split("|")]
    names = sorted(((v.lower(), "x" + k) for k, _, v in parts if k in NAMES and v), key=lambda n: -len(n[0]))

    def swap(text):
        for name, word in names:
            text = re.sub(r"(?<![a-z0-9])" + re.escape(name) + r"(?![a-z0-9])", word, text, flags=re.I)
        return text

    out = "|".join(k + "=" + ("x" + k if k in NAMES and v else swap(v) if k == "heard" else v) for k, _, v in parts)
    return out, swap(reply)


def thin(facts, reply, rng):
    """The same situation with a few facts it didn't use left out (never one whose words the reply has)."""
    words = set(re.findall(r"[a-z]+", reply))
    parts = facts.split("|")
    out = []
    for p in parts:
        k, _, v = p.partition("=")
        if k not in KEEP and not (set(re.findall(r"[a-z]+", v)) & words) and rng.random() < 0.35:
            continue
        out.append(p)
    return "|".join(out)


def sequences(rows, vocab, ctx, rng=None):
    out = []
    for r in rows:
        facts = thin(r["facts"], r["reply"], rng) if rng else r["facts"]
        f, y = vocab.encode(facts), vocab.encode(r["reply"])
        f = f[:ctx - len(y) - 3]
        out.append(([BOS] + f + [SEP] + y + [EOS], len(f) + 2))
    return out


def batches(seqs, size, rng):
    order = list(range(len(seqs)))
    rng.shuffle(order)
    for i in range(0, len(order), size):
        chunk = [seqs[j] for j in order[i:i + size]]
        t = max(len(s) for s, _ in chunk)
        x = torch.full((len(chunk), t), PAD, dtype=torch.long)
        y = torch.full((len(chunk), t), -100, dtype=torch.long)
        for k, (s, start) in enumerate(chunk):
            x[k, :len(s)] = torch.tensor(s)
            y[k, start - 1:len(s) - 1] = torch.tensor(s[start:])    # (loss on the reply and its end only)
        yield x, y


def loss_on(model, seqs):
    model.eval()
    total, n = 0.0, 0
    with torch.no_grad():
        for x, y in batches(seqs, 64, random.Random(0)):
            logits = model(x)
            l = F.cross_entropy(logits.reshape(-1, logits.shape[-1]), y.reshape(-1), ignore_index=-100, reduction="sum")
            total += l.item()
            n += (y != -100).sum().item()
    model.train()
    return total / max(1, n)


PENALTY = 1.3


_GLUE = {}


def glued(vocab):
    """Pieces that are a word with no space before it, and pieces that end in a letter or digit (kept per vocab)."""
    if id(vocab) not in _GLUE:
        _GLUE[id(vocab)] = (torch.tensor([bool(re.fullmatch(r"[a-z]+", w)) for w in vocab.items]),
                            [bool(re.search(r"[a-z0-9]$", w)) for w in vocab.items])
    return _GLUE[id(vocab)]


def penalise(logits, out, vocab=None):
    """What it already said is less likely again (x1.3), no three pieces in a row twice, and no spelling: a word
    starts after a space or a mark (the "m" of "i'm"), never straight after a letter. The same as the Java side."""
    for t in set(out):
        logits[t] = logits[t] / PENALTY if logits[t] > 0 else logits[t] * PENALTY
    if len(out) >= 2:
        a, b = out[-2], out[-1]
        for i in range(len(out) - 2):
            if out[i] == a and out[i + 1] == b:
                logits[out[i + 2]] = float("-inf")
    if vocab is not None:
        glue, ends = glued(vocab)
        if out and ends[out[-1]]:
            logits[glue] = float("-inf")
        if len(out) >= 2 and vocab.items[out[-1]] == "'":                # after "i'" only m, ll, ve, d: the endings the teacher used
            ok = getattr(vocab, "tails", {}).get(vocab.items[out[-2]].strip(), ())
            keep = torch.tensor([w in ok for w in vocab.items])
            logits[~keep if ok else glue] = float("-inf")               # (nothing else after it, not even a word: "i' from")
        for w in (" xme", "xme"):                                       # (never its own name: it was saying it to others)
            if w in vocab.index:
                logits[vocab.index[w]] = float("-inf")
    return logits


def contractions(rows):
    """What may follow a word and "'": the endings the teacher wrote after it ("i" -> m, ll, ve, d; "don" -> t)."""
    tails = {}
    for r in rows:
        for m in re.finditer(r"([a-z]+)'([a-z]+)", r["reply"]):
            tails.setdefault(m.group(1), set()).add(m.group(2))
    return {w: sorted(t) for w, t in sorted(tails.items())}


def greedy(model, vocab, facts, n=30):
    ids = [BOS] + vocab.encode(facts)[:model.ctx - n - 2] + [SEP]
    out = []
    model.eval()
    with torch.no_grad():
        for _ in range(n):
            logits = penalise(model(torch.tensor([ids]))[0, -1].clone(), out, vocab)
            nxt = int(logits.argmax())
            if nxt == EOS:
                break
            out.append(nxt)
            ids.append(nxt)
    return out


def sample(model, vocab, facts, gen, temperature=0.8, top_k=30, n=28):
    ids = [BOS] + vocab.encode(facts)[:model.ctx - n - 2] + [SEP]
    out = []
    with torch.no_grad():
        for _ in range(n):
            logits = penalise(model(torch.tensor([ids]))[0, -1].clone(), out, vocab) / temperature
            v, ix = torch.topk(logits, top_k)
            nxt = int(ix[torch.multinomial(F.softmax(v, dim=-1), 1, generator=gen)])
            if nxt == EOS:
                break
            out.append(nxt)
            ids.append(nxt)
    return vocab.decode(out).strip()


def judge(model, vocab, held, k=200):
    """Sampled replies to held-out situations, as the mod makes them: how many stick to the facts, how long, how varied."""
    import sys as _s
    _s.path.insert(0, os.path.dirname(__file__))
    from teach import grounded
    gen = torch.Generator().manual_seed(SEED)
    model.eval()
    outs = [(r, sample(model, vocab, r["facts"], gen)) for r in held[:k]]
    ok = sum(1 for r, t in outs if t and grounded(t, dict(kv.split("=", 1) for kv in r["facts"].split("|") if "=" in kv)))
    words = [len(t.split()) for _, t in outs]
    return ok / max(1, len(outs)), sum(words) / max(1, len(words)), len(set(t for _, t in outs)) / max(1, len(outs)), outs


def made_up(vocab, text):
    """Words in a line that aren't words it knows (spelt out letter by letter)."""
    known = {w.strip() for w in vocab.items if re.fullmatch(r" ?[a-z]+", w)}
    return [w for w in re.findall(r"[a-z]+", text) if len(w) > 1 and w not in known]


def q8(w):
    """Rows as bytes, one scale each."""
    w = w.detach().float().numpy()
    scale = np.maximum(np.abs(w).max(axis=1), 1e-8) / 127
    return np.round(w / scale[:, None]).astype(np.int8), scale.astype(np.float32)


def export(model, vocab, cfg, path):
    with open(path, "wb") as f:
        f.write(struct.pack("<8i", 0x58544B31, len(vocab.items), cfg["d"], cfg["layers"], cfg["heads"], cfg["ff"], cfg["ctx"], 0))

        def mat(w):                                         # (out rows x in): bytes then scales
            b, s = q8(w)
            f.write(struct.pack("<2i", *b.shape))
            f.write(b.tobytes())
            f.write(s.tobytes())

        def vec(v):
            v = v.detach().float().numpy()
            f.write(struct.pack("<i", v.shape[0]))
            f.write(v.astype("<f4").tobytes())
        mat(model.tok.weight)
        mat(model.pos.weight)
        for b in model.blocks:
            vec(b.ln1.weight); vec(b.ln1.bias)
            mat(b.qkv.weight); vec(b.qkv.bias)
            mat(b.out.weight); vec(b.out.bias)
            vec(b.ln2.weight); vec(b.ln2.bias)
            mat(b.ff1.weight); vec(b.ff1.bias)
            mat(b.ff2.weight); vec(b.ff2.bias)
        vec(model.ln.weight); vec(model.ln.bias)


def load(path, pieces):
    """The model as the jar has it (int8 rows times their scales), from xentalk.bin."""
    with open(path, "rb") as f:
        magic, n, d, layers, heads, ff, ctx, _ = struct.unpack("<8i", f.read(32))
        assert magic == 0x58544B31 and n == len(pieces)
        model = XenTalk(n, d=d, layers=layers, heads=heads, ff=ff, ctx=ctx)

        def mat(w):
            r, c = struct.unpack("<2i", f.read(8))
            b = np.frombuffer(f.read(r * c), dtype=np.int8).reshape(r, c).astype(np.float32)
            sc = np.frombuffer(f.read(4 * r), dtype="<f4")
            w.copy_(torch.from_numpy(b * sc[:, None]))

        def vec(v):
            k, = struct.unpack("<i", f.read(4))
            v.copy_(torch.from_numpy(np.frombuffer(f.read(4 * k), dtype="<f4").copy()))
        with torch.no_grad():
            mat(model.tok.weight)
            mat(model.pos.weight)
            for b in model.blocks:
                vec(b.ln1.weight); vec(b.ln1.bias)
                mat(b.qkv.weight); vec(b.qkv.bias)
                mat(b.out.weight); vec(b.out.bias)
                vec(b.ln2.weight); vec(b.ln2.bias)
                mat(b.ff1.weight); vec(b.ff1.bias)
                mat(b.ff2.weight); vec(b.ff2.bias)
            vec(model.ln.weight); vec(model.ln.bias)
    model.eval()
    return model


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("data", nargs="?", default=os.path.join(ROOT, "data", "xentalk", "pairs.jsonl"))
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--vocab", type=int, default=3000)
    ap.add_argument("--again", action="store_true", help="no training: the fixture and the scores again from the jar's xentalk.bin")
    a = ap.parse_args(argv)
    torch.manual_seed(SEED)
    rng = random.Random(SEED)
    rows = [json.loads(l) for l in open(a.data, encoding="utf-8") if l.strip()]
    for r in rows:
        r["facts"], r["reply"] = masked(r["facts"], r["reply"].lower().strip())
    counts = Counter(w for r in rows for w in set(re.findall(r"[a-z]+", r["reply"])))
    before = len(rows)
    rows = [r for r in rows if all(counts[w] > 2 or w in r["facts"] for w in re.findall(r"[a-z]+", r["reply"]))]   # (a rare word not in its facts: made up)
    print(f"{before} replies, {before - len(rows)} dropped for a rare word that isn't in their facts")
    rng.shuffle(rows)
    held = rows[:max(1, len(rows) // 20)]
    train = rows[len(held):]
    if a.again:
        with open(os.path.join(ASSETS, "xentalk.json")) as f:
            meta = json.load(f)
        vocab = Vocab([])
        vocab.items = meta["pieces"]
        vocab.index = {w: i for i, w in enumerate(vocab.items)}
        vocab.tails = contractions(train)
        meta["contractions"] = vocab.tails
        with open(os.path.join(ASSETS, "xentalk.json"), "w") as f:
            json.dump(meta, f)
        model = load(os.path.join(ASSETS, "xentalk.bin"), vocab.items)
        return finish(model, vocab, held, meta["held_out_loss"])
    vocab = build_vocab([r["facts"] for r in train] + [r["reply"] for r in train], a.vocab)
    vocab.tails = contractions(train)
    cfg = {"d": 192, "layers": 4, "heads": 4, "ff": 768, "ctx": 192}
    model = XenTalk(len(vocab.items), **cfg)
    n_params = sum(p.numel() for p in model.parameters())
    tr, he = sequences(train, vocab, cfg["ctx"]), sequences(held, vocab, cfg["ctx"])
    opt = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=0.05)
    steps = a.epochs * math.ceil(len(tr) / 32)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, 1e-3, total_steps=steps, pct_start=0.05)
    best, best_state = 1e9, None
    print(f"{len(train)} training, {len(held)} held out, {len(vocab.items)} pieces, {n_params / 1e6:.2f}M numbers")
    for ep in range(a.epochs):
        tr = sequences(train, vocab, cfg["ctx"], rng) if ep % 2 else sequences(train, vocab, cfg["ctx"])   # (every other epoch, some unused facts left out)
        for x, y in batches(tr, 32, rng):
            logits = model(x)
            loss = F.cross_entropy(logits.reshape(-1, logits.shape[-1]), y.reshape(-1), ignore_index=-100)
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            sched.step()
        h = loss_on(model, he)
        if h < best:
            best, best_state = h, {k: v.clone() for k, v in model.state_dict().items()}
        print(f"epoch {ep + 1}: train {loss.item():.3f}, held out {h:.3f} (perplexity {math.exp(h):.1f})", flush=True)
    model.load_state_dict(best_state)
    export(model, vocab, cfg, os.path.join(ASSETS, "xentalk.bin"))
    with open(os.path.join(ASSETS, "xentalk.json"), "w") as f:
        json.dump({"about": "XenTalk, Xen's own talk model (scripts/xentalk/train.py); pieces: words with the space before them, then letters",
                   "pieces": vocab.items, "held_out_loss": round(best, 4), "examples": len(train), "contractions": vocab.tails}, f)
    with torch.no_grad():                                   # (the fixture from the model as the jar has it: bytes and scales)
        for name, w in model.state_dict().items():
            if w.dim() == 2:
                b, sc = q8(w)
                w.copy_(torch.from_numpy(b.astype(np.float32) * sc[:, None]))
    finish(model, vocab, held, best)


def finish(model, vocab, held, best):
    """The crossCheck fixture (greedy, from the model as the jar has it) and how it does on the held-out situations."""
    cases = []
    for r in held[:12]:
        ids = greedy(model, vocab, r["facts"])
        cases.append({"facts": r["facts"], "ids": ids, "text": vocab.decode(ids), "teacher": r["reply"]})
    os.makedirs(os.path.dirname(FIXTURE), exist_ok=True)
    names = [masked(raw, "")[0] for raw in MASK_CASES]
    with open(FIXTURE, "w") as f:
        json.dump({"cases": cases, "masked": [{"raw": raw, "masked": m} for raw, m in zip(MASK_CASES, names)]}, f, indent=1)
    for c in cases[:8]:
        print("  facts:", c["facts"][-90:], "\n    xentalk:", c["text"], "\n    teacher:", c["teacher"])
    g, wl, distinct, outs = judge(model, vocab, held)
    odd = sum(1 for _, t in outs if made_up(vocab, t))
    print(f"sampled on {min(200, len(held))} held-out situations: {100 * g:.0f}% stick to the facts, {wl:.1f} words, {100 * distinct:.0f}% different, "
          f"{100 * odd / max(1, len(outs)):.0f}% with a made-up word")
    for r, t in outs[:10]:
        f = dict(kv.split("=", 1) for kv in r["facts"].split("|") if "=" in kv)
        print(f"  [{f.get('tone')}] {('heard: ' + f['heard']) if 'heard' in f else r['kind']} -> {t}")
    print(f"held out loss {best:.3f} (perplexity {math.exp(best):.1f}); xentalk.bin is {os.path.getsize(os.path.join(ASSETS, 'xentalk.bin')) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
