package com.vinylmusic.gui;

import com.vinylmusic.audio.MusicManager;
import com.vinylmusic.client.Diagnostics;
import com.vinylmusic.config.VinylConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * The disc and its song card: spinning disc, song info, transport buttons and a volume bar.
 * Left click on the disc opens the full screen. Dragging with the right mouse button moves the widget.
 * A right click without dragging opens a small menu to make the disc or the card bigger or smaller.
 * The card has its own size, separate from the disc.
 */
public final class Overlay {
	private Overlay() {
	}

	private static final int MARGIN = 6;
	private static final int GAP = 4;
	private static final int INFO_W = 112;
	private static final int VOL_W = 7;
	/** Height of the card before scaling. */
	private static final int CH = 72;
	private static final int MENU_W = 150;
	private static final int MENU_ROW = 14;
	private static final String[] MENU_KEYS = {"menu.disc_bigger", "menu.disc_smaller", "menu.card_bigger", "menu.card_smaller", "menu.reset", "menu.hide", "menu.settings"};

	private static boolean leftHeld;
	private static boolean rightHeld;
	private static boolean draggingVolume;
	private static boolean draggingMove;
	private static boolean moved;
	private static int grabX;
	private static int grabY;
	private static int pressX;
	private static int pressY;
	private static Screen pendingParent;

	private static boolean menuOpen;
	private static int menuX;
	private static int menuY;

	// What was drawn last frame, so a click can be matched against it.
	private static Screen lastScreen;
	private static Layout lastLayout;
	private static boolean lastInteractive;
	private static int lastMx;
	private static int lastMy;
	private static int lastSw;
	private static int lastSh;
	private static long lastDrawNs;
	private static Screen lastDrawScreen;

	public static volatile int pressesHandled;
	public static volatile String lastResult = "none yet";
	private static int logged;

	/** All positions in screen pixels. Card values are scaled by cs. */
	private record Layout(int x, int y, int d, int discX, int discY, int cardX, int cardY, float cs,
						  boolean info, boolean vol, boolean hasCard, int totalW, int totalH) {
		int cardW() {
			return (info ? INFO_W : 0) + (info && vol ? GAP : 0) + (vol ? VOL_W : 0);
		}

		int volLocalX() {
			return info ? INFO_W + GAP : 0;
		}

		float lx(int mx) {
			return (mx - cardX) / cs;
		}

		float ly(int my) {
			return (my - cardY) / cs;
		}
	}

	private static int baseX(int col, int sw, int totalW) {
		return col == 0 ? MARGIN : (col == 1 ? (sw - totalW) / 2 : sw - MARGIN - totalW);
	}

	private static int baseY(int row, int sh, int h) {
		return row == 0 ? MARGIN : (row == 1 ? (sh - h) / 2 : sh - MARGIN - h);
	}

	private static Layout layout(int sw, int sh, VinylConfig cfg, VinylConfig.Placement p) {
		int d = Math.round(72 * cfg.scale);
		float cs = cfg.cardScale;
		boolean info = cfg.showInfo;
		boolean vol = cfg.showVolumeBar;
		int cwLocal = (info ? INFO_W : 0) + (info && vol ? GAP : 0) + (vol ? VOL_W : 0);
		boolean hasCard = cwLocal > 0;
		int cardW = Math.round(cwLocal * cs);
		int cardH = Math.round(CH * cs);
		int totalW = d + (hasCard ? GAP + cardW : 0);
		int totalH = Math.max(d, hasCard ? cardH : d);
		int col = p.anchor % 3;
		int row = p.anchor / 3;
		int x = baseX(col, sw, totalW) + p.offX;
		int y = baseY(row, sh, totalH) + p.offY;
		x = Math.max(0, Math.min(x, Math.max(0, sw - totalW)));
		y = Math.max(0, Math.min(y, Math.max(0, sh - totalH)));
		boolean mirror = col == 2;
		int discX;
		int cardX;
		if (!mirror) {
			discX = x;
			cardX = x + d + GAP;
		} else {
			cardX = x;
			discX = x + (hasCard ? cardW + GAP : 0);
		}
		int discY = y + (totalH - d) / 2;
		int cardY = y + (totalH - cardH) / 2;
		return new Layout(x, y, d, discX, discY, cardX, cardY, cs, info, vol, hasCard, totalW, totalH);
	}

