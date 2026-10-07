package xen.mod;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * PvP tiers (HT1 is the best, then LT1, HT2... LT5): a Xen with a real player's name (the "accurate" name style) fights
 * as well as that player is ranked. The tier comes from config/xen/tiers.txt ("Name HT1", a line: works offline, and
 * for any name), or from MCTiers (mctiers.com, online: the best tier the player holds in any of its gamemodes).
 */
final class Tiers {
	private Tiers() {}

	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(java.time.Duration.ofSeconds(10)).build();

	/** "HT1" -> its fight skill: HT1 1.0, LT1 0.95, HT2 0.9, LT2 0.85 ... LT5 0.55; null for anything else. */
	static Float skill(String tier) {
		if (tier == null || !tier.toUpperCase(Locale.ROOT).matches("[HL]T[1-5]")) return null;
		String t = tier.toUpperCase(Locale.ROOT);
		int rank = (t.charAt(2) - '1') * 2 + (t.charAt(0) == 'H' ? 0 : 1);         // 0 (HT1) to 9 (LT5)
		return 1f - 0.05f * rank;
	}

	/** Its tier from config/xen/tiers.txt, or null. */
	static String local(Path configDir, String name) {
		try {
			Path f = configDir.resolve("xen").resolve("tiers.txt");
			if (!Files.exists(f)) {
				Files.createDirectories(f.getParent());
				Files.writeString(f, "# PvP tiers for Xens with real players' names, one a line: Name HT1 (HT1 best, then LT1, HT2 ... LT5).\n"
						+ "# A Xen with that name fights that well. Names not here are looked up on mctiers.com when there's internet.\n");
			}
			for (String line : Files.readAllLines(f)) {
				String[] w = line.trim().split("\\s+");
				if (w.length >= 2 && !w[0].startsWith("#") && w[0].equalsIgnoreCase(name) && skill(w[1]) != null) return w[1].toUpperCase(Locale.ROOT);
			}
		} catch (Exception e) {
			XenMod.LOG.debug("tiers.txt: {}", e.toString());
		}
		return null;
	}

	/** The best tier MCTiers lists for the player (it waits for the network: off the server thread), or null. */
	static String online(String name) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create("https://mctiers.com/api/search_profile/" + name))
					.header("User-Agent", "XenCompanion (Minecraft mod)").timeout(java.time.Duration.ofSeconds(15)).build();
			HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) return null;
			JsonObject o = new com.google.gson.Gson().fromJson(res.body(), JsonObject.class);
			if (o == null || !o.has("rankings") || !o.get("rankings").isJsonObject()) return null;
			int best = Integer.MAX_VALUE;
			for (var e : o.getAsJsonObject("rankings").entrySet()) {
				if (!e.getValue().isJsonObject()) continue;
				JsonObject r = e.getValue().getAsJsonObject();
				if (!r.has("tier") || !r.has("pos")) continue;
				int tier = r.get("tier").getAsInt(), pos = r.get("pos").getAsInt();   // (pos 0: high, 1: low)
				if (tier >= 1 && tier <= 5) best = Math.min(best, (tier - 1) * 2 + (pos == 0 ? 0 : 1));
			}
			return best == Integer.MAX_VALUE ? null : (best % 2 == 0 ? "HT" : "LT") + (best / 2 + 1);
		} catch (Exception e) {
			XenMod.LOG.info("No MCTiers tier for {} ({})", name, e.toString());
			return null;
		}
	}

	/** A Xen named after a ranked player: it fights that well (and says so in its notes). */
	static void apply(Companion c, String tier) {
		Float s = skill(tier);
		if (s == null) return;
		c.skills.level[Skills.FIGHT] = Math.max(c.skills.level[Skills.FIGHT], s);
		c.journal("notes", c.name + " is ranked " + tier + " in PvP: it fights like it (fight skill " + String.format(Locale.ROOT, "%.2f", s) + ")");
		XenMod.LOG.info("{} fights like a {} (fight skill {})", c.name, tier, s);
	}
}
