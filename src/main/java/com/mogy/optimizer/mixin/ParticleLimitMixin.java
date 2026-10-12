package com.mogy.optimizer.mixin;

import com.mogy.optimizer.boost.FpsBooster;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;

/**
 * Lower particle cap per particle type (see {@link FpsBooster}). Vanilla keeps each type in a
 * rolling queue of 16,384 that drops the oldest particle when full; this applies the same rule
 * with a smaller limit, so new particles always appear and only the oldest ones are dropped.
 */
@Mixin(ParticleGroup.class)
public abstract class ParticleLimitMixin {
    @Shadow
    @Final
    protected Queue<Particle> particles;

    @Inject(method = "add", at = @At("HEAD"))
    private void mogy$capParticles(Particle particle, CallbackInfo ci) {
        int cap = FpsBooster.particleCap();
        if (cap > 0) {
            while (this.particles.size() >= cap) {
                this.particles.poll();
            }
        }
    }
}
