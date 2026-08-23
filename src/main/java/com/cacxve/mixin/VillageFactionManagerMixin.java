package com.cacxve.mixin;

import com.cacxve.core.VillageNameManager;
import com.example.villagerecruits.faction.VillageFactionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(VillageFactionManager.class)
public class VillageFactionManagerMixin
{
    @Overwrite
    public static String getVillageOwnerName(String factionId)
    {
        String name = VillageNameManager.nameFor(factionId);
        return name == null ? "Village_" + factionId : name;
    }
}