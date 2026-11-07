package com.blackocean.skills.client.fx;

import com.blackocean.skills.Element;
import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillAim;
import com.blackocean.skills.SkillServer;
import com.blackocean.skills.SkillTargets;
import com.blackocean.skills.SkillsMod;
import com.blackocean.skills.SubLevels;
import com.blackocean.skills.client.ClientSkillStats;
import com.blackocean.skills.client.SkillBarHud;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 스킬 키를 꾹 누르고 있는 동안 보이는 조준 표시. 서버와 같은 계산(SkillAim)으로 그려서 실제 판정과 맞는다.
 * <pre>
 *  PROJECTILE : 손에서 앞으로 흐르는 화살표 길 (폭 = 판정 폭, 벽·적에서 멈춤) + 떨어지는 곳의 폭발 범위 원
 *  LINE       : 번개·물줄기 길 + 끝 표시 (번개는 연쇄 범위)
 *  GROUND     : 조준한 바닥의 범위 원 + 발밑 최대 사거리 원 (바닥이 없으면 빨간 원)
 *  SELF       : 발밑 범위 원
 *  DASH/WAVE  : 땅 위로 앞으로 뻗는 길 (폭·거리)
 *  BLINK      : 도착 지점 원 + 길 (갈 곳이 없으면 빨간 원)
 * </pre>
 * 색은 능력 색, 쿨타임이 많이 남았으면 빨갛게.
 */
public final class AimPreview {
    static final ResourceLocation RING = SkillsMod.id("textures/fx/aim_ring.png");
    static final ResourceLocation ARROW = SkillsMod.id("textures/fx/aim_arrow.png");
    private static final Vec3 UP = new Vec3(0, 1, 0);
    /** 이만큼(틱) 누르고 있어야 보임 (톡 치는 빠른 사용에는 번쩍이지 않게) */
    private static final int SHOW_AFTER = 2;

    @Nullable
    private static Skill aiming;
    private static int held;

    private AimPreview() {}

    public static void start(Skill s) {
        aiming = s;
        held = 0;
    }

    public static void stop() {
        aiming = null;
        held = 0;
    }

    @Nullable
    public static Skill aiming() {
        return aiming;
    }

    public static boolean visible() {
        return aiming != null && held >= SHOW_AFTER;
    }

    static void tick() {
        if (aiming != null) held++;
    }

    static boolean active() {
        return visible();
    }

