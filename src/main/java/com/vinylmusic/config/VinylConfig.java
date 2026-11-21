package com.vinylmusic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain JSON config stored at config/vinyl-music-player.json. */
public final class VinylConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static VinylConfig instance = new VinylConfig();
	private static volatile boolean dirty;

	/** Per-track edits made in the song popover. */
	public static final class TrackOverride {
		public String title;
		public String artist;
		public String album;
		public String coverPath;
	}

	/** Where the disc sits. Anchor is a 3x3 grid: 0 top-left, 1 top-center, 2 top-right, 3 middle-left,
	 * 4 center, 5 middle-right, 6 bottom-left, 7 bottom-center, 8 bottom-right. Offsets move it from there. */
	public static final class Placement {
		public int anchor = 6;
		public int offX = 0;
		public int offY = 0;

		public Placement() {
		}

		public Placement(int anchor) {
			this.anchor = anchor;
		}
	}

	// Where the disc shows
	public boolean showOnPauseMenu = true;
	public boolean showOnTitleScreen = true;
	/** In-world HUD: 0 off, 1 always, 2 only while music plays. */
	public int hudMode = 0;
	/** Also draw on inventory, chat and other menus while in a world. */
	public boolean showOnOtherScreens = false;
	public Placement pausePlacement = new Placement(6);
	public Placement titlePlacement = new Placement(6);
	public Placement hudPlacement = new Placement(6);

	// Look
	public float scale = 1.0f;
	/** Size of the song card (text, buttons and volume bar) next to the disc. */
	public float cardScale = 1.0f;
	public float opacity = 1.0f;
	/** RGB of the vinyl body. */
	public int vinylColor = 0x101014;
	/** RGB of the center label when a song has no cover, or -1 for automatic. */
	public int labelColor = -1;
	/** RGB used for progress, volume and highlights. */
	public int accentColor = 0xA78BFA;
	/** 0 vinyl, 1 CD. */
	public int discStyle = 0;
	public float spinSpeed = 1.0f;
	public boolean shine = true;
	public boolean showInfo = true;
	public boolean showVolumeBar = true;

	// Player
	public float musicVolume = 0.7f;
	public boolean shuffle = false;
	/** 0 off, 1 all, 2 single. */
	public int loopMode = 1;
	/** LOCAL or SYSTEM. */
	public String source = "LOCAL";
	public boolean muteVanillaMusic = true;
	public boolean autoplay = false;
	/** List the songs that ship with Minecraft next to your own. */
	public boolean showMinecraftMusic = true;
	/** Scan the default music_player folder. */
	public boolean scanMusicFolder = true;
	/** Songs you removed from the list. Files stay on disk and are not added back by rescans. */
	public List<String> hiddenSongs = new ArrayList<>();
	/** Length of Minecraft's songs by asset hash, so they are measured only once. */
	public Map<String, Long> durationCache = new LinkedHashMap<>();
	public List<String> playlist = new ArrayList<>();
	/** Folders that are scanned for songs, next to the default music_player folder. */
	public List<String> libraryFolders = new ArrayList<>();
	public Map<String, TrackOverride> overrides = new LinkedHashMap<>();

	// Mixer: values 0.0 to 1.0. Missing key means 1.0.
	public Map<String, Float> categoryVolume = new LinkedHashMap<>();
	public Map<String, Float> groupVolume = new LinkedHashMap<>();
	public Map<String, Float> soundVolume = new LinkedHashMap<>();

	public static VinylConfig get() {
		return instance;
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("vinyl-music-player.json");
	}

	public static Path musicDir() {
		return FabricLoader.getInstance().getGameDir().resolve("music_player");
	}

	public static void load() {
		Path f = file();
		if (Files.isRegularFile(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				VinylConfig loaded = GSON.fromJson(r, VinylConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (Exception e) {
				System.err.println("[VinylMusicPlayer] Could not read config, using defaults: " + e);
			}
		}
		instance.sanitize();
	}

	private void sanitize() {
		if (playlist == null) playlist = new ArrayList<>();
		if (libraryFolders == null) libraryFolders = new ArrayList<>();
		if (hiddenSongs == null) hiddenSongs = new ArrayList<>();
		if (durationCache == null) durationCache = new LinkedHashMap<>();
		cardScale = Math.max(0.5f, Math.min(2.0f, cardScale));
		if (overrides == null) overrides = new LinkedHashMap<>();
		if (categoryVolume == null) categoryVolume = new LinkedHashMap<>();
		if (groupVolume == null) groupVolume = new LinkedHashMap<>();
		if (soundVolume == null) soundVolume = new LinkedHashMap<>();
		if (source == null || (!source.equals("LOCAL") && !source.equals("SYSTEM"))) source = "LOCAL";
		musicVolume = clamp01(musicVolume);
		scale = Math.max(0.5f, Math.min(2.0f, scale));
		opacity = Math.max(0.2f, Math.min(1.0f, opacity));
		spinSpeed = Math.max(0.25f, Math.min(3.0f, spinSpeed));
		hudMode = Math.max(0, Math.min(2, hudMode));
		discStyle = Math.max(0, Math.min(1, discStyle));
		vinylColor &= 0xFFFFFF;
		accentColor &= 0xFFFFFF;
		if (labelColor >= 0) labelColor &= 0xFFFFFF;
		pausePlacement = fixPlacement(pausePlacement);
		titlePlacement = fixPlacement(titlePlacement);
		hudPlacement = fixPlacement(hudPlacement);
		loopMode = Math.max(0, Math.min(2, loopMode));
	}

	private static Placement fixPlacement(Placement p) {
		if (p == null) p = new Placement(6);
		p.anchor = Math.max(0, Math.min(8, p.anchor));
		p.offX = Math.max(-2000, Math.min(2000, p.offX));
		p.offY = Math.max(-2000, Math.min(2000, p.offY));
		return p;
	}

	public void resetLook() {
		scale = 1.0f;
		cardScale = 1.0f;
		opacity = 1.0f;
		vinylColor = 0x101014;
		labelColor = -1;
		accentColor = 0xA78BFA;
		discStyle = 0;
		spinSpeed = 1.0f;
		shine = true;
		showInfo = true;
		showVolumeBar = true;
		markDirty();
	}

	public static void markDirty() {
		dirty = true;
	}

	public static boolean isDirty() {
		return dirty;
	}

	public static synchronized void save() {
		dirty = false;
		try {
			Path f = file();
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, w);
			}
			Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			System.err.println("[VinylMusicPlayer] Could not save config: " + e);
		}
	}

	public static float clamp01(float v) {
		return v < 0f ? 0f : Math.min(v, 1f);
	}

	// ---- Mixer helpers ----

	private static final java.util.concurrent.ConcurrentHashMap<String, String> GROUPS = new java.util.concurrent.ConcurrentHashMap<>();

	public static String groupOf(String soundId) {
		return GROUPS.computeIfAbsent(soundId, VinylConfig::computeGroup);
	}

	private static String computeGroup(String soundId) {
		String path = soundId;
		int colon = path.indexOf(':');
		if (colon >= 0) path = path.substring(colon + 1);
		String[] seg = path.split("\\.");
		if (seg.length >= 3) return seg[0] + "." + seg[1];
		return seg[0];
	}

	public float getCategory(String category) {
		Float f = categoryVolume.get(category);
		return f == null ? 1f : f;
	}

	public float getGroup(String group) {
		Float f = groupVolume.get(group);
		return f == null ? 1f : f;
	}

	public float getSound(String id) {
		Float f = soundVolume.get(id);
		return f == null ? 1f : f;
	}

	public void setCategory(String key, float v) {
		put(categoryVolume, key, v);
	}

	public void setGroup(String key, float v) {
		put(groupVolume, key, v);
	}

	public void setSound(String key, float v) {
		put(soundVolume, key, v);
	}

	private static void put(Map<String, Float> map, String key, float v) {
		v = clamp01(v);
		if (v >= 0.999f) map.remove(key);
		else map.put(key, v);
		markDirty();
	}

	/** Final multiplier for one playing sound. Called from the sound mixin every tick, so keep it cheap. */
	public float soundFactor(String category, String soundId) {
		float f = 1f;
		if (!categoryVolume.isEmpty()) {
			f *= getCategory("ALL");
			f *= getCategory(category);
		}
		if (!groupVolume.isEmpty()) f *= getGroup(groupOf(soundId));
		if (!soundVolume.isEmpty()) f *= getSound(soundId);
		return f;
	}

	/** True when any slider moved away from 100%. Lets the sound hook return quickly when nothing is adjusted. */
	public boolean hasMixerChanges() {
		return !categoryVolume.isEmpty() || !groupVolume.isEmpty() || !soundVolume.isEmpty();
	}

	public void resetMixer() {
		categoryVolume.clear();
		groupVolume.clear();
		soundVolume.clear();
		markDirty();
	}
}
