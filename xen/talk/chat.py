"""Xen's chat: it understands what players ask and answers, but cannot cheat.

* It understands requests ("Xen, get some wood", "follow me", "build a
  shelter") as one of a few things it can do. A small local chat model
  (SmolLM2-360M) picks which one; without the model, keyword rules do.
  What it then does, it does with its own hands and its own senses.
* It only knows what Xen knows: its notes are built from Xen's own senses and
  beliefs (what it has seen, with how sure it is), its feelings and its body.
* Its answers are one line of plain chat, never a command (a reply can't
  start with "/").
* It doesn't make things up about the world: an answer claiming to see
  something that isn't in its notes is not sent (it tries again, then just
  says what it knows).

Same prompts and rules as the Minecraft mod (mod/common/java/xen/mod/talk/Chat.java).
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
    "<|im_start|>assistant\nNo, I haven't seen any gold. And I'm hurt, so let's be careful.<|im_end|>\n"
    "<|im_start|>user\nNotes: You feel happy. You carry 12 cobblestone. You know there is water 5 blocks from you.\n"
    "Steve says: thanks for the help!<|im_end|>\n"
    "<|im_start|>assistant\nAnytime! That was fun.<|im_end|>\n")

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
    log(f"Downloading Xen's chat model ({MODEL_NAME}, ~390 MB) to {path} ...")
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


_FIRST_PERSON = ((r"\bYou are\b", "I'm"), (r"\b[Yy]ou were\b", "I was"), (r"\bYou know there is\b", "I know there's"), (r"\b[Yy]ou will\b", "I'll"),
                 (r"\byou won't\b", "I won't"), (r"\byou're\b", "I'm"), (r"\byou are\b", "I'm"), (r"\b(so|and|but|because|if|when) you\b", r"\1 I"),
                 (r"\b(tree|block|ore|lava|water|mob|mobs|it) you\b", r"\1 I"), (r"\byou (need|have|know|saw|see)\b", r"I \1"),
                 (r"\byourself\b", "myself"), (r"\bYour\b", "My"), (r"\byour\b", "my"), (r"\bYou\b", "I"), (r"\byou\b", "me"))


_PLAN = re.compile(r"\bPlan: (.+)$")
# Talk can't make it do things (only requests do), so it mustn't promise to.
_PROMISE = re.compile(r"\b(i'll|i will|i'm going to|let me|i can) (go |go and )?(get|build|chop|mine|dig|find|bring|give|"
                      r"follow|hunt|make|collect|gather|fetch|craft|check|look|explore|kill)\b")


def first_person(text):
    for pattern, words in _FIRST_PERSON:
        text = re.sub(pattern, words, text)
    return text


_GREET = re.compile(r"\b(hi|hello|hey|yo|sup|hiya|howdy|good (morning|evening|afternoon))\b|สวัสดี")
_THANKS = re.compile(r"\b(thanks|thank you|thx|ty)\b|ขอบคุณ")
_FEEL = re.compile(r"\b(how are you|how do you feel|are you ok|you ok)\b")
_LOOK = re.compile(r"\b(see|around|near|nearby|found|where|any|anything)\b")
_SENSED = re.compile(r"^You (know|saw|see|hear|think)\b")
NOT_UNDERSTOOD = ("Sorry, I didn't get that. You can ask me to follow you, stay, get wood or stone, hunt, build a shelter, "
                  "or trade.")


TOLD = "What your owner told you about yourself:"
_WORD = re.compile(r"[a-z]{3,}")
_COMMON = {"the", "and", "you", "your", "are", "was", "were", "what", "who", "how", "why", "when", "where", "which", "that", "this",
           "with", "for", "not", "but", "have", "has", "had", "does", "did", "can", "could", "would", "should", "like", "about", "any",
           "anything", "some", "there", "they", "them", "their", "yes", "yeah", "please", "tell", "know", "think", "really", "very",
           "much", "more", "from", "into", "out", "all", "just", "too", "also", "see", "around", "near", "nearby", "found", "doing",
           "favorite", "favourite", "love", "hate", "want", "thing", "things"}


def _key_words(text):
    """The words in a text that say what it's about ("cats" -> "cat")."""
    out = []
    for w in _WORD.findall(text.lower()):
        if w in _COMMON:
            continue
        w = w[:-1] if len(w) > 3 and w.endswith("s") else w
        if w not in out:
            out.append(w)
    return out


