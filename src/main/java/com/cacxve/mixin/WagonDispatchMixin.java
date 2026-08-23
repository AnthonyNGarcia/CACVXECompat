package com.cacxve.mixin;

import com.mojang.logging.LogUtils;
import com.cacxve.core.DispatchTestProbe;
import com.warborn.caravansconvoys.entity.WagonCoachEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WagonCoachEntity.class)
public class WagonDispatchMixin
{
    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "dispatch", at = @At("TAIL"))
    private void traceDispatch(BlockPos destination, ItemStack outbound, ItemStack returned, CallbackInfo callback)
    {
        DispatchTestProbe.record(((WagonCoachEntity) (Object) this).getUUID());
        LOGGER.info("Wagon dispatched to {} with outbound {} and return {}", destination, outbound, returned);
    }
}