package com.mogy.optimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mogy.optimizer.packs.PackMetadataCache;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;

/** Routes pack metadata reads through {@link PackMetadataCache} (see there for the root cause). */
@Mixin(Pack.class)
public abstract class PackMetadataCacheMixin {
    @WrapMethod(method = "readPackMetadata")
    private static Pack.Metadata mogy$cachedMetadata(PackLocationInfo location, Pack.ResourcesSupplier supplier,
                                                     PackFormat format, PackType type, Operation<Pack.Metadata> original) {
        return PackMetadataCache.getOrLoad(location, supplier, format, type,
                () -> original.call(location, supplier, format, type));
    }
}
