package com.mogy.optimizer.mixin;

import com.mogy.optimizer.MogyOptimizerClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client lifecycle points the mod needs (per tick and shutdown; "startup finished" is
 * detected from the tick hook). They are hooked directly instead of through Fabric API, so
 * this build needs only Fabric Loader.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftLifecycleMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void mogy$endOfTick(CallbackInfo ci) {
        MogyOptimizerClient.onEndTick((Minecraft) (Object) this);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void mogy$stopping(CallbackInfo ci) {
        MogyOptimizerClient.onClientStopping();
    }
}
