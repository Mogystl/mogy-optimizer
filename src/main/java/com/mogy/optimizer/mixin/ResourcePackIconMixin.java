package com.mogy.optimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mogy.optimizer.config.MogyConfig;
import com.mogy.optimizer.packs.PackIconCache;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.ResourcePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes pack icons lazy, small and cached (see {@link PackIconCache} for the root cause).
 *
 * <p>In 1.16.1 the icon is not loaded by the menu but by the pack itself: the
 * {@code ResourcePack} constructor decodes {@code pack.png} at full resolution for every
 * pack, during every folder scan (game start, pressing Done, drag-and-drop), and keeps the
 * image in memory until the next scan. Here, packs from the resource pack folder skip that
 * decode, and {@code bindIcon} asks the background icon cache instead. Built-in and server
 * packs keep vanilla behaviour; there are only a few of them and their icons are small.
 */
@Mixin(ResourcePack.class)
public abstract class ResourcePackIconMixin {
    @Unique
    private static final ResourceLocation MOGY$UNKNOWN_PACK = new ResourceLocation("textures/misc/unknown_pack.png");

    @Shadow private NativeImage icon;

    @WrapOperation(method = "<init>(Ljava/lang/String;ZLjava/util/function/Supplier;Lnet/minecraft/server/packs/PackResources;Lnet/minecraft/server/packs/metadata/pack/PackMetadataSection;Lnet/minecraft/server/packs/repository/Pack$Position;Lnet/minecraft/server/packs/repository/PackSource;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/ResourcePack;readIcon(Lnet/minecraft/server/packs/PackResources;)Lcom/mojang/blaze3d/platform/NativeImage;"))
    private static NativeImage mogy$lazyIcon(PackResources resources, Operation<NativeImage> original,
                                             @Local(argsOnly = true) String id) {
        if (MogyConfig.get().asyncPackIcons && PackIconCache.isFolderPack(id)) {
            return null; // decoded later, in the background and downscaled, by PackIconCache
        }
        return original.call(resources);
    }

    @Inject(method = "bindIcon", at = @At("HEAD"), cancellable = true)
    private void mogy$bindCachedIcon(TextureManager textures, CallbackInfo ci) {
        ResourcePack self = (ResourcePack) (Object) this;
        if (this.icon == null && MogyConfig.get().asyncPackIcons && PackIconCache.isFolderPack(self.getId())) {
            textures.bind(PackIconCache.get(self, Minecraft.getInstance().getResourcePackDirectory().toPath(), MOGY$UNKNOWN_PACK));
            ci.cancel();
        }
    }
}
