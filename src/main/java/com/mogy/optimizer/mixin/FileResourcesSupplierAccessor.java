package com.mogy.optimizer.mixin;

import net.minecraft.server.packs.FilePackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.io.File;

/** The zip file behind a zip pack, used as the metadata cache key. */
@Mixin(FilePackResources.FileResourcesSupplier.class)
public interface FileResourcesSupplierAccessor {
    @Accessor("content")
    File mogy$getContent();
}
