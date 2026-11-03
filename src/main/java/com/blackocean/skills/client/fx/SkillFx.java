package com.blackocean.skills.client.fx;

import com.blackocean.skills.SkillsMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static com.blackocean.skills.client.fx.FxManager.RNG;
import static com.blackocean.skills.client.fx.FxManager.clamp01;
import static com.blackocean.skills.client.fx.FxManager.count;
import static com.blackocean.skills.client.fx.FxManager.easeOutCubic;
import static com.blackocean.skills.client.fx.FxManager.rand;
import static com.blackocean.skills.client.fx.FxManager.randomDir;
import static com.blackocean.skills.client.fx.FxManager.spawn;

/**
 * 3번 불기둥, 4번 화염 고리, 바닥 그을음 자국 연출.
 * <pre>
 *  불기둥 준비 : 바닥에 빛나는 경고 원 + 갈라지는 불길 + 떠오르는 불씨      (renderPillarWarn)
 *  불기둥 분출 : 꼬이며 솟는 2겹 불기둥 + 바닥 불꽃 고리 + 충격파 + 불꽃 소용돌이 (PillarFx)
 *  고리 준비  : 몸 주위를 자이로처럼 도는 불 고리 3개 + 바닥 고리 + 감도는 불꽃  (renderRingCharge)
 *  고리 폭발  : 바닥을 따라 퍼지는 불의 벽 2겹 + 바닥 불길 + 큰 충격파          (RingBurstFx)
 * </pre>
 */
final class SkillFx {
    static final ResourceLocation SCORCH = SkillsMod.id("textures/fx/scorch.png");
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final float TAU = (float) (Math.PI * 2);

    private static final List<PillarFx> PILLARS = new ArrayList<>();
    private static final List<RingBurstFx> BURSTS = new ArrayList<>();
    private static final List<Decal> DECALS = new ArrayList<>();

    private SkillFx() {}

    static boolean isEmpty() {
        return PILLARS.isEmpty() && BURSTS.isEmpty() && DECALS.isEmpty();
    }

    static void clear() {
        PILLARS.clear();
        BURSTS.clear();
        DECALS.clear();
    }

    static void tick() {
        PILLARS.removeIf(PillarFx::tick);
        BURSTS.removeIf(RingBurstFx::tick);
        DECALS.removeIf(d -> ++d.age >= d.life);
    }

    /** 반투명 덮기 단계 (연기와 같이) */
    static void renderAlpha(float pt) {
        if (DECALS.isEmpty()) return;
        FxRender.spriteShader(SCORCH);
        RenderSystem.disableCull();
        BufferBuilder bb = FxRender.quads();
        for (Decal d : DECALS) {
            float t = (d.age + pt) / d.life;
            float a = (t < 0.05f ? t / 0.05f : (t > 0.7f ? 1f - (t - 0.7f) / 0.3f : 1f)) * 0.85f;
            FxRender.planeQuad(bb, d.pos.add(d.normal.scale(0.03 + d.layer * 0.004)), d.normal, d.radius, d.rot, 1f, 1f, 1f, a);
        }
        FxRender.draw(bb);
    }

    /** 더하기 섞기 단계 */
    static void renderAdditive(float pt, float time) {
        for (PillarFx p : PILLARS) p.render(pt, time);
        for (RingBurstFx b : BURSTS) b.render(pt, time);
    }

    // =====================================================================
    // 공통 도형: 꼬이며 일렁이는 불기둥 (속이 빈 원통)
    // =====================================================================

