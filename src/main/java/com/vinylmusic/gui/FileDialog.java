package com.vinylmusic.gui;

import com.vinylmusic.audio.MusicManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Native file and folder pickers using what the operating system already has:
 * Windows file dialog through PowerShell, macOS through osascript, Linux through zenity or kdialog.
 * Nothing extra is bundled.
 */
public final class FileDialog {
	private FileDialog() {
	}

	private enum Os { WINDOWS, MAC, LINUX }

	private static Os os() {
		String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (n.contains("win")) return Os.WINDOWS;
		if (n.contains("mac")) return Os.MAC;
		return Os.LINUX;
	}

	/** Result of one helper process. started is false when the program does not exist. */
	private record Run(boolean started, int code, List<String> lines) {
	}

	public static void pickFiles(String title, Path startDir, String description, String[] patterns,
								 boolean multiple, Consumer<List<Path>> result) {
		List<String> exts = new ArrayList<>();
		for (String p : patterns) exts.add(p.replace("*.", "").toLowerCase(Locale.ROOT));
		background(() -> {
			Run r = switch (os()) {
				case WINDOWS -> windows(false, title, startDir, description, exts, multiple);
				case MAC -> mac(false, title, exts, multiple);
				default -> linux(false, title, startDir, exts, multiple);
			};
			deliver(r, paths -> result.accept(paths));
		});
	}

	public static void pickFolder(String title, Path startDir, Consumer<Path> result) {
		background(() -> {
			Run r = switch (os()) {
				case WINDOWS -> windows(true, title, startDir, "", List.of(), false);
				case MAC -> mac(true, title, List.of(), false);
				default -> linux(true, title, startDir, List.of(), false);
			};
			deliver(r, paths -> result.accept(paths.get(0)));
		});
	}

	private static void deliver(Run r, Consumer<List<Path>> sink) {
		if (!r.started()) {
			MusicManager.get().setMessage(Ui.tr("picker.none"));
			return;
		}
		List<Path> paths = new ArrayList<>();
		for (String l : r.lines()) {
			String s = l.trim();
			if (!s.isEmpty()) {
				for (String part : s.split("\\|")) {
					if (!part.isBlank()) paths.add(Path.of(part.trim()));
				}
			}
		}
		if (!paths.isEmpty()) sink.accept(paths);
	}

	// ---------------------------------------------------------------- Windows

	private static String q(String s) {
		return s.replace("'", "''");
	}

	private static Run windows(boolean folder, String title, Path start, String description, List<String> exts, boolean multiple) {
		String dir = q(start == null ? "" : start.toAbsolutePath().toString());
		StringBuilder pattern = new StringBuilder();
		for (String e : exts) {
			if (pattern.length() > 0) pattern.append(';');
			pattern.append("*.").append(e);
		}
		String script = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n"
				+ "Add-Type -AssemblyName System.Windows.Forms\n"
				+ "$owner = New-Object System.Windows.Forms.Form\n"
				+ "$owner.TopMost = $true\n$owner.ShowInTaskbar = $false\n$owner.Opacity = 0\n"
				+ "$owner.StartPosition = 'CenterScreen'\n$owner.Show()\n"
				+ (folder
				? "$dlg = New-Object System.Windows.Forms.FolderBrowserDialog\n$dlg.Description = '" + q(title) + "'\n"
				+ "$dlg.SelectedPath = '" + dir + "'\n"
				+ "if ($dlg.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::Out.WriteLine($dlg.SelectedPath) }\n"
				: "$dlg = New-Object System.Windows.Forms.OpenFileDialog\n$dlg.Title = '" + q(title) + "'\n"
				+ "$dlg.InitialDirectory = '" + dir + "'\n"
				+ "$dlg.Filter = '" + q(description) + " (" + pattern + ")|" + pattern + "|All files|*.*'\n"
				+ "$dlg.Multiselect = " + (multiple ? "$true" : "$false") + "\n"
				+ "if ($dlg.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK) { $dlg.FileNames | ForEach-Object { [Console]::Out.WriteLine($_) } }\n")
				+ "$owner.Close()\n";
		Path file = null;
		try {
			file = Files.createTempFile("vinylmusic-pick", ".ps1");
			Files.writeString(file, script, StandardCharsets.UTF_8);
			return exec("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-ExecutionPolicy", "Bypass", "-File", file.toString());
		} catch (Exception e) {
			return new Run(false, -1, List.of());
		} finally {
			try {
				if (file != null) Files.deleteIfExists(file);
			} catch (Exception ignored) {
			}
		}
	}

