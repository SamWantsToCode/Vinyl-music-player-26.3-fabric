# Vinyl Music Player (Fabric, Minecraft 26.3, client side)

A spinning vinyl disc in a screen corner, a local music player, system media sync
(YouTube Music, Spotify and others) and a per-sound volume mixer.

Version 1.0.21 for Minecraft 26.3. Needs Fabric Loader 0.19.5 or newer, Fabric API and Java 25.
Mod Menu is optional (it adds a Configure button). Client side only: it works on singleplayer,
Realms and any server without installing anything on the server.

## Get the .jar without installing anything

1. Make a free account on github.com and create a new empty repository.
2. Unzip this archive. In the repository click "Add file", then "Upload files". Drag in everything
   from inside the unzipped folder, including the hidden `.github` folder.
   - macOS: press Cmd+Shift+. in Finder to show `.github`.
   - If `.github` will not upload: "Add file", "Create new file", type `.github/workflows/build.yml`
     as the name, paste the contents of that file, then commit.
3. Click "Commit changes". Open the "Actions" tab and wait for the green check (about 3 minutes).
4. On the repository front page, open "Releases" on the right side. Download
   `vinyl-music-player-1.0.21+26.3.jar` directly. It is a real .jar, not a .zip.
   Put it in `.minecraft/mods`. Delete older Vinyl Music Player jars from that folder.

If the run is red, open it, copy the red error lines and send them to the developer.
If the mod does nothing in game, send `.minecraft/logs/latest.log`. Search it for "VinylMusicPlayer".

## Using it

- Open the pause menu (Escape) or the title screen: the disc shows in the bottom-left corner.
- Left click the disc to open the full screen. Click the arrows to skip. Drag the bar for volume.
- Right-drag the disc to move it. Pause menu, title screen and in-game each remember their own place.
- Music tab: Add New Song, Add Folder, Delete All Songs, Shuffle, Loop and Source. Click a song for
  Play Now, Play Next, Add to Queue, Edit Song Info / Custom Cover and Remove.
- Songs are .mp3, .ogg and .wav. You can also copy files into `.minecraft/music_player/` and press Rescan Folder.
- The disc only shows what THIS player plays. The music you hear in a world is Minecraft's own music.
- Sound Mixer tab: category sliders, then Animals, Hostile Mobs, Blocks, Particles, Weather, Other.
  "Group by entity" gives one slider per mob (all cow sounds at once). Turn it off for single sounds.
  M mutes a row. Reset All puts everything back to 100%.
- Settings tab (scrolls):
  - Pause menu, main menu and in game: show or hide, 9 anchor positions, left/right and up/down offsets.
  - In game: Hidden, Always on, or Only while music plays. Optional: also show on inventory, chat and menus.
  - Look: size, opacity, vinyl or CD style, disc color (presets and red/green/blue sliders), label color,
    highlight color, spin speed, shine, song info, volume bar.
  - Status: shows which hooks work. Useful when something does not appear.

## System media (YouTube Music, Spotify)

Set Source to "YouTube Music / Desktop App".
- Windows: works out of the box. The mod starts a small PowerShell script that reads the Windows media
  session (title, artist, album, cover) and sends play, pause, next and previous.
- Linux: install `playerctl`.
- macOS: install `nowplaying-cli` (`brew install nowplaying-cli`).
Volume for this source is only controllable on Linux. On Windows use the player's own volume.

## Files

- Config: `.minecraft/config/vinyl-music-player.json`
- Music folder: `.minecraft/music_player/`

## Source layout

- `audio/` decoders (MP3 through bundled JLayer, OGG through LWJGL STB, WAV through javax.sound), tags,
  player, playlist manager, system media.
- `gui/` disc renderer, corner overlay, main screen, mouse hooks, file pickers, sound catalog.
- `mixin/` `SoundEngineMixin` multiplies the final volume of each sound. `ScreenMixin` draws the corner disc.
- `compat/` Mod Menu entrypoint.

## Places to check first if a Minecraft update breaks the build

- `gui/InputHooks.java`: Fabric `ScreenMouseEvents` (found by name at runtime), plus `gui/Input.java` backup polling
- `gui/VinylScreen.java`: `extractBackground`, `mouseScrolled`, `rebuildWidgets`, `EditBox`
- `gui/Overlay.java`: `mc.gui.screen()`, `mc.gui.setScreen(...)`, `getWindow().getGuiScaledWidth()`
- `mixin/SoundEngineMixin.java`: `calculateVolume`, `getSource()`, `getIdentifier()`
- `mixin/ScreenMixin.java`: `extractRenderState`
- `client/VinylClient.java`: `HudElementRegistry`, `VanillaHudElements`