    /**
     * @param r0 아래 반지름  @param r1 위 반지름  @param h 높이
     * @param twist 높이 1블록당 꼬이는 각도 (라디안)  @param wobble 표면 울퉁불퉁함 (0.05~0.1)
     */
    static void column(Vec3 base, float r0, float r1, float h, float heat, float twist, float uvOff, float wobble, float time) {
        if (h <= 0.05f || heat <= 0.001f) return;
        final int seg = 28, rows = 12;
        BufferBuilder bb = FxRender.quads();
        for (int k = 0; k < rows; k++) {
            float y0 = h * k / rows, y1 = h * (k + 1) / rows;
            for (int i = 0; i < seg; i++) {
                float a0 = TAU * i / seg, a1 = TAU * (i + 1) / seg;
                cv(bb, base, a0, y0, h, r0, r1, heat, twist, uvOff, wobble, time, (float) i / seg);
                cv(bb, base, a1, y0, h, r0, r1, heat, twist, uvOff, wobble, time, (float) (i + 1) / seg);
                cv(bb, base, a1, y1, h, r0, r1, heat, twist, uvOff, wobble, time, (float) (i + 1) / seg);
                cv(bb, base, a0, y1, h, r0, r1, heat, twist, uvOff, wobble, time, (float) i / seg);
            }
        }
        FxRender.draw(bb);
    }

    private static void cv(BufferBuilder bb, Vec3 base, float a, float y, float h, float r0, float r1, float heat,
                           float twist, float uvOff, float wobble, float time, float u) {
        float f = y / h;
        float r = (r0 + (r1 - r0) * f) * (1f + wobble * (float) Math.sin(a * 3f + y * 1.7f + time * 0.6f));
        float ang = a + twist * y;
        double cx = Math.cos(ang), cz = Math.sin(ang);
        double x = base.x + cx * r, z = base.z + cz * r, wy = base.y + y;
        double vx = x - FxRender.cam.x, vy = wy - FxRender.cam.y, vz = z - FxRender.cam.z;
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        // 정면(가운데)은 흰빛, 윤곽은 붉게 → 입체적인 원통으로 읽힘
        float facing = len > 1.0E-4 ? (float) (Math.abs(cx * vx + cz * vz) / len) : 1f;
        float top = 1f - f * f * 0.9f;
        float bottom = clamp01(f / 0.04f) * 0.35f + 0.65f;
        float hv = heat * (0.16f + 0.84f * facing) * top * bottom;
        FxRender.vertex(bb, x, wy, z, u * 2f + uvOff, -y * 0.22f, 1f, 1f, 1f, hv);
    }

    // =====================================================================
    // 그을음 자국
    // =====================================================================

    static void addDecal(Vec3 pos, Vec3 normal, float radius, int life) {
        if (DECALS.size() > 40) DECALS.remove(0);
        DECALS.add(new Decal(pos, normal.normalize(), radius, life, DECALS.size() % 8));
    }

    static final class Decal {
        final Vec3 pos, normal;
        final float radius, rot;
        final int life, layer;
        int age;

        Decal(Vec3 pos, Vec3 normal, float radius, int life, int layer) {
            this.pos = pos;
            this.normal = normal;
            this.radius = radius;
            this.life = life;
            this.layer = layer;
            this.rot = RNG.nextFloat() * TAU;
        }
    }

    // =====================================================================
    // 3. 불기둥
    // =====================================================================

    /** 준비 중 경고 원 (ChargeFx 가 매 프레임 부름). k = 0→1 준비 진행도 */
    static void renderPillarWarn(Vec3 base, float radius, float k, float time) {
        float pulse = 0.75f + 0.25f * (float) Math.sin(time * (1.2f + 2.5f * k));
        Vec3 c = base.add(0, 0.04, 0);

        // 바닥에 깔리는 빛 링 + 가운데 번짐
        FxRender.spriteShader(FxRender.SHOCK_RING);
        RenderSystem.disableCull();
        BufferBuilder bb = FxRender.quads();
        float a = (0.35f + 0.65f * k) * pulse;
        FxRender.planeQuad(bb, c, UP, radius * 1.08f, time * 0.05f, a, a * 0.45f, a * 0.12f, a);
        FxRender.planeQuad(bb, c.add(0, 0.01, 0), UP, radius * (0.55f + 0.4f * k), -time * 0.08f, a * 0.6f, a * 0.25f, a * 0.06f, a);
        FxRender.draw(bb);

        FxRender.spriteShader(FxRender.GLOW);
        bb = FxRender.quads();
        float g = 0.25f + 0.55f * k * k;
        FxRender.planeQuad(bb, c.add(0, 0.02, 0), UP, radius * 1.3f, 0f, g, g * 0.4f, g * 0.1f, g);
        FxRender.draw(bb);

        // 바닥에서 타오르기 시작하는 불꽃 고리 (점점 굵어짐)
        FxRender.fireShader(1.6f, 0.35f - 0.3f * k, 0.8f + 0.4f * k);
        bb = FxRender.quads();
        FxRender.ring(bb, c.add(0, 0.03, 0), UP, radius * (0.9f - 0.15f * k), radius * 1.05f, 0.5f + 0.5f * k, time * 0.3f, 56);
        FxRender.draw(bb);

        // 끝 무렵 가운데에서 짧게 솟는 작은 불길 (곧 터진다는 신호)
        if (k > 0.55f) {
            float kk = (k - 0.55f) / 0.45f;
            FxRender.fireShader(2.2f, 0.4f, 0.9f);
            column(base, radius * 0.35f * kk, radius * 0.1f, 1.6f * kk, 0.8f, 0.6f, 0f, 0.1f, time);
        }
    }

