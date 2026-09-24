#!/usr/bin/env node
// Xen <-> Minecraft (Java Edition) bridge.
//
// Each Xen is a mineflayer bot: a real player connection. The server treats it
// like any other player (tab list, chat, damage, inventory, /op, commands), and
// the bridge can host as many of them as the server will accept.
//
// Xen's Python brain drives them over a local TCP socket, one JSON message per
// line. Every action is a real player input: movement keys are held as control
// states, the view is turned with the mouse, blocks are dug by holding attack.
//
//   node xen_bridge.js --host localhost --port 25565
//
// Ops (Python -> bridge), "bot" is optional and defaults to the first Xen:
//   {"op": "spawn", "name": "Xen_2", "spread": 500}   join one more Xen
//   {"op": "despawn", "bot": "Xen_2"}
//   {"op": "list"}
//   {"op": "observe", "bot": "Xen"}
//   {"op": "act", "bot": "Xen", "action": "FORWARD"}
//   {"op": "act_all", "actions": {"Xen": "MINE", "Xen_2": "JUMP"}}
//   {"op": "build", "bot": "Xen", "origin": [x, y, z], "blocks": [[dx, dy, dz, "oak_stairs[facing=east]"], ...],
//    "mode": "commands" | "hands"}
//   {"op": "scan", "bot": "Xen", "from": [x, y, z], "to": [x, y, z]}
//   {"op": "use", "bot": "Xen", "pos": [x, y, z]}      right-click a block (flip a lever, press a button)
//   {"op": "chat", "bot": "Xen", "text": "..."}
// Reply: {"ok": true, ...} or {"ok": false, "error": "..."}
'use strict'

const net = require('net')
const readline = require('readline')

// Must match xen/perception.py and xen/blocks.py.
const R = 8
const DOWN = 6
const UP = 6
const B = { AIR: 0, GRASS: 1, DIRT: 2, STONE: 3, LOG: 4, LEAVES: 5, COAL: 6, IRON: 7, GOLD: 8, DIAMOND: 9, LAVA: 10, WATER: 11, BEDROCK: 12 }
const DIRS = [[0, -1], [1, 0], [0, 1], [-1, 0]] // north, east, south, west
const FACING = { north: 0, east: 1, south: 2, west: 3 }
const PITCH = { '-1': -Math.PI / 2 + 0.01, 0: -0.7, 1: 0 }
const DIRT_LIKE = new Set(['dirt', 'coarse_dirt', 'podzol', 'rooted_dirt', 'mud', 'farmland', 'dirt_path', 'mycelium', 'sand', 'red_sand', 'gravel', 'clay', 'snow_block'])
const PLACEABLE = ['cobblestone', 'cobbled_deepslate', 'dirt']
const HOSTILE = new Set(['zombie', 'husk', 'drowned', 'zombie_villager', 'skeleton', 'stray', 'creeper', 'spider', 'cave_spider', 'enderman', 'witch', 'slime', 'magma_cube', 'phantom', 'pillager', 'vindicator', 'silverfish', 'blaze', 'ghast', 'piglin_brute', 'hoglin', 'zoglin', 'warden', 'bogged', 'breeze'])
const MAX_SCAN = 40000

function parseArgs (argv) {
  const out = {}
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) out[argv[i].slice(2)] = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true
  }
  return out
}

function category (block) {
  if (!block) return B.STONE // unloaded: treat as solid unknown
  const n = block.name
  if (n === 'air' || n === 'cave_air' || n === 'void_air') return B.AIR
  if (n.includes('lava') || n === 'fire' || n === 'magma_block') return B.LAVA
  if (n.includes('water') || n === 'bubble_column' || n === 'kelp' || n === 'seagrass') return B.WATER
  if (n === 'bedrock' || n === 'barrier' || n === 'obsidian') return B.BEDROCK
  if (n.includes('diamond_ore')) return B.DIAMOND
  if (n.includes('gold_ore')) return B.GOLD
  if (n.includes('iron_ore')) return B.IRON
  if (n.includes('coal_ore')) return B.COAL
  if (n.endsWith('_log') || n.endsWith('_wood') || n.endsWith('_stem')) return B.LOG
  if (n.endsWith('_leaves')) return B.LEAVES
  if (n === 'grass_block') return B.GRASS
  if (DIRT_LIKE.has(n)) return B.DIRT
  if (block.boundingBox === 'block') return B.STONE
  return B.AIR // flowers, grass, torches... nothing to stand on
}

function isHostile (entity) {
  return Boolean(entity) && (entity.type === 'hostile' || HOSTILE.has(entity.name))
}