def told_about(notes, message):
    """The sentence of what its owner told it about itself that fits what was asked, in its own words (or None)."""
    at = notes.find(TOLD)
    if at < 0:
        return None
    asked = set(_key_words(message))
    if not asked:
        return None
    best, most = None, 0
    for x in re.split(r"(?<=[.!?])\s+", notes[at + len(TOLD):].strip()):
        n = len(set(_key_words(x)) & asked)
        if n > most:
            best, most = x, n
    return None if best is None else first_person(best)


def plainly(notes, message=""):
    """Xen's notes in its own words: its answer when the model's answer can't be trusted (or there's no model)."""
    plan = _PLAN.search(notes.strip())
    if plan:
        return ("Okay! " if plan.group(1).startswith("You will") else "") + first_person(plan.group(1))
    sentences = [x for x in re.split(r"(?<=[.!?])\s+", notes.strip()) if x]
    first = [x for x in sentences[1:] if not x.startswith("You carry")][:1]
    asked, known = message.lower(), notes.lower()
    unseen = [PLURAL[i] for i, thing in enumerate(_THING) if thing.search(asked) and not thing.search(known)]
    if unseen or not asked.strip():                            # nothing asked: what it feels and knows
        text = first_person(" ".join(([f"I haven't seen any {unseen[0]}."] if unseen else sentences[:1]) + first))
        return text or "I'm not sure, I haven't seen that."
    for thing in _THING:                                       # asked about something it knows: that
        if thing.search(asked):
            about = [x for x in sentences if thing.search(x.lower())]
            if about:
                return first_person(about[0])
    told = told_about(notes, asked)                            # what its owner told it about itself
    if told is not None:
        return told
    mood = first_person(sentences[0]) if sentences else ""
    if _THANKS.search(asked):
        return "You're welcome!"
    if _GREET.search(asked):
        return ("Hi! " + mood).strip()
    if _FEEL.search(asked):
        return mood or "I'm fine."
    if _LOOK.search(asked):
        sensed = [x for x in sentences if _SENSED.match(x)]
        return first_person(sensed[0]) if sensed else "I don't see anything special."
    return NOT_UNDERSTOOD


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


# ------------------------------------------------------------------------------ requests
# What Xen can be asked to do. The chat model picks one of these words; without it, the rules below do.
INTENTS = ("follow", "stay", "explore", "wood", "stone", "coal", "iron", "mine", "food", "give", "shelter", "eat",
           "stop", "redstone", "trade", "chat", "peace", "pickup")
# Things it can be asked to craft (the recipe is worked out from what it carries: "boat" is an oak boat with oak planks).
CRAFTABLE = ("crafting table", "pressure plate", "boats?", "chests?", "tables?", "furnaces?", "doors?", "torch(es)?", "sticks?",
             "planks?", "beds?", "ladders?", "fences?", "bowls?", "shields?", "buckets?", "pickaxes?", "swords?", "axes?", "shovels?",
             "hoes?", "signs?", "trapdoors?", "slabs?", "stairs", "buttons?", "barrels?", "campfires?", "bread")
