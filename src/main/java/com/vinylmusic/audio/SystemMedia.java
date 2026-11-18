package com.vinylmusic.audio;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Reads and controls whatever the operating system reports as now playing, which includes
 * YouTube Music in a browser, Spotify and most desktop players.
 * Windows uses the System Media Transport Controls through a small PowerShell loop.
 * Linux uses playerctl (MPRIS). macOS uses nowplaying-cli when installed.
 */
public final class SystemMedia {
	public volatile String title = "";
	public volatile String artist = "";
	public volatile String album = "";
	public volatile boolean playing;
	public volatile boolean hasSession;
	public volatile int[] cover;
	/** Short human readable state, shown in the GUI when something is missing. */
	public volatile String status = "";

	private volatile boolean running;
	private Thread worker;
	private Process process;
	private Path cmdFile;
	private Path scriptFile;

	private enum Os { WINDOWS, MAC, LINUX }

	private static Os os() {
		String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (n.contains("win")) return Os.WINDOWS;
		if (n.contains("mac")) return Os.MAC;
		return Os.LINUX;
	}

	public synchronized void start() {
		if (running) return;
		running = true;
		Os o = os();
		worker = new Thread(() -> {
			try {
				switch (o) {
					case WINDOWS -> runWindows();
					case MAC -> runPolling(true);
					default -> runPolling(false);
				}
			} catch (Throwable t) {
				status = "Media sync stopped: " + t.getMessage();
			}
		}, "VinylMusicPlayer-SystemMedia");
		worker.setDaemon(true);
		worker.start();
	}

	public synchronized void stop() {
		running = false;
		if (process != null) {
			process.destroy();
			process = null;
		}
		if (worker != null) worker.interrupt();
		worker = null;
	}

	public void togglePlayPause() {
		send("toggle", "play-pause", "togglePlayPause");
	}

	public void next() {
		send("next", "next", "next");
	}

	public void previous() {
		send("prev", "previous", "previous");
	}

	/** Linux only. Windows does not expose an app volume through the media session. */
	public void setVolume(float v) {
		if (os() == Os.LINUX) {
			exec("playerctl", "volume", String.format(Locale.ROOT, "%.2f", v));
		}
	}

	private void send(String winCmd, String linuxCmd, String macCmd) {
		Thread t = new Thread(() -> {
			try {
				switch (os()) {
					case WINDOWS -> {
						if (cmdFile != null) Files.writeString(cmdFile, winCmd);
					}
					case MAC -> exec("nowplaying-cli", macCmd);
					default -> exec("playerctl", linuxCmd);
				}
			} catch (Throwable ignored) {
			}
		}, "VinylMusicPlayer-MediaCmd");
		t.setDaemon(true);
		t.start();
	}

	// ------------------------------------------------------------------ Windows

	private static final String WIN_SCRIPT = """
			$ErrorActionPreference = 'SilentlyContinue'
			[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
			Add-Type -AssemblyName System.Runtime.WindowsRuntime
			$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
			function Await($op, $type) { $t = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }
			[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
			[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType=WindowsRuntime]
			[void][Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType=WindowsRuntime]
			$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
			$lastKey = ''
			$cmdFile = '__CMD__'
			while ($true) {
			  $s = $mgr.GetCurrentSession()
			  if ($s -ne $null) {
			    $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
			    $st = $s.GetPlaybackInfo().PlaybackStatus
			    $key = "$($p.Title)|$($p.Artist)|$($p.AlbumTitle)"
			    $flag = '0'
			    $cover = ''
			    if ($key -ne $lastKey) {
			      $lastKey = $key
			      $flag = '1'
			      try {
			        if ($p.Thumbnail -ne $null) {
			          $rs = Await ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
			          $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($rs)
			          $ms = New-Object System.IO.MemoryStream
			          $net.CopyTo($ms)
			          $cover = [Convert]::ToBase64String($ms.ToArray())
			        }
			      } catch {}
			    }
			    $t1 = ("$($p.Title)").Replace("`t", ' ')
			    $t2 = ("$($p.Artist)").Replace("`t", ' ')
			    $t3 = ("$($p.AlbumTitle)").Replace("`t", ' ')
			    [Console]::Out.WriteLine("S`t$st`t$t1`t$t2`t$t3`t$flag`t$cover")
			  } else {
			    [Console]::Out.WriteLine("N")
			  }
			  [Console]::Out.Flush()
			  for ($i = 0; $i -lt 6; $i++) {
			    Start-Sleep -Milliseconds 250
			    if (Test-Path $cmdFile) {
			      $c = (Get-Content $cmdFile -Raw).Trim()
			      Remove-Item $cmdFile -Force
			      $s2 = $mgr.GetCurrentSession()
			      if ($s2 -ne $null) {
			        if ($c -eq 'toggle') { [void](Await ($s2.TryTogglePlayPauseAsync()) ([bool])) }
			        elseif ($c -eq 'next') { [void](Await ($s2.TrySkipNextAsync()) ([bool])) }
			        elseif ($c -eq 'prev') { [void](Await ($s2.TrySkipPreviousAsync()) ([bool])) }
			      }
			      break
			    }
			  }
			}
			""";

