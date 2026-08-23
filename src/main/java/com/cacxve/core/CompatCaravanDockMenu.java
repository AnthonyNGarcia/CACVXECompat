package com.cacxve.core;

import com.warborn.caravansconvoys.block.CaravanDockBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

public class CompatCaravanDockMenu extends AbstractContainerMenu
{
    private final ContainerLevelAccess access;
    private final net.minecraft.core.BlockPos pos;
    private final Container samples;
    private final Container wagon;
    private final ContainerData amounts;
    private final int stockCount;

    public CompatCaravanDockMenu(int containerId, Inventory inventory, CaravanDockBlockEntity dock)
    {
        super(CaravansAndConvoysCompat.COMPAT_DOCK_MENU.get(), containerId);
        pos = dock.getBlockPos();
        access = ContainerLevelAccess.create(dock.getLevel(), pos);
        samples = dock.offerSamples();
        wagon = dock.wagonSlot();
        IItemHandler handler = dock.neighborInventory();
        stockCount = handler == null ? 0 : Math.min(27, handler.getSlots());
        amounts = amountsFor(dock);
        addLayout(inventory, handler);
    }

    public CompatCaravanDockMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer)
    {
        super(CaravansAndConvoysCompat.COMPAT_DOCK_MENU.get(), containerId);
        pos = buffer.readBlockPos();
        access = ContainerLevelAccess.NULL;
        samples = new SimpleContainer(2);
        wagon = new SimpleContainer(1);
        stockCount = 27;
        amounts = new SimpleContainerData(3);
        addLayout(inventory, new ItemStackHandler(27));
    }

    private static ContainerData amountsFor(CaravanDockBlockEntity dock)
    {
        DockRegistryAmounts amounts = new DockRegistryAmounts(dock);
        return amounts;
    }

    private void addLayout(Inventory inventory, IItemHandler handler)
    {
        addSlot(new SampleSlot(samples, 0, 80, 20));
        addSlot(new SampleSlot(samples, 1, 80, 44));
        addSlot(new Slot(wagon, 0, 80, 68));
        for (int index = 0; index < stockCount; index++)
            addSlot(new SlotItemHandler(handler, index, 80 + (index % 9) * 18, 88 + (index / 9) * 18));
        for (int row = 0; row < 3; row++)
            for (int column = 0; column < 9; column++)
                addSlot(new Slot(inventory, column + row * 9 + 9, 80 + column * 18, 150 + row * 18));
        for (int column = 0; column < 9; column++)
            addSlot(new Slot(inventory, column, 80 + column * 18, 208));
        addDataSlots(amounts);
    }

    public int exportAmount()
    {
        return amounts.get(0);
    }

    public int importAmount()
    {
        return amounts.get(1);
    }

    public int multiplier()
    {
        return amounts.get(2);
    }

    public net.minecraft.core.BlockPos dockPos()
    {
        return pos;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player)
    {
        if ((slotId == 0 || slotId == 1) && clickType == ClickType.PICKUP)
        {
            Slot slot = slots.get(slotId);
            ItemStack carried = getCarried();
            slot.set(carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
            syncSamplesToRecord();
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index)
    {
        if (index == 0 || index == 1)
        {
            slots.get(index).set(ItemStack.EMPTY);
            syncSamplesToRecord();
            return ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }

    private void syncSamplesToRecord()
    {
        access.execute((level, blockPos) ->
        {
            if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel))
                return;
            ItemStack exportSample = samples.getItem(0);
            ItemStack importSample = samples.getItem(1);
            com.warborn.caravansconvoys.trade.DockRegistry registry =
                    com.warborn.caravansconvoys.trade.DockRegistry.get(serverLevel);
            var record = registry.record(blockPos);
            record.exportId = exportSample.isEmpty() ? ""
                    : net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(exportSample.getItem()).toString();
            record.exportAmt = exportSample.isEmpty() ? 0 : 1;
            record.wantId = importSample.isEmpty() ? ""
                    : net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(importSample.getItem()).toString();
            record.wantAmt = importSample.isEmpty() ? 0 : 1;
            registry.setDirty();
        });
    }

    @Override
    public boolean stillValid(Player player)
    {
        return stillValid(access, player, com.warborn.caravansconvoys.CaravansConvoys.CARAVAN_DOCK.get());
    }

    private static class SampleSlot extends Slot
    {
        SampleSlot(Container container, int index, int x, int y)
        {
            super(container, index, x, y);
        }

        @Override
        public int getMaxStackSize()
        {
            return 1;
        }

        @Override
        public boolean mayPlace(ItemStack stack)
        {
            return false;
        }

        @Override
        public boolean mayPickup(Player player)
        {
            return false;
        }
    }

    private static class DockRegistryAmounts extends SimpleContainerData
    {
        private final CaravanDockBlockEntity dock;

        DockRegistryAmounts(CaravanDockBlockEntity dock)
        {
            super(3);
            this.dock = dock;
        }

        @Override
        public int get(int index)
        {
            if (index == 2)
                return VillageTradeManager.routeMultiplier(dock.getBlockPos());
            var record = com.warborn.caravansconvoys.trade.DockRegistry.get((net.minecraft.server.level.ServerLevel) dock.getLevel()).record(dock.getBlockPos());
            return index == 0 ? record.exportAmt : record.wantAmt;
        }
    }
}
