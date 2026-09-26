package xen.mod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where Xens' skins come from (the {@code skins} setting, any mix of these):
 * <ul>
 *   <li>{@code "modern"} (the default): the mod's skins in today's style: shaded hair with volume, hoodies, jackets,
 *   sneakers, muted and pastel colours, many with slim arms (original, free to use, signed so everyone sees them);</li>
 *   <li>{@code "fun"}: the funny 61 of earlier versions; {@code "pack"}: both;</li>
 *   <li>{@code "random"}: any skin that comes with the mod: both packs and Minecraft's 18 default skins;</li>
 *   <li>{@code "default"}: only Minecraft's 18 default skins;</li>
 *   <li>{@code "folder"}: your own skins: put PNG skin files in {@code config/xen/skins/} (from Planet Minecraft, The
 *   Skindex, NameMC, or drawn yourself). Each one is signed once through mineskin.org (the image is uploaded, unlisted)
 *   and remembered in {@code config/xen/skins/signed.json}, so every player sees it, with or without the mod;</li>
 *   <li>{@code "mineskin"}: random skins from mineskin.org's public gallery (online, made by its users);</li>
 *   <li>{@code "player:Name"}: the skin of a Minecraft account;</li>
 *   <li>a default skin's name ({@code "steve"}, {@code "alex:slim"}...) or {@code "texture:<value>:<signature>"}.</li>
 * </ul>
 * Anything online happens on its own thread; until it's ready, a Xen gets one of the mod's own skins.
 */
