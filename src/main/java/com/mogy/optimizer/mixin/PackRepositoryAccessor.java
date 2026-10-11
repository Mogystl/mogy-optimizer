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
 * {@code selected}, {@code rebuildSelected}) runs on the render thread.
 */
@Mixin(PackRepository.class)
public interface PackRepositoryAccessor {
    @Accessor("sources")
    Set<RepositorySource> mogy$getSources();

    @Accessor("available")
    void mogy$setAvailable(Map<String, Pack> available);

    @Accessor("selected")
    List<Pack> mogy$getSelected();

    @Accessor("selected")
    void mogy$setSelected(List<Pack> selected);

    @Invoker("rebuildSelected")
    List<Pack> mogy$rebuildSelected(Collection<String> selectedIds);
}
