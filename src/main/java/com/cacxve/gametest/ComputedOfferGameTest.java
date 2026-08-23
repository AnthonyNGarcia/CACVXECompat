package com.cacxve.gametest;

import com.cacxve.core.CompatCaravanDockMenu;
import com.cacxve.core.Config;
import com.cacxve.core.DispatchTestProbe;
import com.cacxve.core.VillageTradeManager;
import com.example.villagerecruits.faction.VillageFaction;
import com.example.villagerecruits.faction.VillageFactionManager;
import com.warborn.caravansconvoys.CaravansConvoys;
import com.warborn.caravansconvoys.block.CaravanDockBlockEntity;
import com.warborn.caravansconvoys.entity.WagonCoachEntity;
import com.warborn.caravansconvoys.trade.DockRegistry;
import com.warborn.caravansconvoys.wagon.WagonRig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production-path test for computed barter: the player places an iron block export sample and an
 * oak log import sample into the compat dock menu, the native route report computes whole-number
 * quantities from the emerald values (1 iron block sells for 2 emeralds, 16 oak logs cost 3
 * emeralds, lcm budget 6 -> 3 iron blocks for 32 oak logs), and selecting that computed route
 * runs a real wagon trip with the computed amounts.
 */
@GameTestHolder(com.cacxve.core.CaravansAndConvoysCompat.MOD_ID)
public class ComputedOfferGameTest
{
    private static final Pattern TRADE_ID = Pattern.compile("\\[trade=([^]]+)]");
    private static final Config.TradeDefinition IRON_SELL =
            new Config.TradeDefinition("test_iron_block_sell", "minecraft:emerald", "minecraft:iron_block", 2, 1, "");
    private static final Config.TradeDefinition OAK_BUY =
            new Config.TradeDefinition("test_oak_log_buy", "minecraft:oak_log", "minecraft:emerald", 16, 3, "forestry");
    private static final String COMPUTED_ID = "computed_minecraft_iron_block_for_minecraft_oak_log";

