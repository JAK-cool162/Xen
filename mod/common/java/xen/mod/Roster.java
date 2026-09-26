package xen.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Who the Xens are, kept with the world ({@code <world>/xen/companions.json}): each one's personality, skin, team
 * and the players it knows. Summon "Pip" again and it's the same Pip.
 */
final class Roster {
	private final Map<String, JsonObject> byName = new LinkedHashMap<>();
	private Path file;

	void load(Path file) {
		this.file = file;
		byName.clear();
		try {
			if (!Files.exists(file)) return;
			JsonObject all = new Gson().fromJson(Files.readString(file), JsonObject.class);
			for (Map.Entry<String, JsonElement> e : all.entrySet()) byName.put(e.getKey(), e.getValue().getAsJsonObject());
		} catch (IOException | RuntimeException e) {
			XenMod.LOG.warn("Could not read {}: {}", file, e.toString());
		}
	}

	JsonObject get(String name) {
		return byName.get(name.toLowerCase(Locale.ROOT));
	}

	boolean has(String name) {
		return byName.containsKey(name.toLowerCase(Locale.ROOT));
	}

	void remember(Companion c, String team) {
		JsonObject o = new JsonObject();
		o.addProperty("name", c.name);
		o.add("personality", c.personality.toJson());
		o.addProperty("skin", c.skin);
		if (team != null) o.addProperty("team", team);
		JsonArray known = new JsonArray();
		for (UUID u : c.known) known.add(u.toString());
		o.add("known", known);
		o.add("likes", c.goals.toJson());
		o.add("goals", c.goals.dreamJson());
		JsonObject trust = new JsonObject();
		c.trust.forEach((u, t) -> trust.addProperty(u.toString(), t));
		o.add("trust", trust);
		o.add("skills", c.mimic.toJson());
		o.add("abilities", c.skills.toJson());
		o.add("rumors", c.rumors.toJson());
		o.add("life", c.life.toJson());
		JsonArray memories = new JsonArray();
		for (String m : c.memories) memories.add(m);
		o.add("memories", memories);
		o.add("places", c.places.toJson());
		o.add("chests", c.storage.toJson());
		o.add("knows", c.knowledge.toJson());
		o.add("taste", c.taste.toJson());
		if (c.chores.mineRecordY != Integer.MIN_VALUE) {
			JsonObject mine = new JsonObject();
			mine.addProperty("y", c.chores.mineRecordY);
			mine.addProperty("leg", c.chores.mineRecordLeg);
			mine.addProperty("dir", c.chores.mineRecordDir.getName());
			o.add("mine", mine);
		}
		o.add("crops", c.farmer.toJson());
		if (c.adventure.on) o.addProperty("adventure", true);
		if (c.band != null) o.addProperty("band", c.band);
		byName.put(c.name.toLowerCase(Locale.ROOT), o);
	}

	void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			JsonObject all = new JsonObject();
			byName.forEach(all::add);
			Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(all));
		} catch (IOException e) {
			XenMod.LOG.warn("Could not save {}: {}", file, e.toString());
		}
	}

	java.util.Set<String> names() {
		return byName.keySet();
	}
}