    /** 경고 원 위로 떠오르는 불씨·가장자리 불꽃 (ChargeFx 의 매 틱) */
    static void tickPillarWarn(Vec3 base, float radius, float k) {
        for (int i = 0; i < count(3 + (int) (6 * k)); i++) {
            float ang = RNG.nextFloat() * TAU;
            float rr = radius * (float) Math.sqrt(RNG.nextFloat());
            Vec3 at = base.add(Math.cos(ang) * rr, 0.1, Math.sin(ang) * rr);
            Sprite em = new Sprite(Sprite.Kind.EMBER, at, new Vec3(0, rand(0.05f, 0.14f) * (0.5f + k), 0), rand(0.12f, 0.2f), 0.04f, 10 + RNG.nextInt(10))
                    .color(1f, rand(0.5f, 0.8f), 0.2f, 1f).physics(-0.004f, 0.95f);
            em.sway = 0.004f;
            em.frameOffset = RNG.nextInt(100);
            spawn(em);
        }
        for (int i = 0; i < count(1 + (int) (3 * k)); i++) {
            float ang = RNG.nextFloat() * TAU;
            Vec3 at = base.add(Math.cos(ang) * radius, 0.35, Math.sin(ang) * radius);
            Sprite f = new Sprite(Sprite.Kind.FLAME, at, new Vec3(0, 0.05, 0), 0.6f + 0.5f * k, 0.3f, 6 + RNG.nextInt(4));
            f.frameOffset = RNG.nextInt(64);
            spawn(f);
        }
    }

    /**
     * 불기둥 분출. minor = true 면 화염 주먹 적중 때 함께 솟는 작은 불기둥 (흔들림·자국 없음, 입자 적게).
     */
    static void addPillar(Vec3 base, float radius, float height, int duration, boolean minor) {
        PILLARS.add(new PillarFx(base, radius, height, duration, minor));
    }

    static final class PillarFx {
        final Vec3 base;
        final float r, h;
        final int duration;
        final boolean minor;
        int age;

