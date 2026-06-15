package com.realmplex;

public class BotConfig {
    public String apiKey = "";
    public String apiUrl = "http://127.0.0.1";

    private static final RealmplexConfig<BotConfig> HANDLER =
            new RealmplexConfig<>("realmplex/bot-config.json", new BotConfig(), BotConfig.class);

    public static BotConfig load() {
        return HANDLER.load();
    }
}