_CRAFT_THING = re.compile(r"\b((wooden|wood|stone|iron|golden|gold|diamond) )?(" + "|".join(CRAFTABLE) + r")\b")
_CRAFT_WORD = re.compile(r"\bcraft(ing)? (me |us )?(a |an |some |the |\d+ )*([a-z_]+)")
AMOUNT = {"wood": 8, "stone": 16, "coal": 8, "iron": 4, "mine": 8, "food": 3}
# Requests in Thai: words to look for (Thai has no spaces between words), the first that matches wins.
THAI = (("peace", ("สงบศึก", "ขอโทษ", "ยอมแพ้", "ไม่สู้แล้ว")), ("chat", ("ขอบคุณ",)), ("trade", ("แลก", "เทรด", "ซื้อ", "ขาย")), ("stop", ("หยุด", "พอแล้ว", "ยกเลิก")),
        ("stay", ("ไม่ต้องตาม", "รอ", "อยู่ตรงนี้", "อยู่นี่")), ("follow", ("ตาม", "มานี่", "มาทางนี้", "มาหา")),
        ("give", ("ขอ", "ส่ง")), ("explore", ("สำรวจ", "ไปเที่ยว", "ไปเล่น")), ("redstone", ("เรดสโตน", "วงจร")),
        ("wood", ("ไม้",)), ("coal", ("ถ่าน",)), ("iron", ("เหล็ก",)), ("stone", ("หิน",)), ("mine", ("ขุด", "แร่", "เพชร", "ทอง")),
        ("food", ("อาหาร", "ล่า", "หาของกิน")), ("build", ("สร้างบ้าน", "บ้านใต้ดิน", "ฐานใต้ดิน", "ฟาร์ม", "คอก")), ("shelter", ("บ้าน", "ที่หลบ", "ที่พัก", "สร้าง")), ("eat", ("กิน",)))
_THAI_CHAR = re.compile("[\u0e00-\u0e7f]")
_RULES = tuple((intent, re.compile(pattern)) for intent, pattern in (          # the first that matches wins
    ("pickup", r"\b(mine|break|pick up|pickup|take|grab|collect) (the |that |this |your |my |a )?(crafting table|table|workbench|furnace|chest|bed|door|torch|torches|lantern|barrel|ladder)\b"),
    ("peace", r"\b(truce|peace|ceasefire|i give up|i surrender|surrender|stop fighting|let'?s (stop fighting|not fight|be friends)|don'?t (hit|attack|kill|hurt) me)\b|^(sorry|so sorry|my bad|i'?m sorry|ok ok|okay okay)[!. ]*$"),
    ("give", r"\b(give|hand (me|over)|pass me|toss|throw me|share|can i (have|get)|i need your)\b"),
    ("redstone", r"\b(redstone|circuit|logic gate|(not|or|and) gate|wire)\b"),
    ("craft", r"\b(craft|crafting)\b|\bmake (me |us )?(a |an |some |the |\d+ )?((wooden|wood|stone|iron|golden|gold|diamond) )?(" + "|".join(CRAFTABLE) + ")"),
    ("build", r"\b(build|make|dig|design) (me |us )?(a |an |our |my |the |some )?(\w+ )?(house|home|cottage|cabin|base|bunker|hideout|farm|pen|barn|grinder|statue|sculpture)\b|\bunderground\b|\bstatue of\b"),
    ("wood", r"\b(wood|woods|logs?|trees?|chop|timber|lumber|planks?)\b"),
    ("coal", r"\bcoal\b"),
    ("iron", r"\biron\b"),
    ("stone", r"\b(stones?|cobble|cobblestone)\b"),
    ("mine", r"\b(mine|mining|dig|digging|ores?|diamonds?|gold)\b"),
    ("food", r"\b(food|hunt|hunting|meat|pork|porkchops?|beef|steak|mutton|chickens?|pigs?|cows?|sheep)\b"),
    ("shelter", r"\b(shelter|house|hut|hide|bunker|build)\b"),
    ("eat", r"\b(eat|snack|heal)\b"),
    ("stop", r"\b(stop|cancel|never ?mind|forget (it|that)|quit|enough)\b"),
    ("stay", r"\b(stay|wait|don'?t move|hold (on|still)|stand (still|here)|remain)\b"),
    ("explore", r"\b(explore|wander|roam|adventure|do your (own )?thing|go play|look around|have fun|free)\b"),
    ("follow", r"\b(follow|come|with me|let'?s go|over here|this way|keep up|to me)\b"),
))
# Trading comes first, questions too ("how much for your logs?"): Xen answers those itself, as a trader.
_TRADE = re.compile(r"\b(trade|trades|trading|sell|selling|buy|buying|swap|exchange|barter|haggle|how much (for|is|are|do you want)|what do you want for|price (of|for))\b|\b\d{1,3} [a-z_]+ for (\d{1,3} )?(your |my )?[a-z_]+")
_QUESTION = re.compile(r"^((what|where|why|how|who|when|which)\b|(do|does|did|are|is|am|was|were|have|has|had) "
                       r"(you|we|i|it|there|they|he|she|this|that|your|my)\b|you (had|have|got|already have) \d+|any\b|"
                       r"(?!(can|could|will|would|wanna|pls|please) )[^?]*\?\s*$)")
