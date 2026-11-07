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
 * <p>
 * 화면 프레임 수와 상관없이 같은 빠르기로 돌아간다 (실제 시간 기준). 끝나면 툭 끊기지 않고
 * 몸이 원래 방향으로 몇 틱에 걸쳐 돌아간다 (바닐라가 몸을 머리 쪽으로 천천히 돌리는 것과 이어짐).
 */
public final class CasterPose {
    /** 몸이 도는 최대 빠르기 (도/초): 큰 각도도 0.15초 안에 */
    private static final float BODY_DEG_PER_SEC = 1100f;
    /** 머리는 더 빠르게 (시선이 먼저 가고 몸이 따라감) */
    private static final float HEAD_DEG_PER_SEC = 1800f;

    private static final class Pose {
        final float yaw;
        final long until;
        long lastNanos;

        Pose(float yaw, long until) {
            this.yaw = yaw;
            this.until = until;
        }
    }

    private static final Map<Integer, Pose> POSES = new HashMap<>();

    private CasterPose() {}

    public static void start(Entity caster, Skill skill, int chargeTicks, float yaw, float pitch) {
        if (!(caster instanceof LivingEntity) || !Float.isFinite(yaw)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (POSES.size() > 256) POSES.clear();
        // 모으는 동안 + 쏘고 난 뒤 잠깐(동작이 끝까지 그 방향으로 이어지게). 연사는 5발이 다 나갈 때까지
        int after = skill.aim == Skill.Aim.SELF ? 4 : (skill == Skill.FIRE_BULLETS ? 12 : 8);
        int hold = Math.max(0, chargeTicks) + after;
        Pose old = POSES.get(caster.getId());
        Pose p = new Pose(yaw, mc.level.getGameTime() + hold);
        if (old != null) p.lastNanos = old.lastNanos;
        POSES.put(caster.getId(), p);
    }

    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        if (POSES.isEmpty()) return;
        LivingEntity e = event.getEntity();
        Pose p = POSES.get(e.getId());
        if (p == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getGameTime() > p.until || !e.isAlive()) {
            POSES.remove(e.getId());
            return;
        }
        long now = System.nanoTime();
        // 첫 프레임은 1/20초로 (멈췄다 돌아온 경우 한 번에 확 돌지 않게 최대 0.1초)
        float dt = p.lastNanos == 0 ? 0.05f : Math.min(0.1f, Math.max(0f, (now - p.lastNanos) / 1.0E9f));
        p.lastNanos = now;
        float body = Mth.approachDegrees(e.yBodyRot, p.yaw, BODY_DEG_PER_SEC * dt);
        float head = Mth.approachDegrees(e.yHeadRot, p.yaw, HEAD_DEG_PER_SEC * dt);
        // 보간값도 같이 맞춤 (이전 틱 값과 섞이며 흔들리지 않게)
        e.yBodyRot = body;
        e.yBodyRotO = body;
        e.yHeadRot = head;
        e.yHeadRotO = head;
    }

    static void clear() {
        POSES.clear();
    }
}
