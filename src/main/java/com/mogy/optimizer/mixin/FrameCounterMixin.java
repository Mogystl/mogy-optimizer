package com.mogy.optimizer.mixin;

import com.mogy.optimizer.diagnostics.StallWatchdog;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts frames for the low-FPS recorder in {@link StallWatchdog}. One increment per frame. */
@Mixin(Minecraft.class)
public abstract class FrameCounterMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void mogy$countFrame(boolean advanceGameTime, CallbackInfo ci) {
        StallWatchdog.frame();
    }
}
