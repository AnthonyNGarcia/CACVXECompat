package com.cacxve.core;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(CaravansAndConvoysCompat.MOD_ID)
public class CaravansAndConvoysCompat
{
    public static final String MOD_ID = "cacxvecompat";
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MOD_ID);
    public static final RegistryObject<MenuType<CompatCaravanDockMenu>> COMPAT_DOCK_MENU = MENUS.register(
            "caravan_dock", () -> IForgeMenuType.create(CompatCaravanDockMenu::new));

    public CaravansAndConvoysCompat(FMLJavaModLoadingContext context)
    {
        IEventBus modBus = context.getModEventBus();
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::clientSetup);
        MENUS.register(modBus);
        MinecraftForge.EVENT_BUS.register(this);
        context.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event)
    {
        RouteNetwork.register();
    }

    private void clientSetup(FMLClientSetupEvent event)
    {
        event.enqueueWork(() -> net.minecraft.client.gui.screens.MenuScreens.register(
                COMPAT_DOCK_MENU.get(), CompatCaravanDockScreen::new));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END)
        {
            VillageNameManager.tick(event.getServer());
            VillageTradeManager.tick(event.getServer());
            RouteNetwork.tickStatus(event.getServer());
        }
    }

    @SubscribeEvent
    public void onVillageEndpointBreak(BlockEvent.BreakEvent event)
    {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                && VillageTradeManager.isVillageEndpoint(level, event.getPos()))
            event.setCanceled(true);
    }
}
