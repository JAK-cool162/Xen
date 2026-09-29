package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The world's lore: what happened on it, day by day, the way a server's history gets told (who built the first house,
 * who founded a village, where the stronghold was found, who fell, who killed the dragon). Everything notable goes in
 * here (kept with the world). Whether it gets written down is up to the Xens: a chronicler (a curious, talkative one)
 * writes it into books, now and then, a volume at a time, and keeps them at home; players can ask it for the story, or
 * for the book. Others only know their own part of it.
 */
final class Lore {
	/** One thing that happened: on which day, what. */
	record Entry(long day, String text) {}

	private static final int KEEP = 600;
	final List<Entry> entries = new ArrayList<>();
	private Path file;

	/** Something notable happened (the day it happened, in words: "Aria built a house at 120 -340"). */
	void add(long day, String text) {
		if (!entries.isEmpty() && entries.get(entries.size() - 1).text().equals(text)) return;
		entries.add(new Entry(day, text));
		while (entries.size() > KEEP) entries.remove(0);
	}

	/** The last few things that happened, oldest first. */
	List<Entry> recent(int n) {
		return new ArrayList<>(entries.subList(Math.max(0, entries.size() - n), entries.size()));
	}

	/** Entries from index from on (what a chronicler hasn't written down yet). */
	List<Entry> since(int from) {
		return new ArrayList<>(entries.subList(Math.max(0, Math.min(from, entries.size())), entries.size()));
	}

	/** Is this Xen the kind to write things down? (Curious and talkative: about one in four.) */
	static boolean chronicler(Companion c) {
		var p = c.personality;
		return p.curiosity + p.chattiness > 1.1f;
	}

	/**
	 * A volume of the chronicle as a written book: its title ("Chronicle, vol. 3"), its author, the entries on its
	 * pages ("Day 12: ..."), a few to a page.
	 */
	static ItemStack book(String author, int volume, List<Entry> list) {
		List<Filterable<Component>> pages = new ArrayList<>();
		StringBuilder page = new StringBuilder();
		for (Entry e : list) {
			String line = "Day " + e.day() + ": " + e.text() + "\n\n";
			if (page.length() + line.length() > 230 && page.length() > 0) {
				pages.add(Filterable.passThrough(Component.literal(page.toString().trim())));
				page.setLength(0);
			}
			page.append(line.length() > 240 ? line.substring(0, 237) + "...\n\n" : line);
			if (pages.size() >= 90) break;
		}
		if (page.length() > 0) pages.add(Filterable.passThrough(Component.literal(page.toString().trim())));
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		String title = "Chronicle, vol. " + volume;
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), author, 0, pages, true));
		return book;
	}

	// ------------------------------------------------------------------------------- kept with the world
	void load(Path file) {
		this.file = file;
		entries.clear();
		try {
			if (!Files.exists(file)) return;
			JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			for (var e : o.getAsJsonArray("entries")) {
				JsonObject j = e.getAsJsonObject();
				entries.add(new Entry(j.get("day").getAsLong(), j.get("text").getAsString()));
			}
		} catch (IOException | RuntimeException e) {
			XenMod.LOG.warn("Could not read the world's lore: {}", e.toString());
		}
	}

	void save() {
		if (file == null) return;
		JsonObject o = new JsonObject();
		JsonArray a = new JsonArray();
		for (Entry e : entries) {
			JsonObject j = new JsonObject();
			j.addProperty("day", e.day());
			j.addProperty("text", e.text());
			a.add(j);
		}
		o.add("entries", a);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			XenMod.LOG.warn("Could not save the world's lore: {}", e.toString());
		}
	}
}