	/** Called from the Fabric screen event for every screen that is drawn. */
	public static void onScreenRender(Screen screen, GuiGraphicsExtractor g, int mx, int my) {
		if (Diagnostics.hasError() || screen instanceof VinylScreen) return;
		long now = System.nanoTime();
		if (screen == lastDrawScreen && now - lastDrawNs < 1_000_000L) return; // duplicate handler, same frame
		lastDrawScreen = screen;
		lastDrawNs = now;
		try {
			VinylConfig cfg = VinylConfig.get();
			Minecraft mc = Minecraft.getInstance();
			VinylConfig.Placement p;
			boolean interactive;
			if (screen instanceof PauseScreen) {
				if (!cfg.showOnPauseMenu) return;
				p = cfg.pausePlacement;
				interactive = true;
			} else if (screen instanceof TitleScreen) {
				if (!cfg.showOnTitleScreen) return;
				p = cfg.titlePlacement;
				interactive = true;
			} else if (cfg.showOnOtherScreens && mc.level != null) {
				p = cfg.hudPlacement;
				interactive = false;
			} else {
				return;
			}
			Layout l = layout(screen.width, screen.height, cfg, p);
			draw(g, mc.font, cfg, l, mx, my, interactive);
			if (interactive) drawMenu(g, mc.font, screen, mx, my);
			Diagnostics.overlayDrawn = true;
			if (interactive) {
				lastScreen = screen;
				lastLayout = l;
				lastInteractive = true;
				lastMx = mx;
				lastMy = my;
				lastSw = screen.width;
				lastSh = screen.height;
				applyDrags(screen, p, l, mx, my);
				InputHooks.poll(LISTENER, mx, my);
			} else if (lastScreen == screen) {
				lastInteractive = false;
			}
		} catch (Throwable t) {
			Diagnostics.error("screen overlay", t);
		}
	}

	/** Called from the HUD element during normal play. */
	public static void renderHud(GuiGraphicsExtractor g) {
		if (Diagnostics.hasError()) return;
		try {
			VinylConfig cfg = VinylConfig.get();
			if (cfg.hudMode == 0) return;
			Minecraft mc = Minecraft.getInstance();
			if (mc.gui.screen() != null) return;
			if (cfg.hudMode == 2 && !MusicManager.get().isPlaying()) return;
			int sw = mc.getWindow().getGuiScaledWidth();
			int sh = mc.getWindow().getGuiScaledHeight();
			Layout l = layout(sw, sh, cfg, cfg.hudPlacement);
			draw(g, mc.font, cfg, l, -1, -1, false);
			Diagnostics.hudDrawn = true;
		} catch (Throwable t) {
			Diagnostics.error("HUD overlay", t);
		}
	}

	/** Runs on the client tick. Opens the full screen outside of the render pass. */
	public static void tick(Minecraft mc) {
		if (pendingParent != null) {
			Screen parent = pendingParent;
			pendingParent = null;
			menuOpen = false;
			mc.gui.setScreen(new VinylScreen(parent));
		}
	}

	// ------------------------------------------------------------------ drawing

	private static void draw(GuiGraphicsExtractor g, Font font, VinylConfig cfg, Layout l, int mx, int my, boolean interactive) {
		MusicManager mm = MusicManager.get();
		boolean playing = mm.isPlaying();
		DiscRenderer.advance(playing);
		float a = cfg.opacity;
		int accent = 0xFF000000 | cfg.accentColor;

		String title = mm.title();
		String artist = mm.artist();
		String album = mm.album();
		boolean has = mm.hasTrack() && !title.isEmpty();

		DiscRenderer.draw(g, l.discX() + l.d() / 2, l.discY() + l.d() / 2, l.d(), mm.cover(), title + artist, a);

		if (l.hasCard()) drawCard(g, font, cfg, l, mm, playing, has, title, artist, album, mx, my, interactive, a, accent);

		if (interactive && !draggingMove && Ui.in(mx, my, l.x(), l.y(), l.totalW(), l.totalH()) && !menuOpen) {
			Ui.textShadow(g, font, Ui.tr("overlay_hint"), l.x(), Math.max(1, l.y() - 11), Ui.TEXT);
		}
	}

