package com.cacxve.gametest;

import com.cacxve.core.CaravansAndConvoysCompat;
import com.cacxve.core.Config;
import com.cacxve.core.DispatchTestProbe;
import com.cacxve.core.VillageTradeManager;
import com.warborn.caravansconvoys.CaravansConvoys;
import com.warborn.caravansconvoys.block.CaravanDockBlockEntity;
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

/**
 * Real, non-mocked reproduction of the reported dispatch-loop bugs, using the actual
 * DockRegistry/WagonCoachEntity/CaravanDockBlockEntity classes running in a live GameTest server.
 * Deliberately calls DockRegistry.serviceDock() directly (exactly as WagonReturnMixin's hook does in
 * production) rather than going through VillageTradeManager.tick()'s safety-net poll, so this isolates
 * whether the single native call actually redispatches a returned, idle wagon.
 */
@GameTestHolder(CaravansAndConvoysCompat.MOD_ID)
public class DispatchLoopGameTest
{
    private static final Config.TradeDefinition TEST_TRADE =
            new Config.TradeDefinition("test_trade", "minecraft:wheat", "minecraft:iron_ingot", 24, 4, "");

    @GameTest(template = "dispatch_loop", timeoutTicks = 12000)
    public static void wagonRedispatchesAfterReturningHome(GameTestHelper helper)
    {
        ServerLevel level = helper.getLevel();
        BlockPos playerRel = new BlockPos(1, 1, 1);
        BlockPos villageRel = new BlockPos(6, 1, 1);
        BlockPos playerChestRel = playerRel.north();

        helper.setBlock(playerRel, CaravansConvoys.CARAVAN_DOCK.get());
        helper.setBlock(villageRel, CaravansConvoys.CREATIVE_DOCK.get());
        helper.setBlock(playerChestRel, Blocks.CHEST);

        if (!(helper.getBlockEntity(playerChestRel) instanceof ChestBlockEntity playerChest))
        {
            helper.fail("player chest block entity missing");
            return;
        }
        playerChest.setItem(0, new ItemStack(Items.IRON_INGOT, 20));

        if (!(helper.getBlockEntity(playerRel) instanceof CaravanDockBlockEntity playerDock))
        {
            helper.fail("player dock block entity missing");
            return;
        }
        if (!(helper.getBlockEntity(villageRel) instanceof CaravanDockBlockEntity))
        {
            helper.fail("village dock block entity missing");
            return;
        }

        BlockPos playerAbs = helper.absolutePos(playerRel);
        BlockPos villageAbs = helper.absolutePos(villageRel);

        ServerPlayer fakePlayer = FakePlayerFactory.getMinecraft(level);
        UUID playerUuid = fakePlayer.getUUID();
        DockRegistry registry = DockRegistry.get(level);
        DockRegistry.Record playerRecord = registry.record(playerAbs);
        playerRecord.owner = playerUuid;
        playerRecord.selfTrade = true;
        VillageTradeManager.applyPlayerTrade(playerRecord, TEST_TRADE);

        DockRegistry.Record villageRecord = registry.record(villageAbs);
        villageRecord.owner = playerUuid;
        VillageTradeManager.applyVillageTrade(villageRecord, TEST_TRADE);

        playerDock.setOwner(playerUuid);
        playerDock.wagonSlot().setItem(0, WagonRig.anyWagonItem());
        VillageTradeManager.registerSelectedRouteForTest(playerAbs, villageAbs);

        if (!registry.startOperation(level, playerAbs, playerDock, fakePlayer))
        {
            helper.fail("startOperation refused - test setup is invalid, not a real reproduction");
            return;
        }
        if (registry.peek(playerAbs).wagonId == null)
        {
            helper.fail("startOperation did not assign a wagon id");
            return;
        }
        registry.serviceDock(level, playerAbs, playerDock, true);

        UUID originalWagonId = registry.peek(playerAbs).wagonId;
        final boolean[] wagonWasAwayFromHome = {false};
        helper.succeedWhen(() ->
        {
            int dispatches = DispatchTestProbe.dispatchesFor(originalWagonId);
            if (dispatches < 1)
                throw new GameTestAssertException("Waiting for the first dispatch to occur");

            DockRegistry.Record record = registry.peek(playerAbs);
            if (record == null || record.wagonId == null)
                throw new GameTestAssertException("Wagon record missing during loop " + (dispatches + 1));
            if (!originalWagonId.equals(record.wagonId))
                throw new GameTestAssertException("Native serviceDock replaced the wagon instead of reusing it");
            if (!(level.getEntity(record.wagonId) instanceof WagonCoachEntity wagon))
                throw new GameTestAssertException("Wagon entity missing during loop " + (dispatches + 1));
            if (wagon.blockPosition().distSqr(playerAbs) > 4.0D)
                wagonWasAwayFromHome[0] = true;
            if (!wagon.isIdle())
                throw new GameTestAssertException("Waiting for the wagon to complete its round trip");

            if (dispatches < 5)
                throw new GameTestAssertException(
                    "Wagon did not complete 5 dispatch loops (wagonDispatchCount=" + dispatches
                        + ", expected 5) at tick " + helper.getTick());

            if (!wagonWasAwayFromHome[0])
                throw new GameTestAssertException("Wagon dispatch was recorded but the entity never left its home dock");
            if (countItem(playerChest, Items.WHEAT) < 24)
                throw new GameTestAssertException("Player chest never received returned wheat");
            if (countItem(playerChest, Items.IRON_INGOT) != 20 - 5 * TEST_TRADE.villageImportQuantity())
                throw new GameTestAssertException("Player chest did not lose the five outbound iron shipments");

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
