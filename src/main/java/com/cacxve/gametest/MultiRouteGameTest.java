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
import com.mojang.authlib.GameProfile;

import java.util.UUID;

@GameTestHolder(CaravansAndConvoysCompat.MOD_ID)
public class MultiRouteGameTest
{
    private static final Config.TradeDefinition TEST_TRADE =
            new Config.TradeDefinition("test_trade", "minecraft:wheat", "minecraft:iron_ingot", 24, 4, "");
    private static final int LOOPS = 3;
    private static final int SHIPMENT_COUNT = LOOPS * TEST_TRADE.villageImportQuantity();

    @GameTest(template = "dispatch_loop", timeoutTicks = 12000)
    public static void twoPlayerRoutesToOneCreativeVillage(GameTestHelper helper)
    {
        runScenario(helper, new BlockPos[][] {
                {new BlockPos(1, 1, 1), new BlockPos(8, 1, 1)},
            {new BlockPos(1, 1, 8), new BlockPos(8, 1, 1)}
        });
    }

    @GameTest(template = "dispatch_loop", timeoutTicks = 12000)
    public static void twoPlayerRoutesToDifferentCreativeVillages(GameTestHelper helper)
    {
        runScenario(helper, new BlockPos[][] {
                {new BlockPos(1, 1, 1), new BlockPos(8, 1, 1)},
                {new BlockPos(1, 1, 8), new BlockPos(8, 1, 8)}
        });
    }

    @GameTest(template = "dispatch_loop", timeoutTicks = 16000)
    public static void crossRoutedPlayersAndVillages(GameTestHelper helper)
    {
        runScenario(helper, new BlockPos[][] {
                {new BlockPos(1, 1, 1), new BlockPos(8, 1, 8)},
                {new BlockPos(1, 1, 8), new BlockPos(8, 1, 1)}
        });
    }

    @GameTest(template = "dispatch_loop", timeoutTicks = 18000)
    public static void threePlayerDocksToOneCreativeVillage(GameTestHelper helper)
    {
        runScenario(helper, new BlockPos[][] {
                {new BlockPos(1, 1, 1), new BlockPos(10, 1, 8)},
                {new BlockPos(1, 1, 8), new BlockPos(10, 1, 8)},
                {new BlockPos(1, 1, 15), new BlockPos(10, 1, 8)}
        });
    }

    @GameTest(template = "dispatch_loop", timeoutTicks = 18000)
    public static void threePlayerDocksToThreeCreativeVillages(GameTestHelper helper)
    {
        runScenario(helper, new BlockPos[][] {
                {new BlockPos(1, 1, 1), new BlockPos(10, 1, 1)},
                {new BlockPos(1, 1, 8), new BlockPos(10, 1, 8)},
                {new BlockPos(1, 1, 15), new BlockPos(10, 1, 15)}
        });
    }

    @GameTest(template = "dispatch_loop", timeoutTicks = 18000)
    public static void threeByThreePermutationRoutes(GameTestHelper helper)
    {
        runScenario(helper, new BlockPos[][] {
                {new BlockPos(1, 1, 1), new BlockPos(10, 1, 15)},
                {new BlockPos(1, 1, 8), new BlockPos(10, 1, 1)},
                {new BlockPos(1, 1, 15), new BlockPos(10, 1, 8)}
        });
    }

    @GameTest(template = "dispatch_loop", timeoutTicks = 12000)
    public static void differentPlayersToOneCreativeVillage(GameTestHelper helper)
    {
        runScenario(helper, new BlockPos[][] {
                {new BlockPos(1, 1, 1), new BlockPos(10, 1, 8)},
                {new BlockPos(1, 1, 8), new BlockPos(10, 1, 8)},
                {new BlockPos(1, 1, 15), new BlockPos(10, 1, 8)}
        }, true);
    }

