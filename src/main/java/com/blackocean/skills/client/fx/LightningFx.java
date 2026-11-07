package com.blackocean.skills.client.fx;

import com.blackocean.skills.Element;
import com.blackocean.skills.Skill;
import com.blackocean.skills.network.SkillEventPayload;
import com.blackocean.skills.network.SkillEvents;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

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
 * 번개 능력 연출.
 * <pre>
 *  공통   : 진짜 번개처럼 꺾이는 줄기 (중간점 변위 재귀 + 1~3개 갈래, 1~2틱마다 모양이 바뀌며 깜빡임)
 *           카메라를 향한 띠 2겹 (넓고 옅은 푸른/보랏빛 번짐 + 가늘고 하얀 심지)
 *  1 뇌격  : 손 주위 작은 전기 → 즉시 번개 + 연쇄 (BOLT)
 *  2 섬광  : 몸이 번쩍 → 출발·도착 사이 굵은 번개 + 양 끝 플라즈마 폭발 + 길을 따라 남는 전기 (BLINK)
 *  3 낙뢰  : 바닥 경고 원(푸른 전기) → 하늘에서 떨어지는 번개 3번 (STRIKE)
 *  4 방전  : 몸을 기어 다니는 전기 → 전기 돔 + 바닥을 따라 퍼지는 전기 (BURST + BOLT)
 *  5 뇌신  : 하늘로 번개를 쏘며 하늘이 어두워짐 → 도는 뇌운 + 낙뢰 비 + 마지막 거대 번개 (STORM + STRIKE)
 * </pre>
 */
final class LightningFx {
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final float TAU = (float) (Math.PI * 2);
    private static final Element EL = Element.LIGHTNING;

    private static final int MAX_BOLTS = 220;
    private static final List<Bolt> BOLTS = new ArrayList<>();
    private static final List<StrikeFx> STRIKES = new ArrayList<>();
    private static final List<BlinkFx> BLINKS = new ArrayList<>();
    private static final List<DomeFx> DOMES = new ArrayList<>();
    private static final List<StormFx> STORMS = new ArrayList<>();

    /** 모듈 자체 틱 수 (최근 일 판단용) */
    private static int ticks;
    /** 뇌신 준비 중 하늘을 이미 어둡게 했는지 (STORM 신호 때 다시 부르면 밝아졌다 어두워지는 깜빡임이 생김) */
    @Nullable
    private static FxManager.ChargeFx darkenedCharge;
    private static Vec3 stormDarkPos = Vec3.ZERO;
    private static int stormDarkTick = -1000;

    private LightningFx() {}

    // =====================================================================
    // 계약 함수
    // =====================================================================

    static void onEvent(SkillEventPayload e, Skill skill, @Nullable Entity caster) {
        switch (e.kind()) {
            case SkillEvents.BOLT -> bolt(e.pos(), e.vec(), e.size() > 0 ? e.size() : 1f, e.target(), skill, caster);
            case SkillEvents.BLINK -> blink(e.pos(), e.vec(), e.size() > 0 ? e.size() : 2.5f, caster);
            case SkillEvents.STRIKE -> {
                boolean giant = e.size2() > 0.5f;
                boolean minor = skill == Skill.LIGHTNING_STORM && !giant;
                if (STRIKES.size() >= 24) STRIKES.remove(0);
                STRIKES.add(new StrikeFx(e.pos(), e.size() > 0 ? e.size() : 2.4f, giant, minor));
            }
            case SkillEvents.BURST -> discharge(e.pos(), e.size() > 0 ? e.size() : 7f, e.duration() > 0 ? e.duration() : 14, caster);
            case SkillEvents.STORM -> storm(e.pos(), e.size() > 0 ? e.size() : 11f, Math.max(20, e.duration()));
            default -> { }
        }
    }

    static void tick() {
        ticks++;
        BOLTS.removeIf(Bolt::tick);
        STRIKES.removeIf(StrikeFx::tick);
        BLINKS.removeIf(BlinkFx::tick);
        DOMES.removeIf(DomeFx::tick);
        STORMS.removeIf(StormFx::tick);
        while (BOLTS.size() > MAX_BOLTS) BOLTS.remove(0);
    }

    static void renderAdditive(float pt, float time) {
        if (isEmpty()) return;
        RenderSystem.disableCull();

        // 1) 번개 띠 (한 번에)
        if (!BOLTS.isEmpty() || !STRIKES.isEmpty() || !BLINKS.isEmpty()) {
            FxRender.spriteShader(FxRender.BEAM);
            BufferBuilder bb = FxRender.quads();
            for (Bolt b : BOLTS) b.emit(bb, pt);
            for (StrikeFx s : STRIKES) s.emitCore(bb, pt);
            for (BlinkFx k : BLINKS) k.emit(bb, pt);
            FxRender.draw(bb);
        }

        // 2) 바닥에 번지는 빛
        if (!STRIKES.isEmpty() || !STORMS.isEmpty()) {
            FxRender.spriteShader(FxRender.GLOW_WHITE);
            BufferBuilder bb = FxRender.quads();
            for (StrikeFx s : STRIKES) s.emitGround(bb, pt);
            for (StormFx s : STORMS) s.emitGround(bb, pt);
            FxRender.draw(bb);
        }

        // 3) 흐르는 전기 셰이더 (돔, 폭풍 테두리)
        for (DomeFx d : DOMES) d.render(pt, time);
        for (StormFx s : STORMS) s.renderRim(pt, time);

        // 4) 이번 프레임 후광
        for (StrikeFx s : STRIKES) s.frameGlows(pt);
        for (StormFx s : STORMS) s.frameGlows(pt);
        for (BlinkFx k : BLINKS) k.frameGlows(pt);
    }

    static void renderAlpha(float pt, float time) {
        if (STORMS.isEmpty()) return;
        FxRender.spriteShader(FxRender.MIST);
        RenderSystem.disableCull();
        BufferBuilder bb = FxRender.quads();
        for (StormFx s : STORMS) s.renderCloud(bb, pt, time);
        FxRender.draw(bb);
    }

    static boolean isEmpty() {
        return BOLTS.isEmpty() && STRIKES.isEmpty() && BLINKS.isEmpty() && DOMES.isEmpty() && STORMS.isEmpty();
    }

    static void clear() {
        BOLTS.clear();
        STRIKES.clear();
        BLINKS.clear();
        DOMES.clear();
        STORMS.clear();
        darkenedCharge = null;
        stormDarkTick = -1000;
    }

