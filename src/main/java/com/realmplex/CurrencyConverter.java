package com.realmplex;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;

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
            Map<String, Object> extraNbt
    ) {}

    private record ExchangePair(
            CurrencyItem currency,
            Item rawItem,
            int rate,
            List<String> allowedPlayers
    ) {
        boolean isMint() { return rawItem == null; }
    }

    private static final List<ExchangePair> PAIRS = new ArrayList<>();


    private static final Map<String, String> DEFAULT_DENOMINATION = new HashMap<>();

    private static void loadPairs() {
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
                            denom.color, denom.glint, denom.itemModel, denom.extraNbt
                    );
                    if (currency == null) continue;

                    DEFAULT_DENOMINATION.putIfAbsent(cfg.nbtKey, denom.name);

                    registerExchanges(cfg.nbtKey, currency, denom.exchanges);
                }
            } else {
                CurrencyItem currency = buildCurrencyItem(
                        cfg.nbtKey, "", cfg.item, cfg.displayName,
                        cfg.color, cfg.glint, cfg.itemModel, cfg.extraNbt
                );
                if (currency == null) continue;

                DEFAULT_DENOMINATION.putIfAbsent(cfg.nbtKey, "");

                registerExchanges(cfg.nbtKey, currency, cfg.exchanges);
            }
        }

        long mints    = PAIRS.stream().filter(ExchangePair::isMint).count();
        long converts = PAIRS.size() - mints;
        RealmplexMod.LOGGER.info("Loaded {} conversion pair(s) and {} mint exchange(s)", converts, mints);
    }

    private static CurrencyItem buildCurrencyItem(String nbtKey, String denomination, String itemId, String displayName,
                                                  String colorHex, boolean glint, String itemModel,
                                                  Map<String, Object> extraNbt) {
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
                extraNbt != null ? extraNbt : Map.of()
        );
    }

    private static void registerExchanges(String nbtKey, CurrencyItem currency, List<CurrencyConfig.ExchangeConfig> exchanges) {
        if (exchanges == null) return;

        for (CurrencyConfig.ExchangeConfig exchange : exchanges) {
            if (exchange.rate <= 0) {
                RealmplexMod.LOGGER.warn("Exchange for '{}' (denomination '{}') has invalid rate {}, skipping",
                        nbtKey, currency.denomination(), exchange.rate);
                continue;
            }

            Item rawItem = null;
            if (exchange.rawItem != null && !exchange.rawItem.isBlank()) {
                rawItem = BuiltInRegistries.ITEM.getValue(Identifier.parse(exchange.rawItem));
                if (rawItem == Items.AIR) {
                    RealmplexMod.LOGGER.warn("Unknown raw item '{}' for '{}' (denomination '{}'), skipping",
                            exchange.rawItem, nbtKey, currency.denomination());
                    continue;
                }
            }

            List<String> lowerNames = exchange.allowedPlayers == null
                    ? List.of()
                    : exchange.allowedPlayers.stream().map(String::toLowerCase).toList();

            PAIRS.add(new ExchangePair(currency, rawItem, exchange.rate, lowerNames));
        }
    }

    public static void register() {
        loadPairs();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.<CommandSourceStack>literal("exchange")
                        .executes(CurrencyConverter::executeExchange)
                        .requires(source -> {
                            if (source.getEntity() == null) return true;
                            if (source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) return true;
                            ServerPlayer player = source.getPlayer();
                            if (player == null) return false;
                            String name = player.getName().getString().toLowerCase();
                            return PAIRS.stream().anyMatch(p -> p.allowedPlayers().contains(name));
                        })
                        .then(Commands.argument("currency", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    PAIRS.stream()
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
                                            PAIRS.stream()
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
                                .executes(CurrencyConverter::executeReload)
                        )
                )
        );
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

            if (pair.isMint()) {
                if (!isPermitted(player, pair)) continue;
                return mint(player, pair);
            }

            // raw → currency
            if (held.is(pair.rawItem()) && held.getCount() >= 1) {
                if (!isPermitted(player, pair)) {
                    context.getSource().sendFailure(Component.literal("You don't have permission for that exchange."));
                    return 0;
                }
                return convert(player, held, 1, buildCurrency(pair), pair.rate());
            }

            // currency → raw
            if (held.is(pair.currency().item()) && held.getCount() >= pair.rate()) {
                CustomData heldData = held.get(DataComponents.CUSTOM_DATA);
                if (heldData == null || !heldData.copyTag().contains(pair.currency().nbtKey())) continue;
                if (!isPermitted(player, pair)) {
                    context.getSource().sendFailure(Component.literal("You don't have permission for that exchange."));
                    return 0;
                }
                return convert(player, held, pair.rate(), new ItemStack(pair.rawItem(), 1), 1);
            }
        }

        String label = denomination.isEmpty() ? currencyKey : (currencyKey + " " + denomination);
        context.getSource().sendFailure(Component.literal("No valid exchange found for: " + label));
        return 0;
    }

    private static boolean isPermitted(ServerPlayer player, ExchangePair pair) {
        if (player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) return true;
        return pair.allowedPlayers().contains(player.getName().getString().toLowerCase());
    }

    private static int mint(ServerPlayer player, ExchangePair pair) {
        ItemStack output = buildCurrency(pair);
        Component outputComponent = itemComponent(output);
        player.addItem(output);
        player.sendSystemMessage(
                Component.literal("Minted ")
                        .append(Component.literal(pair.rate() + "x "))
                        .append(outputComponent)
        );
        return 1;
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

    private static ItemStack buildCurrency(ExchangePair pair) {
        CurrencyItem currency = pair.currency();
        ItemStack output = new ItemStack(currency.item(), pair.rate());

        CompoundTag tag = new CompoundTag();
        tag.putBoolean(currency.nbtKey(), true);

        for (Map.Entry<String, Object> entry : currency.extraNbt().entrySet()) {
            if      (entry.getValue() instanceof Boolean b)  tag.putBoolean(entry.getKey(), b);
            else if (entry.getValue() instanceof Integer i)  tag.putInt(entry.getKey(), i);
            else if (entry.getValue() instanceof String s)   tag.putString(entry.getKey(), s);
            else if (entry.getValue() instanceof Float f)    tag.putFloat(entry.getKey(), f);
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

        return output;
    }
}