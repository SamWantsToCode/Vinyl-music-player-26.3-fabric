package com.vinylmusic.audio;

import javax.sound.sampled.AudioSystem;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Reads title, artist, album, duration and cover art without any outside library. */
public final class MetadataReader {
	private MetadataReader() {
	}

	public static final String[] EXTENSIONS = {"mp3", "ogg", "wav"};

	public static boolean isSupported(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		for (String e : EXTENSIONS) {
			if (n.endsWith("." + e)) return true;
		}
		return false;
	}

	public static Track read(Path p) {
		Track t = new Track(p.toAbsolutePath().toString());
		try {
			switch (t.extension()) {
				case "mp3" -> readMp3(p, t);
				case "ogg" -> readOgg(p, t);
				case "wav" -> t.durationMs = WavSource.probeDurationMs(p);
				default -> {
				}
			}
		} catch (Throwable ignored) {
			// Fall through to file name fallbacks.
		}
		applyFileNameFallback(t);
		if (t.cover == null) {
			t.setCover(findSidecarCover(p));
		}
		return t;
	}

	private static void applyFileNameFallback(Track t) {
		String name = t.fileName();
		int dot = name.lastIndexOf('.');
		if (dot > 0) name = name.substring(0, dot);
		if (t.title == null || t.title.isBlank()) {
			int dash = name.indexOf(" - ");
			if (dash > 0 && (t.artist == null || t.artist.isBlank())) {
				t.artist = name.substring(0, dash).trim();
				t.title = name.substring(dash + 3).trim();
			} else {
				t.title = name.trim();
			}
		}
	}

