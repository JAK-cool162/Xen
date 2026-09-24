# Xen Companion (Fabric mod)

Xen as a survival companion: a player that joins your world, learns, thinks,
feels fear and talks. It plays fair. It only knows what it can sense, and it
acts only through a player's inputs.

| file | Minecraft | Java |
|---|---|---|
| `xen-companion-1.0.0+mc1.21.11.jar` | 1.21.11 | 21 or newer |
| `xen-companion-1.0.0+mc26.x.jar` | 26.1, 26.2, 26.3 | 25 or newer |

The trained brain and the talking code are inside the jar. The voice model
(SmolLM2-360M, about 390 MB) downloads by itself the first time Xen talks.

## Requirements

* **Fabric Loader** 0.16 or newer (tested with 0.19.5): <https://fabricmc.net/use/>
* **Fabric API** for your Minecraft version (tested: 0.141.6+1.21.11,
  0.155.3+26.1.2, 0.161.0+26.3): <https://modrinth.com/mod/fabric-api>
* **Java**: 21 for Minecraft 1.21.11, 25 for 26.x.
* **Memory**: give the server at least 4 GB (`-Xmx4G`) so the voice fits
  (the model takes about 500 MB once loaded). Without talking, 2 GB is enough.
* **CPU**: the voice and the learning run in background threads. A reply takes a
  few seconds on 2-4 cores.
* **Internet, once**: to download the voice from Hugging Face into
  `config/xen/smollm2-360m-instruct-q8_0.gguf`. With no internet, download
  [smollm2-360m-instruct-q8_0.gguf](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf)
  yourself and put it there. With `"talk": false` it isn't needed at all.

Players don't need the mod: it runs on the server, and vanilla clients can
join and play with Xen. It was tested on dedicated servers. For single player,
put it (and Fabric API) in your client's `mods` folder so the built-in server
runs it.

## Install

1. Install Fabric Loader for your server version.
2. Put `fabric-api-*.jar` and the matching `xen-companion-*.jar` in `mods/`.
3. Start the server. The log says `Xen is ready: /xen summon`.

## Commands

| command | what it does |
|---|---|
| `/xen summon [name]` | Xen joins next to you as a player (from the console: at world spawn, on its own) |
| `/xen spawn <count> [radius]` | operators: bring in many Xens on their own, scattered on the surface up to `radius` blocks away (default 300). They all share one brain |
| `/xen mode follow` | follows you (walks, jumps, digs stairs, pillars up out of holes) |
| `/xen mode stay` | stays around where it is now |
| `/xen mode free` | lives its own life: explores, mines, learns |
| `/xen status` | health, hunger, mood, what it's doing and thinking |
| `/xen talk on\|off` | its voice |
| `/xen learn on\|off` | keep learning from what it lives through |
| `/xen save` | save the brain now (it also saves every 5 minutes and on shutdown) |
| `/xen dismiss` | it goes home (operators and the console send every Xen home) |

* **Talk to it**: say its name in chat ("Xen, what do you see?"). It answers
  from what it knows.
* **Its bag**: right-click your Xen to open its inventory. Give it food, tools
  and blocks, or take what it mined.
* **Fighting**: it turns to monsters within reach and hits them when its
  attack is charged, like a player. It eats when it gets hungry, if it has food.
* **Death**: it drops its items like a player, respawns at its bed or the
  world spawn, and remembers what hurt it.

## What it knows (it can't cheat)

* It knows every block **within 6 blocks** of it, like a player feeling their
  way around.
* Beyond that it only knows what it **sees**: its view is a 90° cone in front
  of it, up to **8 chunks (128 blocks)** away. Walls, hills and the ground block
  the view, so ores underground or behind it stay unknown. Glass, leaves'
  gaps and water surfaces don't stop the eye, and unloaded chunks look empty.
* What it has seen becomes a **belief with a confidence** that fades when it
  looks away (blocks fade over about 2 minutes, mobs within a few seconds). It
  acts on "I think there was iron over there", not on the truth.
* It acts through player inputs only: walking keys and normal physics, the
  real mining time with the best tool it has, the normal reach and attack
  cooldown, and placing only blocks it carries. It is a real server player:
  it shows in the player list, takes damage, gets hungry and can die.

## Its voice (it can talk but can't cheat)

A small language model (SmolLM2-360M-Instruct) runs inside the mod, on the CPU.

* Its prompt holds **only Xen's own notes**: its feelings, its body, what it
  carries, and what it has seen, with how sure it is. It gets nothing else
  from the world.
* A sentence that claims something (ores, lava, mobs, villages...) that isn't
  in its notes is **dropped** before it's sent.
* It only chats: one line, up to two sentences, never a command (a reply can't
  start with `/`). It doesn't control what Xen does.

## Configuration: `config/xen.json`

| key | default | meaning |
|---|---|---|
| `talk` | `true` | Xen talks (short reactions and voice answers) |
| `downloadVoice` | `true` | download the voice model on first use |
| `voiceThreads` | half the CPU cores (1-4) | CPU threads for the voice |
| `learn` | `true` | keep learning in the world |
| `maxPerPlayer` | `1` | Xens one player may summon (0 = no limit; operators have no limit) |
| `decisionTicks` | `5` | ticks between decisions (5 = four decisions per second) |
| `followDistance` | `10` | follow mode: catch up when further than this |
| `leaveWithOwner` | `true` | Xen leaves when its owner leaves |
| `saveMinutes` | `5` | how often the brain is saved |

The brain is saved per world in `<world>/xen/brain.bin`. Delete it to start
over from the pre-trained brain. It uses the same format as
`python -m xen export`, so a brain trained in SimCraft can be copied in.

## Build from source

```
cd mod/mc1.21.11     # or mod/mc26
./gradlew build      # needs JDK 25 to run Gradle; the jar lands in build/libs/
./gradlew crossCheck # checks that the Java brain, senses and fear match the Python ones
```