// "oak_stairs[facing=east,half=bottom]" -> { name: 'oak_stairs', props: { facing: 'east', half: 'bottom' } }
function parseState (state) {
  const m = /^([a-z0-9_:]+)(?:\[(.*)\])?$/.exec(state)
  if (!m) throw new Error(`bad block state ${state}`)
  const props = {}
  for (const kv of (m[2] || '').split(',').filter(Boolean)) {
    const [k, v] = kv.split('=')
    props[k] = v
  }
  return { name: m[1].replace('minecraft:', ''), props }
}

function stateString (block) {
  if (!block) return 'unknown'
  let props = {}
  try { props = block.getProperties() } catch (e) {}
  const keys = Object.keys(props).sort()
  return keys.length ? `${block.name}[${keys.map((k) => `${k}=${props[k]}`).join(',')}]` : block.name
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
function withTimeout (promise, ms) {
  return Promise.race([promise, sleep(ms).then(() => { throw new Error('timed out') })])
}

class Body {
  constructor (bot, name) {
    this.bot = bot
    this.name = name
    this.yaw = 0
    this.pitch = 0
    this.died = false
    this.spawned = false
    this.heard = []
    bot.on('spawn', () => { this.spawned = true; this.snap() })
    bot.on('death', () => { this.died = true; this.spawned = false })
    bot.on('chat', (username, message) => {
      if (username !== bot.username && this.heard.length < 50) this.heard.push({ from: username, text: message })
    })
  }

  snap () {
    const k = Math.round(-this.bot.entity.yaw / (Math.PI / 2))
    this.yaw = ((k % 4) + 4) % 4
  }

  async waitForSpawn (ms = 60000) {
    const until = Date.now() + ms
    while (!this.spawned) {
      if (Date.now() > until) throw new Error(`${this.name} did not respawn`)
      await sleep(100)
    }
  }

  feet () { return this.bot.entity.position.floored() }

  async look () {
    await this.bot.look(-this.yaw * Math.PI / 2, PITCH[this.pitch], true)
  }

  target () {
    const p = this.feet()
    const [fx, fz] = DIRS[this.yaw]
    if (this.pitch < 0) return p.offset(0, -1, 0)
    return p.offset(fx, this.pitch > 0 ? 1 : 0, fz)
  }

  inventory () {
    const bot = this.bot
    const foods = (bot.registry && bot.registry.foodsByName) || {}
    const inv = { dirt: 0, cobblestone: 0, log: 0, coal: 0, raw_iron: 0, raw_gold: 0, diamond: 0, food: 0 }
    for (const item of bot.inventory.items()) {
      const n = item.name
      if (foods[n]) inv.food += item.count
      else if (n === 'dirt') inv.dirt += item.count
      else if (n === 'cobblestone' || n === 'cobbled_deepslate') inv.cobblestone += item.count
      else if (n.endsWith('_log') || n.endsWith('_stem')) inv.log += item.count
      else if (n === 'coal') inv.coal += item.count
      else if (n === 'raw_iron' || n === 'iron_ore') inv.raw_iron += item.count
      else if (n === 'raw_gold' || n === 'gold_ore') inv.raw_gold += item.count
      else if (n === 'diamond') inv.diamond += item.count
    }
    return inv
  }

  observe () {
    const bot = this.bot
    const p = this.feet()
    const cube = new Array((2 * R + 1) * (DOWN + UP + 1) * (2 * R + 1))
    let i = 0
    for (let dx = -R; dx <= R; dx++) {
      for (let dy = -DOWN; dy <= UP; dy++) {
        for (let dz = -R; dz <= R; dz++) cube[i++] = category(bot.blockAt(p.offset(dx, dy, dz)))
      }
    }
    const mobs = []
    for (const e of Object.values(bot.entities)) {
      if (e === bot.entity || !isHostile(e) || e.position.distanceTo(bot.entity.position) > 16) continue
      const q = e.position.floored()
      mobs.push([q.x - p.x, q.y - p.y, q.z - p.z])
    }
    let burning = false
    try { burning = Boolean(bot.entity.metadata[0] & 0x01) } catch (e) {}
    const died = this.died
    this.died = false
    const heard = this.heard
    this.heard = []
    return {
      name: this.name,
      cube,
      position: [p.x, p.y, p.z],
      yaw: this.yaw,
      pitch: this.pitch,
      health: bot.health,
      food: bot.food,
      night: bot.time ? !bot.time.isDay : false,
      burning,
      in_water: Boolean(bot.entity.isInWater),
      in_lava: Boolean(bot.entity.isInLava),
      inventory: this.inventory(),
      mobs,
      heard,
      dead: died
    }
  }

  async hold (controls, ms) {
    for (const c of controls) this.bot.setControlState(c, true)
    await sleep(ms)
    this.bot.clearControlStates()
  }

  async act (name) {
    const bot = this.bot
    switch (name) {
      case 'IDLE': await sleep(100); break
      case 'FORWARD': await this.hold(['forward'], 250); break
      case 'BACK': await this.hold(['back'], 250); break
      case 'LEFT': await this.hold(['left'], 250); break
      case 'RIGHT': await this.hold(['right'], 250); break
      case 'JUMP': await this.hold(['forward', 'jump'], 350); break
      case 'TURN_LEFT': this.yaw = (this.yaw + 3) % 4; await this.look(); break
      case 'TURN_RIGHT': this.yaw = (this.yaw + 1) % 4; await this.look(); break
      case 'LOOK_UP': this.pitch = Math.min(1, this.pitch + 1); await this.look(); break
      case 'LOOK_DOWN': this.pitch = Math.max(-1, this.pitch - 1); await this.look(); break
      case 'MINE': {
        const block = bot.blockAt(this.target())
        const kind = category(block)
        if (block && block.diggable && kind !== B.AIR && kind !== B.LAVA && kind !== B.WATER) {
          await withTimeout(bot.dig(block, true), 10000).catch(() => bot.stopDigging())
        } else {
          bot.swingArm()
        }
        await this.look()
        break
      }
      case 'PLACE': await this.placeFromInventory(this.target()); break
      case 'ATTACK': {
        const mob = bot.nearestEntity((e) => isHostile(e) && e.position.distanceTo(bot.entity.position) < 3.5)
        if (mob) {
          await bot.lookAt(mob.position.offset(0, mob.height * 0.8, 0), true)
          bot.attack(mob)
          await this.look()
        } else {
          bot.swingArm()
        }
        break
      }
      case 'EAT': {
        const foods = (bot.registry && bot.registry.foodsByName) || {}
        const food = bot.inventory.items().find((item) => foods[item.name])
        if (food && bot.food < 20) {
          await bot.equip(food, 'hand')
          await withTimeout(bot.consume(), 5000).catch(() => {})
        }
        break
      }
      default: throw new Error(`unknown action ${name}`)
    }
  }

  // Place a block by right-clicking a neighbouring solid face, like a player.
  async placeAgainst (pos, item) {
    const bot = this.bot
    const faces = [[0, -1, 0], [1, 0, 0], [-1, 0, 0], [0, 0, 1], [0, 0, -1], [0, 1, 0]]
    for (const [dx, dy, dz] of faces) {
      const ref = bot.blockAt(pos.offset(dx, dy, dz))
      if (ref && ref.boundingBox === 'block') {
        await bot.equip(item, 'hand')
        const face = pos.minus(ref.position)
        await withTimeout(bot.placeBlock(ref, face), 3000)
        return true
      }
    }
    return false
  }

  async placeFromInventory (pos) {
    const here = this.bot.blockAt(pos)
    const replaceable = here && [B.AIR, B.WATER, B.LAVA].includes(category(here))
    const item = this.bot.inventory.items().find((it) => PLACEABLE.includes(it.name))
    if (replaceable && item) await this.placeAgainst(pos, item).catch(() => {})
    await this.look()
  }

  // ------------------------------------------------------------- building
  async build (origin, blocks, mode, clear) {
    const bot = this.bot
    const o = origin ? { x: origin[0], y: origin[1], z: origin[2] } : bot.entity.position.floored()
    // Blocks arrive in build order: bottom-up, supports before attachments.
    const ordered = blocks
    let placed = 0
    const skipped = []
    if (mode === 'commands') {
      if (!(await this.canUseCommands())) {
        throw new Error(`${this.name} needs operator permission to build with commands (/op ${this.name}), ` +
          'or build in creative mode by hand (--build-mode hands)')
      }
      if (clear) {
        const [x0, y0, z0, x1, y1, z1] = clear
        bot.chat(`/fill ${o.x + x0} ${o.y + y0} ${o.z + z0} ${o.x + x1} ${o.y + y1} ${o.z + z1} air`)
        await sleep(200)
      }
      for (const [dx, dy, dz, state, n = 1] of ordered) {
        const at = `${o.x + dx} ${o.y + dy} ${o.z + dz}`
        bot.chat(n > 1 ? `/fill ${at} ${o.x + dx + n - 1} ${o.y + dy} ${o.z + dz} ${state}` : `/setblock ${at} ${state}`)
        placed += n
        await sleep(50)
      }
      return { placed, skipped }
    }
    const { Vec3 } = require('vec3')
    const Item = require('prismarine-item')(bot.registry)
    const creative = bot.game.gameMode === 'creative'
    const single = []
    for (const [dx, dy, dz, state, n = 1] of ordered) {
      for (let i = 0; i < n; i++) single.push([dx + i, dy, dz, state])
    }
    for (const [dx, dy, dz, state] of single) {
      const pos = new Vec3(o.x + dx, o.y + dy, o.z + dz)
      const { name, props } = parseState(state)
      try {
        const current = bot.blockAt(pos)
        if (current && current.name === name) { placed++; continue }
        const info = bot.registry.itemsByName[name]
        if (!info) { skipped.push([dx, dy, dz, state, 'no item']); continue }
        let item = bot.inventory.items().find((it) => it.name === name)
        if (!item && creative) {
          await bot.creative.setInventorySlot(36, new Item(info.id, 64))
          item = bot.inventory.slots[36]
        }
        if (!item) { skipped.push([dx, dy, dz, state, 'missing material']); continue }
        if (bot.entity.position.distanceTo(pos.offset(0.5, 0.5, 0.5)) > 4.2) {
          if (!creative) { skipped.push([dx, dy, dz, state, 'out of reach']); continue }
          await withTimeout(bot.creative.flyTo(pos.offset(0.5, 2.5, 0.5)), 8000)
        }
        if (props.facing && FACING[props.facing] !== undefined) {
          // Stairs and doors face the way the player looks; repeaters, comparators
          // and observers the opposite way.
          const back = /repeater|comparator|observer/.test(name) ? 2 : 0
          await bot.look(-((FACING[props.facing] + back) % 4) * Math.PI / 2, 0, true)
        }
        if (await this.placeAgainst(pos, item)) placed++
        else skipped.push([dx, dy, dz, state, 'nothing to place against'])
      } catch (err) {
        skipped.push([dx, dy, dz, state, String(err.message || err)])
      }
    }
    return { placed, skipped }
  }

  // Try a harmless command and listen for the server refusing it. Commands
  // from a non-operator also count as chat spam, so never flood them.
  async canUseCommands () {
    // Remember a yes; re-check a no now and then (someone may /op Xen meanwhile).
    if (this.commandsOk || Date.now() - (this.checkedAt || 0) < 15000) return Boolean(this.commandsOk)
    this.checkedAt = Date.now()
    const bot = this.bot
    let refused = false
    const listen = (message) => { if (/unknown or incomplete command|permission/i.test(message)) refused = true }
    bot.on('messagestr', listen)
    bot.chat('/time query daytime')
    await sleep(1000)
    bot.removeListener('messagestr', listen)
    this.commandsOk = !refused
    return this.commandsOk
  }

  scan (from, to) {
    const lo = from.map((v, i) => Math.min(v, to[i]))
    const hi = from.map((v, i) => Math.max(v, to[i]))
    const size = hi.map((v, i) => v - lo[i] + 1)
    if (size[0] * size[1] * size[2] > MAX_SCAN) throw new Error('scan region too large')
    const { Vec3 } = require('vec3')
    const states = []
    for (let x = lo[0]; x <= hi[0]; x++) {
      for (let y = lo[1]; y <= hi[1]; y++) {
        for (let z = lo[2]; z <= hi[2]; z++) states.push(stateString(this.bot.blockAt(new Vec3(x, y, z))))
      }
    }
    return { origin: lo, size, states }
  }

  async use (pos) {
    const { Vec3 } = require('vec3')
    const block = this.bot.blockAt(new Vec3(pos[0], pos[1], pos[2]))
    if (!block) throw new Error('no block there')
    await this.bot.lookAt(block.position.offset(0.5, 0.5, 0.5), true)
    await this.bot.activateBlock(block)
    await sleep(250) // let the server confirm the new state
    return stateString(this.bot.blockAt(block.position))
  }
}

function main () {
  const args = parseArgs(process.argv.slice(2))
  const mineflayer = require('mineflayer')
  const server = { host: args.host || 'localhost', port: Number(args.port || 25565), auth: args.auth || 'offline', version: args.version || false }
  const defaultName = args.username || 'Xen'
  const bodies = new Map()

  function spawn (name, spread) {
    return new Promise((resolve, reject) => {
      if (bodies.has(name)) return resolve(bodies.get(name))
      const bot = mineflayer.createBot({ ...server, username: name })
      const body = new Body(bot, name)
      let settled = false
      const fail = (err) => { if (!settled) { settled = true; bot.end(); reject(err) } }
      bot.once('spawn', async () => {
        if (settled) return
        settled = true
        // See the world before acting in it: wait for the chunks around us.
        await withTimeout(bot.waitForChunksToLoad(), 20000).catch(() => {})
        bodies.set(name, body)
        console.log(`[xen-bridge] ${name} joined the server (${bodies.size} Xen online)`)
        // Scatter across the world (needs the bot to be allowed to run /spreadplayers).
        if (spread > 0) {
          const p = bot.entity.position
          bot.chat(`/spreadplayers ${Math.floor(p.x)} ${Math.floor(p.z)} 0 ${spread} false ${name}`)
        }
        resolve(body)
      })
      bot.once('kicked', (reason) => fail(new Error(`kicked: ${typeof reason === 'string' ? reason : JSON.stringify(reason)}`)))
      bot.once('error', (err) => fail(err))
      bot.on('end', () => {
        if (bodies.get(name) === body) {
          bodies.delete(name)
          console.error(`[xen-bridge] ${name} left the server (${bodies.size} Xen online)`)
        }
      })
      setTimeout(() => fail(new Error(`${name} could not join (timed out)`)), 45000)
    })
  }

  async function bodyFor (name) {
    if (name) {
      const body = bodies.get(name)
      if (!body) throw new Error(`no Xen named ${name}`)
      return body
    }
    if (bodies.size) return bodies.values().next().value
    return spawn(defaultName, 0)
  }

  async function step (body, action) {
    await body.waitForSpawn()
    await body.act(action)
    await sleep(50)
    if (!body.spawned) {
      // Died during the action: report it, then wait for the respawn.
      const state = { ...body.observe(), dead: true }
      await body.waitForSpawn()
      return state
    }
    return body.observe()
  }

  async function handle (msg) {
    switch (msg.op) {
      case 'spawn': {
        const body = await spawn(msg.name || defaultName, Number(msg.spread || 0))
        await body.waitForSpawn()
        return { ok: true, name: body.name, state: body.observe() }
      }
      case 'despawn': { const body = await bodyFor(msg.bot); body.bot.quit(); bodies.delete(body.name); return { ok: true } }
      case 'list': return { ok: true, bots: [...bodies.keys()] }
      case 'observe': { const body = await bodyFor(msg.bot); await body.waitForSpawn(); return { ok: true, state: body.observe() } }
      case 'act': { const body = await bodyFor(msg.bot); return { ok: true, state: await step(body, msg.action) } }
      case 'act_all': {
        const names = Object.keys(msg.actions)
        const results = await Promise.all(names.map(async (name) => {
          const body = bodies.get(name)
          if (!body) return [name, { gone: true }]
          try { return [name, await step(body, msg.actions[name])] } catch (err) { return [name, { error: String(err.message || err) }] }
        }))
        return { ok: true, states: Object.fromEntries(results) }
      }
      case 'build': { const body = await bodyFor(msg.bot); return { ok: true, ...(await body.build(msg.origin, msg.blocks, msg.mode || 'commands', msg.clear)) } }
      case 'scan': { const body = await bodyFor(msg.bot); return { ok: true, ...body.scan(msg.from, msg.to) } }
      case 'use': { const body = await bodyFor(msg.bot); return { ok: true, state: await body.use(msg.pos) } }
      case 'chat': { const body = await bodyFor(msg.bot); body.bot.chat(String(msg.text).slice(0, 250)); return { ok: true } }
      default: throw new Error(`unknown op ${msg.op}`)
    }
  }

  const bridgePort = Number(args['bridge-port'] || 8765)
  const listener = net.createServer((socket) => {
    console.log('[xen-bridge] brain connected')
    let queue = Promise.resolve()
    const lines = readline.createInterface({ input: socket })
    // A brain that disconnects abruptly must never take the Xens down with it.
    lines.on('error', () => {})
    lines.on('line', (line) => {
      queue = queue
        .then(() => handle(JSON.parse(line)))
        .catch((err) => ({ ok: false, error: String(err && err.message ? err.message : err) }))
        .then((reply) => { if (!socket.destroyed) socket.write(JSON.stringify(reply) + '\n') })
    })
    socket.on('close', () => console.log('[xen-bridge] brain disconnected'))
    socket.on('error', () => {})
  })
  listener.listen(bridgePort, '127.0.0.1', () => console.log(`[xen-bridge] waiting for Xen's brain on 127.0.0.1:${bridgePort}`))
}

if (require.main === module) main()

module.exports = { category, isHostile, parseState, B, R, DOWN, UP }