	private static int[] findSidecarCover(Path audio) {
		Path dir = audio.toAbsolutePath().getParent();
		if (dir == null) return null;
		String base = audio.getFileName().toString();
		int dot = base.lastIndexOf('.');
		if (dot > 0) base = base.substring(0, dot);
		String[] names = {base, "cover", "folder", "album", "front"};
		String[] exts = {"png", "jpg", "jpeg"};
		for (String n : names) {
			for (String e : exts) {
				Path c = dir.resolve(n + "." + e);
				if (Files.isRegularFile(c)) {
					int[] px = CoverArt.fromFile(c);
					if (px != null) return px;
				}
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- MP3 / ID3

	private static void readMp3(Path p, Track t) throws Exception {
		long fileSize = Files.size(p);
		int tagEnd = 0;
		try (InputStream in = Files.newInputStream(p)) {
			byte[] head = in.readNBytes(10);
			if (head.length == 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
				int version = head[3] & 0xFF;
				int flags = head[5] & 0xFF;
				int size = synchsafe(head, 6);
				tagEnd = 10 + size;
				if (size > 0 && size < 40_000_000) {
					byte[] tag = in.readNBytes(size);
					parseId3v2(tag, version, flags, t);
				}
			}
		}
		if ((t.title == null || t.title.isBlank()) && fileSize > 128) {
			readId3v1(p, fileSize, t);
		}
		t.durationMs = estimateMp3Duration(p, tagEnd, fileSize);
	}

	private static int synchsafe(byte[] b, int o) {
		return ((b[o] & 0x7F) << 21) | ((b[o + 1] & 0x7F) << 14) | ((b[o + 2] & 0x7F) << 7) | (b[o + 3] & 0x7F);
	}

	private static void parseId3v2(byte[] tag, int version, int flags, Track t) {
		int pos = 0;
		if ((flags & 0x40) != 0 && tag.length >= 4) {
			int ext = version == 4 ? synchsafe(tag, 0) : (((tag[0] & 0xFF) << 24) | ((tag[1] & 0xFF) << 16) | ((tag[2] & 0xFF) << 8) | (tag[3] & 0xFF)) + 4;
			pos = Math.max(0, ext);
		}
		int idLen = version == 2 ? 3 : 4;
		int hdrLen = version == 2 ? 6 : 10;
		byte[] bestPic = null;
		int bestPicType = -1;
		while (pos + hdrLen <= tag.length) {
			if (tag[pos] == 0) break;
			String id = new String(tag, pos, idLen, StandardCharsets.ISO_8859_1);
			int size;
			if (version == 2) {
				size = ((tag[pos + 3] & 0xFF) << 16) | ((tag[pos + 4] & 0xFF) << 8) | (tag[pos + 5] & 0xFF);
			} else if (version == 4) {
				size = synchsafe(tag, pos + 4);
			} else {
				size = ((tag[pos + 4] & 0xFF) << 24) | ((tag[pos + 5] & 0xFF) << 16) | ((tag[pos + 6] & 0xFF) << 8) | (tag[pos + 7] & 0xFF);
			}
			int start = pos + hdrLen;
			if (size < 0 || start + size > tag.length) break;
			switch (id) {
				case "TIT2", "TT2" -> t.title = decodeText(tag, start, size);
				case "TPE1", "TP1" -> t.artist = decodeText(tag, start, size);
				case "TALB", "TAL" -> t.album = decodeText(tag, start, size);
				case "APIC", "PIC" -> {
					int[] pic = findPicture(tag, start, size, version == 2);
					if (pic != null) {
						int type = pic[0];
						boolean better = bestPic == null || (type == 3 && bestPicType != 3);
						if (better) {
							bestPic = java.util.Arrays.copyOfRange(tag, pic[1], pic[1] + pic[2]);
							bestPicType = type;
						}
					}
				}
				default -> {
				}
			}
			pos = start + size;
		}
		if (bestPic != null) {
			t.setCover(CoverArt.fromBytes(bestPic));
		}
	}

	/** Returns {pictureType, dataOffset, dataLength} or null. */
	private static int[] findPicture(byte[] b, int start, int size, boolean v22) {
		int end = start + size;
		if (size < 6) return null;
		int enc = b[start] & 0xFF;
		int p = start + 1;
		if (v22) {
			p += 3;
		} else {
			while (p < end && b[p] != 0) p++;
			p++;
		}
		if (p >= end) return null;
		int type = b[p] & 0xFF;
		p++;
		// Skip description.
		boolean wide = enc == 1 || enc == 2;
		if (wide) {
			while (p + 1 < end && !(b[p] == 0 && b[p + 1] == 0)) p += 2;
			p += 2;
		} else {
			while (p < end && b[p] != 0) p++;
			p++;
		}
		if (p >= end) return null;
		return new int[]{type, p, end - p};
	}

	private static String decodeText(byte[] b, int start, int size) {
		if (size <= 1) return "";
		int enc = b[start] & 0xFF;
		Charset cs = switch (enc) {
			case 1 -> StandardCharsets.UTF_16;
			case 2 -> StandardCharsets.UTF_16BE;
			case 3 -> StandardCharsets.UTF_8;
			default -> StandardCharsets.ISO_8859_1;
		};
		String s = new String(b, start + 1, size - 1, cs);
		int nul = s.indexOf('\0');
		if (nul >= 0) s = s.substring(0, nul);
		return s.trim();
	}

	private static void readId3v1(Path p, long size, Track t) throws Exception {
		try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(p.toFile(), "r")) {
			f.seek(size - 128);
			byte[] b = new byte[128];
			f.readFully(b);
			if (b[0] == 'T' && b[1] == 'A' && b[2] == 'G') {
				t.title = new String(b, 3, 30, StandardCharsets.ISO_8859_1).replace('\0', ' ').trim();
				t.artist = new String(b, 33, 30, StandardCharsets.ISO_8859_1).replace('\0', ' ').trim();
				t.album = new String(b, 63, 30, StandardCharsets.ISO_8859_1).replace('\0', ' ').trim();
			}
		}
	}

	private static final int[] BR_V1_L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
	private static final int[] BR_V2_L3 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
	private static final int[][] SR = {{44100, 48000, 32000}, {22050, 24000, 16000}, {11025, 12000, 8000}};

	private static long estimateMp3Duration(Path p, int tagEnd, long fileSize) {
		try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(p.toFile(), "r")) {
			f.seek(tagEnd);
			byte[] buf = new byte[8192];
			int n = f.read(buf);
			for (int i = 0; i + 4 < n; i++) {
				if ((buf[i] & 0xFF) != 0xFF || (buf[i + 1] & 0xE0) != 0xE0) continue;
				int vBits = (buf[i + 1] >> 3) & 3;
				int layer = (buf[i + 1] >> 1) & 3;
				if (vBits == 1 || layer != 1) continue; // Only layer 3 is estimated.
				int brIdx = (buf[i + 2] >> 4) & 0xF;
				int srIdx = (buf[i + 2] >> 2) & 3;
				if (brIdx == 0 || brIdx == 15 || srIdx == 3) continue;
				boolean v1 = vBits == 3;
				int verRow = v1 ? 0 : (vBits == 2 ? 1 : 2);
				int sampleRate = SR[verRow][srIdx];
				int bitrate = (v1 ? BR_V1_L3 : BR_V2_L3)[brIdx] * 1000;
				boolean mono = ((buf[i + 3] >> 6) & 3) == 3;
				int samplesPerFrame = v1 ? 1152 : 576;
				int xingOff = i + 4 + (v1 ? (mono ? 17 : 32) : (mono ? 9 : 17));
				if (xingOff + 12 < n) {
					String tagId = new String(buf, xingOff, 4, StandardCharsets.ISO_8859_1);
					if (tagId.equals("Xing") || tagId.equals("Info")) {
						int fl = buf[xingOff + 7] & 0xFF;
						if ((fl & 1) != 0) {
							long frames = ((buf[xingOff + 8] & 0xFFL) << 24) | ((buf[xingOff + 9] & 0xFFL) << 16)
									| ((buf[xingOff + 10] & 0xFFL) << 8) | (buf[xingOff + 11] & 0xFFL);
							return frames * samplesPerFrame * 1000L / sampleRate;
						}
					}
				}
				return (fileSize - tagEnd) * 8L * 1000L / bitrate;
			}
		} catch (Exception ignored) {
		}
		return 0L;
	}

	// ---------------------------------------------------------------- OGG

	private static void readOgg(Path p, Track t) throws Exception {
		byte[] head;
		try (InputStream in = Files.newInputStream(p)) {
			head = in.readNBytes(262_144);
		}
		int at = -1;
		for (int i = 0; i + 7 < head.length; i++) {
			if (head[i] == 3 && head[i + 1] == 'v' && head[i + 2] == 'o' && head[i + 3] == 'r'
					&& head[i + 4] == 'b' && head[i + 5] == 'i' && head[i + 6] == 's') {
				at = i + 7;
				break;
			}
		}
		if (at > 0) {
			try {
				int pos = at;
				int vendor = le32(head, pos);
				pos += 4 + vendor;
				int count = le32(head, pos);
				pos += 4;
				for (int c = 0; c < count && pos + 4 <= head.length; c++) {
					int len = le32(head, pos);
					pos += 4;
					if (len < 0 || pos + len > head.length) break;
					String kv = new String(head, pos, len, StandardCharsets.UTF_8);
					pos += len;
					int eq = kv.indexOf('=');
					if (eq <= 0) continue;
					String k = kv.substring(0, eq).toUpperCase(Locale.ROOT);
					String v = kv.substring(eq + 1).trim();
					switch (k) {
						case "TITLE" -> t.title = v;
						case "ARTIST" -> t.artist = v;
						case "ALBUM" -> t.album = v;
						default -> {
						}
					}
				}
			} catch (Exception ignored) {
			}
		}
		t.durationMs = OggSource.probeDurationMs(p);
	}

	private static int le32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}
