**Prototype.** Xen Companion is a survival companion for Minecraft (Fabric): a player that learns, thinks, feels
fear and chats, and plays fair. It only knows what it can sense and acts only through a player's inputs.

### Downloads

| file | what |
|---|---|
| `xen-companion-0.2.0-alpha+mc1.21.11.jar` | the mod for Minecraft 1.21.11 (Java 21) |
| `xen-companion-0.2.0-alpha+mc26.x.jar` | the mod for Minecraft 26.1 - 26.3 (Java 25) |
| `smollm2-360m-instruct-q8_0.gguf` | the chat model (optional, about 390 MB): put it in `config/xen/`. Otherwise it downloads by itself the first time it's needed |
| `xen-brain.bin` | Xen's trained brain, already inside the jars. Copy it to `<world>/xen/brain.bin` to reset a world's Xen to it |
| `SHA256SUMS.txt` | checksums |

Needs Fabric Loader 0.16+ and Fabric API. Install and settings: [dist/README.md](https://github.com/JAK-cool162/Xen/blob/main/dist/README.md).

### What's new in 0.2.0-alpha

* **Ask it for things in chat**: "Xen, get me 5 logs", "find coal", "kill a pig", "give me your wood", "build a
  shelter", "follow me", "stay", "go explore", "stop". It does them itself: it goes for blocks and animals it
  knows about, finds its way on foot, digs, chops, hunts and tosses items to you. It says what it will do, or
  exactly why it can't.
* **Understanding**: keywords first; otherwise the chat model picks the request, only when it's clearly more
  likely than plain talk.
* **Without the chat model** (low memory, phones, or `"chatModel": "off"`) Xen still understands requests by
  keywords and answers in plain words.
* **Its fears travel with its brain**: the latest 1000 trauma and joy memories are saved with the brain and keep
  being replayed. A 100-day sprinted training run in the real game showed why this matters: without them, a
  brain learning somewhere safe slowly forgot to fear lava.
* **New instincts**: swims up in water, fights back against monsters within reach.
* **Pathfinding** through the blocks it knows (within 6 blocks): steps up, drops down, goes around obstacles and
  lava.
* `/xen spawn <count> [radius]` for operators; `"maxPerPlayer": 0` for no limit.
* The voice is renamed chat: `/xen chat on|off`, and the settings `chat`, `chatModel`, `downloadChatModel`,
  `chatThreads`.

### Phones (PojavLauncher / Amethyst / Zalith Launcher)

The mod is plain Java and installs like on a PC (Fabric + Fabric API + the jar). Use the 1.21.11 jar with Java 21.
Phones usually give Minecraft too little memory for the chat model, so Xen chats in plain words there. Not tested
on a phone yet.

### Known limits

It's a prototype. Xen still dies more than a good player, and it gets lost in tricky terrain. Its chat model is
small and its answers are simple. It only gathers what it can reach on foot (plus one block up with a pillar).

The chat model is [SmolLM2-360M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct) by Hugging
Face (Apache-2.0).
