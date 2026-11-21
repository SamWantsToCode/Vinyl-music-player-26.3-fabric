package com.vinylmusic.gui;

import com.vinylmusic.client.Diagnostics;
import com.vinylmusic.config.VinylConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Smooth vinyl disc drawn as one rotated texture, created once per size and look.
 * The texture is painted at the screen's real pixel size with anti-aliased edges.
 * Minecraft's texture classes are reached by name, so a renamed method cannot break the build.
 * If anything fails, DiscRenderer falls back to the rectangle disc and Settings shows why.
 */
final class DiscTexture {
	private DiscTexture() {
	}

	private static final class Entry {
		Object id;
		Object texture;
		Object image;
		int size;
		long key = Long.MIN_VALUE;
		long builtAtMs;
	}

	private static final Map<Integer, Entry> ENTRIES = new HashMap<>();
	private static boolean initDone;
	private static boolean failed;
	public static volatile String status = "not used yet";

	private static Constructor<?> imageCtor;
	private static MethodHandle setPixel;
	private static boolean pixelIsAbgr;
	private static Constructor<?> textureCtor;
	private static int textureCtorKind;
	private static MethodHandle upload;
	private static Method register;
	private static Method idFactory;
	private static MethodHandle blit;
	private static Object pipeline;
	private static Method getTextureManager;

	static boolean isFailed() {
		return failed;
	}

	/** Draws the disc. Returns false when the caller should use the rectangle disc instead. */
	static boolean draw(GuiGraphicsExtractor g, int cx, int cy, int diameter, int[] cover, String seed, float angle) {
		if (failed) return false;
		try {
			if (!initDone) init();
			VinylConfig cfg = VinylConfig.get();
			float gs = guiScale();
			int n = Math.max(24, Math.min(512, Math.round(diameter * gs)));
			if ((n & 1) == 1) n++;
			Entry e = ENTRIES.computeIfAbsent(n, k -> new Entry());
			long key = contentKey(n, cfg, cover, seed);
			long now = System.currentTimeMillis();
			if (e.key != key && (e.texture == null || now - e.builtAtMs > 120)) {
				build(e, n, cfg, cover, seed);
				e.key = key;
				e.builtAtMs = now;
			}
			if (e.texture == null) return false;
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate((float) cx, (float) cy);
			pose.rotate(angle);
			pose.scale(1f / gs, 1f / gs);
			blit.invoke(g, pipeline, e.id, -n / 2, -n / 2, 0f, 0f, n, n, n, n);
			pose.popMatrix();
			status = "texture (" + n + " px)";
			return true;
		} catch (Throwable t) {
			failed = true;
			status = "rectangles (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
			Diagnostics.LOG.warn("[VinylMusicPlayer] Smooth disc texture unavailable, using rectangles: {}", t.toString());
			return false;
		}
	}

	private static long contentKey(int n, VinylConfig cfg, int[] cover, String seed) {
		long k = n;
		k = k * 31 + cfg.vinylColor;
		k = k * 31 + cfg.discStyle;
		k = k * 31 + (cfg.shine ? 1 : 0);
		k = k * 31 + cfg.labelColor;
		k = k * 31 + Math.round(cfg.opacity * 100);
		k = k * 31 + (cover != null ? System.identityHashCode(cover) : (cfg.labelColor < 0 && seed != null ? seed.hashCode() : 7));
		return k;
	}

	private static float guiScale() {
		try {
			Object w = Minecraft.getInstance().getWindow();
			try {
				return Math.max(1f, ((Number) w.getClass().getMethod("getGuiScale").invoke(w)).floatValue());
			} catch (Throwable ignored) {
				int fb = ((Number) w.getClass().getMethod("getWidth").invoke(w)).intValue();
				int gw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
				return Math.max(1f, fb / (float) gw);
			}
		} catch (Throwable t) {
			return 2f;
		}
	}

	// ------------------------------------------------------------------ setup

