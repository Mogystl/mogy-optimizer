package com.mogy.optimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mogy.optimizer.packs.DirectoryListingCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;

/**
 * Makes Lunar Client's per-pack folder search reuse directory listings (see
 * {@link DirectoryListingCache} for the root cause).
 *
 * <p>The target is Lunar's recursive {@code findFile(File dir, String name)} helper. Lunar
 * scrambles its class and method names, so they are matched literally as found in Lunar's
 * 26.1.2 build. {@link Pseudo} and {@code require = 0} make this a no-op when Lunar is not
 * installed, or when a Lunar update renames the helper: the game then behaves exactly as
 * without this hook, it never crashes.
 */
@Pseudo
@Mixin(targets = "com.moonsworth.lunar.client.util.CCHHOICHIIRIHCCRIRHHOIHOIOHCOH")
public abstract class LunarFolderSearchMixin {
    private static final String FIND_FILE = "ICCRRCCIOOIRICIHHCIOROHHROOHHR(Ljava/io/File;Ljava/lang/String;)Ljava/io/File;";

    @WrapOperation(method = FIND_FILE, require = 0, at = @At(value = "INVOKE",
            target = "Ljava/io/File;listFiles(Ljava/io/FileFilter;)[Ljava/io/File;"))
    private static File[] mogy$cachedFileListing(File directory, FileFilter filter, Operation<File[]> original) {
        return DirectoryListingCache.list(directory, filter, () -> original.call(directory, filter));
    }

    @WrapOperation(method = FIND_FILE, require = 0, at = @At(value = "INVOKE",
            target = "Ljava/io/File;listFiles(Ljava/io/FilenameFilter;)[Ljava/io/File;"))
    private static File[] mogy$cachedFolderListing(File directory, FilenameFilter filter, Operation<File[]> original) {
        return DirectoryListingCache.list(directory, filter, () -> original.call(directory, filter));
    }
}
