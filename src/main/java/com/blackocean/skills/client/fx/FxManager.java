package com.blackocean.skills.client.fx;

import com.blackocean.skills.SkillsConfig;
import com.blackocean.skills.SkillsRegistry;
import com.blackocean.skills.client.FireFistTravelSound;
import com.blackocean.skills.client.SkillsClientConfig;
import com.blackocean.skills.fire.FireFistProjectile;
import com.blackocean.skills.network.FireFistImpactPayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 화염 주먹의 모든 화면 연출을 관리한다.
 * <pre>
 *  1. 준비 (ChargeFx)     : 손 주변 회전 고리 2겹 + 빨려 드는 불씨 + 점점 커지는 손 빛
 *  2. 비행 (ProjectileFx) : 3D 주먹 본체 + 겉불꽃 + 휘는 궤적 + 잔상 + 후광 + 떨어지는 불씨
 *  3. 적중 (ImpactFx)     : 섬광 → 폭발 구 → 충격파 링 2개 → 튀는 불꽃 + 화면 흔들림
 *  4. 잔류                : 떠오르는 불씨, 퍼지는 연기, 타닥타닥 소리
 * </pre>
 * 모든 연출은 클라이언트에서만 계산하며 서버와 주고받는 것은 "시전 시작", "적중" 신호 두 개뿐이다.
 */
public final class FxManager {
    static final Random RNG = new Random();
    private static final Vec3 UP = new Vec3(0, 1, 0);

    private static final List<ChargeFx> CHARGES = new ArrayList<>();
    private static final Map<Integer, ProjectileFx> PROJECTILES = new HashMap<>();
    private static final List<ImpactFx> IMPACTS = new ArrayList<>();
    private static final List<RingWave> RINGS = new ArrayList<>();
    private static final List<Sprite> SPRITES = new ArrayList<>();
    /** 이번 프레임에만 그리는 후광 (주먹·손 빛). 그리고 나서 바로 비움. */
    private static final List<Sprite> FRAME_GLOWS = new ArrayList<>();

    private static ClientLevel lastLevel;

    private FxManager() {}

    public static void clear() {
        CHARGES.clear();
        PROJECTILES.values().forEach(p -> p.stopSound());
        PROJECTILES.clear();
        IMPACTS.clear();
        RINGS.clear();
        SPRITES.clear();
        FRAME_GLOWS.clear();
        CameraShake.clear();
    }

    private static float amount() {
        return SkillsClientConfig.PARTICLE_AMOUNT.get().floatValue();
    }

    private static int count(int base) {
        float f = base * amount();
        int n = (int) f;
        return RNG.nextFloat() < f - n ? n + 1 : n;
    }

    // =====================================================================
    // 신호 처리
    // =====================================================================

    /** 내가 R 을 누른 순간 (서버 허락 전에 미리 연출 시작 → 손맛). */
    public static void startPredictedCharge(Entity self) {
        for (ChargeFx c : CHARGES) {
            if (c.caster == self && !c.done) return;
        }
        ChargeFx c = new ChargeFx(self, SkillsConfig.CHARGE_TICKS.get(), true);
        CHARGES.add(c);
        playAt(self.position(), SkillsRegistry.FIRE_FIST_CHARGE.get(), 1.4f, 1.0f);
    }

    public static void onCastStart(Entity caster, int chargeTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (caster == mc.player) {
            for (ChargeFx c : CHARGES) {
                if (c.caster == caster && c.predicted && !c.confirmed && !c.done) {
                    c.confirmed = true;
                    c.duration = chargeTicks;
                    return;
                }
            }
        }
        ChargeFx c = new ChargeFx(caster, chargeTicks, false);
        c.confirmed = true;
        CHARGES.add(c);
        playAt(caster.position(), SkillsRegistry.FIRE_FIST_CHARGE.get(), 1.4f, 1.0f);
    }

    public static void onImpact(FireFistImpactPayload p) {
        Vec3 pos = new Vec3(p.x(), p.y(), p.z());
        ProjectileFx proj = PROJECTILES.get(p.projectileId());
        if (proj != null) {
            proj.impact(pos);
        }
        Vec3 n = new Vec3(p.nx(), p.ny(), p.nz());
        boolean blockHit = p.hitEntityId() < 0 && !p.fizzle();
        // 벽에 맞으면 벽면을 따라, 그 외(바닥·생물·공중)에는 수평으로 충격파가 퍼짐
        Vec3 ringNormal = blockHit && Math.abs(n.y) < 0.5 ? n : UP;
        IMPACTS.add(new ImpactFx(pos, n, ringNormal, p.fizzle()));
    }

    // =====================================================================
    // 매 틱 (20회/초)
    // =====================================================================