# (and "you had 20 wood": telling it; "any diamonds?"; anything else asked with a "?", but "can you get wood?" is a request)
_SOCIAL = re.compile(r"^(thanks|thank you|thx|ty|good (job|work|boy|girl)|nice (one|job|work)|well done|gg|lol|haha|"
                     r"love you|you rock|you're (the best|awesome|cool)|bye|goodbye|good night)\b")
_NUMBER = re.compile(r"\b(\d{1,3})\b")
_GIVE_THINGS = (("log", ("wood", "log", "tree", "plank", "ไม้")), ("cobblestone", ("stone", "cobble", "rock", "หิน")),
                ("coal", ("coal", "ถ่าน")), ("raw_iron", ("iron", "เหล็ก")), ("diamond", ("diamond", "เพชร")),
                ("food", ("food", "meat", "eat", "อาหาร", "ของกิน")))

EARS = (
    "<|im_start|>system\nYou are the ears of Xen, a Minecraft companion. Read what a player says to Xen and answer "
    "with the one word for what they want Xen to do: follow, stay, explore, wood, stone, coal, iron, mine, food, give, "
    "shelter, eat, stop, redstone, trade, or chat (only talking, thanking, praising or asking something).<|im_end|>\n"
    + "".join(f"<|im_start|>user\n{said}<|im_end|>\n<|im_start|>assistant\n{intent}<|im_end|>\n" for said, intent in (
        ("come with me", "follow"), ("nice one!", "chat"), ("could you chop down a few trees", "wood"),
        ("keep guard right here", "stay"), ("hand me your stuff", "give"), ("how are you doing?", "chat"),
        ("go wander around", "explore"), ("we need a place to hide tonight", "shelter"), ("never mind", "stop"),
        ("go get us something to eat", "food"), ("you're funny", "chat"), ("i need smelting fuel", "coal"),
        ("follow my lead", "follow"), ("grab me some cobblestone", "stone"), ("see you later", "chat"),
        ("you look hurt, eat up", "eat"), ("put together a little logic thing with levers", "redstone"),
        ("let's make a deal, my iron for your wood", "trade"), ("watch this!", "chat"))))
SURE = 1.0          # the model's pick must beat "chat" by this much (log-probability), or it's just chat


# Common typos, fixed word by word before Xen reads a request ("fallow me" is "follow me").
TYPOS = {"fallow": "follow", "folow": "follow", "follw": "follow", "flw": "follow", "folllow": "follow", "follwo": "follow",
         "cmere": "come here", "comere": "come here", "c'mere": "come here", "plz": "please", "pls": "please", "stahp": "stop",
         "stp": "stop", "wod": "wood", "woood": "wood", "sheltr": "shelter", "shleter": "shelter", "explor": "explore",
         "exlpore": "explore", "mien": "mine", "ston": "stone", "stne": "stone", "fod": "food", "foood": "food", "giv": "give",
         "gimme": "give me", "stya": "stay", "sty": "stay"}


def request_words(message, name="xen"):
    words = re.sub(rf"\b{re.escape(name.lower())}\b", " ", message.lower()).replace(",", " ").split()
    return " ".join(TYPOS.get(w, w) for w in words)


def understand(message, name="xen"):
    """What a player asks Xen to do, by keywords: (intent, thing, amount). Questions are just chat."""
    words = request_words(message, name)
    intent = "chat"
    if _THAI_CHAR.search(words):
        intent = next((i for i, keys in THAI if any(k in words for k in keys)), "chat")
    elif _TRADE.search(words):
        intent = "trade"
    elif not _QUESTION.match(words):
        intent = next((i for i, rule in _RULES if rule.search(words)), "chat")
    return details(intent, words)


def just_talk(words):
    """Questions and thanks: the chat model isn't asked to find a request in them."""
    return bool(_QUESTION.match(words) or _SOCIAL.match(words))


