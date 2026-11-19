package com.vinylmusic.gui;

import com.vinylmusic.audio.MusicManager;
import com.vinylmusic.audio.Track;
import com.vinylmusic.client.Diagnostics;
import com.vinylmusic.config.VinylConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * Music playlist, sound mixer and settings in one screen.
 * Everything except the two text fields is drawn with rectangles and hit-tested by hand,
 * and clicks are read by polling, so this class depends on very little of Minecraft's widget API.
 */
public final class VinylScreen extends Screen {
	private enum Tab { MUSIC, MIXER, SETTINGS }

	private static final class Hot {
		final int x, y, w, h;
		final Runnable click;
		final DoubleConsumer drag;
		final boolean vertical;

		Hot(int x, int y, int w, int h, Runnable click, DoubleConsumer drag, boolean vertical) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.click = click;
			this.drag = drag;
			this.vertical = vertical;
		}

		boolean contains(int mx, int my) {
			return Ui.in(mx, my, x, y, w, h);
		}

		double frac(int mx, int my) {
			double f = vertical ? (my - y) / (double) h : (mx - x) / (double) w;
			return Math.max(0.0, Math.min(1.0, f));
		}
	}

	private record MixRow(String label, String sub, Supplier<Float> get, Consumer<Float> set) {
	}

	private static final String[] MIX_KEYS = {"categories", "animals", "hostile", "vehicles", "player", "blocks", "items", "music", "ui", "particles", "weather", "other", "all"};

	private final Screen parent;
	private final List<Hot> hots = new ArrayList<>();
	private Hot activeDrag;
	private boolean needsRebuild;
	private int curMx, curMy;

	private Tab tab = Tab.MUSIC;
	private int px, py, pw, ph;

	// Music tab
	private EditBox search;
	private String searchText = "";
	private int musicScroll;
	private Track popTrack;
	private int popX, popY;
	private boolean confirmClear;
	private boolean showFolders;
	private int listFilter;

	// Edit modal
	private Track editing;
	private String eTitle = "", eArtist = "", eAlbum = "";
	private volatile String eCover;
	private EditBox boxTitle, boxArtist, boxAlbum;

	// Settings tab
	private int setScroll;

	// Mixer tab
	private int mixTab;
	private boolean grouped = true;
	private int mixScroll;
	private List<MixRow> mixRows = List.of();
	private String mixKey = "";

	public VinylScreen(Screen parent) {
		super(Component.translatable("vinylmusicplayer.title"));
		this.parent = parent;
	}

	// ------------------------------------------------------------------ lifecycle

	private void computeLayout() {
		pw = Math.min(this.width - 12, 470);
		ph = Math.min(this.height - 12, 330);
		px = (this.width - pw) / 2;
		py = (this.height - ph) / 2;
	}

	private boolean scannedOnce;

	@Override
	protected void init() {
		computeLayout();
		if (!scannedOnce) {
			scannedOnce = true;
			MusicManager.get().rescanFolder();
		}
		search = null;
		boxTitle = boxArtist = boxAlbum = null;
		if (editing != null) {
			int mx0 = (this.width - 300) / 2;
			int my0 = (this.height - 150) / 2;
			boxTitle = makeBox(mx0 + 70, my0 + 22, 220, eTitle);
			boxArtist = makeBox(mx0 + 70, my0 + 44, 220, eArtist);
			boxAlbum = makeBox(mx0 + 70, my0 + 66, 220, eAlbum);
		} else if (!showFolders && !confirmClear && (tab == Tab.MIXER || (tab == Tab.MUSIC && MusicManager.get().isLocal()))) {
			int sx;
			int sy;
			if (tab == Tab.MUSIC) {
				sx = rowEndX(musicRowBLabels());
				sy = py + 44 + 20;
			} else {
				sx = rowEndX(mixRow2Labels());
				sy = mixRow2Y();
			}
			int w = Math.max(60, px + pw - 8 - sx);
			search = new EditBox(this.font, sx, sy, w, 16, Component.translatable("vinylmusicplayer.search"));
			search.setMaxLength(60);
			search.setHint(Component.translatable("vinylmusicplayer.search"));
			search.setValue(searchText);
			search.setResponder(s -> {
				searchText = s;
				musicScroll = 0;
				mixScroll = 0;
			});
			this.addRenderableWidget(search);
		}
	}

	private int rowEndX(String[] labels) {
		int x = px + 8;
		for (String l : labels) x += this.font.width(l) + 14 + 4;
		return x;
	}

	private String[] musicRowBLabels() {
		VinylConfig cfg = VinylConfig.get();
		boolean local = MusicManager.get().isLocal();
		String[] loopKeys = {"loop.off", "loop.all", "loop.one"};
		return new String[]{
				Ui.tr("shuffle") + ": " + Ui.tr(cfg.shuffle ? "on" : "off"),
				Ui.tr("loop") + ": " + Ui.tr(loopKeys[cfg.loopMode]),
				Ui.tr("source") + ": " + Ui.tr(local ? "source_short.local" : "source_short.system"),
				Ui.tr("show") + ": " + Ui.tr("filter." + listFilter)
		};
	}

	private String[] mixRow2Labels() {
		if (mixTab == 0) return new String[]{Ui.tr("mix.reset")};
		return new String[]{Ui.tr("mix.grouped") + ": " + Ui.tr(grouped ? "on" : "off"), Ui.tr("mix.reset")};
	}

	/** Positions of the mixer tab buttons, wrapped onto as many rows as needed. Returns {xs, ys, {rows}}. */
	private int[][] mixTabLayout() {
		int n = MIX_KEYS.length;
		int[] xs = new int[n];
		int[] ys = new int[n];
		int x = px + 8;
		int y = py + 44;
		int rows = 1;
		for (int i = 0; i < n; i++) {
			int w = this.font.width(Ui.tr("mix." + MIX_KEYS[i])) + 14;
			if (x + w > px + pw - 8 && x > px + 8) {
				x = px + 8;
				y += 20;
				rows++;
			}
			xs[i] = x;
			ys[i] = y;
			x += w + 4;
		}
		return new int[][]{xs, ys, {rows}};
	}

	private int mixRow2Y() {
		return py + 44 + mixTabLayout()[2][0] * 20;
	}

	private EditBox makeBox(int x, int y, int w, String value) {
		EditBox b = new EditBox(this.font, x, y, w, 16, Component.literal(""));
		b.setMaxLength(120);
		b.setValue(value);
		this.addRenderableWidget(b);
		return b;
	}

	@Override
	public void tick() {
		if (needsRebuild) {
			needsRebuild = false;
			this.rebuildWidgets();
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public void removed() {
		VinylConfig.save();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int delta = scrollY > 0 ? -2 : 2;
		if (tab == Tab.MUSIC) musicScroll = Math.max(0, musicScroll + delta);
		else if (tab == Tab.MIXER) mixScroll = Math.max(0, mixScroll + delta);
		else setScroll = Math.max(0, setScroll + delta);
		return true;
	}

	// ------------------------------------------------------------------ frame

	/** The panel is drawn here so Minecraft draws the text fields on top of it afterwards. */
	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractBackground(g, mx, my, delta);
		curMx = mx;
		curMy = my;
		hots.clear();
		DiscRenderer.advance(MusicManager.get().isPlaying());
		drawUi(g);
		if (activeDrag != null) {
			activeDrag.drag.accept(activeDrag.frac(mx, my));
		}
		InputHooks.poll(listener, mx, my);
	}

	/** Mouse press and release come from InputHooks. */
	private final InputHooks.Listener listener = new InputHooks.Listener() {
		@Override
		public boolean press(int button, double x, double y) {
			if (button != 0) return false;
			int mx = Double.isNaN(x) ? curMx : (int) Math.floor(x);
			int my = Double.isNaN(y) ? curMy : (int) Math.floor(y);
			curMx = mx;
			curMy = my;
			boolean hit = false;
			for (int i = hots.size() - 1; i >= 0; i--) {
				Hot h = hots.get(i);
				if (!h.contains(mx, my)) continue;
				hit = true;
				if (h.click != null) h.click.run();
				if (h.drag != null) {
					activeDrag = h;
					h.drag.accept(h.frac(mx, my));
				}
				break;
			}
			if (!hit && popTrack != null) popTrack = null;
			return false;
		}

		@Override
		public void release(int button) {
			activeDrag = null;
		}
	};

	public InputHooks.Listener inputListener() {
		return listener;
	}

	private boolean hover(int x, int y, int w, int h) {
		return Ui.in(curMx, curMy, x, y, w, h);
	}

	private void hot(int x, int y, int w, int h, Runnable click) {
		hots.add(new Hot(x, y, w, h, click, null, false));
	}

	private void dragH(int x, int y, int w, int h, DoubleConsumer drag) {
		hots.add(new Hot(x, y, w, h, null, drag, false));
	}

	private void dragV(int x, int y, int w, int h, DoubleConsumer drag) {
		hots.add(new Hot(x, y, w, h, null, drag, true));
	}

	/** Draws a button sized to its label and returns the x position for the next one. */
	private int btn(GuiGraphicsExtractor g, int x, int y, String label, boolean danger, boolean active, Runnable click) {
		int w = this.font.width(label) + 14;
		Ui.button(g, this.font, x, y, w, 16, label, hover(x, y, w, 16), danger, active);
		hot(x, y, w, 16, click);
		return x + w + 4;
	}

	private void setTab(Tab t) {
		if (tab == t) return;
		tab = t;
		popTrack = null;
		musicScroll = 0;
		mixScroll = 0;
		setScroll = 0;
		needsRebuild = true;
	}

	// ------------------------------------------------------------------ chrome

	private void drawUi(GuiGraphicsExtractor g) {
		computeLayout();
		Ui.rect(g, px, py, pw, ph, Ui.PANEL);
		Ui.border(g, px, py, pw, ph, Ui.BORDER);
		Ui.rect(g, px, py, pw, 2, Ui.ACCENT);

		MusicManager mm = MusicManager.get();
		// Small spinning disc in the header.
		DiscRenderer.draw(g, px + 18, py + 17, 20, mm.cover(), mm.title() + mm.artist());
		Ui.textShadow(g, this.font, Ui.tr("title"), px + 34, py + 9, Ui.TEXT);

		int closeX = px + pw - 22;
		Ui.button(g, this.font, closeX, py + 6, 16, 14, "X", hover(closeX, py + 6, 16, 14), true, false);
		hot(closeX, py + 6, 16, 14, this::onClose);

		String[] names = {Ui.tr("tab.music"), Ui.tr("tab.mixer"), Ui.tr("tab.settings")};
		Tab[] tabs = Tab.values();
		int tx = px + 8;
		for (int i = 0; i < tabs.length; i++) {
			int w = Math.max(80, this.font.width(names[i]) + 16);
			final Tab t = tabs[i];
			Ui.button(g, this.font, tx, py + 24, w, 16, names[i], hover(tx, py + 24, w, 16), false, tab == t);
			hot(tx, py + 24, w, 16, () -> setTab(t));
			tx += w + 4;
		}

		switch (tab) {
			case MUSIC -> drawMusic(g);
			case MIXER -> drawMixer(g);
			case SETTINGS -> drawSettings(g);
		}
		drawFooter(g);
		if (confirmClear) drawConfirm(g);
		if (showFolders) drawFolders(g);
		if (editing != null) drawEdit(g);
	}

	private void drawFooter(GuiGraphicsExtractor g) {
		MusicManager mm = MusicManager.get();
		int y = py + ph - 24;
		Ui.rect(g, px + 6, y - 3, pw - 12, 1, Ui.BORDER);
		int volW = 80;
		int volX = px + pw - 8 - volW - 30;
		int trX = volX - 90;
		int textW = Math.max(40, trX - (px + 8) - 8);
		String line;
		if (mm.hasTrack() && !mm.title().isEmpty()) {
			line = mm.artist().isEmpty() ? mm.title() : mm.title() + " - " + mm.artist();
			Ui.marquee(g, this.font, line, px + 8, y + 4, textW, Ui.TEXT, false);
		} else {
			Ui.text(g, this.font, Ui.fit(this.font, Ui.tr(mm.isLocal() ? "nothing_playing" : "system.waiting"), textW), px + 8, y + 4, Ui.DIM);
		}
		// Transport.
		boolean playing = mm.isPlaying();
		int cy = y + 8;
		boolean h1 = hover(trX, y, 28, 16);
		boolean h2 = hover(trX + 30, y, 28, 16);
		boolean h3 = hover(trX + 60, y, 28, 16);
		Ui.rect(g, trX, y, 28, 16, h1 ? Ui.BTN_HOVER : Ui.BTN);
		Ui.rect(g, trX + 30, y, 28, 16, h2 ? Ui.BTN_HOVER : Ui.BTN);
		Ui.rect(g, trX + 60, y, 28, 16, h3 ? Ui.BTN_HOVER : Ui.BTN);
		Ui.iconPrev(g, trX + 9, cy, Ui.TEXT);
		if (playing) Ui.iconPause(g, trX + 41, cy, Ui.ACCENT);
		else Ui.iconPlay(g, trX + 41, cy, Ui.ACCENT);
		Ui.iconNext(g, trX + 69, cy, Ui.TEXT);
		hot(trX, y, 28, 16, mm::previous);
		hot(trX + 30, y, 28, 16, mm::togglePlayPause);
		hot(trX + 60, y, 28, 16, mm::next);
		// Volume.
		Ui.slider(g, volX, y, volW, 16, mm.volume(), hover(volX, y, volW, 16));
		dragH(volX, y, volW, 16, f -> mm.setVolume((float) f));
		Ui.text(g, this.font, Math.round(mm.volume() * 100) + "%", volX + volW + 6, y + 4, Ui.DIM);
	}

	// ------------------------------------------------------------------ music tab

	private List<Track> filtered() {
		List<Track> all = MusicManager.get().playlist();
		String f = searchText.toLowerCase(Locale.ROOT).trim();
		List<Track> out = new ArrayList<>();
		for (Track t : all) {
			if (listFilter == 1 && t.vanilla) continue;
			if (listFilter == 2 && !t.vanilla) continue;
			if (!f.isEmpty() && !(t.title.toLowerCase(Locale.ROOT).contains(f) || t.artist.toLowerCase(Locale.ROOT).contains(f)
					|| t.album.toLowerCase(Locale.ROOT).contains(f))) continue;
			out.add(t);
		}
		return out;
	}

	private void drawMusic(GuiGraphicsExtractor g) {
		MusicManager mm = MusicManager.get();
		VinylConfig cfg = VinylConfig.get();
		boolean local = mm.isLocal();
		int x = px + 8;
		int y = py + 44;

		if (local) {
			x = btn(g, x, y, Ui.tr("add_song"), false, false, this::pickSongs);
			x = btn(g, x, y, Ui.tr("folders", mm.libraryFolders().size() + 1), false, false, () -> {
				showFolders = true;
				needsRebuild = true;
			});
			x = btn(g, x, y, Ui.tr("refresh"), false, false, () -> MusicManager.get().rescanFolder());
			btn(g, x, y, Ui.tr("delete_all"), true, false, () -> {
				confirmClear = true;
				needsRebuild = true;
			});
		}
		// Second row: toggles.
		String[] rb = musicRowBLabels();
		int x2 = px + 8;
		int y2 = y + 20;
		x2 = btn(g, x2, y2, rb[0], false, cfg.shuffle, () -> {
			cfg.shuffle = !cfg.shuffle;
			VinylConfig.markDirty();
		});
		x2 = btn(g, x2, y2, rb[1], false, cfg.loopMode != 0, () -> {
			cfg.loopMode = (cfg.loopMode + 1) % 3;
			VinylConfig.markDirty();
		});
		x2 = btn(g, x2, y2, rb[2], false, !local, () -> {
			mm.setLocalSource(!mm.isLocal());
			searchText = "";
			needsRebuild = true;
		});
		if (local) {
			btn(g, x2, y2, rb[3], false, listFilter != 0, () -> {
				listFilter = (listFilter + 1) % 3;
				musicScroll = 0;
				needsRebuild = true;
			});
		}

		int top = y + 42;
		int bottom = py + ph - 40;
		if (!local) {
			drawSystemPanel(g, top, bottom);
			return;
		}

		List<Track> view = filtered();
		int rowH = 22;
		int rows = Math.max(1, (bottom - top) / rowH);
		int maxScroll = Math.max(0, view.size() - rows);
		musicScroll = Math.max(0, Math.min(musicScroll, maxScroll));
		Ui.rect(g, px + 6, top - 2, pw - 12, bottom - top + 4, 0x40000000);

		if (view.isEmpty()) {
			String msg = mm.isScanning() ? Ui.tr("scanning") : Ui.tr("no_songs");
			Ui.centered(g, this.font, msg, px + pw / 2, top + 20, Ui.DIM);
			Ui.centered(g, this.font, Ui.tr("tip.files"), px + pw / 2, top + 34, Ui.DARK_DIM);
			if (!mm.message().isEmpty()) {
				Ui.centered(g, this.font, Ui.fit(this.font, mm.message(), pw - 20), px + pw / 2, top + 52, 0xFFE06666);
			}
		}
		Track current = mm.currentTrack();
		int listW = pw - 12 - (view.size() > rows ? 8 : 0);
		for (int i = 0; i < rows; i++) {
			int idx = musicScroll + i;
			if (idx >= view.size()) break;
			Track t = view.get(idx);
			int ry = top + i * rowH;
			int rx = px + 6;
			boolean isCur = t == current;
			boolean hv = hover(rx, ry, listW, rowH) && popTrack == null && !confirmClear && !showFolders && editing == null;
			if (t == popTrack) Ui.rect(g, rx, ry, listW, rowH, Ui.ROW_SELECT);
			else if (hv) Ui.rect(g, rx, ry, listW, rowH, Ui.ROW_HOVER);
			if (isCur) Ui.rect(g, rx, ry + 2, 2, rowH - 4, Ui.ACCENT);
			drawThumb(g, rx + 6, ry + 3, t.thumb);
			int tx = rx + 28;
			String dur = Ui.time(t.durationMs);
			int durW = this.font.width(dur);
			int textW = listW - 28 - durW - 12;
			Ui.text(g, this.font, Ui.fit(this.font, t.title, textW), tx, ry + 3, isCur ? Ui.ACCENT : Ui.TEXT);
			String sub = t.artist.isEmpty() ? t.album : (t.album.isEmpty() ? t.artist : t.artist + " - " + t.album);
			Ui.text(g, this.font, Ui.fit(this.font, sub, textW), tx, ry + 12, Ui.DIM);
			Ui.text(g, this.font, dur, rx + listW - durW - 6, ry + 7, Ui.DIM);
			hot(rx, ry, listW, rowH, () -> {
				popTrack = t;
				popX = curMx;
				popY = curMy;
			});
		}
		drawScrollbar(g, top, bottom, view.size(), rows, musicScroll, v -> musicScroll = v);

		// Status line under the list.
		long mine = mm.playlist().stream().filter(t -> !t.vanilla).count();
		String info = Ui.tr("songs_count2", mm.playlist().size(), mine, mm.playlist().size() - mine) + (mm.queueSize() > 0 ? "   " + Ui.tr("queue_count", mm.queueSize()) : "");
		if (!mm.message().isEmpty()) {
			Ui.text(g, this.font, Ui.fit(this.font, mm.message(), pw - 16), px + 8, bottom + 4, 0xFFE06666);
		} else {
			Ui.text(g, this.font, info, px + 8, bottom + 4, Ui.DARK_DIM);
		}

		if (popTrack != null) drawPopover(g);
	}

	private void drawThumb(GuiGraphicsExtractor g, int x, int y, int[] thumb) {
		if (thumb == null || thumb.length != 64) {
			Ui.rect(g, x, y, 16, 16, 0xFF30303A);
			Ui.rect(g, x + 6, y + 6, 4, 4, 0xFF15151B);
			return;
		}
		for (int j = 0; j < 8; j++) {
			for (int i = 0; i < 8; i++) {
				g.fill(x + i * 2, y + j * 2, x + i * 2 + 2, y + j * 2 + 2, thumb[j * 8 + i]);
			}
		}
	}

	private void drawScrollbar(GuiGraphicsExtractor g, int top, int bottom, int total, int visible, int scroll, java.util.function.IntConsumer setScroll) {
		if (total <= visible) return;
		int x = px + pw - 12;
		int h = bottom - top;
		Ui.rect(g, x, top, 5, h, 0xFF0E0E14);
		int thumbH = Math.max(14, h * visible / total);
		int maxScroll = total - visible;
		int thumbY = top + (h - thumbH) * scroll / Math.max(1, maxScroll);
		Ui.rect(g, x, thumbY, 5, thumbH, hover(x, top, 5, h) ? 0xFFFFFFFF : Ui.ACCENT);
		dragV(x - 3, top, 11, h, f -> setScroll.accept((int) Math.round(f * maxScroll)));
	}

	private void drawPopover(GuiGraphicsExtractor g) {
		Track t = popTrack;
		MusicManager mm = MusicManager.get();
		String[] labels = {Ui.tr("action.play_now"), Ui.tr("action.play_next"), Ui.tr("action.queue"), Ui.tr("action.edit"), Ui.tr("action.remove")};
		Runnable[] acts = {
				() -> mm.play(t),
				() -> mm.queueNext(t),
				() -> mm.queueLast(t),
				() -> {
					editing = t;
					eTitle = t.title;
					eArtist = t.artist;
					eAlbum = t.album;
					eCover = null;
					needsRebuild = true;
				},
				() -> mm.remove(t)
		};
		int w = 10;
		for (int k = 0; k < (t.vanilla ? 3 : labels.length); k++) w = Math.max(w, this.font.width(labels[k]) + 18);
		int count = t.vanilla ? 3 : labels.length;
		int h = count * 17 + 6;
		int x = Math.min(popX, px + pw - w - 4);
		int y = Math.min(popY, py + ph - h - 4);
		Ui.rect(g, x - 1, y - 1, w + 2, h + 2, Ui.BORDER);
		Ui.rect(g, x, y, w, h, 0xFF202029);
		Ui.text(g, this.font, Ui.fit(this.font, t.title, w - 8), x + 4, y - 11, Ui.TEXT);
		for (int i = 0; i < count; i++) {
			int iy = y + 3 + i * 17;
			boolean danger = !t.vanilla && i == labels.length - 1;
			boolean hv = hover(x + 2, iy, w - 4, 16);
			Ui.rect(g, x + 2, iy, w - 4, 16, hv ? (danger ? Ui.DANGER_HOVER : Ui.BTN_HOVER) : 0x00000000);
			Ui.text(g, this.font, labels[i], x + 8, iy + 4, danger ? 0xFFFF9A9A : Ui.TEXT);
			final Runnable act = acts[i];
			hot(x + 2, iy, w - 4, 16, () -> {
				popTrack = null;
				act.run();
			});
		}
	}

	private void drawSystemPanel(GuiGraphicsExtractor g, int top, int bottom) {
		MusicManager mm = MusicManager.get();
		var sys = mm.system();
		Ui.rect(g, px + 6, top - 2, pw - 12, bottom - top + 4, 0x40000000);
		int[] cover = sys.cover;
		int bx = px + 16;
		if (cover != null && cover.length == 400) {
			for (int j = 0; j < 20; j++) {
				for (int i = 0; i < 20; i++) {
					g.fill(bx + i * 4, top + 8 + j * 4, bx + i * 4 + 4, top + 12 + j * 4, cover[j * 20 + i]);
				}
			}
		} else {
			Ui.rect(g, bx, top + 8, 80, 80, 0xFF30303A);
		}
		int tx = bx + 96;
		int w = pw - (tx - px) - 16;
		if (sys.hasSession) {
			Ui.text(g, this.font, Ui.fit(this.font, sys.title, w), tx, top + 14, Ui.TEXT);
			Ui.text(g, this.font, Ui.fit(this.font, sys.artist, w), tx, top + 28, 0xFFC0C0CA);
			Ui.text(g, this.font, Ui.fit(this.font, sys.album, w), tx, top + 42, Ui.DIM);
		} else {
			Ui.text(g, this.font, Ui.fit(this.font, Ui.tr("system.waiting"), w), tx, top + 14, Ui.DIM);
		}
		String st = sys.status;
		if (st != null && !st.isEmpty()) {
			Ui.text(g, this.font, Ui.fit(this.font, st, w), tx, top + 60, 0xFFE0B066);
		}
		Ui.text(g, this.font, Ui.fit(this.font, Ui.tr("system.hint"), pw - 32), px + 16, top + 100, Ui.DARK_DIM);
		Ui.text(g, this.font, Ui.fit(this.font, Ui.tr("system.hint2"), pw - 32), px + 16, top + 112, Ui.DARK_DIM);
	}

	private void pickSongs() {
		FileDialog.pickFiles(Ui.tr("add_song"), VinylConfig.musicDir(), "Audio (mp3, ogg, wav)",
				new String[]{"*.mp3", "*.ogg", "*.wav"}, true, files -> MusicManager.get().addFiles(files));
	}

	private void pickFolder() {
		FileDialog.pickFolder(Ui.tr("add_folder"), VinylConfig.musicDir(), dir -> MusicManager.get().addLibraryFolder(dir));
	}

	// ------------------------------------------------------------------ modals

	private void drawConfirm(GuiGraphicsExtractor g) {
		MusicManager mm = MusicManager.get();
		hot(0, 0, this.width, this.height, () -> {
		});
		int w = 250;
		int h = 70;
		int x = (this.width - w) / 2;
		int y = (this.height - h) / 2;
		Ui.rect(g, x - 1, y - 1, w + 2, h + 2, Ui.BORDER);
		Ui.rect(g, x, y, w, h, 0xFF202029);
		Ui.centered(g, this.font, Ui.tr("confirm.delete_all"), x + w / 2, y + 14, Ui.TEXT);
		int bw = 80;
		int by = y + h - 26;
		int yx = x + w / 2 - bw - 4;
		int cx = x + w / 2 + 4;
		Ui.button(g, this.font, yx, by, bw, 16, Ui.tr("yes"), hover(yx, by, bw, 16), true, false);
		Ui.button(g, this.font, cx, by, bw, 16, Ui.tr("cancel"), hover(cx, by, bw, 16), false, false);
		hot(yx, by, bw, 16, () -> {
			mm.clearAll();
			confirmClear = false;
			needsRebuild = true;
		});
		hot(cx, by, bw, 16, () -> {
			confirmClear = false;
			needsRebuild = true;
		});
	}

	private void drawFolders(GuiGraphicsExtractor g) {
		MusicManager mm = MusicManager.get();
		VinylConfig cfg = VinylConfig.get();
		hot(0, 0, this.width, this.height, () -> {
		});
		List<String> folders = mm.libraryFolders();
		int maxRows = 6;
		int shown = Math.min(folders.size(), maxRows);
		int w = Math.min(pw - 16, 440);
		int h = 86 + (shown + 2) * 20 + (folders.size() > maxRows ? 12 : 0);
		int x = (this.width - w) / 2;
		int y = (this.height - h) / 2;
		Ui.rect(g, x - 1, y - 1, w + 2, h + 2, Ui.BORDER);
		Ui.rect(g, x, y, w, h, 0xFF202029);
		Ui.text(g, this.font, Ui.tr("folders.title"), x + 8, y + 7, Ui.ACCENT);
		Ui.text(g, this.font, Ui.fit(this.font, Ui.tr("folders.hint"), w - 16), x + 8, y + 20, Ui.DARK_DIM);

		int ry = y + 36;
		int bw = 0;
		for (String k : new String[]{"folders.open", "folders.remove", "folders.restore"}) bw = Math.max(bw, this.font.width(Ui.tr(k)) + 14);
		int bx = x + w - 8 - bw;
		int labelW = bx - x - 16;

		// Default 1: Minecraft's own songs.
		boolean mc = cfg.showMinecraftMusic;
		Ui.text(g, this.font, Ui.fit(this.font, Ui.tr("folders.minecraft"), labelW), x + 8, ry + 4, mc ? Ui.TEXT : Ui.DARK_DIM);
		Ui.button(g, this.font, bx, ry, bw, 16, Ui.tr(mc ? "folders.remove" : "folders.restore"), hover(bx, ry, bw, 16), mc, false);
		hot(bx, ry, bw, 16, () -> mm.setMinecraftMusic(!cfg.showMinecraftMusic));
		ry += 20;

		// Default 2: the music_player folder.
		boolean def = cfg.scanMusicFolder;
		int openW = this.font.width(Ui.tr("folders.open")) + 14;
		int ox = bx - openW - 4;
		Ui.text(g, this.font, Ui.fit(this.font, ".minecraft/music_player  (" + Ui.tr("folders.default") + ")", ox - x - 16), x + 8, ry + 4, def ? Ui.TEXT : Ui.DARK_DIM);
		Ui.button(g, this.font, ox, ry, openW, 16, Ui.tr("folders.open"), hover(ox, ry, openW, 16), false, false);
		hot(ox, ry, openW, 16, () -> FileDialog.openFolder(VinylConfig.musicDir()));
		Ui.button(g, this.font, bx, ry, bw, 16, Ui.tr(def ? "folders.remove" : "folders.restore"), hover(bx, ry, bw, 16), def, false);
		hot(bx, ry, bw, 16, () -> mm.setMusicFolderEnabled(!cfg.scanMusicFolder));
		ry += 20;

		for (int i = 0; i < shown; i++) {
			final String f = folders.get(i);
			Ui.text(g, this.font, Ui.fit(this.font, f, labelW), x + 8, ry + 4, Ui.TEXT);
			Ui.button(g, this.font, bx, ry, bw, 16, Ui.tr("folders.remove"), hover(bx, ry, bw, 16), true, false);
			hot(bx, ry, bw, 16, () -> mm.removeLibraryFolder(f));
			ry += 20;
		}
		if (folders.size() > maxRows) {
			Ui.text(g, this.font, Ui.tr("folders.more", folders.size() - maxRows), x + 8, ry + 2, Ui.DARK_DIM);
		}
		Ui.text(g, this.font, Ui.fit(this.font, Ui.tr("folders.note"), w - 16), x + 8, y + h - 44, Ui.DARK_DIM);

		int by = y + h - 26;
		int ax = x + 8;
		ax = btn(g, ax, by, Ui.tr("add_folder"), false, false, this::pickFolder);
		ax = btn(g, ax, by, Ui.tr("refresh"), false, false, () -> mm.rescanFolder());
		btn(g, ax, by, Ui.tr("folders.reset"), true, false, mm::resetLibrary);
		String close = Ui.tr("folders.close");
		int cw = this.font.width(close) + 14;
		int cx = x + w - 8 - cw;
		Ui.button(g, this.font, cx, by, cw, 16, close, hover(cx, by, cw, 16), false, true);
		hot(cx, by, cw, 16, () -> {
			showFolders = false;
			needsRebuild = true;
		});
	}

	private void drawEdit(GuiGraphicsExtractor g) {
		hot(0, 0, this.width, this.height, () -> {
		});
		int w = 300;
		int h = 150;
		int x = (this.width - w) / 2;
		int y = (this.height - h) / 2;
		Ui.rect(g, x - 1, y - 1, w + 2, h + 2, Ui.BORDER);
		Ui.rect(g, x, y, w, h, 0xFF202029);
		Ui.text(g, this.font, Ui.tr("action.edit"), x + 8, y + 6, Ui.ACCENT);
		Ui.text(g, this.font, Ui.tr("edit.title"), x + 8, y + 26, Ui.DIM);
		Ui.text(g, this.font, Ui.tr("edit.artist"), x + 8, y + 48, Ui.DIM);
		Ui.text(g, this.font, Ui.tr("edit.album"), x + 8, y + 70, Ui.DIM);

		int by = y + 90;
		int bx = x + 8;
		bx = btn(g, bx, by, Ui.tr("edit.choose_cover"), false, false, () -> FileDialog.pickFiles(Ui.tr("edit.choose_cover"),
				VinylConfig.musicDir(), "Images", new String[]{"*.png", "*.jpg", "*.jpeg"}, false,
				files -> eCover = files.get(0).toString()));
		btn(g, bx, by, Ui.tr("edit.reset_cover"), false, false, () -> eCover = "");
		String coverInfo;
		if (eCover == null) coverInfo = "";
		else if (eCover.isEmpty()) coverInfo = Ui.tr("edit.cover_reset");
		else coverInfo = Ui.tr("edit.cover_chosen", Path.of(eCover).getFileName().toString());
		Ui.text(g, this.font, Ui.fit(this.font, coverInfo, w - 16), x + 8, by + 22, Ui.DIM);

		int by2 = y + h - 24;
		int bw = 80;
		int sx = x + w / 2 - bw - 4;
		int cx = x + w / 2 + 4;
		Ui.button(g, this.font, sx, by2, bw, 16, Ui.tr("edit.save"), hover(sx, by2, bw, 16), false, true);
		Ui.button(g, this.font, cx, by2, bw, 16, Ui.tr("cancel"), hover(cx, by2, bw, 16), false, false);
		hot(sx, by2, bw, 16, this::saveEdit);
		hot(cx, by2, bw, 16, () -> {
			editing = null;
			needsRebuild = true;
		});
	}

	private void saveEdit() {
		Track t = editing;
		if (t != null) {
			VinylConfig cfg = VinylConfig.get();
			VinylConfig.TrackOverride o = cfg.overrides.computeIfAbsent(t.path, k -> new VinylConfig.TrackOverride());
			o.title = boxTitle != null ? boxTitle.getValue() : t.title;
			o.artist = boxArtist != null ? boxArtist.getValue() : t.artist;
			o.album = boxAlbum != null ? boxAlbum.getValue() : t.album;
			if (eCover != null) o.coverPath = eCover.isEmpty() ? null : eCover;
			VinylConfig.markDirty();
			MusicManager.get().refresh(t);
		}
		editing = null;
		needsRebuild = true;
	}

	// ------------------------------------------------------------------ mixer tab

	private void rebuildMixRows() {
		String key = mixTab + "|" + grouped + "|" + searchText;
		if (key.equals(mixKey)) return;
		mixKey = key;
		VinylConfig cfg = VinylConfig.get();
		List<MixRow> rows = new ArrayList<>();
		String f = searchText.toLowerCase(Locale.ROOT).trim();
		if (mixTab == 0) {
			for (String c : SoundCatalog.CATEGORIES) {
				String label = Ui.tr("cat." + c);
				if (!f.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(f) && !c.toLowerCase(Locale.ROOT).contains(f)) continue;
				rows.add(new MixRow(label, Ui.tr("cat." + c + ".hint"), () -> cfg.getCategory(c), v -> cfg.setCategory(c, v)));
			}
		} else {
			SoundCatalog.Tab t = mixTab <= SoundCatalog.Tab.values().length ? SoundCatalog.Tab.values()[mixTab - 1] : null;
			List<SoundCatalog.Entry> list = SoundCatalog.forTab(t, searchText);
			if (grouped) {
				Map<String, Integer> counts = new TreeMap<>();
				for (SoundCatalog.Entry e : list) counts.merge(e.group(), 1, Integer::sum);
				for (Map.Entry<String, Integer> en : counts.entrySet()) {
					String grp = en.getKey();
					rows.add(new MixRow(SoundCatalog.pretty(grp), grp + "  (" + en.getValue() + ")", () -> cfg.getGroup(grp), v -> cfg.setGroup(grp, v)));
				}
			} else {
				for (SoundCatalog.Entry e : list) {
					String id = e.id();
					rows.add(new MixRow(e.path(), "", () -> cfg.getSound(id), v -> cfg.setSound(id, v)));
				}
			}
		}
		mixRows = rows;
	}

	private void drawMixer(GuiGraphicsExtractor g) {
		VinylConfig cfg = VinylConfig.get();
		int[][] lay = mixTabLayout();
		for (int i = 0; i < MIX_KEYS.length; i++) {
			final int idx = i;
			btn(g, lay[0][i], lay[1][i], Ui.tr("mix." + MIX_KEYS[i]), false, mixTab == i, () -> {
				mixTab = idx;
				mixScroll = 0;
				needsRebuild = true;
			});
		}
		int y2 = py + 44 + lay[2][0] * 20;
		int x2 = px + 8;
		if (mixTab != 0) {
			x2 = btn(g, x2, y2, Ui.tr("mix.grouped") + ": " + Ui.tr(grouped ? "on" : "off"), false, grouped, () -> {
				grouped = !grouped;
				mixScroll = 0;
				needsRebuild = true;
			});
		}
		btn(g, x2, y2, Ui.tr("mix.reset"), true, false, () -> {
			cfg.resetMixer();
			mixKey = "";
		});

		rebuildMixRows();
		int top = y2 + 22;
		int bottom = py + ph - 40;
		int rowH = 20;
		int rows = Math.max(1, (bottom - top) / rowH);
		int maxScroll = Math.max(0, mixRows.size() - rows);
		mixScroll = Math.max(0, Math.min(mixScroll, maxScroll));
		Ui.rect(g, px + 6, top - 2, pw - 12, bottom - top + 4, 0x40000000);

		int sliderW = Math.max(70, Math.min(120, pw / 4));
		int sliderX = px + pw - 16 - 34 - sliderW - 30;
		int labelW = Math.max(60, sliderX - (px + 12) - 6);
		if (mixRows.isEmpty()) {
			Ui.centered(g, this.font, Ui.tr("mix.empty"), px + pw / 2, top + 20, Ui.DIM);
		}
		for (int i = 0; i < rows; i++) {
			int idx = mixScroll + i;
			if (idx >= mixRows.size()) break;
			MixRow row = mixRows.get(idx);
			int ry = top + i * rowH;
			float v = row.get().get().floatValue();
			boolean hv = hover(px + 6, ry, pw - 24, rowH) && !confirmClear && !showFolders && editing == null;
			if (hv) Ui.rect(g, px + 6, ry, pw - 24, rowH, 0x18FFFFFF);
			if (row.sub().isEmpty()) {
				Ui.text(g, this.font, Ui.fit(this.font, row.label(), labelW), px + 12, ry + 6, Ui.TEXT);
			} else {
				Ui.text(g, this.font, Ui.fit(this.font, row.label(), labelW), px + 12, ry + 1, Ui.TEXT);
				Ui.text(g, this.font, Ui.fit(this.font, row.sub(), labelW), px + 12, ry + 10, Ui.DARK_DIM);
			}
			Ui.slider(g, sliderX, ry + 2, sliderW, 16, v, hover(sliderX, ry + 2, sliderW, 16));
			dragH(sliderX, ry + 2, sliderW, 16, f -> row.set().accept(Math.round((float) f * 100f) / 100f));
			String pct = Math.round(v * 100) + "%";
			int col = v <= 0.001f ? 0xFFE06666 : (v < 0.999f ? Ui.ACCENT : Ui.DIM);
			Ui.text(g, this.font, pct, sliderX + sliderW + 6, ry + 6, col);
			int mx0 = sliderX + sliderW + 36;
			boolean muted = v <= 0.001f;
			Ui.button(g, this.font, mx0, ry + 2, 22, 16, "M", hover(mx0, ry + 2, 22, 16), muted, false);
			hot(mx0, ry + 2, 22, 16, () -> row.set().accept(row.get().get().floatValue() <= 0.001f ? 1f : 0f));
		}
		drawScrollbar(g, top, bottom, mixRows.size(), rows, mixScroll, v -> mixScroll = v);
		Ui.text(g, this.font, Ui.fit(this.font, Ui.tr("mix.tip"), pw - 16), px + 8, bottom + 4, Ui.DARK_DIM);
	}

	// ------------------------------------------------------------------ settings tab

	private static final int[] VINYL_COLORS = {0x101014, 0xB02020, 0xD2691E, 0xD4A82A, 0x1E8C4A, 0x1B8C8C, 0x2A56C6, 0x7B3FBF, 0xD9558F, 0xE8E8EE};
	private static final int[] LABEL_COLORS = {-1, 0xE9A23B, 0xE05252, 0x1DB954, 0x3FA2F6, 0xA96BFF, 0xFF5C9E, 0xF2F2F2, 0x202020};
	private static final int[] ACCENT_COLORS = {0xE9A23B, 0x1DB954, 0x3FA2F6, 0xFF5C9E, 0xE05252, 0xA96BFF, 0xFFFFFF};

	private static final int K_HEADER = 0, K_TOGGLE = 1, K_CYCLE = 2, K_SLIDER = 3, K_TEXT = 4, K_BUTTON = 5, K_SWATCH = 6;

	private static final class SRow {
		int kind;
		String label = "";
		Supplier<String> text = () -> "";
		Supplier<Boolean> flag = () -> false;
		Runnable action = () -> {
		};
		Supplier<Float> norm = () -> 0f;
		Consumer<Float> setNorm = v -> {
		};
		Supplier<Integer> color = () -> 0;
	}

	private SRow header(String key) {
		SRow r = new SRow();
		r.kind = K_HEADER;
		r.label = Ui.tr(key);
		return r;
	}

	private SRow toggle(String key, Supplier<Boolean> get, Consumer<Boolean> set) {
		SRow r = new SRow();
		r.kind = K_TOGGLE;
		r.label = Ui.tr(key);
		r.flag = get;
		r.action = () -> {
			set.accept(!get.get());
			VinylConfig.markDirty();
		};
		return r;
	}

	private SRow cycle(String key, Supplier<String> value, Runnable next) {
		SRow r = new SRow();
		r.kind = K_CYCLE;
		r.label = Ui.tr(key);
		r.text = value;
		r.action = () -> {
			next.run();
			VinylConfig.markDirty();
		};
		return r;
	}

	private SRow swatch(String key, Supplier<Integer> color, Runnable next) {
		SRow r = new SRow();
		r.kind = K_SWATCH;
		r.label = Ui.tr(key);
		r.color = color;
		r.action = () -> {
			next.run();
			VinylConfig.markDirty();
		};
		return r;
	}

	private SRow slider(String key, Supplier<Float> norm, Consumer<Float> set, Supplier<String> text) {
		SRow r = new SRow();
		r.kind = K_SLIDER;
		r.label = Ui.tr(key);
		r.norm = norm;
		r.text = text;
		r.setNorm = v -> {
			set.accept(Math.max(0f, Math.min(1f, v)));
			VinylConfig.markDirty();
		};
		return r;
	}

	private SRow button(String key, Runnable action) {
		SRow r = new SRow();
		r.kind = K_BUTTON;
		r.label = Ui.tr(key);
		r.action = () -> {
			action.run();
			VinylConfig.markDirty();
		};
		return r;
	}

	private SRow info(Supplier<String> text) {
		SRow r = new SRow();
		r.kind = K_TEXT;
		r.text = text;
		return r;
	}

	private static int nextIn(int[] list, int current) {
		for (int i = 0; i < list.length; i++) {
			if (list[i] == current) return list[(i + 1) % list.length];
		}
		return list[0];
	}

	private static int channel(int rgb, int shift) {
		return (rgb >> shift) & 0xFF;
	}

	private SRow rgbSlider(String key, int shift, Supplier<Integer> get, Consumer<Integer> set) {
		return slider(key, () -> channel(get.get(), shift) / 255f, v -> {
			int c = get.get();
			c = (c & ~(0xFF << shift)) | (Math.round(v * 255f) << shift);
			set.accept(c & 0xFFFFFF);
		}, () -> String.valueOf(channel(get.get(), shift)));
	}

	private void placementRows(List<SRow> rows, VinylConfig.Placement p, int defaultAnchor) {
		rows.add(cycle("set.anchor", () -> Ui.tr("anchor." + p.anchor), () -> p.anchor = (p.anchor + 1) % 9));
		rows.add(slider("set.offx", () -> (p.offX + 400) / 800f, v -> p.offX = Math.round((v * 800f - 400f) / 5f) * 5,
				() -> String.valueOf(p.offX)));
		rows.add(slider("set.offy", () -> (p.offY + 400) / 800f, v -> p.offY = Math.round((v * 800f - 400f) / 5f) * 5,
				() -> String.valueOf(p.offY)));
		rows.add(button("set.reset_pos", () -> {
			p.anchor = defaultAnchor;
			p.offX = 0;
			p.offY = 0;
		}));
	}

	private List<SRow> settingsRows() {
		VinylConfig cfg = VinylConfig.get();
		MusicManager mm = MusicManager.get();
		List<SRow> rows = new ArrayList<>();

		rows.add(header("set.sec.pause"));
		rows.add(toggle("set.show", () -> cfg.showOnPauseMenu, v -> cfg.showOnPauseMenu = v));
		placementRows(rows, cfg.pausePlacement, 6);

		rows.add(header("set.sec.title"));
		rows.add(toggle("set.show", () -> cfg.showOnTitleScreen, v -> cfg.showOnTitleScreen = v));
		placementRows(rows, cfg.titlePlacement, 6);

		rows.add(header("set.sec.game"));
		rows.add(cycle("set.hud_mode", () -> Ui.tr("hud_mode." + cfg.hudMode), () -> cfg.hudMode = (cfg.hudMode + 1) % 3));
		rows.add(toggle("set.other_screens", () -> cfg.showOnOtherScreens, v -> cfg.showOnOtherScreens = v));
		placementRows(rows, cfg.hudPlacement, 6);

		rows.add(header("set.sec.look"));
		rows.add(slider("set.size", () -> (cfg.scale - 0.5f) / 1.5f, v -> cfg.scale = Math.round((0.5f + v * 1.5f) * 20f) / 20f,
				() -> Math.round(cfg.scale * 100) + "%"));
		rows.add(slider("set.card_size", () -> (cfg.cardScale - 0.5f) / 1.5f, v -> cfg.cardScale = Math.round((0.5f + v * 1.5f) * 20f) / 20f,
				() -> Math.round(cfg.cardScale * 100) + "%"));
		rows.add(slider("set.opacity", () -> (cfg.opacity - 0.2f) / 0.8f, v -> cfg.opacity = Math.round((0.2f + v * 0.8f) * 20f) / 20f,
				() -> Math.round(cfg.opacity * 100) + "%"));
		rows.add(cycle("set.style", () -> Ui.tr("style." + cfg.discStyle), () -> cfg.discStyle = (cfg.discStyle + 1) % 2));
		rows.add(swatch("set.vinyl_color", () -> cfg.vinylColor, () -> cfg.vinylColor = nextIn(VINYL_COLORS, cfg.vinylColor)));
		rows.add(rgbSlider("set.red", 16, () -> cfg.vinylColor, v -> cfg.vinylColor = v));
		rows.add(rgbSlider("set.green", 8, () -> cfg.vinylColor, v -> cfg.vinylColor = v));
		rows.add(rgbSlider("set.blue", 0, () -> cfg.vinylColor, v -> cfg.vinylColor = v));
		rows.add(swatch("set.label_color", () -> cfg.labelColor < 0 ? -1 : cfg.labelColor, () -> cfg.labelColor = nextIn(LABEL_COLORS, cfg.labelColor)));
		rows.add(swatch("set.accent_color", () -> cfg.accentColor, () -> cfg.accentColor = nextIn(ACCENT_COLORS, cfg.accentColor)));
		rows.add(slider("set.spin", () -> (cfg.spinSpeed - 0.25f) / 2.75f, v -> cfg.spinSpeed = Math.round((0.25f + v * 2.75f) * 20f) / 20f,
				() -> String.format(Locale.ROOT, "%.2fx", cfg.spinSpeed)));
		rows.add(toggle("set.shine", () -> cfg.shine, v -> cfg.shine = v));
		rows.add(toggle("set.show_info", () -> cfg.showInfo, v -> cfg.showInfo = v));
		rows.add(toggle("set.show_volume", () -> cfg.showVolumeBar, v -> cfg.showVolumeBar = v));
		rows.add(button("set.reset_look", cfg::resetLook));

		rows.add(header("set.sec.audio"));
		rows.add(slider("set.volume", mm::volume, v -> mm.setVolume(v), () -> Math.round(mm.volume() * 100) + "%"));
		rows.add(toggle("set.mute_vanilla", () -> cfg.muteVanillaMusic, v -> cfg.muteVanillaMusic = v));
		rows.add(toggle("set.minecraft_music", () -> cfg.showMinecraftMusic, v -> mm.setMinecraftMusic(v)));
		rows.add(toggle("set.autoplay", () -> cfg.autoplay, v -> cfg.autoplay = v));

		rows.add(header("set.sec.diag"));
		rows.add(info(() -> "Build " + Diagnostics.BUILD));
		rows.add(info(() -> Ui.tr("diag.hooks", yn(Diagnostics.screenHookRegistered), yn(Diagnostics.hudRegistered), yn(Diagnostics.soundMixinHit))));
		rows.add(info(() -> Ui.tr("diag.drawn", yn(Diagnostics.overlayDrawn), yn(Diagnostics.hudDrawn), yn(Diagnostics.modMenuPresent))));
		rows.add(info(() -> Ui.tr("diag.input", yn(InputHooks.pressHooked), yn(InputHooks.releaseHooked), String.valueOf(InputHooks.eventPresses), String.valueOf(InputHooks.pollPresses))));
		rows.add(info(() -> Ui.tr("diag.disc", String.valueOf(Overlay.pressesHandled), Overlay.lastResult)));
		rows.add(info(() -> Ui.tr("diag.last_press", InputHooks.lastPress)));
		rows.add(info(() -> Diagnostics.lastError.isEmpty() ? Ui.tr("diag.no_errors") : Diagnostics.lastError));
		rows.add(info(() -> Ui.tr("set.files", "config/vinyl-music-player.json")));
		rows.add(info(() -> Ui.tr("set.music_folder", "music_player")));
		return rows;
	}

	private static String yn(boolean b) {
		return Ui.tr(b ? "yes_short" : "no_short");
	}

	private void drawSettings(GuiGraphicsExtractor g) {
		List<SRow> rows = settingsRows();
		int top = py + 46;
		int bottom = py + ph - 40;
		int rowH = 22;
		int visible = Math.max(1, (bottom - top) / rowH);
		int maxScroll = Math.max(0, rows.size() - visible);
		setScroll = Math.max(0, Math.min(setScroll, maxScroll));
		int x = px + 12;
		int ctlW = 110;
		int ctlX = px + pw - 22 - ctlW;
		Ui.rect(g, px + 6, top - 2, pw - 12, bottom - top + 4, 0x40000000);

		for (int i = 0; i < visible; i++) {
			int idx = setScroll + i;
			if (idx >= rows.size()) break;
			SRow r = rows.get(idx);
			int y = top + i * rowH;
			switch (r.kind) {
				case K_HEADER -> {
					Ui.rect(g, px + 8, y + 15, pw - 28, 1, Ui.BORDER);
					Ui.text(g, this.font, r.label, x, y + 5, Ui.ACCENT);
				}
				case K_TOGGLE -> {
					boolean v = r.flag.get();
					Ui.text(g, this.font, Ui.fit(this.font, r.label, ctlX - x - 8), x, y + 5, Ui.TEXT);
					Ui.button(g, this.font, ctlX, y + 1, ctlW, 16, Ui.tr(v ? "on" : "off"), hover(ctlX, y + 1, ctlW, 16), false, v);
					hot(ctlX, y + 1, ctlW, 16, r.action);
				}
				case K_CYCLE -> {
					Ui.text(g, this.font, Ui.fit(this.font, r.label, ctlX - x - 8), x, y + 5, Ui.TEXT);
					Ui.button(g, this.font, ctlX, y + 1, ctlW, 16, Ui.fit(this.font, r.text.get(), ctlW - 8), hover(ctlX, y + 1, ctlW, 16), false, false);
					hot(ctlX, y + 1, ctlW, 16, r.action);
				}
				case K_SWATCH -> {
					Ui.text(g, this.font, Ui.fit(this.font, r.label, ctlX - x - 8), x, y + 5, Ui.TEXT);
					int c = r.color.get();
					boolean hv = hover(ctlX, y + 1, ctlW, 16);
					Ui.rect(g, ctlX, y + 1, ctlW, 16, hv ? Ui.BTN_HOVER : Ui.BTN);
					if (c < 0) {
						Ui.centered(g, this.font, Ui.tr("auto"), ctlX + ctlW / 2, y + 5, Ui.TEXT);
					} else {
						Ui.rect(g, ctlX + 3, y + 4, ctlW - 6, 10, 0xFF000000 | c);
						Ui.border(g, ctlX + 3, y + 4, ctlW - 6, 10, Ui.BORDER);
					}
					hot(ctlX, y + 1, ctlW, 16, r.action);
				}
				case K_SLIDER -> {
					Ui.text(g, this.font, Ui.fit(this.font, r.label + "  " + r.text.get(), ctlX - x - 8), x, y + 5, Ui.TEXT);
					Ui.slider(g, ctlX, y + 1, ctlW, 16, r.norm.get(), hover(ctlX, y + 1, ctlW, 16));
					dragH(ctlX, y + 1, ctlW, 16, f -> r.setNorm.accept((float) f));
				}
				case K_BUTTON -> {
					int w = this.font.width(r.label) + 16;
					Ui.button(g, this.font, x, y + 1, w, 16, r.label, hover(x, y + 1, w, 16), false, false);
					hot(x, y + 1, w, 16, r.action);
				}
				default -> Ui.text(g, this.font, Ui.fit(this.font, r.text.get(), pw - 40), x, y + 5, Ui.DARK_DIM);
			}
		}
		drawScrollbar(g, top, bottom, rows.size(), visible, setScroll, v -> setScroll = v);
		Ui.text(g, this.font, Ui.fit(this.font, Ui.tr("set.tip"), pw - 16), px + 8, bottom + 4, Ui.DARK_DIM);
	}
}
