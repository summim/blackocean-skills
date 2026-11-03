package com.blackocean.skills;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
 * /skills reset [대상]                — 모든 스킬 쿨타임 초기화 (대상 생략 = 나)
 * /skills cast [대상]                 — 쿨타임 무시하고 화염 주먹 발사 (1.1 과 같음)
 * /skills cast <스킬이름> [대상]       — 쿨타임 무시하고 그 스킬 발동
 *     스킬이름: fire_fist, fire_bullets, fire_pillar, flame_ring
 * 모두 OP(권한 2) 전용.
 */
public final class SkillsCommands {
    private SkillsCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> cast = Commands.literal("cast")
                .executes(c -> cast(c.getSource(), Skill.FIRE_FIST, List.of(c.getSource().getPlayerOrException())))
                .then(Commands.argument("targets", EntityArgument.players())
                        .executes(c -> cast(c.getSource(), Skill.FIRE_FIST, EntityArgument.getPlayers(c, "targets"))));
        for (Skill s : Skill.VALUES) {
            cast.then(Commands.literal(s.id)
                    .executes(c -> cast(c.getSource(), s, List.of(c.getSource().getPlayerOrException())))
                    .then(Commands.argument("targets", EntityArgument.players())
                            .executes(c -> cast(c.getSource(), s, EntityArgument.getPlayers(c, "targets")))));
        }
        d.register(Commands.literal("skills")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("reset")
                        .executes(c -> reset(c.getSource(), List.of(c.getSource().getPlayerOrException())))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(c -> reset(c.getSource(), EntityArgument.getPlayers(c, "targets")))))
                .then(cast));
    }

    private static int reset(CommandSourceStack src, Collection<ServerPlayer> players) throws CommandSyntaxException {
        for (ServerPlayer p : players) SkillServer.resetCooldown(p, null);
        int n = players.size();
        src.sendSuccess(() -> Component.translatable("cmd.blackocean_skills.reset", n), true);
        return n;
    }

    private static int cast(CommandSourceStack src, Skill skill, Collection<ServerPlayer> players) throws CommandSyntaxException {
        int n = 0;
        for (ServerPlayer p : players) {
            if (SkillServer.cast(p, skill, false)) n++;
        }
        int done = n;
        src.sendSuccess(() -> Component.translatable("cmd.blackocean_skills.cast", Component.translatable(skill.translationKey()), done), true);
        return n;
    }
}
