package com.mogy.optimizer.mixin;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.mogy.optimizer.config.MogyConfig;
import com.mogy.optimizer.packs.AsyncPackScreen;
import com.mogy.optimizer.packs.PackScanService;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes the resource pack menu's slow paths to background work.
 * <ul>
 *   <li>Opening the menu shows the packs the game already knows right away (as vanilla
 *       1.16.1 does), then rescans the folder in the background, so packs added while the
 *       game was running show up without a restart. See {@link PackScanService}.</li>
 *   <li>The drag-and-drop {@code reload()} becomes that same background rescan instead of a
 *       full synchronous one.</li>
 *   <li>{@code onClose()} invalidates any in-flight scan, so nothing is applied to a closed screen.</li>
 * </ul>
 * Icons are handled per pack, see {@link ResourcePackIconMixin}. Each hook checks its config
 * switch and lets vanilla run when it is off.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(PackSelectionScreen.class)
public abstract class PackSelectionScreenMixin extends Screen implements AsyncPackScreen {
    @Shadow @Final private PackSelectionModel<?> model;
    @Shadow @Final private File packDir;
    @Shadow private boolean shouldCommit;

    @Unique
    private final AtomicInteger mogy$generation = new AtomicInteger();
    @Unique
    private volatile boolean mogy$closed;

    protected PackSelectionScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    private void populateLists() {
        throw new AssertionError();
    }

    /** Opening the menu (and every resize): the lists are already filled, so only rescan in the background. */
    @Inject(method = "init", at = @At("TAIL"))
    private void mogy$rescanOnOpen(CallbackInfo ci) {
        if (MogyConfig.get().asyncPackScan) {
            PackScanService.requestScan(this);
        }
    }

    /** Drag-and-drop reload: rescan in the background instead of on the render thread. */
    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void mogy$asyncReload(CallbackInfo ci) {
        if (!MogyConfig.get().asyncPackScan) {
            return;
        }
        this.shouldCommit = true;
        PackScanService.requestScan(this);
        ci.cancel();
    }

    @Inject(method = "onClose", at = @At("HEAD"))
    private void mogy$onClose(CallbackInfo ci) {
        this.mogy$closed = true;
        this.mogy$generation.incrementAndGet();
    }

    @Override
    public PackRepository<?> mogy$repository() {
        return ((PackSelectionModelAccessor) this.model).mogy$getRepository();
    }

    @Override
    public Path mogy$packDir() {
        return this.packDir.toPath();
    }

    @Override
    public AtomicInteger mogy$scanGeneration() {
        return this.mogy$generation;
    }

    @Override
    public boolean mogy$isClosed() {
        return this.mogy$closed;
    }

    /**
     * The render-thread half of the async reload. It performs exactly the steps of vanilla's
     * {@code PackRepository.reload()} followed by {@code PackSelectionModel.findNewPacks()},
     * except that discovery already happened on the worker. All of this runs in one
     * render-thread task, so input events can never see a half-updated list.
     */
    @Override
    public void mogy$applyScan(Map<String, Pack> available) {
        if (available == null) {
            this.model.findNewPacks(); // vanilla synchronous fallback
        } else {
            PackRepository repository = this.mogy$repository();
            PackRepositoryAccessor repo = (PackRepositoryAccessor) repository;
            List<String> previouslySelected = repo.mogy$getSelected().stream()
                    .map(Pack::getId).collect(ImmutableList.toImmutableList());
            repository.close(); // vanilla closes the old packs before replacing them
            repo.mogy$setAvailable(ImmutableMap.copyOf(available));
            repo.mogy$setSelected(repo.mogy$rebuildSelected(previouslySelected));

            PackSelectionModelAccessor lists = (PackSelectionModelAccessor) this.model;
            List<Pack> selected = lists.mogy$getSelected();
            List<Pack> unselected = lists.mogy$getUnselected();
            unselected.clear();
            unselected.addAll((Collection<Pack>) repository.getAvailablePacks());
            unselected.removeAll(selected);
        }
        this.populateLists();
    }
}
