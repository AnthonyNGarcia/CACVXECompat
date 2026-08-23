package com.cacxve.mixin;

import com.cacxve.core.VillageTradeManager;
import com.warborn.caravansconvoys.entity.WagonCoachEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects the exact moment a wagon finishes its return leg and drops cargo at its home dock
 * (native phase becomes idle=0 at the tail of completeArrival). Used to immediately trigger
 * re-dispatch instead of polling on a timer.
 */
@Mixin(WagonCoachEntity.class)
public class WagonReturnMixin
{
    @Shadow
    private BlockPos homePos;

    @Shadow
    private int phase;

    @Inject(method = "completeArrival", at = @At("TAIL"))
    private void cacxvecompat$onCompleteArrival(ServerLevel level, CallbackInfo callback)
    {
        if (this.phase == 0 && this.homePos != null)
        {
            VillageTradeManager.onWagonReturnedHome(level, this.homePos);
        }
    }
}
