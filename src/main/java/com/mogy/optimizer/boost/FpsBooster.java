package com.mogy.optimizer.boost;

import com.mogy.optimizer.config.MogyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * FPS booster: skips drawing things that cost frame time but that you can't make out at
 * a distance anyway.
 *
 * <h2>Where the frame time goes</h2>
 * Sodium already makes terrain cheap. On servers, what is left is mostly everything that
 * is drawn one object at a time:
 * <ul>
 *   <li><b>Decorations</b>: lobbies stack hundreds of armor stands and text displays as
 *       holograms, plus item frames and paintings. Vanilla draws them (and their name tags)
 *       as far away as mobs.</li>
 *   <li><b>Block entities</b>: chests, signs (with their text), banners, heads and so on
 *       are drawn up to 64 blocks away.</li>
 *   <li><b>Particles</b>: vanilla keeps up to 16,384 per particle type. Totem pops and
 *       explosions in PvP fill that up quickly, and every particle is updated and drawn
 *       each frame.</li>
 * </ul>
 *
 * <h2>What changes</h2>
 * Each category gets its own render distance (and particles a lower cap). Dropped items
 * and XP orbs are left alone because vanilla already hides them beyond 16 and 32 blocks.
 * Things inside
 * that distance look exactly as before. Players are never hidden, and neither is anything
 * glowing or anything you are riding. Every limit can be set to 0 to restore vanilla.
 *
 * <p>All checks are a type test and one squared-distance comparison, so the booster costs
 * far less per object than the drawing it saves.
 */
public final class FpsBooster {
    private FpsBooster() {
    }

    /** True if the entity should be skipped this frame. Called for every entity, every frame. */
    public static boolean shouldCullEntity(Entity entity, double cameraX, double cameraY, double cameraZ) {
        MogyConfig config = MogyConfig.get();
        if (!config.fpsBooster || entity instanceof Player || entity.isCurrentlyGlowing()) {
            return false;
        }
        int distance = renderDistanceFor(entity, config);
        if (distance <= 0) {
            return false;
        }
        Entity cameraEntity = Minecraft.getInstance().getCameraEntity();
        if (cameraEntity != null && (entity == cameraEntity || entity.hasPassenger(cameraEntity) || cameraEntity.hasPassenger(entity))) {
            return false;
        }
        return entity.distanceToSqr(cameraX, cameraY, cameraZ) > (double) distance * distance;
    }

    private static int renderDistanceFor(Entity entity, MogyConfig config) {
        if (entity instanceof ArmorStand || entity instanceof ItemFrame || entity instanceof Painting
                || entity instanceof Display || entity instanceof Interaction || entity instanceof LeashFenceKnotEntity) {
            return config.decorationRenderDistance;
        }
        return config.otherEntityRenderDistance;
    }

    /**
     * True if the block entity should be skipped this frame. Renderers that are meant to be
     * seen from far away (beacon beams, end gateways and similar report a view distance above
     * vanilla's 64 blocks) are left alone.
     */
    public static boolean shouldCullBlockEntity(BlockEntity blockEntity, BlockEntityRenderer<?, ?> renderer, Vec3 cameraPos) {
        MogyConfig config = MogyConfig.get();
        int distance = config.blockEntityRenderDistance;
        if (!config.fpsBooster || distance <= 0 || cameraPos == null || renderer == null || renderer.getViewDistance() > 64) {
            return false;
        }
        return Vec3.atCenterOf(blockEntity.getBlockPos()).distanceToSqr(cameraPos) > (double) distance * distance;
    }

    /** Particle cap per particle type, or 0 for vanilla's 16,384. */
    public static int particleCap() {
        MogyConfig config = MogyConfig.get();
        return config.fpsBooster ? config.maxParticlesPerType : 0;
    }
}