	private static void drawCard(GuiGraphicsExtractor g, Font font, VinylConfig cfg, Layout l, MusicManager mm, boolean playing,
								 boolean has, String title, String artist, String album, int mx, int my, boolean interactive,
								 float a, int accent) {
		float cs = l.cs();
		float lmx = l.lx(mx);
		float lmy = l.ly(my);
		int infoTop = CH / 2 - 25;
		int btnY = infoTop + 36;

		// Rectangles and icons, drawn in the card's own scaled space.
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate((float) l.cardX(), (float) l.cardY());
		pose.scale(cs, cs);
		if (l.info()) {
			Ui.rect(g, -3, infoTop - 3, INFO_W + 6, 56, Ui.alpha(0x90000000, a));
			long dur = mm.durationMs();
			if (mm.isLocal() && dur > 0) {
				Ui.rect(g, 0, infoTop + 33, INFO_W, 2, Ui.alpha(0xFF303038, a));
				int filled = (int) Math.min(INFO_W, INFO_W * mm.positionMs() / dur);
				Ui.rect(g, 0, infoTop + 33, filled, 2, Ui.alpha(accent, a));
			}
			int by = btnY + 6;
			boolean hPrev = interactive && Ui.in((int) lmx, (int) lmy, 0, btnY, 34, 14);
			boolean hPlay = interactive && Ui.in((int) lmx, (int) lmy, 36, btnY, 38, 14);
			boolean hNext = interactive && Ui.in((int) lmx, (int) lmy, 76, btnY, 36, 14);
			Ui.iconPrev(g, 6, by, Ui.alpha(hPrev ? accent : Ui.TEXT, a));
			if (playing) Ui.iconPause(g, 46, by, Ui.alpha(hPlay ? accent : Ui.TEXT, a));
			else Ui.iconPlay(g, 46, by, Ui.alpha(hPlay ? accent : Ui.TEXT, a));
			Ui.iconNext(g, 84, by, Ui.alpha(hNext ? accent : Ui.TEXT, a));
		}
		if (l.vol()) {
			int vx = l.volLocalX();
			Ui.rect(g, vx, 0, VOL_W, CH, Ui.alpha(0x90000000, a));
			float v = mm.volume();
			int fillH = Math.round((CH - 2) * v);
			Ui.rect(g, vx + 1, 1 + (CH - 2 - fillH), VOL_W - 2, fillH, Ui.alpha(accent, a));
			int knobY = 1 + (CH - 2 - fillH) - 1;
			Ui.rect(g, vx - 1, Math.max(0, Math.min(CH - 3, knobY)), VOL_W + 2, 3, Ui.alpha(0xFFFFFFFF, a));
		}
		pose.popMatrix();

		// Text, positioned in screen space so the clipping box stays correct.
		if (l.info()) {
			int tx = l.cardX();
			String t = has ? title : Ui.tr(mm.isLocal() ? "nothing_playing" : "system.waiting");
			Ui.marqueeScaled(g, font, t, tx, l.cardY() + Math.round(infoTop * cs), INFO_W, cs, Ui.alpha(Ui.TEXT, a), true);
			if (has) {
				Ui.marqueeScaled(g, font, artist, tx, l.cardY() + Math.round((infoTop + 11) * cs), INFO_W, cs, Ui.alpha(0xFFC0C0CA, a), false);
				Ui.marqueeScaled(g, font, album, tx, l.cardY() + Math.round((infoTop + 22) * cs), INFO_W, cs, Ui.alpha(Ui.DARK_DIM, a), false);
			}
		}
		if (l.vol() && interactive && (draggingVolume || Ui.in((int) lmx, (int) lmy, l.volLocalX() - 2, 0, VOL_W + 4, CH))) {
			String pct = Math.round(mm.volume() * 100) + "%";
			int sx = l.cardX() + Math.round(l.volLocalX() * cs);
			int tx = sx - 6 - font.width(pct);
			if (tx < 2) tx = sx + Math.round(VOL_W * cs) + 4;
			Ui.textShadow(g, font, pct, tx, l.cardY() + Math.round(CH * cs / 2f) - 4, Ui.TEXT);
		}
	}

