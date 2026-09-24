package xen.mod.core;

/** Block categories Xen perceives (must match xen/blocks.py). */
public final class Blocks {
	public static final int AIR = 0, GRASS = 1, DIRT = 2, STONE = 3, LOG = 4, LEAVES = 5, COAL = 6, IRON = 7,
			GOLD = 8, DIAMOND = 9, LAVA = 10, WATER = 11, BEDROCK = 12, COUNT = 13;

	public static final String[] NAMES = {"air", "grass", "dirt", "stone", "log", "leaves", "coal ore", "iron ore",
			"gold ore", "diamond ore", "lava", "water", "bedrock"};
	public static final boolean[] SOLID = {false, true, true, true, true, true, true, true, true, true, false, false, true};
	public static final boolean[] OPAQUE = {false, true, true, true, true, true, true, true, true, true, true, false, true};
	/** Satisfaction of mining one (value of what drops). */
	public static final float[] VALUE = {0f, 0.05f, 0.05f, 0.1f, 1f, 0f, 1.5f, 3f, 4f, 10f, 0f, 0f, 0f};
	public static final boolean[] TREASURE = new boolean[COUNT];
	public static final float[] VALUE_NORM = new float[COUNT];

	static {
		float max = 0;
		for (float v : VALUE) max = Math.max(max, v);
		for (int i = 0; i < COUNT; i++) {
			TREASURE[i] = VALUE[i] >= 1f;
			VALUE_NORM[i] = (float) (Math.log1p(VALUE[i]) / Math.log1p(max));
		}
	}

	private Blocks() {}
}
