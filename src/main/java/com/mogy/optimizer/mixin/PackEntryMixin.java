package com.mogy.optimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mogy.optimizer.MogyOptimizerClient;
import net.minecraft.client.gui.screens.packs.TransferableSelectionList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Last line of defence for input on a resource pack row.
 *
 * <p>A click or key press on a row selects, unselects or moves a pack. If anything in that
 * chain throws (a pack whose files vanished, a mod-added pack source misbehaving), vanilla
 * lets the exception reach the game loop and the client crashes. Here the exception is
 * logged and the input is consumed: the menu stays open, and the next rescan shows the
 * real state of the folder.
 *
 * <p>The structural fix for stale rows (rows that refer to a pack an earlier rescan
 * removed) is in {@link PackSelectionEntryGuardMixin}. This wrapper only catches what
 * slips through.
 */
@Mixin(TransferableSelectionList.PackEntry.class)
public abstract class PackEntryMixin {
    @WrapMethod(method = "mouseClicked")
    private boolean mogy$safeMouseClicked(MouseButtonEvent event, boolean doubleClick, Operation<Boolean> original) {
        try {
            return original.call(event, doubleClick);
        } catch (RuntimeException e) {
            MogyOptimizerClient.LOGGER.error("Ignored a failing click in the resource pack menu", e);
            return true;
        }
    }

    @WrapMethod(method = "keyPressed")
    private boolean mogy$safeKeyPressed(KeyEvent event, Operation<Boolean> original) {
        try {
            return original.call(event);
        } catch (RuntimeException e) {
            MogyOptimizerClient.LOGGER.error("Ignored a failing key press in the resource pack menu", e);
            return true;
        }
    }
}
