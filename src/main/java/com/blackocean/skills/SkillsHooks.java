package com.blackocean.skills;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** 다른 패키지(투사체)에서 쓰는 상태이상 + 연출 도우미 */
public final class SkillsHooks {
    private SkillsHooks() {}

    public static void freeze(ServerLevel level, LivingEntity e, int ticks) {
        IceThunderEffects.freeze(level, e, ticks);
    }

    public static void stun(ServerLevel level, LivingEntity e, int ticks) {
        IceThunderEffects.stun(level, e, ticks);
    }

    /** 설정(allowBlockDamage)으로 켰을 때만 블록 파괴 */
    public static void terrain(ServerLevel level, net.minecraft.world.phys.Vec3 at, float power) {
        IceThunderEffects.terrain(level, at, power);
    }
}
