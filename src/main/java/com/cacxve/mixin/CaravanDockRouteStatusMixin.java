package com.cacxve.mixin;

import com.cacxve.core.RouteNetwork;
import com.warborn.caravansconvoys.client.CaravanDockScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CaravanDockScreen.class)
public class CaravanDockRouteStatusMixin
{
    @Inject(method = "renderLabels", at = @At("TAIL"))
    private void renderRouteStatus(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo callback)
    {
        String status = RouteNetwork.currentStatus();
        graphics.drawString(Minecraft.getInstance().font, Component.literal("Route status"), 112, 88, 0xFFD05A, false);
        java.util.List<net.minecraft.util.FormattedCharSequence> wrapped = Minecraft.getInstance().font.split(Component.literal(status), 104);
        for (int index = 0; index < Math.min(2, wrapped.size()); index++)
            graphics.drawString(Minecraft.getInstance().font, wrapped.get(index), 112, 100 + index * 11, 0xFFFFFF, false);
    }
}