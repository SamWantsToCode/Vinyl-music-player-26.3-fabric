package com.vinylmusic.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** MP3 decoding through the bundled JLayer library. */
public final class Mp3Source implements PcmSource {
	private final Bitstream stream;
	private final Decoder decoder = new Decoder();
	private short[] pending;
	private int pendingLen;
	private int channels = 2;
	private int rate = 44100;

	public Mp3Source(Path path) throws Exception {
		this.stream = new Bitstream(new BufferedInputStream(Files.newInputStream(path), 1 << 16));
		// Decode the first frame now so the line can be opened with the right format.
		decodeNext();
		if (pending == null) {
			throw new IllegalStateException("No MP3 frames found");
		}
		this.channels = decoder.getOutputChannels();
		this.rate = decoder.getOutputFrequency();
	}

	private void decodeNext() throws Exception {
		Header h = stream.readFrame();
		if (h == null) {
			pending = null;
			pendingLen = 0;
			return;
		}
		SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(h, stream);
		short[] buf = sb.getBuffer();
		int len = sb.getBufferLength();
		if (pending == null || pending.length < len) {
			pending = new short[len];
		}
		System.arraycopy(buf, 0, pending, 0, len);
		pendingLen = len;
		stream.closeFrame();
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
		if (pending == null) return -1;
		int n = Math.min(pendingLen, out.length);
		System.arraycopy(pending, 0, out, 0, n);
		decodeNext();
		return n;
	}

	@Override
	public void close() {
		try {
			stream.close();
		} catch (Exception ignored) {
		}
	}
}
