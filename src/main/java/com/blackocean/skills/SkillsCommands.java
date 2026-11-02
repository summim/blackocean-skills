package com.blackocean.skills;

import com.blackocean.skills.fire.FireFistServer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Collection;
import java.util.List;

/**
 * /skills reset [대상]  — 화염 주먹 쿨타임 초기화 (대상 생략 = 나)
 * /skills cast  [대상]  — 쿨타임 무시하고 대상이 화염 주먹 발사 (별풍선 보상·테스트용)
 * 둘 다 OP(권한 2) 전용.
 */
public final class SkillsCommands {
    private SkillsCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skills")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("reset")
                        .executes(c -> reset(c.getSource(), List.of(c.getSource().getPlayerOrException())))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(c -> reset(c.getSource(), EntityArgument.getPlayers(c, "targets")))))
                .then(Commands.literal("cast")
                        .executes(c -> cast(c.getSource(), List.of(c.getSource().getPlayerOrException())))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(c -> cast(c.getSource(), EntityArgument.getPlayers(c, "targets"))))));
    }

    private static int reset(CommandSourceStack src, Collection<ServerPlayer> players) throws CommandSyntaxException {
        for (ServerPlayer p : players) FireFistServer.resetCooldown(p);
        int n = players.size();
        src.sendSuccess(() -> Component.translatable("cmd.blackocean_skills.reset", n), true);
        return n;
    }

    private static int cast(CommandSourceStack src, Collection<ServerPlayer> players) throws CommandSyntaxException {
        int n = 0;
        for (ServerPlayer p : players) {
            if (FireFistServer.cast(p, false)) n++;
        }
        int done = n;
        src.sendSuccess(() -> Component.translatable("cmd.blackocean_skills.cast", done), true);
        return n;
    }
}