    public static void tick(Minecraft mc) {
        if (mc.level != lastLevel) {
            clear();
            lastLevel = mc.level;
        }
        if (mc.level == null) return;
        CameraShake.tick();

        CHARGES.removeIf(c -> c.tick());

        // 날아가는 주먹 찾기
        for (ProjectileFx s : PROJECTILES.values()) s.seen = false;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof FireFistProjectile fist) {
                ProjectileFx s = PROJECTILES.computeIfAbsent(fist.getId(), id -> new ProjectileFx(fist));
                s.seen = true;
                if (!s.impacted) s.track(fist);
            }
        }
        Iterator<ProjectileFx> it = PROJECTILES.values().iterator();
        while (it.hasNext()) {
            ProjectileFx s = it.next();
            if (!s.seen && !s.impacted) s.impact(s.last); // 적중 신호 없이 사라짐 (멀어짐 등)
            if (s.tickAfter()) {
                s.stopSound();
                it.remove();
            }
        }

        IMPACTS.removeIf(ImpactFx::tick);
        RINGS.removeIf(r -> ++r.age >= r.delay + r.life);
        SPRITES.removeIf(Sprite::tick);
    }

    // =====================================================================
    // 그리기 (매 프레임)
    // =====================================================================

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (Minecraft.getInstance().level == null) return;
        if (CHARGES.isEmpty() && PROJECTILES.isEmpty() && IMPACTS.isEmpty() && RINGS.isEmpty() && SPRITES.isEmpty()) return;

        FxRender.begin(event);
        try {
            float pt = FxRender.partial;
            float time = (Minecraft.getInstance().level.getGameTime() % 24000L) + pt;

            // 1) 연기 (반투명 덮기) — 불 뒤에 깔리도록 먼저
            FxRender.alphaBlend();
            drawSprites(Sprite.Kind.SMOKE, pt);

            // 2) 이하 전부 더하기 섞기
            FxRender.additive();
            for (ProjectileFx s : PROJECTILES.values()) s.renderTrail(pt);
            for (ProjectileFx s : PROJECTILES.values()) s.renderBody(pt, time);
            for (ChargeFx c : CHARGES) c.render(pt, time);
            for (ImpactFx i : IMPACTS) i.render(pt);
            drawRings(pt);
            drawSprites(Sprite.Kind.FLAME, pt);
            drawSprites(Sprite.Kind.EMBER, pt);
            drawSprites(Sprite.Kind.SPARK, pt);
            drawSprites(Sprite.Kind.GLOW, pt);
        } finally {
            FRAME_GLOWS.clear();
            FxRender.end();
        }
    }

    private static void drawSprites(Sprite.Kind kind, float pt) {
        BufferBuilder bb;
        switch (kind) {
            case FLAME -> FxRender.spriteShader(FxRender.FLAME_SHEET);
            case GLOW -> FxRender.spriteShader(FxRender.GLOW);
            case SPARK -> FxRender.spriteShader(FxRender.SPARK);
            case EMBER -> FxRender.spriteShader(FxRender.EMBER);
            case SMOKE -> FxRender.spriteShader(FxRender.SMOKE);
        }
        RenderSystem.disableCull();
        bb = FxRender.quads();
        List<Sprite> list = kind == Sprite.Kind.GLOW ? concat(SPRITES, FRAME_GLOWS) : SPRITES;
        for (Sprite s : list) {
            if (s.kind != kind) continue;
            float t = s.t(pt);
            Vec3 p = s.pos(pt);
            float size = s.size(pt);
            switch (kind) {
                case FLAME -> {
                    // 64칸 그림 묶음을 넘기며 불꽃이 일렁이게
                    int f = (s.frameOffset + (int) ((s.age + pt) * 2.2f)) & 63;
                    float u0 = (f & 7) / 8f, v0 = (f >> 3) / 8f;
                    float a = Sprite.fadeInOut(t, 0.12f) * s.alpha;
                    FxRender.billboard(bb, p, size, s.rot, u0, v0, u0 + 0.125f, v0 + 0.125f, s.r * a, s.g * a, s.b * a, a);
                }
                case GLOW -> {
                    float a = (s.life <= 1 ? 1f : Sprite.fadeInOut(t, 0.15f)) * s.alpha;
                    FxRender.billboard(bb, p, size, s.rot, 0, 0, 1, 1, s.r * a, s.g * a, s.b * a, a);
                }
                case EMBER -> {
                    float flicker = 0.7f + 0.3f * (float) Math.sin((s.age + pt) * 1.7f + s.frameOffset);
                    float a = Sprite.fadeInOut(t, 0.1f) * s.alpha * flicker;
                    FxRender.billboard(bb, p, size, s.rot, 0, 0, 1, 1, s.r * a, s.g * a, s.b * a, a);
                }
                case SPARK -> {
                    float a = (1f - t) * s.alpha;
                    Vec3 tail = p.subtract(s.vel().scale(2.2));
                    FxRender.streak(bb, tail, p, size, s.r * a, s.g * a, s.b * a, a);
                }
                case SMOKE -> {
                    int f = Math.min(63, (int) (t * 63f));
                    float u0 = (f & 7) / 8f, v0 = (f >> 3) / 8f;
                    float a = Sprite.fadeInOut(t, 0.2f) * s.alpha;
                    FxRender.billboard(bb, p, size, s.rot, u0, v0, u0 + 0.125f, v0 + 0.125f, s.r, s.g, s.b, a);
                }
            }
        }
        FxRender.draw(bb);
    }

    private static List<Sprite> concat(List<Sprite> a, List<Sprite> b) {
        if (b.isEmpty()) return a;
        List<Sprite> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    private static void drawRings(float pt) {
        if (RINGS.isEmpty()) return;
        FxRender.spriteShader(FxRender.SHOCK_RING);
        RenderSystem.disableCull();
        BufferBuilder bb = FxRender.quads();
        for (RingWave r : RINGS) {
            float local = r.age + pt - r.delay;
            if (local < 0) continue;
            float t = Math.min(1f, local / r.life);
            float e = 1f - (1f - t) * (1f - t) * (1f - t); // 빠르게 퍼졌다가 느려짐
            float radius = r.r0 + (r.r1 - r.r0) * e;
            float a = (1f - t) * (1f - t) * r.alpha;
            FxRender.planeQuad(bb, r.center, r.normal, radius, r.rot, a, a * 0.5f, a * 0.18f, a);
        }
        FxRender.draw(bb);
    }

    // =====================================================================
    // 도우미
    // =====================================================================

    static void playAt(Vec3 p, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            level.playLocalSound(p.x, p.y, p.z, sound, SoundSource.PLAYERS, volume, pitch, false);
        }
    }

    static Vec3 randomDir() {
        double u = RNG.nextDouble() * 2 - 1, th = RNG.nextDouble() * Math.PI * 2;
        double s = Math.sqrt(1 - u * u);
        return new Vec3(s * Math.cos(th), u, s * Math.sin(th));
    }

    static float rand(float a, float b) {
        return a + RNG.nextFloat() * (b - a);
    }

    static float easeOutCubic(float t) {
        float u = 1f - Math.min(1f, Math.max(0f, t));
        return 1f - u * u * u;
    }

    /** 메시를 화염 셰이더로 그린다. heat 는 정점별 열기 계산 방식. */
    static void drawMesh(ObjMesh mesh, Vec3 center, Vec3 dir, float scale, HeatFn heat, float uvShift) {
        if (mesh.count == 0) return;
        Quaternionf q = new Quaternionf().rotationTo(0f, 0f, 1f, (float) dir.x, (float) dir.y, (float) dir.z);
        float ox = (float) (center.x - FxRender.cam.x);
        float oy = (float) (center.y - FxRender.cam.y);
        float oz = (float) (center.z - FxRender.cam.z);
        Vector3f p = new Vector3f(), n = new Vector3f();
        BufferBuilder bb = FxRender.triangles();
        for (int i = 0; i < mesh.count; i++) {
            float lx = mesh.pos[i * 3], ly = mesh.pos[i * 3 + 1], lz = mesh.pos[i * 3 + 2];
            p.set(lx * scale, ly * scale, lz * scale);
            q.transform(p);
            p.add(ox, oy, oz);
            n.set(mesh.nrm[i * 3], mesh.nrm[i * 3 + 1], mesh.nrm[i * 3 + 2]);
            q.transform(n);
            float len = p.length();
            // facing: 1 = 카메라를 정면으로 봄(중심), 0 = 옆면(윤곽)
            float facing = len > 1.0E-4f ? Math.abs(n.dot(p)) / len : 1f;
            float h = Math.max(0f, Math.min(1f, heat.apply(facing, lz)));
            bb.addVertex(p.x, p.y, p.z).setUv(mesh.uv[i * 2] + uvShift, mesh.uv[i * 2 + 1]).setColor(1f, 1f, 1f, h);
        }
        FxRender.draw(bb);
    }

    @FunctionalInterface
    interface HeatFn {
        float apply(float facing, float localZ);
    }

    // =====================================================================
    // 1. 준비 동작
    // =====================================================================

    static final class ChargeFx {
        final Entity caster;
        final boolean predicted;
        boolean confirmed;
        boolean done;
        int duration;
        int age;

        ChargeFx(Entity caster, int duration, boolean predicted) {
            this.caster = caster;
            this.duration = Math.max(1, duration);
            this.predicted = predicted;
        }

        /** @return true 면 끝 */
        boolean tick() {
            if (done) return true;
            age++;
            if (caster.isRemoved()) return true;
            // 서버가 거절하면(쿨타임 등) 미리 시작한 연출을 지운다
            if (predicted && !confirmed && age > duration + 6) return true;

            Vec3 hand = handPos(1f);
            // 손으로 빨려 드는 불씨
            for (int i = 0; i < count(4); i++) {
                Vec3 off = randomDir().scale(rand(1.3f, 2.0f));
                Vec3 start = hand.add(off);
                Sprite s = new Sprite(Sprite.Kind.EMBER, start, off.scale(-1.0 / 5.0), 0.16f, 0.06f, 5)
                        .color(1f, 0.75f, 0.35f, 1f);
                s.rot = RNG.nextFloat() * 6.28f;
                SPRITES.add(s);
            }
            if (age % 2 == 0) {
                for (int i = 0; i < count(1); i++) {
                    Sprite f = new Sprite(Sprite.Kind.FLAME, hand.add(randomDir().scale(0.25)), new Vec3(0, 0.04, 0), 0.5f, 0.9f, 6)
                            .color(1f, 1f, 1f, 0.8f);
                    f.frameOffset = RNG.nextInt(64);
                    SPRITES.add(f);
                }
            }

            if (age >= duration && (confirmed || !predicted)) {
                launchBurst(hand, caster.getViewVector(1f));
                done = true;
                return true;
            }
            return false;
        }

        private void launchBurst(Vec3 hand, Vec3 look) {
            SPRITES.add(new Sprite(Sprite.Kind.GLOW, hand, Vec3.ZERO, 3.4f, 4.2f, 4).color(1f, 0.85f, 0.55f, 1f));
            RINGS.add(new RingWave(hand.add(look.scale(0.6)), look, 0.3f, 2.8f, 6, 0, 0.9f));
            for (int i = 0; i < count(10); i++) {
                Vec3 v = look.scale(rand(0.3f, 0.7f)).add(randomDir().scale(0.25));
                SPRITES.add(new Sprite(Sprite.Kind.SPARK, hand, v, 0.09f, 0.09f, 6 + RNG.nextInt(4))
                        .color(1f, 0.8f, 0.4f, 1f).physics(0.03f, 0.88f));
            }
            Minecraft mc = Minecraft.getInstance();
            if (caster == mc.player) CameraShake.add(0.7f);
        }

        Vec3 handPos(float pt) {
            Minecraft mc = Minecraft.getInstance();
            Vec3 eye = caster.getEyePosition(pt);
            Vec3 look = caster.getViewVector(pt);
            Vec3 right = look.cross(UP);
            right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
            boolean firstPerson = caster == mc.player && mc.options.getCameraType().isFirstPerson();
            double fwd = firstPerson ? 1.1 : 0.75;
            return eye.add(look.scale(fwd)).add(right.scale(0.38)).add(0, -0.38, 0);
        }

        void render(float pt, float time) {
            if (done) return;
            Minecraft mc = Minecraft.getInstance();
            boolean firstPerson = caster == mc.player && mc.options.getCameraType().isFirstPerson();
            float k = Math.min(1f, (age + pt) / duration);
            float s = (firstPerson ? 0.7f : 1f) * (0.4f + 0.6f * easeOutCubic(k * 1.6f));
            Vec3 hand = handPos(pt);
            Vec3 look = caster.getViewVector(pt);

            FxRender.fireShader(1.2f, 0.08f, 0.95f);
            RenderSystem.disableCull();
            BufferBuilder bb = FxRender.quads();
            FxRender.ring(bb, hand.add(look.scale(0.1)), look, 0.36f * s, 0.5f * s, 0.95f, time * 0.4f, 40);
            FxRender.ring(bb, hand.subtract(look.scale(0.18)), look, 0.62f * s, 0.74f * s, 0.75f, -time * 0.3f, 48);
            FxRender.draw(bb);

            Sprite glow = new Sprite(Sprite.Kind.GLOW, hand, Vec3.ZERO, (0.7f + 1.6f * k) * s, 0, 1)
                    .color(1f, 0.6f, 0.25f, 0.45f + 0.45f * k);
            glow.size1 = glow.size0;
            FRAME_GLOWS.add(glow);
        }
    }

    // =====================================================================
    // 2. 비행
    // =====================================================================

    static final class ProjectileFx {
        Entity entity;
        final ArrayDeque<Vec3> trail = new ArrayDeque<>(); // 최근 위치, 앞이 최신
        Vec3 dir = new Vec3(0, 0, 1);
        Vec3 last;
        boolean seen;
        boolean impacted;
        Vec3 impactPos;
        int age;
        int afterTicks;
        FireFistTravelSound sound;

        ProjectileFx(FireFistProjectile e) {
            this.entity = e;
            this.last = e.position();
            Vec3 v = e.getDeltaMovement();
            if (v.lengthSqr() > 1.0E-6) dir = v.normalize();
            sound = new FireFistTravelSound(e);
            Minecraft.getInstance().getSoundManager().play(sound);
        }

        void track(FireFistProjectile e) {
            Vec3 p = e.position();
            Vec3 d = p.subtract(last);
            if (d.lengthSqr() > 1.0E-4) dir = d.normalize();
            last = p;
            trail.addFirst(p);
            while (trail.size() > 12) trail.removeLast();
            age++;

            // 꼬리에서 떨어지는 불꽃·불씨·연기
            Vec3 back = dir.scale(-1);
            for (int i = 0; i < count(2); i++) {
                Vec3 at = p.add(back.scale(rand(0.8f, 1.6f))).add(randomDir().scale(0.35));
                Sprite f = new Sprite(Sprite.Kind.FLAME, at, back.scale(0.06).add(0, 0.03, 0), 1.3f, 0.45f, 7 + RNG.nextInt(4));
                f.frameOffset = RNG.nextInt(64);
                SPRITES.add(f);
            }
            for (int i = 0; i < count(2); i++) {
                Vec3 at = p.add(randomDir().scale(0.8));
                Sprite em = new Sprite(Sprite.Kind.EMBER, at, back.scale(0.1).add(randomDir().scale(0.03)), 0.14f, 0.05f, 14 + RNG.nextInt(10))
                        .color(1f, 0.6f, 0.25f, 1f).physics(-0.003f, 0.94f);
                em.sway = 0.004f;
                em.frameOffset = RNG.nextInt(100);
                SPRITES.add(em);
            }
            if (RNG.nextFloat() < 0.5f * amount()) {
                SPRITES.add(new Sprite(Sprite.Kind.SPARK, p, back.scale(0.3).add(randomDir().scale(0.15)), 0.08f, 0.08f, 5 + RNG.nextInt(4))
                        .color(1f, 0.75f, 0.35f, 1f).physics(0.03f, 0.9f));
            }
            if (age % 2 == 0 && RNG.nextFloat() < amount()) {
                Sprite sm = new Sprite(Sprite.Kind.SMOKE, p.add(back.scale(1.8)), new Vec3(0, 0.03, 0), 1.0f, 2.4f, 16)
                        .color(0.25f, 0.22f, 0.2f, 0.32f).physics(0f, 0.96f);
                sm.rot = RNG.nextFloat() * 6.28f;
                SPRITES.add(sm);
            }
        }

        void impact(Vec3 pos) {
            if (impacted) return;
            impacted = true;
            impactPos = pos;
            trail.addFirst(pos);
            entity = null;
            if (sound != null) sound.fadeOut();
        }

        /** @return true 면 지워도 됨 */
        boolean tickAfter() {
            if (!impacted) return false;
            afterTicks++;
            if (!trail.isEmpty()) trail.removeLast(); // 궤적 꼬리가 적중 지점 쪽으로 줄어듦
            return afterTicks > 12;
        }

        void stopSound() {
            if (sound != null) sound.fadeOut();
        }

        private Vec3 head(float pt) {
            if (impacted || entity == null) return impactPos != null ? impactPos : last;
            return entity.getPosition(pt);
        }

        void renderTrail(float pt) {
            if (trail.size() < 2) return;
            List<Vec3> pts = new ArrayList<>();
            pts.add(head(pt));
            int i = 0;
            for (Vec3 v : trail) {
                if (i++ == 0 && !impacted) continue; // 가장 최신 점은 보간된 머리보다 앞이라 건너뜀
                pts.add(v);
            }
            if (pts.size() < 2) return;
            float fade = impacted ? Math.max(0f, 1f - (afterTicks + pt) / 8f) : 1f;
            if (fade <= 0f) return;

            FxRender.fireShader(2.2f, 0.22f, 1.2f);
            RenderSystem.disableCull();
            BufferBuilder bb = FxRender.quads();
            double along = 0;
            int n = pts.size();
            for (int k = 0; k < n - 1; k++) {
                Vec3 a = pts.get(k), b = pts.get(k + 1);
                double segLen = a.distanceTo(b);
                float t0 = (float) k / (n - 1), t1 = (float) (k + 1) / (n - 1);
                Vec3 sa = side(a, b, width(t0)), sb = side(a, b, width(t1));
                // 머리 쪽은 0에서 시작해 빠르게 밝아짐 → 주먹 가운데에 궤적 경계선이 생기지 않음
                float h0 = (float) Math.pow(1f - t0, 1.2) * smooth(t0 / 0.12f) * fade;
                float h1 = (float) Math.pow(1f - t1, 1.2) * smooth(t1 / 0.12f) * fade;
                float v0 = (float) (along * 0.22), v1 = (float) ((along + segLen) * 0.22);
                // 왼쪽 반 (가장자리 → 중심)
                FxRender.vertex(bb, a.x - sa.x, a.y - sa.y, a.z - sa.z, 0f, v0, 1, 1, 1, 0f);
                FxRender.vertex(bb, a.x, a.y, a.z, 0.5f, v0, 1, 1, 1, h0);
                FxRender.vertex(bb, b.x, b.y, b.z, 0.5f, v1, 1, 1, 1, h1);
                FxRender.vertex(bb, b.x - sb.x, b.y - sb.y, b.z - sb.z, 0f, v1, 1, 1, 1, 0f);
                // 오른쪽 반 (중심 → 가장자리)
                FxRender.vertex(bb, a.x, a.y, a.z, 0.5f, v0, 1, 1, 1, h0);
                FxRender.vertex(bb, a.x + sa.x, a.y + sa.y, a.z + sa.z, 1f, v0, 1, 1, 1, 0f);
                FxRender.vertex(bb, b.x + sb.x, b.y + sb.y, b.z + sb.z, 1f, v1, 1, 1, 1, 0f);
                FxRender.vertex(bb, b.x, b.y, b.z, 0.5f, v1, 1, 1, 1, h1);
                along += segLen;
            }
            FxRender.draw(bb);
        }

        private static float width(float t) {
            return 1.8f * (float) Math.pow(1f - t, 0.7) + 0.12f;
        }

        private static Vec3 side(Vec3 a, Vec3 b, float w) {
            Vec3 d = b.subtract(a);
            Vec3 toCam = FxRender.cam.subtract(a);
            Vec3 s = d.cross(toCam);
            if (s.lengthSqr() < 1.0E-8) return Vec3.ZERO;
            return s.normalize().scale(w * 0.5);
        }

        void renderBody(float pt, float time) {
            if (impacted || entity == null) return;
            Vec3 c = head(pt);
            float grow = Math.min(1f, (age + pt) / 2.5f);
            float scale = 1.3f * (0.55f + 0.45f * grow) * (1f + 0.04f * (float) Math.sin(time * 1.9f));
            ObjMesh core = ObjMesh.get("fist_core");
            ObjMesh shell = ObjMesh.get("fist_shell");

            // 잔상: 최근 위치들에 점점 흐려지고 더 많이 타 버린 주먹을 남긴다
            if (SkillsClientConfig.AFTERIMAGES.get()) {
                int k = 0;
                for (Vec3 p : trail) {
                    if (k > 3) break;
                    if (k > 0) {
                        float fade = 0.42f - 0.12f * k;
                        FxRender.fireShader(0.9f, 0.35f + 0.14f * k, fade);
                        RenderSystem.enableCull();
                        drawMesh(core, p, dir, scale * (1f + 0.06f * k), (facing, z) -> 0.2f + 0.4f * facing, k * 0.13f);
                    }
                    k++;
                }
            }

            // 본체: 정면은 흰빛, 윤곽으로 갈수록 주황·빨강
            FxRender.fireShader(0.9f, 0.05f, 1.0f);
            RenderSystem.enableCull();
            drawMesh(core, c, dir, scale, (facing, z) -> (0.22f + 0.5f * facing) * clamp01((z + 1.35f) / 0.95f), 0f);

            // 겉불꽃: 윤곽이 밝은 테두리 + 뒤로 뻗는 불꽃 혀, 노이즈로 바깥이 계속 갉아 먹힘
            FxRender.fireShader(1.7f, 0.38f, 0.95f);
            RenderSystem.disableCull();
            drawMesh(shell, c, dir, scale, (facing, z) ->
                    (0.1f + 0.75f * (float) Math.pow(1f - facing, 1.5)) * clamp01((z + 2.3f) / 1.6f), 0.5f);

            // 후광 (빛 번짐 흉내): 주먹 뒤에 크게 퍼지는 부드러운 빛
            Sprite halo = new Sprite(Sprite.Kind.GLOW, c, Vec3.ZERO, 5.5f * scale / 1.3f, 0, 1)
                    .color(1f, 0.55f, 0.2f, 0.26f + 0.04f * (float) Math.sin(time * 3.1f));
            halo.size1 = halo.size0;
            FRAME_GLOWS.add(halo);
        }
    }

    static float smooth(float t) {
        t = clamp01(t);
        return t * t * (3f - 2f * t);
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // =====================================================================
    // 3·4. 적중과 잔류
    // =====================================================================

    static final class ImpactFx {
        final Vec3 pos;
        final Vec3 normal;
        final boolean fizzle;
        int age;

        ImpactFx(Vec3 pos, Vec3 normal, Vec3 ringNormal, boolean fizzle) {
            this.pos = pos;
            this.normal = normal;
            this.fizzle = fizzle;
            float big = fizzle ? 0.45f : 1f;

            // 섬광 (중심 흰빛 + 넓은 주황 번짐)
            SPRITES.add(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, 7.5f * big, 9f * big, 5).color(1f, 0.95f, 0.8f, 1f));
            SPRITES.add(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, 4f * big, 6f * big, 11).color(1f, 0.5f, 0.15f, 0.8f));

            // 충격파 링 2개 (빠르고 큰 것 + 느리고 작은 것)
            Vec3 rc = pos.add(ringNormal.scale(0.08));
            if (!fizzle) {
                RINGS.add(new RingWave(rc, ringNormal, 0.5f, 7.5f, 9, 0, 1f));
                RINGS.add(new RingWave(rc, ringNormal, 0.4f, 4.2f, 7, 1, 0.75f));
            } else {
                RINGS.add(new RingWave(rc, ringNormal, 0.3f, 2.2f, 6, 0, 0.6f));
            }

            // 바깥으로 터지는 불꽃 덩어리
            for (int i = 0; i < count(fizzle ? 6 : 16); i++) {
                Vec3 d = randomDir().add(0, 0.35, 0).normalize();
                Sprite f = new Sprite(Sprite.Kind.FLAME, pos.add(d.scale(0.4)), d.scale(rand(0.16f, 0.34f) * big),
                        rand(1.4f, 2.0f) * big, rand(2.0f, 2.8f) * big, 10 + RNG.nextInt(7)).physics(-0.008f, 0.82f);
                f.frameOffset = RNG.nextInt(64);
                SPRITES.add(f);
            }
            // 사방으로 튀는 불꽃 줄기 (맞은 면 바깥쪽으로)
            for (int i = 0; i < count(fizzle ? 10 : 36); i++) {
                Vec3 d = randomDir();
                if (d.dot(normal) < 0) d = d.subtract(normal.scale(2 * d.dot(normal)));
                SPRITES.add(new Sprite(Sprite.Kind.SPARK, pos, d.scale(rand(0.55f, 1.25f) * big), 0.12f, 0.12f, 8 + RNG.nextInt(9))
                        .color(1f, 0.82f, 0.45f, 1f).physics(0.045f, 0.9f));
            }
            // 잔류: 천천히 떠오르다 꺼지는 불씨
            for (int i = 0; i < count(fizzle ? 8 : 28); i++) {
                Vec3 at = pos.add(randomDir().scale(rand(0.2f, 2.4f) * big));
                Sprite em = new Sprite(Sprite.Kind.EMBER, at,
                        new Vec3(rand(-0.03f, 0.03f), rand(0.02f, 0.06f), rand(-0.03f, 0.03f)),
                        rand(0.12f, 0.2f), 0.04f, 24 + RNG.nextInt(21))
                        .color(1f, rand(0.45f, 0.7f), 0.2f, 1f).physics(-0.002f, 0.96f);
                em.sway = 0.003f;
                em.frameOffset = RNG.nextInt(100);
                SPRITES.add(em);
            }
            // 잔류: 퍼지며 옅어지는 연기
            for (int i = 0; i < count(fizzle ? 3 : 10); i++) {
                Vec3 d = randomDir();
                Vec3 at = pos.add(d.scale(rand(0.2f, 1.2f) * big));
                Sprite sm = new Sprite(Sprite.Kind.SMOKE, at, new Vec3(d.x * 0.03, rand(0.02f, 0.05f), d.z * 0.03),
                        rand(1.4f, 2.0f) * big, rand(3.4f, 4.4f) * big, 26 + RNG.nextInt(13))
                        .color(0.2f, 0.18f, 0.17f, 0.55f).physics(0f, 0.95f);
                sm.rot = RNG.nextFloat() * 6.28f;
                SPRITES.add(sm);
            }

            Minecraft mc = Minecraft.getInstance();
            if (!fizzle && mc.player != null) {
                double d = mc.player.position().distanceTo(pos);
                if (d < 14) CameraShake.add((float) (2.2 * (1 - d / 14)));
            }
        }

        /** @return true 면 끝 */
        boolean tick() {
            age++;
            if (age == 3 && !fizzle) {
                playAt(pos, SkillsRegistry.FIRE_FIST_CRACKLE.get(), 1.6f, 0.9f + RNG.nextFloat() * 0.2f);
            }
            return age > 16;
        }

        void render(float pt) {
            // 폭발 구: 빠르게 부풀었다가 노이즈 모양으로 타서 사라짐
            float t = age + pt;
            if (t > 15f) return;
            float big = fizzle ? 0.45f : 1f;
            float radius = (0.6f + 2.6f * easeOutCubic(t / 7f)) * big;
            float erosion = t < 4f ? 0f : Math.min(1f, (t - 4f) / 10f);
            float intensity = 1.05f - 0.4f * Math.min(1f, t / 12f);
            FxRender.fireShader(1.4f, erosion, intensity);
            RenderSystem.disableCull();
            BlastSphere.draw(pos, radius, t);
        }
    }

    /** 바닥이나 벽을 따라 퍼지는 충격파 링. */
    static final class RingWave {
        final Vec3 center, normal;
        final float r0, r1, alpha, rot;
        final int life, delay;
        int age;

        RingWave(Vec3 center, Vec3 normal, float r0, float r1, int life, int delay, float alpha) {
            this.center = center;
            this.normal = normal.normalize();
            this.r0 = r0;
            this.r1 = r1;
            this.life = life;
            this.delay = delay;
            this.alpha = alpha;
            this.rot = RNG.nextFloat() * 6.28f;
        }
    }

    /** 폭발 구 메시 (코드로 만든 둥근 구, 이음새 없는 UV). */
    static final class BlastSphere {
        private static final int LAT = 14, LON = 24;
        private static float[] verts; // 삼각형으로 풀어 둔 단위 구: x,y,z,u,v

        private static float[] build() {
            List<float[]> tris = new ArrayList<>();
            for (int i = 0; i < LAT; i++) {
                for (int j = 0; j < LON; j++) {
                    float[] a = pt(i, j), b = pt(i + 1, j), c = pt(i + 1, j + 1), d = pt(i, j + 1);
                    tris.add(a); tris.add(b); tris.add(c);
                    tris.add(a); tris.add(c); tris.add(d);
                }
            }
            float[] out = new float[tris.size() * 5];
            for (int k = 0; k < tris.size(); k++) System.arraycopy(tris.get(k), 0, out, k * 5, 5);
            return out;
        }

        private static float[] pt(int i, int j) {
            double th = Math.PI * i / LAT, ph = Math.PI * 2 * j / LON;
            // u 는 0~2 (노이즈가 1마다 반복되므로 끝과 처음이 이어짐)
            return new float[]{(float) (Math.sin(th) * Math.cos(ph)), (float) Math.cos(th), (float) (Math.sin(th) * Math.sin(ph)),
                    (float) j / LON * 2f, (float) i / LAT};
        }

        static void draw(Vec3 c, float radius, float time) {
            if (verts == null) verts = build();
            float ox = (float) (c.x - FxRender.cam.x), oy = (float) (c.y - FxRender.cam.y), oz = (float) (c.z - FxRender.cam.z);
            BufferBuilder bb = FxRender.triangles();
            for (int k = 0; k < verts.length; k += 5) {
                float nx = verts[k], ny = verts[k + 1], nz = verts[k + 2];
                // 울퉁불퉁한 불덩어리: 방향마다 반지름을 조금씩 다르게 (시간에 따라 꿈틀거림)
                double th = Math.acos(Math.max(-1f, Math.min(1f, ny))), ph = Math.atan2(nz, nx);
                float lump = 1f + 0.13f * (float) (Math.sin(3 * ph + 1.3 + time * 0.15) * Math.sin(4 * th))
                        + 0.09f * (float) Math.sin(5 * ph + 2 * th + 0.7 - time * 0.1);
                float rr = radius * lump;
                float x = ox + nx * rr, y = oy + ny * rr, z = oz + nz * rr;
                float len = (float) Math.sqrt(x * x + y * y + z * z);
                float facing = len > 1.0E-4f ? Math.abs(nx * x + ny * y + nz * z) / len : 1f;
                // 구 아래쪽은 바닥에 눌려 조금 어둡게
                float heat = (0.12f + 0.6f * facing) * (0.75f + 0.25f * (ny * 0.5f + 0.5f));
                bb.addVertex(x, y, z).setUv(verts[k + 3], verts[k + 4]).setColor(1f, 1f, 1f, heat);
            }
            FxRender.draw(bb);
        }
    }
}
