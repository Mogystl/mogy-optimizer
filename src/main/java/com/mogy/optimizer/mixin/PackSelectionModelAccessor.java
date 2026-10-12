package com.mogy.optimizer.mixin;

import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Access to the menu's working lists, so a finished scan can be merged exactly as {@code findNewPacks()} does. */
@SuppressWarnings("rawtypes")
@Mixin(PackSelectionModel.class)
public interface PackSelectionModelAccessor {
    @Accessor("repository")
    PackRepository mogy$getRepository();

    @Accessor("selected")
    List<Pack> mogy$getSelected();

    @Accessor("unselected")
    List<Pack> mogy$getUnselected();
}
