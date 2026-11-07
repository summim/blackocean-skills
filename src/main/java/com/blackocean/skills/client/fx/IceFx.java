package com.blackocean.skills.client.fx;

import com.blackocean.skills.Element;
import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillsMod;
import com.blackocean.skills.SkillsRegistry;
import com.blackocean.skills.SubLevels;
import com.blackocean.skills.client.ClientSkillStats;
import com.blackocean.skills.network.SkillEventPayload;
import com.blackocean.skills.network.SkillEvents;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.blackocean.skills.client.fx.FxManager.RNG;
import static com.blackocean.skills.client.fx.FxManager.clamp01;
import static com.blackocean.skills.client.fx.FxManager.count;
import static com.blackocean.skills.client.fx.FxManager.easeOutCubic;
import static com.blackocean.skills.client.fx.FxManager.rand;
import static com.blackocean.skills.client.fx.FxManager.randomDir;
import static com.blackocean.skills.client.fx.FxManager.smooth;
import static com.blackocean.skills.client.fx.FxManager.spawn;

/**
 * 얼음 능력 연출.
 * <pre>
 *  1 얼음 창     : 손에서 서리가 모여 긴 결정 창이 자람 → 깎인 면이 빛나는 결정 창 + 서리 고리 + 안개·반짝이 꼬리
 *                  적중: 흰 섬광 구 → 결정 파편이 튀고, 맞은 면에 결정 무리가 돋았다가 부서짐 + 서리 자국
 *  2 얼음 파편   : 앞쪽 부채꼴로 파편 7개가 맺힘 → 회전하는 작은 결정 / 적중: 작은 반짝 부서짐
 *  3 빙창 행진   : 앞머리가 나아가는 대로 땅에서 들쭉날쭉한 가시가 바깥으로 기울어 솟음 → 1.5초 뒤 금 가며 부서짐
 *  4 절대 냉기   : 얼음 돔 섬광 + 바닥을 따라 퍼지는 결정 고리 + 바깥으로 돋는 가시 + 눈보라 + 서리 자국
 *  5 빙하기 강림 : 준비 중 땅에 마법진이 커지고 하늘에서 거대한 빙산이 떨어짐 → 충돌(섬광·흔들림·충격파·파편·지형 파편)
 *                  → 빙산이 박힌 채 얼음 지대 유지 → 산산조각 (반짝이는 결정 폭풍)
 *  상태 얼림     : 생물을 감싸는 반투명 결정 덩어리 + 서리, 끝나면 금 가며 부서짐
 * </pre>
 * 결정은 코드로 만든 깎인 메시. 두 겹으로 그림: 반투명 푸른 몸통(알파 섞기, 윤곽일수록 진함) + 빛나는 윤곽·반사광(더하기 섞기).
 * 색: 짙은 파랑 → 청록 → 흰빛. 모든 연출은 시간·개수가 정해져 있고 clear() 로 모두 지워진다.
 */
final class IceFx {
    private static final Element I = Element.ICE;
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final float TAU = (float) (Math.PI * 2);

    // 흰빛 → 옅은 청록 → 청록 → 하늘 → 짙은 파랑
    private static final float[] C_WHITE = {0.93f, 0.98f, 1f};
    private static final float[] C_PALE = {0.72f, 0.93f, 1f};
    private static final float[] C_CYAN = {0.4f, 0.86f, 1f};
    private static final float[] C_AZURE = {0.25f, 0.6f, 1f};
    private static final float[] C_DEEP = {0.08f, 0.22f, 0.7f};
    /** 반짝이 색 (프리즘처럼 살짝 다른 빛깔) */
    private static final float[][] PRISM = {
            {1f, 1f, 1f}, {0.75f, 0.95f, 1f}, {0.5f, 0.88f, 1f}, {0.82f, 0.8f, 1f}, {1f, 0.88f, 0.96f}, {0.8f, 1f, 0.95f}};

    private static final ResourceLocation GLINT = SkillsMod.id("textures/fx/ice_glint.png");
    private static final ResourceLocation FROST = SkillsMod.id("textures/fx/ice_frost.png");
    private static final ResourceLocation RUNE = SkillsMod.id("textures/fx/ice_rune.png");

    /** shock_ring 그림의 고리는 판 반지름의 0.84 에 있음 → 보이는 고리를 정확히 R 에 두려면 R × RK */
    private static final float RK = 1f / 0.84f;

    private static final int MAX_ACTIVE = 48;
    private static final int MAX_FREEZE = 32;
    private static final int MAX_FRAGS = 220;
    private static final int MAX_DECALS = 36;

    private static final List<Fx> ACTIVE = new ArrayList<>();
    private static final List<Fx> PENDING = new ArrayList<>();
    private static final List<Frag> FRAGS = new ArrayList<>();
    private static final List<Decal> DECALS = new ArrayList<>();
    /** 빙하기 준비 중 떨어지는 빙산 (준비 연출마다 하나) */
    private static final Map<FxManager.ChargeFx, Berg> BERGS = new IdentityHashMap<>();
    private static boolean ticking;
    private static int clock;

    private IceFx() {}

    // =====================================================================
    // 모듈 계약
    // =====================================================================

    static void onEvent(SkillEventPayload e, Skill skill, @Nullable Entity caster) {
        FxManager.lod = FxManager.lodAt(e.pos());
        switch (e.kind()) {
            case SkillEvents.IMPACT -> {
                if (skill == Skill.ICE_SHARDS) shardImpact(e);
                else spearImpact(e);
            }
            case SkillEvents.SPIKES -> add(new SpikesFx(e.pos(), e.vec(), e.size(), e.size2(), e.duration(), caster));
            case SkillEvents.NOVA -> add(new NovaFx(e.pos(), e.size(), e.duration(), caster));
            case SkillEvents.GLACIER -> {
                float r = e.size() > 0.5f ? e.size() : 8f;
                if (e.size2() < 0.5f) {
                    if (caster != null) BERGS.entrySet().removeIf(en -> en.getKey().caster == caster);
                    add(new GlacierFx(e.pos(), r, Math.max(20, e.duration()), caster));
                } else {
                    GlacierFx g = findGlacier(e.pos());
                    if (g != null) g.shatter();
                    else glacierShatterBurst(e.pos(), r, null);
                }
            }
            default -> { }
        }
    }

    /** FREEZE: 생물을 결정 덩어리에 가둠 (같은 생물이면 시간만 늘림) */
    static void onStatus(SkillEventPayload e) {
        if (e.kind() != SkillEvents.FREEZE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity ent = mc.level.getEntity(e.target());
        if (ent == null || ent.isRemoved()) return;
        int dur = Math.max(4, e.duration());
        for (Fx f : ACTIVE) {
            if (f instanceof FreezeFx z && z.entity == ent && !z.broken) {
                z.end = Math.max(z.end, z.age + dur);
                return;
            }
        }
        for (Fx f : PENDING) {
            if (f instanceof FreezeFx z && z.entity == ent && !z.broken) {
                z.end = Math.max(z.end, z.age + dur);
                return;
            }
        }
        if (ent.position().distanceToSqr(FxRender.cam) > 96.0 * 96.0) return;
        int n = 0;
        FreezeFx oldest = null;
        for (Fx f : ACTIVE) {
            if (f instanceof FreezeFx z) {
                n++;
                if (oldest == null) oldest = z;
            }
        }
        if (n >= MAX_FREEZE && oldest != null) ACTIVE.remove(oldest);
        FxManager.lod = FxManager.lodAt(ent.position());
        add(new FreezeFx(ent, e.size(), e.size2(), dur));
    }

    static void tick() {
        clock++;
        IceThunderAim.tick();
        ticking = true;
        try {
            ACTIVE.removeIf(Fx::tick);
        } finally {
            ticking = false;
        }
        if (!PENDING.isEmpty()) {
            for (Fx f : PENDING) addNow(f);
            PENDING.clear();
        }
        FRAGS.removeIf(Frag::tick);
        DECALS.removeIf(d -> ++d.age >= d.life);
        Dust.tick();
        if (!BERGS.isEmpty()) BERGS.values().removeIf(b -> clock - b.seen > 2);
    }

    static void renderAlpha(float pt, float time) {
        if (ACTIVE.isEmpty() && FRAGS.isEmpty() && DECALS.isEmpty()) return;
        FxRender.alphaBlend();
        RenderSystem.disableCull();
        // 서리 자국
        if (!DECALS.isEmpty()) {
            FxRender.spriteShader(FROST);
            BufferBuilder bb = FxRender.quads();
            for (Decal d : DECALS) {
                float t = d.age + pt;
                float a = (t < d.life * 0.7f ? 1f : 1f - (t - d.life * 0.7f) / (d.life * 0.3f)) * d.alpha;
                float r = d.radius * (0.35f + 0.65f * easeOutCubic(t / d.grow));
                FxRender.planeQuad(bb, d.pos.add(d.normal.scale(0.035 + d.layer * 0.004)), d.normal, r, d.rot, 0.86f, 0.95f, 1f, Math.max(0f, a));
            }
            FxRender.draw(bb);
        }
        // 결정 몸통 (반투명 푸른 얼음)
        BufferBuilder bb = beginBody();
        for (Fx f : ACTIVE) {
            if (far(f)) continue;
            f.body(bb, pt, time);
        }
        for (Frag f : FRAGS) f.draw(bb, pt, false, time);
        FxRender.draw(bb);
    }

    static void renderAdditive(float pt, float time) {
        FxRender.additive();
        RenderSystem.disableCull();
        if (!ACTIVE.isEmpty() || !FRAGS.isEmpty()) {
            // 결정 윤곽·반사광 (한 번에)
            BufferBuilder bb = beginGlow(1f);
            for (Fx f : ACTIVE) {
                if (far(f)) continue;
                f.glow(bb, pt, time);
            }
            for (Frag f : FRAGS) f.draw(bb, pt, true, time);
            FxRender.draw(bb);
            for (Fx f : ACTIVE) {
                if (far(f)) continue;
                f.extra(pt, time);
                FxRender.additive();
                RenderSystem.disableCull();
            }
        }
        Dust.render(pt);
        Glints.flush();
        FxRender.additive();
    }

    static boolean isEmpty() {
        return ACTIVE.isEmpty() && PENDING.isEmpty() && FRAGS.isEmpty() && DECALS.isEmpty() && Dust.n == 0 && Glints.n == 0;
    }

    static void clear() {
        ACTIVE.clear();
        PENDING.clear();
        FRAGS.clear();
        DECALS.clear();
        BERGS.clear();
        IceThunderAim.clear();
        Dust.n = 0;
        Glints.n = 0;
    }

    private static boolean far(Fx f) {
        Vec3 a = f.anchor();
        return a != null && a.distanceToSqr(FxRender.cam) > 200.0 * 200.0;
    }

    private static void add(Fx f) {
        if (ticking) PENDING.add(f);
        else addNow(f);
    }

    private static void addNow(Fx f) {
        while (ACTIVE.size() >= MAX_ACTIVE) ACTIVE.remove(0);
        ACTIVE.add(f);
    }

    @Nullable
    private static GlacierFx findGlacier(Vec3 c) {
        GlacierFx best = null;
        double bd = 9.0;
        for (List<Fx> l : List.of(ACTIVE, PENDING)) {
            for (Fx f : l) {
                if (f instanceof GlacierFx g && !g.shattered) {
                    double d = g.center.distanceToSqr(c);
                    if (d < bd) {
                        bd = d;
                        best = g;
                    }
                }
            }
        }
        return best;
    }

    private abstract static class Fx {
        int age;

        /** @return true 면 끝 */
        abstract boolean tick();

        /** 결정 몸통 (알파 섞기, 공용 버퍼) */
        void body(BufferBuilder bb, float pt, float time) {}

        /** 결정 윤곽 빛 (더하기 섞기, 얼음 셰이더, 공용 버퍼) */
        void glow(BufferBuilder bb, float pt, float time) {}

        /** 그 밖의 더하기 그림 (스스로 셰이더·버퍼를 정함) */
        void extra(float pt, float time) {}

        @Nullable
        abstract Vec3 anchor();
    }

    // =====================================================================
    // 결정 메시 (코드로 만든 깎인 결정)
    // =====================================================================

    /** 결정 모양 0~9: 육각/오각 기둥 + 뾰족한 끝 (단위: 반지름 1, 길이 1, 뿌리 y=0, 끝 y=1). 10 = 창, 11 = 쌍뿔 파편 */
    private static final int NSHAPE = 12, LANCE = 10, BIPYR = 11;
    private static final float[][] SHAPES = new float[NSHAPE][];
    // 반사광 방향 (고정 "하늘 빛")
    private static final double LX, LY, LZ;

    static {
        for (int i = 0; i < NSHAPE; i++) SHAPES[i] = buildShape(i);
        double l = Math.sqrt(0.32 * 0.32 + 0.86 * 0.86 + 0.4 * 0.4);
        LX = 0.32 / l;
        LY = 0.86 / l;
        LZ = 0.4 / l;
    }

    private static float[] buildShape(int idx) {
        Random r = new Random(9157L + idx * 31L);
        boolean lance = idx >= LANCE;
        int n = lance ? 6 : (idx % 4 == 3 ? 5 : 6);
        float yb = idx == LANCE ? -0.55f : idx == BIPYR ? -0.85f : -0.06f - 0.1f * r.nextFloat();
        float y1 = lance ? 0.14f : 0.55f + 0.2f * r.nextFloat();
        float r0 = lance ? 1f : 0.72f + 0.2f * r.nextFloat();
        float[] x0 = new float[n], z0 = new float[n], x1 = new float[n], z1 = new float[n];
        for (int k = 0; k < n; k++) {
            double a = (k + (r.nextFloat() - 0.5f) * (lance ? 0.15f : 0.5f)) * TAU / n;
            float ra = r0 * (lance ? 1f : 0.88f + 0.24f * r.nextFloat());
            float rb = lance ? ra * 0.94f : 0.88f + 0.24f * r.nextFloat();
            double a1 = a + (lance ? 0 : (r.nextFloat() - 0.5f) * 0.16f);
            x0[k] = (float) Math.cos(a) * ra;
            z0[k] = (float) Math.sin(a) * ra;
            x1[k] = (float) Math.cos(a1) * rb;
            z1[k] = (float) Math.sin(a1) * rb;
        }
        float tx = lance ? 0f : (r.nextFloat() - 0.5f) * 0.22f, tz = lance ? 0f : (r.nextFloat() - 0.5f) * 0.22f;
        float cy = lance ? 0.1f : 0.4f;
        float[] out = new float[n * 4 * 9];
        int o = 0;
        for (int k = 0; k < n; k++) {
            int k1 = (k + 1) % n;
            o = tri(out, o, cy, 0, yb, 0, x0[k1], 0, z0[k1], x0[k], 0, z0[k]);
            o = tri(out, o, cy, x0[k], 0, z0[k], x0[k1], 0, z0[k1], x1[k1], y1, z1[k1]);
            o = tri(out, o, cy, x0[k], 0, z0[k], x1[k1], y1, z1[k1], x1[k], y1, z1[k]);
            o = tri(out, o, cy, x1[k], y1, z1[k], x1[k1], y1, z1[k1], tx, 1, tz);
        }
        return out;
    }

    /** 바깥을 향하도록 감기 순서를 맞춰 삼각형 하나를 넣음 */
    private static int tri(float[] out, int o, float cy, float ax, float ay, float az, float bx, float by, float bz, float cx, float cyy, float cz) {
        float e1x = bx - ax, e1y = by - ay, e1z = bz - az, e2x = cx - ax, e2y = cyy - ay, e2z = cz - az;
        float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
        float mx = (ax + bx + cx) / 3f, my = (ay + by + cyy) / 3f - cy, mz = (az + bz + cz) / 3f;
        boolean flip = nx * mx + ny * my + nz * mz < 0;
        out[o++] = ax; out[o++] = ay; out[o++] = az;
        if (flip) {
            out[o++] = cx; out[o++] = cyy; out[o++] = cz;
            out[o++] = bx; out[o++] = by; out[o++] = bz;
        } else {
            out[o++] = bx; out[o++] = by; out[o++] = bz;
            out[o++] = cx; out[o++] = cyy; out[o++] = cz;
        }
        return o;
    }

    private static BufferBuilder beginBody() {
        FxRender.spriteShader(FxRender.GLOW_WHITE);
        RenderSystem.disableCull();
        return FxRender.triangles();
    }

    private static BufferBuilder beginGlow(float intensity) {
        FxRender.elemShader(I, 0.16f, 0.0f, intensity);
        RenderSystem.disableCull();
        return FxRender.triangles();
    }

    /**
     * 결정 하나를 그린다.
     * @param add false = 반투명 몸통 (GLOW_WHITE 가운데 흰 점으로 단색), true = 얼음 셰이더 윤곽·반사광
     * @param k   밝기·진하기 배율 (사라질 때 0 으로)
     */
    private static void crystal(BufferBuilder bb, int shape, double bx, double by, double bz,
                                double ax, double ay, double az, double ux, double uy, double uz, double vx, double vy, double vz,
                                float rad, float len, float k, boolean add, float time) {
        if (k <= 0.004f || len <= 0.01f || rad <= 0.001f) return;
        float[] s = SHAPES[shape];
        double ox = bx - FxRender.cam.x, oy = by - FxRender.cam.y, oz = bz - FxRender.cam.z;
        // 너무 멀면 생략 (작게만 보임)
        double dd = ox * ox + oy * oy + oz * oz;
        if (dd > 220.0 * 220.0) return;
        float uvOff = shape * 0.137f;
        for (int i = 0; i < s.length; i += 9) {
            double x0 = ox + (ux * s[i] + vx * s[i + 2]) * rad + ax * s[i + 1] * len;
            double y0 = oy + (uy * s[i] + vy * s[i + 2]) * rad + ay * s[i + 1] * len;
            double z0 = oz + (uz * s[i] + vz * s[i + 2]) * rad + az * s[i + 1] * len;
            double x1 = ox + (ux * s[i + 3] + vx * s[i + 5]) * rad + ax * s[i + 4] * len;
            double y1 = oy + (uy * s[i + 3] + vy * s[i + 5]) * rad + ay * s[i + 4] * len;
            double z1 = oz + (uz * s[i + 3] + vz * s[i + 5]) * rad + az * s[i + 4] * len;
            double x2 = ox + (ux * s[i + 6] + vx * s[i + 8]) * rad + ax * s[i + 7] * len;
            double y2 = oy + (uy * s[i + 6] + vy * s[i + 8]) * rad + ay * s[i + 7] * len;
            double z2 = oz + (uz * s[i + 6] + vz * s[i + 8]) * rad + az * s[i + 7] * len;
            double e1x = x1 - x0, e1y = y1 - y0, e1z = z1 - z0, e2x = x2 - x0, e2y = y2 - y0, e2z = z2 - z0;
            double nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl < 1.0E-10) continue;
            nx /= nl;
            ny /= nl;
            nz /= nl;
            double cx = (x0 + x1 + x2) / 3.0, cy = (y0 + y1 + y2) / 3.0, cz = (z0 + z1 + z2) / 3.0;
            double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (cl < 1.0E-6) continue;
            // 카메라 쪽 방향 = -c
            double wx = -cx / cl, wy = -cy / cl, wz = -cz / cl;
            double dv = nx * wx + ny * wy + nz * wz;
            boolean front = dv > 0;
            float fres = 1f - (float) Math.min(1.0, Math.abs(dv));
            if (!add) {
                float a = k * (0.13f + 0.46f * (float) Math.pow(fres, 1.4)) * (front ? 1f : 0.5f);
                float r = 0.12f + 0.5f * fres, g = 0.36f + 0.55f * fres, b = 0.8f + 0.2f * fres;
                bb.addVertex((float) x0, (float) y0, (float) z0).setUv(0.5f, 0.5f).setColor(r, g, b, a);
                bb.addVertex((float) x1, (float) y1, (float) z1).setUv(0.5f, 0.5f).setColor(r, g, b, a);
                bb.addVertex((float) x2, (float) y2, (float) z2).setUv(0.5f, 0.5f).setColor(r, g, b, a);
            } else {
                float glint = 0f;
                if (front) {
                    // 반사 방향 = 2(n·w)n - w → 하늘 빛과 맞으면 번쩍
                    double rx = 2 * dv * nx - wx, ry = 2 * dv * ny - wy, rz = 2 * dv * nz - wz;
                    double gl = rx * LX + ry * LY + rz * LZ;
                    if (gl > 0.6) glint = (float) Math.pow(gl, 24) * 1.4f;
                }
                float base = (0.07f + 0.85f * fres * fres + glint) * k * (front ? 1f : 0.42f);
                float ph = (float) (nx * 3.1 + ny * 2.3 + nz * 1.7) + time * 0.035f;
                float pr = 0.8f + 0.2f * (float) Math.sin(ph), pg = 0.9f + 0.1f * (float) Math.sin(ph + 2.1f), pb = 1f;
                float tip = 0.24f * k;
                bb.addVertex((float) x0, (float) y0, (float) z0).setUv((s[i] + s[i + 2]) * 0.5f + uvOff, s[i + 1] * len * 0.3f)
                        .setColor(pr, pg, pb, Math.min(1f, base + tip * Math.max(0f, s[i + 1])));
                bb.addVertex((float) x1, (float) y1, (float) z1).setUv((s[i + 3] + s[i + 5]) * 0.5f + uvOff, s[i + 4] * len * 0.3f)
                        .setColor(pr, pg, pb, Math.min(1f, base + tip * Math.max(0f, s[i + 4])));
                bb.addVertex((float) x2, (float) y2, (float) z2).setUv((s[i + 6] + s[i + 8]) * 0.5f + uvOff, s[i + 7] * len * 0.3f)
                        .setColor(pr, pg, pb, Math.min(1f, base + tip * Math.max(0f, s[i + 7])));
            }
        }
    }

