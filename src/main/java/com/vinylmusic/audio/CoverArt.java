package com.vinylmusic.audio;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Decodes cover images and shrinks them to a tiny pixel grid that the GUI paints with plain rectangles. */
public final class CoverArt {
	private CoverArt() {
	}

	public static int[] fromBytes(byte[] data) {
		if (data == null || data.length < 16) return null;
		try {
			ImageIO.setUseCache(false);
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
			return fromImage(img);
		} catch (Throwable t) {
			return null;
		}
	}

	public static int[] fromFile(Path p) {
		try {
			if (!Files.isRegularFile(p) || Files.size(p) > 20_000_000L) return null;
			return fromBytes(Files.readAllBytes(p));
		} catch (Throwable t) {
			return null;
		}
	}

	public static int[] fromImage(BufferedImage img) {
		if (img == null) return null;
		int n = Track.COVER_SIZE;
		int w = img.getWidth();
		int h = img.getHeight();
		int side = Math.min(w, h);
		int ox = (w - side) / 2;
		int oy = (h - side) / 2;
		int[] out = new int[n * n];
		for (int y = 0; y < n; y++) {
			int y0 = oy + y * side / n;
			int y1 = Math.max(y0 + 1, oy + (y + 1) * side / n);
			for (int x = 0; x < n; x++) {
				int x0 = ox + x * side / n;
				int x1 = Math.max(x0 + 1, ox + (x + 1) * side / n);
				long r = 0, g = 0, b = 0;
				int count = 0;
				// Sample at most about 8x8 points per cell for speed on large images.
				int stepY = Math.max(1, (y1 - y0) / 8);
				int stepX = Math.max(1, (x1 - x0) / 8);
				for (int yy = y0; yy < y1; yy += stepY) {
					for (int xx = x0; xx < x1; xx += stepX) {
						int argb = img.getRGB(xx, yy);
						int a = argb >>> 24;
						if (a < 8) continue;
						r += (argb >> 16) & 0xFF;
						g += (argb >> 8) & 0xFF;
						b += argb & 0xFF;
						count++;
					}
				}
				if (count == 0) {
					out[y * n + x] = 0xFF202020;
				} else {
					out[y * n + x] = 0xFF000000 | ((int) (r / count) << 16) | ((int) (g / count) << 8) | (int) (b / count);
				}
			}
		}
		return out;
	}

	public static int[] resample(int[] src, int srcN, int dstN) {
		int[] out = new int[dstN * dstN];
		for (int y = 0; y < dstN; y++) {
			int y0 = y * srcN / dstN;
			int y1 = Math.max(y0 + 1, (y + 1) * srcN / dstN);
			for (int x = 0; x < dstN; x++) {
				int x0 = x * srcN / dstN;
				int x1 = Math.max(x0 + 1, (x + 1) * srcN / dstN);
				int r = 0, g = 0, b = 0, c = 0;
				for (int yy = y0; yy < y1; yy++) {
					for (int xx = x0; xx < x1; xx++) {
						int p = src[yy * srcN + xx];
						r += (p >> 16) & 0xFF;
						g += (p >> 8) & 0xFF;
						b += p & 0xFF;
						c++;
					}
				}
				out[y * dstN + x] = 0xFF000000 | ((r / c) << 16) | ((g / c) << 8) | (b / c);
			}
		}
		return out;
	}
}