final class Skins {
	private static final String MINESKIN = "https://api.mineskin.org";
	private final List<String> pack = new ArrayList<>();
	private final List<String> gallery = new CopyOnWriteArrayList<>();
	private final Map<String, String> folder = new ConcurrentHashMap<>();
	private final Map<String, String> players = new ConcurrentHashMap<>();
	private final ExecutorService net = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "xen-skins");
		t.setDaemon(true);
		return t;
	});
	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(java.time.Duration.ofSeconds(15)).build();
	private Path dir;
	private volatile boolean fetchingGallery;

	/** The modern pack (today's style), and the fun pack of earlier versions ({@link #pack}). */
	private final List<String> modern = new ArrayList<>();

	Skins() {
		read("/assets/xen/skins/pack.json", pack);
		read("/assets/xen/skins/modern.json", modern);
	}

	private static void read(String resource, List<String> into) {
		try (InputStream in = Skins.class.getResourceAsStream(resource)) {
			if (in != null) {
				for (JsonElement e : new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class)) {
					JsonObject o = e.getAsJsonObject();
					into.add("texture:" + o.get("value").getAsString() + ":" + o.get("signature").getAsString());
				}
			}
		} catch (Exception e) {
			XenMod.LOG.warn("Xen's skin pack {} couldn't be read: {}", resource, e.toString());
		}
	}

	int packSize() {
		return pack.size() + modern.size();
	}

	/** Get ready for the setting: sign new skins in the folder, fetch gallery skins and players' skins (in the background). */
	void prepare(Path configDir, List<String> setting) {
		dir = configDir.resolve("xen").resolve("skins");
		try {
			Files.createDirectories(dir);
			Path signed = dir.resolve("signed.json");
			if (Files.exists(signed)) {
				JsonObject o = new Gson().fromJson(Files.readString(signed), JsonObject.class);
				for (var e : o.entrySet()) folder.put(e.getKey(), e.getValue().getAsString());
			}
		} catch (Exception e) {
			XenMod.LOG.warn("Xen's skins folder: {}", e.toString());
		}
		for (String s : setting) {
			String k = s.trim();
			if (k.equalsIgnoreCase("folder")) net.submit(this::signFolder);
			else if (k.equalsIgnoreCase("mineskin")) fetchGallery();
			else if (k.toLowerCase(Locale.ROOT).startsWith("player:")) {
				String name = k.substring(7).trim();
				if (!players.containsKey(name.toLowerCase(Locale.ROOT))) net.submit(() -> fetchPlayer(name));
			}
		}
	}

	/** A skin for a new Xen from the setting's sources (what's ready now). */
	String pick(List<String> setting, Random r) {
		List<String> out = new ArrayList<>();
		for (String s : setting == null ? List.of("random") : setting) {
			String k = s.trim(), low = k.toLowerCase(Locale.ROOT);
			switch (low) {
				case "random" -> {
					out.addAll(modern);
					out.addAll(pack);
					for (String d : Looks.SKINS) out.add(d);
				}
				case "modern" -> out.addAll(modern);
				case "fun" -> out.addAll(pack);
				case "pack" -> {
					out.addAll(modern);
					out.addAll(pack);
				}
				case "default" -> {
					for (String d : Looks.SKINS) out.add(d);
				}
				case "folder" -> out.addAll(folder.values());
				case "mineskin" -> {
					out.addAll(gallery);
					if (gallery.size() < 8) fetchGallery();
				}
				default -> {
					if (low.startsWith("player:")) {
						String t = players.get(low.substring(7).trim());
						if (t != null) out.add(t);
					} else if (!k.isEmpty()) {
						out.add(k);                                          // a default skin's name, or texture:...
					}
				}
			}
		}
		if (out.isEmpty()) out.addAll(!modern.isEmpty() ? modern : pack.isEmpty() ? List.of(Looks.SKINS) : pack);   // nothing ready yet
		return out.get(r.nextInt(out.size()));
	}

	// -------------------------------------------------------------------------------- online
	private String get(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "XenCompanion (Minecraft mod)")
				.timeout(java.time.Duration.ofSeconds(30)).build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode() + " from " + url);
		return res.body();
	}

	/** Some skins from mineskin.org's public gallery, with their signed textures. */
	private void fetchGallery() {
		if (fetchingGallery) return;
		fetchingGallery = true;
		net.submit(() -> {
			try {
				JsonObject list = new Gson().fromJson(get(MINESKIN + "/v2/skins?size=24"), JsonObject.class);
				for (JsonElement e : list.getAsJsonArray("skins")) {
					String uuid = e.getAsJsonObject().get("uuid").getAsString();
					JsonObject skin = new Gson().fromJson(get(MINESKIN + "/v2/skins/" + uuid), JsonObject.class).getAsJsonObject("skin");
					JsonObject data = skin.getAsJsonObject("texture").getAsJsonObject("data");
					gallery.add("texture:" + data.get("value").getAsString() + ":" + data.get("signature").getAsString());
					Thread.sleep(300);
				}
				XenMod.LOG.info("Xen has {} skins from mineskin.org's gallery", gallery.size());
			} catch (Exception e) {
				XenMod.LOG.warn("Xen couldn't get skins from mineskin.org: {}", e.toString());
			} finally {
				fetchingGallery = false;
			}
		});
	}

	/** A Minecraft account's skin, signed by Mojang. */
	private void fetchPlayer(String name) {
		try {
			JsonObject id = new Gson().fromJson(get("https://api.mojang.com/users/profiles/minecraft/" + name), JsonObject.class);
			JsonObject profile = new Gson().fromJson(get("https://sessionserver.mojang.com/session/minecraft/profile/"
					+ id.get("id").getAsString() + "?unsigned=false"), JsonObject.class);
			for (JsonElement p : profile.getAsJsonArray("properties")) {
				JsonObject o = p.getAsJsonObject();
				if (o.get("name").getAsString().equals("textures")) {
					players.put(name.toLowerCase(Locale.ROOT), "texture:" + o.get("value").getAsString() + ":" + o.get("signature").getAsString());
					XenMod.LOG.info("Xen has {}'s skin", name);
				}
			}
		} catch (Exception e) {
			XenMod.LOG.warn("Xen couldn't get {}'s skin: {}", name, e.toString());
		}
	}

	/** Sign each new PNG in the skins folder through mineskin.org (once; remembered by the file's hash). */
	private void signFolder() {
		try (var files = Files.list(dir)) {
			List<Path> pngs = files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".png")).sorted().toList();
			boolean changed = false;
			for (Path png : pngs) {
				byte[] data = Files.readAllBytes(png);
				String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
				if (folder.containsKey(hash)) continue;
				String texture = upload(png.getFileName().toString(), data);
				if (texture == null) continue;
				folder.put(hash, texture);
				changed = true;
				XenMod.LOG.info("Xen signed your skin {} with mineskin.org: Xens can wear it now", png.getFileName());
				Thread.sleep(7000);                                          // mineskin.org takes about 10 a minute
			}
			if (changed) {
				JsonObject o = new JsonObject();
				folder.forEach(o::addProperty);
				Files.writeString(dir.resolve("signed.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
			}
		} catch (Exception e) {
			XenMod.LOG.warn("Xen couldn't sign the skins in {}: {}", dir, e.toString());
		}
	}

	/** Upload one skin image (multipart, unlisted); its signed texture, or null. */
	private String upload(String fileName, byte[] png) throws Exception {
		String boundary = "XenSkin" + System.nanoTime();
		var body = new java.io.ByteArrayOutputStream();
		String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName.replace("\"", "")
				+ "\"\r\nContent-Type: image/png\r\n\r\n";
		body.write(head.getBytes(StandardCharsets.UTF_8));
		body.write(png);
		body.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"visibility\"\r\n\r\nunlisted\r\n--" + boundary
				+ "--\r\n").getBytes(StandardCharsets.UTF_8));
		for (int attempt = 0; attempt < 5; attempt++) {
			HttpRequest req = HttpRequest.newBuilder(URI.create(MINESKIN + "/v2/generate"))
					.header("User-Agent", "XenCompanion (Minecraft mod)")
					.header("Content-Type", "multipart/form-data; boundary=" + boundary)
					.timeout(java.time.Duration.ofSeconds(90))
					.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			try {
				JsonObject o = new Gson().fromJson(res.body(), JsonObject.class);
				if (o.has("skin")) {
					JsonObject data = o.getAsJsonObject("skin").getAsJsonObject("texture").getAsJsonObject("data");
					return "texture:" + data.get("value").getAsString() + ":" + data.get("signature").getAsString();
				}
				XenMod.LOG.info("mineskin.org: {}", res.body().length() > 200 ? res.body().substring(0, 200) : res.body());
			} catch (RuntimeException e) {
				XenMod.LOG.info("mineskin.org answered {}", res.statusCode());
			}
			Thread.sleep(10_000L * (attempt + 1));                           // busy: wait and try again
		}
		return null;
	}
}