    /** 축 a 와 그 둘레 회전(roll) 으로 옆 방향 두 개 → out[0..5] = u, v */
    private static void basis(double ax, double ay, double az, float roll, double[] out) {
        double rx, ry, rz;
        if (Math.abs(ay) < 0.9) {
            rx = 0; ry = 1; rz = 0;
        } else {
            rx = 1; ry = 0; rz = 0;
        }
        double ux = ay * rz - az * ry, uy = az * rx - ax * rz, uz = ax * ry - ay * rx;
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;
        // v = u × a → (u, a, v) 가 오른손 좌표계 → 메시의 바깥쪽 감기 순서가 그대로 유지됨
        double vx = uy * az - uz * ay, vy = uz * ax - ux * az, vz = ux * ay - uy * ax;
        double c = Math.cos(roll), s = Math.sin(roll);
        out[0] = ux * c + vx * s;
        out[1] = uy * c + vy * s;
        out[2] = uz * c + vz * s;
        out[3] = vx * c - ux * s;
        out[4] = vy * c - uy * s;
        out[5] = vz * c - uz * s;
    }

    private static final double[] TMP = new double[6];

    /** 한 번 그리고 끝나는 결정 (투사체·준비 연출용) */
    private static void crystalAlong(BufferBuilder bb, int shape, Vec3 base, Vec3 axis, float roll, float rad, float len, float k, boolean add, float time) {
        basis(axis.x, axis.y, axis.z, roll, TMP);
        crystal(bb, shape, base.x, base.y, base.z, axis.x, axis.y, axis.z, TMP[0], TMP[1], TMP[2], TMP[3], TMP[4], TMP[5], rad, len, k, add, time);
    }

    /** 자리 잡은 결정 (가시·빙산·얼림). 좌표는 주인 기준 상대 또는 월드. */
    private static final class Cr {
        final double x, y, z;
        final double ax, ay, az, ux, uy, uz, vx, vy, vz;
        final float rad, len;
        final int shape;
        int born, grow = 3, end = Integer.MAX_VALUE;
        boolean broken;

        Cr(double x, double y, double z, Vec3 axis, float roll, float rad, float len, int shape) {
            this.x = x;
            this.y = y;
            this.z = z;
            Vec3 a = axis.lengthSqr() < 1.0E-8 ? UP : axis.normalize();
            this.ax = a.x;
            this.ay = a.y;
            this.az = a.z;
            double[] b = new double[6];
            basis(ax, ay, az, roll, b);
            this.ux = b[0];
            this.uy = b[1];
            this.uz = b[2];
            this.vx = b[3];
            this.vy = b[4];
            this.vz = b[5];
            this.rad = rad;
            this.len = len;
            this.shape = shape;
        }

        /** 솟아오름 (살짝 넘쳤다가 자리 잡음) */
        float scale(float t) {
            if (t < born) return 0f;
            float q = (t - born) / Math.max(1, grow);
            return q >= 1f ? 1f : easeOutBack(q);
        }

        void draw(BufferBuilder bb, double ox, double oy, double oz, float s, float k, boolean add, float time) {
            if (s <= 0.002f || broken) return;
            crystal(bb, shape, ox + x, oy + y, oz + z, ax, ay, az, ux, uy, uz, vx, vy, vz,
                    rad * (0.3f + 0.7f * Math.min(1f, s)), len * s, k, add, time);
        }

        /** 부서짐: 결정 조각 몇 개 + 얼음 조각 입자 */
        void shatter(double ox, double oy, double oz, int frags, float power, double groundY, boolean sprites) {
            broken = true;
            double bx = ox + x, by = oy + y, bz = oz + z;
            for (int j = 0; j < frags; j++) {
                float along = rand(0.15f, 0.85f) * len;
                float ang = RNG.nextFloat() * TAU;
                double cs = Math.cos(ang), sn = Math.sin(ang);
                double rx = ux * cs + vx * sn, ry = uy * cs + vy * sn, rz = uz * cs + vz * sn;
                double px = bx + ax * along + rx * rad * 0.5, py = by + ay * along + ry * rad * 0.5, pz = bz + az * along + rz * rad * 0.5;
                float sp = power * rand(0.12f, 0.32f);
                float fl = Math.min(len * 0.4f, rad * 2.6f) * rand(0.45f, 1f);
                addFrag(px, py, pz, rx * sp + ax * sp * 0.3, ry * sp + Math.abs(ay) * sp * 0.4 + rand(0.05f, 0.18f) * power, rz * sp + az * sp * 0.3,
                        fl * 0.3f, fl, 16 + RNG.nextInt(14), groundY);
            }
            if (sprites) {
                for (int j = 0, m = count(4 + (int) (len * 2)); j < m; j++) {
                    float along = RNG.nextFloat() * len;
                    Vec3 at = new Vec3(bx + ax * along, by + ay * along, bz + az * along);
                    shardSprite(at, randomDir().scale(rand(0.06f, 0.22f) * power).add(0, rand(0.05f, 0.15f), 0), rand(0.18f, 0.32f), 14 + RNG.nextInt(12), 0.035f);
                }
                for (int j = 0, m = count(3 + (int) len); j < m; j++) {
                    float along = RNG.nextFloat() * len;
                    Dust.glint(bx + ax * along, by + ay * along, bz + az * along, rand(-0.05f, 0.05f), rand(0.01f, 0.06f), rand(-0.05f, 0.05f),
                            rand(0.25f, 0.5f), 14 + RNG.nextInt(16), 0.002f);
                }
                if (RNG.nextFloat() < 0.5f * FxManager.amount() * FxManager.lod) {
                    mist(new Vec3(bx + ax * len * 0.4, by + ay * len * 0.4, bz + az * len * 0.4), new Vec3(0, 0.02, 0), len * 0.6f, len * 1.4f, 22, 0.32f);
                }
            }
        }
    }

