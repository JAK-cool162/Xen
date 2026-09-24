"""Xen's voice: a small local language model (SmolLM2-360M) that talks, but cannot cheat.

* It only knows what Xen knows: its notes are built from Xen's own senses and
  beliefs (what it has seen, with how sure it is), its feelings and its body.
* It only talks: the reply is one line of plain chat, never a command (a reply
  can't start with "/"), and it doesn't control what Xen does.
* It doesn't make things up about the world: an answer claiming to see
  something that isn't in its notes is not sent (it tries again, then just
  says what it knows).

Same prompt and rules as the Minecraft mod (mod/common/java/xen/mod/talk/Voice.java).
"""
import os
import re
import urllib.request

MODEL_NAME = "smollm2-360m-instruct-q8_0.gguf"
MODEL_URL = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/" + MODEL_NAME
MODEL_DIR = os.path.join(os.path.expanduser("~"), ".xen", "models")

PERSONA = (
    "<|im_start|>system\nYou are Xen, a survival companion in Minecraft. You play fair like a real player: "
    "you only know what you have seen yourself, and far-away things are only guesses. You feel fear, pain and "
    "curiosity. Answer as Xen in one or two short, friendly sentences. Only talk about things in your notes; if you "
    "don't know, say so. Never write commands.<|im_end|>\n"
    "<|im_start|>user\nNotes: You feel calm. You know there is a pumpkin 4 blocks from you.\n"
    "Steve says: what do you see?<|im_end|>\n"
    "<|im_start|>assistant\nI can see a pumpkin about 4 blocks from me.<|im_end|>\n"
    "<|im_start|>user\nNotes: You feel afraid. You are hurt. Nothing special is around you.\n"
    "Alex says: find any gold?<|im_end|>\n"
    "<|im_start|>assistant\nNo, I haven't seen any gold. And I'm hurt, so let's be careful.<|im_end|>\n")

THINGS = (("diamond",), ("gold",), ("iron",), ("coal",), ("lava",), ("water", "lake", "river", "ocean"),
          ("tree", "wood", "log"), ("zombie",), ("skeleton",), ("creeper",), ("spider",), ("enderman", "endermen"),
          ("village", "villager"), ("cave",), ("chest", "treasure"), ("mob", "monster"), ("emerald",), ("redstone",),
          ("lapis",), ("copper",), ("obsidian",), ("portal", "nether"), ("dungeon", "spawner"), ("temple", "ruin"),
          ("wolf", "wolves"), ("animal", "cow", "pig", "sheep", "chicken", "horse"), ("pumpkin", "melon"))
PLURAL = ("diamonds", "gold", "iron", "coal", "lava", "water", "trees", "zombies", "skeletons", "creepers", "spiders",
          "endermen", "villages", "caves", "chests", "mobs", "emeralds", "redstone", "lapis", "copper", "obsidian",
          "portals", "dungeons", "temples", "wolves", "animals", "pumpkins")
# Whole words, plurals too ("iron" is not in "environment").
_THING = [re.compile(r"\b(?:" + "|".join(words) + r")(?:s|es)?\b") for words in THINGS]
_CLAIM = re.compile(r"\b(see|saw|seen|spot|spotted|found|there'?s|there is|there are|near|nearby|close|away|here|"
                    r"over there|next to|ahead|behind)\b")
_NEGATION = re.compile(r"\b(no|not|n't|never|nothing|haven't|don't|can't|didn't|any)\b")


def model_path(download=True, log=print):
    """Where the 360M model lives; downloads it on first use (about 390 MB)."""
    path = os.environ.get("XEN_MODEL") or os.path.join(MODEL_DIR, MODEL_NAME)
    if os.path.exists(path) or not download:
        return path
    os.makedirs(os.path.dirname(path), exist_ok=True)
    log(f"Downloading Xen's voice ({MODEL_NAME}, ~390 MB) to {path} ...")
    tmp = path + ".part"
    urllib.request.urlretrieve(MODEL_URL, tmp)
    os.replace(tmp, path)
    return path


def safe_chat(text, limit=220):
    """One line of plain chat: no commands, no special tokens, at most two sentences."""
    text = re.sub(r"<\|[^|]*\|>", " ", text)
    text = " ".join(text.replace("\r", " ").replace("\n", " ").split())
    text = re.sub(r"^Xen\s*:\s*", "", text)
    text = text.lstrip("/\\ ")                          # a chat line starting with / would be a command
    text = " ".join(re.split(r"(?<=[.!?])\s+", text)[:2])
    if len(text) > limit:
        text = text[:limit].rsplit(" ", 1)[0] + "..."
    return text or "..."


def honest(reply, notes):
    """Drop sentences claiming things that aren't in Xen's notes. Being told about something isn't seeing it."""
    known = notes.lower()
    kept = []
    for sentence in re.split(r"(?<=[.!?])\s+", reply):
        s = sentence.lower()
        made_up = False
        if _CLAIM.search(s) and not _NEGATION.search(s):
            for thing in _THING:
                if thing.search(s) and not thing.search(known):
                    made_up = True
        if not made_up:
            kept.append(sentence)
    return " ".join(kept) if kept else "I'm not sure, I haven't seen that."


def plainly(notes, message=""):
    """Xen's notes in its own words: its answer when the model's answer can't be trusted."""
    sentences = [x for x in re.split(r"(?<=[.!?])\s+", notes.strip()) if x]
    first = [x for x in sentences[1:] if not x.startswith("You carry")][:1]
    asked, known = message.lower(), notes.lower()
    unseen = [PLURAL[i] for i, thing in enumerate(_THING) if thing.search(asked) and not thing.search(known)]
    text = " ".join(([f"I haven't seen any {unseen[0]}."] if unseen else sentences[:1]) + first)
    for pattern, words in ((r"\bYou are\b", "I'm"), (r"\bYou know there is\b", "I know there's"),
                           (r"\byou're\b", "I'm"), (r"\bYou\b", "I"), (r"\byou\b", "me")):
        text = re.sub(pattern, words, text)
    return text or "I'm not sure, I haven't seen that."


def notes(mood, hurt, health, hunger, carrying, perceived):
    """Xen's notes: its feelings, body and what it perceives, in plain words."""
    out = [f"You feel {mood}."]
    if hurt or health < 8:
        out.append("You are hurt.")
    if hunger < 8:
        out.append("You are hungry.")
    if carrying:
        out.append(f"You carry {carrying}.")
    out.append(perceived)
    return " ".join(out)


def carrying(inventory, limit=60):
    text = ""
    for item, n in inventory.items():
        if n and len(text) <= limit:
            text += (", " if text else "") + f"{n} {item.replace('_', ' ')}"
    return text


class Voice:
    def __init__(self, path=None, max_tokens=40, temperature=0.5, download=True):
        from .llm import LanguageModel
        self.model = LanguageModel(path or model_path(download))
        self.max_tokens = max_tokens
        self.temperature = temperature
        self._prefix = None

    def prompt(self, speaker, message, context):
        return (f"{PERSONA}<|im_start|>user\nNotes: {context}\n{speaker} says: {message}<|im_end|>\n"
                f"<|im_start|>assistant\n")

    def reply(self, speaker, message, context="", seed=None):
        """Its answer; if the model makes something up, it tries once more, then just says what it knows."""
        for attempt in range(2):
            text = safe_chat(self.model.generate(self.prompt(speaker, message, context), self.max_tokens,
                                                 self.temperature, seed=None if seed is None else seed + attempt))
            if honest(text, context) == text:
                return text
        return plainly(context, message)
