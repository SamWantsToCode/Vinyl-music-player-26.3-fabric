package com.vinylmusic.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Status flags shown in Settings and written to logs/latest.log so a silent failure is visible. */
public final class Diagnostics {
	private Diagnostics() {
	}

	public static final Logger LOG = LoggerFactory.getLogger("VinylMusicPlayer");

	/** Shown in Settings, Status so you can tell which build is running. */
	public static final String BUILD = "1.0.21-r4 (26.3 mouse buttons fixed, removable defaults)";

	public static volatile boolean initDone;
	public static volatile boolean screenHookRegistered;
	public static volatile boolean hudRegistered;
	public static volatile boolean modMenuPresent;
	public static volatile boolean overlayDrawn;
	public static volatile boolean hudDrawn;
	public static volatile boolean soundMixinHit;
	public static volatile String lastError = "";

	private static boolean errorLogged;

	public static void error(String where, Throwable t) {
		lastError = where + ": " + t;
		if (!errorLogged) {
			errorLogged = true;
			LOG.error("[VinylMusicPlayer] Error in {}. The disc is disabled until restart.", where, t);
		}
	}

	public static boolean hasError() {
		return errorLogged;
	}
}