    private static float easeOutBack(float x) {
        float c1 = 1.4f, c3 = c1 + 1f;
        float u = x - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    // =====================================================================
    // 날아가는 결정 조각
    // =====================================================================

    private static void addFrag(double x, double y, double z, double vx, double vy, double vz, float rad, float len, int life, double groundY) {
        if (FRAGS.size() >= MAX_FRAGS) return;
        if (x - FxRender.cam.x > 160 || x - FxRender.cam.x < -160 || z - FxRender.cam.z > 160 || z - FxRender.cam.z < -160) return;
        FRAGS.add(new Frag(x, y, z, vx, vy, vz, rad, len, life, groundY));
    }

    private static final class Frag {
        double x, y, z, px, py, pz, vx, vy, vz;
        final double groundY;
        final double a0x, a0y, a0z, a1x, a1y, a1z;
        float w;
        final float ph, rad, len;
        final int shape, life;
        int age;

        Frag(double x, double y, double z, double vx, double vy, double vz, float rad, float len, int life, double groundY) {
            this.x = this.px = x;
            this.y = this.py = y;
            this.z = this.pz = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.rad = Math.max(0.03f, rad);
            this.len = Math.max(0.08f, len);
            this.life = life;
            this.groundY = groundY;
            Vec3 a0 = randomDir();
            Vec3 a1 = a0.cross(randomDir());
            a1 = a1.lengthSqr() < 1.0E-6 ? FxRender.basis(a0)[0] : a1.normalize();
            a0x = a0.x;
            a0y = a0.y;
            a0z = a0.z;
            a1x = a1.x;
            a1y = a1.y;
            a1z = a1.z;
            this.w = rand(0.25f, 0.7f) * (RNG.nextBoolean() ? 1 : -1);
            this.ph = RNG.nextFloat() * TAU;
            this.shape = RNG.nextInt(10);
        }

        boolean tick() {
            px = x;
            py = y;
            pz = z;
            age++;
            vy -= 0.045;
            vx *= 0.985;
            vy *= 0.985;
            vz *= 0.985;
            x += vx;
            y += vy;
            z += vz;
            if (y < groundY) {
                y = groundY;
                vy = -vy * 0.3;
                vx *= 0.55;
                vz *= 0.55;
                w *= 0.5f;
            }
            return age >= life;
        }

        void draw(BufferBuilder bb, float pt, boolean add, float time) {
            float t = age + pt;
            float fade = 1f - smooth((t - life * 0.55f) / (life * 0.45f));
            if (fade <= 0.01f) return;
            double cx = px + (x - px) * pt, cy = py + (y - py) * pt, cz = pz + (z - pz) * pt;
            double a = ph + w * t;
            double cs = Math.cos(a), sn = Math.sin(a);
            double ax = a0x * cs + a1x * sn, ay = a0y * cs + a1y * sn, az = a0z * cs + a1z * sn;
            basis(ax, ay, az, 0f, TMP);
            float l = len * (0.4f + 0.6f * fade);
            crystal(bb, shape, cx - ax * l * 0.45, cy - ay * l * 0.45, cz - az * l * 0.45, ax, ay, az,
                    TMP[0], TMP[1], TMP[2], TMP[3], TMP[4], TMP[5], rad * (0.5f + 0.5f * fade), l, add ? fade * 1.2f : fade, add, time);
        }
    }

    // =====================================================================
    // 반짝이는 얼음 가루 · 눈송이 (할당 없는 자체 입자, 별 모양 반짝임)
    // =====================================================================

    private static final class Dust {
        static final int MAX = 1100;
        static final double[] X = new double[MAX], Y = new double[MAX], Z = new double[MAX], PX = new double[MAX], PY = new double[MAX], PZ = new double[MAX];
        static final float[] VX = new float[MAX], VY = new float[MAX], VZ = new float[MAX], SIZE = new float[MAX], GRAV = new float[MAX], PH = new float[MAX];
        static final int[] AGE = new int[MAX], LIFE = new int[MAX];
        static final byte[] KIND = new byte[MAX], COL = new byte[MAX];
        static int n;

        /** 반짝이 (별 모양, 깜빡임) */
        static void glint(double x, double y, double z, float vx, float vy, float vz, float size, int life, float grav) {
            add((byte) 0, x, y, z, vx, vy, vz, size, life, grav);
        }

        /** 눈송이 (부드러운 흰 점, 흔들리며 떨어짐) */
        static void flake(double x, double y, double z, float vx, float vy, float vz, float size, int life) {
            add((byte) 1, x, y, z, vx, vy, vz, size, life, 0.002f);
        }

        static void add(byte kind, double x, double y, double z, float vx, float vy, float vz, float size, int life, float grav) {
            if (n >= MAX) return;
            int i = n++;
            X[i] = PX[i] = x;
            Y[i] = PY[i] = y;
            Z[i] = PZ[i] = z;
            VX[i] = vx;
            VY[i] = vy;
            VZ[i] = vz;
            SIZE[i] = size;
            GRAV[i] = grav;
            PH[i] = RNG.nextFloat() * TAU;
            AGE[i] = 0;
            LIFE[i] = Math.max(2, life);
            KIND[i] = kind;
            COL[i] = (byte) RNG.nextInt(PRISM.length);
        }

        static void tick() {
            for (int i = 0; i < n; i++) {
                if (++AGE[i] >= LIFE[i]) {
                    int j = --n;
                    if (i != j) copy(j, i);
                    i--;
                    continue;
                }
                PX[i] = X[i];
                PY[i] = Y[i];
                PZ[i] = Z[i];
                VY[i] -= GRAV[i];
                float drag = KIND[i] == 1 ? 0.97f : 0.93f;
                VX[i] *= drag;
                VY[i] *= drag;
                VZ[i] *= drag;
                if (KIND[i] == 1) {
                    VX[i] += (float) Math.sin(AGE[i] * 0.21f + PH[i]) * 0.004f;
                    VZ[i] += (float) Math.cos(AGE[i] * 0.17f + PH[i]) * 0.004f;
                }
                X[i] += VX[i];
                Y[i] += VY[i];
                Z[i] += VZ[i];
            }
        }

        private static void copy(int from, int to) {
            X[to] = X[from];
            Y[to] = Y[from];
            Z[to] = Z[from];
            PX[to] = PX[from];
            PY[to] = PY[from];
            PZ[to] = PZ[from];
            VX[to] = VX[from];
            VY[to] = VY[from];
            VZ[to] = VZ[from];
            SIZE[to] = SIZE[from];
            GRAV[to] = GRAV[from];
            PH[to] = PH[from];
            AGE[to] = AGE[from];
            LIFE[to] = LIFE[from];
            KIND[to] = KIND[from];
            COL[to] = COL[from];
        }

        static void render(float pt) {
            if (n == 0) return;
            for (int kind = 0; kind < 2; kind++) {
                boolean any = false;
                for (int i = 0; i < n; i++) {
                    if (KIND[i] == kind) {
                        any = true;
                        break;
                    }
                }
                if (!any) continue;
                FxRender.spriteShader(kind == 0 ? GLINT : FxRender.GLOW_WHITE);
                RenderSystem.disableCull();
                BufferBuilder bb = FxRender.quads();
                for (int i = 0; i < n; i++) {
                    if (KIND[i] != kind) continue;
                    float t = (AGE[i] + pt) / LIFE[i];
                    float fade = t < 0.15f ? t / 0.15f : 1f - (t - 0.15f) / 0.85f;
                    float a, size;
                    if (kind == 0) {
                        float tw = (float) Math.abs(Math.sin((AGE[i] + pt) * 0.55f + PH[i]));
                        tw = tw * tw * tw;
                        a = fade * (0.25f + 0.75f * tw);
                        size = SIZE[i] * (0.45f + 0.75f * tw);
                    } else {
                        a = fade * 0.5f;
                        size = SIZE[i];
                    }
                    if (a <= 0.01f) continue;
                    float[] c = PRISM[COL[i]];
                    double x = PX[i] + (X[i] - PX[i]) * pt, y = PY[i] + (Y[i] - PY[i]) * pt, z = PZ[i] + (Z[i] - PZ[i]) * pt;
                    bill(bb, x, y, z, size, kind == 0 ? 0.785f * (PH[i] > 3.14f ? 1 : 0) : PH[i], c[0] * a, c[1] * a, c[2] * a, a);
                }
                FxRender.draw(bb);
            }
        }
    }

    /** 이번 프레임에만 그리는 별 반짝임 (결정 끝·창끝). renderAdditive 끝에서 한 번에 그림 */
    private static final class Glints {
        static final int MAX = 400;
        static final double[] P = new double[MAX * 3];
        static final float[] Q = new float[MAX * 5];
        static int n;

        static void add(double x, double y, double z, float size, float rot, float r, float g, float b, float a) {
            if (n >= MAX || a <= 0.01f) return;
            P[n * 3] = x;
            P[n * 3 + 1] = y;
            P[n * 3 + 2] = z;
            Q[n * 5] = size;
            Q[n * 5 + 1] = rot;
            Q[n * 5 + 2] = r * a;
            Q[n * 5 + 3] = g * a;
            Q[n * 5 + 4] = b * a;
            n++;
        }

        static void flush() {
            if (n == 0) return;
            FxRender.spriteShader(GLINT);
            RenderSystem.disableCull();
            BufferBuilder bb = FxRender.quads();
            for (int i = 0; i < n; i++) {
                float a = Math.max(Q[i * 5 + 2], Math.max(Q[i * 5 + 3], Q[i * 5 + 4]));
                bill(bb, P[i * 3], P[i * 3 + 1], P[i * 3 + 2], Q[i * 5], Q[i * 5 + 1], Q[i * 5 + 2], Q[i * 5 + 3], Q[i * 5 + 4], a);
            }
            FxRender.draw(bb);
            n = 0;
        }
    }

    /** 카메라를 바라보는 사각형 (Vec3 를 만들지 않는 판) */
    private static void bill(BufferBuilder bb, double x, double y, double z, float size, float rot, float r, float g, float b, float a) {
        float h = size * 0.5f;
        float c = (float) Math.cos(rot) * h, s = (float) Math.sin(rot) * h;
        float ax = FxRender.LEFT.x * c + FxRender.UP.x * s, ay = FxRender.LEFT.y * c + FxRender.UP.y * s, az = FxRender.LEFT.z * c + FxRender.UP.z * s;
        float bx = FxRender.UP.x * c - FxRender.LEFT.x * s, by = FxRender.UP.y * c - FxRender.LEFT.y * s, bz = FxRender.UP.z * c - FxRender.LEFT.z * s;
        FxRender.vertex(bb, x - ax - bx, y - ay - by, z - az - bz, 1, 1, r, g, b, a);
        FxRender.vertex(bb, x - ax + bx, y - ay + by, z - az + bz, 1, 0, r, g, b, a);
        FxRender.vertex(bb, x + ax + bx, y + ay + by, z + az + bz, 0, 0, r, g, b, a);
        FxRender.vertex(bb, x + ax - bx, y + ay - by, z + az - bz, 0, 1, r, g, b, a);
    }

    // =====================================================================
    // 서리 자국
    // =====================================================================

    private static final class Decal {
        final Vec3 pos, normal;
        final float radius, rot, alpha, grow;
        final int life, layer;
        int age;

        Decal(Vec3 pos, Vec3 normal, float radius, int life, float alpha, float grow, int layer) {
            this.pos = pos;
            this.normal = normal;
            this.radius = radius;
            this.life = Math.max(10, life);
            this.alpha = alpha;
            this.grow = Math.max(1f, grow);
            this.layer = layer;
            this.rot = RNG.nextFloat() * TAU;
        }
    }

    private static void frostDecal(Vec3 pos, Vec3 normal, float radius, int life, float alpha, float grow) {
        if (DECALS.size() >= MAX_DECALS) DECALS.remove(0);
        Vec3 n = normal.lengthSqr() < 1.0E-4 ? UP : normal.normalize();
        DECALS.add(new Decal(pos, n, radius, life, alpha, grow, DECALS.size() % 8));
    }

    // =====================================================================
    // 공통 도우미 (입자)
    // =====================================================================

    /** 이번 프레임 후광 (공용 풀 — 새 Sprite 를 만들지 않음) */
    private static void frameGlow(Vec3 p, float size, float[] c, float a) {
        FxManager.frameGlow(p.x, p.y, p.z, size, c[0], c[1], c[2], a);
    }

    private static void flashGlow(Vec3 p, float s0, float s1, int life, float[] c, float a) {
        spawn(new Sprite(Sprite.Kind.GLOW, p, Vec3.ZERO, s0, s1, life).color(c[0], c[1], c[2], a).elem(I));
    }

    /** 얼음 조각 입자 (ice_shard 그림) */
    private static void shardSprite(Vec3 at, Vec3 v, float size, int life, float gravity) {
        float[] c = PRISM[RNG.nextInt(3)];
        Sprite s = new Sprite(Sprite.Kind.EMBER, at, v, size, size * 0.55f, life).elem(I).physics(gravity, 0.97f).color(c[0], c[1], c[2], 1f);
        s.rot = RNG.nextFloat() * TAU;
        s.frameOffset = RNG.nextInt(100);
        spawn(s);
    }

    /** 서리 꽃 (frost_sheet) */
    private static void frost(Vec3 at, Vec3 v, float s0, float s1, int life, float a) {
        Sprite s = new Sprite(Sprite.Kind.FLAME, at, v, s0, s1, life).elem(I).physics(0f, 0.9f).color(0.85f, 0.95f, 1f, a);
        s.frameOffset = RNG.nextInt(64);
        s.rot = RNG.nextFloat() * TAU;
        spawn(s);
    }

    /** 차가운 안개 (반투명) */
    private static void mist(Vec3 at, Vec3 v, float s0, float s1, int life, float a) {
        Sprite s = new Sprite(Sprite.Kind.SMOKE, at, v, s0, s1, life).elem(I).color(0.84f, 0.94f, 1f, a).physics(-0.0004f, 0.94f);
        s.rot = RNG.nextFloat() * TAU;
        spawn(s);
    }

    private static void spark(Vec3 at, Vec3 v, int life) {
        float[] c = PRISM[RNG.nextInt(3)];
        spawn(new Sprite(Sprite.Kind.SPARK, at, v, 0.09f, 0.09f, life).color(c[0], c[1], c[2], 1f).physics(0.03f, 0.9f).elem(I));
    }

    /** 사방으로 튀는 얼음 조각 (n 쪽 반구) */
    private static void shardBurst(Vec3 at, Vec3 n, float speed, int base, float size) {
        for (int i = 0, m = count(base); i < m; i++) {
            Vec3 d = randomDir();
            double dn = d.dot(n);
            if (dn < 0) d = d.subtract(n.scale(2 * dn));
            shardSprite(at, d.scale(rand(0.35f, 1f) * speed).add(n.scale(0.12 * speed)), rand(0.7f, 1.3f) * size, 14 + RNG.nextInt(14), 0.04f);
        }
    }

    private static void glintBurst(Vec3 at, float spread, float speed, int base, float size, int life) {
        for (int i = 0, m = count(base); i < m; i++) {
            Vec3 d = randomDir();
            Vec3 p = at.add(d.scale(spread * RNG.nextFloat()));
            Vec3 v = d.scale(speed * rand(0.3f, 1f));
            Dust.glint(p.x, p.y, p.z, (float) v.x, (float) v.y, (float) v.z, size * rand(0.7f, 1.3f), life / 2 + RNG.nextInt(life), 0.003f);
        }
    }

    private static void mistBurst(Vec3 at, float radius, int base, float a) {
        for (int i = 0, m = count(base); i < m; i++) {
            Vec3 d = randomDir();
            mist(at.add(d.scale(rand(0.2f, 1f) * radius)), new Vec3(d.x * 0.06, rand(0.005f, 0.03f), d.z * 0.06),
                    0.6f + radius * 0.8f, 1.2f + radius * 2.0f, 26 + RNG.nextInt(16), a);
        }
    }

    /** 바닥을 따라 바깥으로 밀려 나가는 안개 고리 */
    private static void mistRing(Vec3 c, float r0, float speed, int base, float size, float a) {
        for (int i = 0, m = count(base); i < m; i++) {
            float ang = RNG.nextFloat() * TAU;
            double cx = Math.cos(ang), cz = Math.sin(ang);
            mist(c.add(cx * r0, 0.3, cz * r0), new Vec3(cx * speed * rand(0.7f, 1.2f), rand(0.0f, 0.03f), cz * speed * rand(0.7f, 1.2f)),
                    size, size * 2.6f, 24 + RNG.nextInt(14), a);
        }
    }

    private static void shake(Vec3 at, float amp, float range) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double d = mc.player.position().distanceTo(at);
        if (d < range) CameraShake.add((float) (amp * (1 - d / range)));
    }

    private static float fpScale(Entity caster) {
        Minecraft mc = Minecraft.getInstance();
        return caster == mc.player && mc.options.getCameraType().isFirstPerson() ? 0.7f : 1f;
    }

    /** 서버 groundAt 과 같은 계산: 위 3칸에서 아래 6.5칸까지 (물 위도 땅으로 봄) */
    private static Vec3 groundAt(@Nullable Entity who, Vec3 p) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return p;
        Entity e = who != null && !who.isRemoved() && who.level() == level ? who : mc.player;
        if (e == null) return p;
        Vec3 from = p.add(0, 3.0, 0);
        BlockHitResult down = SubLevels.clip(level, new ClipContext(from, from.add(0, -6.5, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, e));
        return down.getType() == HitResult.Type.MISS ? p : down.getLocation();
    }

    private static Vec3 tilted(Vec3 out, double tilt, Vec3 extra) {
        Vec3 a = UP.scale(Math.cos(tilt)).add(out.scale(Math.sin(tilt))).add(extra);
        return a.lengthSqr() < 1.0E-6 ? UP : a.normalize();
    }

    /** 바닥 마법진 (ice_rune 두 겹 반대로 회전 + 흐르는 고리 + 번짐) */
    private static void runeCircle(Vec3 c, float radius, float k, float time, float alpha) {
        if (alpha <= 0.01f || radius <= 0.05f) return;
        Vec3 g = c.add(0, 0.06, 0);
        RenderSystem.disableCull();
        FxRender.spriteShader(FxRender.GLOW_WHITE);
        BufferBuilder bb = FxRender.quads();
        float gl = (0.15f + 0.4f * k * k) * alpha;
        FxRender.planeQuad(bb, g, UP, radius * 1.35f, 0f, gl * C_DEEP[0], gl * C_DEEP[1], gl * C_DEEP[2], gl);
        FxRender.draw(bb);
        FxRender.spriteShader(RUNE);
        bb = FxRender.quads();
        float pulse = 0.8f + 0.2f * (float) Math.sin(time * (0.8f + 2f * k));
        float a = (0.45f + 0.55f * k) * pulse * alpha;
        FxRender.planeQuad(bb, g.add(0, 0.01, 0), UP, radius, time * 0.03f, a * C_CYAN[0], a * C_CYAN[1], a * C_CYAN[2], a);
        float a2 = a * 0.75f;
        FxRender.planeQuad(bb, g.add(0, 0.02, 0), UP, radius * 0.62f, -time * 0.05f, a2 * C_PALE[0], a2 * C_PALE[1], a2 * C_PALE[2], a2);
        FxRender.draw(bb);
        FxRender.elemShader(I, 0.8f, 0.25f - 0.2f * k, (0.7f + 0.5f * k) * alpha);
        bb = FxRender.quads();
        FxRender.ring(bb, g.add(0, 0.03, 0), UP, radius * 0.92f, radius, 0.6f + 0.4f * k, time * 0.2f, 64);
        FxRender.draw(bb);
    }

    // =====================================================================
    // 1. 얼음 창 · 2. 얼음 파편 (투사체)
    // =====================================================================

    static void trackProjectile(FxManager.ProjectileFx p, Vec3 pos) {
        Vec3 back = p.dir.scale(-1);
        if (p.skill == Skill.ICE_SHARDS) {
            for (int i = 0, m = count(1); i < m; i++) {
                Vec3 at = pos.add(randomDir().scale(0.18));
                Dust.glint(at.x, at.y, at.z, (float) back.x * 0.04f, 0.005f, (float) back.z * 0.04f, rand(0.18f, 0.3f), 8 + RNG.nextInt(6), 0.002f);
            }
            if (p.age % 3 == 0 && RNG.nextFloat() < FxManager.amount() * FxManager.lod) {
                mist(pos.add(back.scale(0.4)), Vec3.ZERO, 0.25f, 0.8f, 9, 0.22f);
            }
            return;
        }
        // 얼음 창: 서리 안개 · 반짝이 가루 · 떨어지는 얼음 조각 · 서리 꽃
        for (int i = 0, m = count(1); i < m; i++) {
            mist(pos.add(back.scale(rand(1.2f, 2.2f))).add(randomDir().scale(0.2)), back.scale(0.02).add(0, 0.01, 0), 0.5f, 1.7f, 16 + RNG.nextInt(6), 0.33f);
        }
        for (int i = 0, m = count(3); i < m; i++) {
            Vec3 at = pos.add(back.scale(rand(0.3f, 2.2f))).add(randomDir().scale(0.35));
            Vec3 v = back.scale(0.03).add(randomDir().scale(0.02));
            Dust.glint(at.x, at.y, at.z, (float) v.x, (float) v.y, (float) v.z, rand(0.2f, 0.42f), 10 + RNG.nextInt(12), 0.003f);
        }
        if (RNG.nextFloat() < 0.7f * FxManager.amount() * FxManager.lod) {
            shardSprite(pos.add(back.scale(rand(0.5f, 1.8f))).add(randomDir().scale(0.25)), back.scale(0.05).add(randomDir().scale(0.04)),
                    rand(0.14f, 0.24f), 12 + RNG.nextInt(8), 0.02f);
        }
        if (p.age % 2 == 0) {
            for (int i = 0, m = count(1); i < m; i++) {
                frost(pos.add(back.scale(rand(0.6f, 1.4f))), back.scale(0.03), 0.9f, 0.4f, 6, 0.8f);
            }
        }
    }

