package com.vinylmusic.audio;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** OGG Vorbis decoding through the STB decoder that already ships with LWJGL inside Minecraft. */
public final class OggSource implements PcmSource {
	private final ByteBuffer data;
	private final long handle;
	private final int channels;
	private final int rate;
	private ShortBuffer pcm;
	private boolean closed;

	public OggSource(Path path) throws Exception {
		byte[] bytes = Files.readAllBytes(path);
		data = MemoryUtil.memAlloc(bytes.length);
		data.put(bytes).flip();
		IntBuffer err = MemoryUtil.memAllocInt(1);
		long h;
		try {
			h = STBVorbis.stb_vorbis_open_memory(data, err, null);
		} finally {
			MemoryUtil.memFree(err);
		}
		if (h == 0L) {
			MemoryUtil.memFree(data);
			throw new IllegalStateException("Could not open OGG file");
		}
		handle = h;
		STBVorbisInfo info = STBVorbisInfo.malloc();
		try {
			STBVorbis.stb_vorbis_get_info(handle, info);
			channels = info.channels();
			rate = info.sample_rate();
		} finally {
			info.free();
		}
	}

	/** Length in milliseconds or 0 when it cannot be read. */
	public static long probeDurationMs(Path path) {
		try (OggSource s = new OggSource(path)) {
			return (long) (STBVorbis.stb_vorbis_stream_length_in_seconds(s.handle) * 1000.0f);
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
	public int read(short[] out) {
		if (closed) return -1;
		if (pcm == null || pcm.capacity() < out.length) {
			if (pcm != null) MemoryUtil.memFree(pcm);
			pcm = MemoryUtil.memAllocShort(out.length);
		}
		pcm.clear();
		pcm.limit(out.length);
		int perChannel = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, pcm);
		if (perChannel <= 0) return -1;
		int total = perChannel * channels;
		pcm.get(0, out, 0, total);
		return total;
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		STBVorbis.stb_vorbis_close(handle);
		MemoryUtil.memFree(data);
		if (pcm != null) {
			MemoryUtil.memFree(pcm);
			pcm = null;
		}
	}
}
