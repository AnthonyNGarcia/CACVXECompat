package com.cacxve.mixin;

import com.cacxve.core.VillageTradeManager;
import com.warborn.caravansconvoys.trade.DockRegistry;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Comparator;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DockRegistry.class)
public class DockRegistryRouteMixin
{
    @Shadow
    @Final
    private Map<BlockPos, DockRegistry.Record> docks;

    private BlockPos activeDock;

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "serviceDock(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lcom/warborn/caravansconvoys/block/CaravanDockBlockEntity;Z)V",
            at = @At("HEAD"))
    private void rememberDock(net.minecraft.server.level.ServerLevel level, BlockPos dock,
                               com.warborn.caravansconvoys.block.CaravanDockBlockEntity blockEntity,
                               boolean immediate, CallbackInfo callback)
    {
        activeDock = dock;
    }


    @Redirect(
            method = "serviceDock(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lcom/warborn/caravansconvoys/block/CaravanDockBlockEntity;Z)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;entrySet()Ljava/util/Set;")
    )
    private Set<Map.Entry<BlockPos, DockRegistry.Record>> selectedRoute(Map<BlockPos, DockRegistry.Record> ignored)
    {
        return selectedRouteEntries();
    }

    private Set<Map.Entry<BlockPos, DockRegistry.Record>> selectedRouteEntries()
    {
        BlockPos selected = VillageTradeManager.selectedRoute(activeDock);
        if (selected == null && activeDock != null)
        {
            DockRegistry.Record player = docks.get(activeDock);
            if (player != null)
                selected = docks.entrySet().stream()
                        .filter(entry -> VillageTradeManager.isVillageOwner(entry.getValue().owner))
                        .filter(entry -> player.exportId != null && player.wantId != null
                                && player.exportId.equals(entry.getValue().wantId)
                                && player.wantId.equals(entry.getValue().exportId))
                        .min(Comparator.comparingDouble(entry -> entry.getKey().distSqr(activeDock)))
                        .map(Map.Entry::getKey)
                        .orElse(null);
        }
        if (selected == null)
            return docks.entrySet();
        BlockPos selectedTarget = selected;
        return docks.entrySet().stream()
            .filter(entry -> selectedTarget.equals(entry.getKey()))
                .collect(Collectors.toSet());
    }
}