package com.vinylmusic.gui;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Native file and folder pickers (tinyfiledialogs ships with Minecraft's LWJGL). */
public final class FileDialog {
	private FileDialog() {
	}

	public static void pickFiles(String title, Path startDir, String description, String[] patterns,
								 boolean multiple, Consumer<List<Path>> result) {
		Thread t = new Thread(() -> {
			List<Path> out = new ArrayList<>();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer filters = stack.mallocPointer(patterns.length);
				for (String p : patterns) filters.put(stack.UTF8(p));
				filters.flip();
				String res = TinyFileDialogs.tinyfd_openFileDialog(title, startString(startDir), filters, description, multiple);
				if (res != null && !res.isEmpty()) {
					for (String part : res.split("\\|")) {
						if (!part.isBlank()) out.add(Path.of(part));
					}
				}
			} catch (Throwable ignored) {
			}
			if (!out.isEmpty()) result.accept(out);
		}, "VinylMusicPlayer-FileDialog");
		t.setDaemon(true);
		t.start();
	}

	public static void pickFolder(String title, Path startDir, Consumer<Path> result) {
		Thread t = new Thread(() -> {
			try {
				String res = TinyFileDialogs.tinyfd_selectFolderDialog(title, startString(startDir));
				if (res != null && !res.isEmpty()) result.accept(Path.of(res));
			} catch (Throwable ignored) {
			}
		}, "VinylMusicPlayer-FolderDialog");
		t.setDaemon(true);
		t.start();
	}

	/** Opens a folder in the system file manager. */
	public static void openFolder(Path dir) {
		Thread t = new Thread(() -> {
			try {
				Files.createDirectories(dir);
				String os = System.getProperty("os.name", "").toLowerCase();
				String[] cmd = os.contains("win") ? new String[]{"explorer.exe", dir.toString()}
						: os.contains("mac") ? new String[]{"open", dir.toString()}
						: new String[]{"xdg-open", dir.toString()};
				new ProcessBuilder(cmd).start();
			} catch (Throwable ignored) {
			}
		}, "VinylMusicPlayer-OpenFolder");
		t.setDaemon(true);
		t.start();
	}

	private static String startString(Path p) {
		String s = p == null ? "" : p.toAbsolutePath().toString();
		return s.endsWith(File.separator) ? s : s + File.separator;
	}
}
