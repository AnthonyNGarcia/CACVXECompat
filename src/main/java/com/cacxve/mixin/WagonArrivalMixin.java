package com.cacxve.mixin;

import com.warborn.caravansconvoys.entity.WagonCoachEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Constant;

@Mixin(WagonCoachEntity.class)
public class WagonArrivalMixin
{
    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = 9.0D, ordinal = 1))
    private double cacxvecompat$expandFinalDockArrival(double original)
    {
        return 144.0D;
    }
}