	private void runWindows() throws Exception {
		Path dir = Files.createTempDirectory("vinylmusic");
		cmdFile = dir.resolve("cmd.txt");
		scriptFile = dir.resolve("media.ps1");
		String cmdPath = cmdFile.toString().replace("'", "''");
		Files.writeString(scriptFile, WIN_SCRIPT.replace("__CMD__", cmdPath), StandardCharsets.UTF_8);
		ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
				"-ExecutionPolicy", "Bypass", "-File", scriptFile.toString());
		pb.redirectError(ProcessBuilder.Redirect.DISCARD);
		status = "Connecting to Windows media session";
		process = pb.start();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (running && (line = r.readLine()) != null) {
				if (line.startsWith("S\t")) {
					String[] f = line.split("\t", -1);
					if (f.length >= 6) {
						hasSession = true;
						playing = f[1].equalsIgnoreCase("Playing");
						title = f[2];
						artist = f[3];
						album = f[4];
						if (f.length >= 7 && f[5].equals("1")) {
							cover = f[6].isEmpty() ? null : CoverArt.fromBytes(Base64.getDecoder().decode(f[6]));
						}
						status = "";
					}
				} else if (line.startsWith("N")) {
					hasSession = false;
					playing = false;
					status = "Nothing is playing. Start music in YouTube Music, Spotify or another player.";
				}
			}
		} finally {
			if (process != null) process.destroy();
			try {
				Files.deleteIfExists(scriptFile);
				Files.deleteIfExists(cmdFile);
				Files.deleteIfExists(scriptFile.getParent());
			} catch (Exception ignored) {
			}
		}
	}

	// ------------------------------------------------------------------ Linux and macOS

	private String lastArt = "";

	private void runPolling(boolean mac) throws Exception {
		String tool = mac ? "nowplaying-cli" : "playerctl";
		if (exec(tool, "--help") == null && !commandExists(tool)) {
			status = mac ? "Install nowplaying-cli (brew install nowplaying-cli) for system media sync."
					: "Install playerctl to sync with Spotify, YouTube Music and other players.";
			return;
		}
		while (running) {
			try {
				if (mac) pollMac();
				else pollLinux();
			} catch (Throwable ignored) {
			}
			Thread.sleep(1500);
		}
	}

	private void pollLinux() {
		List<String> out = exec("playerctl", "metadata", "--format",
				"{{status}}\t{{title}}\t{{artist}}\t{{album}}\t{{mpris:artUrl}}");
		if (out == null || out.isEmpty() || out.get(0).isBlank()) {
			hasSession = false;
			playing = false;
			status = "Nothing is playing. Start music in a player that supports MPRIS.";
			return;
		}
		String[] f = out.get(0).split("\t", -1);
		if (f.length < 4) return;
		hasSession = true;
		playing = f[0].equalsIgnoreCase("Playing");
		title = f[1];
		artist = f[2];
		album = f[3];
		status = "";
		String art = f.length > 4 ? f[4] : "";
		if (!art.equals(lastArt)) {
			lastArt = art;
			cover = loadArt(art);
		}
	}

	private void pollMac() {
		List<String> out = exec("nowplaying-cli", "get", "title", "artist", "album", "playbackRate");
		if (out == null || out.size() < 4 || out.get(0).equals("null")) {
			hasSession = false;
			playing = false;
			status = "Nothing is playing.";
			return;
		}
		hasSession = true;
		title = nullToEmpty(out.get(0));
		artist = nullToEmpty(out.get(1));
		album = nullToEmpty(out.get(2));
		playing = out.get(3).trim().startsWith("1");
		status = "";
		String key = title + "|" + artist + "|" + album;
		if (!key.equals(lastArt)) {
			lastArt = key;
			List<String> art = exec("nowplaying-cli", "get", "artworkData");
			try {
				cover = art == null || art.isEmpty() || art.get(0).equals("null") ? null
						: CoverArt.fromBytes(Base64.getDecoder().decode(art.get(0).trim()));
			} catch (Exception e) {
				cover = null;
			}
		}
	}

	private static String nullToEmpty(String s) {
		return s == null || s.equals("null") ? "" : s;
	}

	private static int[] loadArt(String url) {
		try {
			if (url == null || url.isBlank()) return null;
			if (url.startsWith("file:")) {
				return CoverArt.fromFile(Path.of(URI.create(url)));
			}
			if (url.startsWith("https://") || url.startsWith("http://")) {
				HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
				c.setConnectTimeout(4000);
				c.setReadTimeout(4000);
				try (InputStream in = c.getInputStream()) {
					byte[] data = in.readNBytes(5_000_000);
					return CoverArt.fromBytes(data);
				}
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	private static boolean commandExists(String tool) {
		return exec("which", tool) != null;
	}

	/** Runs a command and returns its stdout lines, or null when it failed or timed out. */
	private static List<String> exec(String... cmd) {
		try {
			Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
			List<String> lines = new ArrayList<>();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String l;
				while ((l = r.readLine()) != null) lines.add(l);
			}
			if (!p.waitFor(4, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return null;
			}
			return p.exitValue() == 0 ? lines : null;
		} catch (Throwable t) {
			return null;
		}
	}
}