	private static void init() throws Exception {
		initDone = true;
		MethodHandles.Lookup lookup = MethodHandles.publicLookup();
		Class<?> nativeImage = Class.forName("com.mojang.blaze3d.platform.NativeImage");
		Class<?> dynamic = Class.forName("net.minecraft.client.renderer.texture.DynamicTexture");
		Class<?> abstractTexture = Class.forName("net.minecraft.client.renderer.texture.AbstractTexture");
		Class<?> idClass = Class.forName("net.minecraft.resources.Identifier");
		Class<?> pipelines = Class.forName("net.minecraft.client.renderer.RenderPipelines");

		pipeline = pipelines.getField("GUI_TEXTURED").get(null);
		idFactory = idClass.getMethod("fromNamespaceAndPath", String.class, String.class);

		imageCtor = nativeImage.getConstructor(int.class, int.class, boolean.class);
		Method px = null;
		for (Method m : nativeImage.getMethods()) {
			if (m.getName().equals("setPixel") && m.getParameterCount() == 3 && m.getParameterTypes()[2] == int.class) px = m;
		}
		if (px == null) {
			for (Method m : nativeImage.getMethods()) {
				if (m.getName().equals("setPixelABGR") && m.getParameterCount() == 3) {
					px = m;
					pixelIsAbgr = true;
				}
			}
		}
		if (px == null) throw new NoSuchMethodException("NativeImage.setPixel");
		setPixel = lookup.unreflect(px);

		for (Constructor<?> c : dynamic.getConstructors()) {
			Class<?>[] p = c.getParameterTypes();
			if (p.length == 2 && Supplier.class.isAssignableFrom(p[0]) && p[1] == nativeImage) {
				textureCtor = c;
				textureCtorKind = 1;
			} else if (p.length == 2 && p[0] == String.class && p[1] == nativeImage && textureCtor == null) {
				textureCtor = c;
				textureCtorKind = 2;
			} else if (p.length == 1 && p[0] == nativeImage && textureCtor == null) {
				textureCtor = c;
				textureCtorKind = 3;
			}
		}
		if (textureCtor == null) throw new NoSuchMethodException("DynamicTexture constructor");
		upload = lookup.unreflect(dynamic.getMethod("upload"));

		getTextureManager = Minecraft.class.getMethod("getTextureManager");
		Class<?> tm = getTextureManager.getReturnType();
		for (Method m : tm.getMethods()) {
			if (m.getName().equals("register") && m.getParameterCount() == 2 && m.getParameterTypes()[0] == idClass
					&& m.getParameterTypes()[1].isAssignableFrom(dynamic)) register = m;
		}
		if (register == null) throw new NoSuchMethodException("TextureManager.register");

		Method b = null;
		for (Method m : GuiGraphicsExtractor.class.getMethods()) {
			Class<?>[] p = m.getParameterTypes();
			if (m.getName().equals("blit") && p.length == 10 && p[1] == idClass && p[2] == int.class && p[3] == int.class
					&& p[4] == float.class && p[5] == float.class && p[6] == int.class && p[7] == int.class
					&& p[8] == int.class && p[9] == int.class) b = m;
		}
		if (b == null) throw new NoSuchMethodException("GuiGraphicsExtractor.blit(pipeline, id, x, y, u, v, w, h, texW, texH)");
		blit = lookup.unreflect(b);
		// Leave the argument types loose so invoke() can adapt Integer and Float values.
	}

