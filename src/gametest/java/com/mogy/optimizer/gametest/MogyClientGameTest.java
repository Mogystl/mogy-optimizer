package com.mogy.optimizer.gametest;

import com.mogy.optimizer.packs.PackIconCache;
import com.mogy.optimizer.packs.PackScanService;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.packs.TransferableSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.PackSource;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end checks in a real client. Run with {@code ./gradlew runClientGameTest}.
 * <ol>
 *   <li>Resource pack menu: opens instantly with 300+ packs, survives click spam during
 *       the background scan, loads icons asynchronously, handles corrupt/huge icons and
 *       packs deleted while the menu is open.</li>
 *   <li>Display: auto-iconify is off, and fullscreen is borderless (no monitor attached,
 *       undecorated, covering the monitor) and restores cleanly.</li>
 * </ol>
 */
public class MogyClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("Mogy GameTest");
    private static final int PACK_COUNT = 300;

    @Override
    public void runTest(ClientGameTestContext context) {
        benchmarkRealPackFolder(context);
        testFreezeRecorder(context);
        testLauncherFolderSearchCache(context);
        testResourcePackMenu(context);
        testBorderlessFullscreen(context);
    }

    /**
     * Optional: set the environment variable MOGY_BENCH_PACK_DIR to a real resource pack
     * folder to time a cold and a warm scan of it. The folder is only read, never modified.
     */
    private void benchmarkRealPackFolder(ClientGameTestContext context) {
        String dir = System.getenv("MOGY_BENCH_PACK_DIR");
        if (dir == null || dir.isBlank()) {
            return;
        }
        Path path = Path.of(dir);
        context.runOnClient(client -> {
            FolderRepositorySource source = new FolderRepositorySource(path, PackType.CLIENT_RESOURCES,
                    PackSource.DEFAULT, client.directoryValidator());
            for (String pass : new String[]{"cold", "warm", "warm"}) {
                int[] count = {0};
                long start = System.nanoTime();
                source.loadPacks(pack -> count[0]++);
                LOGGER.info("BENCH {} scan of {}: {} packs in {} ms", pass, path, count[0], (System.nanoTime() - start) / 1_000_000);
            }
        });
    }

    /**
     * Lunar-style pack menu rebuild: one recursive search per pack over a folder with many
     * loose files. Without the cache every search walks the tree; with it each folder is read
     * once. Results must be identical.
     */
    private void testLauncherFolderSearchCache(ClientGameTestContext context) {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "mogy-lunar-search-test");
        try {
            if (Files.exists(root)) {
                PackFixtures.deleteRecursively(root);
            }
            Files.createDirectories(root);
            // 150 top-level packs + an unzipped pack with 200 folders x 30 files (6,000 loose files).
            for (int i = 0; i < 150; i++) {
                Files.write(root.resolve("pack_" + i + ".zip"), new byte[]{0});
            }
            for (int d = 0; d < 200; d++) {
                Path folder = Files.createDirectories(root.resolve("assets/minecraft/textures/block/set_" + d));
                for (int f = 0; f < 30; f++) {
                    Files.write(folder.resolve("tex_" + f + ".png"), new byte[]{0});
                }
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        // What a list rebuild looks up: every real pack, plus 60 built-in/mod packs that are not files.
        java.util.List<String> lookups = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            lookups.add("file/pack_" + i + ".zip");
        }
        for (int i = 0; i < 60; i++) {
            lookups.add("fabric-mod-" + i);
        }
        java.io.File dir = root.toFile();

        com.mogy.optimizer.config.MogyConfig.get().cacheLauncherFolderSearch = false;
        long start = System.nanoTime();
        java.util.List<java.io.File> uncached = lookups.stream()
                .map(name -> com.moonsworth.lunar.client.util.CCHHOICHIIRIHCCRIRHHOIHOIOHCOH.ICCRRCCIOOIRICIHHCIOROHHROOHHR(dir, name)).toList();
        long uncachedMs = (System.nanoTime() - start) / 1_000_000;

        com.mogy.optimizer.config.MogyConfig.get().cacheLauncherFolderSearch = true;
        com.mogy.optimizer.packs.DirectoryListingCache.invalidate();
        start = System.nanoTime();
        java.util.List<java.io.File> cached = lookups.stream()
                .map(name -> com.moonsworth.lunar.client.util.CCHHOICHIIRIHCCRIRHHOIHOIOHCOH.ICCRRCCIOOIRICIHHCIOROHHROOHHR(dir, name)).toList();
        long cachedMs = (System.nanoTime() - start) / 1_000_000;

        LOGGER.info("Lunar-style list rebuild ({} lookups): {} ms without cache, {} ms with cache; {}",
                lookups.size(), uncachedMs, cachedMs, com.mogy.optimizer.packs.DirectoryListingCache.stats());
        if (!uncached.equals(cached)) {
            throw new AssertionError("Cached folder search returned different files");
        }
        if (cachedMs * 5 > uncachedMs) {
            throw new AssertionError("Folder search cache did not speed up the rebuild enough");
        }
        LOGGER.info("FOLDER SEARCH CACHE TEST PASSED");
    }

    /** Freezes the render thread on purpose and checks that a freeze report naming the cause is written. */
    private void testFreezeRecorder(ClientGameTestContext context) {
        Path report = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mogy-optimizer-stalls.txt");
        try {
            Files.deleteIfExists(report);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        context.runOnClient(client -> deliberateFreeze());
        context.waitTicks(20);
        try {
            String text = Files.exists(report) ? Files.readString(report) : "";
            if (!text.contains("deliberateFreeze")) {
                throw new AssertionError("Freeze report missing or does not name the frozen method:\n" + text);
            }
            LOGGER.info("FREEZE RECORDER TEST PASSED:\n{}", text.lines().limit(8).reduce("", (a, b) -> a + b + "\n"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void deliberateFreeze() {
        try {
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void testResourcePackMenu(ClientGameTestContext context) {
        Path packDir = context.computeOnClient(Minecraft::getResourcePackDirectory);
        int expected = PackFixtures.generate(packDir, PACK_COUNT, 10);
        LOGGER.info("Generated {} test packs in {}", expected, packDir);

        // Baseline: what vanilla does on the render thread every time the menu opens.
        long vanillaReloadMs = context.computeOnClient(client -> {
            long start = System.nanoTime();
            client.getResourcePackRepository().reload();
            return (System.nanoTime() - start) / 1_000_000;
        });
        int discovered = context.computeOnClient(client -> client.getResourcePackRepository().getAvailablePacks().size());
        LOGGER.info("Cold PackRepository.reload() (packs never seen) took {} ms ({} packs)", vanillaReloadMs, discovered);

        // Same scan again: unchanged packs now come from the metadata cache.
        long warmReloadMs = context.computeOnClient(client -> {
            long start = System.nanoTime();
            client.getResourcePackRepository().reload();
            return (System.nanoTime() - start) / 1_000_000;
        });
        LOGGER.info("Warm PackRepository.reload() (metadata cached) took {} ms", warmReloadMs);
        if (warmReloadMs >= vanillaReloadMs) {
            throw new AssertionError("Metadata cache did not speed up the rescan (" + warmReloadMs + " ms >= " + vanillaReloadMs + " ms)");
        }

        // Open the menu exactly like OptionsScreen does, timing the render-thread cost.
        long openMs = context.computeOnClient(client -> {
            long start = System.nanoTime();
            client.setScreen(new PackSelectionScreen(client.getResourcePackRepository(), repository -> {
            }, packDir, Component.translatable("resourcePack.title")));
            return (System.nanoTime() - start) / 1_000_000;
        });
        LOGGER.info("Resource pack menu opened in {} ms on the render thread", openMs);

        // The lists must be filled on the very first frame, not after the background scan.
        int rowsAtOpen = context.computeOnClient(MogyClientGameTest::countPackRows);
        LOGGER.info("Pack rows visible immediately after opening: {}", rowsAtOpen);
        if (rowsAtOpen < PACK_COUNT) {
            throw new AssertionError("Menu opened with only " + rowsAtOpen + " rows; expected the known packs immediately");
        }

        // Spam clicks across both lists while the background scan and icon decode run.
        spamClicks(context, 40);
        assertScreen(context, "after click spam during scan");

        // Only the rows on screen request icons, so wait for those first.
        context.waitFor(client -> PackIconCache.loadedIconCount() >= 3, 20 * 30);
        int iconsBeforeScroll = context.computeOnClient(client -> PackIconCache.loadedIconCount());
        LOGGER.info("Icons loaded asynchronously for visible rows: {}", iconsBeforeScroll);
        context.takeScreenshot("mogy_packs_loaded");

        // Scroll down into the generated packs (2048 px icons among them): new rows must
        // request their icons lazily and get them without blocking.
        int[] size = windowSize(context);
        context.getInput().setCursorPos(size[0] * 0.25, size[1] * 0.5);
        for (int i = 0; i < 15; i++) {
            context.getInput().scroll(-10);
            context.waitTick();
        }
        context.waitFor(client -> PackIconCache.loadedIconCount() > iconsBeforeScroll, 20 * 30);
        context.waitTicks(20);
        int iconsAfterScroll = context.computeOnClient(client -> PackIconCache.loadedIconCount());
        LOGGER.info("Icons after scrolling: {}", iconsAfterScroll);
        context.takeScreenshot("mogy_packs_scrolled");

        // Reopen: the menu is full immediately, and the background rescan (needed by launchers
        // that build their own pack view from it) still runs and completes.
        context.waitTicks(40);
        int scansBefore = PackScanService.completedScans();
        context.setScreen(() -> new PackSelectionScreen(Minecraft.getInstance().getResourcePackRepository(), repository -> {
        }, packDir, Component.translatable("resourcePack.title")));
        int rowsOnReopen = context.computeOnClient(MogyClientGameTest::countPackRows);
        context.waitFor(client -> PackScanService.completedScans() > scansBefore, 20 * 10);
        LOGGER.info("Reopen: {} rows immediately, background rescan completed", rowsOnReopen);
        if (rowsOnReopen < PACK_COUNT) {
            throw new AssertionError("Reopen should show the packs immediately (rows=" + rowsOnReopen + ")");
        }

        // Request the corrupt icon and the "30000x30000" icon directly. Both must resolve to
        // the default icon without crashing (vanilla would try to decode the huge one).
        context.runOnClient(client -> client.getResourcePackRepository().getAvailablePacks().stream()
                .filter(pack -> pack.getId().contains("corrupt_icon") || pack.getId().contains("bomb_icon"))
                .forEach(pack -> PackIconCache.get(pack, packDir, Identifier.withDefaultNamespace("textures/misc/unknown_pack.png"))));
        context.waitTicks(40);
        assertScreen(context, "after loading broken icons");

        // Delete most packs while the menu is open: the folder watcher triggers a background
        // rescan, and rows still on screen become stale. Clicking them must not crash.
        deletePacks(packDir, 150);
        spamClicks(context, 60);
        context.waitTicks(60);
        assertScreen(context, "after deleting packs while open");
        int remaining = context.computeOnClient(client -> client.getResourcePackRepository().getAvailablePacks().size());
        LOGGER.info("Repository after rescan: {} packs (was {})", remaining, discovered);
        if (remaining >= discovered) {
            throw new AssertionError("Background rescan did not pick up deleted packs (" + remaining + " >= " + discovered + ")");
        }

        // Close without committing (no resource reload needed for the test) and clean up.
        context.setScreen(() -> null);
        try {
            PackFixtures.clean(packDir);
        } catch (IOException e) {
            LOGGER.warn("Could not clean test packs", e);
        }
        context.runOnClient(client -> client.getResourcePackRepository().reload());
        LOGGER.info("PACK MENU TEST PASSED: open {} ms vs vanilla reload {} ms", openMs, vanillaReloadMs);
    }

    private void testBorderlessFullscreen(ClientGameTestContext context) {
        context.runOnClient(client -> {
            long handle = client.getWindow().handle();
            int autoIconify = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_AUTO_ICONIFY);
            LOGGER.info("GLFW_AUTO_ICONIFY = {}", autoIconify);
            if (autoIconify != GLFW.GLFW_FALSE) {
                throw new AssertionError("Auto-iconify should be disabled");
            }
            client.getWindow().toggleFullScreen();
        });
        context.waitTicks(20);
        context.runOnClient(client -> {
            Window window = client.getWindow();
            long handle = window.handle();
            long monitor = GLFW.glfwGetWindowMonitor(handle);
            int decorated = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_DECORATED);
            int[] w = new int[1];
            int[] h = new int[1];
            GLFW.glfwGetWindowSize(handle, w, h);
            LOGGER.info("Fullscreen: monitor={} decorated={} size={}x{} isFullscreen={}", monitor, decorated, w[0], h[0], window.isFullscreen());
            if (!window.isFullscreen() || monitor != 0L || decorated != GLFW.GLFW_FALSE) {
                throw new AssertionError("Expected borderless fullscreen (no monitor, undecorated)");
            }
        });
        context.takeScreenshot("mogy_borderless");

        context.runOnClient(client -> {
            client.getWindow().toggleFullScreen();
        });
        context.waitTicks(20);
        context.runOnClient(client -> {
            long handle = client.getWindow().handle();
            int decorated = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_DECORATED);
            int[] w = new int[1];
            int[] h = new int[1];
            GLFW.glfwGetWindowSize(handle, w, h);
            LOGGER.info("Windowed again: decorated={} size={}x{}", decorated, w[0], h[0]);
            if (client.getWindow().isFullscreen() || decorated != GLFW.GLFW_TRUE) {
                throw new AssertionError("Expected decorated windowed mode after leaving fullscreen");
            }
        });
        LOGGER.info("DISPLAY TEST PASSED");
    }

    private static void spamClicks(ClientGameTestContext context, int clicks) {
        int[] size = windowSize(context);
        for (int i = 0; i < clicks; i++) {
            double x = size[0] * (i % 2 == 0 ? 0.25 : 0.75);
            double y = size[1] * (0.25 + 0.5 * ((i * 7) % 10) / 10.0);
            context.getInput().setCursorPos(x, y);
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
    }

    /** Number of pack rows across both lists of the open resource pack menu. */
    private static int countPackRows(Minecraft client) {
        if (!(client.screen instanceof PackSelectionScreen screen)) {
            return 0;
        }
        int rows = 0;
        for (var child : screen.children()) {
            if (child instanceof TransferableSelectionList list) {
                for (var entry : list.children()) {
                    if (entry instanceof TransferableSelectionList.PackEntry) {
                        rows++;
                    }
                }
            }
        }
        return rows;
    }

    private static int[] windowSize(ClientGameTestContext context) {
        return context.computeOnClient(client -> new int[]{client.getWindow().getScreenWidth(), client.getWindow().getScreenHeight()});
    }

    private static void assertScreen(ClientGameTestContext context, String when) {
        // Random clicks may legitimately open vanilla's "incompatible pack" confirmation.
        String screen = context.computeOnClient(client -> client.screen == null ? "none" : client.screen.getClass().getSimpleName());
        boolean open = context.computeOnClient(client -> client.screen instanceof PackSelectionScreen || client.screen instanceof ConfirmScreen);
        if (!open) {
            throw new AssertionError("Resource pack menu is no longer open " + when + " (screen: " + screen + ")");
        }
    }

    private static void deletePacks(Path dir, int count) {
        for (int i = 0; i < count; i++) {
            try {
                Files.deleteIfExists(dir.resolve(PackFixtures.PREFIX + String.format("%03d", i) + ".zip"));
            } catch (IOException e) {
                LOGGER.warn("Could not delete test pack {}", i, e);
            }
        }
    }
}
