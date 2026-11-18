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
 * The disc widget: spinning disc, song info, transport buttons and a vertical volume bar.
 * Left click opens the full screen. Dragging with the right mouse button moves it.
 * Clicks are read by polling the mouse buttons once per frame.
 */
public final class Overlay {
	private Overlay() {
	}

	private static final int MARGIN = 6;
	private static final int GAP = 4;
	private static final int INFO_W = 112;
	private static final int VOL_W = 7;

	private static boolean leftHeld;
	private static boolean rightHeld;
	private static boolean draggingVolume;
	private static boolean draggingMove;
	private static int grabX;
	private static int grabY;
	private static Screen pendingParent;

	// What was drawn last frame, so a click can be matched against it.
	private static Screen lastScreen;
	private static Layout lastLayout;
	private static VinylConfig.Placement lastPlacement;
	private static boolean lastInteractive;
	private static int lastMx;
	private static int lastMy;

	private record Layout(int x, int y, int d, int discX, int infoX, int infoY, int volX, int totalW, boolean info, boolean vol) {
		int btnY() {
			return infoY + 36;
		}
	}

	private static int baseX(int col, int sw, int totalW) {
		return col == 0 ? MARGIN : (col == 1 ? (sw - totalW) / 2 : sw - MARGIN - totalW);
	}

	private static int baseY(int row, int sh, int d) {
		return row == 0 ? MARGIN : (row == 1 ? (sh - d) / 2 : sh - MARGIN - d);
	}

	private static Layout layout(int sw, int sh, VinylConfig cfg, VinylConfig.Placement p) {
		int d = Math.round(72 * cfg.scale);
		boolean info = cfg.showInfo;
		boolean vol = cfg.showVolumeBar;
		int totalW = d + (info ? GAP + INFO_W : 0) + (vol ? GAP + VOL_W : 0);
		int col = p.anchor % 3;
		int row = p.anchor / 3;
		int x = baseX(col, sw, totalW) + p.offX;
		int y = baseY(row, sh, d) + p.offY;
		x = Math.max(0, Math.min(x, Math.max(0, sw - totalW)));
		y = Math.max(0, Math.min(y, Math.max(0, sh - d)));
		boolean mirror = col == 2;
		int cursor = x;
		int discX;
		int infoX = 0;
		int volX = 0;
		if (!mirror) {
			discX = cursor;
			cursor += d;
			if (info) {
				cursor += GAP;
				infoX = cursor;
				cursor += INFO_W;
			}
			if (vol) {
				cursor += GAP;
				volX = cursor;
			}
		} else {
			if (vol) {
				volX = cursor;
				cursor += VOL_W + GAP;
			}
			if (info) {
				infoX = cursor;
				cursor += INFO_W + GAP;
			}
			discX = cursor;
		}
		return new Layout(x, y, d, discX, infoX, y + d / 2 - 25, volX, totalW, info, vol);
	}

	/** Called from the Fabric screen event for every screen that is drawn. */
	public static void onScreenRender(Screen screen, GuiGraphicsExtractor g, int mx, int my) {
		if (Diagnostics.hasError() || screen instanceof VinylScreen) return;
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
			Diagnostics.overlayDrawn = true;
			if (interactive) {
				lastScreen = screen;
				lastLayout = l;
				lastPlacement = p;
				lastInteractive = true;
				lastMx = mx;
				lastMy = my;
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
			mc.gui.setScreen(new VinylScreen(parent));
		}
	}

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

		int cx = l.discX() + l.d() / 2;
		int cy = l.y() + l.d() / 2;
		DiscRenderer.draw(g, cx, cy, l.d(), mm.cover(), title + artist, a);

		if (l.info()) {
			Ui.rect(g, l.infoX() - 3, l.infoY() - 3, INFO_W + 6, 56, Ui.alpha(0x90000000, a));
			String t = has ? title : Ui.tr(mm.isLocal() ? "nothing_playing" : "system.waiting");
			Ui.marquee(g, font, t, l.infoX(), l.infoY(), INFO_W, Ui.alpha(Ui.TEXT, a), true);
			if (has) {
				Ui.marquee(g, font, artist, l.infoX(), l.infoY() + 11, INFO_W, Ui.alpha(0xFFC0C0CA, a), false);
				Ui.marquee(g, font, album, l.infoX(), l.infoY() + 22, INFO_W, Ui.alpha(Ui.DARK_DIM, a), false);
			}
			long dur = mm.durationMs();
			if (mm.isLocal() && dur > 0) {
				Ui.rect(g, l.infoX(), l.infoY() + 33, INFO_W, 2, Ui.alpha(0xFF303038, a));
				int filled = (int) Math.min(INFO_W, INFO_W * mm.positionMs() / dur);
				Ui.rect(g, l.infoX(), l.infoY() + 33, filled, 2, Ui.alpha(accent, a));
			}
			int by = l.btnY() + 6;
			int prevX = l.infoX() + 6;
			int playX = l.infoX() + 46;
			int nextX = l.infoX() + 84;
			boolean hPrev = interactive && Ui.in(mx, my, l.infoX(), l.btnY(), 34, 14);
			boolean hPlay = interactive && Ui.in(mx, my, l.infoX() + 36, l.btnY(), 38, 14);
			boolean hNext = interactive && Ui.in(mx, my, l.infoX() + 76, l.btnY(), 36, 14);
			Ui.iconPrev(g, prevX, by, Ui.alpha(hPrev ? accent : Ui.TEXT, a));
			if (playing) Ui.iconPause(g, playX, by, Ui.alpha(hPlay ? accent : Ui.TEXT, a));
			else Ui.iconPlay(g, playX, by, Ui.alpha(hPlay ? accent : Ui.TEXT, a));
			Ui.iconNext(g, nextX, by, Ui.alpha(hNext ? accent : Ui.TEXT, a));
		}

