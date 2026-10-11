package com.mogy.optimizer.mixin;

import net.minecraft.server.packs.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

/** The folder behind a folder pack, used as the metadata cache key. */
@Mixin(PathPackResources.PathResourcesSupplier.class)
public interface PathResourcesSupplierAccessor {
    @Accessor("content")
    Path mogy$getContent();
}