        PillarFx(Vec3 base, float r, float h, int duration, boolean minor) {
            this.base = base;
            this.r = r;
            this.h = h;
            this.duration = Math.max(4, duration);
            this.minor = minor;
            FxManager.lod = FxManager.lodAt(base);
            float s = minor ? 0.5f : 1f;

            spawn(new Sprite(Sprite.Kind.GLOW, base.add(0, 1.0, 0), Vec3.ZERO, 8f * s, 11f * s, 6).color(1f, 0.9f, 0.7f, 1f));
            spawn(new Sprite(Sprite.Kind.GLOW, base.add(0, h * 0.5, 0), Vec3.ZERO, h * 0.9f, h * 1.1f, 10).color(1f, 0.45f, 0.12f, 0.7f));
            Vec3 rc = base.add(0, 0.06, 0);
            FxManager.addRing(rc, UP, r * 0.5f, r * (minor ? 2.4f : 3.6f), 10, 0, 1f);
            if (!minor) {
                FxManager.addRing(rc, UP, r * 0.3f, r * 2.2f, 8, 2, 0.8f);
                addDecal(base, UP, r * 1.7f, 180);
            }
            // 위로 터져 오르는 불꽃 줄기
            for (int i = 0; i < count(minor ? 14 : 40); i++) {
                Vec3 d = new Vec3(rand(-0.5f, 0.5f), rand(1.2f, 2.4f), rand(-0.5f, 0.5f)).normalize();
                spawn(new Sprite(Sprite.Kind.SPARK, base.add(0, 0.3, 0), d.scale(rand(0.6f, 1.3f) * (minor ? 0.7f : 1f)), 0.13f, 0.13f, 10 + RNG.nextInt(10))
                        .color(1f, 0.82f, 0.45f, 1f).physics(0.04f, 0.92f));
            }
            // 바닥을 따라 바깥으로 퍼지는 불꽃 덩어리
            for (int i = 0; i < count(minor ? 6 : 18); i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 d = new Vec3(Math.cos(ang), 0.15, Math.sin(ang));
                Sprite f = new Sprite(Sprite.Kind.FLAME, base.add(d.scale(r * 0.6)).add(0, 0.4, 0), d.scale(rand(0.18f, 0.32f)),
                        rand(1.2f, 1.8f) * (minor ? 0.6f : 1f), rand(2.0f, 2.6f) * (minor ? 0.6f : 1f), 9 + RNG.nextInt(6)).physics(-0.01f, 0.84f);
                f.frameOffset = RNG.nextInt(64);
                spawn(f);
            }
            if (!minor) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    double d = mc.player.position().distanceTo(base);
                    if (d < 18) CameraShake.add((float) (2.8 * (1 - d / 18)));
                }
            }
        }

        /** 기둥 높이 (솟는 4틱 동안 빠르게 자람, 끝 무렵 조금 가라앉음) */
        float height(float t) {
            float grow = easeOutCubic(Math.min(1f, t / 4f));
            float sink = t > duration ? clamp01((t - duration) / 12f) : 0f;
            return h * grow * (1f - 0.35f * sink) * (1f + 0.04f * (float) Math.sin(t * 1.3f));
        }

        /** @return true 면 끝 */
        boolean tick() {
            age++;
            float t = age;
            if (t > duration + 12) return true;
            FxManager.lod = FxManager.lodAt(base);
            float hh = height(t);
            boolean alive = t <= duration;
            float s = minor ? 0.55f : 1f;
            // 기둥을 따라 꼬이며 올라가는 불꽃
            for (int i = 0; i < count(alive ? (minor ? 3 : 7) : 2); i++) {
                float ang = RNG.nextFloat() * TAU;
                float y = RNG.nextFloat() * hh * 0.8f;
                float rr = r * (0.55f + 0.4f * RNG.nextFloat()) * (1f - 0.35f * y / Math.max(1f, hh));
                Vec3 at = base.add(Math.cos(ang) * rr, y, Math.sin(ang) * rr);
                Vec3 v = new Vec3(-Math.sin(ang) * 0.12, rand(0.25f, 0.45f), Math.cos(ang) * 0.12);
                Sprite f = new Sprite(Sprite.Kind.FLAME, at, v, rand(1.2f, 1.9f) * s, rand(0.5f, 0.9f) * s, 6 + RNG.nextInt(5)).physics(-0.01f, 0.9f);
                f.frameOffset = RNG.nextInt(64);
                spawn(f);
            }
            // 꼭대기에서 흩날리는 불씨
            for (int i = 0; i < count(alive ? (minor ? 2 : 5) : 3); i++) {
                Vec3 at = base.add(rand(-r, r) * 0.6, hh * rand(0.7f, 1.0f), rand(-r, r) * 0.6);
                Sprite em = new Sprite(Sprite.Kind.EMBER, at, new Vec3(rand(-0.08f, 0.08f), rand(0.06f, 0.16f), rand(-0.08f, 0.08f)),
                        rand(0.14f, 0.24f), 0.04f, 22 + RNG.nextInt(18)).color(1f, rand(0.45f, 0.75f), 0.2f, 1f).physics(-0.002f, 0.96f);
                em.sway = 0.004f;
                em.frameOffset = RNG.nextInt(100);
                spawn(em);
            }
            // 사라질 때 연기
            if (!minor && t > duration - 4 && t < duration + 6) {
                for (int i = 0; i < count(2); i++) {
                    Vec3 at = base.add(rand(-r, r) * 0.5, rand(0.5f, hh), rand(-r, r) * 0.5);
                    Sprite sm = new Sprite(Sprite.Kind.SMOKE, at, new Vec3(0, rand(0.04f, 0.08f), 0), rand(1.6f, 2.4f), rand(3.6f, 4.8f), 30 + RNG.nextInt(14))
                            .color(0.18f, 0.16f, 0.15f, 0.5f).physics(0f, 0.96f);
                    sm.rot = RNG.nextFloat() * TAU;
                    spawn(sm);
                }
            }
            return false;
        }

        void render(float pt, float time) {
            float t = age + pt;
            float hh = height(t);
            float fade = t > duration ? 1f - clamp01((t - duration) / 12f) : 1f;
            float erosion = t > duration - 6 ? clamp01((t - duration + 6) / 16f) : 0.04f;
            // 바깥 불기둥: 굵고 붉은 외곽, 위로 갈수록 가늘어지며 꼬임
            FxRender.fireShader(1.8f, erosion, 1.0f * fade + 0.1f);
            RenderSystem.disableCull();
            column(base, r * 1.05f, r * 0.62f, hh, 0.95f, 0.28f, 0f, 0.09f, time);
            // 안쪽 심지: 가늘고 하얗게 빛나며 반대로 꼬임
            FxRender.fireShader(2.6f, erosion * 0.8f, 1.25f * fade + 0.1f);
            column(base, r * 0.42f, r * 0.2f, hh * 1.06f, 1f, -0.4f, 0.37f, 0.06f, time);
            // 바닥에 퍼진 불꽃 고리
            FxRender.fireShader(1.4f, erosion, 0.9f * fade);
            BufferBuilder bb = FxRender.quads();
            FxRender.ring(bb, base.add(0, 0.05, 0), UP, r * 0.7f, r * (1.9f + 0.3f * clamp01(t / 6f)), 0.85f * fade, time * 0.25f, 56);
            FxRender.draw(bb);
            // 기둥 전체를 감싸는 빛
            Sprite glow = new Sprite(Sprite.Kind.GLOW, base.add(0, hh * 0.45, 0), Vec3.ZERO, hh * 1.1f + r * 2f, 0, 1)
                    .color(1f, 0.5f, 0.15f, 0.32f * fade);
            glow.size1 = glow.size0;
            FxManager.addFrameGlow(glow);
        }
    }

    // =====================================================================
    // 4. 화염 고리
    // =====================================================================

    /** 준비 중: 몸 주위를 도는 고리 3개 (ChargeFx 가 매 프레임 부름). k = 0→1 */
    static void renderRingCharge(Entity caster, float pt, float k, float time) {
        Vec3 c = caster.getPosition(pt).add(0, 1.0, 0);
        float s = 0.55f + 0.45f * easeOutCubic(Math.min(1f, k * 1.5f));
        float speed = 0.35f + 0.9f * k; // 점점 빨리 돔
        FxRender.fireShader(1.5f + k, 0.06f, 0.9f + 0.35f * k);
        RenderSystem.disableCull();
        BufferBuilder bb = FxRender.quads();
        float[] tilts = {0.25f, 1.05f, 1.35f};
        float[] rIn = {1.35f, 1.7f, 2.05f};
        float[] rOut = {1.6f, 1.92f, 2.25f};
        for (int i = 0; i < 3; i++) {
            float ph = time * speed * (i == 1 ? -1f : 1f) + i * 2.1f;
            Vec3 n = new Vec3(Math.sin(tilts[i]) * Math.cos(ph), Math.cos(tilts[i]), Math.sin(tilts[i]) * Math.sin(ph));
            FxRender.ring(bb, c, n, rIn[i] * s, rOut[i] * s, 0.8f + 0.2f * k, time * (0.5f + i * 0.2f), 56);
        }
        // 발밑 고리 (점점 넓어짐)
        Vec3 feet = caster.getPosition(pt).add(0, 0.05, 0);
        FxRender.ring(bb, feet, UP, (0.3f + 1.8f * k), (0.8f + 2.0f * k), 0.55f + 0.35f * k, -time * 0.4f, 56);
        FxRender.draw(bb);

        Sprite glow = new Sprite(Sprite.Kind.GLOW, c, Vec3.ZERO, 3.2f + 3.5f * k, 0, 1).color(1f, 0.55f, 0.18f, 0.35f + 0.4f * k);
        glow.size1 = glow.size0;
        FxManager.addFrameGlow(glow);
    }

    /** 준비 중: 몸 주위를 감도는 불꽃·빨려 드는 불씨 (ChargeFx 의 매 틱) */
    static void tickRingCharge(Entity caster, int age, float k) {
        Vec3 c = caster.position();
        for (int i = 0; i < 3; i++) {
            if (RNG.nextFloat() > FxManager.amount() * FxManager.lod) continue;
            float ang = age * 0.55f + i * TAU / 3f;
            float rr = 1.9f;
            Vec3 at = c.add(Math.cos(ang) * rr, 0.3 + i * 0.6, Math.sin(ang) * rr);
            Vec3 tangent = new Vec3(-Math.sin(ang), 0.04, Math.cos(ang)).scale(0.32 + 0.3 * k);
            Sprite f = new Sprite(Sprite.Kind.FLAME, at, tangent, 0.9f + 0.6f * k, 0.4f, 6).physics(0f, 0.9f);
            f.frameOffset = RNG.nextInt(64);
            spawn(f);
        }
        for (int i = 0; i < count(4); i++) {
            Vec3 off = new Vec3(rand(-1f, 1f), rand(-0.2f, 0.9f), rand(-1f, 1f)).normalize().scale(rand(2.6f, 3.6f));
            Sprite em = new Sprite(Sprite.Kind.EMBER, c.add(0, 1.0, 0).add(off), off.scale(-1.0 / 6.0), 0.17f, 0.06f, 6)
                    .color(1f, 0.7f, 0.3f, 1f);
            spawn(em);
        }
    }

    static void addRingBurst(Vec3 center, float radius, int duration) {
        BURSTS.add(new RingBurstFx(center, radius, duration));
    }

    static final class RingBurstFx {
        final Vec3 c;
        final float R;
        final int duration;
        int age;

        RingBurstFx(Vec3 c, float radius, int duration) {
            this.c = c;
            this.R = radius;
            this.duration = Math.max(8, duration);
            FxManager.lod = FxManager.lodAt(c);
            Vec3 mid = c.add(0, 1.0, 0);
            spawn(new Sprite(Sprite.Kind.GLOW, mid, Vec3.ZERO, 9f, 13f, 6).color(1f, 0.92f, 0.72f, 1f));
            spawn(new Sprite(Sprite.Kind.GLOW, mid, Vec3.ZERO, R * 1.6f, R * 2.4f, 12).color(1f, 0.45f, 0.12f, 0.6f));
            Vec3 rc = c.add(0, 0.08, 0);
            FxManager.addRing(rc, UP, 0.6f, R * 1.35f, 11, 0, 1f);
            FxManager.addRing(rc, UP, 0.4f, R * 0.95f, 9, 2, 0.85f);
            FxManager.addRing(mid, UP, 0.5f, R * 1.1f, 8, 1, 0.6f);
            addDecal(c, UP, R * 0.6f, 200);
            for (int i = 0; i < count(48); i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 d = new Vec3(Math.cos(ang), rand(0.05f, 0.5f), Math.sin(ang)).normalize();
                spawn(new Sprite(Sprite.Kind.SPARK, mid, d.scale(rand(0.8f, 1.6f)), 0.13f, 0.13f, 9 + RNG.nextInt(8))
                        .color(1f, 0.82f, 0.45f, 1f).physics(0.04f, 0.9f));
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                double d = mc.player.position().distanceTo(c);
                if (d < R + 10) CameraShake.add((float) (3.0 * (1 - d / (R + 10))));
            }
        }

        float radius(float t, float delay) {
            float f = easeOutCubic(clamp01((t - delay) / (duration * 0.6f)));
            return 0.8f + (R - 0.8f) * f;
        }

        boolean tick() {
            age++;
            if (age > duration + 8) return true;
            FxManager.lod = FxManager.lodAt(c);
            float t = age;
            if (t <= duration) {
                float rr = radius(t, 0f);
                // 불의 벽 앞머리에서 바깥으로 튀는 불꽃
                for (int i = 0; i < count(12); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    Vec3 d = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                    Sprite f = new Sprite(Sprite.Kind.FLAME, c.add(d.scale(rr)).add(0, rand(0.3f, 1.4f), 0),
                            d.scale(rand(0.1f, 0.22f)).add(0, rand(0.04f, 0.1f), 0), rand(1.0f, 1.6f), rand(0.5f, 0.9f), 6 + RNG.nextInt(5))
                            .physics(-0.006f, 0.88f);
                    f.frameOffset = RNG.nextInt(64);
                    spawn(f);
                }
                for (int i = 0; i < count(5); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float r2 = rr * (float) Math.sqrt(RNG.nextFloat());
                    Sprite em = new Sprite(Sprite.Kind.EMBER, c.add(Math.cos(ang) * r2, 0.2, Math.sin(ang) * r2),
                            new Vec3(rand(-0.04f, 0.04f), rand(0.05f, 0.12f), rand(-0.04f, 0.04f)), rand(0.14f, 0.22f), 0.04f, 26 + RNG.nextInt(20))
                            .color(1f, rand(0.45f, 0.75f), 0.2f, 1f).physics(-0.002f, 0.96f);
                    em.sway = 0.004f;
                    em.frameOffset = RNG.nextInt(100);
                    spawn(em);
                }
            }
            if (age == duration - 4) {
                for (int i = 0; i < count(12); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float r2 = R * rand(0.3f, 0.95f);
                    Sprite sm = new Sprite(Sprite.Kind.SMOKE, c.add(Math.cos(ang) * r2, rand(0.3f, 1.2f), Math.sin(ang) * r2),
                            new Vec3(0, rand(0.03f, 0.06f), 0), rand(1.6f, 2.4f), rand(3.6f, 4.8f), 30 + RNG.nextInt(14))
                            .color(0.19f, 0.17f, 0.16f, 0.5f).physics(0f, 0.96f);
                    sm.rot = RNG.nextFloat() * TAU;
                    spawn(sm);
                }
            }
            return false;
        }

        void render(float pt, float time) {
            float t = age + pt;
            float fade = 1f - clamp01((t - duration * 0.55f) / (duration * 0.6f));
            if (fade <= 0f) return;
            float erosion = clamp01((t - duration * 0.4f) / duration);
            RenderSystem.disableCull();
            // 앞쪽 큰 불의 벽 (퍼질수록 낮아짐)
            float r1 = radius(t, 0f);
            float f1 = (r1 - 0.8f) / Math.max(0.1f, R - 0.8f);
            FxRender.fireShader(2.0f, erosion, 1.15f * fade);
            column(c, r1, r1 * 1.06f, 2.8f * (1f - 0.65f * f1) + 0.4f, 0.95f, 0.15f, 0f, 0.07f, time);
            // 뒤따르는 낮은 두 번째 벽
            float r2 = radius(t, 3f);
            if (t > 3f) {
                FxRender.fireShader(1.6f, Math.min(1f, erosion + 0.15f), 0.9f * fade);
                column(c, r2, r2 * 1.04f, 1.4f * (1f - 0.5f * f1) + 0.2f, 0.8f, -0.2f, 0.5f, 0.06f, time);
            }
            // 바닥을 따라 타는 불길 띠
            FxRender.fireShader(1.3f, erosion, 0.9f * fade);
            BufferBuilder bb = FxRender.quads();
            FxRender.ring(bb, c.add(0, 0.05, 0), UP, Math.max(0.1f, r1 - 2.0f), r1 + 0.35f, 0.85f * fade, time * 0.2f, 72);
            FxRender.draw(bb);
        }
    }
}
