package com.vinylmusic.audio;

import com.vinylmusic.config.VinylConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/** Playlist, queue and now playing state for both sources. Call tick() from the client thread. */
public final class MusicManager {
	private static final MusicManager INSTANCE = new MusicManager();

	public static MusicManager get() {
		return INSTANCE;
	}

	private final List<Track> playlist = new CopyOnWriteArrayList<>();
	private final Deque<Track> queue = new ArrayDeque<>();
	private final AudioPlayer player = new AudioPlayer();
	private final SystemMedia system = new SystemMedia();
	private final ExecutorService scanner = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "VinylMusicPlayer-Scanner");
		t.setDaemon(true);
		return t;
	});
	private final Random random = new Random();

	private volatile Track current;
	private volatile boolean localPlaying;
	private volatile String message = "";
	private volatile int pendingScans;
	private boolean initialised;

	private MusicManager() {
	}

	// ------------------------------------------------------------ lifecycle

	public void init() {
		if (initialised) return;
		initialised = true;
		VinylConfig cfg = VinylConfig.get();
		player.setVolume(cfg.musicVolume);
		try {
			Files.createDirectories(VinylConfig.musicDir());
		} catch (Exception ignored) {
		}
		List<String> saved = new ArrayList<>(cfg.playlist);
		pendingScans++;
		scanner.submit(() -> {
			try {
				for (String s : saved) {
					Path p = Path.of(s);
					if (Files.isRegularFile(p)) playlist.add(load(p));
				}
				scanAllFolders();
				if (cfg.showMinecraftMusic) loadVanilla();
			} finally {
				pendingScans--;
				persistPlaylist();
				if (cfg.autoplay && isLocal() && !playlist.isEmpty() && current == null) {
					playIndex(cfg.shuffle ? random.nextInt(playlist.size()) : 0);
				}
			}
		});
		if (!isLocal()) system.start();
	}

	public void shutdown() {
		player.stop();
		system.stop();
		scanner.shutdownNow();
	}

	public void tick() {
		if (player.consumeEnded()) {
			advanceAfterEnd();
		}
		String err = player.consumeError();
		if (err != null) {
			message = err;
			localPlaying = false;
		}
	}

	// ------------------------------------------------------------ source

	public boolean isLocal() {
		return !"SYSTEM".equals(VinylConfig.get().source);
	}

	public void setLocalSource(boolean local) {
		VinylConfig cfg = VinylConfig.get();
		cfg.source = local ? "LOCAL" : "SYSTEM";
		VinylConfig.markDirty();
		if (local) {
			system.stop();
		} else {
			player.stop();
			localPlaying = false;
			system.start();
		}
	}

	public SystemMedia system() {
		return system;
	}

	// ------------------------------------------------------------ playlist

	public List<Track> playlist() {
		return Collections.unmodifiableList(playlist);
	}

	public int queueSize() {
		synchronized (queue) {
			return queue.size();
		}
	}

	public void setMessage(String m) {
		this.message = m == null ? "" : m;
	}

	/** Picks up new files in the music_player folder and in every library folder. */
	public void rescanFolder() {
		pendingScans++;
		scanner.submit(() -> {
			try {
				scanAllFolders();
				if (VinylConfig.get().showMinecraftMusic) loadVanilla();
			} finally {
				pendingScans--;
				persistPlaylist();
			}
		});
	}

	public boolean isScanning() {
		return pendingScans > 0;
	}

	public String message() {
		return message;
	}

	private Track load(Path p) {
		Track t = MetadataReader.read(p);
		applyOverride(t);
		return t;
	}

	public void applyOverride(Track t) {
		VinylConfig.TrackOverride o = VinylConfig.get().overrides.get(t.path);
		if (o == null) return;
		if (o.title != null && !o.title.isBlank()) t.title = o.title;
		if (o.artist != null) t.artist = o.artist;
		if (o.album != null) t.album = o.album;
		if (o.coverPath != null && !o.coverPath.isBlank()) {
			int[] px = CoverArt.fromFile(Path.of(o.coverPath));
			if (px != null) t.setCover(px);
		}
	}

	public void addFiles(List<Path> files) {
		pendingScans++;
		scanner.submit(() -> {
			try {
				for (Path p : files) {
					if (Files.isRegularFile(p) && MetadataReader.isSupported(p) && !contains(p)) {
						playlist.add(load(p));
					}
				}
			} finally {
				pendingScans--;
				persistPlaylist();
			}
		});
	}

	/** Adds a folder to the library. It is scanned now and again on every rescan and game start. */
	public void addLibraryFolder(Path dir) {
		String abs = dir.toAbsolutePath().toString();
		VinylConfig cfg = VinylConfig.get();
		if (!cfg.libraryFolders.contains(abs)) {
			cfg.libraryFolders.add(abs);
			VinylConfig.markDirty();
		}
		pendingScans++;
		scanner.submit(() -> {
			try {
				scanFolderInternal(dir, true, abs);
			} finally {
				pendingScans--;
				persistPlaylist();
			}
		});
	}

	public List<String> libraryFolders() {
		return new ArrayList<>(VinylConfig.get().libraryFolders);
	}

	/** Stops scanning a folder and removes the songs that came from it. */
	public void removeLibraryFolder(String folder) {
		VinylConfig.get().libraryFolders.remove(folder);
		VinylConfig.markDirty();
		for (Track t : new ArrayList<>(playlist)) {
			if (folder.equals(t.folder)) remove(t);
		}
	}

	private void scanAllFolders() {
		scanFolderInternal(VinylConfig.musicDir(), false);
		for (String f : new ArrayList<>(VinylConfig.get().libraryFolders)) {
			scanFolderInternal(Path.of(f), true, f);
		}
	}

	private boolean vanillaLoaded;

	private void loadVanilla() {
		if (vanillaLoaded) return;
		vanillaLoaded = true;
		playlist.addAll(VanillaMusic.load());
	}

	private void scanFolderInternal(Path dir, boolean recursive) {
		scanFolderInternal(dir, recursive, null);
	}

	private void scanFolderInternal(Path dir, boolean recursive, String tag) {
		if (!Files.isDirectory(dir)) return;
		try (Stream<Path> s = recursive ? Files.walk(dir, 6) : Files.list(dir)) {
			List<Path> found = s.filter(Files::isRegularFile).filter(MetadataReader::isSupported).sorted().toList();
			for (Path p : found) {
				if (!contains(p)) {
					Track t = load(p);
					t.folder = tag == null ? dir.toAbsolutePath().toString() : tag;
					playlist.add(t);
				}
			}
		} catch (Exception e) {
			message = "Could not read folder: " + e.getMessage();
		}
	}

	private boolean contains(Path p) {
		String abs = p.toAbsolutePath().toString();
		for (Track t : playlist) {
			if (t.path.equals(abs)) return true;
		}
		return false;
	}

	public void remove(Track t) {
		if (t.vanilla) return;
		boolean wasCurrent = t == current;
		playlist.remove(t);
		synchronized (queue) {
			queue.remove(t);
		}
		VinylConfig.get().overrides.remove(t.path);
		if (wasCurrent) {
			player.stop();
			localPlaying = false;
			current = null;
		}
		persistPlaylist();
	}

	/** Removes every song you added. Minecraft's own songs stay. */
	public void clearAll() {
		Track cur = current;
		if (cur != null && !cur.vanilla) {
			player.stop();
			localPlaying = false;
			current = null;
		}
		playlist.removeIf(t -> !t.vanilla);
		synchronized (queue) {
			queue.removeIf(t -> !t.vanilla);
		}
		VinylConfig.get().libraryFolders.clear();
		persistPlaylist();
	}

	/** Re-reads one track after its info or cover was edited. */
	public void refresh(Track t) {
		Track fresh = MetadataReader.read(Path.of(t.path));
		t.title = fresh.title;
		t.artist = fresh.artist;
		t.album = fresh.album;
		t.durationMs = fresh.durationMs;
		t.setCover(fresh.cover);
		applyOverride(t);
	}

	private void persistPlaylist() {
		VinylConfig cfg = VinylConfig.get();
		List<String> paths = new ArrayList<>();
		for (Track t : playlist) {
			if (!t.vanilla && t.folder == null) paths.add(t.path);
		}
		cfg.playlist = paths;
		VinylConfig.markDirty();
	}

	public void queueNext(Track t) {
		synchronized (queue) {
			queue.addFirst(t);
		}
	}

	public void queueLast(Track t) {
		synchronized (queue) {
			queue.addLast(t);
		}
	}

	// ------------------------------------------------------------ transport

	public void play(Track t) {
		if (!isLocal()) setLocalSource(true);
		current = t;
		message = "";
		player.setVolume(VinylConfig.get().musicVolume);
		player.play(t);
		localPlaying = true;
	}

	public void playIndex(int i) {
		if (i >= 0 && i < playlist.size()) play(playlist.get(i));
	}

	public void togglePlayPause() {
		if (!isLocal()) {
			system.togglePlayPause();
			return;
		}
		if (current == null) {
			if (!playlist.isEmpty()) playIndex(VinylConfig.get().shuffle ? random.nextInt(playlist.size()) : 0);
			return;
		}
		if (!player.isActive()) {
			play(current);
			return;
		}
		boolean nowPaused = !player.isPaused();
		player.setPaused(nowPaused);
		localPlaying = !nowPaused;
	}

	public void next() {
		if (!isLocal()) {
			system.next();
			return;
		}
		Track q;
		synchronized (queue) {
			q = queue.pollFirst();
		}
		if (q != null) {
			play(q);
			return;
		}
		if (playlist.isEmpty()) return;
		int idx = playlist.indexOf(current);
		if (VinylConfig.get().shuffle && playlist.size() > 1) {
			int n;
			do {
				n = random.nextInt(playlist.size());
			} while (n == idx);
			playIndex(n);
		} else {
			playIndex((idx + 1) % playlist.size());
		}
	}

	public void previous() {
		if (!isLocal()) {
			system.previous();
			return;
		}
		if (playlist.isEmpty()) return;
		if (current != null && player.positionMs() > 3000) {
			play(current);
			return;
		}
		int idx = playlist.indexOf(current);
		playIndex(idx <= 0 ? playlist.size() - 1 : idx - 1);
	}

	private void advanceAfterEnd() {
		int loop = VinylConfig.get().loopMode;
		if (loop == 2 && current != null) {
			play(current);
			return;
		}
		boolean queued;
		synchronized (queue) {
			queued = !queue.isEmpty();
		}
		int idx = playlist.indexOf(current);
		boolean lastOfList = idx >= playlist.size() - 1 && !VinylConfig.get().shuffle;
		if (!queued && lastOfList && loop == 0) {
			localPlaying = false;
			return;
		}
		next();
	}

	// ------------------------------------------------------------ volume

	public float volume() {
		return VinylConfig.get().musicVolume;
	}

	public void setVolume(float v) {
		v = VinylConfig.clamp01(v);
		VinylConfig.get().musicVolume = v;
		VinylConfig.markDirty();
		player.setVolume(v);
		if (!isLocal()) system.setVolume(v);
	}

	// ------------------------------------------------------------ now playing

	public boolean isPlaying() {
		return isLocal() ? (localPlaying && player.isActive() && !player.isPaused()) : (system.hasSession && system.playing);
	}

	/** True when our own audio is audible, used to silence vanilla music. */
	public boolean isAudible() {
		return isPlaying();
	}

	public boolean hasTrack() {
		return isLocal() ? current != null : system.hasSession;
	}

	public Track currentTrack() {
		return isLocal() ? current : null;
	}

	public String title() {
		if (!isLocal()) return system.hasSession ? system.title : "";
		Track t = current;
		return t == null ? "" : t.title;
	}

	public String artist() {
		if (!isLocal()) return system.hasSession ? system.artist : "";
		Track t = current;
		return t == null ? "" : t.artist;
	}

	public String album() {
		if (!isLocal()) return system.hasSession ? system.album : "";
		Track t = current;
		return t == null ? "" : t.album;
	}

	public int[] cover() {
		if (!isLocal()) return system.cover;
		Track t = current;
		return t == null ? null : t.cover;
	}

	public long positionMs() {
		return isLocal() ? player.positionMs() : 0L;
	}

	public long durationMs() {
		Track t = current;
		return isLocal() && t != null ? t.durationMs : 0L;
	}
}