	// ---------------------------------------------------------------- macOS

	private static Run mac(boolean folder, String title, List<String> exts, boolean multiple) {
		if (folder) {
			return exec("osascript", "-e", "POSIX path of (choose folder with prompt \"" + title.replace("\"", "") + "\")");
		}
		StringBuilder types = new StringBuilder();
		for (String e : exts) {
			if (types.length() > 0) types.append(", ");
			types.append('"').append(e).append('"');
		}
		return exec("osascript",
				"-e", "set fs to choose file with prompt \"" + title.replace("\"", "") + "\" of type {" + types + "}"
						+ (multiple ? " with multiple selections allowed" : ""),
				"-e", "if class of fs is not list then set fs to {fs}",
				"-e", "set out to \"\"",
				"-e", "repeat with f in fs",
				"-e", "set out to out & POSIX path of f & linefeed",
				"-e", "end repeat",
				"-e", "return out");
	}

	// ---------------------------------------------------------------- Linux

	private static Run linux(boolean folder, String title, Path start, List<String> exts, boolean multiple) {
		String dir = (start == null ? "" : start.toAbsolutePath().toString());
		StringBuilder globs = new StringBuilder();
		for (String e : exts) {
			if (globs.length() > 0) globs.append(' ');
			globs.append("*.").append(e);
		}
		List<String> z = new ArrayList<>(List.of("zenity", "--file-selection", "--title=" + title, "--separator=|"));
		if (folder) z.add("--directory");
		if (multiple) z.add("--multiple");
		if (!dir.isEmpty()) z.add("--filename=" + dir + "/");
		if (!folder && globs.length() > 0) z.add("--file-filter=Audio | " + globs);
		Run r = exec(z.toArray(new String[0]));
		if (r.started()) return r;

		List<String> k = new ArrayList<>(List.of("kdialog", "--title", title));
		if (folder) {
			k.add("--getexistingdirectory");
			k.add(dir.isEmpty() ? "." : dir);
		} else {
			if (multiple) {
				k.add("--multiple");
				k.add("--separate-output");
			}
			k.add("--getopenfilename");
			k.add(dir.isEmpty() ? "." : dir);
			if (globs.length() > 0) k.add(globs + "|Audio");
		}
		return exec(k.toArray(new String[0]));
	}

	// ---------------------------------------------------------------- helpers

	private static Run exec(String... cmd) {
		try {
			Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
			List<String> lines = new ArrayList<>();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String l;
				while ((l = r.readLine()) != null) lines.add(l);
			}
			int code = p.waitFor();
			return new Run(true, code, lines);
		} catch (Throwable t) {
			return new Run(false, -1, List.of());
		}
	}

	private static void background(Runnable r) {
		Thread t = new Thread(r, "VinylMusicPlayer-FileDialog");
		t.setDaemon(true);
		t.start();
	}

	/** Opens a folder in the system file manager. */
	public static void openFolder(Path dir) {
		background(() -> {
			try {
				Files.createDirectories(dir);
				String[] cmd = switch (os()) {
					case WINDOWS -> new String[]{"explorer.exe", dir.toString()};
					case MAC -> new String[]{"open", dir.toString()};
					default -> new String[]{"xdg-open", dir.toString()};
				};
				new ProcessBuilder(cmd).start();
			} catch (Throwable ignored) {
			}
		});
	}
}
