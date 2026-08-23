package com.cacxve.core;

import com.example.villagerecruits.faction.VillageFaction;
import com.example.villagerecruits.faction.VillageFactionManager;
import com.mojang.logging.LogUtils;
import com.talhanation.recruits.ClaimEvents;
import com.talhanation.recruits.world.RecruitsClaim;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.scores.PlayerTeam;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;

public final class VillageNameManager
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> NAME_POOL = List.of(
            "Ashenford", "Briarwatch", "Cinderbrook", "Dunmere", "Elderfall",
            "Foxhollow", "Greyhaven", "Highmere", "Ironvale", "Juniper Rest",
            "Kingsrest", "Larkspur", "Moonfen", "Northpass", "Oakenspire",
            "Pinewatch", "Queenscross", "Ravenmoor", "Stoneleigh", "Thornmere",
            "Umberfield", "Valewick", "Willowmark", "Yarrowden", "Zephyr Vale"
    );

    private static long ticksUntilScan;
    private static VillageNameData currentData;

    private VillageNameManager()
    {
    }

    public static void tick(MinecraftServer server)
    {
        if (--ticksUntilScan > 0)
            return;
        ticksUntilScan = 100;

        ServerLevel level = server.overworld();
        VillageNameData data = level.getDataStorage().computeIfAbsent(
                VillageNameData::load,
                VillageNameData::new,
                "cacxvecompat_village_names"
        );
            currentData = data;
        Set<String> usedNames = new HashSet<>();
        data.addNamesTo(usedNames);
        int factionCount = 0;
        int villageCount = 0;

        for (VillageFaction faction : VillageFactionManager.getAllFactions())
        {
            factionCount++;
            if (faction == null || faction.id == null || !faction.id.startsWith("village_"))
                continue;
            villageCount++;

            String name = data.nameFor(faction.id);
            if (name == null || name.isBlank())
            {
                name = nextName(usedNames);
                data.setName(faction.id, name);
                usedNames.add(name);
            }

            PlayerTeam team = server.getScoreboard().getPlayerTeam(faction.id);
            if (team != null && !name.equals(team.getDisplayName().getString()))
            {
                team.setDisplayName(Component.literal(name));
                LOGGER.info("Assigned village name '{}' to faction {}", name, faction.id);
            }
            else if (team == null)
                LOGGER.warn("Could not find scoreboard team for village faction {}", faction.id);

            migrateClaims(level, faction, name);
        }

        LOGGER.debug("Village name scan found {} factions and {} village factions", factionCount, villageCount);
    }

    public static String nameFor(String villageId)
    {
        return currentData == null || villageId == null ? null : currentData.nameFor(villageId);
    }

    private static void migrateClaims(ServerLevel level, VillageFaction faction, String name)
    {
        if (ClaimEvents.recruitsClaimManager == null)
            return;

        for (RecruitsClaim claim : ClaimEvents.recruitsClaimManager.getAllClaims())
        {
            if (!faction.id.equals(claim.getOwnerFactionStringID()))
                continue;

            boolean changed = !name.equals(claim.getName());
            if (changed)
                claim.setName(name);
            if (claim.getPlayerInfo() != null && !name.equals(claim.getPlayerInfo().getName()))
            {
                claim.getPlayerInfo().setName(name);
                changed = true;
            }
            if (changed)
                ClaimEvents.recruitsClaimManager.addOrUpdateClaim(level, claim);
        }
    }

    private static String nextName(Set<String> usedNames)
    {
        for (String name : NAME_POOL)
        {
            if (!usedNames.contains(name))
                return name;
        }
        return "New Settlement " + (usedNames.size() + 1);
    }
}