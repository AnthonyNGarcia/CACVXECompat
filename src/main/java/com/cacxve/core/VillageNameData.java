package com.cacxve.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class VillageNameData extends SavedData
{
    private final Map<String, String> names = new HashMap<>();

    public static VillageNameData load(CompoundTag tag)
    {
        VillageNameData data = new VillageNameData();
        for (String villageId : tag.getAllKeys())
            data.names.put(villageId, tag.getString(villageId));
        return data;
    }

    public String nameFor(String villageId)
    {
        return names.get(villageId);
    }

    public void addNamesTo(Set<String> destination)
    {
        destination.addAll(names.values());
    }

    public void setName(String villageId, String name)
    {
        if (!name.equals(names.put(villageId, name)))
            setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        names.forEach(tag::putString);
        return tag;
    }
}