package xen.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A Litematica schematic (.litematic), read without Litematica: each region's palette and its packed block states
 * (gzipped NBT, or plain NBT as some tools save it). The files go in config/xen/schematics/; next to one, a note of the
 * same name ending in .json may say what it is for: {"makes": "smelted iron", "output": [x, y, z], "stand": [x, y, z],
 * "refill": "raw iron and coal into the top chests", "switch": [x, y, z]} (positions from the schematic's corner).
 *
 * <p>{@link #plan} turns it into a plan for the builder, in the order a player builds a machine: what's in the way dug
 * out, solid blocks from the bottom up, sand and gravel once there is something under them, shaped blocks (stairs,
 * slabs, fences, doors), chests, hoppers and furnaces, then the redstone, and last the water, the lava and the switch
 * that turns it on. The exact block states (facing, half, shape) are the plan's.
 */
final class Schematic {
	/** One block, from the schematic's lowest corner, as "oak_stairs[facing=north,half=bottom]". */
	record Block(BlockPos at, String spec) {}

	final String name;
	final List<Block> blocks;
	/** The cells that are empty in it (what's there in the world is dug out). */
	final List<BlockPos> empty;
	final int sizeX, sizeY, sizeZ;
	/** Its note (what it makes, where its output is...), or an empty one. */
	final JsonObject note;

	private Schematic(String name, List<Block> blocks, List<BlockPos> empty, int sx, int sy, int sz, JsonObject note) {
		this.name = name;
		this.blocks = blocks;
		this.empty = empty;
		this.sizeX = sx;
		this.sizeY = sy;
		this.sizeZ = sz;
		this.note = note;
	}

	static Path dir() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("xen").resolve("schematics");
	}

	/** The schematics there are (names without .litematic). */
	static List<String> list() {
		List<String> out = new ArrayList<>();
		try {
			Files.createDirectories(dir());                                    // (there to drop them in)
		} catch (IOException ignored) {
		}
		try (var s = Files.list(dir())) {
			s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".litematic")).sorted().forEach(n -> out.add(n.substring(0, n.length() - 10)));
		} catch (IOException ignored) {
		}
		return out;
	}

	/** By name ("coal-farm", or with .litematic), from config/xen/schematics/; a name that's part of one file's name is enough. */
	static Schematic load(String name) throws IOException {
		String n = name.endsWith(".litematic") ? name.substring(0, name.length() - 10) : name;
		Path file = dir().resolve(n + ".litematic");
		if (!Files.exists(file)) {
			for (String s : list()) {
				if (s.toLowerCase(Locale.ROOT).contains(n.toLowerCase(Locale.ROOT))) {
					file = dir().resolve(s + ".litematic");
					n = s;
					break;
				}
			}
		}
		if (!Files.exists(file)) throw new IOException("no schematic called " + name + " in config/xen/schematics/");
		JsonObject note = new JsonObject();
		Path notePath = dir().resolve(n + ".json");
		if (Files.exists(notePath)) {
			try {
				note = JsonParser.parseString(Files.readString(notePath)).getAsJsonObject();
			} catch (RuntimeException e) {
				XenMod.LOG.warn("Xen: couldn't read the note {}: {}", notePath.getFileName(), e.toString());
			}
		}
		return read(n, Files.readAllBytes(file), note);
	}

	@SuppressWarnings("unchecked")
	static Schematic read(String name, byte[] raw, JsonObject note) throws IOException {
		if (raw.length > 64 * 1024 * 1024) throw new IOException("too big");
		byte[] data = raw.length > 1 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b ? new GZIPInputStream(new ByteArrayInputStream(raw)).readAllBytes() : raw;
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
		int type = in.readByte();
		if (type != 10) throw new IOException("not a schematic (no NBT compound)");
		in.readUTF();
		Map<String, Object> root = (Map<String, Object>) tag(in, 10, 0);
		Object regions = root.get("Regions");
		if (!(regions instanceof Map)) throw new IOException("not a Litematica schematic (no regions)");
		// all regions into one box: each region's lowest corner (a negative size runs back from its position)
		List<Block> blocks = new ArrayList<>();
		List<BlockPos> empty = new ArrayList<>();
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (Object ro : ((Map<String, Object>) regions).values()) {
			Map<String, Object> r = (Map<String, Object>) ro;
			Map<String, Object> pos = (Map<String, Object>) r.get("Position"), size = (Map<String, Object>) r.get("Size");
			int px = num(pos.get("x")), py = num(pos.get("y")), pz = num(pos.get("z"));
			int sx = num(size.get("x")), sy = num(size.get("y")), sz = num(size.get("z"));
			int ox = px + (sx < 0 ? sx + 1 : 0), oy = py + (sy < 0 ? sy + 1 : 0), oz = pz + (sz < 0 ? sz + 1 : 0);
			sx = Math.abs(sx);
			sy = Math.abs(sy);
			sz = Math.abs(sz);
			List<Object> palette = (List<Object>) r.get("BlockStatePalette");
			long[] states = (long[]) r.get("BlockStates");
			if (palette == null || states == null || palette.isEmpty()) continue;
			String[] specs = new String[palette.size()];
			for (int i = 0; i < specs.length; i++) specs[i] = spec((Map<String, Object>) palette.get(i));
			int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
			long mask = (1L << bits) - 1;
			long cells = (long) sx * sy * sz;
			if (cells > 4_000_000) throw new IOException("too big (" + cells + " cells)");
			for (int i = 0; i < cells; i++) {
				long start = (long) i * bits;
				int li = (int) (start >>> 6), off = (int) (start & 63);
				if (li >= states.length) break;
				long v = states[li] >>> off;
				if (off + bits > 64 && li + 1 < states.length) v |= states[li + 1] << (64 - off);
				int id = (int) (v & mask);
				if (id >= specs.length) continue;
				int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
				BlockPos at = new BlockPos(ox + x, oy + y, oz + z);
				String s = specs[id];
				if (s == null) continue;                                       // (a block from another mod: left out)
				if (s.isEmpty()) empty.add(at);
				else blocks.add(new Block(at, s));
			}
			minX = Math.min(minX, ox);
			minY = Math.min(minY, oy);
			minZ = Math.min(minZ, oz);
			maxX = Math.max(maxX, ox + sx - 1);
			maxY = Math.max(maxY, oy + sy - 1);
			maxZ = Math.max(maxZ, oz + sz - 1);
		}
		if (minX == Integer.MAX_VALUE) throw new IOException("an empty schematic");
		BlockPos o = new BlockPos(minX, minY, minZ);
		List<Block> shifted = new ArrayList<>(blocks.size());
		for (Block b : blocks) shifted.add(new Block(b.at().subtract(o), b.spec()));
		List<BlockPos> hollow = new ArrayList<>(empty.size());
		for (BlockPos e : empty) hollow.add(e.subtract(o));
		return new Schematic(name, shifted, hollow, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, note);
	}

	/** A palette entry as a spec ("" for air, null for a block from another mod). */
	@SuppressWarnings("unchecked")
	private static String spec(Map<String, Object> e) {
		String id = String.valueOf(e.get("Name"));
		if (id.startsWith("minecraft:")) id = id.substring(10);
		else if (id.contains(":")) return null;
		if (id.equals("air") || id.equals("void_air") || id.equals("cave_air") || id.equals("structure_void")) return "";
		Object props = e.get("Properties");
		if (!(props instanceof Map) || ((Map<String, Object>) props).isEmpty()) return id;
		StringBuilder sb = new StringBuilder(id).append('[');
		boolean first = true;
		for (var kv : ((Map<String, Object>) props).entrySet()) {
			if (!first) sb.append(',');
			first = false;
			sb.append(kv.getKey()).append('=').append(kv.getValue());
		}
		return sb.append(']').toString();
	}

	private static int num(Object o) {
		return o instanceof Number n ? n.intValue() : 0;
	}

	/** One NBT tag's value: numbers, strings, lists, compounds (maps) and arrays. */
	private static Object tag(DataInputStream in, int type, int depth) throws IOException {
		if (depth > 64) throw new IOException("nested too deep");
		switch (type) {
			case 1: return in.readByte();
			case 2: return in.readShort();
			case 3: return in.readInt();
			case 4: return in.readLong();
			case 5: return in.readFloat();
			case 6: return in.readDouble();
			case 7: {
				int n = in.readInt();
				byte[] b = new byte[Math.max(0, n)];
				in.readFully(b);
				return b;
			}
			case 8: {
				int n = in.readUnsignedShort();
				byte[] b = new byte[n];
				in.readFully(b);
				return new String(b, StandardCharsets.UTF_8);       // (modified UTF-8 in NBT: the same for anything a block name holds)
			}
			case 9: {
				int et = in.readByte(), n = in.readInt();
				List<Object> l = new ArrayList<>(Math.max(0, Math.min(n, 1 << 16)));
				for (int i = 0; i < n; i++) l.add(tag(in, et, depth + 1));
				return l;
			}
			case 10: {
				Map<String, Object> m = new HashMap<>();
				while (true) {
					int t = in.readByte();
					if (t == 0) return m;
					String k = in.readUTF();
					m.put(k, tag(in, t, depth + 1));
				}
			}
			case 11: {
				int n = in.readInt();
				int[] a = new int[Math.max(0, n)];
				for (int i = 0; i < n; i++) a[i] = in.readInt();
				return a;
			}
			case 12: {
				int n = in.readInt();
				long[] a = new long[Math.max(0, n)];
				for (int i = 0; i < n; i++) a[i] = in.readLong();
				return a;
			}
			default: throw new IOException("bad NBT tag " + type);
		}
	}

	// ------------------------------------------------------------------------------------------------ as a plan
	private static final java.util.Set<String> FALLING = java.util.Set.of("sand", "red_sand", "gravel", "anvil", "chipped_anvil", "damaged_anvil",
			"suspicious_sand", "suspicious_gravel", "dragon_egg", "pointed_dripstone", "scaffolding");
	private static final java.util.Set<String> REDSTONE = java.util.Set.of("redstone_wire", "repeater", "comparator", "redstone_torch", "redstone_wall_torch",
			"observer", "piston", "sticky_piston", "dropper", "dispenser", "tripwire_hook", "tripwire", "daylight_detector", "target", "redstone_lamp",
			"note_block", "crafter", "redstone_block", "sculk_sensor", "calibrated_sculk_sensor");
	private static final java.util.Set<String> CONTAINERS = java.util.Set.of("chest", "trapped_chest", "barrel", "hopper", "furnace", "blast_furnace",
			"smoker", "brewing_stand", "crafting_table", "composter", "cauldron", "water_cauldron", "lava_cauldron", "enchanting_table", "anvil",
			"smithing_table", "stonecutter", "loom", "grindstone", "lectern", "jukebox", "ender_chest", "bell");

	/** Where each kind of block comes in the build (Architect's phases). */
	static int phase(String spec, BlockState st) {
		String n = spec.indexOf('[') >= 0 ? spec.substring(0, spec.indexOf('[')) : spec;
		if (n.equals("water") || n.equals("lava") || n.equals("bubble_column") || n.equals("lever") || n.endsWith("_button") || n.endsWith("_door")) return Architect.DOORS;
		if (REDSTONE.contains(n) || n.endsWith("_pressure_plate") || n.endsWith("_rail") || n.equals("rail")) return Architect.OUTSIDE;
		if (CONTAINERS.contains(n) || n.endsWith("shulker_box") || n.endsWith("_bed")) return Architect.INSIDE;
		if (FALLING.contains(n) || n.endsWith("concrete_powder")) return Architect.FRAME;
		if (st != null && st.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
				&& !(st.getBlock() instanceof net.minecraft.world.level.block.EntityBlock)) return Architect.SUPPORT;
		boolean attached = n.endsWith("torch") || n.equals("lantern") || n.equals("soul_lantern") || n.equals("ladder") || n.endsWith("_carpet")
				|| n.equals("snow") || n.endsWith("_sign") || n.endsWith("_banner") || n.equals("vine") || n.equals("flower_pot") || n.startsWith("potted_")
				|| n.endsWith("_chain") || n.equals("chain") || n.equals("iron_chain");
		return attached ? Architect.INSIDE : Architect.WALLS;
	}

	/** Flowers, carpets, snow, pots: what a survival Xen may leave out if it has nothing to make it from. */
	private static boolean decor(String n) {
		return n.endsWith("_carpet") || n.equals("snow") || n.startsWith("potted_") || n.equals("flower_pot") || n.endsWith("_tulip") || n.equals("poppy")
				|| n.equals("dandelion") || n.equals("cornflower") || n.endsWith("fern") || n.equals("short_grass") || n.equals("tall_grass")
				|| n.endsWith("_banner") || n.equals("lantern") || n.equals("candle") || n.endsWith("_candle");
	}

	/**
	 * A plan for the builder with its lowest corner at origin (no turning: as it was saved). What's solid in the world
	 * where the schematic is empty is dug out first; the upper half of a door, a bed's head and a tall plant's top come
	 * with their other half.
	 */
	Architect.Plan plan(ServerLevel level, BlockPos origin) {
		List<Architect.Step> steps = new ArrayList<>();
		for (BlockPos e : empty) {
			BlockPos w = origin.offset(e);
			var here = level.getBlockState(w);
			if (!here.isAir() && !here.canBeReplaced()) steps.add(new Architect.Step(w, null, Architect.DIG, false));
		}
		for (Block b : blocks) {
			String s = b.spec();
			if (s.contains("half=upper") && !s.contains("_stairs") && !s.contains("_trapdoor") || s.contains("part=head")) continue;
			BlockState st = Architect.state(s);
			if (st == null) continue;
			String n = s.indexOf('[') >= 0 ? s.substring(0, s.indexOf('[')) : s;
			steps.add(new Architect.Step(origin.offset(b.at()), st, phase(s, st), decor(n)));
		}
		steps.sort(java.util.Comparator.comparingInt(Architect.Step::phase).thenComparingInt(st -> st.pos().getY()));   // (each phase from the bottom up: something under it first)
		BlockPos middle = origin.offset(sizeX / 2, 0, sizeZ / 2);
		return new Architect.Plan(name, steps, middle, middle, Direction.NORTH, new net.minecraft.world.phys.AABB(middle));
	}

	/** What it says in /xen schematics. */
	String describe() {
		return String.format(Locale.ROOT, "%s: %d blocks, %dx%dx%d%s", name, blocks.size(), sizeX, sizeY, sizeZ,
				note.has("makes") ? ", makes " + note.get("makes").getAsString() : "");
	}
}
