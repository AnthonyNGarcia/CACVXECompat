package com.cacxve.mixin;

import com.warborn.caravansconvoys.client.CaravanDockScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import com.warborn.caravansconvoys.net.Network;
import com.warborn.caravansconvoys.net.RequestPanelMsg;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(CaravanDockScreen.class)
public class CaravanDockScreenMixin
{
    @Inject(method = "init", at = @At("TAIL"))
    private void hideSettingsTab(CallbackInfo callback)
    {
        CaravanDockScreen screen = (CaravanDockScreen) (Object) this;
        ((AbstractContainerScreenAccessor) (Object) screen).cacxvecompat$setImageHeight(256);
        ((AbstractContainerScreenAccessor) (Object) screen).cacxvecompat$setTopPos((Minecraft.getInstance().getWindow().getGuiScaledHeight() - 256) / 2);
        List<Button> sideButtons = new ArrayList<>();
        for (GuiEventListener child : screen.children())
        {
            if (child instanceof Button button
                    && button.getX() > (Minecraft.getInstance().getWindow().getGuiScaledWidth() - 222) / 2 + 222)
            sideButtons.add(button);
        }
        sideButtons.forEach(button -> ((ScreenAccessor) (Object) screen).cacxvecompat$removeWidget(button));

        int left = screen.getGuiLeft();
        int top = screen.getGuiTop();
        for (GuiEventListener child : new ArrayList<>(screen.children()))
        {
            if (child instanceof Button button
                && button.getX() >= left + 50 && button.getX() <= left + 165
                && button.getY() >= top + 15 && button.getY() <= top + 70)
            ((ScreenAccessor) (Object) screen).cacxvecompat$removeWidget(button);
        }

        ((ScreenAccessor) (Object) screen).cacxvecompat$addRenderableWidget(Button.builder(
            Component.literal("Find Trades"), button -> Network.toServer(
                new RequestPanelMsg(screen.getMenu().dockPos(), 2)))
                .bounds(left + 112, top + 64, 104, 20).build());
    }

    @Overwrite
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY)
    {
        CaravanDockScreen screen = (CaravanDockScreen) (Object) this;
        graphics.drawCenteredString(Minecraft.getInstance().font, Component.literal("Caravan Dock"), 111, 7, 0xFFB000);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("Export"), 8, 24, 0xE8D99A, false);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("Import"), 8, 48, 0xE8D99A, false);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("x" + screen.getMenu().exportAmt()), 120, 24, 0xFFFFFF, false);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("x" + screen.getMenu().paymentAmt()), 120, 48, 0xFFFFFF, false);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("Caravan"), 8, 72, 0xE8D99A, false);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("Stock"), 30, 100, 0xE8D99A, false);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("Inventory"), 30, 162, 0xE8D99A, false);
    }
}