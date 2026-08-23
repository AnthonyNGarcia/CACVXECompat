package com.cacxve.core;

import com.example.villagerecruits.faction.VillageFaction;
import com.example.villagerecruits.faction.VillageFactionManager;
import com.example.villagerecruits.special.SpecialFactionManager;
import com.mojang.logging.LogUtils;
import com.warborn.caravansconvoys.CaravansConvoys;
import com.warborn.caravansconvoys.block.CaravanDockBlockEntity;
import com.warborn.caravansconvoys.entity.WagonCoachEntity;
import com.warborn.caravansconvoys.trade.DockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.RandomSource;
import net.minecraftforge.fml.loading.FMLPaths;

public final class VillageTradeManager
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static long ticksUntilScan;
    private static long ticksUntilForceService;
    private static final Map<BlockPos, BlockPos> SELECTED_ROUTES = new ConcurrentHashMap<>();
    private static final Map<BlockPos, String> ACTIVE_TRADES = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer> ROUTE_MULTIPLIERS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer> PENDING_MULTIPLIERS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer> TARGET_MULTIPLIERS = new ConcurrentHashMap<>();
    private static volatile BlockPos LAST_SELECTED_ROUTE;
    private static boolean tradeConfigLoaded;
    private static VillageTypeData villageTypeData;

    private VillageTradeManager()
    {
    }

    public static void tick(MinecraftServer server)
    {
        ServerLevel level = server.overworld();

        // Runs on a fast, independent cadence (~0.25s) regardless of the slower dock-reconciliation scan below.
        // This is a self-healing safety net covering BOTH: (a) a wagon that was just deployed by
        // startOperation() but never actually got its first dispatch() call, and (b) a wagon idling at
        // home after completing a trip. The WagonReturnMixin hook handles (b) immediately in the common
        // case, but this poll guarantees neither ever stalls indefinitely if that one-shot attempt misses
        // for any transient reason (capability/block-entity not yet settled, packet ordering, etc.).
        if (--ticksUntilForceService <= 0)
        {
            ticksUntilForceService = 5;
            forceServiceStalledRoutes(level);
        }

        if (--ticksUntilScan > 0)
            return;
        ticksUntilScan = 100;

        if (!tradeConfigLoaded)
        {
            Config.setVillageTrades(Config.loadTradeCatalog(FMLPaths.CONFIGDIR.get()));
            Config.villageTypes = Config.loadVillageTypes(
                FMLPaths.CONFIGDIR.get().resolve("cacxvecompat/village-types.json"));
            tradeConfigLoaded = true;
            LOGGER.info("Loaded {} village trade definitions", Config.villageTrades.size());
        }
        VillageTradeData data = level.getDataStorage().computeIfAbsent(
                VillageTradeData::load,
                VillageTradeData::new,
                "cacxvecompat_village_trades"
        );
            villageTypeData = level.getDataStorage().computeIfAbsent(
                VillageTypeData::load,
                VillageTypeData::new,
                "cacxvecompat_village_types"
            );
        cleanupLegacyTradeDocks(level, data);

        if (Config.villageTrades.isEmpty())
            return;

        for (VillageFaction faction : VillageFactionManager.getAllFactions())
        {
            if (faction == null || faction.id == null || !faction.id.startsWith("village_") || faction.center == null)
                continue;

            ensureVillageType(faction);

            List<Config.TradeDefinition> trades = tradesFor(faction);
            LOGGER.debug("Village endpoint reconcile {} center={} trades={}", faction.id, faction.center, trades.size());
            if (trades.isEmpty())
                continue;
            String dockKey = faction.id;
            BlockPos dockPos = data.dockFor(dockKey);
            if (dockPos == null)
            {
                dockPos = findOpenPosition(level, faction.center);
                if (dockPos == null)
                {
                    LOGGER.warn("Could not find a position for village trade dock {}", faction.id);
                    continue;
                }
                placeDock(level, dockPos);
                data.setDock(dockKey, dockPos);
                LOGGER.info("Created village trade dock for {} at {}", faction.id, dockPos);
            }
            ensureCreativeVillageDock(level, dockPos);
            LOGGER.debug("Using village trade endpoint {} for {}", dockPos, faction.id);
                BlockPos activeDockPos = dockPos;
                String activeId = ACTIVE_TRADES.get(activeDockPos);
                if (activeId != null && activeId.startsWith("computed_"))
                    continue;
                Config.TradeDefinition active = trades.stream()
                    .filter(trade -> trade.id().equals(activeId))
                    .findFirst().orElse(trades.get(0));
            active = Config.multiplyTrade(active, TARGET_MULTIPLIERS.getOrDefault(activeDockPos, 1));
            configureDock(level, dockPos, faction, active);
        }
    }

    /**
     * Called via mixin the instant a wagon finishes its return leg and dumps cargo at its home dock
     * (WagonCoachEntity.completeArrival, phase becomes idle). This is the real "trip complete" signal -
     * we force-service the dock immediately so the still-idle wagon is re-matched and redispatched
     * without waiting on any polling interval or timeout.
     */
    public static void onWagonReturnedHome(ServerLevel level, BlockPos homePos)
    {
        if (homePos == null || !SELECTED_ROUTES.containsKey(homePos))
            return;

        try
        {
            if (level.getBlockEntity(homePos) instanceof CaravanDockBlockEntity dock)
            {
                DockRegistry registry = DockRegistry.get(level);
                DockRegistry.Record before = registry.peek(homePos);
                LOGGER.info("Wagon returned home at {}; before service wagon={} operating={}",
                    homePos, before == null ? null : before.wagonId,
                    before != null && before.operating);
                registry.serviceDock(level, homePos, dock, true);
                DockRegistry.Record after = registry.peek(homePos);
                LOGGER.info("Return service completed at {}; after service wagon={} operating={}",
                    homePos, after == null ? null : after.wagonId,
                    after != null && after.operating);
            }
        }
        catch (Exception e)
        {
            LOGGER.error("Error re-dispatching wagon at {}", homePos, e);
        }
    }

    /**
     * Safety-net poll (runs ~once/sec, see tick()): force-services any selected route whose wagon is
     * idle at home. serviceDock() with force=true also bypasses DEFAULT_SURPLUS_FLOOR (native default 64),
     * without which the native per-tick block ticker would never redispatch modest player stockpiles.
     */
    private static void forceServiceStalledRoutes(ServerLevel level)
    {
        if (SELECTED_ROUTES.isEmpty())
            return;

        DockRegistry registry = DockRegistry.get(level);
        for (BlockPos playerDockPos : SELECTED_ROUTES.keySet())
        {
            try
            {
                DockRegistry.Record record = registry.peek(playerDockPos);
                if (record == null || !record.operating || record.wagonId == null)
                    continue;
                if (!(level.getEntity(record.wagonId) instanceof WagonCoachEntity wagon) || !wagon.isIdle())
                    continue;
                if (!(level.getBlockEntity(playerDockPos) instanceof CaravanDockBlockEntity dock))
                    continue;
                registry.serviceDock(level, playerDockPos, dock, true);
            }
            catch (Exception e)
            {
                LOGGER.error("Error force-servicing stalled route at {}", playerDockPos, e);
            }
        }
    }

    public static boolean isVillageOwner(UUID owner)
    {
        if (owner == null)
            return false;

        for (VillageFaction faction : VillageFactionManager.getAllFactions())
        {
            if (faction != null && faction.id != null
                    && owner.equals(UUID.nameUUIDFromBytes(("cacxvecompat:village:" + faction.id).getBytes(StandardCharsets.UTF_8))))
                return true;
        }
        return false;
    }

    public static boolean canAccessDock(UUID owner, UUID player, boolean villageDock)
    {
        return canAccessDock(owner, player, villageDock, Config.allowPublicCaravanDocks());
    }

    public static boolean canAccessDock(UUID owner, UUID player, boolean villageDock, boolean allowPublic)
    {
        return Config.canAccessCaravanDock(owner, player, villageDock || isVillageOwner(owner), allowPublic);
    }

    public static String villageType(VillageFaction faction)
    {
        if (faction == null)
            return "";
        if (villageTypeData != null && faction.id != null && faction.center != null)
        {
            String assigned = villageTypeData.typeFor(villageTypeKey(faction));
            if (assigned != null && !assigned.isBlank())
                return displayType(assigned);
        }
        if (faction.specialType != null && !faction.specialType.isBlank())
            return faction.specialType;
        var type = SpecialFactionManager.typeOf(faction);
        return type == null ? "" : type.id();
    }

    public static String villageTypeForOwner(UUID owner)
    {
        for (VillageFaction faction : VillageFactionManager.getAllFactions())
        {
            if (faction != null && faction.id != null && owner != null
                    && owner.equals(UUID.nameUUIDFromBytes(("cacxvecompat:village:" + faction.id).getBytes(StandardCharsets.UTF_8))))
                return villageType(faction);
        }
        return "";
    }

    public static List<Config.TradeDefinition> tradesFor(VillageFaction faction)
    {
        Set<String> types = villageTypesFor(faction);
        return Config.tradesForTypes(types);
    }

    private static void ensureVillageType(VillageFaction faction)
    {
        String key = villageTypeKey(faction);
        if (villageTypeData == null || villageTypeData.typeFor(key) != null || Config.villageTypes.isEmpty())
            return;
        VillageTypeData data = villageTypeData;
        String type = Config.villageTypes.get(RandomSource.create().nextInt(Config.villageTypes.size())).id();
        data.setType(key, type);
    }

    private static Set<String> villageTypesFor(VillageFaction faction)
    {
        Set<String> types = new java.util.HashSet<>();
        String key = villageTypeKey(faction);
        if (villageTypeData != null && villageTypeData.typeFor(key) != null)
            types.add(villageTypeData.typeFor(key));
        if (faction.specialType != null)
            types.add(faction.specialType);
        var special = SpecialFactionManager.typeOf(faction);
        if (special != null)
            types.add(special.id());
        return types;
    }

    private static String villageTypeKey(VillageFaction faction)
    {
        return faction.id + "@" + faction.center.asLong();
    }

    private static String displayType(String id)
    {
        return Config.villageTypes.stream().filter(type -> type.id().equalsIgnoreCase(id))
                .map(Config.VillageTypeDefinition::displayName).findFirst().orElse(id);
    }

    public static boolean isVillageEndpoint(ServerLevel level, BlockPos position)
    {
        if (!(level.getBlockEntity(position) instanceof CaravanDockBlockEntity dock))
            return false;
        return isVillageOwner(dock.getOwner());
    }

    public static void selectRoute(ServerLevel level, ServerPlayer player, BlockPos dock, BlockPos target, String tradeId)
    {
        selectRoute(level, player, dock, target, tradeId, 1);
    }

    public static void selectRoute(ServerLevel level, ServerPlayer player, BlockPos dock, BlockPos target, String tradeId, int multiplier)
    {
        if (multiplier != 1 && multiplier != 2 && multiplier != 3 && multiplier != 5 && multiplier != 10)
            return;
        if (!(level.getBlockEntity(dock) instanceof CaravanDockBlockEntity playerDock))
            return;
        DockRegistry.Record playerRecord = DockRegistry.get(level).record(dock);
        DockRegistry.Record villageRecord = DockRegistry.get(level).peek(target);
        if (villageRecord == null || !isVillageOwner(villageRecord.owner))
            return;
        if (!canAccessDock(playerRecord.owner, player.getUUID(), false))
            return;

        VillageFaction faction = factionForOwner(villageRecord.owner);
        Config.TradeDefinition trade = faction == null ? null : tradesFor(faction).stream()
            .filter(candidate -> candidate.id().equals(tradeId)).findFirst().orElse(null);
        if (trade == null && faction != null && tradeId != null && tradeId.startsWith("computed_"))
            trade = Config.computeOffer(playerRecord.exportId, playerRecord.wantId,
                villageTypesFor(faction));
        if (trade == null)
            return;
        String baseTradeId = trade.id();
        int selectedMultiplier = multiplier == 1 ? PENDING_MULTIPLIERS.getOrDefault(dock, 1) : multiplier;
        ROUTE_MULTIPLIERS.put(dock.immutable(), selectedMultiplier);
        TARGET_MULTIPLIERS.put(target.immutable(), selectedMultiplier);
        PENDING_MULTIPLIERS.remove(dock);
        trade = Config.multiplyTrade(trade, selectedMultiplier);
        ensureCreativeVillageDock(level, target);
        if (level.getBlockEntity(target) instanceof CaravanDockBlockEntity villageDock)
        {
            villageDock.setOwner(villageRecord.owner);
                Item villageExportItem = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    ResourceLocation.tryParse(trade.villageExportItem()));
                Item villageImportItem = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    ResourceLocation.tryParse(trade.villageImportItem()));
            villageDock.setOfferSamples(new ItemStack(villageExportItem), new ItemStack(villageImportItem));
        }
        ACTIVE_TRADES.put(target.immutable(), baseTradeId);
        applyVillageTrade(villageRecord, trade);
        applyPlayerTrade(playerRecord, trade);
        Item playerExportItem = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(playerRecord.exportId));
        Item playerWantItem = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(playerRecord.wantId));
        playerDock.setOfferSamples(new ItemStack(playerExportItem), new ItemStack(playerWantItem));
        DockRegistry.get(level).setDirty();
        SELECTED_ROUTES.put(dock.immutable(), target.immutable());
        LAST_SELECTED_ROUTE = target.immutable();
        if (playerDock.wagonSlot().getItem(0) != null && !playerDock.wagonSlot().getItem(0).isEmpty())
        {
            DockRegistry registry = DockRegistry.get(level);
            if (registry.startOperation(level, dock, playerDock, player))
                registry.serviceDock(level, dock, playerDock, true);
        }
    }

    public static int routeMultiplier(BlockPos dock)
    {
        return ROUTE_MULTIPLIERS.getOrDefault(dock, PENDING_MULTIPLIERS.getOrDefault(dock, 1));
    }

    public static void setRouteMultiplier(ServerLevel level, ServerPlayer player, BlockPos dock, int multiplier)
    {
        if (multiplier != 1 && multiplier != 2 && multiplier != 3 && multiplier != 5 && multiplier != 10)
            return;
        if (!(level.getBlockEntity(dock) instanceof CaravanDockBlockEntity playerDock))
            return;
        DockRegistry.Record playerRecord = DockRegistry.get(level).record(dock);
        if (!canAccessDock(playerRecord.owner, player.getUUID(), false))
            return;
        BlockPos target = SELECTED_ROUTES.get(dock);
        if (target == null)
        {
            PENDING_MULTIPLIERS.put(dock.immutable(), multiplier);
            LOGGER.debug("Stored pending route multiplier {}x for dock {}", multiplier, dock);
            return;
        }
        DockRegistry.Record villageRecord = DockRegistry.get(level).peek(target);
        if (villageRecord == null)
            return;
        String baseId = ACTIVE_TRADES.get(target);
        VillageFaction faction = factionForOwner(villageRecord.owner);
        if (faction == null)
            return;
        Config.TradeDefinition base = tradesFor(faction).stream().filter(trade -> trade.id().equals(baseId)).findFirst().orElse(null);
        if (base == null)
            return;
        ROUTE_MULTIPLIERS.put(dock.immutable(), multiplier);
        TARGET_MULTIPLIERS.put(target.immutable(), multiplier);
        LOGGER.debug("Updated route multiplier {}x for dock {} target {}", multiplier, dock, target);
        Config.TradeDefinition scaled = Config.multiplyTrade(base, multiplier);
        applyVillageTrade(villageRecord, scaled);
        applyPlayerTrade(DockRegistry.get(level).record(dock), scaled);
        playerDock.setOfferSamples(new ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(scaled.villageImportItem()))),
                new ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(scaled.villageExportItem()))));
        DockRegistry.get(level).setDirty();
    }

    public static Config.TradeDefinition computedOfferFor(VillageFaction faction, String exportId, String wantId)
    {
        if (faction == null)
            return null;
        return Config.computeOffer(exportId, wantId, villageTypesFor(faction));
    }

    public static void applyVillageTrade(DockRegistry.Record record, Config.TradeDefinition trade)
    {
        record.exportId = trade.villageExportItem();
        record.exportAmt = trade.villageExportQuantity();
        record.wantId = trade.villageImportItem();
        record.wantAmt = trade.villageImportQuantity();
    }

    /**
     * Player side is the mirror image of the village record: sends what the village wants,
     * wants what the village sends. DockRegistry.mirrors()/complements() require exportId/wantId
     * populated on BOTH sides or serviceDock never finds a match (fields default to "", not null).
     */
    public static void applyPlayerTrade(DockRegistry.Record playerRecord, Config.TradeDefinition trade)
    {
        playerRecord.exportId = trade.villageImportItem();
        playerRecord.exportAmt = trade.villageImportQuantity();
        playerRecord.wantId = trade.villageExportItem();
        playerRecord.wantAmt = trade.villageExportQuantity();
    }

    private static VillageFaction factionForOwner(UUID owner)
    {
        for (VillageFaction faction : VillageFactionManager.getAllFactions())
            if (owner != null && faction != null && owner.equals(villageOwner(faction)))
                return faction;
        return null;
    }

    public static VillageFaction factionForOwnerForUi(UUID owner)
    {
        return factionForOwner(owner);
    }

    private static UUID villageOwner(VillageFaction faction)
    {
        return UUID.nameUUIDFromBytes(("cacxvecompat:village:" + faction.id).getBytes(StandardCharsets.UTF_8));
    }

    public static BlockPos selectedRoute(BlockPos dock)
    {
        return dock == null ? LAST_SELECTED_ROUTE : SELECTED_ROUTES.get(dock);
    }

    /** Test-only: registers a route directly, bypassing the faction/UI lookups in selectRoute(). */
    public static void registerSelectedRouteForTest(BlockPos player, BlockPos village)
    {
        SELECTED_ROUTES.put(player.immutable(), village.immutable());
    }

    private static boolean isVillageDock(ServerLevel level, BlockPos position)
    {
        return level.getBlockState(position).is(CaravansConvoys.CARAVAN_DOCK.get())
                && level.getBlockEntity(position) instanceof CaravanDockBlockEntity;
    }

    private static void cleanupLegacyTradeDocks(ServerLevel level, VillageTradeData data)
    {
        for (Map.Entry<String, BlockPos> entry : data.docks().entrySet())
        {
            if (!entry.getKey().contains("#"))
                continue;
            BlockPos dock = entry.getValue();
            if (isVillageDock(level, dock))
            {
                level.setBlock(dock.east(), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(dock, Blocks.AIR.defaultBlockState(), 3);
            }
            data.removeDock(entry.getKey());
        }
    }

    private static BlockPos findOpenPosition(ServerLevel level, BlockPos center)
    {
        for (int radius = 4; radius <= 16; radius++)
        {
            for (BlockPos candidate : BlockPos.withinManhattan(center, radius, 2, radius))
            {
                BlockPos chestPos = candidate.east();
                if (level.isEmptyBlock(candidate) && level.isEmptyBlock(chestPos)
                        && level.getBlockState(candidate.below()).isFaceSturdy(level, candidate.below(), net.minecraft.core.Direction.UP)
                        && level.getBlockState(chestPos.below()).isFaceSturdy(level, chestPos.below(), net.minecraft.core.Direction.UP))
                    return candidate;
            }
        }
        return null;
    }

    private static void placeDock(ServerLevel level, BlockPos dockPos)
    {
        level.setBlock(dockPos, CaravansConvoys.CREATIVE_DOCK.get().defaultBlockState(), 3);
        level.setBlock(dockPos.east(), Blocks.AIR.defaultBlockState(), 3);
    }

    static void ensureCreativeVillageDock(ServerLevel level, BlockPos dockPos)
    {
        if (level.getBlockState(dockPos).is(CaravansConvoys.CREATIVE_DOCK.get()))
            return;

        DockRegistry.Record record = DockRegistry.get(level).peek(dockPos);
        if (record != null && record.operating && record.wagonId != null)
            return;
        level.setBlock(dockPos, CaravansConvoys.CREATIVE_DOCK.get().defaultBlockState(), 3);
    }

    private static void configureDock(ServerLevel level, BlockPos dockPos, VillageFaction faction, Config.TradeDefinition trade)
    {
        BlockEntity blockEntity = level.getBlockEntity(dockPos);
        if (!(blockEntity instanceof CaravanDockBlockEntity dock))
            return;

        String name = VillageNameManager.nameFor(faction.id);
        if (name == null || name.isBlank())
            name = faction.id;
        UUID owner = UUID.nameUUIDFromBytes(("cacxvecompat:village:" + faction.id).getBytes(StandardCharsets.UTF_8));
        Item exportItem = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(trade.villageExportItem()));
        Item importItem = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(trade.villageImportItem()));
        dock.setOwner(owner);
        dock.setOfferSamples(new ItemStack(exportItem), new ItemStack(importItem));

        DockRegistry.Record record = DockRegistry.get(level).record(dockPos);
        record.owner = owner;
        record.ownerName = name;
        applyVillageTrade(record, trade);
        record.creative = true;
        record.banditMode = false;
        DockRegistry.get(level).setDirty();

        if (level.getBlockState(dockPos.east()).is(Blocks.CHEST))
            level.setBlock(dockPos.east(), Blocks.AIR.defaultBlockState(), 3);
    }
}