package com.mogy.optimizer.mixin;

import com.mogy.optimizer.boost.FpsBooster;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shorter render distance for chests, signs, banners, heads and other block entities (see
 * {@link FpsBooster}). {@code tryExtractRenderState} is where vanilla decides whether a block
 * entity is drawn this frame (it returns null to skip one), so returning null early skips it
 * the same way vanilla's own 64-block check does.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityCullingMixin {
    @Shadow
    private Vec3 cameraPos;

    @Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity, S extends BlockEntityRenderState> void mogy$cullDistantBlockEntities(
            E blockEntity, float partialTick, ModelFeatureRenderer.CrumblingOverlay crumbling, CallbackInfoReturnable<S> cir) {
        BlockEntityRenderDispatcher self = (BlockEntityRenderDispatcher) (Object) this;
        if (FpsBooster.shouldCullBlockEntity(blockEntity, self.getRenderer(blockEntity), this.cameraPos)) {
            cir.setReturnValue(null);
        }
    }
}
