package com.sableport.mod;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

@Mod(SableportLibMod.MODID)
public class SableportLibMod {

    public static final String MODID =
            "sableportlibmod";

    public static final Logger LOGGER =
            LogUtils.getLogger();

    public SableportLibMod(
            final IEventBus modEventBus,
            final ModContainer modContainer
    ) {
        NeoForge.EVENT_BUS.register(this);

        LOGGER.info(
                "Sableport library initialized"
        );
    }

    @SubscribeEvent
    public void onRegisterCommands(
            final RegisterCommandsEvent event
    ) {
        SableTeleportCommand.register(
                event.getDispatcher()
        );
    }
}