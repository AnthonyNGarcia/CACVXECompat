package com.cacxve.core;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;
import com.warborn.caravansconvoys.trade.DockRegistry;
import com.warborn.caravansconvoys.entity.WagonCoachEntity;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;
import java.util.HashMap;
import java.util.Map;
import net.minecraftforge.items.IItemHandler;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class RouteNetwork
{
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(CaravansAndConvoysCompat.MOD_ID, "routes"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );
    private static final Map<ServerPlayer, Integer> STATUS_TICKS = new HashMap<>();
    private static String currentStatus = "No active village route";
    private static Screen previousRouteScreen;
    private static int selectedMultiplier = 1;

    private RouteNetwork()
    {
    }

    public static void register()
    {
        CHANNEL.registerMessage(0, SelectRouteMessage.class, SelectRouteMessage::encode,
                SelectRouteMessage::decode, SelectRouteMessage::handle);
        CHANNEL.registerMessage(1, RouteStatusMessage.class, RouteStatusMessage::encode,
            RouteStatusMessage::decode, RouteStatusMessage::handle);
        CHANNEL.registerMessage(2, SetMultiplierMessage.class, SetMultiplierMessage::encode,
            SetMultiplierMessage::decode, SetMultiplierMessage::handle);
    }

    public static void tickStatus(MinecraftServer server)
    {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (!(player.containerMenu instanceof CompatCaravanDockMenu menu))
                continue;
            int ticks = STATUS_TICKS.getOrDefault(player, 0) - 1;
            if (ticks > 0)
            {
                STATUS_TICKS.put(player, ticks);
                continue;
            }
            STATUS_TICKS.put(player, 10);
            ServerLevel level = player.serverLevel();
            DockRegistry.Record record = DockRegistry.get(level).peek(menu.dockPos());
            BlockPos target = VillageTradeManager.selectedRoute(menu.dockPos());
            String status = "Paused: no active route";
            if (record != null && record.operating && record.wagonId != null)
            {
                if (level.getEntity(record.wagonId) instanceof WagonCoachEntity wagon)
                    status = wagon.isIdle() ? "Returning" : "En route";
                else
                    status = "Paused: wagon unavailable";
            }
            if (record != null && !record.operating && target != null)
            {
                BlockEntity blockEntity = level.getBlockEntity(menu.dockPos());
                if (blockEntity instanceof com.warborn.caravansconvoys.block.CaravanDockBlockEntity dock
                        && count(dock.neighborInventory(), record.exportId) < record.exportAmt)
                    status = "Paused: insufficient export materials";
            }
            DockRegistry.Record targetRecord = target == null ? null : DockRegistry.get(level).peek(target);
                String route = targetRecord == null ? "No village selected" : targetRecord.ownerName;
            CHANNEL.sendTo(new RouteStatusMessage(route, status, VillageTradeManager.routeMultiplier(menu.dockPos())), player.connection.connection, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
        }
    }

    private static int count(IItemHandler inventory, String itemId)
    {
        if (inventory == null || itemId == null)
            return 0;
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(itemId));
        if (item == null)
            return 0;
        int total = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++)
            if (inventory.getStackInSlot(slot).is(item))
                total += inventory.getStackInSlot(slot).getCount();
        return total;
    }

    public static String currentStatus()
    {
        return currentStatus;
    }

    public static void rememberRouteScreen(Screen screen)
    {
        previousRouteScreen = screen;
    }

    public static Screen previousRouteScreen()
    {
        return previousRouteScreen;
    }

    public static int selectedMultiplier()
    {
        return selectedMultiplier;
    }

    public static void setSelectedMultiplier(int multiplier)
    {
        if (multiplier == 1 || multiplier == 2 || multiplier == 3 || multiplier == 5 || multiplier == 10)
            selectedMultiplier = multiplier;
    }

    public static void selectMultiplier(BlockPos dock, int multiplier)
    {
        setSelectedMultiplier(multiplier);
        CHANNEL.sendToServer(new SetMultiplierMessage(dock, multiplier));
    }

    private record RouteStatusMessage(String route, String status, int multiplier)
    {
        private static void encode(RouteStatusMessage message, FriendlyByteBuf buffer)
        {
            buffer.writeUtf(message.route, 128);
            buffer.writeUtf(message.status, 128);
            buffer.writeVarInt(message.multiplier);
        }

        private static RouteStatusMessage decode(FriendlyByteBuf buffer)
        {
            return new RouteStatusMessage(buffer.readUtf(128), buffer.readUtf(128), buffer.readVarInt());
        }

        private static void handle(RouteStatusMessage message, Supplier<NetworkEvent.Context> contextSupplier)
        {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> { currentStatus = message.route + " | " + message.status; setSelectedMultiplier(message.multiplier); });
            context.setPacketHandled(true);
        }
    }

    private record SetMultiplierMessage(BlockPos dock, int multiplier)
    {
        private static void encode(SetMultiplierMessage message, FriendlyByteBuf buffer)
        { buffer.writeBlockPos(message.dock); buffer.writeVarInt(message.multiplier); }
        private static SetMultiplierMessage decode(FriendlyByteBuf buffer)
        { return new SetMultiplierMessage(buffer.readBlockPos(), buffer.readVarInt()); }
        private static void handle(SetMultiplierMessage message, Supplier<NetworkEvent.Context> contextSupplier)
        {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> { ServerPlayer player = context.getSender(); if (player != null && player.level() instanceof ServerLevel level) VillageTradeManager.setRouteMultiplier(level, player, message.dock, message.multiplier); });
            context.setPacketHandled(true);
        }
    }

    public static void select(BlockPos dock, BlockPos target, String tradeId)
    {
        CHANNEL.sendToServer(new SelectRouteMessage(dock, target, tradeId, selectedMultiplier));
    }

    private record SelectRouteMessage(BlockPos dock, BlockPos target, String tradeId, int multiplier)
    {
        private static void encode(SelectRouteMessage message, FriendlyByteBuf buffer)
        {
            buffer.writeBlockPos(message.dock);
            buffer.writeBlockPos(message.target);
            buffer.writeUtf(message.tradeId);
            buffer.writeVarInt(message.multiplier);
        }

        private static SelectRouteMessage decode(FriendlyByteBuf buffer)
        {
            return new SelectRouteMessage(buffer.readBlockPos(), buffer.readBlockPos(), buffer.readUtf(128), buffer.readVarInt());
        }

        private static void handle(SelectRouteMessage message, Supplier<NetworkEvent.Context> contextSupplier)
        {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && player.level() instanceof ServerLevel level)
                    VillageTradeManager.selectRoute(level, player, message.dock, message.target, message.tradeId, message.multiplier);
            });
            context.setPacketHandled(true);
        }
    }
}