package com.vinylmusic.gui;

import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Every registered sound event, sorted into the mixer tabs. */
public final class SoundCatalog {
	private SoundCatalog() {
	}

	public enum Tab { ANIMALS, HOSTILE, VEHICLES, PLAYER, BLOCKS, ITEMS, MUSIC, UI, PARTICLES, WEATHER, OTHER }

	public record Entry(String id, String group, Tab tab) {
		/** Id without the namespace. */
		public String path() {
			int c = id.indexOf(':');
			return c >= 0 ? id.substring(c + 1) : id;
		}
	}

	public static final String[] CATEGORIES = {
			"ALL", "MUSIC", "RECORDS", "WEATHER", "BLOCKS", "HOSTILE", "NEUTRAL", "PLAYERS", "AMBIENT", "VOICE"
	};

	private static final Set<String> HOSTILE = Set.of(
			"zombie", "zombie_villager", "husk", "drowned", "skeleton", "stray", "bogged", "wither_skeleton",
			"creeper", "spider", "cave_spider", "enderman", "endermite", "silverfish", "witch", "slime",
			"magma_cube", "ghast", "blaze", "phantom", "pillager", "vindicator", "evoker", "ravager", "vex",
			"warden", "guardian", "elder_guardian", "shulker", "piglin", "piglin_brute", "zombified_piglin",
			"zoglin", "hoglin", "wither", "ender_dragon", "breeze", "creaking", "illusioner", "skeleton_horse",
			"zombie_horse");

	private static final Set<String> ANIMALS = Set.of(
			"cow", "pig", "sheep", "chicken", "horse", "donkey", "mule", "cat", "ocelot", "wolf", "fox", "rabbit",
			"parrot", "bee", "llama", "trader_llama", "panda", "polar_bear", "goat", "camel", "sniffer", "turtle",
			"frog", "tadpole", "axolotl", "cod", "salmon", "pufferfish", "tropical_fish", "squid", "glow_squid",
			"dolphin", "bat", "mooshroom", "strider", "armadillo", "allay", "villager", "wandering_trader",
			"iron_golem", "snow_golem", "happy_ghast", "camel_husk");

	private static List<Entry> cache;

	public static synchronized List<Entry> all() {
		if (cache == null) {
			TreeMap<String, Entry> sorted = new TreeMap<>();
			try {
				for (var key : BuiltInRegistries.SOUND_EVENT.keySet()) {
					String id = key.toString();
					sorted.put(id, new Entry(id, com.vinylmusic.config.VinylConfig.groupOf(id), tabOf(id)));
				}
			} catch (Throwable ignored) {
			}
			cache = new ArrayList<>(sorted.values());
		}
		return cache;
	}

	private static final String[] VEHICLE_WORDS = {"boat", "minecart", "raft", "vehicle", "car", "train", "plane", "helicopter", "bike", "ship", "tank", "mech"};

	private static Tab tabOf(String id) {
		int c = id.indexOf(':');
		String ns = c >= 0 ? id.substring(0, c) : "minecraft";
		String path = c >= 0 ? id.substring(c + 1) : id;
		String[] seg = path.split("\\.");
		boolean vanilla = ns.equals("minecraft");
		switch (seg[0]) {
			case "entity":
				if (seg.length >= 2) return mobTab(seg[1], vanilla);
				return Tab.OTHER;
			case "block":
				return Tab.BLOCKS;
			case "item":
				return Tab.ITEMS;
			case "music":
			case "music_disc":
			case "record":
				return Tab.MUSIC;
			case "ui":
				return Tab.UI;
			case "particle":
			case "ambient":
				return Tab.PARTICLES;
			case "weather":
				return Tab.WEATHER;
			default:
				if (!vanilla) {
					for (String w : VEHICLE_WORDS) if (path.contains(w)) return Tab.VEHICLES;
				}
				return Tab.OTHER;
		}
	}

	private static Tab mobTab(String mob, boolean vanilla) {
		for (String w : VEHICLE_WORDS) {
			if (mob.contains(w) && !mob.equals("creeper") && !mob.equals("scary")) return Tab.VEHICLES;
		}
		if (mob.equals("player")) return Tab.PLAYER;
		if (HOSTILE.contains(mob)) return Tab.HOSTILE;
		if (ANIMALS.contains(mob)) return Tab.ANIMALS;
		return Tab.OTHER;
	}

	public static List<Entry> forTab(Tab tab, String filter) {
		String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT).trim();
		List<Entry> out = new ArrayList<>();
		for (Entry e : all()) {
			if (tab != null && e.tab() != tab) continue;
			if (!f.isEmpty() && !e.id().toLowerCase(Locale.ROOT).contains(f)) continue;
			out.add(e);
		}
		return out;
	}

	public static List<String> groups(List<Entry> entries) {
		Set<String> set = new TreeSet<>();
		for (Entry e : entries) set.add(e.group());
		return new ArrayList<>(set);
	}

	/** "entity.cow" becomes "Cow". */
	public static String pretty(String group) {
		int dot = group.lastIndexOf('.');
		String last = dot >= 0 ? group.substring(dot + 1) : group;
		String[] words = last.replace('_', ' ').split(" ");
		StringBuilder sb = new StringBuilder();
		for (String w : words) {
			if (w.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return sb.toString();
	}
}