def details(intent, words):
    """The thing and the amount for a request: "give me 5 logs" -> ("give", "log", 5)."""
    number = _NUMBER.search(words)
    amount = int(number.group(1)) if number else 64 if "stack" in words else AMOUNT.get(intent, 0)
    thing = ""
    if intent == "redstone":
        thing = next((k for k in ("and", "or", "wire") if re.search(rf"\b{k}\b", words)), "not")
        amount = 0
    if intent == "pickup":                                       # "mine the crafting table" -> ("pickup", "crafting_table", 0)
        m = re.search(r"\b(crafting table|table|workbench|furnace|chest|bed|door|torch|torches|lantern|barrel|ladder)\b", words)
        b = m.group(1) if m else "crafting table"
        thing = "crafting_table" if b in ("table", "workbench") else "torch" if b == "torches" else b.replace(" ", "_")
        amount = 0
    if intent == "build":                                        # a house, a base under the ground, a farm, a pen, a mob farm
        of = re.search(r"\b(statue|sculpture)( of ([a-z0-9_]+))?", words)
        thing = ("statue:" + ("you" if not of.group(3) or of.group(3) == "yourself" else of.group(3)) if of
                 else "mob farm" if re.search(r"\b(mob|mobs|xp|grinder)\b", words) else "farm" if re.search(r"\bfarm\b", words)
                 else "pen" if re.search(r"\b(pen|barn|animal)\b", words)
                 else "base" if re.search(r"\b(underground|base|bunker|hideout|dig)\b", words) else "house")
        amount = 0
    if intent == "craft":                                        # "craft 4 torches" -> ("craft", "torch", 4)
        m = _CRAFT_THING.search(words)
        if m:
            what = re.sub(r"(es|s)$", "", m.group(3)).replace(" ", "_")
            if m.group(3) == "stairs":
                what = "stairs"
            if m.group(3).startswith("torch"):
                what = "torch"
            if what == "table":
                what = "crafting_table"
            if what == "plank":
                what = "planks"
            material = (m.group(2) or "").replace("wood", "wooden").replace("woodenen", "wooden").replace("gold", "golden").replace("goldenen", "golden")
            thing = f"{material}_{what}" if material else what
        else:
            w = _CRAFT_WORD.search(words)
            thing = w.group(4) if w else ""
        amount = int(number.group(1)) if number else 1
    if intent == "give":
        thing = next((item for item, keys in _GIVE_THINGS if any(k in words for k in keys)), "all")
        amount = int(number.group(1)) if number else 64 if "stack" in words else 0     # 0 = all of it
    return intent, thing, max(0, min(amount, 256))


class ChatBot:
    """Understands players and answers them with the small local chat model."""

    def __init__(self, path=None, max_tokens=40, temperature=0.5, download=True):
        from .llm import LanguageModel
        self.model = LanguageModel(path or model_path(download))
        self.model.keep_prefix(PERSONA)
        self.model.keep_prefix(EARS)
        self.max_tokens = max_tokens
        self.temperature = temperature

    def understand(self, message, name="xen"):
        """Keywords first (they're reliable); when there are none, the chat model decides."""
        request = understand(message, name)
        if request[0] != "chat":
            return request
        words = request_words(message, name)
        if just_talk(words):
            return request
        best, scores = self.model.choose(f"{EARS}<|im_start|>user\n{words}<|im_end|>\n<|im_start|>assistant\n",
                                         INTENTS)
        if scores[best] - scores[INTENTS.index("chat")] < SURE:
            return request
        return details(INTENTS[best], words)

    def prompt(self, speaker, message, context):
        return (f"{PERSONA}<|im_start|>user\nNotes: {context}\n{speaker} says: {message}<|im_end|>\n"
                f"<|im_start|>assistant\n")

    def reply(self, speaker, message, context="", seed=None):
        """Its answer. What it was asked to do, it answers at once in plain words (exactly its plan); for talk, the
        model answers, and if that makes something up or promises to do something, it tries once more, then just
        says what it knows."""
        if _PLAN.search(context):
            return plainly(context, message)
        for attempt in range(2):
            text = safe_chat(self.model.generate(self.prompt(speaker, message, context), self.max_tokens,
                                                 self.temperature, seed=None if seed is None else seed + attempt))
            if honest(text, context) == text and not _PROMISE.search(text.lower()):
                return text
        return plainly(context, message)
