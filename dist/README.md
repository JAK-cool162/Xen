# Xen Companion (Fabric mod), prototype 0.2.0-alpha

Xen as a survival companion: a player that joins your world, learns, thinks,
feels fear and chats. Ask it for things in plain words ("Xen, get me some
wood") and it does them. It plays fair: it only knows what it can sense, and it
acts only through a player's inputs.

| file | Minecraft | Java |
|---|---|---|
| `xen-companion-0.2.0-alpha+mc1.21.11.jar` | 1.21.11 | 21 or newer |
| `xen-companion-0.2.0-alpha+mc26.x.jar` | 26.1, 26.2, 26.3 | 25 or newer |

Get them from the GitHub **Releases** page (with the chat model and Xen's
brain as separate downloads) or from this folder. The trained brain is inside
the jar. This is a prototype, so expect rough edges.

## Requirements

* **Fabric Loader** 0.16 or newer (tested with 0.19.5): <https://fabricmc.net/use/>
* **Fabric API** for your Minecraft version (tested: 0.141.6+1.21.11,
  0.155.3+26.1.2, 0.161.0+26.3): <https://modrinth.com/mod/fabric-api>
* **Java**: 21 for Minecraft 1.21.11, 25 for 26.x.
* **Memory**: 2 GB is enough for Xen itself. For the chat model, give the game
  3 GB or more (`-Xmx3G`); it takes about 500 MB. With less memory, Xen still
  chats and understands requests, only more simply (see below).
* **Chat model** (optional): `smollm2-360m-instruct-q8_0.gguf`, about 390 MB. It
  downloads by itself into `config/xen/` the first time it's needed. With no
  internet, take it from the release page (or
  [Hugging Face](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf))
  and put it in `config/xen/` yourself.

Players don't need the mod on a server: it runs on the server, and vanilla
clients can join and play with Xen. For single player, put it (with Fabric API)
in your `mods` folder and the built-in server runs it.

## Install

1. Install Fabric Loader for your Minecraft version.
2. Put `fabric-api-*.jar` and the matching `xen-companion-*.jar` in `mods/`.
3. Start the game or server. The log says `Xen is ready: /xen summon`.

## Phones: PojavLauncher, Amethyst, Zalith Launcher

These launchers run Java Edition on Android, with Fabric. Xen Companion is plain
Java (no native code), so it installs like on a PC: install Fabric in the
launcher, then put Fabric API and the Xen jar in that profile's `mods` folder.

* **Which jar**: the 1.21.11 jar needs Java 21, which these launchers include
  (PojavLauncher's successor Amethyst ships Java 8, 17 and 21). The 26.x jar
  needs Java 25, like Minecraft 26.x itself: use it only if your launcher runs
  26.x.
* **Memory**: phones usually give Minecraft 1-2 GB, so the chat model isn't
  loaded (`"chatModel": "auto"`). Xen still understands requests by their
  keywords and answers in plain words from what it knows. With a phone that
  can give the game 3 GB or more, the model loads, but answers are slower on
  phone CPUs.
* **Slow phone?** In `config/xen.json` set `"decisionTicks": 10` (half as many
  decisions) and `"learn": false` (no learning in the background).
* It was tested on PC servers and in the Java code, not on a phone yet.

## Talking to Xen: it understands and does it

Say its name in chat. Only its owner can give it orders (anyone can, for the
ownerless Xens from `/xen spawn`). Anyone can chat with it.

| you say (any words like these) | Xen |
|---|---|
| "Xen, follow me" / "come here" / "let's go" | follows you |
| "Xen, stay" / "wait here" | stays around here |
| "Xen, go explore" / "do your thing" | lives its own life |
| "Xen, get me 5 logs" / "chop some trees" | walks to a tree it knows, chops, climbs a block if it must |
| "Xen, get stone" / "find coal" / "find iron" / "go mining" | the same for stone, coal, iron or any ore |
| "Xen, kill a pig" / "get us food" | hunts an animal it sees and picks up the meat |
| "Xen, give me your wood" / "hand over 12 cobblestone" / "give me your stuff" | walks over and tosses it to you (keeps its tools) |
| "Xen, build a shelter" / "hide!" | builds a little dirt or cobblestone hut around itself (10 blocks) and stays in it |
| "Xen, eat something" | eats, if it has food and is hungry |
| "Xen, stop" / "cancel that" | stops what it's doing |
| "Xen, what do you see?" / "thanks!" | just talks |

How it understands: clear keywords decide first. When there are none ("go see
what's out there"), the chat model picks one of the things above, and only if
it's clearly more likely than just talking. Questions and thanks are always just
talk. `/xen status` shows what it's doing right now ("getting wood, 1 of 5 so
far", "hunting a pig 4 blocks away").

It does it fairly:

* It only goes for blocks and animals it knows about: felt within 6 blocks, or
  seen in its 90° view. When it knows of none, it looks around and walks
  somewhere new, like a player would.
* It walks there itself. It finds a way through the blocks it knows (stepping
  up, dropping down, around obstacles and lava) and digs only when there's no
  way.
* It mines with the real break time and the best tool it has, places only
  blocks it carries, hits with the normal reach and cooldown, and gives items
  by tossing them.
* It answers honestly. For a request it says what it will do, or exactly why
  it can't ("I need 10 dirt or cobblestone and have 3", "the ground here
  isn't flat", "I can't reach you up there, come down"). In conversation the
  chat model answers, and anything it claims (ores, lava, mobs, villages...)
  must be in Xen's notes. It can't promise to do things, and it never writes
  commands.

## Commands

| command | what it does |
|---|---|
| `/xen summon [name]` | Xen joins next to you as a player (from the console: at world spawn, on its own) |
| `/xen spawn <count> [radius]` | operators: bring in many Xens on their own, scattered on the surface up to `radius` blocks away (default 300). They all share one brain |
| `/xen mode follow\|stay\|free` | the same as asking it to follow, stay or explore |
| `/xen status` | health, hunger, mood, what it knows and what it's doing |
| `/xen chat on\|off` | Xen answers and understands chat |
| `/xen learn on\|off` | keep learning from what it lives through |
| `/xen save` | save the brain now (it also saves every 5 minutes and on shutdown) |
| `/xen dismiss` | it goes home (operators and the console send every Xen home) |

* **Its bag**: right-click your Xen to open its inventory.
* **Instincts**: it swims up in water, fights back against monsters within
  reach, and eats when it gets hungry.
* **Death**: it drops its items like a player, respawns at its bed or the
  world spawn, and remembers what hurt it.

## What it knows (it can't cheat)

* It knows every block **within 6 blocks** of it.
* Beyond that it only knows what it **sees**: its view is a 90° cone in front
  of it, up to **8 chunks (128 blocks)**. Walls, hills and the ground block the
  view, so ores underground or behind it stay unknown. Glass, ice and water don't
  stop the eye (leaves do), and unloaded chunks stay unknown.
* What it has seen becomes a **belief with a confidence** that fades when it
  looks away (blocks lose half their confidence in 2 minutes, mobs in 3
  seconds). It acts on "I think there was iron over there", not on the truth.
* It's a real server player: it shows in the player list, takes damage, gets
  hungry and can die.

## Configuration: `config/xen.json`

| key | default | meaning |
|---|---|---|
| `chat` | `true` | Xen answers and understands chat |
| `chatModel` | `"auto"` | the chat model: `"auto"` (when the game has about 3 GB or more), `"on"` or `"off"` |
| `downloadChatModel` | `true` | download the chat model the first time it's needed |
| `chatThreads` | half the CPU cores (1-4) | CPU threads for the chat model |
| `learn` | `true` | keep learning in the world |
| `maxPerPlayer` | `1` | Xens one player may summon (0 = no limit; operators have no limit) |
| `decisionTicks` | `5` | ticks between decisions (5 = four a second; 10 for slow machines) |
| `followDistance` | `10` | follow mode: catch up when further than this |
| `leaveWithOwner` | `true` | Xen leaves when its owner leaves |
| `saveMinutes` | `5` | how often the brain is saved |

The brain is saved per world in `<world>/xen/brain.bin`, and every life is
logged in `<world>/xen/lives.csv` (how long it lived, what it got, what killed
it). Delete `brain.bin` to start over from the pre-trained brain.
`python -m xen export` writes a brain trained in Python in the same format.

Troubleshooting: start the game with `-Dxen.debug=true` and every decision is
logged.

## Build from source

```
cd mod/mc1.21.11     # or mod/mc26
./gradlew build      # needs JDK 25 to run Gradle; the jar lands in build/libs/
./gradlew crossCheck # checks the Java brain, senses, chat rules and paths against the Python ones
```

The chat model is [SmolLM2-360M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct)
by Hugging Face, Apache-2.0 license.
