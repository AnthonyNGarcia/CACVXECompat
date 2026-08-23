package com.cacxve.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;
import java.util.HashMap;

public class VillageTradeData extends SavedData
{
    private final Map<String, BlockPos> dockPositions = new HashMap<>();

    public static VillageTradeData load(CompoundTag tag)
    {
        VillageTradeData data = new VillageTradeData();
        for (String factionId : tag.getAllKeys())
            data.dockPositions.put(factionId, BlockPos.of(tag.getLong(factionId)));
        return data;
    }

    public BlockPos dockFor(String factionId)
    {
        return dockPositions.get(factionId);
    }

    public void setDock(String factionId, BlockPos position)
    {
        if (!position.equals(dockPositions.put(factionId, position)))
            setDirty();
    }

    public void removeDock(String factionId)
    {
        if (dockPositions.remove(factionId) != null)
            setDirty();
    }

    public Map<String, BlockPos> docks()
    {
        return new HashMap<>(dockPositions);
    }

    public boolean containsDock(BlockPos position)
    {
        return dockPositions.values().stream()
                .anyMatch(dock -> dock.equals(position) || dock.east().equals(position));
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        dockPositions.forEach((factionId, position) -> tag.putLong(factionId, position.asLong()));
        return tag;
    }
}