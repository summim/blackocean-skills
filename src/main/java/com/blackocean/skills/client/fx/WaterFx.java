package com.blackocean.skills.client.fx;

import com.blackocean.skills.Skill;
import com.blackocean.skills.network.SkillEventPayload;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** (작성 예정) */
final class WaterFx {
    private WaterFx() {}

    static void onEvent(SkillEventPayload e, Skill skill, @Nullable Entity caster) {}
    static void tick() {}
    static void renderAdditive(float pt, float time) {}
    static void renderAlpha(float pt, float time) {}
    static boolean isEmpty() { return true; }
    static void clear() {}
    static void tickCharge(FxManager.ChargeFx c, float k) {}
    static void renderCharge(FxManager.ChargeFx c, float pt, float k, float time) {}
    static void trackProjectile(FxManager.ProjectileFx p, Vec3 pos) {}
    static void renderProjectile(FxManager.ProjectileFx p, Vec3 c, float pt, float time) {}
}
