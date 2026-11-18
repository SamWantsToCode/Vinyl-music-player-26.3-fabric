package com.vinylmusic.audio;

/** Decoded 16-bit interleaved little-endian-ready PCM. */
public interface PcmSource extends AutoCloseable {
	int channels();

	int sampleRate();

	/** Fills out with interleaved samples. Returns the number of shorts written or -1 at the end of the stream. */
	int read(short[] out) throws Exception;

	@Override
	void close();
}
