package com.blackocean.skills.client.fx;

import com.blackocean.skills.Skill;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 시전하는 동안 시전자의 몸과 머리가 고정된 공격 방향을 바라보게 한다 (화면에만, 판정과 무관).
 * 다른 사람이 봐도 "저쪽으로 쏘는구나"가 바로 읽힌다.
 */
public final class CasterPose {
    private record Pose(float yaw, long until) {}

    private static final Map<Integer, Pose> POSES = new HashMap<>();

    private CasterPose() {}

    public static void start(Entity caster, Skill skill, int chargeTicks, float yaw, float pitch) {
        if (!(caster instanceof LivingEntity) || !Float.isFinite(yaw)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (POSES.size() > 256) POSES.clear();
        // 모으는 동안 + 쏘고 난 뒤 잠깐(동작이 끝까지 그 방향으로 이어지게)
        int hold = Math.max(0, chargeTicks) + (skill.aim == Skill.Aim.SELF ? 4 : 10);
        POSES.put(caster.getId(), new Pose(yaw, mc.level.getGameTime() + hold));
    }

    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        if (POSES.isEmpty()) return;
        LivingEntity e = event.getEntity();
        Pose p = POSES.get(e.getId());
        if (p == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getGameTime() > p.until() || !e.isAlive()) {
            POSES.remove(e.getId());
            return;
        }
        // 몸은 빠르게 돌아가고, 머리는 정면
        float body = Mth.approachDegrees(e.yBodyRot, p.yaw(), 40f);
        e.yBodyRot = body;
        e.yBodyRotO = body;
        e.yHeadRot = p.yaw();
        e.yHeadRotO = p.yaw();
    }

    static void clear() {
        POSES.clear();
    }
}