	private static void build(Entry e, int n, VinylConfig cfg, int[] cover, String seed) throws Throwable {
		int[] argb = paint(n, cfg, cover, seed);
		if (e.texture == null || e.size != n) {
			Object image = imageCtor.newInstance(n, n, false);
			Object id = idFactory.invoke(null, "vinylmusicplayer", "dynamic/disc_" + n);
			Object texture = switch (textureCtorKind) {
				case 1 -> textureCtor.newInstance((Supplier<String>) () -> "needledrop disc " + n, image);
				case 2 -> textureCtor.newInstance("needledrop disc " + n, image);
				default -> textureCtor.newInstance(image);
			};
			Object tm = getTextureManager.invoke(Minecraft.getInstance());
			register.invoke(tm, id, texture);
			e.image = image;
			e.texture = texture;
			e.id = id;
			e.size = n;
		}
		Object image = e.image;
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				int c = argb[y * n + x];
				if (pixelIsAbgr) c = (c & 0xFF00FF00) | ((c & 0xFF) << 16) | ((c >> 16) & 0xFF);
				setPixel.invoke(image, x, y, c);
			}
		}
		upload.invoke(e.texture);
	}

	// ------------------------------------------------------------------ painting

	/** Paints the disc into an n by n ARGB array, with 2 by 2 supersampling for smooth edges. */
	private static int[] paint(int n, VinylConfig cfg, int[] cover, String seed) {
		int[] out = new int[n * n];
		boolean cd = cfg.discStyle == 1;
		int baseRgb = cfg.vinylColor & 0xFFFFFF;
		int base = 0xFF000000 | baseRgb;
		int face = cd ? Ui.mix(base, 0xFFFFFFFF, 0.62f) : base;
		int rim = Ui.mix(base, 0xFFFFFFFF, cd ? 0.85f : 0.16f);
		int border = Ui.mix(face, 0xFF000000, 0.45f);
		int labelA;
		int labelB;
		if (cfg.labelColor >= 0) {
			labelA = 0xFF000000 | cfg.labelColor;
			labelB = Ui.mix(labelA, 0xFFFFFFFF, 0.35f);
		} else {
			float hue = ((seed == null ? "" : seed).hashCode() & 0xFFFF) / 65535f;
			labelA = java.awt.Color.HSBtoRGB(hue, 0.55f, 0.82f) | 0xFF000000;
			labelB = java.awt.Color.HSBtoRGB(hue, 0.35f, 0.95f) | 0xFF000000;
		}
		float alphaMul = cfg.opacity;
		boolean shine = cfg.shine;
		double half = n / 2.0;
		double radius = half - 0.75;
		int cs = Track64.SIZE;

		for (int py = 0; py < n; py++) {
			for (int px = 0; px < n; px++) {
				double sa = 0, sr = 0, sg = 0, sb = 0;
				for (int sy = 0; sy < 2; sy++) {
					for (int sx = 0; sx < 2; sx++) {
						double dx = (px + 0.25 + sx * 0.5 - half) / radius;
						double dy = (py + 0.25 + sy * 0.5 - half) / radius;
						double r = Math.sqrt(dx * dx + dy * dy);
						if (r > 1.0) continue;
						int c = shade(dx, dy, r, cd, face, rim, border, labelA, labelB, cover, cs, shine);
						double a = (c >>> 24) / 255.0;
						sa += a;
						sr += a * ((c >> 16) & 0xFF);
						sg += a * ((c >> 8) & 0xFF);
						sb += a * (c & 0xFF);
					}
				}
				if (sa <= 0) {
					out[py * n + px] = 0;
					continue;
				}
				int a = (int) Math.round(255.0 * (sa / 4.0) * alphaMul);
				int rr = (int) Math.round(sr / sa);
				int gg = (int) Math.round(sg / sa);
				int bb = (int) Math.round(sb / sa);
				out[py * n + px] = (a << 24) | (rr << 16) | (gg << 8) | bb;
			}
		}
		return out;
	}

	/** Cover size, kept in one place. */
	private static final class Track64 {
		static final int SIZE = com.vinylmusic.audio.Track.COVER_SIZE;
	}

	private static int shade(double dx, double dy, double r, boolean cd, int face, int rim, int border,
							int labelA, int labelB, int[] cover, int cs, boolean shine) {
		final double label = 0.47;
		if (r < 0.055) return 0; // spindle hole, see-through
		if (r < 0.085) return 0xFFD0D0D8;
		if (r < label) {
			if (cover != null && cover.length == cs * cs) return sampleCover(cover, cs, dx / label, dy / label);
			if (r < 0.22) return labelA;
			if (r < 0.31) return labelB;
			if (r < 0.40) return labelA;
			return Ui.mix(labelA, labelB, 0.5f);
		}
		if (r < label + 0.03) return border;
		if (r > 0.96) return Ui.mix(rim, face, (float) ((r - 0.96) / 0.04) * 0.3f);
		int c = face;
		if (!cd) {
			double groove = 0.5 + 0.5 * Math.sin(r * 260.0);
			c = Ui.mix(c, 0xFFFFFFFF, (float) (0.025 * groove));
			for (double gap : new double[]{0.62, 0.74, 0.86}) {
				if (Math.abs(r - gap) < 0.006) c = Ui.mix(c, 0xFF000000, 0.35f);
			}
		} else {
			for (double ring : new double[]{0.6, 0.72, 0.84}) {
				if (Math.abs(r - ring) < 0.004) c = Ui.mix(c, 0xFF000000, 0.12f);
			}
		}
		if (shine) {
			double ang = Math.atan2(dy, dx);
			double d1 = angDiff(ang, -Math.PI / 4);
			double d2 = angDiff(ang, 3 * Math.PI / 4);
			double d = Math.min(d1, d2);
			if (d < 0.5) {
				double t = 1.0 - d / 0.5;
				double radial = Math.sin(Math.PI * Math.min(1.0, Math.max(0.0, (r - 0.5) / 0.46)));
				double strength = t * t * (0.10 + 0.22 * radial) * (cd ? 2.0 : 1.0);
				c = Ui.mix(c, 0xFFFFFFFF, (float) Math.min(0.85, strength));
			}
		}
		return c;
	}

	private static double angDiff(double a, double b) {
		double d = Math.abs(a - b) % (Math.PI * 2);
		return d > Math.PI ? Math.PI * 2 - d : d;
	}

	/** Bilinear sample of the cover. u and v run from -1 to 1 across the label. */
	private static int sampleCover(int[] cover, int cs, double u, double v) {
		double fx = (u * 0.5 + 0.5) * cs - 0.5;
		double fy = (v * 0.5 + 0.5) * cs - 0.5;
		int x0 = (int) Math.floor(fx);
		int y0 = (int) Math.floor(fy);
		double tx = fx - x0;
		double ty = fy - y0;
		double r = 0, g = 0, b = 0;
		for (int j = 0; j < 2; j++) {
			for (int i = 0; i < 2; i++) {
				int x = Math.max(0, Math.min(cs - 1, x0 + i));
				int y = Math.max(0, Math.min(cs - 1, y0 + j));
				double w = (i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - ty : ty);
				int c = cover[y * cs + x];
				r += w * ((c >> 16) & 0xFF);
				g += w * ((c >> 8) & 0xFF);
				b += w * (c & 0xFF);
			}
		}
		return 0xFF000000 | ((int) Math.round(r) << 16) | ((int) Math.round(g) << 8) | (int) Math.round(b);
	}
}