    static void renderProjectile(FxManager.ProjectileFx p, Vec3 c, float pt, float time) {
        float grow = Math.min(1f, (p.age + pt) / 2.5f);
        Vec3 dir = p.dir;
        if (p.skill == Skill.ICE_SHARDS) {
            float len = 0.8f * (0.6f + 0.4f * grow), rad = 0.12f;
            Vec3 base = c.subtract(dir.scale(len * 0.4));
            float roll = time * 0.9f + p.proj.getId() * 1.3f;
            FxRender.alphaBlend();
            BufferBuilder bb = beginBody();
            crystalAlong(bb, BIPYR, base, dir, roll, rad, len, 1f, false, time);
            FxRender.draw(bb);
            FxRender.additive();
            bb = beginGlow(1.15f);
            crystalAlong(bb, BIPYR, base, dir, roll, rad, len, 1.2f, true, time);
            FxRender.draw(bb);
            frameGlow(c, 1.0f, C_CYAN, 0.35f);
            Vec3 tip = c.add(dir.scale(len * 0.6));
            Glints.add(tip.x, tip.y, tip.z, 0.45f + 0.15f * (float) Math.sin(time * 2.3f + p.proj.getId()), 0f, C_WHITE[0], C_WHITE[1], C_WHITE[2], 0.8f);
            return;
        }
        // 얼음 창: 깎인 창 (겉 + 밝은 심) + 꼬리 깃 결정 3개 + 서리 고리 2개 + 창끝 별빛 + 후광
        float len = 2.1f * (0.55f + 0.45f * grow);
        float rad = 0.3f;
        float roll = time * 0.22f;
        Vec3 base = c.add(dir.scale(0.7 - len));
        Vec3[] b = FxRender.basis(dir);
        FxRender.alphaBlend();
        BufferBuilder bb = beginBody();
        crystalAlong(bb, LANCE, base, dir, roll, rad, len, 1.1f, false, time);
        for (int j = 0; j < 3; j++) {
            double a = roll * 1.6 + j * TAU / 3;
            Vec3 out = b[0].scale(Math.cos(a)).add(b[1].scale(Math.sin(a)));
            Vec3 at = c.add(dir.scale(-len * 0.75)).add(out.scale(0.12));
            crystalAlong(bb, BIPYR, at, out.scale(0.55).subtract(dir).normalize(), 0f, 0.07f, 0.55f, 0.9f, false, time);
        }
        FxRender.draw(bb);
        FxRender.additive();
        bb = beginGlow(1.1f);
        crystalAlong(bb, LANCE, base, dir, roll, rad, len, 1.15f, true, time);
        crystalAlong(bb, LANCE, base.add(dir.scale(len * 0.08)), dir, -roll, rad * 0.42f, len * 0.9f, 1.6f, true, time);
        for (int j = 0; j < 3; j++) {
            double a = roll * 1.6 + j * TAU / 3;
            Vec3 out = b[0].scale(Math.cos(a)).add(b[1].scale(Math.sin(a)));
            Vec3 at = c.add(dir.scale(-len * 0.75)).add(out.scale(0.12));
            crystalAlong(bb, BIPYR, at, out.scale(0.55).subtract(dir).normalize(), 0f, 0.07f, 0.55f, 1.1f, true, time);
        }
        FxRender.draw(bb);
        // 서리 고리: 창 허리를 감고 뒤로 흘러가며 커짐
        FxRender.elemShader(I, 1.6f, 0.12f, 1.0f);
        bb = FxRender.quads();
        for (int j = 0; j < 2; j++) {
            float q = ((time * 0.12f) + j * 0.5f) % 1f;
            Vec3 at = c.add(dir.scale(0.2 - q * len * 1.2));
            float r0 = 0.25f + 0.55f * q;
            FxRender.ring(bb, at, dir, r0, r0 + 0.12f, 0.85f * (1f - q), time * 0.6f * (j == 0 ? 1 : -1), 28);
        }
        FxRender.draw(bb);
        Vec3 tip = c.add(dir.scale(0.7));
        Glints.add(tip.x, tip.y, tip.z, 1.1f + 0.3f * (float) Math.sin(time * 1.7f), time * 0.05f, C_WHITE[0], C_WHITE[1], C_WHITE[2], 1f);
        frameGlow(tip, 1.2f, C_WHITE, 0.45f);
        frameGlow(c.subtract(dir.scale(0.5)), 4.2f, C_AZURE, 0.28f + 0.04f * (float) Math.sin(time * 3.1f));
    }

    /** 얼음 창 적중 (IMPACT, size = 파편 반지름) */
    private static void spearImpact(SkillEventPayload e) {
        Vec3 pos = e.pos();
        Vec3 n = e.vec();
        n = n.lengthSqr() < 0.25 ? UP : n.normalize();
        boolean fizzle = e.size2() > 0.5f;
        float R = e.size() > 0.1f ? Math.min(e.size(), 8f) : 2.5f;
        boolean blockHit = e.target() < 0 && !fizzle;
        add(new ImpactFx(pos, n, R, fizzle, blockHit));
    }

    /** 얼음 파편 적중: 가볍게 (7발이 한꺼번에 맞음) */
    private static void shardImpact(SkillEventPayload e) {
        Vec3 pos = e.pos();
        Vec3 n = e.vec();
        n = n.lengthSqr() < 0.25 ? UP : n.normalize();
        boolean fizzle = e.size2() > 0.5f;
        boolean blockHit = e.target() < 0 && !fizzle;
        float big = fizzle ? 0.6f : 1f;
        flashGlow(pos, 1.3f * big, 2.0f * big, 4, C_WHITE, 0.9f);
        flashGlow(pos, 2.2f * big, 3.0f * big, 7, C_CYAN, 0.45f);
        Vec3 rn = blockHit && Math.abs(n.y) < 0.5 ? n : (blockHit ? n : UP);
        FxManager.addRing(pos.add(rn.scale(0.06)), rn, 0.15f, 1.4f * big, 6, 0, 0.7f, I);
        shardBurst(pos, n, 0.32f * big, fizzle ? 3 : 7, 0.2f);
        for (int i = 0, m = count(fizzle ? 2 : 4); i < m; i++) spark(pos, randomDir().scale(rand(0.2f, 0.5f)), 5 + RNG.nextInt(4));
        glintBurst(pos, 0.3f, 0.08f, fizzle ? 3 : 6, 0.32f, 18);
        if (blockHit && RNG.nextFloat() < 0.6f) frostDecal(pos, n, 0.7f, 90, 0.75f, 3f);
        if (!fizzle && e.target() >= 0 && RNG.nextFloat() < 0.5f) frost(pos, Vec3.ZERO, 0.8f, 1.4f, 7, 0.8f);
    }

    private static final class ImpactFx extends Fx {
        final Vec3 pos, n, ringN;
        final float R;
        final boolean fizzle;
        final List<Cr> crs = new ArrayList<>();

        ImpactFx(Vec3 pos, Vec3 n, float R, boolean fizzle, boolean blockHit) {
            this.pos = pos;
            this.n = n;
            this.R = R;
            this.fizzle = fizzle;
            this.ringN = blockHit && Math.abs(n.y) < 0.5 ? n : UP;
            float big = fizzle ? 0.5f : 1f;
            // 흰 섬광 → 청록 번짐
            flashGlow(pos, 4.5f * big, 6.5f * big, 4, C_WHITE, 1f);
            flashGlow(pos, R * 2.4f * big, R * 3.4f * big, 11, C_CYAN, 0.6f);
            // 충격파 고리: 가장 밝은 고리가 파편 피해 반지름 R 에서 멈춤
            Vec3 rc = pos.add(ringN.scale(0.08));
            FxManager.addRing(rc, ringN, 0.4f, R * RK * big, 8, 0, 1f, I);
            if (!fizzle) {
                FxManager.addRing(rc, ringN, 0.3f, R * 0.7f, 6, 1, 0.8f, I);
                FxManager.addRing(pos, n, 0.2f, R * 0.6f, 5, 1, 0.6f, I);
            }
            // 튀는 얼음 조각 · 불꽃 · 반짝이 · 서리 꽃 · 안개
            shardBurst(pos, n, 0.7f * big, fizzle ? 10 : 30, 0.3f);
            for (int i = 0, m = count(fizzle ? 6 : 22); i < m; i++) {
                Vec3 d = randomDir();
                if (d.dot(n) < 0) d = d.subtract(n.scale(2 * d.dot(n)));
                spark(pos, d.scale(rand(0.5f, 1.1f) * big), 6 + RNG.nextInt(6));
            }
            glintBurst(pos, R * 0.4f, 0.14f, fizzle ? 6 : 22, 0.4f, 30);
            for (int i = 0, m = count(fizzle ? 2 : 6); i < m; i++) {
                frost(pos.add(randomDir().scale(0.6)), randomDir().scale(0.05), 1.0f * big, 2.2f * big, 8 + RNG.nextInt(4), 0.9f);
            }
            mistBurst(pos, R * 0.35f * big, fizzle ? 3 : 8, 0.4f);
            // 결정 파편 (메시)
            if (!fizzle) {
                for (int i = 0; i < 8; i++) {
                    Vec3 d = randomDir();
                    if (d.dot(n) < 0) d = d.subtract(n.scale(2 * d.dot(n)));
                    Vec3 v = d.scale(rand(0.2f, 0.45f)).add(n.scale(0.12));
                    float fl = rand(0.25f, 0.5f);
                    addFrag(pos.x, pos.y, pos.z, v.x, v.y, v.z, fl * 0.3f, fl, 18 + RNG.nextInt(12), pos.y - (n.y > 0.7 ? 0.0 : 2.0));
                }
            }
            // 맞은 면에 돋는 결정 무리 (블록에 맞았을 때)
            if (blockHit) {
                float sc = Math.min(1.6f, R / 2.5f);
                int m = 4 + RNG.nextInt(3);
                for (int j = 0; j < m; j++) {
                    Vec3 d = j == 0 ? n : n.add(randomDir().scale(0.75));
                    if (d.dot(n) < 0.25) d = d.add(n.scale(0.6));
                    Vec3 at = pos.subtract(n.scale(0.15)).add(randomDir().scale(j == 0 ? 0.05 : 0.35 * sc));
                    float len = (j == 0 ? rand(1.1f, 1.5f) : rand(0.5f, 1.0f)) * sc;
                    Cr c = new Cr(at.x, at.y, at.z, d, RNG.nextFloat() * TAU, len * rand(0.17f, 0.24f), len, RNG.nextInt(10));
                    c.born = j == 0 ? 0 : RNG.nextInt(3);
                    c.grow = 2;
                    c.end = 18 + RNG.nextInt(6);
                    crs.add(c);
                }
                frostDecal(pos, n, R * 0.95f, 160, 0.85f, 4f);
                DebrisFx.burst(pos, R * 0.55f, 10, 0.5f);
            }
            if (!fizzle) shake(pos, 1.6f, 14f);
        }

        @Override
        boolean tick() {
            age++;
            FxManager.lod = FxManager.lodAt(pos);
            for (Cr c : crs) {
                if (!c.broken && age >= c.end) c.shatter(0, 0, 0, 3, 1f, pos.y - 1.0, true);
            }
            if (age == 4 && !fizzle) {
                for (int i = 0, m = count(4); i < m; i++) {
                    Vec3 at = pos.add(randomDir().scale(R * 0.4));
                    Dust.glint(at.x, at.y, at.z, 0, 0.01f, 0, rand(0.3f, 0.5f), 24 + RNG.nextInt(14), 0.0005f);
                }
            }
            return age > 28;
        }

        @Override
        void body(BufferBuilder bb, float pt, float time) {
            float t = age + pt;
            for (Cr c : crs) c.draw(bb, 0, 0, 0, c.scale(t), 1f, false, time);
        }

        @Override
        void glow(BufferBuilder bb, float pt, float time) {
            float t = age + pt;
            for (Cr c : crs) {
                float k = 1f;
                if (t > c.end - 6) k += 0.8f * (float) Math.abs(Math.sin(t * 2.7f)); // 금 가며 번쩍
                c.draw(bb, 0, 0, 0, c.scale(t), k, true, time);
            }
        }

        @Override
        void extra(float pt, float time) {
            float t = age + pt;
            if (t > 10f) return;
            float big = fizzle ? 0.5f : 1f;
            float radius = R * big * (0.2f + 0.55f * easeOutCubic(t / 4f));
            float erosion = t < 2f ? 0.02f : Math.min(1f, (t - 2f) / 7f);
            FxRender.elemShader(I, 1.3f, erosion, 1.15f * (1f - t / 10f));
            RenderSystem.disableCull();
            FxManager.BlastSphere.draw(pos, radius, t * 1.3f);
            FxRender.elemShader(I, 1.8f, Math.min(1f, erosion + 0.1f), 1f - t / 10f);
            BufferBuilder bb = FxRender.quads();
            FxRender.ring(bb, pos.add(ringN.scale(0.05)), ringN, radius * 0.9f, radius * 1.5f, 0.9f, t * 0.4f, 40);
            FxRender.draw(bb);
        }

        @Override
        Vec3 anchor() {
            return pos;
        }
    }

    // =====================================================================
    // 3. 빙창 행진 (SPIKES)
    // =====================================================================

    private static final class SpikesFx extends Fx {
        final Vec3 start, dir, side;
        final float W, range;
        final int duration;
        @Nullable
        final Entity who;
        final List<Cr> crs = new ArrayList<>();
        final float step;
        float nextD;
        int rows;
        int lastSound = -100;

        SpikesFx(Vec3 start, Vec3 vec, float halfWidth, float range, int duration, @Nullable Entity who) {
            this.start = start;
            Vec3 f = new Vec3(vec.x, 0, vec.z);
            this.dir = f.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : f.normalize();
            this.side = new Vec3(-dir.z, 0, dir.x);
            this.W = halfWidth > 0.2f ? Math.min(halfWidth, 6f) : 1.6f;
            this.range = range > 0.5f ? Math.min(range, 64f) : 18f;
            this.duration = Math.max(2, duration);
            this.who = who;
            this.step = 0.72f;
            this.nextD = 0.9f;
            // 가시가 서 있는 시간 = 얼림 시간 (얼음이 풀리는 순간 같이 부서짐)
            this.hold = Math.max(10, ClientSkillStats.durationTicks(Skill.ICE_SPIKES));
            // 시작: 발밑에서 냉기가 터짐 + 나아갈 방향으로 밀려 나가는 안개
            FxManager.addRing(start.add(0, 0.06, 0), UP, 0.4f, W * 1.6f, 7, 0, 0.9f, I);
            flashGlow(start.add(0, 0.4, 0), 2.5f, 3.5f, 4, C_PALE, 0.8f);
            for (int i = 0, m = count(6); i < m; i++) {
                mist(start.add(side.scale(rand(-W, W))).add(0, 0.3, 0), dir.scale(rand(0.12f, 0.25f)).add(0, 0.01, 0), 0.9f, 2.2f, 18 + RNG.nextInt(8), 0.35f);
            }
            frostDecal(start, UP, W * 1.4f, this.duration + hold + 20, 0.8f, 4f);
            shake(start, 1.2f, 10f);
        }

        /** 가시가 서 있는 틱 수 */
        final int hold;

        Vec3 frontAt(float t) {
            return start.add(dir.scale(range * Math.min(1f, t / duration)));
        }

        @Override
        Vec3 anchor() {
            return frontAt(age);
        }

        @Override
        boolean tick() {
            age++;
            float front = range * Math.min(1f, (float) age / duration);
            FxManager.lod = FxManager.lodAt(start.add(dir.scale(front)));
            int guard = 0;
            while (nextD <= front + 1.0E-3f && nextD <= range && guard++ < 12) {
                row(nextD);
                nextD += step;
            }
            boolean broke = false;
            Vec3 brokeAt = null;
            for (Cr c : crs) {
                if (!c.broken && age >= c.end) {
                    c.shatter(0, 0, 0, RNG.nextFloat() < 0.6f ? 2 : 1, 0.9f, c.y - 0.1, RNG.nextFloat() < 0.6f);
                    broke = true;
                    brokeAt = new Vec3(c.x, c.y, c.z);
                }
            }
            if (broke && age - lastSound > 7 && brokeAt != null) {
                lastSound = age;
                FxManager.playAt(brokeAt, SkillsRegistry.snd("ice.shatter"), 0.9f, 1.15f + RNG.nextFloat() * 0.2f);
            }
            return age > duration + hold + 14;
        }

        private void row(double d) {
            Vec3 p = start.add(dir.scale(d));
            Vec3 g0 = groundAt(who, p);
            float wk = (float) Math.sqrt(W / 1.6f);
            int n = 3 + (RNG.nextFloat() < 0.6f ? 1 : 0);
            for (int j = 0; j < n; j++) {
                if (crs.size() >= 110) break;
                // 가시 끝이 판정 폭(W) 안에 머물도록: 바깥 가시일수록 덜 기울임
                float lat;
                boolean small = j == 3;
                if (j == 0) lat = rand(-0.18f, 0.18f) * W;
                else if (j == 1) lat = -rand(0.4f, 0.8f) * W;
                else if (j == 2) lat = rand(0.4f, 0.8f) * W;
                else lat = rand(-0.85f, 0.85f) * W;
                Vec3 q = p.add(side.scale(lat)).add(dir.scale(rand(-0.25f, 0.25f)));
                Vec3 g = groundAt(who, q);
                float out = lat / W;
                double tilt = 0.1 + 0.38 * Math.abs(out) + (small ? 0.2 : 0);
                Vec3 axis = tilted(side.scale(Math.signum(out == 0 ? 1 : out)), tilt, dir.scale(0.28));
                float h = (j == 0 ? rand(1.7f, 2.6f) : rand(1.0f, 1.8f)) * wk * (small ? 0.5f : 1f);
                float rad = h * rand(0.16f, 0.22f);
                Cr c = new Cr(g.x, g.y - 0.2, g.z, axis, RNG.nextFloat() * TAU, rad, h, RNG.nextInt(10));
                c.born = age;
                c.grow = 2;
                c.end = age + hold - 2 + RNG.nextInt(5);
                crs.add(c);
            }
            rows++;
            // 솟을 때: 서리 꽃 · 위로 튀는 조각 · 반짝이 · 안개
            Vec3 gp = g0.add(0, 0.3, 0);
            for (int i = 0, m = count(2); i < m; i++) frost(gp.add(side.scale(rand(-W, W))), new Vec3(0, 0.05, 0), 1.0f, 2.0f, 7 + RNG.nextInt(3), 0.85f);
            for (int i = 0, m = count(4); i < m; i++) {
                shardSprite(gp.add(side.scale(rand(-W, W))), new Vec3(rand(-0.08f, 0.08f), rand(0.2f, 0.42f), rand(-0.08f, 0.08f)).add(dir.scale(0.06)),
                        rand(0.2f, 0.32f), 14 + RNG.nextInt(8), 0.04f);
            }
            for (int i = 0, m = count(3); i < m; i++) {
                Vec3 at = gp.add(side.scale(rand(-W, W))).add(0, rand(0f, 1.5f), 0);
                Dust.glint(at.x, at.y, at.z, 0, rand(0.01f, 0.04f), 0, rand(0.25f, 0.45f), 18 + RNG.nextInt(20), 0.001f);
            }
            if (RNG.nextFloat() < 0.7f * FxManager.amount() * FxManager.lod) {
                mist(gp.add(side.scale(rand(-W, W))), new Vec3(0, 0.02, 0).add(dir.scale(0.03)), 1.0f, 2.6f, 26, 0.3f);
            }
            if (rows % 2 == 1) DebrisFx.directed(g0, dir, 2.4f, 40f, 4, 0.55f);
            if (rows % 3 == 0) frostDecal(g0, UP, W * 1.35f, duration + hold + 20 - age, 0.8f, 3f);
        }

