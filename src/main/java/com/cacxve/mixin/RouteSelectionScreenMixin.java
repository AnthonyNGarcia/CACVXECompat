package com.cacxve.mixin;

import com.cacxve.core.RouteNetwork;
import com.warborn.caravansconvoys.client.MatchmakingScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(MatchmakingScreen.class)
public abstract class RouteSelectionScreenMixin
{
    private static final Pattern COORDINATES = Pattern.compile("(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+)");
    private static final Pattern TRADE_ID = Pattern.compile("\\[trade=([^]]+)]");

    @Shadow
    @Final
    private BlockPos dock;

    @Shadow
    @Mutable
    @Final
    private List<Component> lines;

    @Inject(method = "init", at = @At("TAIL"))
    private void addRouteButtons(CallbackInfo callback)
    {
        MatchmakingScreen screen = (MatchmakingScreen) (Object) this;
        int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        List<BlockPos> targets = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> details = new ArrayList<>();
        List<String> tradeIds = new ArrayList<>();
        List<Button> routeButtons = new ArrayList<>();
        for (Component line : lines)
        {
            Matcher matcher = COORDINATES.matcher(line.getString());
            if (matcher.find())
            {
                targets.add(new BlockPos(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))));
                String name = line.getString().substring(0, matcher.start()).trim();
                names.add(name.isEmpty() ? "Village" : name.replaceAll("\\s+at$", ""));
                details.add(line.getString());
                Matcher tradeMatcher = TRADE_ID.matcher(line.getString());
                tradeIds.add(tradeMatcher.find() ? tradeMatcher.group(1) : "");
            }
        }
        for (GuiEventListener child : new ArrayList<>(screen.children()))
        {
            if (!(child instanceof Button button))
                continue;
            String label = button.getMessage().getString();
            if (label.equalsIgnoreCase("Send now") || label.equalsIgnoreCase("Refresh") || label.equalsIgnoreCase("Close"))
                ((ScreenAccessor) (Object) screen).cacxvecompat$removeWidget(button);
        }
        ((ScreenAccessor) (Object) screen).cacxvecompat$addRenderableWidget(Button.builder(
                Component.literal("Back"), button -> Minecraft.getInstance().setScreen(RouteNetwork.previousRouteScreen()))
                .bounds((width - 100) / 2, height - 52, 100, 20).build());
        lines = List.of(Component.literal("Select a village route:"));

        int row = 0;
        for (BlockPos target : targets)
        {
            if (row >= 8)
                break;
            int distance = Minecraft.getInstance().player == null ? 0
                    : (int) Math.sqrt(Minecraft.getInstance().player.blockPosition().distSqr(target));
            int y = (height - 240) / 2 + 52 + row * 23;
            String name = names.get(row);
            String buttonName = name;
            if (buttonName.length() > 28)
                buttonName = buttonName.substring(0, 28) + "...";
            String tradeId = tradeIds.get(row);
            String hover = details.get(row);
            int givesAt = hover.indexOf(": village gives ");
            if (givesAt >= 0)
            {
                String villageName = hover.substring(0, hover.indexOf(" at "));
                String exchange = hover.substring(givesAt + ": village gives ".length());
                int forAt = exchange.indexOf(" for ");
                if (forAt >= 0)
                    hover = villageName + " will give you " + exchange.substring(0, forAt)
                            + " in exchange for " + exchange.substring(forAt + " for ".length());
            }
            Button routeButton = Button.builder(Component.literal(buttonName + " - " + distance + " blocks"), button -> {
                RouteNetwork.select(dock, target, tradeId);
                for (Button other : routeButtons)
                    other.active = other != button;
            }).bounds((width - 340) / 2 + 14, y, 312, 20).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(hover))).build();
            routeButtons.add(routeButton);
            ((ScreenAccessor) (Object) screen).cacxvecompat$addRenderableWidget(routeButton);
            row++;
        }
    }

    @ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"), index = 0)
    private String renameTitle(String key)
    {
        return key.equals("gui.caravansconvoys.matchmaking") ? "gui.cacxvecompat.trade_offers" : key;
    }

}