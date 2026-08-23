package com.cacxve.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

public class VillageTypeData extends SavedData
{
    private final Map<String, String> types = new HashMap<>();

    public static VillageTypeData load(CompoundTag tag)
    {
        VillageTypeData data = new VillageTypeData();
        for (String factionId : tag.getAllKeys())
            data.types.put(factionId, tag.getString(factionId));
        return data;
    }

    public String typeFor(String factionId)
    {
        return types.get(factionId);
    }

    public void setType(String factionId, String typeId)
    {
        if (!typeId.equals(types.put(factionId, typeId)))
            setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        types.forEach(tag::putString);
        return tag;
    }
}