        @Override
        void body(BufferBuilder bb, float pt, float time) {
            float t = age + pt;
            for (Cr c : crs) c.draw(bb, 0, 0, 0, c.scale(t), 1f, false, time);
        }

        @Override
        void glow(BufferBuilder bb, float pt, float time) {
            float t = age + pt;
            for (Cr c : crs) {
                if (c.broken) continue;
                float k = 1f;
                float since = t - c.born;
                if (since < 3f) k += 1.2f * (1f - since / 3f);         // 솟는 순간 하얗게
                if (t > c.end - 5) k += 0.9f * (float) Math.abs(Math.sin(t * 2.9f + c.shape)); // 금 가며 번쩍
                c.draw(bb, 0, 0, 0, c.scale(t), k, true, time);
                if (since < 6f && since >= 0f) {
                    float a = 1f - since / 6f;
                    Glints.add(c.x + c.ax * c.len, c.y + c.ay * c.len, c.z + c.az * c.len, 0.9f * a + 0.2f, 0f, 1f, 1f, 1f, a);
                }
            }
        }

        @Override
        void extra(float pt, float time) {
            float t = age + pt;
            // 판정 폭을 알려 주는 길 경계 (앞머리를 따라 그어지고, 다 가면 빨리 사라짐)
            float la = 0.55f * (1f - smooth((t - duration) / 8f));
            if (la > 0.01f) lane(start.add(0, 0.06, 0), dir, W, Math.max(0f, range * Math.min(1f, t / duration) - 0.6f), la);
            if (t >= duration) return;
            Vec3 fr = frontAt(t).add(0, 0.6, 0);
            frameGlow(fr, 3.2f, C_CYAN, 0.45f);
            frameGlow(fr, 1.4f, C_WHITE, 0.5f);
        }
    }

    // =====================================================================
    // 4. 절대 냉기 (NOVA)
    // =====================================================================

    private static final class NovaFx extends Fx {
        final Vec3 c;
        final float R;
        final int freeze;
        final List<Cr> crs = new ArrayList<>();

        NovaFx(Vec3 c, float radius, int freeze, @Nullable Entity who) {
            this.c = c;
            this.R = radius > 0.5f ? Math.min(radius, 24f) : 6.5f;
            this.freeze = freeze;
            Vec3 core = c.add(0, 1.0, 0);
            flashGlow(core, 6f, 9f, 5, C_WHITE, 1f);
            flashGlow(core, R * 2.6f, R * 3.4f, 10, C_CYAN, 0.6f);
            // 충격파: 가장 밝은 고리가 얼림 범위 R 에서 멈춤
            FxManager.addRing(c.add(0, 0.08, 0), UP, 0.5f, R * RK, 8, 0, 1f, I);
            FxManager.addRing(c.add(0, 0.1, 0), UP, 0.3f, R * 0.75f, 7, 2, 0.8f, I);
            FxManager.addRing(core, UP, 0.4f, R * RK, 7, 1, 0.6f, I);
            // 바깥으로 쏟아지는 안개 벽 + 얼음 조각 + 반짝이
            mistRing(c, 0.8f, R * 0.06f, 22, 1.4f, 0.42f);
            for (int i = 0, m = count(40); i < m; i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 d = new Vec3(Math.cos(ang), rand(0.15f, 0.6f), Math.sin(ang));
                shardSprite(core.add(d.scale(0.5)), d.scale(rand(0.3f, 0.75f) * R / 6.5f), rand(0.22f, 0.4f), 14 + RNG.nextInt(10), 0.03f);
            }
            for (int i = 0, m = count(16); i < m; i++) {
                float ang = RNG.nextFloat() * TAU;
                frost(c.add(Math.cos(ang) * R * 0.3, 0.4, Math.sin(ang) * R * 0.3), new Vec3(Math.cos(ang), 0.05, Math.sin(ang)).scale(0.25 * R / 6.5f),
                        1.2f, 2.6f, 10 + RNG.nextInt(4), 0.85f);
            }
            for (int i = 0, m = count(20); i < m; i++) spark(core, randomDir().scale(rand(0.6f, 1.2f)), 6 + RNG.nextInt(6));
            // 바닥에서 바깥으로 기울어 돋는 가시 고리 (발밑 1.5칸 안은 비움)
            int n = Math.min(44, Math.max(10, count((int) (R * 6))));
            for (int i = 0; i < n; i++) {
                float ang = RNG.nextFloat() * TAU;
                // 가시 끝이 경계 R 을 넘지 않게 (뿌리는 R - 1 안쪽, 바깥일수록 덜 기울임)
                float rr = 1.5f + Math.max(0f, R - 2.5f) * (float) Math.sqrt(RNG.nextFloat());
                Vec3 out = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                Vec3 g = groundAt(who, c.add(out.scale(rr)));
                float f = rr / R;
                double tilt = 0.3 + 0.4 * f;
                Vec3 axis = tilted(out, tilt, Vec3.ZERO);
                float len = rand(0.8f, 1.6f) * (1.25f - 0.5f * f) * (float) Math.sqrt(R / 6.5f);
                Cr cr = new Cr(g.x, g.y - 0.15, g.z, axis, RNG.nextFloat() * TAU, len * rand(0.17f, 0.24f), len, RNG.nextInt(10));
                cr.born = Math.round(5f * f);
                cr.grow = 2;
                // 얼림이 풀리는 순간에 맞춰 부서짐
                cr.end = Math.max(16, freeze) - 4 + RNG.nextInt(8) + cr.born;
                crs.add(cr);
            }
            frostDecal(c, UP, R * 1.05f, 50 + freeze, 0.85f, 5f);
            frostDecal(c, UP, R * 0.6f, 40 + freeze, 0.6f, 3f);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                double d = mc.player.position().distanceTo(c);
                if (d < R + 4) ScreenFx.flash(I.color, 0.3f * (float) (1 - d / (R + 4)) + 0.1f, 10);
            }
            shake(c, 2.2f, R + 12f);
        }

        @Override
        Vec3 anchor() {
            return c;
        }

        @Override
        boolean tick() {
            age++;
            FxManager.lod = FxManager.lodAt(c);
            boolean broke = false;
            for (Cr cr : crs) {
                if (!cr.broken && age >= cr.end) {
                    cr.shatter(0, 0, 0, RNG.nextFloat() < 0.5f ? 1 : 0, 0.7f, cr.y - 0.1, RNG.nextFloat() < 0.5f);
                    broke = true;
                }
            }
            if (broke && age % 8 == 0) FxManager.playAt(c, SkillsRegistry.snd("ice.shatter"), 0.7f, 1.3f);
            if (age < 30) {
                // 눈보라: 위에서 흩날리는 눈송이 + 바닥 반짝이
                for (int i = 0, m = count(4); i < m; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = R * (float) Math.sqrt(RNG.nextFloat());
                    Dust.flake(c.x + Math.cos(ang) * rr, c.y + rand(2.5f, 5f), c.z + Math.sin(ang) * rr,
                            rand(-0.03f, 0.03f), rand(-0.06f, -0.03f), rand(-0.03f, 0.03f), rand(0.12f, 0.22f), 40 + RNG.nextInt(30));
                }
                for (int i = 0, m = count(3); i < m; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = R * (float) Math.sqrt(RNG.nextFloat());
                    Dust.glint(c.x + Math.cos(ang) * rr, c.y + rand(0.1f, 1.2f), c.z + Math.sin(ang) * rr, 0, rand(0.005f, 0.02f), 0,
                            rand(0.25f, 0.45f), 16 + RNG.nextInt(20), 0.0005f);
                }
                if (age % 4 == 0) {
                    for (int i = 0, m = count(2); i < m; i++) {
                        float ang = RNG.nextFloat() * TAU;
                        float rr = R * rand(0.3f, 1f);
                        mist(c.add(Math.cos(ang) * rr, 0.3, Math.sin(ang) * rr), new Vec3(Math.cos(ang) * 0.02, 0.01, Math.sin(ang) * 0.02), 1.6f, 3.4f, 24, 0.3f);
                    }
                }
            }
            return age > Math.max(16, freeze) + 18;
        }

        @Override
        void body(BufferBuilder bb, float pt, float time) {
            float t = age + pt;
            for (Cr cr : crs) cr.draw(bb, 0, 0, 0, cr.scale(t), 1f, false, time);
        }

        @Override
        void glow(BufferBuilder bb, float pt, float time) {
            float t = age + pt;
            for (Cr cr : crs) {
                if (cr.broken) continue;
                float k = 1f;
                float since = t - cr.born;
                if (since >= 0 && since < 3f) k += 1.3f * (1f - since / 3f);
                if (t > cr.end - 5) k += 0.8f * (float) Math.abs(Math.sin(t * 3.1f + cr.shape));
                cr.draw(bb, 0, 0, 0, cr.scale(t), k, true, time);
            }
        }

