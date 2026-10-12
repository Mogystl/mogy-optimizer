package com.mogy.optimizer.mixin;

import com.mogy.optimizer.boost.FpsBooster;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per-category entity render distances (see {@link FpsBooster}). {@code shouldRender} is the
 * single check vanilla makes before preparing and drawing an entity, so skipping here saves
 * the whole cost: model, name tag, shadow and render state.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityCullingMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void mogy$cullDistantEntities(E entity, Frustum frustum, double cameraX, double cameraY,
                                                             double cameraZ, CallbackInfoReturnable<Boolean> cir) {
        if (FpsBooster.shouldCullEntity(entity, cameraX, cameraY, cameraZ)) {
            cir.setReturnValue(false);
        }
    }
}