    @GameTest(template = "dispatch_loop", timeoutTicks = 16000)
    public static void computedBarterFromSampleSlotsRunsRealWagonTrip(GameTestHelper helper)
    {
        ServerLevel level = helper.getLevel();
        BlockPos playerRel = new BlockPos(1, 1, 1);
        BlockPos villageRel = new BlockPos(10, 1, 8);
        BlockPos chestRel = playerRel.north();
        helper.setBlock(playerRel, CaravansConvoys.CARAVAN_DOCK.get());
        helper.setBlock(villageRel, CaravansConvoys.CREATIVE_DOCK.get());
        helper.setBlock(chestRel, Blocks.CHEST);

        if (!(helper.getBlockEntity(playerRel) instanceof CaravanDockBlockEntity playerDock)
                || !(helper.getBlockEntity(villageRel) instanceof CaravanDockBlockEntity villageDock)
                || !(helper.getBlockEntity(chestRel) instanceof ChestBlockEntity chest))
        {
            helper.fail("Computed-offer fixture is missing its native block entities");
            return;
        }

        List<Config.TradeDefinition> trades = new ArrayList<>();
        trades.add(IRON_SELL);
        trades.add(OAK_BUY);
        Config.setVillageTrades(trades);

        BlockPos playerAbs = helper.absolutePos(playerRel);
        BlockPos villageAbs = helper.absolutePos(villageRel);
        ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        UUID playerId = player.getUUID();
        String factionId = "village_" + villageAbs.getX() + "_" + villageAbs.getY() + "_" + villageAbs.getZ();
        VillageFaction faction = VillageFactionManager.getOrCreate(factionId);
        faction.center = villageAbs;
        faction.specialType = "forestry";
        UUID villageOwner = UUID.nameUUIDFromBytes(("cacxvecompat:village:" + faction.id).getBytes(StandardCharsets.UTF_8));

        DockRegistry registry = DockRegistry.get(level);
        DockRegistry.Record playerRecord = registry.record(playerAbs);
        playerRecord.owner = playerId;
        playerDock.setOwner(playerId);
        playerDock.wagonSlot().setItem(0, WagonRig.anyWagonItem());
        chest.setItem(0, new ItemStack(Items.IRON_BLOCK, 3));

        DockRegistry.Record villageRecord = registry.record(villageAbs);
        villageRecord.owner = villageOwner;
        villageRecord.ownerName = "Computed Test Village";
        villageRecord.creative = true;
        villageDock.setOwner(villageOwner);
        registry.setDirty();

        // Player places iron block as the export sample and oak log as the import sample,
        // exactly like clicking the compat menu's sample slots with carried items.
        CompatCaravanDockMenu menu = new CompatCaravanDockMenu(1, player.getInventory(), playerDock);
        menu.setCarried(new ItemStack(Items.IRON_BLOCK));
        menu.clicked(0, 0, ClickType.PICKUP, player);
        menu.setCarried(new ItemStack(Items.OAK_LOG));
        menu.clicked(1, 0, ClickType.PICKUP, player);
        menu.setCarried(ItemStack.EMPTY);

        if (!"minecraft:iron_block".equals(playerRecord.exportId)
                || !"minecraft:oak_log".equals(playerRecord.wantId))
        {
            helper.fail("Sample slots did not sync into the dock record: export=" + playerRecord.exportId
                    + " want=" + playerRecord.wantId);
            return;
        }

        List<Component> report = registry.matchReport(level, playerAbs, playerDock);
        String selectedTradeId = tradeIdFor(report, COMPUTED_ID);
        if (selectedTradeId == null)
        {
            helper.fail("Native route report did not compute a barter offer for the sample pair: " + report);
            return;
        }

        VillageTradeManager.selectRoute(level, player, playerAbs, villageAbs, selectedTradeId, 1);

        DockRegistry.Record appliedPlayer = registry.peek(playerAbs);
        DockRegistry.Record appliedVillage = registry.peek(villageAbs);
        if (appliedPlayer.exportAmt != 3 || !"minecraft:iron_block".equals(appliedPlayer.exportId)
                || appliedPlayer.wantAmt != 32 || !"minecraft:oak_log".equals(appliedPlayer.wantId))
        {
            helper.fail("Computed player offer is wrong: sends " + appliedPlayer.exportAmt + " "
                    + appliedPlayer.exportId + " for " + appliedPlayer.wantAmt + " " + appliedPlayer.wantId);
            return;
        }
        if (appliedVillage.exportAmt != 32 || !"minecraft:oak_log".equals(appliedVillage.exportId)
                || appliedVillage.wantAmt != 3 || !"minecraft:iron_block".equals(appliedVillage.wantId))
        {
            helper.fail("Computed village offer is wrong: sends " + appliedVillage.exportAmt + " "
                    + appliedVillage.exportId + " for " + appliedVillage.wantAmt + " " + appliedVillage.wantId);
            return;
        }

        UUID wagonId = appliedPlayer.wagonId;
        helper.succeedWhen(() ->
        {
            DockRegistry.Record current = registry.peek(playerAbs);
            if (current == null || !(level.getEntity(current.wagonId) instanceof WagonCoachEntity wagon))
                throw new GameTestAssertException("Computed-offer wagon record or entity is missing");
            if (!wagon.isIdle())
                throw new GameTestAssertException("Waiting for the computed barter wagon to return");
            if (DispatchTestProbe.dispatchesFor(wagonId) < 1)
                throw new GameTestAssertException("The computed barter route did not dispatch its wagon");
            if (countItem(chest, Items.IRON_BLOCK) != 0)
                throw new GameTestAssertException("The 3 iron blocks were not fully consumed; remaining="
                        + countItem(chest, Items.IRON_BLOCK));
            if (countItem(chest, Items.OAK_LOG) < 32)
                throw new GameTestAssertException("The computed 32 oak logs were not returned; oak="
                        + countItem(chest, Items.OAK_LOG));
            helper.succeed();
        });
    }

    private static String tradeIdFor(List<Component> report, String expected)
    {
        if (report == null)
            return null;
        for (Component line : report)
        {
            Matcher matcher = TRADE_ID.matcher(line.getString());
            if (matcher.find() && expected.equals(matcher.group(1)))
                return matcher.group(1);
        }
        return null;
    }

    private static int countItem(ChestBlockEntity chest, net.minecraft.world.item.Item item)
    {
        int count = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++)
            if (chest.getItem(slot).is(item))
                count += chest.getItem(slot).getCount();
        return count;
    }
}
