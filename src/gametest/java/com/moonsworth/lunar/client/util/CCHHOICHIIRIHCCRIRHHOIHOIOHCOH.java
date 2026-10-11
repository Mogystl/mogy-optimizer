package com.moonsworth.lunar.client.util;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.util.Objects;

/**
 * Test fixture (gametest source set only, not part of the mod jar).
 *
 * <p>Mirrors the class name, method name and search order of Lunar Client's pack-file
 * lookup, so the client game test can check that {@code LunarFolderSearchMixin} attaches
 * to it and that cached folder listings give exactly the same results, faster.
 */
public final class CCHHOICHIIRIHCCRIRHHOIHOIOHCOH {
    private static final FileFilter FILES = file -> file.isFile() || new File(file, "pack.mcmeta").isFile();
    private static final FilenameFilter FOLDERS = (dir, name) -> new File(dir, name).isDirectory();

    private CCHHOICHIIRIHCCRIRHHOIHOIOHCOH() {
    }

    /** Depth-first search for a pack named {@code name} (or "file/" + name) below {@code dir}. */
    public static File ICCRRCCIOOIRICIHHCIOROHHROOHHR(File dir, String name) {
        File[] files = Objects.requireNonNullElse(dir.listFiles(FILES), new File[0]);
        for (File file : files) {
            if (file.getName().equals(name) || name.equals("file/" + file.getName())) {
                return file;
            }
        }
        File[] folders = Objects.requireNonNullElse(dir.listFiles(FOLDERS), new File[0]);
        for (File folder : folders) {
            File found = ICCRRCCIOOIRICIHHCIOROHHROOHHR(folder, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
