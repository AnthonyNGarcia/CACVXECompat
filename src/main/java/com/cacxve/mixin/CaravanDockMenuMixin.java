package com.cacxve.mixin;

import com.warborn.caravansconvoys.block.CaravanDockBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(com.warborn.caravansconvoys.menu.CaravanDockMenu.class)
public abstract class CaravanDockMenuMixin extends AbstractContainerMenu
{
    protected CaravanDockMenuMixin()
    {
        super(null, -1);
    }

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lcom/warborn/caravansconvoys/block/CaravanDockBlockEntity;)V", at = @At("TAIL"))
    private void addWagonSlot(int containerId, Inventory inventory, CaravanDockBlockEntity dock, CallbackInfo callback)
    {
        addSlot(new Slot(dock.wagonSlot(), 0, 68, 68));
    }

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("TAIL"))
    private void addClientWagonSlot(int containerId, Inventory inventory, FriendlyByteBuf buffer, CallbackInfo callback)
    {
        addSlot(new Slot(new SimpleContainer(1), 0, 68, 68));
    }

}