package xen.mod;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * How a Xen looks: its name and its skin.
 * <ul>
 *   <li>Built-in skins are Minecraft's own 18 default skins: every game has them, nothing to download. The game picks
 *   one from a player's UUID, so a Xen gets the skin it should by getting the right UUID (the same one every time for
 *   its name, so it keeps its things).</li>
 *   <li>Custom skins are signed skin textures, like the ones mineskin.org makes from any skin image:
 *   {@code "texture:<value>:<signature>"} in the {@code skins} setting.</li>
 * </ul>
 */
final class Looks {
	/** In the game's order (DefaultPlayerSkin): the UUID hash picks one of these 18. */
	static final String[] SKINS = {"alex:slim", "ari:slim", "efe:slim", "kai:slim", "makena:slim", "noor:slim", "steve:slim",
			"sunny:slim", "zuri:slim", "alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};

	/** Names for Xens when none is given (all up to 16 letters). */
	static final String[] NAMES = {"Pip", "Nova", "Bramble", "Juniper", "Pebble", "Rowan", "Sprocket", "Maple", "Fennel", "Tinker",
			"Wren", "Clover", "Ember", "Moss", "Quill", "Sable", "Tansy", "Birch", "Cobble", "Nimbus", "Pickle", "Rune", "Sorrel",
			"Thistle", "Umber", "Violet", "Willow", "Yarrow", "Zephyr", "Acorn", "Basil", "Cinder", "Dusk", "Echo", "Flint", "Gale",
			"Hazel", "Iris", "Jasper", "Kestrel", "Lark", "Mica", "Nettle", "Onyx", "Poppy", "Quartz", "Reed", "Slate", "Tallow",
			"Vesper", "Wisp", "Xeno", "Yew", "Zinnia", "Biscuit", "Noodle", "Muffin", "Waffle", "Pudding", "Sprout"};

	private Looks() {}

	/** A name nobody has yet: from the list, or Xen, Xen2, ... when random names are off. */
	static String freshName(boolean random, Set<String> taken, Random r) {
		if (random) {
			for (int tries = 0; tries < 200; tries++) {
				String n = NAMES[r.nextInt(NAMES.length)];
				if (tries > 100) n = n + (2 + r.nextInt(98));
				if (!taken.contains(n.toLowerCase(Locale.ROOT))) return n;
			}
		}
		String n = "Xen";
		for (int i = 2; taken.contains(n.toLowerCase(Locale.ROOT)); i++) n = "Xen" + i;
		return n;
	}

	/** Pick a skin from the setting: built-in names, "random", or custom textures. */
	static String pickSkin(List<String> choices, Random r) {
		if (choices == null || choices.isEmpty()) return "random";
		String s = choices.get(r.nextInt(choices.size())).trim();
		return s.equalsIgnoreCase("random") ? SKINS[r.nextInt(SKINS.length)] : s;
	}

	static int builtIn(String skin) {
		String s = skin.toLowerCase(Locale.ROOT).replace(":wide", "");
		for (int i = 0; i < SKINS.length; i++) if (SKINS[i].equals(s)) return i;
		return -1;
	}

	/** Its UUID: always the same for its name, and (for a built-in skin) one the game shows with that skin. */
	static UUID uuid(String name, int skin) {
		for (int k = 0; ; k++) {
			UUID u = UUID.nameUUIDFromBytes(("XenCompanion:" + name + ":" + k).getBytes(StandardCharsets.UTF_8));
			if (skin < 0 || Math.floorMod(u.hashCode(), SKINS.length) == skin) return u;
		}
	}

	/** The profile the game shows for it: name, UUID, and a custom skin when it has one. */
	static GameProfile profile(String name, String skin) {
		if (skin != null && skin.startsWith("texture:")) {
			String[] parts = skin.split(":", 3);
			if (parts.length == 3) {
				Multimap<String, Property> props = LinkedHashMultimap.create();
				props.put("textures", new Property("textures", parts[1], parts[2]));
				return new GameProfile(uuid(name, -1), name, new PropertyMap(props));
			}
		}
		return new GameProfile(uuid(name, skin == null ? -1 : builtIn(skin)), name);
	}
}
