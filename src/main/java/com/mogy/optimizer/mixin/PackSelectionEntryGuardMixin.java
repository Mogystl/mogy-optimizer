package com.mogy.optimizer.mixin;

import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Refuses to select, unselect or move a pack that is no longer in the list its row belongs to.
 *
 * <h2>Root cause</h2>
 * {@code PackSelectionModel.EntryBase.toggleSelection()} removes the pack from its own
 * list and then inserts it into the other list <em>unconditionally</em>. {@code move()}
 * uses {@code indexOf()} on its own list. A row whose pack was dropped by a rescan can
 * still be acted on, for example by confirming the "incompatible pack" dialog after a
 * drag-and-drop or background rescan changed the folder underneath it. The toggle then duplicates the pack, and the move calls
 * {@code remove(-1)}, which throws {@link IndexOutOfBoundsException} and crashes the
 * client.
 *
 * <h2>Fix</h2>
 * Both operations are skipped when the pack is not in its own list. The row is already
 * stale and the next list rebuild replaces it, so skipping is the correct outcome.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.packs.PackSelectionModel$EntryBase")
public abstract class PackSelectionEntryGuardMixin {
    @Shadow @Final private Pack pack;

    @Shadow
    protected abstract List<Pack> getSelfList();

    @Inject(method = {"toggleSelection", "move"}, at = @At("HEAD"), cancellable = true)
    private void mogy$skipStaleEntry(CallbackInfo ci) {
        if (!this.getSelfList().contains(this.pack)) {
            ci.cancel();
        }
    }
}
