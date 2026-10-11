# Mogy Optimizer

A client-side Fabric mod that fixes two things that have bothered me in Minecraft for a long time:

1. **Fullscreen stutter while screen sharing.** Streaming the game on Discord or recording with OBS in fullscreen freezes the stream, drops your FPS and adds micro-stutter, no matter how good your PC is.
2. **The resource pack menu.** With a lot of packs, opening the menu freezes the game, clicking a pack can freeze it again, and on some clients it lags long enough to get you kicked from a server.

Mogy Optimizer fixes both without changing how the game looks or plays.

| Minecraft | Loader | Branch | Requires |
|-----------|--------|--------|----------|
| 26.1.2 | Fabric | [`main`](../../tree/main) | Fabric API |
| 1.21.11 | Fabric | [`1.21.11`](../../tree/1.21.11) | Fabric Loader only |

Client-side only. Works on any server.

**Download:** [Modrinth](https://modrinth.com/mod/mogy-optimizer) · [GitHub Releases](https://github.com/Mogystl/mogy-optimizer/releases)

---

## Features

- **Borderless fullscreen** that screen-capture tools can actually record, with instant alt-tab.
- **No more auto-minimize.** Clicking another monitor no longer minimizes the game and drops it to 10 FPS.
- **Adaptive vsync**, so a late frame doesn't halve your frame rate.
- **Resource pack menu opens instantly**, with all your packs listed on the first frame.
- **Pack scans run in the background** and reuse cached pack info, so they're around 10Ã— faster.
- **Pack icons load in the background**, downscaled and cached, so scrolling stays smooth.
- **Crash protection** for broken packs, oversized icons and clicks during a rescan.
- **Lunar Client fix** for its folder view, which could freeze the game for close to a minute on large pack folders.
- **Freeze reports**: if the game ever hangs for 3+ seconds, a short report shows what it was doing.

---

## How it works

### Fullscreen and screen sharing

Vanilla fullscreen attaches the window directly to your monitor (exclusive fullscreen). On Windows the GPU driver then gives the game direct control of the display and skips the desktop compositor. Discord, OBS window capture and the Windows capture API all read frames *from* that compositor, so they either force a display mode switch (the stream freezes, the game hitches) or hook into the game's swap chain.

Mogy Optimizer replaces this with **borderless fullscreen**: an undecorated window moved and resized to cover the monitor. It looks the same, but it stays a normal desktop window, so the compositor keeps presenting it and capture tools get every frame. The window is made one pixel taller than the screen, because some drivers notice a window that covers a monitor exactly and silently switch it back to exclusive mode.

Two smaller issues made it worse:

- **Auto-minimize:** GLFW (the window library Minecraft uses) minimizes a fullscreen window when it loses focus. Vanilla then sees a minimized window and caps the game at **10 FPS**, which is exactly what you see on stream when you click Discord on your second monitor. The mod turns that behaviour off. A real minimize (Win+D, taskbar) still works as usual.
- **Vsync halving:** with normal vsync, a frame that's a little late waits a whole refresh, so 60 FPS drops to 30 and back. That alternation is the "micro-stutter". If your driver supports it, the mod uses *adaptive* vsync, which shows a late frame immediately with a tiny tear instead of waiting.

On macOS and Linux Wayland the game keeps vanilla fullscreen, since those platforms handle fullscreen differently.

### Resource pack menu

Opening the vanilla menu does several slow things on the main thread:

1. It rescans the whole resource pack folder, opening every zip and reading every `pack.mcmeta`, **every time** the menu opens.
2. It loads each pack icon at full resolution the first time the row is drawn. Many packs ship 1024â€“4096 px icons that are displayed at 32 px.
3. It throws away its icon cache whenever anything in the folder changes.

The mod reworks this:

- **Instant open:** the menu is filled immediately with the packs the game already knows, and the rescan happens on a background thread. When it finishes, the result is swapped in on the main thread in a single step, using the same logic vanilla uses.
- **Pack info cache:** each pack's metadata (name, description, compatibility) is remembered, keyed by the file's size and modification date. Unchanged packs are answered from memory instead of reopening the archive. Edited packs have a new size or date, so they're read again. On my own 250-pack folder this cut a scan from about 520 ms to about 50 ms.
- **Icon cache:** icons are decoded and shrunk to 64 px in the background, uploaded to the GPU a few per tick, and kept between menu opens. Oversized or broken images fall back to the default icon instead of using huge amounts of memory.
- **Quieter scans:** vanilla logs a line for every loose file in the pack folder. On folders with an unzipped pack that's thousands of log lines per scan, which also slows the game. The mod drops that message.

### Crash protection

- A pack image that is corrupt, or that claims huge dimensions, is rejected *before* decoding. Vanilla only catches regular exceptions, so an out-of-memory error from a "decompression bomb" image would crash the game.
- Vanilla's select/move code assumes the pack is still in its list. If a rescan removed it, moving it calls `remove(-1)` and the game crashes. The mod skips actions on these outdated rows.
- Errors from a single click in the menu are logged instead of closing the game.

### Lunar Client

Lunar Client shows packs in folders. Every time the pack list is rebuilt (opening the menu, and every select or remove), it looks up each pack's file with a separate recursive search of the whole pack folder. Built-in and mod packs aren't files, so each of those searches walks every folder. With a few hundred packs and some loose files, that adds up to millions of file checks. I measured a **53-second freeze** per click, which is long enough for servers to time you out.

Within one rebuild nothing in the folder changes, so the mod caches folder listings while Lunar searches: each folder is read once per rebuild instead of once per pack. The results are identical, just not recomputed. In testing, a rebuild went from **32.8 s to 0.57 s**. The cache is cleared at the start of every rebuild, so new or removed packs always show up.

This hook targets Lunar's current build. If a Lunar update changes it, the hook simply turns itself off; it never causes a crash.

### Freeze reports

A small watchdog thread checks whether the game is still ticking. If the main thread stops for more than 1.5 seconds, it samples what the thread is doing several times a second. For freezes of 3+ seconds it appends a short report to `config/mogy-optimizer-stalls.txt`: the methods that took the most time, plus two stack traces. When the game runs normally it does nothing. Turn it off with `freezeReports` if you don't want it.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for your Minecraft version.
2. **26.1.2 only:** also install [Fabric API](https://modrinth.com/mod/fabric-api).
3. Download the jar for your version from [Modrinth](https://modrinth.com/mod/mogy-optimizer) or [Releases](../../releases) and drop it into your `mods` folder.

For Lunar Client, put the jar in `.lunarclient/profiles/<profile>/mods/fabric-<version>/` and launch that version with Fabric enabled.

---

## Configuration

Settings are in `config/mogy-optimizer.json`, created on first launch. Every fix can be turned off on its own.

| Option | Default | What it does |
|--------|---------|--------------|
| `fullscreenMode` | `BORDERLESS` | `BORDERLESS` or `EXCLUSIVE` (vanilla). Use exclusive if you need a different fullscreen resolution than your desktop. |
| `borderlessOverscanPixels` | `1` | Extra pixels added to the borderless window's height (0â€“2). |
| `disableAutoIconify` | `true` | Keep fullscreen from minimizing when it loses focus. |
| `adaptiveVsync` | `true` | Use adaptive vsync when the driver supports it. |
| `asyncPackScan` | `true` | Rescan the pack folder in the background. |
| `cachePackMetadata` | `true` | Remember pack info between scans. |
| `quietPackScanLog` | `true` | Drop the per-file "non-pack entry" log line. |
| `cacheLauncherFolderSearch` | `true` | Lunar Client folder-view fix. |
| `asyncPackIcons` | `true` | Load pack icons in the background. |
| `preloadPackIcons` | `true` | Load all pack icons at startup so the menu has them ready. |
| `maxIconBytes` | `4194304` | Largest `pack.png` that will be decoded (bytes). |
| `iconCacheSize` | `512` | Number of icons kept in memory. |
| `iconUploadsPerTick` | `16` | Icons uploaded to the GPU per tick. |
| `freezeReports` | `true` | Write freeze reports for hangs of 3+ seconds. |

---

## Compatibility

- Works alongside Sodium, Iris, Mod Menu and Simple Voice Chat.
- Doesn't touch rendering, world loading or networking, so it should be compatible with most other mods.
- If another mod also changes fullscreen handling, set `fullscreenMode` to `EXCLUSIVE` to leave it alone.

---

## Building from source

Each branch is a standalone Gradle project.

```sh
git clone https://github.com/Mogystl/mogy-optimizer.git
cd mogy-optimizer
./gradlew build                # jar ends up in build/libs/
./gradlew runClientGameTest    # optional: in-game automated tests
```

- `main` (26.1.2) needs **JDK 25**.
- `1.21.11` needs **JDK 25** to run Gradle and builds with Java 21.

The client game tests start a real Minecraft client and check the pack menu (with 300+ generated packs, including broken ones), the Lunar search cache, borderless fullscreen and the freeze reports.

---

## License

[MIT](LICENSE). Lunar Client is a trademark of Moonsworth; this project is not affiliated with it.