    static void tickCharge(FxManager.ChargeFx c, float k) {
        Entity ce = c.caster;
        if (ce == null || ce.isRemoved()) return;
        switch (c.skill) {
            case LIGHTNING_BOLT -> {
                Vec3 h = c.handPos(1f, 0.38);
                FxManager.lod = FxManager.lodAt(h);
                for (int i = 0; i < Math.max(1, count(2 + (int) (2 * k))); i++) {
                    Vec3 s = h.add(randomDir().scale(0.12));
                    Vec3 t = h.add(randomDir().scale(rand(0.4f, 0.75f)));
                    addBolt(new Bolt(s, t, 0.22f + 0.1f * k, 2, 0, 3, RNG.nextInt(2), 0.32f, false));
                }
                Sprite f = sp(Sprite.Kind.FLAME, h.add(randomDir().scale(0.1)), Vec3.ZERO, 0.5f + 0.3f * k, 0.8f + 0.3f * k, 4)
                        .color(0.8f, 0.9f, 1f, 0.9f);
                f.frameOffset = RNG.nextInt(64);
                spawn(f);
                for (int i = 0; i < count(2); i++) {
                    spawn(sp(Sprite.Kind.SPARK, h, randomDir().scale(rand(0.1f, 0.25f)), 0.06f, 0.06f, 4 + RNG.nextInt(3))
                            .color(0.75f, 0.9f, 1f, 1f).physics(0.01f, 0.85f));
                }
            }
            case LIGHTNING_BLINK -> {
                Vec3 body = ce.position().add(0, 0.9, 0);
                spawn(sp(Sprite.Kind.GLOW, body, Vec3.ZERO, 1.5f, 3.2f, 3).color(0.6f, 0.82f, 1f, 0.8f));
                bodyArcs(ce, 3);
                for (int i = 0; i < count(8); i++) {
                    spawn(sp(Sprite.Kind.SPARK, body, randomDir().scale(rand(0.2f, 0.45f)), 0.07f, 0.07f, 4 + RNG.nextInt(4))
                            .color(0.75f, 0.9f, 1f, 1f).physics(0.02f, 0.88f));
                }
            }
            case LIGHTNING_STRIKE -> {
                tickWarn(c.target, c.size, k);
                FxManager.lod = FxManager.lodAt(ce.position());
                Vec3 h = c.handPos(1f, 0.38);
                if (RNG.nextFloat() < 0.6f) {
                    addBolt(new Bolt(h.add(randomDir().scale(0.1)), h.add(randomDir().scale(rand(0.35f, 0.6f))), 0.2f, 2, 0, 3, 0, 0.32f, false));
                }
            }
            case LIGHTNING_DISCHARGE -> {
                bodyArcs(ce, 2 + (int) (4 * k));
                Vec3 feet = ce.position();
                for (int i = 0; i < count(2 + (int) (4 * k)); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = rand(0.4f, 1.6f);
                    spawn(sp(Sprite.Kind.SPARK, feet.add(Math.cos(ang) * rr, 0.1, Math.sin(ang) * rr),
                            new Vec3(0, rand(0.1f, 0.25f), 0), 0.07f, 0.07f, 6 + RNG.nextInt(6))
                            .color(0.7f, 0.88f, 1f, 1f).physics(-0.004f, 0.94f));
                }
                if (c.age % 3 == 0) {
                    // 발밑에서 바깥으로 기어 나가는 짧은 전기
                    float ang = RNG.nextFloat() * TAU;
                    Vec3 s = feet.add(Math.cos(ang) * 0.4, 0.12, Math.sin(ang) * 0.4);
                    Vec3 t = feet.add(Math.cos(ang) * (1.2 + 2.0 * k), 0.12, Math.sin(ang) * (1.2 + 2.0 * k));
                    addBolt(new Bolt(s, t, 0.3f, 3, 0, 3, 1, 0.3f, true));
                }
            }
            case LIGHTNING_STORM -> {
                if (darkenedCharge != c) {
                    darkenedCharge = c;
                    int storm = Math.max(20, Skill.LIGHTNING_STORM.durationTicks());
                    ScreenFx.darken(c.target, Math.max(6f, c.size), 0.7f, c.duration + storm + 30);
                    stormDarkPos = c.target;
                    stormDarkTick = ticks;
                }
                FxManager.lod = FxManager.lodAt(ce.position());
                bodyArcs(ce, 2 + (int) (3 * k));
                if (c.age % 5 == 1) {
                    // 시전자 머리 위에서 하늘로 솟구치는 번개
                    Vec3 head = ce.position().add(0, ce.getBbHeight() + 0.3, 0);
                    Vec3 top = head.add(rand(-2.5f, 2.5f), 24 + rand(0f, 4f), rand(-2.5f, 2.5f));
                    addBolt(new Bolt(head, top, 0.9f + 0.6f * k, 5, 0, 6, 2, 0.2f, false));
                    spawn(sp(Sprite.Kind.GLOW, head, Vec3.ZERO, 2.2f, 3.4f, 4).color(0.7f, 0.88f, 1f, 1f));
                    for (int i = 0; i < count(8); i++) {
                        spawn(sp(Sprite.Kind.SPARK, head, randomDir().scale(rand(0.2f, 0.5f)).add(0, 0.2, 0), 0.08f, 0.08f, 6 + RNG.nextInt(5))
                                .color(0.75f, 0.9f, 1f, 1f).physics(0.03f, 0.9f));
                    }
                }
                tickWarn(c.target, c.size, k);
            }
            default -> { }
        }
    }

    static void renderCharge(FxManager.ChargeFx c, float pt, float k, float time) {
        Entity ce = c.caster;
        if (ce == null || ce.isRemoved()) return;
        switch (c.skill) {
            case LIGHTNING_BOLT -> {
                Vec3 h = c.handPos(pt, 0.38);
                float fl = 0.85f + 0.15f * (float) Math.sin(time * 3.1f);
                frameGlow(h, (0.9f + 1.4f * k) * fl, 0.45f, 0.7f, 1f, 0.45f + 0.4f * k);
                frameGlow(h, 0.3f + 0.35f * k, 0.9f, 0.95f, 1f, 0.9f);
            }
            case LIGHTNING_BLINK -> {
                Vec3 body = ce.getPosition(pt).add(0, 0.9, 0);
                frameGlow(body, 1.6f + 3.2f * k, 0.5f, 0.75f, 1f, 0.45f + 0.5f * k);
            }
            case LIGHTNING_STRIKE -> {
                renderWarn(c.target, c.size, k, time);
                frameGlow(c.handPos(pt, 0.38), 0.7f + 0.9f * k, 0.5f, 0.75f, 1f, 0.5f + 0.3f * k);
            }
            case LIGHTNING_DISCHARGE -> {
                Vec3 feet = ce.getPosition(pt);
                Vec3 chest = feet.add(0, 1.0, 0);
                float fl = 0.85f + 0.15f * (float) Math.sin(time * 2.7f);
                frameGlow(chest, (1.4f + 4.2f * k * k) * fl, 0.45f, 0.65f, 1f, 0.3f + 0.5f * k);
                frameGlow(chest, 0.6f + 1.2f * k, 0.85f, 0.93f, 1f, 0.5f + 0.4f * k);
                FxRender.elemShader(EL, 2.4f, 0.25f - 0.2f * k, 0.8f + 0.5f * k);
                RenderSystem.disableCull();
                BufferBuilder bb = FxRender.quads();
                FxRender.ring(bb, feet.add(0, 0.05, 0), UP, 0.3f + 1.6f * k, 0.7f + 1.9f * k, 0.55f + 0.4f * k, time * 0.5f, 48);
                FxRender.draw(bb);
            }
            case LIGHTNING_STORM -> {
                renderWarn(c.target, c.size, k, time);
                Vec3 p = ce.getPosition(pt);
                frameGlow(p.add(0, 1.0, 0), 2f + 3f * k, 0.45f, 0.62f, 1f, 0.3f + 0.4f * k);
                frameGlow(p.add(0, ce.getBbHeight() + 0.4, 0), 0.8f + 1.6f * k, 0.8f, 0.92f, 1f, 0.6f + 0.3f * k);
            }
            default -> { }
        }
    }

    static void trackProjectile(FxManager.ProjectileFx p, Vec3 pos) {}

    static void renderProjectile(FxManager.ProjectileFx p, Vec3 c, float pt, float time) {}

    // =====================================================================
    // 신호별 연출
    // =====================================================================

