package com.blackocean.skills.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 이 시전자의 공격이 "나"를 다치게 할 수 있는지 (화면 표시용 — 서버 SkillTargets.canHit 와 같은 규칙).
 * 적 공격 = 빨간 테두리 + 경고 표시, 내 것·아군 = 파란 점선 테두리 (색만이 아니라 모양으로도 구분).
 */
public final class Hostility {
    public enum Side { SELF, ALLY, ENEMY }

    private static boolean pvp = true;

    private Hostility() {}

    public static void setPvp(boolean on) {
        pvp = on;
    }

    public static Side of(@Nullable Entity caster) {
        Minecraft mc = Minecraft.getInstance();
        Player me = mc.player;
        if (caster == null || me == null) return Side.ENEMY;
        if (caster == me) return Side.SELF;
        if (caster instanceof Player) {
            if (!pvp) return Side.ALLY;
            if (caster.isAlliedTo(me)) return Side.ALLY;
            Entity a = caster.getRootVehicle(), b = me.getRootVehicle();
            if (a != caster && a == b) return Side.ALLY; // 같은 배·탈것
            if (me.isCreative() || me.isSpectator()) return Side.ALLY; // 나는 맞지 않음
        }
        return Side.ENEMY;
    }

    public static boolean hostile(@Nullable Entity caster) {
        return of(caster) == Side.ENEMY;
    }
}
