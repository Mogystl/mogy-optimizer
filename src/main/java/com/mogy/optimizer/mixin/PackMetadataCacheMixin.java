package com.mogy.optimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mogy.optimizer.packs.PackMetadataCache;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Routes the {@code pack.mcmeta} read in {@code Pack.create} through {@link PackMetadataCache} (see there for the root cause). */
@Mixin(Pack.class)
public abstract class PackMetadataCacheMixin {
    @WrapOperation(method = "create", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/PackResources;getMetadataSection(Lnet/minecraft/server/packs/metadata/MetadataSectionSerializer;)Ljava/lang/Object;"))
    private static Object mogy$cachedMetadata(PackResources resources, MetadataSectionSerializer<?> serializer,
                                              Operation<Object> original) {
        return PackMetadataCache.getOrLoad(resources, serializer, () -> original.call(resources, serializer));
    }
}
