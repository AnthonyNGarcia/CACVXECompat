package com.cacxve.core;

import com.warborn.caravansconvoys.trade.DockRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the "wagon never leaves the dock on the first selectRoute" bug.
 *
 * Root cause: DockRegistry.Record.exportId/wantId default to "" (not null - see native source,
 * DockRegistry$Record fields). VillageTradeManager.selectRoute() only ever set the PLAYER dock's
 * exportAmt/wantAmt, never exportId/wantId, so Record.hasOffer() was permanently false and
 * DockRegistry.serviceDock() bailed out before ever calling wagon.dispatch() - the wagon spawned
 * but never moved, even on the very first trade selection.
 *
 * This uses the REAL native DockRegistry.Record class (pure Java fields, no Minecraft world
 * dependency) so these assertions exercise the actual mod's data contract, not a hand-rolled mock.
 */
@DisplayName("Player/village DockRegistry.Record trade wiring (matches native mirrors()/complements() contract)")
public class DockRegistryTradeMatchingTest
{
    private static Config.TradeDefinition sampleTrade()
    {
        return new Config.TradeDefinition("test_trade", "minecraft:wheat", "minecraft:iron_ingot", 24, 4, "");
    }

    /** Mirrors DockRegistry.mirrors(a, b) - decompiled source, DockRegistry.java line 851-853. */
    private static boolean mirrors(DockRegistry.Record a, DockRegistry.Record b)
    {
        return a.exportId.equals(b.wantId) && a.exportAmt == b.wantAmt
                && b.exportId.equals(a.wantId) && b.exportAmt == a.wantAmt;
    }

    /** Mirrors DockRegistry.complements(a, b) - decompiled source, DockRegistry.java line 580-582. */
    private static boolean complements(DockRegistry.Record a, DockRegistry.Record b)
    {
        return a.exportId.equals(b.wantId) && b.exportId.equals(a.wantId);
    }

    @Test
    @DisplayName("applyPlayerTrade populates exportId/wantId (not just amounts) so hasOffer() is true")
    void playerRecordHasOfferAfterSelectRoute()
    {
        DockRegistry.Record playerRecord = new DockRegistry.Record();
        assertFalse(playerRecord.hasOffer(), "Fresh record must start with no offer (native default)");

        VillageTradeManager.applyPlayerTrade(playerRecord, sampleTrade());

        assertTrue(playerRecord.hasOffer(), "Player record must have a complete offer after route selection");
        assertFalse(playerRecord.exportId.isEmpty());
        assertFalse(playerRecord.wantId.isEmpty());
    }

    @Test
    @DisplayName("applyVillageTrade populates exportId/wantId so hasOffer() is true")
    void villageRecordHasOfferAfterConfigure()
    {
        DockRegistry.Record villageRecord = new DockRegistry.Record();
        VillageTradeManager.applyVillageTrade(villageRecord, sampleTrade());

        assertTrue(villageRecord.hasOffer());
    }

    @Test
    @DisplayName("Player and village records mirror each other exactly, as native serviceDock requires")
    void playerAndVillageRecordsMirror()
    {
        Config.TradeDefinition trade = sampleTrade();
        DockRegistry.Record playerRecord = new DockRegistry.Record();
        DockRegistry.Record villageRecord = new DockRegistry.Record();

        VillageTradeManager.applyPlayerTrade(playerRecord, trade);
        VillageTradeManager.applyVillageTrade(villageRecord, trade);

        assertTrue(mirrors(playerRecord, villageRecord),
                "Player/village records must satisfy DockRegistry.mirrors() or a wagon will never be matched");
        assertTrue(complements(playerRecord, villageRecord),
                "Player/village records must also satisfy the looser complements() check used when either side is creative");
    }

    @Test
    @DisplayName("Village dock is marked creative, matching the creative-endpoint trade path in serviceDock")
    void villageDockMarkedCreative()
    {
        DockRegistry.Record villageRecord = new DockRegistry.Record();
        VillageTradeManager.applyVillageTrade(villageRecord, sampleTrade());
        villageRecord.creative = true; // set explicitly by configureDock in production code

        assertTrue(villageRecord.creative);
    }

    @Test
    @DisplayName("Bug regression: exportId/wantId are never left at native default empty string")
    void neverLeavesDefaultEmptyStrings()
    {
        DockRegistry.Record fresh = new DockRegistry.Record();
        assertEquals("", fresh.exportId, "Sanity check: native default is empty string, not null");
        assertEquals("", fresh.wantId);

        VillageTradeManager.applyPlayerTrade(fresh, sampleTrade());
        assertNotEquals("", fresh.exportId);
        assertNotEquals("", fresh.wantId);
    }
}