	private static int[] menuRect() {
		int h = MENU_KEYS.length * MENU_ROW + 6;
		int x = Math.max(2, Math.min(menuX, lastSw - MENU_W - 2));
		int y = Math.max(2, Math.min(menuY, lastSh - h - 2));
		return new int[]{x, y, MENU_W, h};
	}

	private static String menuLabel(int i, VinylConfig cfg) {
		return switch (i) {
			case 0, 1 -> Ui.tr(MENU_KEYS[i]) + " (" + Math.round(cfg.scale * 100) + "%)";
			case 2, 3 -> Ui.tr(MENU_KEYS[i]) + " (" + Math.round(cfg.cardScale * 100) + "%)";
			default -> Ui.tr(MENU_KEYS[i]);
		};
	}

	private static void drawMenu(GuiGraphicsExtractor g, Font font, Screen screen, int mx, int my) {
		if (!menuOpen) return;
		VinylConfig cfg = VinylConfig.get();
		lastSw = screen.width;
		lastSh = screen.height;
		int[] r = menuRect();
		Ui.rect(g, r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2, Ui.BORDER);
		Ui.rect(g, r[0], r[1], r[2], r[3], 0xF0202029);
		for (int i = 0; i < MENU_KEYS.length; i++) {
			int iy = r[1] + 3 + i * MENU_ROW;
			boolean hv = Ui.in(mx, my, r[0] + 2, iy, r[2] - 4, MENU_ROW);
			if (hv) Ui.rect(g, r[0] + 2, iy, r[2] - 4, MENU_ROW, Ui.BTN_HOVER);
			Ui.text(g, font, Ui.fit(font, menuLabel(i, cfg), r[2] - 12), r[0] + 6, iy + 3, Ui.TEXT);
		}
	}

	private static int menuItemAt(int mx, int my) {
		int[] r = menuRect();
		for (int i = 0; i < MENU_KEYS.length; i++) {
			if (Ui.in(mx, my, r[0] + 2, r[1] + 3 + i * MENU_ROW, r[2] - 4, MENU_ROW)) return i;
		}
		return -1;
	}

	private static float step(float v, float delta) {
		return Math.max(0.5f, Math.min(2.0f, Math.round((v + delta) * 10f) / 10f));
	}

	private static void applyMenu(int item) {
		VinylConfig cfg = VinylConfig.get();
		switch (item) {
			case 0 -> cfg.scale = step(cfg.scale, 0.1f);
			case 1 -> cfg.scale = step(cfg.scale, -0.1f);
			case 2 -> cfg.cardScale = step(cfg.cardScale, 0.1f);
			case 3 -> cfg.cardScale = step(cfg.cardScale, -0.1f);
			case 4 -> {
				cfg.scale = 1.0f;
				cfg.cardScale = 1.0f;
			}
			case 5 -> {
				// Hide the disc on this kind of screen. Settings has the switch to bring it back.
				if (lastScreen instanceof TitleScreen) cfg.showOnTitleScreen = false;
				else cfg.showOnPauseMenu = false;
				menuOpen = false;
			}
			default -> {
				pendingParent = lastScreen;
				menuOpen = false;
			}
		}
		VinylConfig.markDirty();
	}

	// ------------------------------------------------------------------ input

	/** Receives presses and releases from InputHooks. */
	public static final InputHooks.Listener LISTENER = new InputHooks.Listener() {
		@Override
		public boolean press(int button, double x, double y) {
			boolean used = onPress(button, x, y);
			pressesHandled++;
			if (logged < 12) {
				logged++;
				Diagnostics.LOG.info("[VinylMusicPlayer] Disc press: button {}, used {}, interactive {}", button, used, lastInteractive);
			}
			return used;
		}

		@Override
		public void release(int button) {
			if (button == 0) {
				leftHeld = false;
				draggingVolume = false;
			} else if (button == 1) {
				rightHeld = false;
				if (draggingMove && !moved) {
					menuOpen = true;
					menuX = lastMx;
					menuY = lastMy;
				}
				draggingMove = false;
			}
		}
	};