    @GameTest(template = "dispatch_loop", timeoutTicks = 200)
    public static void publicDockAllowsAnotherPlayerToStartOperation(GameTestHelper helper)
    {
        ServerLevel level = helper.getLevel();
        BlockPos dockPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chestPos = helper.absolutePos(new BlockPos(1, 1, 0));
        helper.setBlock(new BlockPos(1, 1, 1), CaravansConvoys.CARAVAN_DOCK.get());
        helper.setBlock(new BlockPos(1, 1, 0), Blocks.CHEST);

        if (!(level.getBlockEntity(dockPos) instanceof CaravanDockBlockEntity dock)
                || !(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest))
        {
            helper.fail("public dock fixture missing");
            return;
        }
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 4));

        ServerPlayer owner = FakePlayerFactory.get(level,
                new GameProfile(UUID.randomUUID(), "DockOwner"));
        ServerPlayer visitor = FakePlayerFactory.get(level,
                new GameProfile(UUID.randomUUID(), "DockVisitor"));
        DockRegistry.Record record = DockRegistry.get(level).record(dockPos);
        record.owner = owner.getUUID();
        VillageTradeManager.applyPlayerTrade(record, TEST_TRADE);
        dock.setOwner(owner.getUUID());
        dock.wagonSlot().setItem(0, WagonRig.anyWagonItem());

        Config.ALLOW_PUBLIC_CARAVAN_DOCKS.set(true);
        boolean started = DockRegistry.get(level).startOperation(level, dockPos, dock, visitor);
        Config.ALLOW_PUBLIC_CARAVAN_DOCKS.set(false);
        if (!started)
        {
            helper.fail("configured public access did not allow another player to start the dock");
            return;
        }
        helper.succeed();
    }

    private static void runScenario(GameTestHelper helper, BlockPos[][] routes)
    {
        runScenario(helper, routes, false);
    }

    private static void runScenario(GameTestHelper helper, BlockPos[][] routes, boolean differentPlayers)
    {
        ServerLevel level = helper.getLevel();
        DockRegistry registry = DockRegistry.get(level);
        ServerPlayer sharedPlayer = FakePlayerFactory.getMinecraft(level);
        Route[] configured = new Route[routes.length];

        for (int index = 0; index < routes.length; index++)
        {
            BlockPos playerRel = routes[index][0];
            BlockPos villageRel = routes[index][1];
            BlockPos chestRel = playerRel.north();
            helper.setBlock(playerRel, CaravansConvoys.CARAVAN_DOCK.get());
            helper.setBlock(villageRel, CaravansConvoys.CARAVAN_DOCK.get());
            helper.setBlock(chestRel, Blocks.CHEST);
            helper.setBlock(villageRel, CaravansConvoys.CREATIVE_DOCK.get());

            if (!(helper.getBlockEntity(chestRel) instanceof ChestBlockEntity chest))
            {
                helper.fail("player chest missing for route " + index);
                return;
            }
            chest.setItem(0, new ItemStack(Items.IRON_INGOT, SHIPMENT_COUNT));

            BlockPos playerAbs = helper.absolutePos(playerRel);
            BlockPos villageAbs = helper.absolutePos(villageRel);
                ServerPlayer player = differentPlayers
                    ? FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "RoutePlayer" + index))
                    : sharedPlayer;
                UUID playerId = player.getUUID();
            if (!(level.getBlockEntity(playerAbs) instanceof CaravanDockBlockEntity playerDock)
                    || !(level.getBlockEntity(villageAbs) instanceof CaravanDockBlockEntity villageDock))
            {
                helper.fail("dock block entity missing for route " + index);
                return;
            }

            DockRegistry.Record playerRecord = registry.record(playerAbs);
            playerRecord.owner = playerId;
            playerRecord.selfTrade = true;
            VillageTradeManager.applyPlayerTrade(playerRecord, TEST_TRADE);
            playerDock.setOwner(playerId);
            playerDock.wagonSlot().setItem(0, WagonRig.anyWagonItem());

            DockRegistry.Record villageRecord = registry.record(villageAbs);
            villageRecord.owner = UUID.randomUUID();
            VillageTradeManager.applyVillageTrade(villageRecord, TEST_TRADE);
            villageDock.setOwner(villageRecord.owner);

            VillageTradeManager.registerSelectedRouteForTest(playerAbs, villageAbs);
            configured[index] = new Route(playerAbs, villageAbs, chest, playerRecord.wagonId, player);
        }

        for (Route route : configured)
        {
            CaravanDockBlockEntity playerDock = (CaravanDockBlockEntity) level.getBlockEntity(route.playerDock);
            if (!registry.startOperation(level, route.playerDock, playerDock, route.player))
            {
                helper.fail("initial startOperation refused for " + route.playerDock);
                return;
            }
            route.wagonId = registry.peek(route.playerDock).wagonId;
            registry.serviceDock(level, route.playerDock, playerDock, true);
        }

        helper.succeedWhen(() ->
        {
            for (Route route : configured)
            {
                DockRegistry.Record record = registry.peek(route.playerDock);
                if (record == null || record.wagonId == null)
                    throw new GameTestAssertException("route lost wagon record at " + route.playerDock);
                if (!route.wagonId.equals(record.wagonId))
                    throw new GameTestAssertException("route replaced its wagon at " + route.playerDock);
                if (!(level.getEntity(record.wagonId) instanceof WagonCoachEntity wagon))
                    throw new GameTestAssertException("route lost wagon entity at " + route.playerDock);
                if (!wagon.isIdle())
                    throw new GameTestAssertException("route has not completed " + LOOPS + " loops yet");
                if (DispatchTestProbe.dispatchesFor(route.wagonId) < LOOPS)
                    throw new GameTestAssertException("route has completed only "
                            + DispatchTestProbe.dispatchesFor(route.wagonId) + " of " + LOOPS + " loops");
                if (countItem(route.playerChest, Items.IRON_INGOT) != 0)
                    throw new GameTestAssertException("outbound iron was not fully consumed at " + route.playerDock);
                if (countItem(route.playerChest, Items.WHEAT) < LOOPS * TEST_TRADE.villageExportQuantity())
                    throw new GameTestAssertException("returned wheat missing at " + route.playerDock);
            }
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

    private static final class Route
    {
        private final BlockPos playerDock;
        private final ChestBlockEntity playerChest;
        private UUID wagonId;
        private final ServerPlayer player;

        private Route(BlockPos playerDock, BlockPos villageDock, ChestBlockEntity playerChest, UUID wagonId, ServerPlayer player)
        {
            this.playerDock = playerDock;
            this.playerChest = playerChest;
            this.wagonId = wagonId;
            this.player = player;
        }
    }
}
