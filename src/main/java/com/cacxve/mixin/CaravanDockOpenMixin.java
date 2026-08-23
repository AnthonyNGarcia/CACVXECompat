package com.cacxve.mixin;

import com.cacxve.core.CompatCaravanDockMenu;
import com.cacxve.core.VillageTradeManager;
import com.warborn.caravansconvoys.block.CaravanDockBlock;
import com.warborn.caravansconvoys.block.CaravanDockBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CaravanDockBlock.class)
public class CaravanDockOpenMixin
{
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void openCompatDock(BlockState state, Level level, BlockPos pos, Player player,
                                InteractionHand hand, BlockHitResult hit,
                                CallbackInfoReturnable<InteractionResult> callback)
    {
        if (!(level instanceof ServerLevel serverLevel)
                || !(player instanceof ServerPlayer serverPlayer)
                || !(serverLevel.getBlockEntity(pos) instanceof CaravanDockBlockEntity dock))
            return;
        if (VillageTradeManager.isVillageEndpoint(serverLevel, pos))
            return;
        if (!VillageTradeManager.canAccessDock(dock.getOwner(), serverPlayer.getUUID(), false))
        {
            callback.setReturnValue(InteractionResult.FAIL);
            return;
        }
        NetworkHooks.openScreen(serverPlayer,
                new SimpleMenuProvider((containerId, inventory, menuPlayer) ->
                        new CompatCaravanDockMenu(containerId, inventory, dock),
                        Component.literal("Caravan Dock")),
                buffer -> buffer.writeBlockPos(pos));
        callback.setReturnValue(InteractionResult.sidedSuccess(false));
    }
}