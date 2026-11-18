package com.vinylmusic.audio;

/** One song in the playlist. */
public final class Track {
	public static final int COVER_SIZE = 20;
	public static final int THUMB_SIZE = 8;

	public final String path;
	public volatile String title;
	public volatile String artist = "";
	public volatile String album = "";
	public volatile long durationMs;
	/** COVER_SIZE x COVER_SIZE opaque ARGB pixels or null. */
	public volatile int[] cover;
	/** THUMB_SIZE x THUMB_SIZE opaque ARGB pixels or null. */
	public volatile int[] thumb;

	public Track(String path) {
		this.path = path;
		this.title = "";
	}

	public void setCover(int[] c) {
		this.cover = c;
		this.thumb = c == null ? null : CoverArt.resample(c, COVER_SIZE, THUMB_SIZE);
	}

	public String fileName() {
		int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return i >= 0 ? path.substring(i + 1) : path;
	}

	public String extension() {
		String n = fileName();
		int d = n.lastIndexOf('.');
		return d >= 0 ? n.substring(d + 1).toLowerCase() : "";
	}
}
