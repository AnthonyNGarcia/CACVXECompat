package com.cacxve.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigCatalogTest
{
    @TempDir
    Path tempDir;

    @Test
    void loadsValidTradeEntriesAndDefaultsMissingId()
            throws Exception
    {
        List<Config.TradeDefinition> trades = Config.parseVillageTrades("[{\"villageExportItem\":\"minecraft:wheat\",\"villageExportQuantity\":16,\"villageImportItem\":\"minecraft:iron_ingot\",\"villageImportQuantity\":4}]");
        assertEquals(1, trades.size());
        assertEquals("trade_0", trades.get(0).id());
    }

    @Test
    void invalidTradeFileFallsBackWithoutThrowing()
            throws Exception
    {
        assertTrue(Config.parseVillageTrades("not-json").isEmpty());
    }

    @Test
    void emptyOrMissingTradeFileProvidesDefaultCatalog()
            throws Exception
    {
        assertTrue(Config.parseVillageTrades("[]").isEmpty());
        assertTrue(Config.parseVillageTrades("{}").isEmpty());
    }

    @Test
    void loadsVillageTypesWithFallbackDisplayName()
            throws Exception
    {
        Path file = tempDir.resolve("types.json");
        Files.writeString(file, "[{\"id\":\"farming\"},{\"id\":\"\"}]");
        List<Config.VillageTypeDefinition> types = Config.loadVillageTypes(file);
        assertEquals(1, types.size());
        assertEquals("farming", types.get(0).displayName());
    }

    @Test
    void invalidVillageTypesFallBackWithoutThrowing()
            throws Exception
    {
        Path file = tempDir.resolve("invalid-types.json");
        Files.writeString(file, "not-json");
        assertFalse(Config.loadVillageTypes(file).isEmpty());
    }

    @Test
        void expandsBarterTradesWithOnlyReverseVariant()
    {
        Config.TradeDefinition base = new Config.TradeDefinition(
                "wheat", "minecraft:wheat", "minecraft:iron_ingot", 16, 4, "farming");
        List<Config.TradeDefinition> expanded = Config.expandTradeEconomies(List.of(base));

        assertEquals(2, expanded.size());
        assertFalse(expanded.stream().anyMatch(trade -> trade.id().endsWith("_emerald")));
        assertFalse(expanded.stream().anyMatch(trade -> trade.villageExportItem().equals("minecraft:iron_ingot")
                && trade.villageImportItem().equals("minecraft:wheat")));
        assertTrue(expanded.stream().anyMatch(trade -> trade.villageExportItem().equals("minecraft:emerald")
                && trade.villageImportItem().equals("minecraft:wheat")));
    }

    @Test
        void expansionAlwaysIncludesEmeraldAndReverseVariants()
    {
        Config.TradeDefinition base = new Config.TradeDefinition(
                "wheat", "minecraft:wheat", "minecraft:iron_ingot", 16, 4, "");
        assertEquals(2, Config.expandTradeEconomies(List.of(base)).size());
    }

        @Test
        void indexedTypeTradeOverridesGenericPair()
        {
                Config.TradeDefinition generic = new Config.TradeDefinition(
                                "generic", "minecraft:wheat", "minecraft:emerald", 16, 4, "");
                Config.TradeDefinition farming = new Config.TradeDefinition(
                                "farming", "minecraft:wheat", "minecraft:emerald", 8, 4, "farming");
                Config.setVillageTrades(List.of(generic, farming));

                assertEquals(List.of(farming), Config.tradesForTypes(java.util.Set.of("farming")));
                assertTrue(Config.tradesForTypes(java.util.Set.of("mining")).contains(generic));
        }

        @Test
        void reverseSellTradesAreAvailableToEveryVillageType()
        {
                Config.TradeDefinition diamondMining = new Config.TradeDefinition(
                                "mining_diamond", "minecraft:diamond", "minecraft:emerald", 1, 8, "mining");
                Config.TradeDefinition expanded = Config.expandTradeEconomies(List.of(diamondMining)).stream()
                                .filter(trade -> trade.id().equals("mining_diamond_reverse"))
                                .findFirst().orElseThrow();
                Config.setVillageTrades(List.of(expanded));

                assertTrue(Config.tradesForTypes(java.util.Set.of("farming")).contains(expanded));
                assertTrue(Config.tradesForTypes(java.util.Set.of("merchant")).contains(expanded));
                assertTrue(Config.tradesForTypes(java.util.Set.of("military")).contains(expanded));
        }

        @Test
        void computedOfferEqualizesEmeraldValueWithWholeNumbers()
        {
                Config.TradeDefinition ironBlockSell = new Config.TradeDefinition(
                                "iron_block_reverse", "minecraft:emerald", "minecraft:iron_block", 2, 1, "");
                Config.TradeDefinition oakLogBuy = new Config.TradeDefinition(
                                "oak_log", "minecraft:oak_log", "minecraft:emerald", 16, 3, "forestry");
                Config.setVillageTrades(List.of(ironBlockSell, oakLogBuy));

                Config.TradeDefinition offer = Config.computeOffer("minecraft:iron_block",
                                "minecraft:oak_log", java.util.Set.of("forestry"));
                assertNotNull(offer);
                assertEquals("minecraft:oak_log", offer.villageExportItem());
                assertEquals(32, offer.villageExportQuantity(),
                                "emerald budget lcm(2, 3) = 6 funds 32 oak logs at 16 per 3 emeralds");
                assertEquals("minecraft:iron_block", offer.villageImportItem());
                assertEquals(3, offer.villageImportQuantity(),
                                "emerald budget 6 equals 3 iron blocks at 2 emeralds each");
        }

        @Test
        void computedOfferRequiresWantedItemFromVillageType()
        {
                Config.TradeDefinition ironBlockSell = new Config.TradeDefinition(
                                "iron_block_reverse", "minecraft:emerald", "minecraft:iron_block", 2, 1, "");
                Config.TradeDefinition oakLogBuy = new Config.TradeDefinition(
                                "oak_log", "minecraft:oak_log", "minecraft:emerald", 16, 3, "forestry");
                Config.setVillageTrades(List.of(ironBlockSell, oakLogBuy));

                assertNull(Config.computeOffer("minecraft:iron_block", "minecraft:oak_log",
                                java.util.Set.of("mining")),
                                "a mining village does not export oak logs, so no offer can be computed");
        }

        @Test
        void computedOfferRejectsUnpricedExportItem()
        {
                Config.TradeDefinition oakLogBuy = new Config.TradeDefinition(
                                "oak_log", "minecraft:oak_log", "minecraft:emerald", 16, 3, "forestry");
                Config.setVillageTrades(List.of(oakLogBuy));

                assertNull(Config.computeOffer("minecraft:dirt", "minecraft:oak_log",
                                java.util.Set.of("forestry")),
                                "villages only import items that have a defined config price");
        }

        @Test
        void reverseEmeraldTradeUsesHalfTheBaseEmeraldAmount()
        {
                Config.TradeDefinition base = new Config.TradeDefinition(
                                "oak", "minecraft:oak_log", "minecraft:emerald", 16, 4, "forestry");
                Config.TradeDefinition reverse = Config.expandTradeEconomies(List.of(base)).stream()
                                .filter(trade -> trade.id().equals("oak_reverse"))
                                .findFirst().orElseThrow();
                assertEquals("minecraft:emerald", reverse.villageExportItem());
                assertEquals(2, reverse.villageExportQuantity());
                assertEquals("minecraft:oak_log", reverse.villageImportItem());
                assertEquals(16, reverse.villageImportQuantity());
        }

        @Test
        void multiplyTradeScalesBothSidesWithoutChangingRate()
        {
                Config.TradeDefinition base = new Config.TradeDefinition(
                                "wheat", "minecraft:wheat", "minecraft:emerald", 16, 4, "farming");

                for (int multiplier : new int[]{1, 2, 3, 5, 10})
                {
                        Config.TradeDefinition scaled = Config.multiplyTrade(base, multiplier);
                        assertEquals(16 * multiplier, scaled.villageExportQuantity());
                        assertEquals(4 * multiplier, scaled.villageImportQuantity());
                        assertEquals("wheat_" + multiplier + "x", scaled.id());
                }
        }

        @Test
        void multiplyTradeRejectsUnsupportedValues()
        {
                Config.TradeDefinition base = new Config.TradeDefinition(
                                "wheat", "minecraft:wheat", "minecraft:emerald", 16, 4, "farming");
                assertThrows(IllegalArgumentException.class, () -> Config.multiplyTrade(base, 4));
                assertThrows(IllegalArgumentException.class, () -> Config.multiplyTrade(base, 0));
                assertThrows(IllegalArgumentException.class, () -> Config.multiplyTrade(base, 11));
        }

        @Test
        void multipliedTradeKeepsCatalogIdentityOnlyAsAnExplicitSuffix()
        {
                Config.TradeDefinition base = new Config.TradeDefinition(
                                "farming_wheat", "minecraft:wheat", "minecraft:emerald", 16, 4, "farming");
                Config.TradeDefinition scaled = Config.multiplyTrade(base, 5);

                assertEquals("farming_wheat", base.id());
                assertEquals("farming_wheat_5x", scaled.id());
                assertEquals(base.villageExportItem(), scaled.villageExportItem());
                assertEquals(base.villageImportItem(), scaled.villageImportItem());
        }

        @Test
        void allAllowedMultipliersHaveStableScaledQuantities()
        {
                Config.TradeDefinition base = new Config.TradeDefinition(
                                "stone", "minecraft:stone", "minecraft:emerald", 48, 4, "mining");
                assertEquals(240, Config.multiplyTrade(base, 5).villageExportQuantity());
                assertEquals(20, Config.multiplyTrade(base, 5).villageImportQuantity());
                assertEquals(480, Config.multiplyTrade(base, 10).villageExportQuantity());
                assertEquals(40, Config.multiplyTrade(base, 10).villageImportQuantity());
        }

            @Test
            void typedCoreRateBeatsGenericRate()
            {
                Config.TradeDefinition generic = new Config.TradeDefinition(
                        "generic_wheat", "minecraft:wheat", "minecraft:emerald", 16, 4, "");
                Config.TradeDefinition farming = new Config.TradeDefinition(
                        "farming_wheat", "minecraft:wheat", "minecraft:emerald", 32, 4, "farming");
                Config.setVillageTrades(Config.expandTradeEconomies(List.of(generic, farming)));

                Config.TradeDefinition selected = Config.tradesForTypes(java.util.Set.of("farming")).stream()
                        .filter(trade -> trade.villageExportItem().equals("minecraft:wheat")
                                && trade.villageImportItem().equals("minecraft:emerald"))
                        .findFirst().orElseThrow();
                assertEquals("farming_wheat", selected.id());
                assertEquals(32, selected.villageExportQuantity());
            }

        @Test
        void parsesGenericAndTypedArrayDocuments()
        {
                String json = "[{\"id\":\"farming_wheat\",\"villageExportItem\":\"minecraft:wheat\",\"villageExportQuantity\":16,\"villageImportItem\":\"minecraft:emerald\",\"villageImportQuantity\":4},{\"id\":\"farming_oak\",\"villageExportItem\":\"minecraft:oak_log\",\"villageExportQuantity\":8,\"villageImportItem\":\"minecraft:emerald\",\"villageImportQuantity\":4}]";
                List<Config.TradeDefinition> trades = Config.parseVillageTrades(json);
                assertEquals(2, trades.size());
                assertEquals("farming_wheat", trades.get(0).id());
                assertEquals("farming_oak", trades.get(1).id());
        }
}
