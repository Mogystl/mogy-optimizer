package com.mogy.optimizer.gametest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a worst-case resource pack folder: hundreds of zips, oversized icons, and
 * deliberately broken packs that crash or stall vanilla's menu.
 */
final class PackFixtures {
    static final String PREFIX = "mogytest_";

    private PackFixtures() {
    }

    /** Creates the fixture packs and returns how many valid packs were written. */
    static int generate(Path dir, int zipCount, int bigIconEvery) {
        try {
            Files.createDirectories(dir);
            clean(dir);
            byte[] bigIcon = png(2048, 2048, 0x3366CC);   // what many real packs ship
            byte[] smallIcon = png(128, 128, 0xCC6633);
            byte[] mcmeta = """
                    {"pack": {"description": "Mogy stress test pack", "min_format": 65, "max_format": 999}}
                    """.getBytes(StandardCharsets.UTF_8);

            int valid = 0;
            for (int i = 0; i < zipCount; i++) {
                byte[] icon = i % bigIconEvery == 0 ? bigIcon : smallIcon;
                Files.write(dir.resolve(PREFIX + String.format("%03d", i) + ".zip"), zip(mcmeta, icon));
                valid++;
            }
            // Folder packs exercise the directory branch of the icon cache key.
            for (int i = 0; i < 5; i++) {
                Path folder = Files.createDirectories(dir.resolve(PREFIX + "folder_" + i));
                Files.write(folder.resolve("pack.mcmeta"), mcmeta);
                Files.write(folder.resolve("pack.png"), smallIcon);
                valid++;
            }
            // Broken icon: random bytes named pack.png.
            byte[] garbage = new byte[4096];
            new Random(42).nextBytes(garbage);
            Files.write(dir.resolve(PREFIX + "corrupt_icon.zip"), zip(mcmeta, garbage));
            valid++;
            // "Decompression bomb": a PNG header claiming 30000x30000 pixels.
            Files.write(dir.resolve(PREFIX + "bomb_icon.zip"), zip(mcmeta, fakeHugePngHeader()));
            valid++;
            // Truncated archive: vanilla logs and skips it; it must not break the scan.
            byte[] whole = zip(mcmeta, smallIcon);
            Files.write(dir.resolve(PREFIX + "truncated.zip"), java.util.Arrays.copyOf(whole, whole.length / 2));
            return valid;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void clean(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                if (path.getFileName().toString().startsWith(PREFIX)) {
                    deleteRecursively(path);
                }
            }
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> children = Files.list(path)) {
                for (Path child : (Iterable<Path>) children::iterator) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private static byte[] zip(byte[] mcmeta, byte[] icon) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write(mcmeta);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("pack.png"));
            zip.write(icon);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] png(int width, int height, int rgb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int shade = (x * 255 / width) ^ (y * 255 / height);
                image.setRGB(x, y, 0xFF000000 | (rgb & 0xFFFF00) | (shade & 0xFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] fakeHugePngHeader() {
        byte[] header = new byte[64];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        System.arraycopy(signature, 0, header, 0, signature.length);
        int size = 30000;
        for (int offset : new int[]{16, 20}) {
            header[offset] = (byte) (size >>> 24);
            header[offset + 1] = (byte) (size >>> 16);
            header[offset + 2] = (byte) (size >>> 8);
            header[offset + 3] = (byte) size;
        }
        return header;
    }
}
