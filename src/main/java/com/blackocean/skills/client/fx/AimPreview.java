package com.blackocean.skills.client.fx;

import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillTargets;
import com.blackocean.skills.SkillsMod;
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
 * 스킬 키를 꾹 누르고 있는 동안 보이는 조준 표시. 서버와 같은 계산으로 그려서 실제로 날아갈 곳과 맞는다.
 * <pre>
 *  1 화염 주먹 / 2 불꽃 탄환 : 손에서 앞으로 흐르는 화살표 길(폭 = 판정 폭, 길이 = 사거리, 벽·적에서 멈춤)
 *                            + 떨어지는 자리의 폭발 범위 원
 *  3 불기둥                 : 조준한 바닥의 불기둥 범위 원 + 발밑 최대 사거리 원 (바닥이 없으면 빨간 원)
 *  4 화염 고리              : 발밑의 불의 파도 범위 원
 * </pre>
 * 쿨타임이 많이 남았으면 회색빛 빨강으로 보인다.
 */
public final class AimPreview {
    static final ResourceLocation RING = SkillsMod.id("textures/fx/aim_ring.png");
    static final ResourceLocation ARROW = SkillsMod.id("textures/fx/aim_arrow.png");
    private static final Vec3 UP = new Vec3(0, 1, 0);
    /** 이만큼(틱) 누르고 있어야 보임. 톡 치고 떼는 빠른 사용에는 표시가 번쩍이지 않게. */
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

    /** 실제로 화면에 보이는 중인지 (안내 문구 표시용) */
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
        if (p == null || mc.level == null) return;
        Skill s = aiming;
        if (s == null) return;

        float fadeIn = Math.min(1f, (held - SHOW_AFTER + pt) / 3f);
        boolean blocked = SkillBarHud.remaining(s) > com.blackocean.skills.SkillServer.BUFFER_TICKS;
        float r = blocked ? 0.75f : 1f, g = blocked ? 0.28f : 0.55f, b = blocked ? 0.22f : 0.12f;
        float a = (blocked ? 0.55f : 0.85f) * fadeIn;

