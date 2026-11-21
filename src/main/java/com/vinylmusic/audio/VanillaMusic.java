package com.vinylmusic.audio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vinylmusic.client.Diagnostics;
import com.vinylmusic.config.VinylConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Finds the music and music discs that Minecraft itself ships and lists them as songs.
 * Minecraft keeps sounds under hashed file names. The asset index maps the real names to those files.
 */
public final class VanillaMusic {
	private VanillaMusic() {
	}

	public static List<Track> load(java.util.Set<String> hidden) {
		List<Track> out = new ArrayList<>();
		try {
			Path assets = assetsDir();
			Path index = indexFile(assets);
			if (index == null) {
				Diagnostics.LOG.warn("[VinylMusicPlayer] No Minecraft asset index found in {}", assets);
				return out;
			}
			TreeMap<String, String> found = new TreeMap<>();
			try (Reader r = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
				JsonObject objects = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("objects");
				for (Map.Entry<String, JsonElement> e : objects.entrySet()) {
					String key = e.getKey();
					if (!key.endsWith(".ogg")) continue;
					if (key.startsWith("minecraft/sounds/music/") || key.startsWith("minecraft/sounds/records/")) {
						found.put(key, e.getValue().getAsJsonObject().get("hash").getAsString());
					}
				}
			}
			for (Map.Entry<String, String> e : found.entrySet()) {
				String hash = e.getValue();
				Path file = assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
				if (!Files.isRegularFile(file)) continue;
				if (hidden.contains(e.getKey())) continue;
				out.add(makeTrack(e.getKey(), file, hash));
			}
			Diagnostics.LOG.info("[VinylMusicPlayer] Found {} Minecraft songs.", out.size());
		} catch (Throwable t) {
			Diagnostics.LOG.warn("[VinylMusicPlayer] Could not list Minecraft songs: {}", t.toString());
		}
		return out;
	}

	private static Track makeTrack(String key, Path file, String hash) {
		String rest = key.substring("minecraft/sounds/".length(), key.length() - 4); // music/game/calm1 or records/cat
		String[] seg = rest.split("/");
		String name = seg[seg.length - 1];
		String album;
		if (seg[0].equals("records")) {
			album = "Music Discs";
		} else {
			String folder = seg.length >= 3 ? seg[seg.length - 2] : "menu";
			album = pretty(folder);
		}
		Track t = new Track(file.toAbsolutePath().toString());
		t.format = "ogg";
		t.vanilla = true;
		t.title = pretty(name);
		t.artist = "Minecraft";
		t.album = album;
		t.key = key;
		java.util.Map<String, Long> cache = VinylConfig.get().durationCache;
		Long known = cache.get(hash);
		if (known != null && known > 0) {
			t.durationMs = known;
		} else {
			t.durationMs = OggSource.probeDurationMs(file);
			if (t.durationMs > 0) {
				cache.put(hash, t.durationMs);
				VinylConfig.markDirty();
			}
		}
		return t;
	}

	/** "calm1" becomes "Calm 1", "a_familiar_room" becomes "A Familiar Room". */
	static String pretty(String raw) {
		String s = raw.replace('_', ' ').replace('-', ' ');
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (i > 0 && Character.isDigit(c) && Character.isLetter(s.charAt(i - 1))) sb.append(' ');
			sb.append(c);
		}
		StringBuilder out = new StringBuilder();
		for (String w : sb.toString().trim().split("\\s+")) {
			if (w.isEmpty()) continue;
			if (out.length() > 0) out.append(' ');
			out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
		}
		return out.toString();
	}

	private static Path assetsDir() {
		String[] args = launchArgs();
		String dir = argValue(args, "--assetsDir");
		if (dir != null && Files.isDirectory(Path.of(dir))) return Path.of(dir);
		return FabricLoader.getInstance().getGameDir().resolve("assets");
	}

	private static Path indexFile(Path assets) throws Exception {
		Path indexes = assets.resolve("indexes");
		if (!Files.isDirectory(indexes)) return null;
		String name = argValue(launchArgs(), "--assetIndex");
		if (name != null) {
			Path p = indexes.resolve(name + ".json");
			if (Files.isRegularFile(p)) return p;
		}
		// Fall back to the newest index file.
		try (Stream<Path> s = Files.list(indexes)) {
			return s.filter(p -> p.getFileName().toString().endsWith(".json"))
					.max((a, b) -> {
						try {
							return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
						} catch (Exception e) {
							return 0;
						}
					}).orElse(null);
		}
	}

	private static String[] launchArgs() {
		try {
			return FabricLoader.getInstance().getLaunchArguments(true);
		} catch (Throwable t) {
			return new String[0];
		}
	}

	private static String argValue(String[] args, String key) {
		for (int i = 0; i + 1 < args.length; i++) {
			if (args[i].equals(key)) return args[i + 1];
		}
		return null;
	}
}
