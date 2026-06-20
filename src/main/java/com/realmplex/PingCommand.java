package com.realmplex;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class PingCommand {
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.<CommandSourceStack>literal("ping")
                    .executes(PingCommand::getPing)
            );
        });
    }

    private static int getPing(CommandContext<CommandSourceStack> commandSourceStackCommandContext) {
        ServerPlayer player = commandSourceStackCommandContext.getSource().getPlayer();

        int ping = player.connection.latency();

        player.sendSystemMessage(Component.literal(String.format("Ping: %dms ", ping)));

        return 1;
    }
}