    static void render(float pt, float time) {
        if (!visible()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Skill s = aiming;
        if (p == null || mc.level == null || s == null) return;

        float fadeIn = Math.min(1f, (held - SHOW_AFTER + pt) / 3f);
        boolean blocked = SkillBarHud.remaining(s) > SkillServer.BUFFER_TICKS;
        float[] c = blocked ? new float[]{0.75f, 0.28f, 0.22f} : color(s.element);
        float a = (blocked ? 0.55f : 0.85f) * fadeIn;
        float range = ClientSkillStats.range(s), radius = ClientSkillStats.radius(s);
        Level level = mc.level;
        Vec3 eye = p.getEyePosition(pt), look = p.getViewVector(pt);

        FxRender.alphaBlend();
        RenderSystem.disableCull();
        switch (s.aim) {
            case PROJECTILE -> {
                double side = s == Skill.FIRE_SUN ? 0 : s == Skill.FIRE_BULLETS ? 0.38 : 0.3;
                double lift = s == Skill.FIRE_SUN ? 2.6 : 0;
                projectile(level, p, eye, look, side, lift, range, s.hit, s.shape == Skill.Shape.BLADE ? 0f : radius, time, c, a);
            }
            case LINE -> {
                Vec3 end = projectile(level, p, eye, look, 0.3, 0, range, s.hit, 0f, time, c, a);
                if (s == Skill.LIGHTNING_BOLT && end != null) {
                    // 연쇄 범위: 맞은 곳 주변 원 (옅게)
                    rings(SkillAim.groundBelow(level, p, end).add(0, 0.06, 0), UP, radius, time, c, a * 0.4f);
                }
            }
            case GROUND -> {
                Vec3 feet = p.getPosition(pt).add(0, 0.05, 0);
                quad(RING, feet, UP, range, 0f, c, a * 0.2f); // 최대 사거리
                Vec3 target = SkillAim.groundTarget(level, p, eye, look, range);
                if (target == null) {
                    Vec3 far = eye.add(look.scale(Math.min(range, 12)));
                    rings(far, look.scale(-1), radius, time, new float[]{0.85f, 0.15f, 0.12f}, a * 0.8f);
                    return;
                }
                rings(target.add(0, 0.06, 0), UP, radius, time, c, a);
                float h = heightOf(s);
                if (h > 0) quad(RING, target.add(0, h, 0), UP, radius * 0.75f, -time * 0.05f, c, a * 0.22f);
            }
            case SELF -> rings(SkillAim.groundBelow(level, p, p.getPosition(pt)).add(0, 0.06, 0), UP, radius, time, c, a);
            case DASH, WAVE -> {
                Vec3 feet = SkillAim.groundBelow(level, p, p.getPosition(pt)).add(0, 0.08, 0);
                Vec3 dir = SkillAim.flatLook(look, p.getViewYRot(pt));
                float halfW = s.aim == Skill.Aim.WAVE ? radius : Math.max(0.6f, radius * 0.7f);
                Vec3 end = feet.add(dir.scale(range));
                arrow(feet, end, dir, halfW, time, c, a, true);
                quad(RING, end.add(0, 0.01, 0), UP, Math.max(0.8f, halfW * 0.6f), time * 0.05f, c, a * 0.6f);
            }
            case BLINK -> {
                Vec3 feet = p.getPosition(pt);
                Vec3 dest = SkillAim.blinkDestination(level, p, feet, eye, look, range);
                if (dest == null) {
                    rings(eye.add(look.scale(2)), look.scale(-1), 0.8f, time, new float[]{0.85f, 0.15f, 0.12f}, a * 0.8f);
                    return;
                }
                Vec3 g0 = feet.add(0, 0.08, 0), g1 = dest.add(0, 0.08, 0);
                Vec3 d = g1.subtract(g0);
                if (d.lengthSqr() > 0.25) arrow(g0, g1, d.normalize(), 0.45f, time, c, a * 0.8f, d.y * d.y < 0.5 * d.lengthSqr());
                rings(SkillAim.groundBelow(level, p, dest).add(0, 0.06, 0), UP, radius, time, c, a);
            }
        }
    }

    private static float[] color(Element el) {
        return switch (el) {
            case FIRE -> new float[]{1f, 0.55f, 0.12f};
            case LIGHTNING -> new float[]{0.45f, 0.8f, 1f};
            case WIND -> new float[]{0.45f, 1f, 0.7f};
            case WATER -> new float[]{0.3f, 0.65f, 1f};
            case ICE -> new float[]{0.7f, 0.92f, 1f};
            case THUNDER -> new float[]{1f, 0.8f, 0.25f};
        };
    }

    private static float heightOf(Skill s) {
        return switch (s) {
            case FIRE_PILLAR -> 9f;
            case WIND_WHIRLWIND -> 7f;
            case WIND_TEMPEST -> 22f;
            default -> 0f;
        };
    }

    // ---------------------------------------------------------------------
    // 투사체·줄기 길. 돌려주는 값 = 멈추는 곳
    // ---------------------------------------------------------------------

    @Nullable
    private static Vec3 projectile(Level level, LocalPlayer p, Vec3 eye, Vec3 look, double side, double lift, float range, float hitR,
                                   float splash, float time, float[] c, float a) {
        Vec3 start = SkillAim.handPos(level, p, eye, look, side, lift);
        Vec3 dir = SkillAim.aimDirection(level, p, eye, look, start, range);
        Vec3 end = start.add(dir.scale(range));
        Vec3 normal = null;
        BlockHitResult path = SubLevels.clip(level, new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (path.getType() != HitResult.Type.MISS) {
            end = path.getLocation();
            Direction f = path.getDirection();
            normal = new Vec3(f.getStepX(), f.getStepY(), f.getStepZ());
        }
        final Vec3 s0 = start, s1 = start.add(dir.scale(range));
        final float r = hitR;
        EntityHitResult target = ProjectileUtil.getEntityHitResult(level, p, start, end, new AABB(start, end).inflate(hitR + 1.0),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && e != p && !SkillTargets.isSameRide(p, e)
                        && e.getBoundingBox().inflate(r).clip(s0, s1).isPresent());
        if (target != null) {
            Vec3 hit = target.getEntity().getBoundingBox().inflate(hitR).clip(start, end).orElse(target.getLocation());
            if (hit.distanceToSqr(start) < end.distanceToSqr(start)) {
                end = hit;
                normal = UP;
            }
        }
        Vec3 right = look.cross(UP);
        right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 flatSide = dir.cross(UP);
        boolean flat = flatSide.lengthSqr() > 1.0E-4;
        arrow(start, end, dir, Math.max(0.3f, hitR), time, c, a, flat);

        if (normal != null) {
            if (splash > 0) rings(end.add(normal.scale(0.06)), normal, Math.max(0.6f, splash), time, c, a);
            else quad(RING, end.add(normal.scale(0.06)), normal, Math.max(0.5f, hitR), time * 0.05f, c, a * 0.7f);
        } else {
            quad(RING, end, dir.scale(-1), Math.max(0.5f, splash * 0.5f), time * 0.05f, c, a * 0.45f);
        }
        return end;
    }

    /** 앞으로 흐르는 화살표 띠. flat 이면 땅과 나란하게, 아니면 카메라를 향하게 */
    private static void arrow(Vec3 start, Vec3 end, Vec3 dir, float halfW, float time, float[] c, float a, boolean flat) {
        double len = start.distanceTo(end);
        if (len < 0.3) return;
        Vec3 sideV = flat ? dir.cross(UP) : dir.cross(FxRender.cam.subtract(start));
        if (sideV.lengthSqr() < 1.0E-6) sideV = new Vec3(1, 0, 0);
        sideV = sideV.normalize().scale(halfW);
        float tile = Math.max(0.6f, halfW * 2f) * 1.2f;
        FxRender.spriteShader(ARROW);
        BufferBuilder bb = FxRender.quads();
        int steps = Math.max(1, (int) Math.ceil(len / 1.5));
        for (int i = 0; i < steps; i++) {
            double t0 = len * i / steps, t1 = len * (i + 1) / steps;
            Vec3 p0 = start.add(dir.scale(t0)), p1 = start.add(dir.scale(t1));
            float a0 = a * fadeAlong(t0, len), a1 = a * fadeAlong(t1, len);
            float v0 = (float) (t0 / tile) - time * 0.06f, v1 = (float) (t1 / tile) - time * 0.06f;
            FxRender.vertex(bb, p0.x - sideV.x, p0.y - sideV.y, p0.z - sideV.z, 0f, v0, c[0], c[1], c[2], a0);
            FxRender.vertex(bb, p0.x + sideV.x, p0.y + sideV.y, p0.z + sideV.z, 1f, v0, c[0], c[1], c[2], a0);
            FxRender.vertex(bb, p1.x + sideV.x, p1.y + sideV.y, p1.z + sideV.z, 1f, v1, c[0], c[1], c[2], a1);
            FxRender.vertex(bb, p1.x - sideV.x, p1.y - sideV.y, p1.z - sideV.z, 0f, v1, c[0], c[1], c[2], a1);
        }
        FxRender.draw(bb);
    }

    private static float fadeAlong(double t, double len) {
        float in = (float) Math.min(1.0, t / 0.8);
        float out = (float) Math.min(1.0, (len - t) / 0.6 + 0.4);
        return in * Math.min(1f, out);
    }

    private static void quad(ResourceLocation tex, Vec3 c, Vec3 n, float radius, float rot, float[] col, float a) {
        FxRender.spriteShader(tex);
        BufferBuilder bb = FxRender.quads();
        FxRender.planeQuad(bb, c, n, radius, rot, col[0], col[1], col[2], a);
        FxRender.draw(bb);
    }

    /** 범위 원: 테두리 원 + 안쪽에서 퍼져 나가는 원 */
    private static void rings(Vec3 c, Vec3 n, float radius, float time, float[] col, float a) {
        FxRender.spriteShader(RING);
        BufferBuilder bb = FxRender.quads();
        FxRender.planeQuad(bb, c, n, radius, time * 0.03f, col[0], col[1], col[2], a);
        float wave = (time * 0.05f) % 1f;
        FxRender.planeQuad(bb, c.add(n.scale(0.01)), n, radius * wave, 0f, col[0], col[1], col[2], a * 0.6f * (1f - wave));
        FxRender.draw(bb);
    }
}
