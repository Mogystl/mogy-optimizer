package com.mogy.optimizer.mixin;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 1.16.1's {@code TextureManager.release()} only frees the GL texture id: the texture object,
 * and the image a {@code DynamicTexture} holds, stay registered forever. The icon cache needs
 * to really drop textures it evicts, so it removes them from this map itself.
 */
@Mixin(TextureManager.class)
public interface TextureManagerAccessor {
    @Accessor("byPath")
    Map<ResourceLocation, AbstractTexture> mogy$getByPath();
}