        FxRender.alphaBlend();
        RenderSystem.disableCull();
        switch (s) {
            case FIRE_FIST -> projectile(mc.level, p, pt, time, 0.3, ClientSkillStats.fistRange(), ClientSkillStats.fistHitRadius(),
                    ClientSkillStats.fistSplash(), r, g, b, a);
            case FIRE_BULLETS -> projectile(mc.level, p, pt, time, 0.38, ClientSkillStats.bulletsRange(), 0.45f,
                    ClientSkillStats.bulletsSplash(), r, g, b, a);
            case FIRE_PILLAR -> pillar(mc.level, p, pt, time, r, g, b, a);
            case FLAME_RING -> {
                Vec3 feet = p.getPosition(pt);
                float rad = ClientSkillStats.ringRadius();
                rings(feet.add(0, 0.06, 0), UP, rad, time, r, g, b, a);
            }
        }
    }

    // ---------------------------------------------------------------------
    // 1·2: 투사체 길
    // ---------------------------------------------------------------------

    private static void projectile(Level level, LocalPlayer p, float pt, float time, double side, float range, float hitR,
                                   float splash, float r, float g, float b, float a) {
        Vec3 eye = p.getEyePosition(pt);
        Vec3 look = p.getViewVector(pt);
        Vec3 right = look.cross(UP);
        right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 start = eye.add(look.scale(1.6)).add(right.scale(side)).add(0, -0.3, 0);
        BlockHitResult wall = level.clip(new ClipContext(eye, start, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (wall.getType() != HitResult.Type.MISS) start = wall.getLocation().subtract(look.scale(0.15));

        // 서버와 같은 조준: 조준점이 가리키는 곳으로 방향을 맞춤
        Vec3 far = eye.add(look.scale(range));
        BlockHitResult bh = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 aim = bh.getType() == HitResult.Type.MISS ? far : bh.getLocation();
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(level, p, eye, aim,
                p.getBoundingBox().expandTowards(aim.subtract(eye)).inflate(1.0),
                e -> e.isPickable() && !e.isSpectator() && !SkillTargets.isSameRide(p, e));
        if (eh != null) aim = eh.getLocation();
        Vec3 d = aim.subtract(start);
        Vec3 dir = (d.lengthSqr() < 4.0 || d.normalize().dot(look) < 0.5) ? look : d.normalize();

        // 실제로 날아가다 멈출 곳
        Vec3 end = start.add(dir.scale(range));
        Vec3 normal = null;
        BlockHitResult path = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (path.getType() != HitResult.Type.MISS) {
            end = path.getLocation();
            Direction f = path.getDirection();
            normal = new Vec3(f.getStepX(), f.getStepY(), f.getStepZ());
        }
        final Vec3 s0 = start;
        EntityHitResult target = ProjectileUtil.getEntityHitResult(level, p, start, end, new AABB(start, end).inflate(hitR + 1.0),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && e != p && !SkillTargets.isSameRide(p, e)
                        && e.getBoundingBox().inflate(hitR).clip(s0, s0.add(dir.scale(range))).isPresent());
        if (target != null) {
            Vec3 hit = target.getEntity().getBoundingBox().inflate(hitR).clip(start, end).orElse(target.getLocation());
            if (hit.distanceToSqr(start) < end.distanceToSqr(start)) {
                end = hit;
                normal = UP;
            }
        }

        // 화살표 길 (바닥과 나란한 띠, 앞으로 흐르는 화살표)
        double len = start.distanceTo(end);
        if (len > 0.3) {
            Vec3 sideV = dir.cross(UP);
            if (sideV.lengthSqr() < 1.0E-4) sideV = right;
            sideV = sideV.normalize().scale(Math.max(0.3, hitR));
            float tile = (float) (Math.max(0.6, hitR * 2.0) * 1.2);
            FxRender.spriteShader(ARROW);
            BufferBuilder bb = FxRender.quads();
            int steps = Math.max(1, (int) Math.ceil(len / 1.5));
            for (int i = 0; i < steps; i++) {
                double t0 = len * i / steps, t1 = len * (i + 1) / steps;
                Vec3 p0 = start.add(dir.scale(t0)), p1 = start.add(dir.scale(t1));
                float a0 = a * fadeAlong(t0, len), a1 = a * fadeAlong(t1, len);
                float v0 = (float) (t0 / tile) - time * 0.06f, v1 = (float) (t1 / tile) - time * 0.06f;
                FxRender.vertex(bb, p0.x - sideV.x, p0.y - sideV.y, p0.z - sideV.z, 0f, v0, r, g, b, a0);
                FxRender.vertex(bb, p0.x + sideV.x, p0.y + sideV.y, p0.z + sideV.z, 1f, v0, r, g, b, a0);
                FxRender.vertex(bb, p1.x + sideV.x, p1.y + sideV.y, p1.z + sideV.z, 1f, v1, r, g, b, a1);
                FxRender.vertex(bb, p1.x - sideV.x, p1.y - sideV.y, p1.z - sideV.z, 0f, v1, r, g, b, a1);
            }
            FxRender.draw(bb);
        }

        // 떨어지는 자리: 폭발 범위 원 (아무것도 안 맞으면 흐릿하게, 날아가는 방향을 향해)
        if (normal != null) {
            rings(end.add(normal.scale(0.06)), normal, Math.max(0.6f, splash), time, r, g, b, a);
        } else {
            FxRender.spriteShader(RING);
            BufferBuilder bb = FxRender.quads();
            FxRender.planeQuad(bb, end, dir.scale(-1), Math.max(0.5f, splash * 0.5f), time * 0.05f, r, g, b, a * 0.45f);
            FxRender.draw(bb);
        }
    }

    private static float fadeAlong(double t, double len) {
        float in = (float) Math.min(1.0, t / 0.8);
        float out = (float) Math.min(1.0, (len - t) / 0.6 + 0.4);
        return in * Math.min(1f, out);
    }

    // ---------------------------------------------------------------------
    // 3: 불기둥 자리
    // ---------------------------------------------------------------------

    private static void pillar(Level level, LocalPlayer p, float pt, float time, float r, float g, float b, float a) {
        float range = ClientSkillStats.pillarRange();
        float radius = ClientSkillStats.pillarRadius();
        Vec3 eye = p.getEyePosition(pt);
        Vec3 look = p.getViewVector(pt);
        Vec3 far = eye.add(look.scale(range));
        Vec3 target = null;
        BlockHitResult bh = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        Vec3 from;
        if (bh.getType() != HitResult.Type.MISS && bh.getDirection() == Direction.UP) {
            target = bh.getLocation();
            from = null;
        } else if (bh.getType() != HitResult.Type.MISS) {
            from = bh.getLocation().add(Vec3.atLowerCornerOf(bh.getDirection().getNormal()).scale(0.3));
        } else {
            from = far;
        }
        if (target == null && from != null) {
            BlockHitResult down = level.clip(new ClipContext(from, from.add(0, -10, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
            if (down.getType() != HitResult.Type.MISS) target = down.getLocation();
        }

        // 발밑: 최대 사거리 원 (옅게)
        Vec3 feet = p.getPosition(pt).add(0, 0.05, 0);
        FxRender.spriteShader(RING);
        BufferBuilder bb = FxRender.quads();
        FxRender.planeQuad(bb, feet, UP, range, 0f, r, g, b, a * 0.22f);
        FxRender.draw(bb);

        if (target == null) {
            // 세울 바닥이 없음 → 바라보는 곳에 빨간 원
            Vec3 miss = from != null ? from : far;
            rings(miss, look.scale(-1), radius, time, 0.85f, 0.15f, 0.12f, a * 0.8f);
            return;
        }
        rings(target.add(0, 0.06, 0), UP, radius, time, r, g, b, a);
        // 기둥 높이를 알려 주는 위쪽 옅은 원
        bb = FxRender.quads();
        FxRender.planeQuad(bb, target.add(0, ClientSkillStats.pillarHeight(), 0), UP, radius * 0.75f, -time * 0.05f, r, g, b, a * 0.25f);
        FxRender.draw(bb);
    }

    // ---------------------------------------------------------------------
    // 공통: 범위 원 (테두리 원 + 안쪽에서 퍼져 나가는 원)
    // ---------------------------------------------------------------------

    private static void rings(Vec3 c, Vec3 n, float radius, float time, float r, float g, float b, float a) {
        FxRender.spriteShader(RING);
        BufferBuilder bb = FxRender.quads();
        FxRender.planeQuad(bb, c, n, radius, time * 0.03f, r, g, b, a);
        float wave = (time * 0.05f) % 1f;
        FxRender.planeQuad(bb, c.add(n.scale(0.01)), n, radius * wave, 0f, r, g, b, a * 0.6f * (1f - wave));
        FxRender.draw(bb);
    }
}
