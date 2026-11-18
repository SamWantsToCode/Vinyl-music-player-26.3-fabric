package com.vinylmusic.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Path;

/** Streams one track at a time to the default audio output on its own thread. */
public final class AudioPlayer {
	private volatile Thread thread;
	private volatile boolean stopRequested;
	private volatile boolean paused;
	private volatile float volume = 0.7f;
	private volatile long positionMs;
	private volatile boolean ended;
	private volatile String error;
	private volatile boolean active;

	public void setVolume(float v) {
		this.volume = Math.max(0f, Math.min(1f, v));
	}

	public boolean isActive() {
		return active;
	}

	public boolean isPaused() {
		return paused;
	}

	public long positionMs() {
		return positionMs;
	}

	/** True once after a track finished by itself. */
	public boolean consumeEnded() {
		if (ended) {
			ended = false;
			return true;
		}
		return false;
	}

	public String consumeError() {
		String e = error;
		error = null;
		return e;
	}

	public synchronized void play(Track track) {
		stop();
		stopRequested = false;
		paused = false;
		ended = false;
		positionMs = 0;
		active = true;
		Thread t = new Thread(() -> run(track), "VinylMusicPlayer-Audio");
		t.setDaemon(true);
		thread = t;
		t.start();
	}

	public void setPaused(boolean p) {
		this.paused = p;
	}

	public synchronized void stop() {
		stopRequested = true;
		Thread t = thread;
		if (t != null) {
			t.interrupt();
			try {
				t.join(500);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}
		thread = null;
		active = false;
		paused = false;
	}

	private static PcmSource open(Path p, String ext) throws Exception {
		return switch (ext) {
			case "mp3" -> new Mp3Source(p);
			case "ogg" -> new OggSource(p);
			case "wav" -> new WavSource(p);
			default -> throw new IllegalArgumentException("Unsupported format: " + ext);
		};
	}

	private void run(Track track) {
		PcmSource src = null;
		SourceDataLine line = null;
		boolean finishedNaturally = false;
		try {
			src = open(Path.of(track.path), track.extension());
			int ch = Math.max(1, Math.min(2, src.channels()));
			int rate = src.sampleRate();
			AudioFormat fmt = new AudioFormat(rate, 16, ch, true, false);
			line = AudioSystem.getSourceDataLine(fmt);
			int lineBytes = rate * ch * 2 / 8; // about 125 ms keeps volume changes responsive
			line.open(fmt, Math.max(4096, lineBytes));
			line.start();
			short[] pcm = new short[8192];
			byte[] out = new byte[pcm.length * 2];
			float smooth = volume;
			long startFrames = line.getLongFramePosition();
			boolean wasPaused = false;
			while (!stopRequested) {
				if (paused) {
					if (!wasPaused) {
						line.stop();
						wasPaused = true;
					}
					Thread.sleep(40);
					continue;
				}
				if (wasPaused) {
					line.start();
					wasPaused = false;
				}
				int n = src.read(pcm);
				if (n < 0) {
					finishedNaturally = true;
					break;
				}
				int srcCh = src.channels();
				int written = 0;
				float target = volume;
				for (int i = 0; i < n; ) {
					smooth += (target - smooth) * 0.002f;
					float g = smooth * smooth;
					if (srcCh == ch) {
						for (int c = 0; c < ch && i < n; c++, i++) {
							written = put(out, written, pcm[i], g);
						}
					} else {
						// Fold surround or other layouts to stereo by taking the first two channels.
						int frameEnd = Math.min(n, i + srcCh);
						for (int c = 0; c < srcCh && i < n; c++, i++) {
							if (c < ch) written = put(out, written, pcm[i], g);
						}
						if (i < frameEnd) i = frameEnd;
					}
				}
				line.write(out, 0, written);
				positionMs = (line.getLongFramePosition() - startFrames) * 1000L / rate;
			}
			if (finishedNaturally) {
				line.drain();
			}
		} catch (InterruptedException ignored) {
			// Stopped.
		} catch (Throwable t) {
			error = t.getClass().getSimpleName() + ": " + t.getMessage();
		} finally {
			if (line != null) {
				try {
					line.stop();
					line.flush();
					line.close();
				} catch (Throwable ignored) {
				}
			}
			if (src != null) src.close();
			if (finishedNaturally && !stopRequested) {
				ended = true;
			}
			if (thread == Thread.currentThread() || thread == null) {
				active = false;
			}
		}
	}

	private static int put(byte[] out, int at, short sample, float gain) {
		int v = Math.round(sample * gain);
		if (v > 32767) v = 32767;
		if (v < -32768) v = -32768;
		out[at++] = (byte) v;
		out[at++] = (byte) (v >> 8);
		return at;
	}
}
