package com.cacxve.mixin;

import com.warborn.caravansconvoys.client.CaravanPanelScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

@Mixin(CaravanPanelScreen.class)
public class CaravanPanelScreenMixin
{
    @Inject(method = "init", at = @At("TAIL"))
    private void hideLegacyDispatchButtons(CallbackInfo callback)
    {
        CaravanPanelScreen screen = (CaravanPanelScreen) (Object) this;
        for (GuiEventListener child : new ArrayList<>(screen.children()))
        {
            if (child instanceof Button button)
            {
                String label = button.getMessage().getString();
                if (label.equalsIgnoreCase("Start Operation") || label.equalsIgnoreCase("Send Now"))
                    ((ScreenAccessor) (Object) screen).cacxvecompat$removeWidget(button);
            }
        }
    }
}