"""A tiny language model runtime: loads a GGUF file (Llama architecture, e.g.
SmolLM2-360M-Instruct, Q8_0 or F16/F32 weights) and generates text with numpy.

No PyTorch, no llama.cpp: a GGUF reader, the GPT-2 style byte-level BPE
tokenizer stored in the file, and the transformer (RMSNorm, rotary positions,
grouped-query attention, SwiGLU).
"""
import math
import re
import struct

import numpy as np

# ---------------------------------------------------------------------------- GGUF
_SCALAR = {0: "B", 1: "b", 2: "H", 3: "h", 4: "I", 5: "i", 6: "f", 7: "?", 10: "Q", 11: "q", 12: "d"}
F32, F16, Q8_0 = 0, 1, 8


class GGUF:
    """Reads the metadata and tensors of a .gguf file (memory-mapped)."""

    def __init__(self, path):
        self.path = path
        with open(path, "rb") as f:
            self._f = f
            magic, version, n_tensors, n_kv = struct.unpack("<4sIQQ", f.read(24))
            if magic != b"GGUF" or version < 2:
                raise ValueError(f"{path} is not a GGUF v2+ file")
            self.meta = {}
            for _ in range(n_kv):
                key = self._string()
                (kind,) = struct.unpack("<I", f.read(4))
                self.meta[key] = self._value(kind)
            self.tensors = {}
            for _ in range(n_tensors):
                name = self._string()
                (dims,) = struct.unpack("<I", f.read(4))
                shape = struct.unpack("<" + "Q" * dims, f.read(8 * dims))
                kind, offset = struct.unpack("<IQ", f.read(12))
                self.tensors[name] = (shape, kind, offset)
            align = self.meta.get("general.alignment", 32)
            self.data_start = (f.tell() + align - 1) // align * align
        del self._f
        self.raw = np.memmap(path, np.uint8, mode="r")

    def _string(self):
        (n,) = struct.unpack("<Q", self._f.read(8))
        return self._f.read(n).decode("utf-8", "replace")

    def _value(self, kind):
        if kind == 8:
            return self._string()
        if kind == 9:
            item, n = struct.unpack("<IQ", self._f.read(12))
            return [self._value(item) for _ in range(n)]
        fmt = "<" + _SCALAR[kind]
        return struct.unpack(fmt, self._f.read(struct.calcsize(fmt)))[0]

    def tensor(self, name):
        """A tensor as float32, shaped like PyTorch: (rows, row_length)."""
        shape, kind, offset = self.tensors[name]
        count = int(np.prod(shape))
        start = self.data_start + offset
        if kind == F32:
            values = self.raw[start:start + 4 * count].view(np.float32).astype(np.float32)
        elif kind == F16:
            values = self.raw[start:start + 2 * count].view(np.float16).astype(np.float32)
        elif kind == Q8_0:
            blocks = self.raw[start:start + 34 * (count // 32)].reshape(-1, 34)
            scale = blocks[:, :2].copy().view(np.float16).astype(np.float32)
            values = (blocks[:, 2:].view(np.int8).astype(np.float32) * scale).ravel()
        else:
            raise ValueError(f"tensor type {kind} of {name} is not supported (use Q8_0, F16 or F32)")
        return values.reshape(tuple(reversed(shape))) if len(shape) > 1 else values


# ------------------------------------------------------------------------ tokenizer
def _bytes_to_unicode():
    bs = list(range(ord("!"), ord("~") + 1)) + list(range(ord("¡"), ord("¬") + 1)) + list(range(ord("®"), ord("ÿ") + 1))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return dict(zip(bs, map(chr, cs)))


# GPT-2 pre-tokenization (letters, numbers, punctuation runs, spaces); SmolLM also splits digits one by one.
_PIECES = re.compile(r"""'s|'t|'re|'ve|'m|'ll|'d| ?[^\W\d_]+| ?\d| ?(?:[^\s\w]|_)+|\s+(?!\S)|\s+""")


class Tokenizer:
    def __init__(self, meta):
        self.tokens = meta["tokenizer.ggml.tokens"]
        self.ids = {t: i for i, t in enumerate(self.tokens)}
        self.ranks = {tuple(m.split(" ", 1)): i for i, m in enumerate(meta["tokenizer.ggml.merges"])}
        types = meta.get("tokenizer.ggml.token_type", [1] * len(self.tokens))
        self.special = sorted((t for t, k in zip(self.tokens, types) if k in (3, 4) and t), key=len, reverse=True)
        self.eos = meta.get("tokenizer.ggml.eos_token_id", 2)
        self.byte_enc = _bytes_to_unicode()
        self.byte_dec = {v: k for k, v in self.byte_enc.items()}
        self._special_re = re.compile("(" + "|".join(map(re.escape, self.special)) + ")") if self.special else None
        self._cache = {}

    def _bpe(self, word):
        if word in self._cache:
            return self._cache[word]
        parts = list(word)
        while len(parts) > 1:
            pairs = [(self.ranks.get((a, b), 1 << 30), i) for i, (a, b) in enumerate(zip(parts, parts[1:]))]
            rank, i = min(pairs)
            if rank == 1 << 30:
                break
            parts[i:i + 2] = [parts[i] + parts[i + 1]]
        ids = [self.ids[p] for p in parts if p in self.ids]
        self._cache[word] = ids
        return ids

    def encode(self, text):
        out = []
        chunks = self._special_re.split(text) if self._special_re else [text]
        for chunk in chunks:
            if not chunk:
                continue
            if chunk in self.ids and chunk in self.special:
                out.append(self.ids[chunk])
                continue
            for piece in _PIECES.findall(chunk):
                out += self._bpe("".join(self.byte_enc[b] for b in piece.encode("utf-8")))
        return out

    def decode(self, ids):
        text = "".join(self.tokens[i] for i in ids if self.tokens[i] not in self.special)
        return bytes(self.byte_dec.get(c, ord("?")) for c in text).decode("utf-8", "replace")


# ------------------------------------------------------------------------- the model
def _rms(x, w, eps):
    return x / np.sqrt(np.mean(x * x, axis=-1, keepdims=True) + eps) * w


class LanguageModel:
    def __init__(self, path):
        g = GGUF(path)
        m = g.meta
        arch = m.get("general.architecture", "llama")
        if arch != "llama":
            raise ValueError(f"only Llama-architecture models are supported, not {arch}")
        self.tokenizer = Tokenizer(m)
        self.n_layers = m["llama.block_count"]
        self.dim = m["llama.embedding_length"]
        self.n_heads = m["llama.attention.head_count"]
        self.n_kv = m.get("llama.attention.head_count_kv", self.n_heads)
        self.head_dim = self.dim // self.n_heads
        self.eps = m.get("llama.attention.layer_norm_rms_epsilon", 1e-5)
        self.rope_base = m.get("llama.rope.freq_base", 10000.0)
        self.context = min(m.get("llama.context_length", 2048), 4096)
        self.embed = g.tensor("token_embd.weight")
        self.output = g.tensor("output.weight") if "output.weight" in g.tensors else self.embed
        self.norm = g.tensor("output_norm.weight")
        self.layers = []
        for i in range(self.n_layers):
            t = lambda n: g.tensor(f"blk.{i}.{n}.weight")
            self.layers.append({"attn_norm": t("attn_norm"), "q": t("attn_q"), "k": t("attn_k"), "v": t("attn_v"),
                                "o": t("attn_output"), "ffn_norm": t("ffn_norm"), "gate": t("ffn_gate"),
                                "up": t("ffn_up"), "down": t("ffn_down")})
        half = self.head_dim // 2
        self.inv_freq = self.rope_base ** (-np.arange(half) * 2.0 / self.head_dim)
        self.reset()

    def reset(self):
        self.cache = [(np.zeros((0, self.n_kv, self.head_dim), np.float32),) * 2 for _ in range(self.n_layers)]
        self.pos = 0

    def keep_prefix(self, text):
        """Remember the model's state after this text; prompts that start with it skip re-reading it."""
        self.reset()
        self.forward(self.tokenizer.encode(text))
        self._prefixes = getattr(self, "_prefixes", {})
        self._prefixes[text] = (list(self.cache), self.pos)

    def _read(self, prompt, room):
        """Start a prompt (from a kept prefix when there is one); returns the logits after it."""
        for text, (cache, pos) in getattr(self, "_prefixes", {}).items():
            if prompt.startswith(text) and len(prompt) > len(text):
                self.cache, self.pos = list(cache), pos
                return self.forward(self.tokenizer.encode(prompt[len(text):])[-(self.context - pos - room):])
        self.reset()
        return self.forward(self.tokenizer.encode(prompt)[-(self.context - room):])

    def _rope(self, x, positions):
        # Pairs of neighbouring dimensions rotate together (the GGUF/llama.cpp layout).
        angles = positions[:, None] * self.inv_freq[None, :]
        cos, sin = np.cos(angles)[:, None, :], np.sin(angles)[:, None, :]
        even, odd = x[..., 0::2], x[..., 1::2]
        out = np.empty_like(x)
        out[..., 0::2] = even * cos - odd * sin
        out[..., 1::2] = even * sin + odd * cos
        return out

    def forward(self, ids):
        """Feed tokens (continuing the cache); returns logits of the last one."""
        ids = np.asarray(ids)
        n = len(ids)
        positions = np.arange(self.pos, self.pos + n, dtype=np.float64)
        x = self.embed[ids]
        group = self.n_heads // self.n_kv
        for i, L in enumerate(self.layers):
            h = _rms(x, L["attn_norm"], self.eps)
            q = self._rope((h @ L["q"].T).reshape(n, self.n_heads, self.head_dim), positions)
            k = self._rope((h @ L["k"].T).reshape(n, self.n_kv, self.head_dim), positions)
            v = (h @ L["v"].T).reshape(n, self.n_kv, self.head_dim)
            ck, cv = self.cache[i]
            ck, cv = np.concatenate([ck, k]), np.concatenate([cv, v])
            self.cache[i] = (ck, cv)
            total = len(ck)
            kk = np.repeat(ck, group, axis=1)                     # (total, heads, hd)
            vv = np.repeat(cv, group, axis=1)
            scores = np.einsum("nhd,thd->hnt", q, kk) / math.sqrt(self.head_dim)
            mask = np.arange(total)[None, :] > (self.pos + np.arange(n))[:, None]
            scores = np.where(mask[None], -1e30, scores)
            scores = np.exp(scores - scores.max(-1, keepdims=True))
            scores /= scores.sum(-1, keepdims=True)
            attn = np.einsum("hnt,thd->nhd", scores, vv).reshape(n, self.dim)
            x = x + attn @ L["o"].T
            h = _rms(x, L["ffn_norm"], self.eps)
            gate = h @ L["gate"].T
            x = x + ((gate / (1 + np.exp(-gate))) * (h @ L["up"].T)) @ L["down"].T
        self.pos += n
        return _rms(x[-1], self.norm, self.eps) @ self.output.T

    def generate(self, prompt, max_tokens=60, temperature=0.7, top_p=0.9, seed=None, stop=("<|im_end|>",)):
        rng = np.random.default_rng(seed)
        logits = self._read(prompt, max_tokens)
        stop_ids = {self.tokenizer.ids[s] for s in stop if s in self.tokenizer.ids} | {self.tokenizer.eos}
        out = []
        for _ in range(max_tokens):
            token = _sample(logits, temperature, top_p, rng)
            if token in stop_ids:
                break
            out.append(token)
            logits = self.forward([token])
        return self.tokenizer.decode(out)


    def choose(self, prompt, options):
        """Which option the model finds likeliest to come next (log-probabilities of each, whole option)."""
        logits = self._read(prompt, 8)
        cache, pos = list(self.cache), self.pos
        scores = []
        for option in options:
            ids, l, total = self.tokenizer.encode(option), logits, 0.0
            for j, token in enumerate(ids):
                total += float(l[token] - l.max() - np.log(np.exp(l - l.max()).sum()))
                if j + 1 < len(ids):
                    l = self.forward([token])
            self.cache, self.pos = list(cache), pos
            scores.append(total)
        return int(np.argmax(scores)), scores


def _sample(logits, temperature, top_p, rng):
    if temperature <= 0:
        return int(np.argmax(logits))
    z = (logits - logits.max()) / temperature
    p = np.exp(z)
    p /= p.sum()
    order = np.argsort(-p)
    keep = order[: int(np.searchsorted(np.cumsum(p[order]), top_p)) + 1]
    q = p[keep] / p[keep].sum()
    return int(rng.choice(keep, p=q))
