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
 * Server-side equivalent of the production UI flow: a blank player dock requests the native route
 * report, chooses a multiplier before selecting a trade, selects the reported village option, and
 * later changes the multiplier again after the menu is reconstructed. All assertions use the real
 * DockRegistry records and CompatCaravanDockMenu data backing the client screen.
 */
@GameTestHolder(com.cacxve.core.CaravansAndConvoysCompat.MOD_ID)
public class UiMultiplierFlowGameTest
{
    private static final Pattern TRADE_ID = Pattern.compile("\\[trade=([^]]+)]");
    private static final Config.TradeDefinition FLOW_TRADE =
            new Config.TradeDefinition("ui_flow_trade", "minecraft:wheat", "minecraft:iron_ingot", 24, 4, "");

    @GameTest(template = "dispatch_loop", timeoutTicks = 16000)
    public static void pendingMultiplierSurvivesReportSelectionAndMenuReopen(GameTestHelper helper)
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
            helper.fail("UI multiplier flow fixture is missing its native block entities");
            return;
        }

        List<Config.TradeDefinition> trades = new ArrayList<>(Config.villageTrades);
        trades.removeIf(trade -> trade.id().equals(FLOW_TRADE.id()));
        trades.add(FLOW_TRADE);
        Config.setVillageTrades(trades);

        BlockPos playerAbs = helper.absolutePos(playerRel);
        BlockPos villageAbs = helper.absolutePos(villageRel);
        ServerPlayer player = FakePlayerFactory.getMinecraft(level);
        UUID playerId = player.getUUID();
        String factionId = "village_" + villageAbs.getX() + "_" + villageAbs.getY() + "_" + villageAbs.getZ();
        VillageFaction faction = VillageFactionManager.getOrCreate(factionId);
        faction.center = villageAbs;
        faction.specialType = "";
        UUID villageOwner = UUID.nameUUIDFromBytes(("cacxvecompat:village:" + faction.id).getBytes(StandardCharsets.UTF_8));

        DockRegistry registry = DockRegistry.get(level);
        DockRegistry.Record playerRecord = registry.record(playerAbs);
        playerRecord.owner = playerId;
        playerDock.setOwner(playerId);
        playerDock.wagonSlot().setItem(0, WagonRig.anyWagonItem());
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, FLOW_TRADE.villageImportQuantity() * 5));

        DockRegistry.Record villageRecord = registry.record(villageAbs);
        villageRecord.owner = villageOwner;
        villageRecord.ownerName = "Flow Test Village";
        villageRecord.creative = true;
        VillageTradeManager.applyVillageTrade(villageRecord, FLOW_TRADE);
        villageDock.setOwner(villageOwner);
        registry.setDirty();

        VillageTradeManager.setRouteMultiplier(level, player, playerAbs, 5);
        List<Component> report = registry.matchReport(level, playerAbs, playerDock);
        String selectedTradeId = tradeIdFor(report, FLOW_TRADE.id());
        if (selectedTradeId == null)
        {
            helper.fail("Native route report did not list the faction-backed village for a blank player dock: " + report);
            return;
        }

        VillageTradeManager.selectRoute(level, player, playerAbs, villageAbs, selectedTradeId,
                VillageTradeManager.routeMultiplier(playerAbs));
        assertMenuState(player, playerDock, 20, 120, 5, "initial pending multiplier");

        UUID wagonId = registry.peek(playerAbs).wagonId;
        final int[] phase = {0};
        helper.succeedWhen(() ->
        {
            DockRegistry.Record current = registry.peek(playerAbs);
            if (current == null || !(level.getEntity(current.wagonId) instanceof WagonCoachEntity wagon))
                throw new GameTestAssertException("UI-flow wagon record or entity is missing");
            if (!wagon.isIdle())
                throw new GameTestAssertException("Waiting for the selected " + (phase[0] == 0 ? 5 : 3) + "x trade to return");
            if (DispatchTestProbe.dispatchesFor(wagonId) < phase[0] + 1)
                throw new GameTestAssertException("The selected bulk route did not dispatch its wagon");

            if (phase[0] == 0)
            {
                if (countItem(chest, Items.IRON_INGOT) != 0)
                    throw new GameTestAssertException("The initial 5x payment was not fully consumed");
                if (countItem(chest, Items.WHEAT) < FLOW_TRADE.villageExportQuantity() * 5)
                    throw new GameTestAssertException("The initial 5x cargo was not returned");

                VillageTradeManager.setRouteMultiplier(level, player, playerAbs, 3);
                assertMenuState(player, playerDock, 12, 72, 3, "reopened menu after changing multiplier");
                addPayment(chest, 12);
                registry.serviceDock(level, playerAbs, playerDock, true);
                phase[0] = 1;
                throw new GameTestAssertException("Changed multiplier to 3x and started the return-trip regression leg");
            }

            if (DispatchTestProbe.dispatchesFor(wagonId) < 2)
                throw new GameTestAssertException("The 3x update did not redispatch the existing wagon");
            if (countItem(chest, Items.IRON_INGOT) != 0)
                throw new GameTestAssertException("The 3x payment was not fully consumed; remaining="
                        + countItem(chest, Items.IRON_INGOT));
            int returnedWheat = countItem(chest, Items.WHEAT);
            int expectedWheat = FLOW_TRADE.villageExportQuantity() * 8;
            if (returnedWheat < expectedWheat)
                throw new GameTestAssertException("The 5x + 3x returned cargo totals are incorrect; wheat="
                        + returnedWheat + ", expected=" + expectedWheat + ", dispatches="
                        + DispatchTestProbe.dispatchesFor(wagonId));
            assertMenuState(player, playerDock, 12, 72, 3, "persisted menu after both wagon trips");
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

    private static void assertMenuState(ServerPlayer player, CaravanDockBlockEntity dock,
                                        int exportAmount, int importAmount, int multiplier, String context)
    {
        CompatCaravanDockMenu menu = new CompatCaravanDockMenu(1, player.getInventory(), dock);
        if (menu.exportAmount() != exportAmount || menu.importAmount() != importAmount || menu.multiplier() != multiplier)
            throw new GameTestAssertException(context + " exposed menu state export=" + menu.exportAmount()
                    + " import=" + menu.importAmount() + " multiplier=" + menu.multiplier()
                    + "; expected " + exportAmount + "/" + importAmount + "/" + multiplier + "x");
    }

    private static void addPayment(ChestBlockEntity chest, int amount)
    {
        for (int slot = 0; slot < chest.getContainerSize(); slot++)
        {
            ItemStack existing = chest.getItem(slot);
            if (existing.isEmpty())
            {
                chest.setItem(slot, new ItemStack(Items.IRON_INGOT, amount));
                return;
            }
            if (existing.is(Items.IRON_INGOT) && existing.getCount() + amount <= existing.getMaxStackSize())
            {
                existing.grow(amount);
                chest.setChanged();
                return;
            }
        }
        throw new GameTestAssertException("No chest space remained for the second bulk payment");
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
