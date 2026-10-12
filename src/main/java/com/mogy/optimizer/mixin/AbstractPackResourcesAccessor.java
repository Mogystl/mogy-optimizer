package com.mogy.optimizer.mixin;

import net.minecraft.server.packs.AbstractPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.io.File;

/** The zip file or folder behind a pack from the pack folder, used as the metadata cache key. */
@Mixin(AbstractPackResources.class)
public interface AbstractPackResourcesAccessor {
    @Accessor("file")
    File mogy$getFile();
}
