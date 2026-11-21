package com.vinylmusic.gui;

import com.vinylmusic.audio.CoverArt;
import com.vinylmusic.audio.Track;
import com.vinylmusic.config.VinylConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the spinning disc with plain rectangles inside one rotated and scaled matrix.
 * The body (grooves and shine) is built once per color and style. Only the label changes per song.
 */
public final class DiscRenderer {
	private DiscRenderer() {
	}

	private static final int R = 32;
	private static final int LABEL_R = 15;

	private static List<int[]> body;
	private static long bodyKey = Long.MIN_VALUE;

	private static float angle;
	private static float speed;
	private static long lastNs;

	/** Call once per rendered frame. */
	public static void advance(boolean playing) {
		long now = System.nanoTime();
		float dt = lastNs == 0 ? 0f : (now - lastNs) / 1_000_000_000f;
		lastNs = now;
		dt = Math.min(dt, 0.1f);
		float target = playing ? 2.0f * VinylConfig.get().spinSpeed : 0f;
		speed += (target - speed) * Math.min(1f, dt * 2.5f);
		angle += speed * dt;
		if (angle > Math.PI * 2) angle -= (float) (Math.PI * 2);
	}

	public static void draw(GuiGraphicsExtractor g, int cx, int cy, int diameter, int[] cover, String seed) {
		draw(g, cx, cy, diameter, cover, seed, 1f);
	}

	public static void draw(GuiGraphicsExtractor g, int cx, int cy, int diameter, int[] cover, String seed, float alpha) {
		VinylConfig cfg = VinylConfig.get();
		if (DiscTexture.draw(g, cx, cy, diameter, cover, seed, angle)) return;
		List<int[]> rects = bodyFor(cfg);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate((float) cx, (float) cy);
		pose.rotate(angle);
		float s = diameter / (float) (R * 2);
		pose.scale(s, s);

		for (int[] r : rects) {
			g.fill(r[0], r[1], r[2], r[3], Ui.alpha(r[4], alpha));
		}
		if (cover != null && cover.length == Track.COVER_SIZE * Track.COVER_SIZE) {
			drawCoverLabel(g, coverGrid(cover), alpha);
		} else {
			drawDefaultLabel(g, seed == null ? "" : seed, cfg, alpha);
		}
		// Spindle hole.
		disc(g, 4, Ui.alpha(0xFFC8C8D0, alpha));
		disc(g, 2, Ui.alpha(0xFF050505, alpha));
		pose.popMatrix();
	}

	private static int[] gridSource;
	private static int[] gridCache;

	/** 20 by 20 version of a cover for the rectangle disc. Cached for the current cover. */
	static int[] coverGrid(int[] cover) {
		if (cover != gridSource) {
			gridSource = cover;
			gridCache = CoverArt.resample(cover, Track.COVER_SIZE, 20);
		}
		return gridCache;
	}

