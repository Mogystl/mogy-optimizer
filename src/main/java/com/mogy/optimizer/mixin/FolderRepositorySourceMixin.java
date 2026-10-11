package com.mogy.optimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mogy.optimizer.config.MogyConfig;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Silences the "Found non-pack entry '...', ignoring" message.
 *
 * <p>Vanilla logs it for every file in the pack folder that is not a pack. Launchers that
 * scan sub-folders recursively (Lunar does) run this for every folder, so a pack that was
 * unzipped into the folder produces one log line per texture: thousands per scan. Every
 * line goes through the game's shared logger, which the render thread also uses, so the
 * flood shows up as in-game stutter. The message carries no useful information, so it is
 * dropped. Real problems (unreadable files, symlink warnings) are still logged.
 */
@Mixin(FolderRepositorySource.class)
public abstract class FolderRepositorySourceMixin {
    @WrapOperation(method = "discoverPacks", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;)V"))
    private static void mogy$skipNonPackEntryLog(Logger logger, String message, Object path, Operation<Void> original) {
        if (!MogyConfig.get().quietPackScanLog) {
            original.call(logger, message, path);
        }
    }
}