		if (l.vol()) {
			Ui.rect(g, l.volX(), l.y(), VOL_W, l.d(), Ui.alpha(0x90000000, a));
			float v = mm.volume();
			int fillH = Math.round((l.d() - 2) * v);
			Ui.rect(g, l.volX() + 1, l.y() + 1 + (l.d() - 2 - fillH), VOL_W - 2, fillH, Ui.alpha(accent, a));
			int knobY = l.y() + 1 + (l.d() - 2 - fillH) - 1;
			Ui.rect(g, l.volX() - 1, Math.max(l.y(), Math.min(l.y() + l.d() - 3, knobY)), VOL_W + 2, 3, Ui.alpha(0xFFFFFFFF, a));
			if (interactive && (draggingVolume || Ui.in(mx, my, l.volX() - 2, l.y(), VOL_W + 4, l.d()))) {
				String pct = Math.round(v * 100) + "%";
				int tx = l.volX() - 8 - font.width(pct);
				if (tx < 2) tx = l.volX() + VOL_W + 4;
				Ui.textShadow(g, font, pct, tx, l.y() + l.d() / 2 - 4, Ui.TEXT);
			}
		}
		if (interactive && !draggingMove && Ui.in(mx, my, l.x(), l.y(), l.totalW(), l.d())) {
			Ui.textShadow(g, font, Ui.tr("overlay_hint"), l.x(), Math.max(1, l.y() - 11), Ui.TEXT);
		}
	}

	/** Receives presses and releases from InputHooks. */
	public static final InputHooks.Listener LISTENER = new InputHooks.Listener() {
		@Override
		public boolean press(int button, double x, double y) {
			return onPress(button, x, y);
		}

		@Override
		public void release(int button) {
			if (button == 0) {
				leftHeld = false;
				draggingVolume = false;
			} else if (button == 1) {
				rightHeld = false;
				draggingMove = false;
			}
		}
	};

	private static boolean onPress(int button, double px, double py) {
		Layout l = lastLayout;
		if (!lastInteractive || lastScreen == null || l == null) return false;
		int mx = Double.isNaN(px) ? lastMx : (int) Math.floor(px);
		int my = Double.isNaN(py) ? lastMy : (int) Math.floor(py);
		MusicManager mm = MusicManager.get();
		if (button == 0) {
			leftHeld = true;
			int cx = l.discX() + l.d() / 2;
			int cy = l.y() + l.d() / 2;
			double dx = mx - cx;
			double dy = my - cy;
			if (l.vol() && Ui.in(mx, my, l.volX() - 2, l.y(), VOL_W + 4, l.d())) {
				draggingVolume = true;
				mm.setVolume(1f - (my - l.y()) / (float) l.d());
				return true;
			}
			if (dx * dx + dy * dy <= (l.d() / 2.0) * (l.d() / 2.0)) {
				pendingParent = lastScreen;
				return true;
			}
			if (l.info() && Ui.in(mx, my, l.infoX(), l.btnY(), 34, 14)) {
				mm.previous();
				return true;
			}
			if (l.info() && Ui.in(mx, my, l.infoX() + 36, l.btnY(), 38, 14)) {
				mm.togglePlayPause();
				return true;
			}
			if (l.info() && Ui.in(mx, my, l.infoX() + 76, l.btnY(), 36, 14)) {
				mm.next();
				return true;
			}
			return false;
		}
		if (button == 1) {
			rightHeld = true;
			if (Ui.in(mx, my, l.x(), l.y(), l.totalW(), l.d())) {
				draggingMove = true;
				grabX = mx - l.x();
				grabY = my - l.y();
				return true;
			}
		}
		return false;
	}

	/** Runs every frame. Follows the mouse while a button is held on the volume bar or the disc. */
	private static void applyDrags(Screen screen, VinylConfig.Placement p, Layout l, int mx, int my) {
		if (draggingVolume && leftHeld) {
			MusicManager.get().setVolume(1f - (my - l.y()) / (float) l.d());
		}
		if (draggingMove && rightHeld) {
			int nx = Math.max(0, Math.min(mx - grabX, Math.max(0, screen.width - l.totalW())));
			int ny = Math.max(0, Math.min(my - grabY, Math.max(0, screen.height - l.d())));
			int ccx = nx + l.totalW() / 2;
			int ccy = ny + l.d() / 2;
			int col = ccx < screen.width / 3 ? 0 : (ccx > screen.width * 2 / 3 ? 2 : 1);
			int row = ccy < screen.height / 3 ? 0 : (ccy > screen.height * 2 / 3 ? 2 : 1);
			p.anchor = row * 3 + col;
			p.offX = nx - baseX(col, screen.width, l.totalW());
			p.offY = ny - baseY(row, screen.height, l.d());
			VinylConfig.markDirty();
		}
	}
}
