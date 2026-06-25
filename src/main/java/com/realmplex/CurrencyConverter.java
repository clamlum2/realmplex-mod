package com.realmplex;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CurrencyConverter {

    private record CurrencyItem(
            Item item,
            String nbtKey,
            String denomination,
            String displayName,
            int color,
            boolean glint,
            String itemModel,
            Map<String, Object> extraNbt,
            List<Object> lore
    ) {}

    public record ExchangePair(
            CurrencyItem currency,
            Item rawItem,
            int rate,
            boolean useAll,
            List<String> allowedPlayers
    ) {}

    private static final List<ExchangePair> PAIRS = new ArrayList<>();
    private static final Map<String, String> DEFAULT_DENOMINATION = new HashMap<>();

    public static void loadPairs() {
        PAIRS.clear();
        DEFAULT_DENOMINATION.clear();
        CurrencyConfig config = CurrencyConfig.load();

        for (CurrencyConfig.CurrencyItemConfig cfg : config.currencies) {
            if (cfg.nbtKey == null || cfg.nbtKey.isBlank()) {
                RealmplexMod.LOGGER.warn("Currency is missing nbtKey, skipping");
                continue;
            }

            if (cfg.denominations != null && !cfg.denominations.isEmpty()) {
                for (CurrencyConfig.DenominationConfig denom : cfg.denominations) {
                    if (denom.name == null || denom.name.isBlank()) {
                        RealmplexMod.LOGGER.warn("Denomination for '{}' is missing a name, skipping", cfg.nbtKey);
                        continue;
                    }
                    CurrencyItem currency = buildCurrencyItem(
                            cfg.nbtKey, denom.name, denom.item, denom.displayName,
                            denom.color, denom.glint, denom.itemModel, denom.extraNbt, denom.lore
                    );
                    if (currency == null) continue;
                    DEFAULT_DENOMINATION.putIfAbsent(cfg.nbtKey, denom.name);
                    registerExchanges(cfg.nbtKey, currency, denom.exchanges);
                }
            } else {
                CurrencyItem currency = buildCurrencyItem(
                        cfg.nbtKey, "", cfg.item, cfg.displayName,
                        cfg.color, cfg.glint, cfg.itemModel, cfg.extraNbt, cfg.lore
                );
                if (currency == null) continue;
                DEFAULT_DENOMINATION.putIfAbsent(cfg.nbtKey, "");
                registerExchanges(cfg.nbtKey, currency, cfg.exchanges);
            }
        }

        RealmplexMod.LOGGER.info("Loaded {} exchange pair(s)", PAIRS.size());
    }

    private static CurrencyItem buildCurrencyItem(String nbtKey, String denomination, String itemId, String displayName,
                                                  String colorHex, boolean glint, String itemModel,
                                                  Map<String, Object> extraNbt, List<Object> lore) {
        Item currencyItem = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        if (currencyItem == Items.AIR) {
            RealmplexMod.LOGGER.warn("Unknown currency item '{}' for '{}' (denomination '{}'), skipping",
                    itemId, nbtKey, denomination);
            return null;
        }

        int color;
        try {
            color = Integer.parseInt(colorHex.replace("#", ""), 16);
        } catch (NumberFormatException e) {
            RealmplexMod.LOGGER.warn("Invalid color '{}' for '{}' (denomination '{}'), defaulting to white",
                    colorHex, nbtKey, denomination);
            color = 0xFFFFFF;
        }

        return new CurrencyItem(
                currencyItem,
                nbtKey,
                denomination,
                displayName,
                color,
                glint,
                itemModel,
                extraNbt != null ? extraNbt : Map.of(),
                lore != null ? lore : List.of()
        );
    }

    private static void registerExchanges(String nbtKey, CurrencyItem currency,
                                          List<CurrencyConfig.ExchangeConfig> exchanges) {
        if (exchanges == null) return;

        for (CurrencyConfig.ExchangeConfig exchange : exchanges) {
            if (exchange.rawItem == null || exchange.rawItem.isBlank()) {
                RealmplexMod.LOGGER.warn("Exchange for '{}' (denomination '{}') is missing rawItem, skipping",
                        nbtKey, currency.denomination());
                continue;
            }
            if (exchange.rate <= 0) {
                RealmplexMod.LOGGER.warn("Exchange for '{}' (denomination '{}') has invalid rate {}, skipping",
                        nbtKey, currency.denomination(), exchange.rate);
                continue;
            }

            Item rawItem = BuiltInRegistries.ITEM.getValue(Identifier.parse(exchange.rawItem));
            if (rawItem == Items.AIR) {
                RealmplexMod.LOGGER.warn("Unknown raw item '{}' for '{}' (denomination '{}'), skipping",
                        exchange.rawItem, nbtKey, currency.denomination());
                continue;
            }

            List<String> lowerNames = exchange.allowedPlayers == null
                    ? List.of()
                    : exchange.allowedPlayers.stream().map(String::toLowerCase).toList();

            PAIRS.add(new ExchangePair(currency, rawItem, exchange.rate, exchange.useAll, lowerNames));
        }
    }

    public static void register() {
        loadPairs();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.<CommandSourceStack>literal("exchange")
                        .requires(CurrencyConverter::canUseExchange)   // <-- clean one-liner
                        .executes(CurrencyConverter::executeExchange)
                        .then(Commands.argument("currency", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    getAllowedPairs(context.getSource())  // respect per-player visibility
                                            .stream()
                                            .map(p -> p.currency().nbtKey())
                                            .distinct()
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(CurrencyConverter::executeExchange)
                                .then(Commands.argument("denomination", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            String currencyKey;
                                            try {
                                                currencyKey = StringArgumentType.getString(context, "currency");
                                            } catch (IllegalArgumentException e) {
                                                return builder.buildFuture();
                                            }
                                            getAllowedPairs(context.getSource())  // same filter
                                                    .stream()
                                                    .filter(p -> p.currency().nbtKey().equals(currencyKey))
                                                    .map(p -> p.currency().denomination())
                                                    .filter(d -> !d.isEmpty())
                                                    .distinct()
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(CurrencyConverter::executeExchange)
                                )
                        )
                        .then(Commands.literal("reload")
                                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                                .executes(CurrencyConverter::executeReload)
                        )
                )
        );
    }


    private static boolean canUseExchange(CommandSourceStack source) {
        if (source.getEntity() == null) return true;

        ServerPlayer player = source.getPlayer();
        if (player == null) return true;

        if (source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) return true;

        String name = player.getName().getString().toLowerCase();
        return PAIRS.stream().anyMatch(p -> p.allowedPlayers().contains(name));
    }

    public static List<ExchangePair> getAllowedPairs(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return PAIRS;
        if (source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) return PAIRS;

        String name = player.getName().getString().toLowerCase();
        return PAIRS.stream()
                .filter(p -> p.allowedPlayers().contains(name))
                .toList();
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        loadPairs();
        context.getSource().sendSuccess(() -> Component.literal("Currency config reloaded."), false);
        return 1;
    }

    private static int executeExchange(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Not a player."));
            return 0;
        }

        String currencyKey;
        try {
            currencyKey = StringArgumentType.getString(context, "currency");
        } catch (IllegalArgumentException e) {
            context.getSource().sendFailure(Component.literal("Please specify a currency key."));
            return 0;
        }

        String denomination;
        try {
            denomination = StringArgumentType.getString(context, "denomination");
        } catch (IllegalArgumentException e) {
            denomination = DEFAULT_DENOMINATION.getOrDefault(currencyKey, "");
        }

        ItemStack held = player.getMainHandItem();

        for (ExchangePair pair : PAIRS) {
            if (!pair.currency().nbtKey().equals(currencyKey)) continue;
            if (!pair.currency().denomination().equals(denomination)) continue;

            // raw → currency
            if (held.is(pair.rawItem()) && held.getCount() >= 1) {
                int rawCost      = pair.useAll() ? held.getCount() : 1;
                int currencyYield = rawCost * pair.rate();
                return convert(player, held, rawCost, buildCurrency(pair, currencyYield), currencyYield);
            }

            // currency → raw
            if (held.is(pair.currency().item()) && held.getCount() >= pair.rate()) {
                CustomData heldData = held.get(DataComponents.CUSTOM_DATA);
                if (heldData == null || !heldData.copyTag().contains(pair.currency().nbtKey())) continue;
                int rawYield, currencyCost;
                if (pair.useAll()) {
                    rawYield     = held.getCount() / pair.rate();
                    currencyCost = rawYield * pair.rate();
                } else {
                    rawYield     = 1;
                    currencyCost = pair.rate();
                }
                if (rawYield <= 0) {
                    context.getSource().sendFailure(Component.literal("Not enough to exchange."));
                    return 0;
                }
                return convert(player, held, currencyCost, new ItemStack(pair.rawItem(), rawYield), rawYield);
            }
        }

        String label = denomination.isEmpty() ? currencyKey : (currencyKey + " " + denomination);
        context.getSource().sendFailure(Component.literal("No valid exchange found for: " + label));
        return 0;
    }

    private static int convert(ServerPlayer player, ItemStack held, int cost, ItemStack output, int yield) {
        player.sendSystemMessage(
                Component.literal(String.format("Converted %d ", cost))
                        .append(itemComponent(held))
                        .append(Component.literal(String.format(" into %d ", yield)))
                        .append(itemComponent(output))
        );
        held.shrink(cost);
        player.addItem(output);
        return 1;
    }

    private static Component itemComponent(ItemStack stack) {
        ChatFormatting rarityColor  = stack.getRarity().color();
        ChatFormatting displayColor = rarityColor == ChatFormatting.WHITE ? ChatFormatting.GREEN : rarityColor;

        Component name = stack.has(DataComponents.CUSTOM_NAME)
                ? stack.getHoverName()
                : stack.getHoverName().copy().withStyle(displayColor);

        return name.copy().withStyle(style -> style
                .withHoverEvent(new HoverEvent.ShowItem(ItemStackTemplate.fromNonEmptyStack(stack)))
        );
    }

    private static ItemStack buildCurrency(ExchangePair pair, int count) {
        CurrencyItem currency = pair.currency();
        ItemStack output = new ItemStack(currency.item(), count);

        CompoundTag tag = new CompoundTag();
        tag.putBoolean(currency.nbtKey(), true);

        for (Map.Entry<String, Object> entry : currency.extraNbt().entrySet()) {
            if      (entry.getValue() instanceof Boolean b) tag.putBoolean(entry.getKey(), b);
            else if (entry.getValue() instanceof Integer i) tag.putInt(entry.getKey(), i);
            else if (entry.getValue() instanceof String s)  tag.putString(entry.getKey(), s);
            else if (entry.getValue() instanceof Float f)   tag.putFloat(entry.getKey(), f);
        }

        output.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        output.set(DataComponents.CUSTOM_NAME,
                Component.literal(currency.displayName()).withStyle(s -> s
                        .withColor(currency.color())
                        .withItalic(false)
                )
        );

        if (currency.glint()) {
            output.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        if (currency.itemModel() != null) {
            output.set(DataComponents.ITEM_MODEL, Identifier.parse(currency.itemModel()));
        }
        if (!currency.lore().isEmpty()) {
            List<Component> loreLines = currency.lore().stream()
                    .map(CurrencyConverter::parseLoreEntry)
                    .toList();
            output.set(DataComponents.LORE, new ItemLore(loreLines));
        }
        return output;
    }

    private static final Style DEFAULT_LORE_STYLE = Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(true);

    private static Component parseLoreEntry(Object entry) {
        if (entry instanceof String s) {
            return parseLoreLine(s);
        }
        if (entry instanceof Map<?, ?> map) {
            return parseLoreComponent(map, DEFAULT_LORE_STYLE);
        }
        RealmplexMod.LOGGER.warn("Unsupported lore entry '{}', skipping", entry);
        return Component.empty();
    }

    private static Component parseLoreComponent(Map<?, ?> map, Style inheritedStyle) {
        Object textValue = map.get("text");
        String text = textValue != null ? textValue.toString() : "";

        Style style = inheritedStyle;

        Object colorValue = map.get("color");
        if (colorValue != null) {
            TextColor parsed = TextColor.parseColor(colorValue.toString()).result().orElse(null);

            if (parsed != null) {
                style = style.withColor(parsed);
            } else {
                RealmplexMod.LOGGER.warn("Invalid lore color '{}', ignoring", colorValue);
            }
        }

        if (map.get("bold") != null) style = style.withBold(toBoolean(map.get("bold")));
        if (map.get("italic") != null) style = style.withItalic(toBoolean(map.get("italic")));
        if (map.get("underlined") != null) style = style.withUnderlined(toBoolean(map.get("underlined")));
        if (map.get("strikethrough") != null) style = style.withStrikethrough(toBoolean(map.get("strikethrough")));
        if (map.get("obfuscated") != null) style = style.withObfuscated(toBoolean(map.get("obfuscated")));

        MutableComponent component = Component.literal(text).withStyle(style);

        Object extra = map.get("extra");
        if (extra instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> childMap) {
                    component.append(parseLoreComponent(childMap, style));
                } else if (child instanceof String s) {
                    component.append(parseLoreLine(s));
                }
            }
        }

        return component;
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(value.toString());
    }

    private static Component parseLoreLine(String raw) {
        MutableComponent result = Component.empty();
        Style currentStyle = DEFAULT_LORE_STYLE;
        StringBuilder buffer = new StringBuilder();

        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '&' && i + 1 < raw.length()) {
                ChatFormatting formatting = ChatFormatting.getByCode(raw.charAt(i + 1));
                if (formatting != null) {
                    if (!buffer.isEmpty()) {
                        result.append(Component.literal(buffer.toString()).withStyle(currentStyle));
                        buffer.setLength(0);
                    }
                    currentStyle = (formatting == ChatFormatting.RESET)
                            ? DEFAULT_LORE_STYLE
                            : currentStyle.applyFormat(formatting);
                    i += 2;
                    continue;
                }
            }
            buffer.append(c);
            i++;
        }
        result.append(Component.literal(buffer.toString()).withStyle(currentStyle));
        return result;
    }
}