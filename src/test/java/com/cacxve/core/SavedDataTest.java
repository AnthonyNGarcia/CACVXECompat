package com.cacxve.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SavedDataTest
{
    @Test
    void tradeDataStoresCopiesAndNeighbors()
    {
        VillageTradeData data = new VillageTradeData();
        BlockPos dock = new BlockPos(4, 70, -2);
        data.setDock("village_a", dock);

        assertEquals(dock, data.dockFor("village_a"));
        assertTrue(data.containsDock(dock));
        assertTrue(data.containsDock(dock.east()));
        assertFalse(data.containsDock(dock.north()));
        Map<String, BlockPos> copy = data.docks();
        copy.clear();
        assertEquals(dock, data.dockFor("village_a"));
    }

    @Test
    void tradeDataRoundTripsNbt()
    {
        VillageTradeData data = new VillageTradeData();
        data.setDock("village_a", new BlockPos(1, 2, 3));
        VillageTradeData loaded = VillageTradeData.load(data.save(new CompoundTag()));
        assertEquals(new BlockPos(1, 2, 3), loaded.dockFor("village_a"));
    }

    @Test
    void tradeDataRemovalAndReplacementAreSafe()
    {
        VillageTradeData data = new VillageTradeData();
        data.setDock("village_a", new BlockPos(1, 2, 3));
        data.setDock("village_a", new BlockPos(4, 5, 6));
        data.removeDock("missing");
        data.removeDock("village_a");
        assertNull(data.dockFor("village_a"));
    }

    @Test
    void typeDataRoundTripsAndReplaces()
    {
        VillageTypeData data = new VillageTypeData();
        data.setType("village_a", "farming");
        data.setType("village_a", "mining");
        VillageTypeData loaded = VillageTypeData.load(data.save(new CompoundTag()));
        assertEquals("mining", loaded.typeFor("village_a"));
        assertNull(loaded.typeFor("missing"));
    }

    @Test
    void nameDataRoundTripsAndCollectsNames()
    {
        VillageNameData data = new VillageNameData();
        data.setName("village_a", "Ashenford");
        data.setName("village_b", "Briarwatch");
        Set<String> names = new java.util.HashSet<>();
        data.addNamesTo(names);
        assertEquals(Set.of("Ashenford", "Briarwatch"), names);
        VillageNameData loaded = VillageNameData.load(data.save(new CompoundTag()));
        assertEquals("Ashenford", loaded.nameFor("village_a"));
    }
}
