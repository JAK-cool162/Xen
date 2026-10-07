package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The houses villages are built of, read from the game's own structure files (data/minecraft/structure/village/KIND/houses,
 * inside the game the player runs: nothing of Mojang's is copied into Xen). A Xen that comes to a village looks at its
 * houses (plains, desert, savanna, taiga or snowy, by the biome), and a little of their style rubs off on its own
 * designs. Later it may build one of them: a copy, the way a player does who likes a house they saw, out of what it has
 * (its own wood; stone for the sandstone, the terracotta and the ice it can't make), or block for block in creative.
 * Whether it copies or designs its own is its taste (curious Xens like their own ideas), and it learns from how the
 * copy went and what people say about it.
 */
final class VillageHouses {
	private VillageHouses() {}

	static final String[] KINDS = {"plains", "desert", "savanna", "taiga", "snowy"};

	/** A block of a house: where (in the house's own space), and what (a block id and its properties). */
	record Block(int x, int y, int z, String name, String props) {}

	/** A house: which file, the kind of village, its blocks, its size, its door (and which side it opens to), its design. */
	record House(String id, String kind, List<Block> blocks, int sx, int sy, int sz, BlockPos door, Direction out, Taste.Design design, int loose) {
		String name() {
			if (kind.equals("taught")) return id.substring(id.indexOf('/') + 1);   // (one of the builds it was shown: its own name)
			String n = id.substring(id.lastIndexOf('/') + 1).replace(kind + "_", "").replaceAll("_\\d+$", "").replace('_', ' ');
			return kind + " " + n;
		}
	}

	private static final Map<String, List<House>> CACHE = new HashMap<>();
	private static MinecraftServer cachedFor;

	/** The kind of village a biome has (as the game picks it). */
	static String kindOf(String biome) {
		String b = biome.toLowerCase(Locale.ROOT);
		if (b.contains("desert")) return "desert";
		if (b.contains("savanna")) return "savanna";
		if (b.contains("snowy") || b.contains("ice") || b.contains("frozen")) return "snowy";
		if (b.contains("taiga") || b.contains("grove")) return "taiga";
		return "plains";
	}

	static String kindAt(ServerLevel level, BlockPos at) {
		return kindOf(level.getBiome(at).unwrapKey().map(k -> k.identifier().getPath()).orElse("plains"));
	}

	/** The homes of a kind of village (the small, medium and big houses; not the smithies and farms), loaded once. */
	static synchronized List<House> of(MinecraftServer server, String kind) {
		if (kind.equals("taught")) return Taught.houses();                        // (the houses it was shown: see Taught)
		if (cachedFor != server) {
			CACHE.clear();
			cachedFor = server;
		}
		return CACHE.computeIfAbsent(kind, k -> load(server, k));
	}

	private static List<House> load(MinecraftServer server, String kind) {
		List<House> out = new ArrayList<>();
		var resources = server.getResourceManager();
		for (String size : new String[] {"small", "medium", "big"}) {                // (by name: the files are kind_size_house_N)
			for (int n = 1; n <= 12; n++) {
				String path = "village/" + kind + "/houses/" + kind + "_" + size + "_house_" + n;
				var res = resources.getResource(Identifier.withDefaultNamespace("structure/" + path + ".nbt"));
				if (res.isEmpty()) continue;
				try (InputStream in = res.get().open()) {
					House h = parse(path, kind, in);
					if (h != null) out.add(h);
				} catch (Exception e) {
					XenMod.LOG.debug("village house {}: {}", path, e.toString());
				}
			}
		}
		XenMod.LOG.info("Village houses ({}): {} from the game's structure files", kind, out.size());
		return out;
	}

	// ------------------------------------------------------------------------------ the structure file (NBT)
	/** A structure file's blocks, palette and size, into a house (null if it has no door, or isn't one). */
	@SuppressWarnings("unchecked")
	static House parse(String id, String kind, InputStream gz) throws IOException {
		Map<String, Object> root = readNbt(new java.util.zip.GZIPInputStream(gz));
		List<Object> size = (List<Object>) root.get("size");
		List<Object> palette = (List<Object>) root.get("palette");
		List<Object> blocks = (List<Object>) root.get("blocks");
		if (size == null || palette == null || blocks == null) return null;
		String[] names = new String[palette.size()], props = new String[palette.size()];
		for (int i = 0; i < palette.size(); i++) {
			Map<String, Object> e = (Map<String, Object>) palette.get(i);
			String n = String.valueOf(e.get("Name"));
			names[i] = n.substring(n.indexOf(':') + 1);
			StringBuilder sb = new StringBuilder();
			Object p = e.get("Properties");
			if (p instanceof Map<?, ?> pm) for (var kv : pm.entrySet()) sb.append(sb.length() == 0 ? "" : ",").append(kv.getKey()).append('=').append(kv.getValue());
			props[i] = sb.toString();
		}
		List<Block> list = new ArrayList<>();
		for (Object o : blocks) {
			Map<String, Object> b = (Map<String, Object>) o;
			List<Object> pos = (List<Object>) b.get("pos");
			int s = ((Number) b.get("state")).intValue();
			String n = names[s];
			if (n.equals("jigsaw") || n.equals("structure_void") || n.equals("structure_block")) continue;
			Block blk = new Block(((Number) pos.get(0)).intValue(), ((Number) pos.get(1)).intValue(), ((Number) pos.get(2)).intValue(), n, props[s]);
			list.add(blk);
		}
		return house(id, kind, list, ((Number) size.get(0)).intValue(), ((Number) size.get(1)).intValue(), ((Number) size.get(2)).intValue(), false);
	}

	/**
	 * Blocks into a house: its door (the lowest door's lower half) and the side it opens to (the edge it's nearest).
	 * Without a door: null, or with anyDoor (a build it was shown: a pool, a bar) the middle of its front edge, a block
	 * above its lowest layer.
	 */
	static House house(String id, String kind, List<Block> list, int sx, int sy, int sz, boolean anyDoor) {
		BlockPos door = null;
		int lowest = Integer.MAX_VALUE;
		for (Block b : list) {
			if (b.name().endsWith("_door") && b.props().contains("half=lower") && (door == null || b.y() < door.getY())) door = new BlockPos(b.x(), b.y(), b.z());
			if (!b.name().equals("air") && !b.name().equals("cave_air")) lowest = Math.min(lowest, b.y());
		}
		if (lowest == Integer.MAX_VALUE || door == null && !anyDoor) return null;
		if (door == null) door = new BlockPos(sx / 2, lowest + 1, 0);
		int[] gap = {door.getZ(), sz - 1 - door.getZ(), door.getX(), sx - 1 - door.getX()};   // (the side the door opens to)
		Direction[] side = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
		int best = 0;
		for (int i = 1; i < 4; i++) if (gap[i] < gap[best]) best = i;
		House h = new House(id, kind, list, sx, sy, sz, door, side[best], null, 0);
		return new House(id, kind, list, sx, sy, sz, door, side[best], designOf(h), loose(list));
	}

	/**
	 * How many of its blocks can't be built from the ground up, a block at a time against another (an igloo's dome that
	 * only touches the rest through snow or at a corner): a house with any isn't one to copy.
	 */
	static int loose(List<Block> blocks) {
		java.util.Set<Long> solid = new java.util.HashSet<>(), held = new java.util.HashSet<>();
		int lowest = Integer.MAX_VALUE;
		for (Block b : blocks) {
			if (b.name().equals("air") || b.name().equals("cave_air") || decor(b.name())) continue;
			solid.add(BlockPos.asLong(b.x(), b.y(), b.z()));
			lowest = Math.min(lowest, b.y());
		}
		java.util.ArrayDeque<Long> todo = new java.util.ArrayDeque<>();
		for (long k : solid) if (BlockPos.getY(k) == lowest) { held.add(k); todo.add(k); }
		while (!todo.isEmpty()) {
			BlockPos q = BlockPos.of(todo.poll());
			for (Direction d : Direction.values()) {
				long n = q.relative(d).asLong();
				if (solid.contains(n) && held.add(n)) todo.add(n);
			}
		}
		return solid.size() - held.size();
	}

	/** Named binary tags, as a map (compounds), lists, numbers, strings and arrays. */
	static Map<String, Object> readNbt(InputStream raw) throws IOException {
		DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
		int type = in.readByte();
		if (type != 10) throw new IOException("not a compound");
		in.readUTF();
		@SuppressWarnings("unchecked") Map<String, Object> root = (Map<String, Object>) tag(in, 10);
		return root;
	}

	private static Object tag(DataInputStream in, int type) throws IOException {
		switch (type) {
			case 1: return in.readByte();
			case 2: return in.readShort();
			case 3: return in.readInt();
			case 4: return in.readLong();
			case 5: return in.readFloat();
			case 6: return in.readDouble();
			case 7: { byte[] a = new byte[in.readInt()]; in.readFully(a); return a; }
			case 8: return in.readUTF();
			case 9: {
				int et = in.readByte(), n = in.readInt();
				List<Object> l = new ArrayList<>(Math.max(0, n));
				for (int i = 0; i < n; i++) l.add(tag(in, et));
				return l;
			}
			case 10: {
				Map<String, Object> m = new LinkedHashMap<>();
				while (true) {
					int t = in.readByte();
					if (t == 0) return m;
					String k = in.readUTF();
					m.put(k, tag(in, t));
				}
			}
			case 11: { int n = in.readInt(); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = in.readInt(); return a; }
			case 12: { int n = in.readInt(); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = in.readLong(); return a; }
			default: throw new IOException("bad tag " + type);
		}
	}

	// ------------------------------------------------------------------------------ what it looks like, as a design
	private static boolean stoneish(String n) {
		return n.contains("cobblestone") || n.contains("stone_brick") || n.equals("stone") || n.contains("smooth_stone") || n.contains("bricks")
				|| n.contains("sandstone");
	}

	/** The house as Xen's own design choices would describe it (what it learns to like from looking at it). */
	static Taste.Design designOf(House h) {
		int doorY = h.door().getY(), minX = 99, maxX = -1, minZ = 99, maxZ = -1, top = 0;
		Map<Integer, int[]> layer = new HashMap<>();                              // y -> {solid, stairs or slabs}
		int logs = 0, trapdoors = 0, fences = 0, ladders = 0, flowers = 0, water = 0, stations = 0, leaves = 0, baseStone = 0, base = 0;
		int lowStone = 0, low = 0;
		java.util.Set<String> roofFacings = new java.util.HashSet<>();
		for (Block b : h.blocks()) {
			String n = b.name();
			if (n.equals("air") || n.equals("cave_air")) continue;
			minX = Math.min(minX, b.x()); maxX = Math.max(maxX, b.x()); minZ = Math.min(minZ, b.z()); maxZ = Math.max(maxZ, b.z());
			top = Math.max(top, b.y());
			int[] l = layer.computeIfAbsent(b.y(), k -> new int[2]);
			l[0]++;
			if (n.endsWith("_stairs") || n.endsWith("_slab")) {
				l[1]++;
				if (b.y() > doorY + 1 && n.endsWith("_stairs")) {
					var m = java.util.regex.Pattern.compile("facing=(\\w+)").matcher(b.props());
					if (m.find()) roofFacings.add(m.group(1));
				}
			}
			if (n.endsWith("_log") || n.endsWith("_wood")) logs++;
			if (n.endsWith("_trapdoor")) trapdoors++;
			if (n.endsWith("_fence")) fences++;
			if (n.equals("ladder")) ladders++;
			if (n.contains("poppy") || n.contains("dandelion") || n.contains("tulip") || n.equals("farmland") || n.startsWith("potted_")) flowers++;
			if (n.equals("water")) water++;
			if (n.endsWith("_table") || n.equals("loom") || n.equals("stonecutter") || n.equals("blast_furnace") || n.equals("smoker") || n.equals("grindstone")) stations++;
			if (n.endsWith("_leaves")) leaves++;
			if (b.y() < doorY) { base++; if (stoneish(n)) baseStone++; }
			if (b.y() == doorY) { low++; if (stoneish(n)) lowStone++; }
		}
		int w = Math.max(5, maxX - minX + 1), d = Math.max(5, maxZ - minZ + 1);
		int roofAt = top;                                                         // the lowest layer above the door that's mostly roof
		for (int y = doorY + 2; y <= top; y++) {
			int[] l = layer.get(y);
			if (l != null && l[1] * 2 >= l[0]) { roofAt = y; break; }
		}
		int wallH = Math.max(3, Math.min(6, roofAt - doorY));
		Taste.Roof roof = roofFacings.size() >= 4 ? Taste.Roof.HIP : roofFacings.size() >= 2 ? Taste.Roof.GABLE : Taste.Roof.FLAT;
		boolean tower = top - doorY > 1.5 * Math.max(w, d);
		Taste.Style style = tower ? Taste.Style.TOWER : roof == Taste.Roof.FLAT ? Taste.Style.MODERN : Taste.Style.COTTAGE;
		return new Taste.Design(w, d, wallH, roof, w >= d, base > 0 && baseStone * 2 > base, logs >= 12, low > 0 && lowStone * 5 > low * 2,
				trapdoors >= 2, fences >= 4 && fences < 8, false, leaves > 0, ladders > 0, fences >= 8, flowers > 0, style, water > 0, stations > 0);
	}

	// ------------------------------------------------------------------------------ seeing a village, and copying a house
	/** At a village: it looks at a few of its houses (their style rubs off a little), and remembers the kind. */
	static void saw(Companion c, ServerLevel level, BlockPos village) {
		String kind = kindAt(level, village);
		List<House> houses = of(level.getServer(), kind);
		if (houses.isEmpty()) return;
		boolean first = c.taste.liking.getOrDefault("seen:" + kind, 0f) < 0.5f;
		c.taste.liking.put("seen:" + kind, 1f);
		Random r = new Random(village.asLong());
		for (int i = 0; i < 2; i++) c.taste.admire(houses.get(r.nextInt(houses.size())).design());
		if (first) c.journal("learns", "looked at the houses of a " + kind + " village (their style rubs off a little)");
	}

	/** The kinds of village it has seen houses of. */
	static List<String> seen(Companion c) {
		List<String> out = new ArrayList<>();
		for (String k : KINDS) if (c.taste.liking.getOrDefault("seen:" + k, 0f) >= 0.5f) out.add(k);
		return out;
	}

	/** A house of a kind it has seen that it could build here (in survival, one on flat ground, mostly of what it can make), or null. */
	static House pick(Companion c, MinecraftServer server, String kind, boolean creative, Random r) {
		List<String> kinds = new ArrayList<>(kind != null ? List.of(kind) : seen(c));
		if (kind == null) kinds.add("taught");                                   // (and the houses it was shown)
		List<House> fit = new ArrayList<>();
		for (String k : kinds) {
			for (House h : of(server, k)) {
				if (h.loose() > 0) continue;                                         // (some of it can't be built a block at a time)
				if (creative) { fit.add(h); continue; }
				if (h.door().getY() > 2 || h.sx() > 13 || h.sz() > 13) continue;     // (on a mound of earth, or too big for a first copy)
				int solid = 0, ok = 0;
				for (Block b : h.blocks()) {
					if (b.name().equals("air") || decor(b.name())) continue;
					solid++;
					String a = adapt(b.name(), "oak");
					if (a.endsWith("_planks") || a.endsWith("_log") || a.endsWith("_stairs") || a.endsWith("_slab") || a.startsWith("cobblestone")
							|| a.equals("dirt") || a.endsWith("_door") || a.endsWith("_fence") || a.endsWith("_trapdoor")) ok++;
				}
				if (solid > 0 && ok * 10 >= solid * 9) fit.add(h);
			}
		}
		return fit.isEmpty() ? null : fit.get(r.nextInt(fit.size()));
	}

	/** Things a house has that it can do without (it puts them in if it has them). */
	static boolean decor(String n) {
		return n.endsWith("_bed") || n.contains("torch") || n.equals("lantern") || n.endsWith("_carpet") || n.startsWith("potted_") || n.equals("flower_pot")
				|| n.equals("chest") || n.equals("barrel") || n.equals("crafting_table") || n.equals("furnace") || n.equals("smoker") || n.equals("blast_furnace")
				|| n.endsWith("_table") || n.equals("loom") || n.equals("stonecutter") || n.equals("grindstone") || n.equals("composter") || n.equals("cauldron")
				|| n.equals("water_cauldron") || n.equals("lectern") || n.equals("brewing_stand") || n.equals("bell") || n.equals("hay_block") || n.equals("ladder")
				|| n.endsWith("_sign") || n.endsWith("_button") || n.endsWith("_pressure_plate") || n.equals("snow") || n.equals("farmland") || n.equals("wheat")
				|| n.equals("water") || n.equals("dirt_path") || n.contains("grass") && !n.equals("grass_block") || n.contains("fern") || n.equals("poppy")
				|| n.equals("dandelion") || n.contains("tulip") || n.endsWith("_leaves") || n.equals("glass_pane") || n.equals("glass") || n.endsWith("_banner")
				|| n.equals("cactus") || n.equals("anvil") || n.equals("bookshelf");
	}

	private static final String[] WOODS = {"dark_oak", "pale_oak", "oak", "spruce", "birch", "jungle", "acacia", "mangrove", "cherry", "bamboo", "crimson", "warped"};

	/**
	 * In survival: a block of the house as it can make it, the way a player copies a house with what they have: its own
	 * wood for any wood; stone for the sandstone, the bricks and smooth stone (cobblestone and its stairs and slabs);
	 * planks for the terracotta, the wool and the clay; dirt for the grass and the sand; logs for the stripped logs.
	 */
	static String adapt(String n, String wood) {
		if (n.startsWith("stripped_")) n = n.substring(9);
		if (n.endsWith("_wood")) n = n.substring(0, n.length() - 5) + "_log";
		for (String w : WOODS) {                                                  // (dark_oak_ and pale_oak_ don't start with oak_)
			if (n.startsWith(w + "_")) {
				String rest = n.substring(w.length());
				if (rest.equals("_log") || rest.equals("_planks") || rest.equals("_stairs") || rest.equals("_slab") || rest.equals("_fence") || rest.equals("_door")
						|| rest.equals("_trapdoor") || rest.equals("_fence_gate") || rest.equals("_pressure_plate") || rest.equals("_button")) return wood + rest;
				break;
			}
		}
		if (n.endsWith("terracotta") || n.endsWith("_wool") || n.equals("clay") || n.endsWith("_concrete") || n.equals("snow_block") || n.equals("packed_ice")) {
			return n.equals("snow_block") || n.equals("packed_ice") ? "cobblestone" : wood + "_planks";
		}
		if (n.equals("grass_block") || n.equals("sand") || n.equals("red_sand") || n.equals("coarse_dirt")) return "dirt";
		if (stoneish(n) || n.equals("andesite") || n.equals("mossy_cobblestone")) {
			if (n.endsWith("_stairs")) return "cobblestone_stairs";
			if (n.endsWith("_slab")) return "cobblestone_slab";
			if (n.endsWith("_wall")) return "cobblestone_wall";
			return "cobblestone";
		}
		return n;
	}

	/** The rotation that turns the house's door side to face front. */
	private static Rotation turn(Direction out, Direction front) {
		for (Rotation r : Rotation.values()) if (r.rotate(out) == front) return r;
		return Rotation.NONE;
	}

	private static int[] rotated(int x, int z, Rotation r) {
		return switch (r) {
			case CLOCKWISE_90 -> new int[] {-z, x};
			case CLOCKWISE_180 -> new int[] {-x, -z};
			case COUNTERCLOCKWISE_90 -> new int[] {z, -x};
			default -> new int[] {x, z};
		};
	}

	/**
	 * The house as a plan at a site (its front-left corner at floor level, door facing front): every block of it, the
	 * air inside dug out, in survival made of what it can make (wood its own kind). The door goes one above the floor.
	 */
	static Architect.Plan plan(House h, BlockPos corner, Direction front, boolean creative, String wood) {
		Rotation r = turn(h.out(), front);
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (Block b : h.blocks()) {
			int[] q = rotated(b.x(), b.z(), r);
			minX = Math.min(minX, q[0]); minZ = Math.min(minZ, q[1]); maxX = Math.max(maxX, q[0]); maxZ = Math.max(maxZ, q[1]);
		}
		int wx = maxX - minX + 1, wz = maxZ - minZ + 1;
		boolean alongX = front.getAxis() == Direction.Axis.Z;                     // the house's width runs along x when it faces north or south
		int width = alongX ? wx : wz, depth = alongX ? wz : wx;
		Architect.Layout L = new Architect.Layout(corner, front);
		BlockPos a = L.pos(0, 0, 0), b2 = L.pos(width - 1, depth - 1, 0);
		int bx = Math.min(a.getX(), b2.getX()), bz = Math.min(a.getZ(), b2.getZ());
		int dy = corner.getY() + 1 - h.door().getY();
		List<Architect.Step> steps = new ArrayList<>();
		BlockPos door = null;
		int doorY = h.door().getY();
		for (Block b : h.blocks()) {
			int[] q = rotated(b.x(), b.z(), r);
			BlockPos p = new BlockPos(bx + q[0] - minX, b.y() + dy, bz + q[1] - minZ);
			if (b.name().equals("air") || b.name().equals("cave_air")) {
				if (b.y() >= doorY) steps.add(new Architect.Step(p, null, Architect.DIG, false));
				continue;
			}
			String n = creative ? b.name() : adapt(b.name(), wood);
			BlockState st = Architect.state(n + (b.props().isEmpty() ? "" : "[" + b.props() + "]"));
			if (st == null) continue;
			st = st.rotate(r);
			boolean decor = decor(b.name());
			// what's under the door, then the whole shell from the ground up in one go (a roof block or a dome rests on the
			// layer below it, whatever it's made of), the doors, and last what goes in and on it
			int phase = decor ? Architect.INSIDE : n.endsWith("_door") ? Architect.DOORS : b.y() < doorY ? Architect.SUPPORT : Architect.WALLS;
			steps.add(new Architect.Step(p, st, phase, decor));
			if (b.x() == h.door().getX() && b.y() == h.door().getY() && b.z() == h.door().getZ()) door = p;
		}
		steps.sort((s1, s2) -> s1.phase() != s2.phase() ? Integer.compare(s1.phase(), s2.phase())
				: s1.phase() == Architect.DIG ? Integer.compare(s2.pos().getY(), s1.pos().getY()) : Integer.compare(s1.pos().getY(), s2.pos().getY()));
		BlockPos middle = new BlockPos(bx + wx / 2, corner.getY() + 1, bz + wz / 2);
		AABB inside = new AABB(bx + 1, corner.getY() + 1, bz + 1, bx + wx - 1, corner.getY() + 3, bz + wz - 1);
		return new Architect.Plan(h.kind().equals("taught") ? h.name() : "village house", steps, door == null ? middle : door, middle, front, inside);
	}

	/** Its width and depth when it faces front (for finding a site). */
	static int[] footprint(House h, Direction front) {
		Rotation r = turn(h.out(), front);
		boolean swap = r == Rotation.CLOCKWISE_90 || r == Rotation.COUNTERCLOCKWISE_90;
		int wx = swap ? h.sz() : h.sx(), wz = swap ? h.sx() : h.sz();
		return front.getAxis() == Direction.Axis.Z ? new int[] {wx, wz} : new int[] {wz, wx};
	}
}
