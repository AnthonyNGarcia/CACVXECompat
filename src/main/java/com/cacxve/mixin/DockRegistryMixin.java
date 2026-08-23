package com.cacxve.mixin;

import com.cacxve.core.VillageTradeManager;
import com.cacxve.core.Config;
import com.example.villagerecruits.faction.VillageFaction;
import com.mojang.logging.LogUtils;
import com.warborn.caravansconvoys.trade.DockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.warborn.caravansconvoys.block.CaravanDockBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Comparator;
import java.util.UUID;
import org.spongepowered.asm.mixin.Unique;

@Mixin(DockRegistry.class)
public class DockRegistryMixin
{
    private static final Logger LOGGER = LogUtils.getLogger();
    @Unique
    private UUID cacxvecompat$restoredOwner;

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private Map<BlockPos, DockRegistry.Record> docks;

    @Inject(method = "depositToDock", at = @At("HEAD"), cancellable = true, require = 0)
    private static void discardVillageCargoFallback(ServerLevel level, BlockPos position,
                                                     net.minecraft.world.item.ItemStack stack,
                                                     CallbackInfo callback)
    {
        if (VillageTradeManager.isVillageEndpoint(level, position))
            callback.cancel();
    }

    @Inject(method = "matchReport", at = @At("HEAD"), cancellable = true)
    private void openEndedVillageReport(ServerLevel level, BlockPos dockPos,
                                         CaravanDockBlockEntity dock,
                                         CallbackInfoReturnable<List<Component>> callback)
    {
        DockRegistry.Record player = ((DockRegistry) (Object) this).record(dockPos);
        boolean hasOffer = player.exportId != null && !player.exportId.isBlank()
            && player.wantId != null && !player.wantId.isBlank();

        List<Component> report = new ArrayList<>();
        report.add(Component.literal("Village routes matching your items:"));
        List<Map.Entry<BlockPos, DockRegistry.Record>> candidates = docks.entrySet().stream()
            .filter(entry -> !entry.getKey().equals(dockPos))
            .filter(entry -> VillageTradeManager.isVillageOwner(entry.getValue().owner))
            .sorted(Comparator.comparingDouble(entry -> entry.getKey().distSqr(dockPos)))
            .toList();
        for (Map.Entry<BlockPos, DockRegistry.Record> entry : candidates)
        {
            BlockPos target = entry.getKey();
            DockRegistry.Record village = entry.getValue();
            String ownerName = village.ownerName == null ? "Village" : village.ownerName;
            String villageType = VillageTradeManager.villageTypeForOwner(village.owner);
            if (!villageType.isBlank())
                ownerName += " (" + villageType + ")";
            VillageFaction faction = VillageTradeManager.factionForOwnerForUi(village.owner);
            if (faction == null)
                continue;
            boolean matchedCatalog = false;
            for (Config.TradeDefinition trade : VillageTradeManager.tradesFor(faction))
            {
                if (hasOffer
                    && (!player.exportId.equals(trade.villageImportItem())
                    || !player.wantId.equals(trade.villageExportItem())))
                    continue;
                matchedCatalog = true;
                report.add(Component.literal(ownerName + " at " + target.getX() + ", "
                        + target.getY() + ", " + target.getZ() + " [trade=" + trade.id() + "]: village gives "
                        + amountName(trade.villageExportQuantity(), trade.villageExportItem()) + " for "
                        + amountName(trade.villageImportQuantity(), trade.villageImportItem())));
            }
            if (hasOffer && !matchedCatalog)
            {
                Config.TradeDefinition offer = VillageTradeManager.computedOfferFor(faction,
                        player.exportId, player.wantId);
                if (offer != null)
                    report.add(Component.literal(ownerName + " at " + target.getX() + ", "
                            + target.getY() + ", " + target.getZ() + " [trade=" + offer.id() + "]: village gives "
                            + amountName(offer.villageExportQuantity(), offer.villageExportItem()) + " for "
                            + amountName(offer.villageImportQuantity(), offer.villageImportItem())));
            }
        }

        LOGGER.debug("Built {} village route options for dock {} (matchingOffer={})", report.size() - 1, dockPos, hasOffer);
        if (candidates.isEmpty() || report.size() == 1)
            return;
        callback.setReturnValue(report);
    }

    private static String amountName(int amount, String itemId)
    {
        Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
            net.minecraft.resources.ResourceLocation.tryParse(itemId));
        return amount + " " + (item == null ? itemId : item.getDescription().getString());
    }

    @Inject(method = "startOperation", at = @At("RETURN"))
        private void traceStartOperation(
            ServerLevel level,
            BlockPos dockPos,
            CaravanDockBlockEntity dock,
            ServerPlayer player,
            CallbackInfoReturnable<Boolean> callback)
    {
        DockRegistry.Record record = ((DockRegistry) (Object) this).record(dockPos);
        LOGGER.info("Start operation at {} by {} returned {}; offer={}x{} want={}x{} wagon={} operating={}",
                dockPos,
                player.getGameProfile().getName(),
                callback.getReturnValue(),
                record.exportId,
                record.exportAmt,
                record.wantId,
                record.wantAmt,
                record.wagonId,
                record.operating);
    }

    @Inject(method = "startOperation", at = @At("HEAD"))
    private void cacxvecompat$allowPublicStart(ServerLevel level, BlockPos dockPos,
                                                CaravanDockBlockEntity dock, ServerPlayer player,
                                                CallbackInfoReturnable<Boolean> callback)
    {
        DockRegistry.Record record = ((DockRegistry) (Object) this).record(dockPos);
        if (player != null && Config.allowPublicCaravanDocks()
                && !VillageTradeManager.isVillageOwner(record.owner)
                && record.owner != null && !record.owner.equals(player.getUUID()))
        {
            cacxvecompat$restoredOwner = record.owner;
            record.owner = null;
        }
    }

    @Inject(method = "startOperation", at = @At("RETURN"))
    private void cacxvecompat$restorePublicOwner(ServerLevel level, BlockPos dockPos,
                                                  CaravanDockBlockEntity dock, ServerPlayer player,
                                                  CallbackInfoReturnable<Boolean> callback)
    {
        if (cacxvecompat$restoredOwner != null)
        {
            ((DockRegistry) (Object) this).record(dockPos).owner = cacxvecompat$restoredOwner;
            cacxvecompat$restoredOwner = null;
        }
    }

    @Inject(method = "whitelisted", at = @At("HEAD"), cancellable = true)
    private static void allowVillagePartners(
            DockRegistry.Record first,
            DockRegistry.Record second,
            CallbackInfoReturnable<Boolean> callback)
    {
        if (VillageTradeManager.isVillageOwner(first.owner)
                || VillageTradeManager.isVillageOwner(second.owner))
            callback.setReturnValue(true);
    }
}