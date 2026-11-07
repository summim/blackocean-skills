package com.blackocean.skills;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
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
 * 모두 OP(권한 2) 전용.
 * <pre>
 * /skills element <대상> <fire|lightning|wind|water|none|default>   능력 정하기 (default = 서버 기본값으로)
 * /skills element <대상>                                             능력 확인
 * /skills reset [대상]                                               모든 쿨타임 초기화
 * /skills cast <스킬이름> [대상]                                       쿨타임 무시하고 발동 (능력과 상관없이)
 * /skills cast [대상]                                                 화염 주먹 (1.1 과 같음)
 * </pre>
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
        LiteralArgumentBuilder<CommandSourceStack> element = Commands.literal("element");
        var targets = Commands.argument("targets", EntityArgument.players())
                .executes(c -> show(c.getSource(), EntityArgument.getPlayers(c, "targets")));
        for (String name : new String[]{"fire", "lightning", "wind", "water", "none", "default"}) {
            targets.then(Commands.literal(name).executes(c -> setElement(c.getSource(), EntityArgument.getPlayers(c, "targets"), name)));
        }
        element.then(targets);

        d.register(Commands.literal("skills")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("reset")
                        .executes(c -> reset(c.getSource(), List.of(c.getSource().getPlayerOrException())))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(c -> reset(c.getSource(), EntityArgument.getPlayers(c, "targets")))))
                .then(cast)
                .then(element));
    }

    private static int setElement(CommandSourceStack src, Collection<ServerPlayer> players, String name) {
        for (ServerPlayer p : players) {
            if (name.equals("default")) PlayerElements.clear(p);
            else PlayerElements.set(p, Element.byId(name));
        }
        int n = players.size();
        Component label = name.equals("none") || name.equals("default")
                ? Component.translatable("element.blackocean_skills." + name) : Component.translatable(Element.byId(name).translationKey());
        src.sendSuccess(() -> Component.translatable("cmd.blackocean_skills.element_set", n, label), true);
        return n;
    }

    private static int show(CommandSourceStack src, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            Element e = PlayerElements.get(p);
            Component label = e == null ? Component.translatable("element.blackocean_skills.none") : Component.translatable(e.translationKey());
            src.sendSuccess(() -> Component.translatable("cmd.blackocean_skills.element_show", p.getDisplayName(), label), false);
        }
        return players.size();
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

    @SuppressWarnings("unused")
    private static StringArgumentType unused() {
        return StringArgumentType.word();
    }
}
