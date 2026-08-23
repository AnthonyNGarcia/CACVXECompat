package com.cacxve.mixin;

import com.cacxve.core.RouteNetwork;
import com.warborn.caravansconvoys.client.CaravanDockScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CaravanDockScreen.class)
public class CaravanDockStatusMixin
{
    @Inject(method = "renderLabels", at = @At("TAIL"))
    private void renderRouteStatus(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo callback)
    {
        CaravanDockScreen screen = (CaravanDockScreen) (Object) this;
        graphics.drawString(screen.getMinecraft().font, Component.literal("Route: " + RouteNetwork.currentStatus()), 8, 207, 0xFFFFFF, false);
    }
}