	private static boolean onPress(int button, double px, double py) {
		Layout l = lastLayout;
		if (!lastInteractive || lastScreen == null || l == null) {
			lastResult = "ignored: nothing drawn yet";
			return false;
		}
		int mx = Double.isNaN(px) ? lastMx : (int) Math.floor(px);
		int my = Double.isNaN(py) ? lastMy : (int) Math.floor(py);
		MusicManager mm = MusicManager.get();
		if (button == 0) {
			if (menuOpen) {
				int item = menuItemAt(mx, my);
				int[] r = menuRect();
				if (item >= 0) {
					applyMenu(item);
					lastResult = "menu item " + item;
					return true;
				}
				menuOpen = false;
				if (Ui.in(mx, my, r[0], r[1], r[2], r[3])) return true;
			}
			leftHeld = true;
			int cx = l.discX() + l.d() / 2;
			int cy = l.discY() + l.d() / 2;
			double dx = mx - cx;
			double dy = my - cy;
			if (l.hasCard()) {
				int lx = (int) Math.floor(l.lx(mx));
				int ly = (int) Math.floor(l.ly(my));
				int btnY = CH / 2 - 25 + 36;
				if (l.vol() && Ui.in(lx, ly, l.volLocalX() - 2, 0, VOL_W + 4, CH)) {
					draggingVolume = true;
					mm.setVolume(1f - ly / (float) CH);
					lastResult = "volume bar";
					return true;
				}
				if (l.info() && Ui.in(lx, ly, 0, btnY, 34, 14)) {
					mm.previous();
					lastResult = "previous";
					return true;
				}
				if (l.info() && Ui.in(lx, ly, 36, btnY, 38, 14)) {
					mm.togglePlayPause();
					lastResult = "play or pause";
					return true;
				}
				if (l.info() && Ui.in(lx, ly, 76, btnY, 36, 14)) {
					mm.next();
					lastResult = "next";
					return true;
				}
			}
			if (dx * dx + dy * dy <= (l.d() / 2.0) * (l.d() / 2.0)) {
				pendingParent = lastScreen;
				lastResult = "disc, opening the screen";
				return true;
			}
			lastResult = "left press missed the disc and card";
			return false;
		}
		if (button == 1) {
			rightHeld = true;
			if (Ui.in(mx, my, l.x(), l.y(), l.totalW(), l.totalH())) {
				menuOpen = false;
				draggingMove = true;
				moved = false;
				pressX = mx;
				pressY = my;
				grabX = mx - l.x();
				grabY = my - l.y();
				lastResult = "right press on the disc";
				return true;
			}
			menuOpen = false;
		}
		return false;
	}

	/** Runs every frame. Follows the mouse while a button is held on the volume bar or the disc. */
	private static void applyDrags(Screen screen, VinylConfig.Placement p, Layout l, int mx, int my) {
		if (draggingVolume && leftHeld) {
			MusicManager.get().setVolume(1f - l.ly(my) / (float) CH);
		}
		if (draggingMove && rightHeld) {
			if (!moved && (Math.abs(mx - pressX) >= 4 || Math.abs(my - pressY) >= 4)) moved = true;
			if (!moved) return;
			int nx = Math.max(0, Math.min(mx - grabX, Math.max(0, screen.width - l.totalW())));
			int ny = Math.max(0, Math.min(my - grabY, Math.max(0, screen.height - l.totalH())));
			int ccx = nx + l.totalW() / 2;
			int ccy = ny + l.totalH() / 2;
			int col = ccx < screen.width / 3 ? 0 : (ccx > screen.width * 2 / 3 ? 2 : 1);
			int row = ccy < screen.height / 3 ? 0 : (ccy > screen.height * 2 / 3 ? 2 : 1);
			p.anchor = row * 3 + col;
			p.offX = nx - baseX(col, screen.width, l.totalW());
			p.offY = ny - baseY(row, screen.height, l.totalH());
			VinylConfig.markDirty();
		}
	}
}
