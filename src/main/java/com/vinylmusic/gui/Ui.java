package com.vinylmusic.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Small drawing helpers built only on fill() and text(). */
public final class Ui {
	private Ui() {
	}

	public static final int PANEL = 0xF0181820;
	public static final int BORDER = 0xFF34343F;
	public static final int BTN = 0xFF2A2A35;
	public static final int BTN_HOVER = 0xFF3B3B4A;
	public static final int DANGER = 0xFF7A2E2E;
	public static final int DANGER_HOVER = 0xFF9A3C3C;
	public static final int ACCENT = 0xFFE9A23B;
	public static final int TEXT = 0xFFFFFFFF;
	public static final int DIM = 0xFF9A9AA8;
	public static final int DARK_DIM = 0xFF6C6C7A;
	public static final int ROW_HOVER = 0x30FFFFFF;
	public static final int ROW_SELECT = 0x40E9A23B;

	public static String tr(String key) {
		return Component.translatable("vinylmusicplayer." + key).getString();
	}

	public static String tr(String key, Object... args) {
		return Component.translatable("vinylmusicplayer." + key, args).getString();
	}

	/** Multiplies the alpha channel of an ARGB color. */
	public static int alpha(int argb, float a) {
		if (a >= 0.999f) return argb;
		int al = Math.round((argb >>> 24) * Math.max(0f, Math.min(1f, a)));
		return (al << 24) | (argb & 0xFFFFFF);
	}

	/** Blends two opaque colors. t = 0 gives a, t = 1 gives b. */
	public static int mix(int a, int b, float t) {
		int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	public static boolean in(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	public static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + h, color);
	}

	public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	public static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
		g.text(font, s, x, y, color, false);
	}

	public static void textShadow(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
		g.text(font, s, x, y, color, true);
	}

	public static void centered(GuiGraphicsExtractor g, Font font, String s, int cx, int y, int color) {
		g.text(font, s, cx - font.width(s) / 2, y, color, false);
	}

	public static String fit(Font font, String s, int maxW) {
		if (font.width(s) <= maxW) return s;
		String dots = "...";
		int limit = maxW - font.width(dots);
		if (limit <= 0) return "";
		int end = s.length();
		while (end > 0 && font.width(s.substring(0, end)) > limit) end--;
		return s.substring(0, end) + dots;
	}

	public static void button(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String label,
							  boolean hover, boolean danger, boolean active) {
		int bg = danger ? (hover ? DANGER_HOVER : DANGER) : (hover ? BTN_HOVER : BTN);
		rect(g, x, y, w, h, bg);
		if (active) {
			rect(g, x, y + h - 2, w, 2, ACCENT);
		}
		centered(g, font, label, x + w / 2, y + (h - 8) / 2, TEXT);
	}

	public static void slider(GuiGraphicsExtractor g, int x, int y, int w, int h, float value, boolean hover) {
		int ty = y + h / 2 - 2;
		rect(g, x, ty, w, 4, 0xFF0E0E14);
		int fillW = Math.round(w * Math.max(0f, Math.min(1f, value)));
		rect(g, x, ty, fillW, 4, ACCENT);
		int hx = x + fillW - 2;
		hx = Math.max(x, Math.min(x + w - 4, hx));
		rect(g, hx, y + 1, 4, h - 2, hover ? 0xFFFFFFFF : 0xFFE0E0E0);
	}

	/** Draws text that scrolls sideways when it is wider than the box. */
	public static void marquee(GuiGraphicsExtractor g, Font font, String s, int x, int y, int w, int color, boolean shadow) {
		int tw = font.width(s);
		if (tw <= w) {
			g.text(font, s, x, y, color, shadow);
			return;
		}
		int gap = 24;
		long t = System.currentTimeMillis();
		int period = tw + gap;
		int off = (int) ((t / 30) % period);
		g.enableScissor(x, y - 1, x + w, y + 10);
		g.text(font, s, x - off, y, color, shadow);
		g.text(font, s, x - off + period, y, color, shadow);
		g.disableScissor();
	}

	public static String time(long ms) {
		if (ms <= 0) return "--:--";
		long s = ms / 1000;
		return String.format("%d:%02d", s / 60, s % 60);
	}

	// ---- Transport icons, each about 8 px wide and 7 px tall, centred on cy ----

	public static void iconPlay(GuiGraphicsExtractor g, int x, int cy, int color) {
		for (int c = 0; c < 4; c++) {
			int h = 7 - 2 * c;
			g.fill(x + 2 * c, cy - 3 + c, x + 2 * c + 2, cy - 3 + c + h, color);
		}
	}

	public static void iconPause(GuiGraphicsExtractor g, int x, int cy, int color) {
		g.fill(x + 1, cy - 3, x + 3, cy + 4, color);
		g.fill(x + 5, cy - 3, x + 7, cy + 4, color);
	}

	public static void iconNext(GuiGraphicsExtractor g, int x, int cy, int color) {
		for (int c = 0; c < 4; c++) {
			int h = 7 - 2 * c;
			g.fill(x + 2 * c, cy - 3 + c, x + 2 * c + 2, cy - 3 + c + h, color);
		}
		g.fill(x + 8, cy - 3, x + 10, cy + 4, color);
	}

	public static void iconPrev(GuiGraphicsExtractor g, int x, int cy, int color) {
		g.fill(x, cy - 3, x + 2, cy + 4, color);
		for (int c = 0; c < 4; c++) {
			int h = 1 + 2 * c;
			g.fill(x + 2 + 2 * c, cy - c, x + 4 + 2 * c, cy - c + h, color);
		}
	}
}
