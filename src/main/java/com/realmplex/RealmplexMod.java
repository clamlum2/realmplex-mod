package com.realmplex;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RealmplexMod implements ModInitializer {
    public static final String MOD_ID = "realmplex-mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static BotConfig CONFIG;

    @Override
    public void onInitialize() {
        CONFIG = BotConfig.load();
        LOGGER.info("Loaded Realmplex Mod");
        CurrencyConverter.register();
        ItemFlexer.register();
        PingCommand.register();

        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                CurrencyConverter.loadPairs();
            }
        });
    }
}