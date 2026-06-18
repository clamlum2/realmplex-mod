package com.realmplex;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
public class CurrencyConfig {
    public List<CurrencyItemConfig> currencies = new ArrayList<>();
    public static class ExchangeConfig {
        @SerializedName(value = "rawItem", alternate = "raw_item")
        public String rawItem;
        public int rate;
        @SerializedName(value = "useAll", alternate = "use_all")
        public boolean useAll = false;
        @SerializedName(value = "allowedPlayers", alternate = "allowed_players")
        public List<String> allowedPlayers = new ArrayList<>();
    }
    public static class DenominationConfig {
        public String name;
        public String item;
        @SerializedName(value = "displayName", alternate = "display_name")
        public String displayName;
        public String color;
        public boolean glint;
        @SerializedName(value = "itemModel", alternate = "item_model")
        public String itemModel;
        @SerializedName(value = "extraNbt", alternate = "extra_nbt")
        public Map<String, Object> extraNbt;
        public List<ExchangeConfig> exchanges = new ArrayList<>();
    }
    public static class CurrencyItemConfig {
        @SerializedName(value = "nbtKey", alternate = "nbt_key")
        public String nbtKey;
        public String item;
        @SerializedName(value = "displayName", alternate = "display_name")
        public String displayName;
        public String color;
        public boolean glint;
        @SerializedName(value = "itemModel", alternate = "item_model")
        public String itemModel;
        @SerializedName(value = "extraNbt", alternate = "extra_nbt")
        public Map<String, Object> extraNbt;
        public List<ExchangeConfig> exchanges = new ArrayList<>();
        public List<DenominationConfig> denominations;
    }
    private static final RealmplexConfig<CurrencyConfig> HANDLER =
            new RealmplexConfig<>("realmplex/currencies.json", defaultConfig(), CurrencyConfig.class);
    public static CurrencyConfig load() {
        return HANDLER.load();
    }
    private static CurrencyConfig defaultConfig() {
        CurrencyConfig config = new CurrencyConfig();
        CurrencyItemConfig usd = new CurrencyItemConfig();
        usd.nbtKey      = "usd";
        usd.item        = "minecraft:paper";
        usd.displayName = "US Dollar";
        usd.color       = "#5555FF";
        usd.glint       = true;
        usd.itemModel   = "minecraft:filled_map";
        usd.extraNbt    = Map.of("usd", true);
        usd.exchanges.add(exchange("minecraft:netherite_ingot", 64, false, List.of()));
        usd.exchanges.add(exchange("minecraft:netherite_scrap", 16, false, List.of()));
        config.currencies.add(usd);
        return config;
    }
    private static ExchangeConfig exchange(String rawItem, int rate, boolean useAll, List<String> allowedPlayers) {
        ExchangeConfig e = new ExchangeConfig();
        e.rawItem        = rawItem;
        e.rate           = rate;
        e.useAll         = useAll;
        e.allowedPlayers = new ArrayList<>(allowedPlayers);
        return e;
    }
}