        @Override
        void extra(float pt, float time) {
            float t = age + pt;
            // 얼음 돔: 순식간에 부풀었다가 서리로 갈라지며 사라짐
            if (t < 14f) {
                float radius = R * (0.15f + 0.6f * easeOutCubic(t / 5f));
                float erosion = t < 3f ? 0.03f : Math.min(1f, (t - 3f) / 10f);
                FxRender.elemShader(I, 0.9f, erosion, 1.0f * (1f - t / 14f) + 0.1f);
                RenderSystem.disableCull();
                FxManager.BlastSphere.draw(c.add(0, 0.4, 0), radius, t * 0.8f);
            }
            // 바닥을 따라 퍼지는 결정 고리 (4틱 만에 경계 R 에 닿음 = 판정은 이미 들어간 상태)
            if (t < 14f) {
                float f = easeOutCubic(t / 4f);
                float rf = 0.6f + (R - 0.6f) * f;
                float fade = 1f - smooth((t - 4f) / 10f);
                FxRender.elemShader(I, 1.4f, 0.08f + 0.5f * (1f - fade), 1.2f * fade);
                BufferBuilder bb = FxRender.quads();
                FxRender.ring(bb, c.add(0, 0.07, 0), UP, rf * 0.82f, rf, 0.95f, t * 0.5f, 64);
                FxRender.ring(bb, c.add(0, 0.08, 0), UP, rf * 0.4f, rf * 0.82f, 0.35f * fade, -t * 0.3f, 48);
                FxRender.draw(bb);
            }
            // 얼림 범위 경계: 터지는 순간 가장 밝고 얼림이 이어지는 동안 서서히 옅어짐
            float hold = Math.max(16, freeze);
            if (t < hold) edgeRing(c, R, (t < 3f ? 1f : 0.85f) * (1f - smooth(t / hold)), time);
            if (t < 20f) runeCircle(c, R * (0.7f + 0.3f * easeOutCubic(t / 4f)), 1f, time, 1f - smooth(t / 20f));
        }
    }

    // =====================================================================
    // 5. 빙하기 강림 (준비: 떨어지는 빙산 / GLACIER 0: 충돌 / GLACIER 1: 산산조각)
    // =====================================================================

    /** 빙산 모양 (중심 바닥 기준 상대 좌표). 같은 자리면 같은 씨앗 → 떨어지던 빙산과 박힌 빙산이 같은 모양 */
    private static List<Cr> buildBerg(Vec3 at, float R) {
        long seed = ((long) Math.floor(at.x / 2.0)) * 73856093L ^ ((long) Math.floor(at.z / 2.0)) * 19349663L;
        Random r = new Random(seed);
        List<Cr> out = new ArrayList<>();
        // 거대한 본체
        Vec3 ax = new Vec3((r.nextFloat() - 0.5f) * 0.22f, 1, (r.nextFloat() - 0.5f) * 0.22f);
        out.add(new Cr(0, -R * 0.32, 0, ax, r.nextFloat() * TAU, R * 0.36f, R * 1.75f, r.nextInt(10)));
        // 둘레 큰 결정 5개 (바깥으로 기욺)
        for (int i = 0; i < 5; i++) {
            float ang = (i + (r.nextFloat() - 0.5f) * 0.5f) * TAU / 5;
            Vec3 o = new Vec3(Math.cos(ang), 0, Math.sin(ang));
            double tilt = 0.4 + 0.3 * r.nextFloat();
            Vec3 a = UP.scale(Math.cos(tilt)).add(o.scale(Math.sin(tilt)));
            float len = R * (0.75f + 0.4f * r.nextFloat());
            out.add(new Cr(o.x * R * 0.22, -R * 0.22, o.z * R * 0.22, a, r.nextFloat() * TAU, R * (0.13f + 0.07f * r.nextFloat()), len, r.nextInt(10)));
        }
        // 바깥 작은 결정 7개
        for (int i = 0; i < 7; i++) {
            float ang = (i + 0.5f + (r.nextFloat() - 0.5f) * 0.6f) * TAU / 7;
            Vec3 o = new Vec3(Math.cos(ang), 0, Math.sin(ang));
            double tilt = 0.75 + 0.35 * r.nextFloat();
            Vec3 a = UP.scale(Math.cos(tilt)).add(o.scale(Math.sin(tilt)));
            float len = R * (0.38f + 0.25f * r.nextFloat());
            out.add(new Cr(o.x * R * 0.4, -R * 0.1, o.z * R * 0.4, a, r.nextFloat() * TAU, R * (0.07f + 0.04f * r.nextFloat()), len, r.nextInt(10)));
        }
        return out;
    }

    private static final class Berg {
        final List<Cr> crs;
        final float R;
        int seen;

        Berg(Vec3 at, float R) {
            this.R = R;
            this.crs = buildBerg(at, R);
        }
    }

    /** 떨어지는 높이 (k = 0 → 1 에서 가속하며 내려와 k = 1 에 정확히 땅에 닿음) */
    private static float fallHeight(float k, float R) {
        float h = 30f + R * 1.6f;
        k = clamp01(k);
        return h * (1f - k * k);
    }

    private static final class GlacierFx extends Fx {
        final Vec3 center;
        final float R;
        final int duration;
        final List<Cr> berg;
        final List<Cr> field = new ArrayList<>();
        boolean shattered;
        int shatterAge = -1;

        GlacierFx(Vec3 center, float R, int duration, @Nullable Entity who) {
            this.center = center;
            this.R = Math.min(R, 24f);
            this.duration = duration;
            this.berg = buildBerg(center, this.R);
            Vec3 core = center.add(0, 1.5, 0);
            Minecraft mc = Minecraft.getInstance();
            double d = mc.player != null ? mc.player.position().distanceTo(center) : 999;
            // 화면: 흰 섬광 + 강한 흔들림
            if (d < R * 7) ScreenFx.flash(0xE6FAFF, (float) (0.85 * (1 - d / (R * 7))) + 0.1f, 18);
            shake(center, 6f, R + 44f);
            // 섬광 · 충격파
            flashGlow(core, R * 2.2f, R * 3.2f, 6, C_WHITE, 1f);
            flashGlow(core, R * 4f, R * 6f, 16, C_CYAN, 0.75f);
            flashGlow(center.add(0, R * 0.9, 0), R * 3f, R * 4f, 10, C_PALE, 0.5f);
            // 충격파: 가장 밝은 고리는 피해 반지름 R 에서 멈추고, 바깥의 옅은 공기 고리만 더 멀리 감
            FxManager.addRing(center.add(0, 0.1, 0), UP, 1f, R * RK, 10, 0, 1f, I);
            FxManager.addRing(center.add(0, 0.12, 0), UP, R * 0.3f, R * 0.8f, 8, 2, 0.9f, I);
            FxManager.addRing(center.add(0, R * 0.5, 0), UP, R * 0.3f, R * 1.3f, 9, 1, 0.55f, I);
            FxManager.addRing(center.add(0, 1.5, 0), UP, R * 0.6f, R * 2.6f, 16, 3, 0.3f, I);
            // 지형 파편 + 패인 자국
            DebrisFx.burst(center, R * 0.9f, 60, 1.4f);
            DebrisFx.crater(center, R * 0.85f, duration + 40, I);
            // 튀는 얼음 조각 · 불꽃 · 서리 꽃 · 안개 벽
            for (int i = 0, m = count(130); i < m; i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 dd = new Vec3(Math.cos(ang), rand(0.2f, 1.1f), Math.sin(ang)).normalize();
                shardSprite(core.add(dd.scale(rand(0.5f, R * 0.4f))), dd.scale(rand(0.4f, 1.3f) * (0.6f + R / 16f)), rand(0.3f, 0.6f), 20 + RNG.nextInt(16), 0.04f);
            }
            for (int i = 0, m = count(70); i < m; i++) spark(core, randomDir().add(0, 0.4, 0).scale(rand(0.8f, 1.8f)), 8 + RNG.nextInt(8));
            for (int i = 0, m = count(28); i < m; i++) {
                float ang = RNG.nextFloat() * TAU;
                float rr = R * rand(0.2f, 0.7f);
                frost(center.add(Math.cos(ang) * rr, rand(0.5f, 2.5f), Math.sin(ang) * rr), new Vec3(Math.cos(ang), 0.1, Math.sin(ang)).scale(0.25),
                        2.0f, 4.5f, 12 + RNG.nextInt(6), 0.9f);
            }
            mistRing(center, R * 0.4f, 0.35f + R * 0.02f, 40, 2.6f, 0.5f);
            mistBurst(center.add(0, 1.5, 0), R * 0.5f, 14, 0.45f);
            glintBurst(center.add(0, 2, 0), R * 0.7f, 0.12f, 120, 0.5f, 50);
            // 결정 파편 (메시)
            for (int i = 0; i < 26; i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 v = new Vec3(Math.cos(ang) * rand(0.3f, 0.75f), rand(0.35f, 0.9f), Math.sin(ang) * rand(0.3f, 0.75f));
                float fl = rand(0.5f, 1.3f);
                addFrag(center.x + Math.cos(ang) * R * 0.3, center.y + 1, center.z + Math.sin(ang) * R * 0.3, v.x, v.y, v.z, fl * 0.3f, fl,
                        26 + RNG.nextInt(16), center.y);
            }
            // 얼어붙은 들판: 충격파를 따라 바깥으로 기울어 돋는 가시
            int n = Math.min(48, Math.max(14, count((int) (R * 5.5f))));
            for (int i = 0; i < n; i++) {
                float ang = RNG.nextFloat() * TAU;
                // 얼음 지대(= 0.5초마다 피해 범위 R) 안쪽에만 돋음
                float rr = R * (0.4f + 0.45f * (float) Math.sqrt(RNG.nextFloat()));
                Vec3 out = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                Vec3 g = groundAt(who, center.add(out.scale(rr)));
                float f = rr / R;
                Vec3 axis = tilted(out, 0.3 + 0.4 * f, Vec3.ZERO);
                float len = rand(1.0f, 2.4f) * (1.4f - 0.6f * f) * (float) Math.sqrt(R / 8f);
                Cr cr = new Cr(g.x - center.x, g.y - center.y - 0.2, g.z - center.z, axis, RNG.nextFloat() * TAU, len * rand(0.16f, 0.23f), len, RNG.nextInt(10));
                cr.born = 1 + Math.round(7f * f);
                cr.grow = 3;
                field.add(cr);
            }
            frostDecal(center, UP, R * 1.4f, duration + 100, 0.9f, 6f);
            frostDecal(center, UP, R * 0.8f, duration + 80, 0.7f, 3f);
        }

        @Override
        Vec3 anchor() {
            return center;
        }

        @Override
        boolean tick() {
            age++;
            FxManager.lod = FxManager.lodAt(center);
            if (shattered) return age - shatterAge > 14;
            if (age > duration + 12) {
                // 산산조각 신호를 못 받음 (멀어짐 등) → 조용히 부서짐
                shatter();
                return false;
            }
            // 예고: 부서지기 0.6초 전 얼음이 삐걱이며 금 가는 소리 (경계 고리도 깜빡임)
            if (age == duration - 12) FxManager.playAt(center, SkillsRegistry.snd("ice.shatter"), 1.4f, 0.55f);
            // 얼음 지대: 낮게 깔린 안개 · 반짝이는 가루 · 흩날리는 눈
            for (int i = 0, m = count(3); i < m; i++) {
                float ang = RNG.nextFloat() * TAU;
                float rr = R * (float) Math.sqrt(RNG.nextFloat());
                Dust.glint(center.x + Math.cos(ang) * rr, center.y + rand(0.1f, 1.6f), center.z + Math.sin(ang) * rr, 0, rand(0.004f, 0.02f), 0,
                        rand(0.3f, 0.55f), 20 + RNG.nextInt(24), 0.0003f);
            }
            for (int i = 0, m = count(2); i < m; i++) {
                float ang = RNG.nextFloat() * TAU;
                float rr = R * 1.1f * (float) Math.sqrt(RNG.nextFloat());
                Dust.flake(center.x + Math.cos(ang) * rr, center.y + rand(4f, 9f), center.z + Math.sin(ang) * rr,
                        rand(-0.03f, 0.03f), rand(-0.07f, -0.04f), rand(-0.03f, 0.03f), rand(0.14f, 0.24f), 60 + RNG.nextInt(30));
            }
            if (age % 3 == 0) {
                for (int i = 0, m = count(2); i < m; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = R * rand(0.2f, 1.05f);
                    mist(center.add(Math.cos(ang) * rr, 0.25, Math.sin(ang) * rr), new Vec3(Math.cos(ang) * 0.015, 0.004, Math.sin(ang) * 0.015),
                            2.0f, 4.2f, 40, 0.26f);
                }
            }
            if (age % 5 == 0 && RNG.nextFloat() < FxManager.amount() * FxManager.lod) {
                Cr c = berg.get(RNG.nextInt(berg.size()));
                float along = RNG.nextFloat() * c.len;
                frost(new Vec3(center.x + c.x + c.ax * along, center.y + c.y + c.ay * along, center.z + c.z + c.az * along), Vec3.ZERO, 1.2f, 2.2f, 10, 0.5f);
            }
            return false;
        }

        /** GLACIER 1: 빙산과 들판이 반짝이는 결정 폭풍으로 산산조각 */
        void shatter() {
            if (shattered) return;
            shattered = true;
            shatterAge = age;
            FxManager.lod = FxManager.lodAt(center);
            for (int i = 0; i < berg.size(); i++) {
                Cr c = berg.get(i);
                c.shatter(center.x, center.y, center.z, i == 0 ? 16 : (i < 6 ? 6 : 3), i == 0 ? 2.2f : 1.6f, center.y, true);
            }
            for (Cr c : field) {
                if (!c.broken) c.shatter(center.x, center.y, center.z, 1, 1.2f, center.y, RNG.nextFloat() < 0.5f);
            }
            glacierShatterBurst(center, R, this);
        }

        private float landBounce(float t) {
            // 박힌 직후 살짝 더 파고들었다가 자리 잡음
            if (t > 6f) return 0f;
            return (float) (-R * 0.05f * Math.sin(Math.PI * t / 6f));
        }

        @Override
        void body(BufferBuilder bb, float pt, float time) {
            if (shattered) return;
            float t = age + pt;
            double oy = center.y + landBounce(t);
            for (Cr c : berg) c.draw(bb, center.x, oy, center.z, 1f, 1.15f, false, time);
            for (Cr c : field) c.draw(bb, center.x, center.y, center.z, c.scale(t), 1f, false, time);
        }

        @Override
        void glow(BufferBuilder bb, float pt, float time) {
            if (shattered) return;
            float t = age + pt;
            double oy = center.y + landBounce(t);
            float hit = t < 8f ? 1.4f * (1f - t / 8f) : 0f;
            float pre = clamp01((t - (duration - 12)) / 12f); // 부서지기 직전 금 가며 번쩍임
            float crack = pre > 0 ? pre * 0.9f * (float) Math.abs(Math.sin(t * (2f + 3f * pre))) : 0f;
            float breathe = 0.08f * (float) Math.sin(time * 0.15f);
            for (Cr c : berg) c.draw(bb, center.x, oy, center.z, 1f, 1f + hit + crack + breathe, true, time);
            for (Cr c : field) {
                float since = t - c.born;
                float k = 1f + crack + (since >= 0 && since < 3f ? 1.2f * (1f - since / 3f) : 0f);
                c.draw(bb, center.x, center.y, center.z, c.scale(t), k, true, time);
            }
            // 결정 끝 별빛
            for (int i = 0; i < berg.size(); i++) {
                Cr c = berg.get(i);
                float tw = (float) Math.abs(Math.sin(time * 0.35f + i * 1.7f));
                tw = tw * tw * tw * tw;
                if (tw < 0.1f) continue;
                Glints.add(center.x + c.x + c.ax * c.len, oy + c.y + c.ay * c.len, center.z + c.z + c.az * c.len,
                        (i == 0 ? 3.2f : 1.6f) * tw, 0f, 1f, 1f, 1f, tw);
            }
        }

        @Override
        void extra(float pt, float time) {
            float t = age + pt;
            if (shattered) {
                // 산산조각 순간의 흰 돔
                float s = age - shatterAge + pt;
                if (s < 12f) {
                    float erosion = s < 2f ? 0.03f : Math.min(1f, (s - 2f) / 9f);
                    FxRender.elemShader(I, 1.1f, erosion, 1.2f * (1f - s / 12f));
                    RenderSystem.disableCull();
                    FxManager.BlastSphere.draw(center.add(0, R * 0.4, 0), R * (0.5f + 0.6f * easeOutCubic(s / 5f)), s);
                }
                return;
            }
            // 충돌 돔: 순식간에 부풀었다가 서리로 갈라지며 사라짐
            if (t < 16f) {
                float radius = R * (0.3f + 0.7f * easeOutCubic(t / 5f));
                float erosion = t < 3f ? 0.02f : Math.min(1f, (t - 3f) / 12f);
                FxRender.elemShader(I, 1.0f, erosion, 1.25f * (1f - t / 16f) + 0.05f);
                RenderSystem.disableCull();
                FxManager.BlastSphere.draw(center.add(0, R * 0.15, 0), radius, t * 0.7f);
            }
            // 바닥을 따라 퍼지는 결정 충격파 고리
            if (t < 18f) {
                float f = easeOutCubic(t / 5f);
                float rf = 1f + (R - 1f) * f;
                float fade = 1f - smooth((t - 6f) / 12f);
                FxRender.elemShader(I, 1.4f, 0.06f + 0.5f * (1f - fade), 1.3f * fade);
                BufferBuilder bb = FxRender.quads();
                FxRender.ring(bb, center.add(0, 0.08, 0), UP, rf * 0.84f, rf, 1f, t * 0.4f, 72);
                FxRender.ring(bb, center.add(0, 0.09, 0), UP, rf * 0.5f, rf * 0.84f, 0.4f * fade, -t * 0.25f, 56);
                FxRender.draw(bb);
            }
            // 얼음 지대 경계 (피해 범위): 은은하게 맥동하는 마법진 + 또렷한 경계 고리
            float zone = smooth(t / 8f);
            runeCircle(center, R, 0.35f + 0.15f * (float) Math.sin(time * 0.2f), time, 0.5f * zone);
            float pre = clamp01((t - (duration - 13)) / 12f); // 산산조각 예고: 경계가 점점 밝게 깜빡임
            float blink = pre > 0 ? pre * (0.5f + 0.5f * (float) Math.sin(t * (1.2f + 2.4f * pre))) : 0f;
            edgeRing(center, R, (0.55f + 0.6f * blink) * zone, time);
            frameGlow(center.add(0, R * 0.7, 0), R * 3.2f, C_AZURE, 0.18f + 0.04f * (float) Math.sin(time * 0.3f));
        }
    }

    /** 산산조각 폭풍 (빙산이 없어도 신호만으로 터뜨릴 수 있게 분리) */
    private static void glacierShatterBurst(Vec3 center, float R, @Nullable GlacierFx g) {
        FxManager.lod = FxManager.lodAt(center);
        Vec3 core = center.add(0, R * 0.5, 0);
        Minecraft mc = Minecraft.getInstance();
        double d = mc.player != null ? mc.player.position().distanceTo(center) : 999;
        if (d < R * 5) ScreenFx.flash(0xCFF4FF, (float) (0.55 * (1 - d / (R * 5))) + 0.05f, 14);
        shake(center, 4.2f, R + 34f);
        flashGlow(core, R * 2.4f, R * 3.6f, 6, C_WHITE, 1f);
        flashGlow(core, R * 3.6f, R * 5f, 14, C_CYAN, 0.65f);
        // 마지막 폭발도 같은 반지름 R (가장 밝은 고리가 경계에서 멈춤)
        FxManager.addRing(center.add(0, 0.1, 0), UP, R * 0.3f, R * RK, 9, 0, 1f, I);
        FxManager.addRing(core, UP, R * 0.3f, R * 1.1f, 9, 1, 0.7f, I);
        FxManager.addRing(center.add(0, R * 1.1, 0), UP, R * 0.2f, R * 0.9f, 8, 2, 0.5f, I);
        for (int i = 0, m = count(200); i < m; i++) {
            Vec3 dd = randomDir().add(0, 0.55, 0).normalize();
            Vec3 at = center.add(0, rand(0.2f, R * 1.5f), 0).add(dd.scale(rand(0f, R * 0.4f)));
            shardSprite(at, dd.scale(rand(0.3f, 1.1f) * (0.6f + R / 16f)), rand(0.3f, 0.65f), 22 + RNG.nextInt(18), 0.035f);
        }
        for (int i = 0, m = count(90); i < m; i++) spark(core, randomDir().scale(rand(0.8f, 2.0f)), 8 + RNG.nextInt(8));
        // 반짝이는 결정 가루 폭풍: 천천히 떠올랐다 가라앉음
        for (int i = 0, m = count(240); i < m; i++) {
            Vec3 dd = randomDir();
            Vec3 at = center.add(dd.x * R * RNG.nextFloat(), rand(0.2f, R * 1.6f), dd.z * R * RNG.nextFloat());
            Dust.glint(at.x, at.y, at.z, (float) dd.x * rand(0.05f, 0.25f), rand(0.02f, 0.12f), (float) dd.z * rand(0.05f, 0.25f),
                    rand(0.35f, 0.75f), 30 + RNG.nextInt(40), 0.004f);
        }
        for (int i = 0, m = count(20); i < m; i++) {
            Vec3 dd = randomDir();
            frost(core.add(dd.scale(R * rand(0.1f, 0.6f))), dd.scale(0.2), 2.2f, 4.8f, 12 + RNG.nextInt(6), 0.9f);
        }
        mistBurst(core, R * 0.7f, 18, 0.45f);
        mistRing(center, R * 0.5f, 0.3f, 24, 2.2f, 0.4f);
        DebrisFx.burst(center, R, 30, 0.9f);
        if (g == null) {
            // 빙산을 본 적 없는 사람에게도 조각이 보이게
            for (int i = 0; i < 30; i++) {
                Vec3 dd = randomDir().add(0, 0.6, 0).normalize();
                float fl = rand(0.5f, 1.4f);
                addFrag(core.x, core.y, core.z, dd.x * rand(0.2f, 0.7f), dd.y * rand(0.3f, 0.8f), dd.z * rand(0.2f, 0.7f), fl * 0.3f, fl,
                        24 + RNG.nextInt(16), center.y);
            }
        }
    }

    // =====================================================================
    // 상태: 얼림 (FREEZE)
    // =====================================================================

    private static final class FreezeFx extends Fx {
        final Entity entity;
        final float w, h;
        final List<Cr> crs = new ArrayList<>();
        int end;
        boolean broken;
        Vec3 last;

        FreezeFx(Entity entity, float width, float height, int duration) {
            this.entity = entity;
            this.w = Math.max(0.3f, Math.min(width > 0 ? width : entity.getBbWidth(), 6f));
            this.h = Math.max(0.3f, Math.min(height > 0 ? height : entity.getBbHeight(), 8f));
            this.end = duration;
            this.last = entity.position();
            float rad = Math.min(3.5f, w * 0.78f + 0.14f);
            float len = Math.min(9f, h * 1.32f + 0.2f);
            // 몸을 감싸는 큰 결정 (살짝 기울어짐)
            Vec3 tiltA = new Vec3(rand(-0.06f, 0.06f), 1, rand(-0.06f, 0.06f));
            Cr main = new Cr(0, -0.05, 0, tiltA, RNG.nextFloat() * TAU, rad, len, RNG.nextInt(10));
            main.grow = 3;
            crs.add(main);
            // 발밑 둘레로 바깥을 향해 돋은 결정
            int n = 5 + RNG.nextInt(2);
            float sz = (float) Math.sqrt(Math.min(h, 3f));
            for (int i = 0; i < n; i++) {
                float ang = (i + rand(-0.3f, 0.3f)) * TAU / n;
                Vec3 o = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                Vec3 a = tilted(o, rand(0.55f, 1.1f), Vec3.ZERO);
                float l = rand(0.4f, 0.75f) * sz;
                Cr c = new Cr(o.x * rad * 0.75, rand(-0.05f, 0.15f) * h, o.z * rad * 0.75, a, RNG.nextFloat() * TAU, l * rand(0.2f, 0.28f), l, RNG.nextInt(10));
                c.born = RNG.nextInt(2);
                c.grow = 2;
                crs.add(c);
            }
            // 어깨쯤 몇 개
            for (int i = 0; i < 2; i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 o = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                Vec3 a = tilted(o, rand(0.8f, 1.2f), Vec3.ZERO);
                float l = rand(0.3f, 0.5f) * sz;
                Cr c = new Cr(o.x * rad * 0.7, h * rand(0.5f, 0.75f), o.z * rad * 0.7, a, RNG.nextFloat() * TAU, l * 0.25f, l, RNG.nextInt(10));
                c.born = 1 + RNG.nextInt(2);
                c.grow = 2;
                crs.add(c);
            }
            Vec3 p = entity.position();
            // 얼어붙은 본인(1인칭)은 몸 둘레 섬광·서리를 빼고 발밑만 (시야를 가리지 않음, 화면 서리는 바닐라 동상 표시)
            boolean self = IceThunderAim.firstPersonSelf(entity);
            if (!self) {
                flashGlow(p.add(0, h * 0.5, 0), Math.max(2f, h * 1.6f), Math.max(3f, h * 2.4f), 5, C_PALE, 0.7f);
                for (int i = 0, m = count(6); i < m; i++) frost(p.add(rand(-rad, rad), rand(0, h), rand(-rad, rad)), Vec3.ZERO, 0.9f, 1.6f, 7, 0.8f);
                glintBurst(p.add(0, h * 0.5, 0), Math.max(w, h) * 0.6f, 0.04f, 8, 0.32f, 24);
            }
            FxManager.addRing(p.add(0, 0.06, 0), UP, 0.2f, rad * 3.2f + 0.8f, 7, 0, 0.8f, I);
            if (entity.onGround()) frostDecal(p, UP, rad * 2.2f + 0.6f, duration + 20, 0.75f, 3f);
        }

        /** 이 결정이 1인칭 본인의 눈높이를 가리는지 (몸을 감싼 큰 결정·어깨 결정) */
        boolean blocksView(int i) {
            return i == 0 || i >= crs.size() - 2;
        }

        boolean alive() {
            if (entity.isRemoved()) return false;
            Minecraft mc = Minecraft.getInstance();
            if (entity.level() != mc.level) return false;
            return !(entity instanceof LivingEntity le) || le.isAlive();
        }

        @Override
        Vec3 anchor() {
            return last;
        }

        @Override
        boolean tick() {
            age++;
            if (broken) return true;
            boolean alive = alive();
            if (alive) last = entity.position();
            FxManager.lod = FxManager.lodAt(last);
            if (!alive || age >= end) {
                shatter();
                return true;
            }
            // 발밑 서리 안개 · 몸 둘레 반짝이 · 금 가기 시작하면 떨어지는 조각
            if (age % 5 == 0 && RNG.nextFloat() < FxManager.amount() * FxManager.lod && !IceThunderAim.firstPersonSelf(entity)) {
                mist(last.add(rand(-w, w), 0.2, rand(-w, w)), new Vec3(0, 0.005, 0), 0.8f + w, 1.8f + w * 1.5f, 30, 0.25f);
            }
            // 본인 1인칭이면 허리 아래에만 (눈앞에 반짝이가 끼지 않게)
            float hy = IceThunderAim.firstPersonSelf(entity) ? h * 0.45f : h * 1.2f;
            if (age % 2 == 0) {
                for (int i = 0, m = count(1); i < m; i++) {
                    Dust.glint(last.x + rand(-w, w) * 0.8, last.y + RNG.nextFloat() * hy, last.z + rand(-w, w) * 0.8, 0, 0.005f, 0,
                            rand(0.2f, 0.4f), 14 + RNG.nextInt(10), 0.0003f);
                }
            }
            if (end - age < 8 && RNG.nextFloat() < 0.6f) {
                shardSprite(last.add(rand(-w, w) * 0.6, RNG.nextFloat() * hy * 0.8, rand(-w, w) * 0.6), new Vec3(rand(-0.04f, 0.04f), 0.04, rand(-0.04f, 0.04f)),
                        rand(0.14f, 0.22f), 12, 0.03f);
            }
            return false;
        }

        void shatter() {
            if (broken) return;
            broken = true;
            Vec3 p = last;
            FxManager.lod = FxManager.lodAt(p);
            boolean self = IceThunderAim.firstPersonSelf(entity);
            for (int i = 0; i < crs.size(); i++) {
                crs.get(i).shatter(p.x, p.y, p.z, i == 0 ? 5 + (int) Math.min(6, h * 2) : 1, 1.1f, p.y, i == 0 && !self);
            }
            Vec3 core = p.add(0, h * 0.5, 0);
            if (!self) flashGlow(core, Math.max(2.5f, h * 1.6f), Math.max(3.5f, h * 2.6f), 5, C_WHITE, 0.9f);
            FxManager.addRing(p.add(0, 0.08, 0), UP, 0.3f, w * 3f + 1.5f, 9, 0, 0.8f, I);
            shardBurst(core, UP, 0.45f, 10 + (int) (h * 6), 0.26f);
            for (int i = 0, m = count(8); i < m; i++) spark(core, randomDir().scale(rand(0.3f, 0.7f)), 6 + RNG.nextInt(5));
            glintBurst(core, Math.max(w, h) * 0.5f, 0.1f, self ? 6 : 14, 0.4f, self ? 14 : 30);
            if (!self) mistBurst(core, Math.max(0.5f, w * 0.6f), 4, 0.35f);
            FxManager.playAt(p, SkillsRegistry.snd("ice.shatter"), 0.7f, 1.2f + RNG.nextFloat() * 0.2f);
        }

        private Vec3 pos(float pt) {
            return alive() ? entity.getPosition(pt) : last;
        }

        @Override
        void body(BufferBuilder bb, float pt, float time) {
            if (broken) return;
            float t = age + pt;
            Vec3 p = pos(pt);
            boolean self = IceThunderAim.firstPersonSelf(entity);
            for (int i = 0; i < crs.size(); i++) {
                if (self && blocksView(i)) continue; // 본인 1인칭: 눈앞을 덮는 결정은 그리지 않음
                Cr c = crs.get(i);
                c.draw(bb, p.x, p.y, p.z, c.scale(t), i == 0 ? 1.1f : (self ? 0.6f : 1f), false, time);
            }
        }

        @Override
        void glow(BufferBuilder bb, float pt, float time) {
            if (broken) return;
            float t = age + pt;
            Vec3 p = pos(pt);
            boolean self = IceThunderAim.firstPersonSelf(entity);
            float form = t < 3f ? 1.2f * (1f - t / 3f) : 0f;
            float left = end - t;
            float crack = left < 8f ? (1f - left / 8f) * 0.9f * (float) Math.abs(Math.sin(t * 3.3f)) : 0f;
            for (int i = 0; i < crs.size(); i++) {
                if (self && blocksView(i)) continue;
                Cr c = crs.get(i);
                c.draw(bb, p.x, p.y, p.z, c.scale(t), (1f + form + crack) * (self ? 0.6f : 1f), true, time);
            }
            if (self) return;
            Cr m = crs.get(0);
            float tw = (float) Math.abs(Math.sin(time * 0.4f + entity.getId()));
            tw = tw * tw * tw;
            if (tw > 0.15f) Glints.add(p.x + m.x + m.ax * m.len, p.y + m.y + m.ay * m.len, p.z + m.z + m.az * m.len, 1.1f * tw, 0f, 1f, 1f, 1f, tw);
        }
    }

    // =====================================================================
    // 준비 동작
    // =====================================================================

    static void tickCharge(FxManager.ChargeFx c, float k) {
        Entity e = c.caster;
        switch (c.skill) {
            case ICE_SPEAR -> {
                // 서버가 창을 만드는 바로 그 자리(S)로 냉기가 모이고, 마지막 틱에 S 에서 조준 방향으로 터져 나감
                IceThunderAim.update(c, 0.3);
                Vec3 S = IceThunderAim.hand(c, 1f, 0.3), d = IceThunderAim.shotDir(c);
                Vec3 grip = S.subtract(d.scale(0.35));
                FxManager.lod = FxManager.lodAt(S);
                gather(grip, 3 + (int) (2 * k), 1.8f - 0.6f * k);
                if (c.age % 3 == 0) mist(grip, d.scale(0.02), 0.3f, 0.9f, 10, 0.25f);
                if (IceThunderAim.releasing(c)) spearRelease(S, d, e);
            }
            case ICE_SHARDS -> {
                IceThunderAim.update(c, 0.0);
                Vec3 S = IceThunderAim.hand(c, 1f, 0.0), D = IceThunderAim.shotDir(c);
                FxManager.lod = FxManager.lodAt(S);
                float half = shardHalf();
                // 부채꼴의 일곱 갈래 방향에서 손으로 빨려 드는 반짝이 (나갈 방향이 미리 읽힘)
                for (int i = 0, m = count(4); i < m; i++) {
                    Vec3 dd = D.yRot((float) (-half + 2 * half * RNG.nextInt(7) / 6.0));
                    Vec3 at = S.add(dd.scale(rand(1.4f, 2.4f))).add(randomDir().scale(0.25));
                    Vec3 v = S.subtract(at).scale(1.0 / 5.0);
                    Dust.glint(at.x, at.y, at.z, (float) v.x, (float) v.y, (float) v.z, rand(0.2f, 0.32f), 5, 0f);
                }
                if (IceThunderAim.releasing(c)) shardsRelease(S, D, half, e);
            }
            case ICE_SPIKES -> {
                Vec3 feet = e.position();
                Vec3 f = c.flatDir();
                FxManager.lod = FxManager.lodAt(feet);
                for (int i = 0, m = count(2 + (int) (3 * k)); i < m; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = rand(0.3f, 1.3f);
                    Dust.glint(feet.x + Math.cos(ang) * rr, feet.y + 0.1, feet.z + Math.sin(ang) * rr, 0, rand(0.02f, 0.06f), 0, rand(0.2f, 0.35f), 10, 0f);
                }
                if (c.age % 2 == 0) {
                    for (int i = 0, m = count(1); i < m; i++) {
                        frost(feet.add(f.scale(rand(0.5f, 0.6f + 2.4f * k))).add(0, 0.2, 0), new Vec3(0, 0.03, 0), 0.6f, 1.2f, 6, 0.75f);
                    }
                }
                if (c.age % 3 == 0) mist(feet.add(0, 0.2, 0), f.scale(0.05), 0.8f, 1.8f, 14, 0.25f);
            }
            case FROST_NOVA -> {
                Vec3 feet = e.position();
                float R = ClientSkillStats.radius(Skill.FROST_NOVA);
                FxManager.lod = FxManager.lodAt(feet);
                // 바깥 경계에서 소용돌이치며 몸으로 빨려 드는 냉기
                for (int i = 0, m = count(4 + (int) (4 * k)); i < m; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = rand(0.6f, 1f) * Math.max(2f, R * 0.8f);
                    double x = feet.x + Math.cos(ang) * rr, y = feet.y + rand(0.1f, 1.8f), z = feet.z + Math.sin(ang) * rr;
                    float tx = (float) -Math.sin(ang), tz = (float) Math.cos(ang);
                    float vx = (float) (feet.x - x) / 6f + tx * 0.12f, vz = (float) (feet.z - z) / 6f + tz * 0.12f;
                    Dust.glint(x, y, z, vx, (float) (feet.y + 1 - y) / 8f, vz, rand(0.22f, 0.36f), 6, 0f);
                }
                if (c.age % 2 == 0) {
                    float ang = RNG.nextFloat() * TAU;
                    mist(feet.add(Math.cos(ang) * 1.5, 0.2, Math.sin(ang) * 1.5), new Vec3(-Math.cos(ang) * 0.05, 0.01, -Math.sin(ang) * 0.05), 1.0f, 0.5f, 12, 0.3f);
                }
            }
            case ICE_AGE -> {
                Berg b = BERGS.computeIfAbsent(c, cc -> new Berg(cc.target, Math.min(24f, cc.size)));
                b.seen = clock;
                Vec3 t = c.target;
                float R = Math.min(24f, c.size);
                FxManager.lod = FxManager.lodAt(t);
                // 일대에 흩날리는 눈 + 마법진 위 반짝이
                for (int i = 0, m = count(2 + (int) (5 * k)); i < m; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = R * (float) Math.sqrt(RNG.nextFloat());
                    Dust.flake(t.x + Math.cos(ang) * rr, t.y + rand(5f, 12f), t.z + Math.sin(ang) * rr,
                            rand(-0.04f, 0.04f), rand(-0.12f, -0.06f), rand(-0.04f, 0.04f), rand(0.14f, 0.26f), 50 + RNG.nextInt(20));
                }
                for (int i = 0, m = count(2 + (int) (3 * k)); i < m; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = R * rand(0.2f, 1f);
                    Dust.glint(t.x + Math.cos(ang) * rr, t.y + 0.15, t.z + Math.sin(ang) * rr, 0, rand(0.03f, 0.1f), 0, rand(0.25f, 0.45f), 14, 0f);
                }
                // 떨어지는 빙산의 꼬리: 서리 불꽃 · 안개 · 떨어져 나가는 조각
                float hgt = fallHeight(k, R);
                if (hgt > 0.5f) {
                    Vec3 bottom = t.add(0, hgt - R * 0.3, 0);
                    for (int i = 0, m = count(3); i < m; i++) {
                        frost(bottom.add(randomDir().scale(R * 0.35)), new Vec3(0, 0.35 + 0.6 * k, 0), 2.0f, 3.6f, 7 + RNG.nextInt(3), 0.85f);
                    }
                    for (int i = 0, m = count(2); i < m; i++) {
                        mist(t.add(rand(-R, R) * 0.4, hgt + rand(0, R * 1.2f), rand(-R, R) * 0.4), new Vec3(0, 0.3 + 0.5 * k, 0), 2.2f, 4.5f, 14, 0.3f);
                    }
                    for (int i = 0, m = count(3); i < m; i++) {
                        Vec3 at = t.add(rand(-R, R) * 0.4, hgt + rand(0, R * 1.4f), rand(-R, R) * 0.4);
                        Dust.glint(at.x, at.y, at.z, rand(-0.05f, 0.05f), 0.2f, rand(-0.05f, 0.05f), rand(0.35f, 0.6f), 10, 0f);
                    }
                }
                // 충돌 직전: 공기가 눌려 바닥 안개가 바깥으로 밀려남
                if (c.age == c.duration - 3) mistRing(t, R * 0.5f, 0.25f, 18, 1.8f, 0.35f);
                gather(IceThunderAim.hand(c, 1f, 0.3), 2, 1.5f);
            }
            default -> { }
        }
    }

    private static float shardHalf() {
        return (float) Math.toRadians(Math.min(80.0, Math.max(1.0, ClientSkillStats.radius(Skill.ICE_SHARDS))));
    }

    /** 창이 손에서 나가는 순간: 창끝 쪽으로 터지는 서리 + 조준 방향 음속 고리 2개 */
    private static void spearRelease(Vec3 S, Vec3 d, Entity caster) {
        flashGlow(S, 1.2f, 2.6f, 3, C_WHITE, 0.9f);
        flashGlow(S, 2.2f, 3.2f, 5, C_CYAN, 0.45f);
        FxManager.addRing(S.add(d.scale(0.5)), d, 0.2f, 1.5f, 5, 0, 0.9f, I);
        FxManager.addRing(S.add(d.scale(1.3)), d, 0.15f, 0.95f, 4, 1, 0.6f, I);
        for (int i = 0, m = count(8); i < m; i++) {
            Vec3 at = S.add(d.scale(rand(0f, 1.4f))).add(randomDir().scale(0.15));
            Vec3 v = d.scale(rand(0.15f, 0.4f)).add(randomDir().scale(0.04));
            Dust.glint(at.x, at.y, at.z, (float) v.x, (float) v.y, (float) v.z, rand(0.22f, 0.36f), 6 + RNG.nextInt(5), 0f);
        }
        for (int i = 0, m = count(4); i < m; i++) {
            Vec3 v = d.scale(rand(0.1f, 0.3f)).add(randomDir().scale(0.12));
            shardSprite(S, v, rand(0.14f, 0.22f), 7 + RNG.nextInt(4), 0.02f);
        }
        mist(S.subtract(d.scale(0.3)), d.scale(0.04), 0.4f, 1.3f, 9, 0.28f);
        if (IceThunderAim.firstPersonSelf(caster)) CameraShake.kick(-0.5f, 0f);
    }

    /** 파편이 손에서 부채꼴로 나가는 순간: 일곱 갈래로 튀는 서리 줄기 */
    private static void shardsRelease(Vec3 S, Vec3 D, float half, Entity caster) {
        flashGlow(S, 1.0f, 2.2f, 3, C_WHITE, 0.85f);
        FxManager.addRing(S.add(D.scale(0.4)), D, 0.2f, 1.6f, 5, 0, 0.8f, I);
        for (int i = 0; i < 7; i++) {
            Vec3 dd = D.yRot((float) (-half + 2 * half * i / 6.0));
            spark(S.add(dd.scale(0.2)), dd.scale(rand(0.7f, 1.0f)), 4);
        }
        if (IceThunderAim.firstPersonSelf(caster)) CameraShake.kick(-0.35f, 0f);
    }

    /** 손으로 빨려 드는 반짝이 · 얼음 조각 */
    private static void gather(Vec3 hand, int n, float dist) {
        for (int i = 0, m = count(n); i < m; i++) {
            Vec3 off = randomDir().scale(rand(dist * 0.7f, dist));
            Vec3 v = off.scale(-1.0 / 5.0);
            Dust.glint(hand.x + off.x, hand.y + off.y, hand.z + off.z, (float) v.x, (float) v.y, (float) v.z, rand(0.2f, 0.34f), 5, 0f);
        }
        if (RNG.nextFloat() < 0.5f * FxManager.amount() * FxManager.lod) {
            Vec3 off = randomDir().scale(dist);
            shardSprite(hand.add(off), off.scale(-1.0 / 5.0), 0.2f, 5, 0f);
        }
    }

    static void renderCharge(FxManager.ChargeFx c, float pt, float k, float time) {
        Entity e = c.caster;
        float fp = fpScale(e);
        switch (c.skill) {
            case ICE_SPEAR -> {
                // 창끝 = 서버가 창을 만드는 자리 + 0.7 (날아가는 창과 같은 자리·같은 길이에서 이어짐)
                Vec3 S = IceThunderAim.hand(c, pt, 0.3), d = IceThunderAim.shotDir(c);
                float s = easeOutCubic(Math.min(1f, k * 1.25f));
                float len = 0.35f + 0.8f * s, rad = 0.3f * (fp < 1f ? 0.8f : 1f) * (0.45f + 0.55f * s);
                Vec3 tip = S.add(d.scale(0.7));
                Vec3 base = tip.subtract(d.scale(len));
                FxRender.alphaBlend();
                BufferBuilder bb = beginBody();
                crystalAlong(bb, LANCE, base, d, time * 0.22f, rad, len, s, false, time);
                FxRender.draw(bb);
                FxRender.additive();
                bb = beginGlow(1.1f);
                crystalAlong(bb, LANCE, base, d, time * 0.22f, rad, len, 0.6f + 0.8f * s, true, time);
                FxRender.draw(bb);
                // 손 주위 서리 고리: 모으는 동안 비스듬히 돌다가 마지막 40% 에 창 허리로 조여 듦 (곧 나감)
                Vec3 grip = S.subtract(d.scale(0.3));
                float tight = smooth((k - 0.6f) / 0.4f);
                Vec3[] b = FxRender.basis(d);
                double ph = time * 0.35;
                Vec3 tilt = d.scale(Math.cos(1.0)).add(b[0].scale(Math.sin(1.0) * Math.cos(ph))).add(b[1].scale(Math.sin(1.0) * Math.sin(ph))).normalize();
                float r1 = (0.75f - 0.35f * tight) * fp, r0 = (0.45f - 0.15f * tight) * fp;
                FxRender.elemShader(I, 1.2f, 0.1f, 1.0f + 0.4f * tight);
                bb = FxRender.quads();
                FxRender.ring(bb, grip, d, r0, r0 + 0.12f * fp, 0.7f + 0.3f * k, time * 0.5f, 36);
                FxRender.ring(bb, grip, tight > 0.5f ? d : tilt, r1, r1 + 0.08f * fp, 0.45f + 0.5f * k, -time * 0.6f, 40);
                FxRender.draw(bb);
                Glints.add(tip.x, tip.y, tip.z, (0.5f + 0.7f * s) * fp, time * 0.04f, 1f, 1f, 1f, 0.5f + 0.5f * k);
                frameGlow(grip, (0.8f + 1.8f * k) * fp, C_CYAN, 0.35f + 0.35f * k);
            }
            case ICE_SHARDS -> {
                Vec3 S = IceThunderAim.hand(c, pt, 0.0), D = IceThunderAim.shotDir(c);
                float half = shardHalf();
                FxRender.alphaBlend();
                BufferBuilder bb = beginBody();
                for (int i = 0; i < 7; i++) shardInFan(bb, S, D, half, i, k, fp, false, time);
                FxRender.draw(bb);
                FxRender.additive();
                bb = beginGlow(1.15f);
                for (int i = 0; i < 7; i++) shardInFan(bb, S, D, half, i, k, fp, true, time);
                FxRender.draw(bb);
                frameGlow(S, (0.7f + 1.4f * k) * fp, C_CYAN, 0.3f + 0.3f * k);
            }
            case ICE_SPIKES -> {
                Vec3 feet = e.getPosition(pt);
                Vec3 f = c.flatDir();
                Vec3 sd = IceThunderAim.flatRight(f);
                // 발 앞에 돋기 시작하는 작은 가시 3개 (서버 가시가 시작하는 바로 그 자리·방향)
                FxRender.alphaBlend();
                BufferBuilder bb = beginBody();
                for (int i = 0; i < 3; i++) spikeHint(bb, feet, f, sd, i, k, false, time);
                FxRender.draw(bb);
                FxRender.additive();
                bb = beginGlow(1.1f);
                for (int i = 0; i < 3; i++) spikeHint(bb, feet, f, sd, i, k, true, time);
                FxRender.draw(bb);
                // 바닥에 그어지는 길 (폭 = 실제 판정 폭) — 다른 사람도 어디로 올지 읽힘
                float W = Math.max(0.5f, ClientSkillStats.radius(Skill.ICE_SPIKES));
                float L = Math.min(ClientSkillStats.range(Skill.ICE_SPIKES), 7f) * easeOutCubic(Math.min(1f, k * 1.3f));
                lane(feet.add(0, 0.06, 0), f, W, L, 0.3f + 0.5f * k);
                FxRender.elemShader(I, 1.0f, 0.2f, 0.9f);
                bb = FxRender.quads();
                FxRender.ring(bb, feet.add(0, 0.06, 0), UP, 0.5f + 0.5f * k, 0.75f + 0.7f * k, 0.5f + 0.5f * k, time * 0.4f, 40);
                FxRender.draw(bb);
                frameGlow(IceThunderAim.hand(c, pt, 0.3), (0.6f + 1.2f * k) * fp, C_CYAN, 0.3f + 0.3f * k);
            }
            case FROST_NOVA -> {
                Vec3 feet = e.getPosition(pt);
                float R = Math.max(0.5f, ClientSkillStats.radius(Skill.FROST_NOVA));
                runeCircle(feet, 1.2f + 1.6f * easeOutCubic(k), k, time, 0.85f);
                // 실제 얼림 범위 경계 + 안에서 채워지는 앞머리 (다 차면 터짐)
                edgeRing(feet, R, 0.35f + 0.55f * k, time);
                fillFront(feet, R, k, 0.6f, time);
                float orbit = (1.7f - 0.6f * k);
                float spin = time * (0.18f + 0.3f * k);
                FxRender.alphaBlend();
                BufferBuilder bb = beginBody();
                for (int i = 0; i < 6; i++) orbitShard(bb, feet, orbit, spin, i, k, false, time);
                FxRender.draw(bb);
                FxRender.additive();
                bb = beginGlow(1.15f);
                for (int i = 0; i < 6; i++) orbitShard(bb, feet, orbit, spin, i, k, true, time);
                FxRender.draw(bb);
                frameGlow(feet.add(0, 1.0, 0), 1.5f + 2.5f * k, C_CYAN, 0.25f + 0.35f * k);
            }
            case ICE_AGE -> {
                Vec3 t = c.target;
                float R = Math.min(24f, c.size);
                // 떨어질 자리: 경계(=실제 범위)는 처음부터 또렷하게, 안쪽 마법진은 빙산이 닿는 순간 경계에 닿도록 커짐
                float urgent = smooth((k - 0.7f) / 0.3f);
                edgeRing(t, R, (0.6f + 0.4f * k) * (1f - 0.35f * urgent * (0.5f + 0.5f * (float) Math.sin(time * 1.6f))), time);
                runeCircle(t, R * (0.3f + 0.7f * easeOutCubic(k)), k, time, 1f);
                fillFront(t, R, k, 0.7f, time);
                Berg b = BERGS.get(c);
                if (b != null) {
                    float hgt = fallHeight(k, R);
                    float vis = smooth(k / 0.15f);
                    FxRender.alphaBlend();
                    BufferBuilder bb = beginBody();
                    for (Cr cr : b.crs) cr.draw(bb, t.x, t.y + hgt, t.z, 1f, 1.15f * vis, false, time);
                    FxRender.draw(bb);
                    FxRender.additive();
                    bb = beginGlow(1.1f);
                    for (Cr cr : b.crs) cr.draw(bb, t.x, t.y + hgt, t.z, 1f, (1f + 0.4f * k) * vis, true, time);
                    FxRender.draw(bb);
                    // 빙산 아래 차가운 빛 (떨어질수록 땅을 밝힘)
                    frameGlow(t.add(0, hgt - R * 0.2, 0), R * 2.4f, C_CYAN, 0.35f * vis);
                    frameGlow(t.add(0, 0.3, 0), R * (1.2f + 0.8f * k), C_AZURE, 0.12f + 0.3f * k * k);
                    Cr top = b.crs.get(0);
                    Glints.add(t.x + top.x + top.ax * top.len, t.y + hgt + top.y + top.ay * top.len, t.z + top.z + top.az * top.len,
                            4f * vis, time * 0.02f, 1f, 1f, 1f, vis * (0.6f + 0.4f * (float) Math.sin(time * 0.8f)));
                }
                frameGlow(IceThunderAim.hand(c, pt, 0.3), (0.8f + 1.6f * k) * fp, C_CYAN, 0.3f + 0.4f * k);
            }
            default -> { }
        }
    }

    /** 서버 fan 과 같은 각도의 i 번째 파편 (S = 서버 생성 자리, D = 서버 발사 방향) */
    private static void shardInFan(BufferBuilder bb, Vec3 S, Vec3 D, float half, int i, float k, float fp, boolean add, float time) {
        float a = -half + 2f * half * i / 6f;
        Vec3 d = D.yRot(a);
        float si = clamp01(k * 1.7f - Math.abs(i - 3) * 0.13f);
        if (si <= 0.01f) return;
        float s = easeOutBack(si);
        Vec3 at = S.add(d.scale(0.05f + 0.2f * si));
        float sc = fp < 1f ? 0.85f : 1f;
        float len = 0.48f * s * sc;
        crystalAlong(bb, BIPYR, at.subtract(d.scale(len * 0.4)), d, time * 0.5f + i, 0.12f * sc, len, add ? 0.7f + 0.6f * si : si, add, time);
        if (add) {
            Vec3 tip = at.add(d.scale(len * 0.6));
            Glints.add(tip.x, tip.y, tip.z, 0.35f * fp * s, 0f, 1f, 1f, 1f, 0.5f * si);
        }
    }

    private static void spikeHint(BufferBuilder bb, Vec3 feet, Vec3 f, Vec3 sd, int i, float k, boolean add, float time) {
        float si = clamp01(k * 1.5f - i * 0.2f);
        if (si <= 0.01f) return;
        double lat = (i - 1) * 0.45;
        Vec3 at = feet.add(f.scale(0.9 + 0.55 * i)).add(sd.scale(lat)).subtract(0, 0.1, 0);
        Vec3 axis = tilted(sd.scale(lat >= 0 ? 1 : -1), 0.15 + Math.abs(lat) * 0.6, f.scale(0.25));
        float len = (0.25f + 0.45f * si) * (1f + 0.25f * i);
        crystalAlong(bb, i, at, axis, i * 1.3f, len * 0.22f, len, add ? 0.8f + 0.5f * si : si, add, time);
    }

    private static void orbitShard(BufferBuilder bb, Vec3 feet, float orbit, float spin, int i, float k, boolean add, float time) {
        double a = spin + i * TAU / 6;
        Vec3 out = new Vec3(Math.cos(a), 0, Math.sin(a));
        Vec3 tan = new Vec3(-Math.sin(a), 0.25 * Math.sin(time * 0.2 + i), Math.cos(a));
        Vec3 at = feet.add(out.scale(orbit)).add(0, 0.7 + 0.35 * Math.sin(time * 0.15 + i * 1.1), 0);
        float s = easeOutBack(clamp01(k * 2f - i * 0.08f));
        float len = 0.55f * s;
        crystalAlong(bb, BIPYR, at.subtract(tan.normalize().scale(len * 0.3)), tan, time * 0.3f + i, 0.1f, len, add ? 0.8f + 0.6f * k : 0.6f + 0.4f * k, add, time);
    }

    // =====================================================================
    // 범위 표시 (판정 범위와 정확히 같은 크기)
    // =====================================================================

    private static int edgeSegs(float r) {
        return Math.max(32, Math.min(128, (int) (r * 10f)));
    }

    /**
     * 피해 범위의 경계: 정확히 반지름 R 의 가는 밝은 고리 + 안쪽 옅은 띠.
     * 같은 고리를 반 바퀴 어긋나게 두 번 그려 둘레 밝기가 고르게 (끊겨 보이지 않게) 한다.
     */
    private static void edgeRing(Vec3 c, float R, float a, float time) {
        if (a <= 0.01f || R <= 0.2f) return;
        Vec3 g = c.add(0, 0.07, 0);
        int seg = edgeSegs(R);
        FxRender.elemShader(I, 1.0f, 0.04f, 1.25f * a);
        BufferBuilder bb = FxRender.quads();
        FxRender.ring(bb, g, UP, R - 0.24f, R + 0.02f, 0.55f, time * 0.25f, seg);
        FxRender.ring(bb, g, UP, R - 0.24f, R + 0.02f, 0.55f, time * 0.25f + (float) Math.PI, seg);
        FxRender.ring(bb, g.add(0, 0.004, 0), UP, R * 0.88f, R - 0.2f, 0.22f, -time * 0.15f, seg);
        FxRender.draw(bb);
    }

    /** 범위 안에서 바깥으로 채워지는 앞머리 (k = 1 에 경계 R 에 닿음 = 그 순간 발동) */
    private static void fillFront(Vec3 c, float R, float k, float a, float time) {
        float r = R * clamp01(k);
        if (r < 0.3f || a <= 0.01f) return;
        Vec3 g = c.add(0, 0.075, 0);
        FxRender.elemShader(I, 1.4f, 0.1f, a);
        BufferBuilder bb = FxRender.quads();
        FxRender.ring(bb, g, UP, Math.max(0f, r - 0.7f), r, 0.45f, time * 0.4f, edgeSegs(r));
        FxRender.ring(bb, g, UP, Math.max(0f, r - 0.7f), r, 0.45f, time * 0.4f + (float) Math.PI, edgeSegs(r));
        FxRender.draw(bb);
        FxRender.spriteShader(FxRender.GLOW_WHITE);
        bb = FxRender.quads();
        float ga = 0.16f * a;
        FxRender.planeQuad(bb, g.add(0, 0.01, 0), UP, r * 1.2f, 0f, ga * C_CYAN[0], ga * C_CYAN[1], ga * C_CYAN[2], ga);
        FxRender.draw(bb);
    }

    /** 바닥에 그어지는 직선 길 (양쪽 경계선 + 가운데 서리 줄), 끝으로 갈수록 흐려짐 */
    private static void lane(Vec3 o, Vec3 f, float W, float L, float a) {
        if (L < 0.3f || a <= 0.01f) return;
        Vec3 sd = IceThunderAim.flatRight(f);
        FxRender.spriteShader(FxRender.BEAM);
        BufferBuilder bb = FxRender.quads();
        Vec3 a0 = o.add(f.scale(0.6)), a1 = o.add(f.scale(0.6 + L));
        flatStrip(bb, a0.add(sd.scale(W)), a1.add(sd.scale(W)), 0.22f, a, 0f, C_CYAN);
        flatStrip(bb, a0.subtract(sd.scale(W)), a1.subtract(sd.scale(W)), 0.22f, a, 0f, C_CYAN);
        flatStrip(bb, a0, a1, W * 1.6f, a * 0.25f, 0f, C_AZURE);
        flatStrip(bb, a0, a1, 0.3f, a * 0.8f, 0f, C_WHITE);
        FxRender.draw(bb);
    }

    /** 땅과 나란한 띠 (BEAM 그림: 가로 = 폭, 가운데 밝음). 색은 알파를 곱해서 넣음 */
    private static void flatStrip(BufferBuilder bb, Vec3 a, Vec3 b, float w, float a0, float a1, float[] col) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4) return;
        double sx = -dz / len * w * 0.5, sz = dx / len * w * 0.5;
        FxRender.vertex(bb, a.x - sx, a.y, a.z - sz, 0f, 0f, col[0] * a0, col[1] * a0, col[2] * a0, a0);
        FxRender.vertex(bb, a.x + sx, a.y, a.z + sz, 1f, 0f, col[0] * a0, col[1] * a0, col[2] * a0, a0);
        FxRender.vertex(bb, b.x + sx, b.y, b.z + sz, 1f, 1f, col[0] * a1, col[1] * a1, col[2] * a1, a1);
        FxRender.vertex(bb, b.x - sx, b.y, b.z - sz, 0f, 1f, col[0] * a1, col[1] * a1, col[2] * a1, a1);
    }
}
