package com.cacxve.gametest;

import com.cacxve.core.Config;
import com.cacxve.core.DispatchTestProbe;
import com.cacxve.core.VillageTradeManager;
import com.warborn.caravansconvoys.CaravansConvoys;
import com.warborn.caravansconvoys.entity.WagonCoachEntity;
import com.warborn.caravansconvoys.trade.DockRegistry;
import com.warborn.caravansconvoys.wagon.WagonRig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;

import java.util.UUID;

@GameTestHolder(com.cacxve.core.CaravansAndConvoysCompat.MOD_ID)
public class BulkTradeMultiplierGameTest
{
    private static final Config.TradeDefinition BASE_TRADE =
            new Config.TradeDefinition("bulk_test", "minecraft:wheat", "minecraft:iron_ingot", 24, 4, "");

    @GameTest(template = "dispatch_loop", timeoutTicks = 12000)
    public static void oneTimesMultiplierUsesBaseQuantities(GameTestHelper helper)
    {
        runScenario(helper, 1);
    }

    @GameTest(template = "dispatch_loop", timeoutTicks = 12000)
    public static void twoTimesMultiplierScalesNativeTrade(GameTestHelper helper)
    {
        runScenario(helper, 2);
    }

    @GameTest(template = "dispatch_loop", timeoutTicks = 12000)
    public static void threeTimesMultiplierScalesNativeTrade(GameTestHelper helper)
    {
        runScenario(helper, 3);
    }

    @GameTest(template = "dispatch_loop", timeoutTicks = 12000)
    public static void fiveTimesMultiplierScalesNativeTrade(GameTestHelper helper)
    {
        runScenario(helper, 5);
    }

    @GameTest(template = "dispatch_loop", timeoutTicks = 12000)
    public static void tenTimesMultiplierScalesNativeTrade(GameTestHelper helper)
    {
        runScenario(helper, 10);
    }

    private static void runScenario(GameTestHelper helper, int multiplier)
    {
        ServerLevel level = helper.getLevel();
        BlockPos playerRel = new BlockPos(1, 1, 1);
        BlockPos villageRel = new BlockPos(8, 1, 1);
        BlockPos chestRel = playerRel.north();
        helper.setBlock(playerRel, CaravansConvoys.CARAVAN_DOCK.get());
        helper.setBlock(villageRel, CaravansConvoys.CREATIVE_DOCK.get());
        helper.setBlock(chestRel, Blocks.CHEST);

        if (!(helper.getBlockEntity(chestRel) instanceof ChestBlockEntity chest))
        {
            helper.fail("bulk multiplier test chest missing");
            return;
        }
        Config.TradeDefinition trade = Config.multiplyTrade(BASE_TRADE, multiplier);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, trade.villageImportQuantity()));
        if (!(helper.getBlockEntity(playerRel) instanceof com.warborn.caravansconvoys.block.CaravanDockBlockEntity playerDock))
        {
            helper.fail("bulk multiplier player dock missing");
            return;
        }

        BlockPos playerAbs = helper.absolutePos(playerRel);
        BlockPos villageAbs = helper.absolutePos(villageRel);
        ServerPlayer fakePlayer = FakePlayerFactory.getMinecraft(level);
        UUID owner = fakePlayer.getUUID();
        DockRegistry registry = DockRegistry.get(level);
        DockRegistry.Record playerRecord = registry.record(playerAbs);
        playerRecord.owner = owner;
        playerRecord.selfTrade = true;
        VillageTradeManager.applyPlayerTrade(playerRecord, trade);
        DockRegistry.Record villageRecord = registry.record(villageAbs);
        villageRecord.owner = owner;
        VillageTradeManager.applyVillageTrade(villageRecord, trade);
        playerDock.setOwner(owner);
        playerDock.wagonSlot().setItem(0, WagonRig.anyWagonItem());
        VillageTradeManager.registerSelectedRouteForTest(playerAbs, villageAbs);

        if (playerRecord.exportAmt != trade.villageImportQuantity() || playerRecord.wantAmt != trade.villageExportQuantity())
            throw new GameTestAssertException("Native player record did not receive the " + multiplier + "x quantities");
        if (!registry.startOperation(level, playerAbs, playerDock, fakePlayer))
        {
            helper.fail("bulk multiplier startOperation refused");
            return;
        }
        registry.serviceDock(level, playerAbs, playerDock, true);
        UUID wagonId = registry.peek(playerAbs).wagonId;
        helper.succeedWhen(() -> {
            if (DispatchTestProbe.dispatchesFor(wagonId) < 1)
                throw new GameTestAssertException("Waiting for " + multiplier + "x dispatch");
            if (!(level.getEntity(wagonId) instanceof WagonCoachEntity wagon) || !wagon.isIdle())
                throw new GameTestAssertException("Waiting for bulk wagon to return home");
            if (countItem(chest, Items.IRON_INGOT) != 0)
                throw new GameTestAssertException("Bulk trade did not consume the scaled payment");
            if (countItem(chest, Items.WHEAT) < trade.villageExportQuantity())
                throw new GameTestAssertException("Bulk trade did not return the scaled cargo");
            helper.succeed();
        });
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