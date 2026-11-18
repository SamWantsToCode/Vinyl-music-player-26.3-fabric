package com.vinylmusic.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Path;

/** WAV decoding with javax.sound. */
public final class WavSource implements PcmSource {
	private final AudioInputStream in;
	private final int channels;
	private final int rate;
	private byte[] bytes = new byte[0];

	public WavSource(Path path) throws Exception {
		AudioInputStream raw = AudioSystem.getAudioInputStream(path.toFile());
		AudioFormat f = raw.getFormat();
		AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, f.getSampleRate(), 16,
				f.getChannels(), f.getChannels() * 2, f.getSampleRate(), false);
		this.in = AudioSystem.isConversionSupported(target, f) ? AudioSystem.getAudioInputStream(target, raw) : raw;
		this.channels = f.getChannels();
		this.rate = (int) f.getSampleRate();
	}

	public static long probeDurationMs(Path path) {
		try (AudioInputStream s = AudioSystem.getAudioInputStream(path.toFile())) {
			long frames = s.getFrameLength();
			float r = s.getFormat().getFrameRate();
			return frames > 0 && r > 0 ? (long) (frames * 1000.0 / r) : 0L;
		} catch (Throwable t) {
			return 0L;
		}
	}

	@Override
	public int channels() {
		return channels;
	}

	@Override
	public int sampleRate() {
		return rate;
	}

	@Override
	public int read(short[] out) throws Exception {
		int need = out.length * 2;
		if (bytes.length < need) bytes = new byte[need];
		int n = in.read(bytes, 0, need);
		if (n <= 0) return -1;
		int shorts = n / 2;
		for (int i = 0; i < shorts; i++) {
			out[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
		}
		return shorts;
	}

	@Override
	public void close() {
		try {
			in.close();
		} catch (Exception ignored) {
		}
	}
}
