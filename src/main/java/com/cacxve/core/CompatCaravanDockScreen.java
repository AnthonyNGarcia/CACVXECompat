package com.cacxve.core;

import com.warborn.caravansconvoys.net.Network;
import com.warborn.caravansconvoys.net.RequestPanelMsg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class CompatCaravanDockScreen extends AbstractContainerScreen<CompatCaravanDockMenu>
{
    private final java.util.List<Button> multiplierButtons = new java.util.ArrayList<>();

    public CompatCaravanDockScreen(CompatCaravanDockMenu menu, Inventory inventory, Component title)
    {
        super(menu, inventory, title);
        imageWidth = 280;
        imageHeight = 232;
    }

    @Override
    protected void init()
    {
        super.init();
        multiplierButtons.clear();
        topPos = Math.max(4, Math.min(topPos - 24, height - imageHeight - 4));
        RouteNetwork.setSelectedMultiplier(menu.multiplier());
        addRenderableWidget(Button.builder(Component.literal("Find Trades"), button ->
                findTrades())
                .bounds(leftPos + 140, topPos + 16, 120, 20).build());
        int[] multipliers = {1, 2, 3, 5, 10};
        for (int index = 0; index < multipliers.length; index++)
        {
            int multiplier = multipliers[index];
            Button button = Button.builder(Component.literal(multiplier + "x"), clicked -> {
                RouteNetwork.selectMultiplier(menu.dockPos(), multiplier);
                updateMultiplierButtons();
            }).bounds(leftPos + 250, topPos + 96 + index * 22, 22, 20).build();
            multiplierButtons.add(addRenderableWidget(button));
        }
        updateMultiplierButtons();
    }

    private void updateMultiplierButtons()
    {
        int[] multipliers = {1, 2, 3, 5, 10};
        int count = Math.min(multipliers.length, multiplierButtons.size());
        for (int index = 0; index < count; index++)
            multiplierButtons.get(index).active = RouteNetwork.selectedMultiplier() != multipliers[index];
    }

    private void findTrades()
    {
        RouteNetwork.rememberRouteScreen(this);
        Network.toServer(new RequestPanelMsg(menu.dockPos(), 2));
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY)
    {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xC0101010);
        graphics.renderOutline(leftPos, topPos, imageWidth, imageHeight, 0xFFD0A030);
        for (net.minecraft.world.inventory.Slot slot : menu.slots)
        {
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            graphics.fill(x, y, x + 18, y + 18, 0xFF17120C);
            graphics.renderOutline(x, y, 18, 18, 0xFF6D5227);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY)
    {
        graphics.drawCenteredString(Minecraft.getInstance().font, Component.literal("Caravan Dock"), 140, 8, 0xFFFFB000);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("Export"), 12, 25, 0xFFE8D99A, false);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("Import"), 12, 49, 0xFFE8D99A, false);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("Caravan"), 12, 73, 0xFFE8D99A, false);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("x" + menu.exportAmount()), 115, 25, 0xFFFFFFFF, false);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("x" + menu.importAmount()), 115, 49, 0xFFFFFFFF, false);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("Stock"), 12, 109, 0xFFE8D99A, false);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("Inventory"), 12, 171, 0xFFE8D99A, false);
        String status = RouteNetwork.currentStatus();
        java.util.List<net.minecraft.util.FormattedCharSequence> lines = Minecraft.getInstance().font.split(Component.literal(status), 130);
        graphics.drawString(Minecraft.getInstance().font, Component.literal("Route status"), 140, 41, 0xFFFFD05A, false);
        for (int index = 0; index < Math.min(3, lines.size()); index++)
            graphics.drawString(Minecraft.getInstance().font, lines.get(index), 140, 53 + index * 11, 0xFFFFFFFF, false);
    }
}
