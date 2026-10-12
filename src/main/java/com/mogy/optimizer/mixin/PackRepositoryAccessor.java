package com.mogy.optimizer.mixin;

import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lets the async pack scan split {@code PackRepository.reload()} in two: discovery
 * ({@code sources}) runs on the worker, and the field swap ({@code available},
 * {@code selected}, {@code rebuildSelected}) runs on the render thread. In 1.16.1 the
 * repository is generic over its pack type, so the raw {@link Pack} type is used here.
 */
@SuppressWarnings("rawtypes")
@Mixin(PackRepository.class)
public interface PackRepositoryAccessor {
    @Accessor("sources")
    Set<RepositorySource> mogy$getSources();

    @Accessor("constructor")
    Pack.PackConstructor mogy$getConstructor();

    @Accessor("available")
    Map<String, Pack> mogy$getAvailable();

    @Accessor("available")
    void mogy$setAvailable(Map<String, Pack> available);

    @Accessor("selected")
    List<Pack> mogy$getSelected();

    @Accessor("selected")
    void mogy$setSelected(List<Pack> selected);

    @Invoker("rebuildSelected")
    List<Pack> mogy$rebuildSelected(Collection<String> selectedIds);
}