	private static void drawCoverLabel(GuiGraphicsExtractor g, int[] cover, float alpha) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.scale(1.5f, 1.5f);
		int n = 20;
		for (int y = 0; y < n; y++) {
			int runStart = -1;
			int runColor = 0;
			for (int x = 0; x <= n; x++) {
				boolean inside = false;
				int color = 0;
				if (x < n) {
					float dx = x + 0.5f - 10f;
					float dy = y + 0.5f - 10f;
					inside = dx * dx + dy * dy <= 100f;
					color = cover[y * n + x];
				}
				if (inside && runStart >= 0 && color == runColor) {
					continue;
				}
				if (runStart >= 0) {
					g.fill(runStart - 10, y - 10, x - 10, y - 9, Ui.alpha(runColor, alpha));
					runStart = -1;
				}
				if (inside) {
					runStart = x;
					runColor = color;
				}
			}
		}
		pose.popMatrix();
	}

	private static void drawDefaultLabel(GuiGraphicsExtractor g, String seed, VinylConfig cfg, float alpha) {
		int base;
		int light;
		if (cfg.labelColor >= 0) {
			base = 0xFF000000 | cfg.labelColor;
			light = Ui.mix(base, 0xFFFFFFFF, 0.35f);
		} else {
			float hue = (seed.hashCode() & 0xFFFF) / 65535f;
			base = java.awt.Color.HSBtoRGB(hue, 0.55f, 0.82f) | 0xFF000000;
			light = java.awt.Color.HSBtoRGB(hue, 0.35f, 0.95f) | 0xFF000000;
		}
		disc(g, LABEL_R, Ui.alpha(base, alpha));
		disc(g, 10, Ui.alpha(light, alpha));
		disc(g, 7, Ui.alpha(base, alpha));
	}

	private static void disc(GuiGraphicsExtractor g, int r, int color) {
		for (int y = -r; y < r; y++) {
			float yy = y + 0.5f;
			int hw = Math.round((float) Math.sqrt(r * r - yy * yy));
			if (hw > 0) g.fill(-hw, y, hw, y + 1, color);
		}
	}

	// ---------------------------------------------------------------- pre-built body

	private static List<int[]> bodyFor(VinylConfig cfg) {
		long key = ((long) cfg.vinylColor << 8) | (cfg.discStyle << 1) | (cfg.shine ? 1 : 0);
		if (body == null || key != bodyKey) {
			body = buildBody(0xFF000000 | cfg.vinylColor, cfg.discStyle, cfg.shine);
			bodyKey = key;
		}
		return body;
	}

	private static List<int[]> buildBody(int base, int style, boolean shine) {
		boolean cd = style == 1;
		int rim = Ui.mix(base, 0xFFFFFFFF, cd ? 0.85f : 0.16f);
		int face = cd ? Ui.mix(base, 0xFFFFFFFF, 0.62f) : base;
		int ring = cd ? 0x1E000000 : 0x2CFFFFFF;
		List<int[]> out = new ArrayList<>();
		addDisc(out, R, rim);
		addDisc(out, R - 1, face);
		for (int r = 29; r >= 17; r -= 3) {
			addRing(out, r, ring);
		}
		if (!shine) return out;
		// Two opposite shine wedges so the rotation is visible.
		int strong = cd ? 0x70FFFFFF : 0x44FFFFFF;
		int soft = cd ? 0x38FFFFFF : 0x1EFFFFFF;
		double a0 = -Math.PI / 4;
		for (int y = -R; y < R; y++) {
			int runStart = -1;
			int runColor = 0;
			for (int x = -R; x <= R; x++) {
				int color = 0;
				if (x < R) {
					double cx = x + 0.5;
					double cy = y + 0.5;
					double r = Math.hypot(cx, cy);
					if (r >= 17.5 && r <= 30.5) {
						double ang = Math.atan2(cy, cx);
						double d = Math.min(angDiff(ang, a0), angDiff(ang, a0 + Math.PI));
						if (d < 0.12) color = strong;
						else if (d < 0.32) color = soft;
					}
				}
				if (color != 0 && runStart >= 0 && color == runColor) continue;
				if (runStart >= 0) {
					out.add(new int[]{runStart, y, x, y + 1, runColor});
					runStart = -1;
				}
				if (color != 0) {
					runStart = x;
					runColor = color;
				}
			}
		}
		return out;
	}

	private static double angDiff(double a, double b) {
		double d = Math.abs(a - b) % (Math.PI * 2);
		return d > Math.PI ? Math.PI * 2 - d : d;
	}

	private static void addDisc(List<int[]> out, int r, int color) {
		for (int y = -r; y < r; y++) {
			double yy = y + 0.5;
			int hw = (int) Math.round(Math.sqrt(r * r - yy * yy));
			if (hw > 0) out.add(new int[]{-hw, y, hw, y + 1, color});
		}
	}

	private static void addRing(List<int[]> out, int r, int color) {
		for (int y = -r; y < r; y++) {
			double yy = y + 0.5;
			double ho = Math.sqrt(r * r - yy * yy);
			double inner = (r - 1) * (r - 1) - yy * yy;
			int hwo = (int) Math.round(ho);
			if (inner > 0) {
				int hwi = (int) Math.round(Math.sqrt(inner));
				if (hwi < hwo) {
					out.add(new int[]{-hwo, y, -hwi, y + 1, color});
					out.add(new int[]{hwi, y, hwo, y + 1, color});
				}
			} else if (hwo > 0) {
				out.add(new int[]{-hwo, y, hwo, y + 1, color});
			}
		}
	}
}
