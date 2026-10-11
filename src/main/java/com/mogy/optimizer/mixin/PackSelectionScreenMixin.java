package com.mogy.optimizer.mixin;

import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mogy.optimizer.config.MogyConfig;
import com.mogy.optimizer.packs.AsyncPackScreen;
import com.mogy.optimizer.packs.DirectoryListingCache;
import com.mogy.optimizer.packs.PackIconCache;
import com.mogy.optimizer.packs.PackScanService;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes the resource pack menu's slow paths to background work.
 * <ul>
 *   <li>Opening the menu fills both lists immediately from the already-loaded packs, then
 *       asks for a background rescan, see {@link PackScanService}.</li>
 *   <li>Every other {@code reload()} (folder watcher, drag-and-drop) becomes that same
 *       background rescan.</li>
 *   <li>{@code getPackIcon()} becomes a non-blocking cache lookup, see {@link PackIconCache}.</li>
 *   <li>{@code onClose()} invalidates any in-flight scan, so nothing is applied to a closed screen.</li>
 * </ul>
 * Each hook checks its config switch and lets vanilla run when it is off.
 */
@Mixin(PackSelectionScreen.class)
public abstract class PackSelectionScreenMixin extends Screen implements AsyncPackScreen {
    @Shadow @Final private static Identifier DEFAULT_ICON;
    @Shadow @Final private PackSelectionModel model;
    @Shadow @Final private Path packDir;
    @Shadow private long ticksToReload;

    @Unique
    private final AtomicInteger mogy$generation = new AtomicInteger();
    @Unique
    private volatile boolean mogy$closed;

    protected PackSelectionScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    private void populateLists(PackSelectionModel.EntryBase toFocus) {
        throw new AssertionError();
    }

    /**
     * Opening the menu. Vanilla's {@code init()} builds two empty list widgets and relies on
     * {@code reload()} to fill them. Here they are filled right away with the packs the
     * repository already knows, so the menu is complete on its first frame. The background
     * rescan then only has to add whatever changed on disk, which is usually nothing.
     */
    @WrapOperation(method = "init", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/packs/PackSelectionScreen;reload()V"))
    private void mogy$openWithKnownPacks(PackSelectionScreen screen, Operation<Void> original) {
        if (!MogyConfig.get().asyncPackScan) {
            original.call(screen);
            return;
        }
        this.populateLists(null);
        this.ticksToReload = 0L;
        PackScanService.requestScan(this);
    }

    /** Every other reload (folder watcher, drag-and-drop): the lists are already filled, so only rescan. */
    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void mogy$asyncReload(CallbackInfo ci) {
        if (!MogyConfig.get().asyncPackScan) {
            return;
        }
        this.ticksToReload = 0L;
        PackScanService.requestScan(this);
        ci.cancel();
    }

    /** Every list rebuild starts with fresh folder listings, so added or removed packs always show up. */
    @Inject(method = "populateLists", at = @At("HEAD"))
    private void mogy$freshFolderListings(PackSelectionModel.EntryBase toFocus, CallbackInfo ci) {
        DirectoryListingCache.invalidate();
    }

    @Inject(method = "getPackIcon", at = @At("HEAD"), cancellable = true)
    private void mogy$cachedIcon(Pack pack, CallbackInfoReturnable<Identifier> cir) {
        if (MogyConfig.get().asyncPackIcons) {
            cir.setReturnValue(PackIconCache.get(pack, this.packDir, DEFAULT_ICON));
        }
    }

    @Inject(method = "onClose", at = @At("HEAD"))
    private void mogy$onClose(CallbackInfo ci) {
        this.mogy$closed = true;
        this.mogy$generation.incrementAndGet();
    }

    @Override
    public PackRepository mogy$repository() {
        return ((PackSelectionModelAccessor) this.model).mogy$getRepository();
    }

    @Override
    public Path mogy$packDir() {
        return this.packDir;
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
            List<String> previouslySelected = repo.mogy$getSelected().stream().map(Pack::getId).toList();
            repo.mogy$setAvailable(ImmutableMap.copyOf(available));
            repo.mogy$setSelected(repo.mogy$rebuildSelected(previouslySelected));

            PackSelectionModelAccessor lists = (PackSelectionModelAccessor) this.model;
            List<Pack> selected = lists.mogy$getSelected();
            List<Pack> unselected = lists.mogy$getUnselected();
            selected.retainAll(repository.getAvailablePacks());
            unselected.clear();
            unselected.addAll(repository.getAvailablePacks());
            unselected.removeAll(selected);
        }
        this.populateLists(null);
        this.ticksToReload = 0L;
    }
}