    /** 뇌격·방전 줄기: a → b */
    private static void bolt(Vec3 a, Vec3 b, float w, int target, Skill skill, @Nullable Entity caster) {
        float len = (float) a.distanceTo(b);
        boolean discharge = skill == Skill.LIGHTNING_DISCHARGE;
        if (discharge) {
            // 방전은 대상이 많을 수 있어 가볍게 한 줄기
            addBolt(new Bolt(a, b, w, 7, 0, len > 5 ? 5 : 4, 1, 0.2f, false));
        } else {
            int depth = len > 14 ? 6 : (len > 5 ? 5 : 4);
            addBolt(new Bolt(a, b, w, 7, 0, depth, w >= 1.1f ? 3 : 2, 0.2f, false));
            addBolt(new Bolt(a, b, w * 0.5f, 5, 1, Math.max(3, depth - 1), 1, 0.25f, false));
        }

        FxManager.lod = FxManager.lodAt(b);
        // 맞은 곳
        spawn(sp(Sprite.Kind.GLOW, b, Vec3.ZERO, 2.2f * w, 3.4f * w, 5).color(0.85f, 0.93f, 1f, 1f));
        spawn(sp(Sprite.Kind.GLOW, b, Vec3.ZERO, 4.5f * w, 5.5f * w, 8).color(0.4f, 0.6f, 1f, 0.5f));
        for (int i = 0; i < count(discharge ? 6 : 14); i++) {
            spawn(sp(Sprite.Kind.SPARK, b, randomDir().scale(rand(0.3f, 0.8f)), 0.09f, 0.09f, 6 + RNG.nextInt(7))
                    .color(0.75f, 0.9f, 1f, 1f).physics(0.04f, 0.9f));
        }
        for (int i = 0; i < count(discharge ? 2 : 4); i++) {
            Sprite f = sp(Sprite.Kind.FLAME, b.add(randomDir().scale(0.3)), randomDir().scale(0.05), 0.9f * w, 1.6f * w, 6 + RNG.nextInt(4))
                    .color(0.75f, 0.88f, 1f, 1f);
            f.frameOffset = RNG.nextInt(64);
            spawn(f);
        }
        FxManager.addRing(b, UP, 0.2f, 1.6f * w, 7, 0, 0.8f, EL);
        if (target >= 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                Entity t = mc.level.getEntity(target);
                if (t != null && !t.isRemoved()) bodyArcs(t, discharge ? 2 : 4);
            }
        }
        // 시작점
        if (!discharge) {
            FxManager.lod = FxManager.lodAt(a);
            spawn(sp(Sprite.Kind.GLOW, a, Vec3.ZERO, 1.6f * w, 2.4f * w, 4).color(0.75f, 0.9f, 1f, 1f));
            for (int i = 0; i < count(5); i++) {
                spawn(sp(Sprite.Kind.SPARK, a, randomDir().scale(rand(0.2f, 0.5f)), 0.07f, 0.07f, 5 + RNG.nextInt(4))
                        .color(0.75f, 0.9f, 1f, 1f).physics(0.03f, 0.9f));
            }
            if (caster != null && caster == Minecraft.getInstance().player && w >= 1.1f) CameraShake.add(0.7f);
        }
    }

    /** 섬광 이동 */
    private static void blink(Vec3 a, Vec3 b, float R, @Nullable Entity caster) {
        float len = (float) a.distanceTo(b);
        int depth = len > 8 ? 6 : 5;
        addBolt(new Bolt(a, b, 1.7f, 9, 0, depth, 3, 0.18f, false));
        addBolt(new Bolt(a, b, 0.8f, 7, 1, depth - 1, 1, 0.22f, false));
        addBolt(new Bolt(a, b, 0.6f, 6, 3, depth - 1, 2, 0.24f, false));
        if (BLINKS.size() >= 16) BLINKS.remove(0);
        BLINKS.add(new BlinkFx(a, b, R));
        Vec3 dir = b.subtract(a);
        dir = dir.lengthSqr() < 1.0E-6 ? UP : dir.normalize();
        blinkEnd(a, dir, R);
        blinkEnd(b, dir, R);
        Minecraft mc = Minecraft.getInstance();
        if (caster != null && caster == mc.player) {
            CameraShake.add(1.0f);
            ScreenFx.flash(0xBFDFFF, 0.25f, 6);
        } else if (mc.player != null) {
            double d = Math.min(mc.player.position().distanceTo(a), mc.player.position().distanceTo(b));
            if (d < 14) CameraShake.add((float) (0.8 * (1 - d / 14)));
        }
    }

    private static void blinkEnd(Vec3 p, Vec3 dir, float R) {
        FxManager.lod = FxManager.lodAt(p);
        spawn(sp(Sprite.Kind.GLOW, p, Vec3.ZERO, 3f, 5f, 6).color(0.85f, 0.93f, 1f, 1f));
        spawn(sp(Sprite.Kind.GLOW, p, Vec3.ZERO, R * 2.4f, R * 3f, 10).color(0.4f, 0.58f, 1f, 0.55f));
        for (int i = 0; i < count(10); i++) {
            Vec3 v = randomDir().scale(rand(0.1f, 0.24f));
            Sprite f = sp(Sprite.Kind.FLAME, p.add(v.scale(2)), v, rand(0.9f, 1.3f), rand(1.8f, 2.4f), 7 + RNG.nextInt(5))
                    .color(0.75f, 0.88f, 1f, 1f).physics(-0.004f, 0.86f);
            f.frameOffset = RNG.nextInt(64);
            f.rot = RNG.nextFloat() * TAU;
            spawn(f);
        }
        for (int i = 0; i < count(18); i++) {
            spawn(sp(Sprite.Kind.SPARK, p, randomDir().scale(rand(0.35f, 0.9f)), 0.09f, 0.09f, 7 + RNG.nextInt(8))
                    .color(0.75f, 0.9f, 1f, 1f).physics(0.04f, 0.9f));
        }
        Vec3 feet = p.subtract(0, 0.85, 0);
        FxManager.addRing(feet, UP, 0.3f, R * 1.8f, 10, 0, 1f, EL);
        FxManager.addRing(p, UP, 0.2f, R * 1.2f, 8, 1, 0.8f, EL);
        FxManager.addRing(p, dir, 0.2f, R * 1.0f, 7, 0, 0.7f, EL);
        // 바닥 쪽으로 튀는 짧은 전기
        for (int i = 0; i < 3; i++) {
            Vec3 t = feet.add(rand(-1.5f, 1.5f), 0.1, rand(-1.5f, 1.5f));
            addBolt(new Bolt(p, t, 0.35f, 3, RNG.nextInt(3), 3, 1, 0.3f, false));
        }
    }

    /** 방전 폭발 (BURST, pos = 발밑 바닥) */
    private static void discharge(Vec3 g, float R, int life, @Nullable Entity caster) {
        Vec3 chest = caster != null && !caster.isRemoved() ? caster.position().add(0, 1.0, 0) : g.add(0, 1.0, 0);
        FxManager.lod = FxManager.lodAt(g);
        if (DOMES.size() >= 16) DOMES.remove(0);
        DOMES.add(new DomeFx(chest, R, Math.max(10, life), 4f));

        // 바닥을 따라 원 끝까지 뻗는 전기
        int n = Math.max(6, count(16));
        float base = RNG.nextFloat() * TAU;
        for (int i = 0; i < n; i++) {
            float ang = base + i * TAU / n + rand(-0.15f, 0.15f);
            double cs = Math.cos(ang), sn = Math.sin(ang);
            Vec3 s = g.add(cs * 0.6, 0.2, sn * 0.6);
            float er = R * rand(0.85f, 1.05f);
            Vec3 t = g.add(cs * er, 0.15, sn * er);
            addBolt(new Bolt(s, t, 0.55f, 6 + RNG.nextInt(3), RNG.nextInt(3), 4, 1, 0.22f, true));
        }
        Vec3 rc = g.add(0, 0.06, 0);
        FxManager.addRing(rc, UP, 0.5f, R * 1.3f, 11, 0, 1f, EL);
        FxManager.addRing(rc, UP, 0.3f, R * 0.9f, 9, 2, 0.85f, EL);
        FxManager.addRing(chest, UP, 0.4f, R * 1.1f, 8, 1, 0.6f, EL);
        spawn(sp(Sprite.Kind.GLOW, chest, Vec3.ZERO, 6f, 10f, 6).color(0.88f, 0.95f, 1f, 1f));
        spawn(sp(Sprite.Kind.GLOW, chest, Vec3.ZERO, R * 2f, R * 3f, 10).color(0.4f, 0.58f, 1f, 0.6f));
        for (int i = 0; i < count(22); i++) {
            float ang = RNG.nextFloat() * TAU;
            Vec3 d = new Vec3(Math.cos(ang), rand(-0.1f, 0.25f), Math.sin(ang));
            Sprite f = sp(Sprite.Kind.FLAME, chest.add(d.scale(0.6)), d.scale(rand(0.2f, 0.45f)), rand(1.1f, 1.5f), rand(2.1f, 2.7f), 7 + RNG.nextInt(5))
                    .color(0.75f, 0.88f, 1f, 1f).physics(0f, 0.86f);
            f.frameOffset = RNG.nextInt(64);
            f.rot = RNG.nextFloat() * TAU;
            spawn(f);
        }
        for (int i = 0; i < count(50); i++) {
            Vec3 d = randomDir();
            spawn(sp(Sprite.Kind.SPARK, chest, d.scale(rand(0.6f, 1.4f)), 0.1f, 0.1f, 8 + RNG.nextInt(8))
                    .color(0.75f, 0.9f, 1f, 1f).physics(0.04f, 0.9f));
        }
        SkillFx.addDecal(g, UP, R * 0.55f, 180, 0.6f, 0.72f, 1f);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            double d = mc.player.position().distanceTo(g);
            if (d < R + 10) CameraShake.add((float) (2.6 * (1 - d / (R + 10))));
            if (caster != null && caster == mc.player) ScreenFx.flash(0xBFDFFF, 0.22f, 7);
        }
    }

    /** 뇌신의 심판 시작 */
    private static void storm(Vec3 c, float R, int dur) {
        boolean already = ticks - stormDarkTick < 80 && stormDarkPos.distanceTo(c) < 8;
        if (!already) ScreenFx.darken(c, R, 0.7f, dur + 30);
        if (STORMS.size() >= 4) STORMS.remove(0);
        STORMS.add(new StormFx(c, R, dur));
        FxManager.lod = FxManager.lodAt(c);
        Vec3 rc = c.add(0, 0.08, 0);
        FxManager.addRing(rc, UP, R * 0.3f, R * 1.25f, 16, 0, 0.9f, EL);
        FxManager.addRing(rc, UP, R * 0.2f, R * 0.9f, 12, 3, 0.7f, EL);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            double d = mc.player.position().distanceTo(c);
            if (d < R + 24) CameraShake.add((float) (1.4 * (1 - d / (R + 24))));
        }
    }

    // =====================================================================
    // 준비 도우미
    // =====================================================================

    /** 몸 표면을 기어 다니는 짧은 전기 */
    private static void bodyArcs(Entity e, int n) {
        Vec3 base = e.position();
        float h = Math.max(0.5f, e.getBbHeight());
        float w = Math.max(0.25f, e.getBbWidth() * 0.6f);
        for (int i = 0; i < count(n); i++) {
            float ang = RNG.nextFloat() * TAU;
            Vec3 p0 = base.add(Math.cos(ang) * w, rand(0.1f, h), Math.sin(ang) * w);
            Vec3 p1 = p0.add(randomDir().scale(rand(0.4f, 0.9f)));
            addBolt(new Bolt(p0, p1, 0.22f, 2 + RNG.nextInt(2), 0, 3, RNG.nextInt(2), 0.35f, false));
        }
    }

    /** 경고 원 위를 기어 다니는 전기·솟는 불꽃 (매 틱) */
    private static void tickWarn(Vec3 base, float R, float k) {
        FxManager.lod = FxManager.lodAt(base);
        float areaMul = Math.max(1f, R / 4f);
        for (int i = 0; i < count((int) ((1 + 3 * k) * areaMul)); i++) {
            float ang = RNG.nextFloat() * TAU;
            float rr = R * (float) Math.sqrt(RNG.nextFloat()) * 0.95f;
            Vec3 s = base.add(Math.cos(ang) * rr, 0.12, Math.sin(ang) * rr);
            float a2 = RNG.nextFloat() * TAU;
            float len = rand(0.8f, 2.0f) * (R > 6 ? 1.6f : 1f);
            Vec3 t = s.add(Math.cos(a2) * len, 0, Math.sin(a2) * len);
            addBolt(new Bolt(s, t, 0.28f + 0.2f * k, 2 + RNG.nextInt(2), 0, 3, RNG.nextInt(2), 0.3f, true));
        }
        // 테두리를 따라 튀는 전기
        for (int i = 0; i < count((int) ((1 + 2 * k) * areaMul * 0.7f) + 1); i++) {
            float a0 = RNG.nextFloat() * TAU;
            float a1 = a0 + (R > 6 ? 0.3f : 0.7f) * (RNG.nextBoolean() ? 1 : -1);
            Vec3 s = base.add(Math.cos(a0) * R, 0.15, Math.sin(a0) * R);
            Vec3 t = base.add(Math.cos(a1) * R, 0.15, Math.sin(a1) * R);
            addBolt(new Bolt(s, t, 0.3f + 0.2f * k, 2, 0, 3, 0, 0.28f, true));
        }
        for (int i = 0; i < count((int) ((2 + 5 * k) * areaMul)); i++) {
            float ang = RNG.nextFloat() * TAU;
            float rr = R * (float) Math.sqrt(RNG.nextFloat());
            Vec3 at = base.add(Math.cos(ang) * rr, 0.1, Math.sin(ang) * rr);
            spawn(sp(Sprite.Kind.SPARK, at, new Vec3(rand(-0.02f, 0.02f), rand(0.12f, 0.3f) * (0.6f + k), rand(-0.02f, 0.02f)),
                    0.07f, 0.07f, 8 + RNG.nextInt(7)).color(0.7f, 0.88f, 1f, 1f).physics(-0.004f, 0.95f));
        }
        for (int i = 0; i < count((int) ((1 + 2 * k) * areaMul)); i++) {
            float ang = RNG.nextFloat() * TAU;
            float rr = R * (float) Math.sqrt(RNG.nextFloat());
            Sprite em = sp(Sprite.Kind.EMBER, base.add(Math.cos(ang) * rr, 0.1, Math.sin(ang) * rr),
                    new Vec3(0, rand(0.04f, 0.1f), 0), rand(0.1f, 0.18f), 0.04f, 12 + RNG.nextInt(10))
                    .color(0.6f, 0.82f, 1f, 1f).physics(-0.003f, 0.95f);
            em.frameOffset = RNG.nextInt(100);
            spawn(em);
        }
    }

    /** 푸른 전기 경고 원 (매 프레임, 더하기 섞기) */
    private static void renderWarn(Vec3 base, float R, float k, float time) {
        float pulse = 0.7f + 0.3f * (float) Math.sin(time * (1.5f + 3f * k));
        Vec3 c = base.add(0, 0.05, 0);
        RenderSystem.disableCull();

        FxRender.spriteShader(FxRender.SHOCK_RING_WHITE);
        BufferBuilder bb = FxRender.quads();
        float a = (0.35f + 0.65f * k) * pulse;
        FxRender.planeQuad(bb, c, UP, R * 1.08f, time * 0.05f, a * 0.45f, a * 0.72f, a, a);
        float a2 = a * 0.6f;
        FxRender.planeQuad(bb, c.add(0, 0.01, 0), UP, R * (0.45f + 0.45f * k), -time * 0.09f, a2 * 0.55f, a2 * 0.78f, a2, a2);
        FxRender.draw(bb);

        FxRender.spriteShader(FxRender.GLOW_WHITE);
        bb = FxRender.quads();
        float g = 0.2f + 0.5f * k * k;
        FxRender.planeQuad(bb, c.add(0, 0.02, 0), UP, R * 1.3f, 0f, g * 0.35f, g * 0.55f, g, g);
        FxRender.draw(bb);

        FxRender.elemShader(EL, 2.2f, 0.3f - 0.25f * k, 0.8f + 0.5f * k);
        bb = FxRender.quads();
        int seg = R > 6 ? 72 : 48;
        FxRender.ring(bb, c.add(0, 0.03, 0), UP, R * (0.92f - 0.08f * k), R * 1.04f, (0.6f + 0.4f * k) * pulse, time * 0.4f, seg);
        FxRender.ring(bb, c.add(0, 0.035, 0), UP, R * 0.48f, R * 0.55f, 0.25f + 0.5f * k, -time * 0.6f, seg);
        FxRender.draw(bb);
    }

    // =====================================================================
    // 공통 도우미
    // =====================================================================

    private static Sprite sp(Sprite.Kind kind, Vec3 p, Vec3 v, float s0, float s1, int life) {
        return new Sprite(kind, p, v, s0, s1, life).elem(EL);
    }

    private static void frameGlow(Vec3 p, float size, float r, float g, float b, float a) {
        Sprite s = new Sprite(Sprite.Kind.GLOW, p, Vec3.ZERO, size, size, 1).color(r, g, b, a).elem(EL);
        s.size1 = s.size0;
        FxManager.addFrameGlow(s);
    }

    private static void addBolt(Bolt b) {
        if (BOLTS.size() >= MAX_BOLTS) BOLTS.remove(0);
        BOLTS.add(b);
    }

    private static double distToPlayer(Vec3 p) {
        Entity pl = Minecraft.getInstance().player;
        return pl == null ? 1.0E9 : pl.position().distanceTo(p);
    }

    /**
     * 중간점 변위로 꺾인 선을 만든다. 각 단계의 변위는 그 구간 길이에 비례 (구간이 짧아질수록 작아짐).
     * @param flat true 면 위아래로는 거의 안 꺾임 (바닥을 기는 전기)
     */
    private static Vec3[] jagged(Vec3 a, Vec3 b, int depth, float jag, boolean flat) {
        int n = 1 << depth;
        Vec3[] p = new Vec3[n + 1];
        p[0] = a;
        p[n] = b;
        for (int step = n; step > 1; step >>= 1) {
            int half = step >> 1;
            for (int i = 0; i < n; i += step) {
                Vec3 p0 = p[i], p1 = p[i + step];
                double dx = p1.x - p0.x, dy = p1.y - p0.y, dz = p1.z - p0.z;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                Vec3 r = randomDir();
                double rx = r.x, ry = r.y, rz = r.z;
                if (len > 1.0E-6) {
                    double ux = dx / len, uy = dy / len, uz = dz / len;
                    double dot = rx * ux + ry * uy + rz * uz;
                    rx -= ux * dot;
                    ry -= uy * dot;
                    rz -= uz * dot;
                }
                if (flat) ry *= 0.25;
                double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
                double mag = len * jag * (0.35 + 0.65 * RNG.nextDouble());
                double s = rl > 1.0E-6 ? mag / rl : 0.0;
                p[i + half] = new Vec3((p0.x + p1.x) * 0.5 + rx * s, (p0.y + p1.y) * 0.5 + ry * s, (p0.z + p1.z) * 0.5 + rz * s);
            }
        }
        return p;
    }

    /**
     * 꺾인 선을 카메라를 향한 띠로 그린다 (구간마다 옆 방향 = 구간 방향 × 카메라 쪽).
     * 색은 미리 알파를 곱한 값 (더하기 섞기). endScale = 끝으로 갈수록 가늘어지는 비율.
     */
    private static void ribbon(BufferBuilder bb, Vec3[] pts, float w, float endScale, float r, float g, float b, float a, float vOff) {
        int n = pts.length - 1;
        if (n <= 0 || w <= 0.001f) return;
        Vec3 cam = FxRender.cam;
        float v = vOff;
        for (int i = 0; i < n; i++) {
            Vec3 p0 = pts[i], p1 = pts[i + 1];
            double dx = p1.x - p0.x, dy = p1.y - p0.y, dz = p1.z - p0.z;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0E-5) continue;
            double ux = dx / len, uy = dy / len, uz = dz / len;
            double cx = cam.x - (p0.x + p1.x) * 0.5, cy = cam.y - (p0.y + p1.y) * 0.5, cz = cam.z - (p0.z + p1.z) * 0.5;
            double sx = uy * cz - uz * cy, sy = uz * cx - ux * cz, sz = ux * cy - uy * cx;
            double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1.0E-6) continue;
            sx /= sl;
            sy /= sl;
            sz /= sl;
            float f0 = 1f - (1f - endScale) * i / n, f1 = 1f - (1f - endScale) * (i + 1) / n;
            double h0 = w * f0 * 0.5, h1 = w * f1 * 0.5;
            // 꺾이는 곳 틈이 안 보이게 앞뒤로 조금 늘림
            double ext = Math.min(len * 0.3, w * f0 * 0.2);
            double ax = p0.x - ux * ext, ay = p0.y - uy * ext, az = p0.z - uz * ext;
            double bx = p1.x + ux * ext, by = p1.y + uy * ext, bz = p1.z + uz * ext;
            float v1 = v + (float) len * 0.12f;
            FxRender.vertex(bb, ax - sx * h0, ay - sy * h0, az - sz * h0, 0f, v, r, g, b, a);
            FxRender.vertex(bb, ax + sx * h0, ay + sy * h0, az + sz * h0, 1f, v, r, g, b, a);
            FxRender.vertex(bb, bx + sx * h1, by + sy * h1, bz + sz * h1, 1f, v1, r, g, b, a);
            FxRender.vertex(bb, bx - sx * h1, by - sy * h1, bz - sz * h1, 0f, v1, r, g, b, a);
            v = v1;
        }
    }

    // =====================================================================
    // 번개 줄기 하나
    // =====================================================================

    static final class Bolt {
        /** 굵기 1 당 바깥 번짐 / 심지 폭 (블록) */
        private static final float GLOW_W = 0.85f, CORE_W = 0.22f;

        final Vec3 a, b;
        final float width, jag;
        final int life, delay, depth, branches;
        final boolean flat;
        final float gr, gg, gb;
        final List<Vec3[]> paths = new ArrayList<>(4);
        final float[] pathW;
        int age, regen;
        float flick = 1f, vOff;

        Bolt(Vec3 a, Vec3 b, float width, int life, int delay, int depth, int branches, float jag, boolean flat) {
            this.a = a;
            this.b = b;
            this.width = width;
            this.life = Math.max(1, life);
            this.delay = Math.max(0, delay);
            this.depth = Math.max(1, Math.min(7, depth));
            this.branches = Math.max(0, branches);
            this.jag = jag;
            this.flat = flat;
            this.pathW = new float[this.branches + 1];
            // 푸른색 ~ 보랏빛 사이에서 조금씩 다르게
            float violet = RNG.nextFloat();
            this.gr = 0.38f + 0.2f * violet;
            this.gg = 0.55f - 0.1f * violet;
            this.gb = 1f;
            rebuild();
            regen = 1 + RNG.nextInt(2);
        }

        void rebuild() {
            paths.clear();
            Vec3[] main = jagged(a, b, depth, jag, flat);
            paths.add(main);
            pathW[0] = 1f;
            int n = main.length - 1;
            double total = a.distanceTo(b);
            for (int k = 0; k < branches && n >= 2; k++) {
                int idx = 1 + RNG.nextInt(Math.max(1, (int) (n * 0.65f)));
                Vec3 s = main[Math.min(idx, n - 1)];
                Vec3 rem = b.subtract(s);
                double rl = rem.length();
                if (rl < 0.3) continue;
                Vec3 dir = rem.scale(1.0 / rl).add(randomDir().scale(0.9));
                if (flat) dir = new Vec3(dir.x, dir.y * 0.2, dir.z);
                if (dir.lengthSqr() < 1.0E-6) continue;
                dir = dir.normalize();
                double bl = Math.min(rl * rand(0.25f, 0.5f) + 0.3, total * 0.4 + 0.5);
                Vec3[] br = jagged(s, s.add(dir.scale(bl)), Math.max(2, depth - 1), jag * 1.15f, flat);
                paths.add(br);
                pathW[paths.size() - 1] = rand(0.35f, 0.6f) * (1f - 0.4f * idx / n);
            }
            vOff = RNG.nextFloat();
        }

        /** @return true 면 끝 */
        boolean tick() {
            age++;
            if (age < delay) return false;
            int local = age - delay;
            if (local >= life) return true;
            if (--regen <= 0) {
                rebuild();
                regen = 1 + RNG.nextInt(2);
            }
            flick = local < 2 ? 1f : (RNG.nextFloat() < 0.18f ? 0.25f : rand(0.65f, 1f));
            return false;
        }

        float alpha(float pt) {
            if (age < delay) return 0f;
            float t = (age - delay + pt) / life;
            if (t >= 1f) return 0f;
            float fade = t < 0.2f ? 1f : clamp01((1f - t) / 0.8f);
            return fade * fade * flick;
        }

        void emit(BufferBuilder bb, float pt) {
            float al = alpha(pt);
            if (al < 0.01f) return;
            for (int p = 0; p < paths.size(); p++) {
                Vec3[] pts = paths.get(p);
                float w = width * pathW[p];
                float end = p == 0 ? 0.65f : 0.1f;
                float ga = al * 0.45f;
                ribbon(bb, pts, w * GLOW_W, end, gr * ga, gg * ga, gb * ga, ga, vOff);
                float ca = al * (p == 0 ? 1f : 0.85f);
                ribbon(bb, pts, w * CORE_W, end, 0.9f * ca, 0.95f * ca, ca, ca, vOff + 0.5f);
            }
        }
    }

    // =====================================================================
    // 하늘에서 떨어지는 낙뢰 (낙뢰·뇌신 공용)
    // =====================================================================

    static final class StrikeFx {
        final Vec3 p, top;
        final float R, s;
        final boolean giant, minor;
        final int life, coreLife;
        int age;
        float flick = 1f;

        StrikeFx(Vec3 p, float R, boolean giant, boolean minor) {
            this.p = p;
            this.R = R;
            this.giant = giant;
            this.minor = minor;
            this.s = giant ? 2.2f : (minor ? 0.8f : 1f);
            this.life = giant ? 24 : 10;
            this.coreLife = giant ? 12 : 5;
            float height = giant ? 34f : (minor ? 19f : 30f);
            this.top = p.add(rand(-1f, 1f) * R * 0.8f, height, rand(-1f, 1f) * R * 0.8f);
            FxManager.lod = FxManager.lodAt(p);

            if (giant) {
                addBolt(new Bolt(p.add(0, height, 0), p, 4.5f, 14, 0, 6, 3, 0.16f, false));
                float base = RNG.nextFloat() * TAU;
                for (int k = 0; k < 3; k++) {
                    float ang = base + k * TAU / 3f + rand(-0.3f, 0.3f);
                    Vec3 t = p.add(Math.cos(ang) * R * 1.6, height - rand(0f, 4f), Math.sin(ang) * R * 1.6);
                    Vec3 e = p.add(Math.cos(ang) * R * 0.35, 0, Math.sin(ang) * R * 0.35);
                    addBolt(new Bolt(t, e, 2.2f, 12, k, 5, 1, 0.2f, false));
                }
                addBolt(new Bolt(p.add(rand(-2f, 2f), height, rand(-2f, 2f)), p, 3.0f, 10, 6, 5, 2, 0.2f, false));
            } else if (minor) {
                addBolt(new Bolt(top, p, 1.4f, 7, 0, 5, 2, 0.2f, false));
                addBolt(new Bolt(top.add(rand(-2f, 2f), 0, rand(-2f, 2f)), p, 0.6f, 5, 2, 4, 1, 0.22f, false));
            } else {
                addBolt(new Bolt(top, p, 1.7f, 8, 0, 6, 3, 0.2f, false));
                addBolt(new Bolt(top.add(rand(-3f, 3f), 0, rand(-3f, 3f)), p, 0.8f, 6, 2, 5, 1, 0.22f, false));
                addBolt(new Bolt(top, p, 1.2f, 5, 4, 5, 2, 0.2f, false));
            }

            Vec3 mid = p.add(0, 1.0, 0);
            spawn(sp(Sprite.Kind.GLOW, mid, Vec3.ZERO, 7f * s, 10f * s, 5).color(0.85f, 0.93f, 1f, 1f));
            spawn(sp(Sprite.Kind.GLOW, mid, Vec3.ZERO, R * 3f, R * 4.5f, 10).color(0.45f, 0.65f, 1f, 0.7f));
            Vec3 rc = p.add(0, 0.06, 0);
            if (giant) {
                FxManager.addRing(rc, UP, 1f, R * 5f, 18, 0, 1f, EL);
                FxManager.addRing(rc, UP, 0.6f, R * 3.4f, 14, 3, 0.9f, EL);
                FxManager.addRing(p.add(0, 3, 0), UP, 0.5f, R * 3f, 12, 1, 0.7f, EL);
                FxManager.addRing(p.add(0, 1.5, 0), UP, 0.5f, R * 4f, 16, 6, 0.6f, EL);
                if (DOMES.size() >= 16) DOMES.remove(0);
                DOMES.add(new DomeFx(p.add(0, 0.5, 0), R * 1.5f, 18, 3f));
            } else {
                FxManager.addRing(rc, UP, 0.3f, R * 2.2f, 10, 0, 1f, EL);
                FxManager.addRing(rc, UP, 0.2f, R * 1.4f, 8, 2, 0.8f, EL);
            }
            for (int i = 0; i < count(giant ? 90 : (minor ? 16 : 32)); i++) {
                Vec3 d = new Vec3(rand(-1f, 1f), rand(0.3f, 1.4f), rand(-1f, 1f)).normalize();
                spawn(sp(Sprite.Kind.SPARK, p.add(0, 0.3, 0), d.scale(rand(0.5f, 1.3f) * (giant ? 1.6f : 1f)), 0.12f, 0.12f, 8 + RNG.nextInt(9))
                        .color(0.75f, 0.88f, 1f, 1f).physics(0.045f, 0.9f));
            }
            for (int i = 0; i < count(giant ? 50 : (minor ? 5 : 12)); i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 d = new Vec3(Math.cos(ang), giant ? rand(0.1f, 0.8f) : 0.25, Math.sin(ang));
                Sprite f = sp(Sprite.Kind.FLAME, p.add(d.scale(R * 0.4)).add(0, 0.5, 0), d.scale(rand(0.12f, 0.3f) * s),
                        rand(1.2f, 2f) * s, rand(2.2f, 3f) * s, 7 + RNG.nextInt(6))
                        .color(0.7f, 0.85f, 1f, 1f).physics(-0.008f, 0.85f);
                f.frameOffset = RNG.nextInt(64);
                f.rot = RNG.nextFloat() * TAU;
                spawn(f);
            }
            SkillFx.addDecal(p, UP, R * (giant ? 1.6f : 1.15f), giant ? 260 : 160, 0.55f, 0.7f, 1f);

            double d = distToPlayer(p);
            if (giant) {
                if (d < 40) ScreenFx.flash(0xCFEFFF, 0.55f + 0.45f * (float) (1 - d / 40), 22);
                if (d < 64) CameraShake.add((float) (5.0 * (1 - d / 64)));
            } else if (d < 24) {
                float near = (float) (1 - d / 24);
                ScreenFx.flash(0xDDEEFF, (minor ? 0.15f : 0.3f) * near, 5);
                CameraShake.add((minor ? 0.9f : 1.8f) * near);
            }
        }

        boolean tick() {
            age++;
            if (age > life) return true;
            flick = RNG.nextFloat() < 0.2f ? 0.45f : rand(0.8f, 1f);
            FxManager.lod = FxManager.lodAt(p);
            if (age < (giant ? 14 : 6)) {
                // 바닥을 따라 튀어 나가는 전기
                for (int i = 0; i < count(giant ? 4 : (minor ? 1 : 2)); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    double cs = Math.cos(ang), sn = Math.sin(ang);
                    float r0 = R * rand(0f, 0.3f), r1 = R * rand(0.8f, 1.6f);
                    addBolt(new Bolt(p.add(cs * r0, 0.15, sn * r0), p.add(cs * r1, 0.12, sn * r1), 0.35f * s, 2 + RNG.nextInt(2), 0, 3, 1, 0.3f, true));
                }
            }
            for (int i = 0; i < count(giant ? 6 : 2); i++) {
                Sprite em = sp(Sprite.Kind.EMBER, p.add(rand(-R, R), 0.2, rand(-R, R)), new Vec3(0, rand(0.04f, 0.12f), 0),
                        rand(0.1f, 0.2f), 0.04f, 14 + RNG.nextInt(10)).color(0.6f, 0.82f, 1f, 1f).physics(-0.003f, 0.95f);
                em.frameOffset = RNG.nextInt(100);
                spawn(em);
            }
            if (giant && age < 12) {
                // 거대 번개 기둥을 따라 솟는 플라즈마
                for (int i = 0; i < count(5); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    Vec3 at = p.add(Math.cos(ang) * R * 0.5, rand(0f, 10f), Math.sin(ang) * R * 0.5);
                    Sprite f = sp(Sprite.Kind.FLAME, at, new Vec3(0, rand(0.15f, 0.35f), 0), rand(1.5f, 2.5f), rand(2.5f, 3.5f), 7 + RNG.nextInt(5))
                            .color(0.72f, 0.86f, 1f, 1f).physics(-0.01f, 0.9f);
                    f.frameOffset = RNG.nextInt(64);
                    spawn(f);
                }
            }
            return false;
        }

        /** 눈부신 중심 기둥 (BEAM 띠 버퍼에 함께) */
        void emitCore(BufferBuilder bb, float pt) {
            float t = (age + pt) / coreLife;
            if (t >= 1f) return;
            float a = (1f - t) * (1f - t) * flick;
            Vec3 tp = giant ? p.add(0, top.y - p.y, 0) : top;
            Vec3[] col = {p, tp};
            float w = giant ? 2.8f : 0.75f * s;
            float ga = a * 0.35f;
            ribbon(bb, col, w * 3.2f, 0.7f, 0.35f * ga, 0.5f * ga, ga, ga, 0f);
            ribbon(bb, col, w, 0.6f, a, a, a, a, 0.3f);
        }

        /** 바닥에 번지는 빛 */
        void emitGround(BufferBuilder bb, float pt) {
            float t = (age + pt) / (giant ? 18f : 8f);
            if (t >= 1f) return;
            float a = (1f - t) * (1f - t) * (0.75f + 0.25f * flick);
            FxRender.planeQuad(bb, p.add(0, 0.08, 0), UP, R * (giant ? 3.2f : 2.2f), 0f, a * 0.45f, a * 0.65f, a, a);
        }

        void frameGlows(float pt) {
            float t = (age + pt) / (giant ? 14f : 6f);
            if (t >= 1f) return;
            float a = (1f - t) * (1f - t) * flick;
            frameGlow(p.add(0, 1.0, 0), R * (giant ? 6f : 4f), 0.7f, 0.85f, 1f, a);
            if (giant) frameGlow(p.add(0, 12, 0), 26f, 0.5f, 0.65f, 1f, a * 0.6f);
        }
    }

    // =====================================================================
    // 섬광 이동 뒤 남는 전기
    // =====================================================================

    static final class BlinkFx {
        static final int LIFE = 10;
        final Vec3 a, b;
        final float R;
        int age;

        BlinkFx(Vec3 a, Vec3 b, float R) {
            this.a = a;
            this.b = b;
            this.R = R;
        }

        boolean tick() {
            age++;
            if (age > LIFE) return true;
            if (age > 8) return false;
            Vec3 mid = a.add(b).scale(0.5);
            FxManager.lod = FxManager.lodAt(mid);
            Vec3 d = b.subtract(a);
            for (int i = 0; i < Math.max(1, count(3)); i++) {
                Vec3 q = a.add(d.scale(RNG.nextDouble())).add(randomDir().scale(0.35));
                addBolt(new Bolt(q.add(randomDir().scale(0.5)), q.add(randomDir().scale(0.6)), 0.32f, 2 + RNG.nextInt(2), 0, 3, RNG.nextInt(2), 0.32f, false));
            }
            for (int i = 0; i < count(3); i++) {
                Vec3 q = a.add(d.scale(RNG.nextDouble())).add(randomDir().scale(0.5));
                spawn(sp(Sprite.Kind.SPARK, q, randomDir().scale(rand(0.08f, 0.25f)), 0.07f, 0.07f, 5 + RNG.nextInt(5))
                        .color(0.75f, 0.9f, 1f, 1f).physics(0.02f, 0.9f));
                Sprite em = sp(Sprite.Kind.EMBER, q, new Vec3(0, rand(0.01f, 0.05f), 0), rand(0.1f, 0.18f), 0.04f, 10 + RNG.nextInt(8))
                        .color(0.6f, 0.82f, 1f, 1f).physics(-0.002f, 0.95f);
                em.frameOffset = RNG.nextInt(100);
                spawn(em);
            }
            return false;
        }

        /** 지나간 길의 잔상 */
        void emit(BufferBuilder bb, float pt) {
            float t = (age + pt) / LIFE;
            if (t >= 1f) return;
            float a0 = (1f - t) * (1f - t);
            Vec3[] path = {a, b};
            float ga = a0 * 0.35f;
            ribbon(bb, path, 1.8f, 1f, 0.35f * ga, 0.5f * ga, ga, ga, 0f);
            float ca = a0 * 0.4f;
            ribbon(bb, path, 0.35f, 1f, 0.8f * ca, 0.9f * ca, ca, ca, 0.5f);
        }

        void frameGlows(float pt) {
            float t = (age + pt) / LIFE;
            if (t >= 1f) return;
            float al = (1f - t) * 0.5f;
            frameGlow(a, R * 1.6f, 0.5f, 0.7f, 1f, al);
            frameGlow(b, R * 1.6f, 0.5f, 0.7f, 1f, al);
        }
    }

    // =====================================================================
    // 전기 돔 (방전, 거대 낙뢰)
    // =====================================================================

    static final class DomeFx {
        final Vec3 c;
        final float R, expand;
        final int life;
        int age;

        DomeFx(Vec3 c, float R, int life, float expand) {
            this.c = c;
            this.R = R;
            this.life = Math.max(6, life);
            this.expand = Math.max(1f, expand);
        }

        float radius(float t) {
            return R * (0.15f + 0.85f * easeOutCubic(t / expand));
        }

        boolean tick() {
            age++;
            if (age > life) return true;
            if (age <= expand + 4) {
                // 돔 표면을 타고 튀는 전기
                FxManager.lod = FxManager.lodAt(c);
                float r = radius(age);
                for (int i = 0; i < count(4); i++) {
                    Vec3 d = randomDir();
                    if (d.y < -0.2) d = new Vec3(d.x, -d.y, d.z);
                    Vec3 s0 = c.add(d.scale(r));
                    Vec3 s1 = c.add(d.add(randomDir().scale(0.35)).normalize().scale(r));
                    addBolt(new Bolt(s0, s1, 0.4f, 2, 0, 3, 1, 0.3f, false));
                }
            }
            return false;
        }

        void render(float pt, float time) {
            float t = age + pt;
            float r = radius(t);
            float erosion = 0.05f + 0.95f * clamp01((t - expand) / (life - expand));
            float fade = 1f - clamp01((t - life * 0.6f) / (life * 0.4f));
            FxRender.elemShader(EL, 3f, erosion, 1.3f * fade + 0.15f);
            RenderSystem.disableCull();
            FxManager.BlastSphere.draw(c, r, time);
            if (t < expand + 3) {
                // 안쪽 밝은 심
                float k = 1f - clamp01(t / (expand + 3));
                FxRender.elemShader(EL, 4f, 0.1f, 1.6f * k);
                FxManager.BlastSphere.draw(c, r * 0.45f, time * 1.7f);
            }
        }
    }

    // =====================================================================
    // 5. 뇌신의 심판: 도는 뇌운 + 바닥 테두리
    // =====================================================================

    static final class StormFx {
        static final int PUFFS = 64;
        static final int FLASHES = 4;
        final Vec3 c;
        final float R, cloudR, cloudY;
        final int dur;
        final float[] pa = new float[PUFFS], pr = new float[PUFFS], ps = new float[PUFFS], py = new float[PUFFS],
                pshade = new float[PUFFS], prot = new float[PUFFS];
        final int[] pf = new int[PUFFS];
        final Vec3[] flashPos = new Vec3[FLASHES];
        final int[] flashAge = new int[FLASHES], flashLife = new int[FLASHES];
        final float[] flashSize = new float[FLASHES];
        int age;

        StormFx(Vec3 c, float R, int dur) {
            this.c = c;
            this.R = R;
            this.dur = dur;
            this.cloudR = R * 1.6f;
            this.cloudY = 18f;
            float scale = Math.max(0.6f, cloudR / 17f);
            for (int i = 0; i < PUFFS; i++) {
                pa[i] = RNG.nextFloat() * TAU;
                pr[i] = cloudR * (float) Math.sqrt(RNG.nextFloat());
                float centre = 1f - pr[i] / cloudR;
                ps[i] = rand(6f, 10f) * scale * (0.8f + 0.4f * centre);
                py[i] = rand(-1.2f, 1.2f) - centre * 1.5f;
                pshade[i] = rand(0.75f, 1.2f);
                prot[i] = RNG.nextFloat() * TAU;
                pf[i] = 18 + RNG.nextInt(20);
            }
            for (int i = 0; i < FLASHES; i++) flashAge[i] = flashLife[i] = 0;
        }

        float fade(float t) {
            float in = clamp01(t / 20f);
            float out = 1f - clamp01((t - dur) / 30f);
            return in * out;
        }

        Vec3 puffPos(int i, float t) {
            float omega = 0.025f * (1.4f - 0.7f * pr[i] / cloudR);
            float ang = pa[i] + t * omega;
            return c.add(Math.cos(ang) * pr[i], cloudY + py[i], Math.sin(ang) * pr[i]);
        }

        boolean tick() {
            age++;
            if (age > dur + 30) return true;
            FxManager.lod = FxManager.lodAt(c);
            boolean alive = age <= dur;
            float f = fade(age);

            // 구름 아래에서 소용돌이치는 연기
            for (int i = 0; i < count(alive ? 4 : 1); i++) {
                float ang = RNG.nextFloat() * TAU;
                float rr = cloudR * (float) Math.sqrt(RNG.nextFloat());
                Vec3 at = c.add(Math.cos(ang) * rr, cloudY - 1.5 + rand(-1f, 1f), Math.sin(ang) * rr);
                Vec3 v = new Vec3(-Math.sin(ang) * 0.09, rand(-0.01f, 0.01f), Math.cos(ang) * 0.09);
                Sprite sm = sp(Sprite.Kind.SMOKE, at, v, rand(5f, 8f), rand(8f, 11f), 30 + RNG.nextInt(16))
                        .color(0.13f, 0.15f, 0.21f, 0.55f * f).physics(0f, 0.98f);
                sm.rot = RNG.nextFloat() * TAU;
                spawn(sm);
            }

            // 구름 속 번쩍임 + 구름 사이 번개
            for (int i = 0; i < FLASHES; i++) if (flashAge[i] < flashLife[i]) flashAge[i]++;
            if (alive && RNG.nextFloat() < 0.4f) {
                for (int i = 0; i < FLASHES; i++) {
                    if (flashAge[i] >= flashLife[i]) {
                        flashPos[i] = puffPos(RNG.nextInt(PUFFS), age).add(0, -1.0, 0);
                        flashAge[i] = 0;
                        flashLife[i] = 3 + RNG.nextInt(4);
                        flashSize[i] = rand(9f, 15f);
                        break;
                    }
                }
                if (RNG.nextFloat() < 0.5f) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = cloudR * 0.8f * (float) Math.sqrt(RNG.nextFloat());
                    Vec3 s0 = c.add(Math.cos(ang) * rr, cloudY - 1.8, Math.sin(ang) * rr);
                    float a2 = RNG.nextFloat() * TAU;
                    float len = rand(4f, 8f);
                    Vec3 s1 = s0.add(Math.cos(a2) * len, rand(-0.5f, 0.5f), Math.sin(a2) * len);
                    addBolt(new Bolt(s0, s1, 0.6f, 4, 0, 4, 1, 0.25f, true));
                }
            }

            // 바닥 테두리를 따라 튀는 전기
            if (alive) {
                for (int i = 0; i < count(3); i++) {
                    float a0 = RNG.nextFloat() * TAU, a1 = a0 + 0.35f * (RNG.nextBoolean() ? 1 : -1);
                    Vec3 s0 = c.add(Math.cos(a0) * R, 0.2, Math.sin(a0) * R);
                    Vec3 s1 = c.add(Math.cos(a1) * R, 0.2, Math.sin(a1) * R);
                    addBolt(new Bolt(s0, s1, 0.4f, 2 + RNG.nextInt(2), 0, 3, 1, 0.28f, true));
                }
                if (RNG.nextFloat() < 0.5f) {
                    float a0 = RNG.nextFloat() * TAU;
                    Vec3 s0 = c.add(Math.cos(a0) * R, 0.1, Math.sin(a0) * R);
                    addBolt(new Bolt(s0, s0.add(rand(-0.6f, 0.6f), rand(1.5f, 3f), rand(-0.6f, 0.6f)), 0.35f, 3, 0, 3, 1, 0.3f, false));
                }
                for (int i = 0; i < count(3); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = R * (float) Math.sqrt(RNG.nextFloat());
                    Sprite em = sp(Sprite.Kind.EMBER, c.add(Math.cos(ang) * rr, 0.2, Math.sin(ang) * rr), new Vec3(0, rand(0.05f, 0.14f), 0),
                            rand(0.1f, 0.2f), 0.04f, 18 + RNG.nextInt(14)).color(0.6f, 0.82f, 1f, 1f).physics(-0.003f, 0.96f);
                    em.frameOffset = RNG.nextInt(100);
                    spawn(em);
                }
            }
            return false;
        }

        /** 반투명 단계: 도는 먹구름 원반 */
        void renderCloud(BufferBuilder bb, float pt, float time) {
            float t = age + pt;
            float f = fade(t);
            if (f <= 0.01f) return;
            for (int i = 0; i < PUFFS; i++) {
                Vec3 pos = puffPos(i, t);
                // 가까운 번쩍임이 구름을 안쪽에서 밝힘
                float lit = 0f;
                for (int j = 0; j < FLASHES; j++) {
                    if (flashAge[j] >= flashLife[j] || flashPos[j] == null) continue;
                    double d = flashPos[j].distanceTo(pos);
                    if (d < 9) lit += (float) (1 - d / 9) * (1f - (float) flashAge[j] / flashLife[j]);
                }
                lit = Math.min(1f, lit);
                float sh = pshade[i];
                float r = 0.12f * sh + 0.35f * lit, g = 0.14f * sh + 0.42f * lit, b = 0.2f * sh + 0.55f * lit;
                float size = ps[i] * (0.6f + 0.4f * clamp01(t / 20f));
                int fr = pf[i];
                float u0 = (fr & 7) / 8f, v0 = (fr >> 3) / 8f;
                FxRender.billboard(bb, pos, size, prot[i] + time * 0.004f, u0, v0, u0 + 0.125f, v0 + 0.125f, r, g, b, 0.82f * f);
            }
        }

        /** 바닥에 깔린 옅은 푸른 빛 */
        void emitGround(BufferBuilder bb, float pt) {
            float f = fade(age + pt);
            if (f <= 0.01f) return;
            float a = 0.22f * f;
            FxRender.planeQuad(bb, c.add(0, 0.07, 0), UP, R * 1.25f, 0f, a * 0.4f, a * 0.6f, a, a);
        }

        /** 바닥 테두리 고리 */
        void renderRim(float pt, float time) {
            float f = fade(age + pt);
            if (f <= 0.01f) return;
            FxRender.elemShader(EL, 2.4f, 0.2f + 0.6f * (1f - f), 0.9f * f + 0.1f);
            RenderSystem.disableCull();
            BufferBuilder bb = FxRender.quads();
            FxRender.ring(bb, c.add(0, 0.08, 0), UP, R * 0.94f, R * 1.04f, 0.85f * f, time * 0.35f, 80);
            FxRender.ring(bb, c.add(0, 0.09, 0), UP, R * 0.56f, R * 0.6f, 0.4f * f, -time * 0.5f, 56);
            FxRender.draw(bb);
        }

        void frameGlows(float pt) {
            for (int j = 0; j < FLASHES; j++) {
                if (flashAge[j] >= flashLife[j] || flashPos[j] == null) continue;
                float k = 1f - (flashAge[j] + pt) / flashLife[j];
                if (k <= 0f) continue;
                frameGlow(flashPos[j], flashSize[j], 0.55f, 0.7f, 1f, 0.75f * k);
            }
        }
    }
}
