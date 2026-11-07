package com.blackocean.skills.client.fx;

import com.blackocean.skills.Element;
import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillAim;
import com.blackocean.skills.SubLevels;
import com.blackocean.skills.network.SkillEventPayload;
import com.blackocean.skills.network.SkillEvents;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
import static com.blackocean.skills.client.fx.FxManager.smooth;
import static com.blackocean.skills.client.fx.FxManager.spawn;

/**
 * 물 능력 연출.
 *
 * <h2>2.3 디자인 (물 = 무거운 액체 덩어리: 물판·관·파도 벽 + 흰 거품 테 + 중력으로 떨어지는 물방울 /
 * 떨어지고 튀며 사라지고 젖은 바닥에 물결 / 짙은 파랑 몸통, 흰 거품)</h2>
 * 위계: 주 형태 = 가장 밝고 대비 큼 → 보조 층 40~60 % → 적중 = 짧은 정점(4틱 이하) → 잔류 = 옅게, 테두리 선 없음.
 * <pre>
 * 1 물 구슬 (가벼움)
 *   실루엣  : 손바닥만 한 일렁이는 물 구 하나 + 흰 테.
 *   주 형태 : 3D 물 구(구 메시) + 카메라를 향한 거품 테 + 비행 축을 감는 기울어진 고리.
 *   보조    : 떨어지는 물방울 꼬리, 옅은 물안개.
 *   적중    : 물보라 왕관 + 피해 반지름 r 에서 멈추는 고리 (6틱, 정점 2틱) + 젖은 자국.
 *   사라짐  : 물방울이 포물선으로 떨어지고 바닥에 물결 2~3개 (테두리 없음).
 *   경중    : 준비 5틱, 짧은 섬광·약한 흔들림. 투사체라 위험 범위 표시 없음 (조준 표시가 길을 보여 줌).
 * 2 수류 분사 (가벼움·지속)
 *   실루엣  : 손에서 곧게 뻗는 굵은 물기둥.
 *   주 형태 : 축을 따라 꼬인 3D 관 2겹(바깥 + 반대로 꼬인 흰 심) — 어느 각도에서도 둥근 관,
 *             + 카메라를 향한 빛 띠 3겹 (옆에서 보면 굵은 광택).
 *   보조    : 노즐 고리·분무, 줄기에서 튀어 나가는 물방울.
 *   적중    : 끝점에서 계속 튀는 물보라 + 맥동 고리, 땅을 파냄.
 *   위험    : 분사 중 dangerLane(손 → 끝점, 반폭 = 굵기 r) — 실시간 조준을 그대로 따라감.
 *   사라짐  : 5틱 동안 관이 가늘어지며 깎여 사라짐, 물방울은 떨어짐.
 * 3 물의 감옥 (무거움)
 *   실루엣  : 떠 있는 커다란 물방울(구) + 바닥 소용돌이.
 *   주 형태 : 윤곽이 밝고 가운데가 투명한 물 구 (직접 만든 구 메시, 정면 면 밝기를 크게 낮춤 → 갇힌 상대가 보임).
 *   보조    : 안쪽 나선 물줄기 3, 오르는 기포, 기울어진 물결 띠 2, 바닥 나선 팔.
 *   적중    : 끝 틱 파열: 흰 섬광 + 고리가 끌어당김 반지름 r × 1.4 에서 멈춤 + 물기둥.
 *   위험    : 준비 중 dangerZone(r × 1.4, 진행도) → 유지 중 dangerZone(r × 1.4, 파열까지 차오름).
 *   사라짐  : 하늘로 튄 물이 24틱 비처럼 떨어짐, 젖은 자국 (테두리 없음).
 *   경중    : 10틱 바닥 표시 → 6틱에 구가 차오름 → 끝 6틱 부풀어 오름(예고) → 한 번에 터짐.
 * 4 해일 (무거움)
 *   실루엣  : 앞머리가 말린 파도 벽 (옆에서 보면 갈고리 모양 단면).
 *   주 형태 : 단면 9줄 × 폭 17칸의 3D 격자 면 (앞·옆·뒤에서 각각 벽·단면·물판으로 읽힘) + 마루의 흰 거품 띠.
 *   보조    : 마루의 물보라 커튼, 발밑 물튀김, 앞머리가 밀어내는 흙, 지나간 자리 젖은 자국.
 *   적중    : 끝에서 부서짐: 왕관 5개 + 앞으로 쏟아지는 물방울.
 *   위험    : 준비 중·진행 중 dangerLane(출발 → 끝, 반폭 = 서버 폭), 진행도만큼 차오름.
 *   사라짐  : 말린 끝이 앞으로 쏟아지며 6틱에 무너짐, 물결 3개 (테두리 없음).
 *   경중    : 8틱 동안 발 앞에 물벽이 일어남 → 느리게 밀려감(0.9칸/틱).
 * 5 해룡 강림 (궁극기, 가장 무거움)
 *   실루엣  : 나선으로 솟았다 내리꽂히는 물의 용 (뿔·눈·지느러미).
 *   주 형태 : 경로를 따르는 3D 관 몸통(28마디, 평행 이동 틀이라 꺾여도 안 뒤틀림) + 구슬 마디 + 빛나는 심.
 *   보조    : 몸에서 떨어지는 물방울·안개, 꼭대기 포효 고리, 하늘이 어두워짐.
 *   적중    : 충돌 틱: 거대 물기둥 + 피해 반지름 r 에서 멈추는 고리 + 둥근 해일 벽 + 화면 번쩍(제한됨).
 *   위험    : 준비 중 dangerZone(r, 진행도) → 비행 중 dangerZone(r, 충돌까지 차오름)
 *             → 소용돌이 동안 dangerZone(r, 서버가 끌어당기는 10틱 주기마다 차오름).
 *   사라짐  : 넓게 쏟아지는 물비 36틱, 젖은 자국·물결 (테두리 없음).
 *   경중    : 24틱 준비(발밑 물기둥, 땅울림) → 26틱 비행 → 충돌 → 60틱 소용돌이.
 * </pre>
 * <pre>
 *  1 물 구슬   : 일렁이는 물 구 + 카메라를 향한 테두리 링 + 후광, 물방울·물안개 꼬리 / 적중 시 물보라 왕관·물방울·안개·링·젖은 자국
 *  2 수류 분사 : 손에서 뻗는 고압 물줄기 (꼬인 관 2겹 + 빛 띠 3겹), 노즐 분무, 끝점에서 계속 튀는 물보라
 *  3 물의 감옥 : 떠 있는 물 구 + 안쪽 나선 물줄기 + 오르는 기포 + 바닥 소용돌이, 끝에 격하게 터짐
 *  4 해일      : 앞으로 밀려가며 앞머리가 말리는 파도 벽, 마루의 물보라, 발밑 물튀김, 젖은 자국, 끝에 부서짐
 *  5 해룡 강림 : 나선으로 솟아올랐다 내리꽂히는 물의 용 (몸통 관 + 구슬 마디 + 빛 띠 + 눈·뿔·지느러미),
 *               충돌 시 거대 물기둥·충격파·화면 번쩍·흔들림, 이어서 소용돌이
 * </pre>
 * 색: 흰빛 → 청록 → 하늘 → 짙은 파랑.
 * 무거운 순간에는 땅이 뜯겨 나감 (DebrisFx: 물 구슬 적중, 감옥 파열, 해일 앞머리가 밀어내는 흙, 해룡 충돌 구덩이),
 * 끝난 뒤에도 물결 고리·떨어지는 물방울·젖은 자국이 남음.
 * <pre>
 * 단계 (기본 설정값, 틱 = 1/20초)
 *  구슬   : 5틱, 손(서버 손 0.3)에 물 구 → 손에서 물 고리 → 1.8칸/틱 17틱 비행 → 물보라 고리가 피해 반지름 r 에서 멈춤 → 물결 2~3개
 *  분사   : 4틱, 손에 소용돌이 → 손에서 2틱 만에 끝까지 뻗음 → 32틱 동안 실시간 조준(서버와 같은 계산) → 5틱 페이드
 *  감옥   : 10틱 바닥 원 + 물벽(r×1.4 = 실제로 끌려 드는 원통) → 60틱 유지(테두리 실선) → 끝 틱 파열(고리가 r×1.4 에서 멈춤) → 24틱 물비
 *  해일   : 8틱, 발 앞에 실제 폭(±r)의 물벽이 일어남 → 그 자리에서 출발, 0.9칸/틱 → 끝에서 부서짐 → 6틱
 *  해룡   : 24틱 목표 원 + 발밑 물기둥 → 26틱 비행(꼭대기 포효) → 충돌(고리가 r 에서 멈춤) → 60틱 소용돌이(테두리 r, 10틱마다 조임) → 36틱 물비
 * </pre>
 * <p>성능: 수류 분사의 레이캐스트는 틱에만 하고 프레임은 보간. 띠·관·격자 배열은 정적 임시 배열을 다시 씀.</p>
 */
final class WaterFx {
    private static final Element W = Element.WATER;
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final float TAU = (float) (Math.PI * 2);
    private static final float PI = (float) Math.PI;

    // 흰빛 → 청록 → 하늘 → 짙은 파랑
    private static final float[] C_WHITE = {0.88f, 0.97f, 1f};
    private static final float[] C_AQUA = {0.55f, 0.9f, 1f};
    private static final float[] C_AZURE = {0.35f, 0.7f, 1f};
    private static final float[] C_DEEP = {0.12f, 0.32f, 0.85f};

    private static final int MAX_ACTIVE = 32;
    private static final List<Fx> ACTIVE = new ArrayList<>();
    private static final List<Fx> PENDING = new ArrayList<>();
    private static boolean ticking;

    private WaterFx() {}

    // =====================================================================
    // 모듈 계약
    // =====================================================================

    static void onEvent(SkillEventPayload e, Skill skill, @Nullable Entity caster) {
        FxManager.lod = FxManager.lodAt(e.pos());
        switch (e.kind()) {
            case SkillEvents.IMPACT -> bulletImpact(e);
            case SkillEvents.JET -> {
                if (caster instanceof Player p && !p.isRemoved()) {
                    ACTIVE.removeIf(f -> f instanceof JetFx j && j.caster == p);
                    PENDING.removeIf(f -> f instanceof JetFx j && j.caster == p);
                    add(new JetFx(p, e.size(), e.size2(), e.duration()));
                }
            }
            case SkillEvents.PRISON -> add(new PrisonFx(caster, e.pos(), e.size(), e.duration()));
            case SkillEvents.WAVE -> add(new WaveFx(caster, e.pos(), e.vec(), e.size(), e.size2(), e.duration()));
            case SkillEvents.DRAGON -> add(new DragonFx(caster, e.pos(), e.vec(), e.size(), (int) e.size2(), e.duration()));
            default -> { }
        }
    }

    static void tick() {
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
    }

    static void renderAdditive(float pt, float time) {
        if (ACTIVE.isEmpty()) return;
        for (Fx f : ACTIVE) {
            Vec3 a = f.anchor();
            if (a != null && a.distanceToSqr(FxRender.cam) > 200.0 * 200.0) continue;
            f.render(pt, time);
        }
    }

    static void renderAlpha(float pt, float time) {
        // 물 연출은 더하기 섞기로 그림 (젖은 자국은 SkillFx 가, 안개는 SMOKE 입자가 그림)
    }

    static boolean isEmpty() {
        return ACTIVE.isEmpty() && PENDING.isEmpty();
    }

    static void clear() {
        ACTIVE.clear();
        PENDING.clear();
    }

    private static void add(Fx f) {
        if (ticking) PENDING.add(f);
        else addNow(f);
    }

    private static void addNow(Fx f) {
        while (ACTIVE.size() >= MAX_ACTIVE) ACTIVE.remove(0);
        ACTIVE.add(f);
    }

    private abstract static class Fx {
        int age;

        /** @return true 면 끝 */
        abstract boolean tick();

        abstract void render(float pt, float time);

        @Nullable
        abstract Vec3 anchor();
    }

    // =====================================================================
    // 공통 도우미 (입자)
    // =====================================================================

    /** 이번 프레임만 그리는 빛 (FxManager 의 풀 — 프레임마다 Sprite 를 만들지 않음) */
    private static void glow(Vec3 p, float size, float[] c, float a) {
        FxManager.frameGlow(p, size, c[0], c[1], c[2], a);
    }

    /** 해일 폭의 절반 (서버 Wave: radius, 주변 사람 화면에서도 같은 서버 값) */
    private static float waveHalf(FxManager.ChargeFx c) {
        return Math.max(0.5f, com.blackocean.skills.client.ClientSkillStats.radius(c.skill));
    }

    /** 감옥이 끌어당기고 터질 때 맞히는 반지름 (서버 Prison: r × 1.4, 높이 4.5) */
    static float prisonR(float r) {
        return r * 1.4f;
    }

    // ---- 그리기 임시 배열 (프레임마다 새로 만들지 않음; 그리기는 한 스레드) ----
    private static double[] sideBuf = new double[96], axBuf = new double[192], alongBuf = new double[32];

    /** 띠·관 점과 폭·열기 (한 번에 한 도형만 씀: 채우고 → 바로 그림) */
    private static final Vec3[] P32 = new Vec3[32];
    private static final float[] F1 = new float[32], F2 = new float[32], F3 = new float[32], F4 = new float[32];
    /** 해일 격자 (단면 PX 의 줄 수 = PX_ROWS) */
    private static final int PX_ROWS = 9;
    private static final Vec3[] GRID = new Vec3[PX_ROWS * 17];
    private static final float[] GHEAT = new float[PX_ROWS * 17];

    private static double[] sideBuf(int n) {
        if (sideBuf.length < n) sideBuf = new double[n];
        return sideBuf;
    }

    /** 짧게 번쩍이는 빛 */
    private static void flashGlow(Vec3 p, float s0, float s1, int life, float[] c, float a) {
        spawn(new Sprite(Sprite.Kind.GLOW, p, Vec3.ZERO, s0, s1, life).color(c[0], c[1], c[2], a));
    }

    /** 물방울 (gravity > 0 떨어짐, < 0 떠오름: 기포) */
    private static void drop(Vec3 at, Vec3 v, float size, int life, float gravity) {
        float w = RNG.nextFloat();
        Sprite s = new Sprite(Sprite.Kind.EMBER, at, v, size, size * 0.55f, life)
                .elem(W).physics(gravity, 0.98f).color(0.55f + 0.4f * w, 0.85f + 0.13f * w, 1f, 1f);
        s.frameOffset = RNG.nextInt(100);
        spawn(s);
    }

    /** 물보라 (물보라 그림 묶음) */
    private static void splash(Vec3 at, Vec3 v, float s0, float s1, int life, float gravity) {
        float w = RNG.nextFloat();
        Sprite s = new Sprite(Sprite.Kind.FLAME, at, v, s0, s1, life)
                .elem(W).physics(gravity, 0.9f).color(0.6f + 0.35f * w, 0.85f + 0.12f * w, 1f, 0.95f);
        s.frameOffset = RNG.nextInt(64);
        s.rot = RNG.nextFloat() * TAU;
        spawn(s);
    }

    /** 흰 물안개 (반투명) */
    private static void mist(Vec3 at, Vec3 v, float s0, float s1, int life, float a) {
        Sprite s = new Sprite(Sprite.Kind.SMOKE, at, v, s0, s1, life).elem(W).color(0.8f, 0.9f, 1f, a).physics(-0.001f, 0.94f);
        s.rot = RNG.nextFloat() * TAU;
        spawn(s);
    }

    /** 물보라 왕관: n 방향으로 솟고 바깥으로 벌어짐 */
    private static void crown(Vec3 at, Vec3 n, float scale, int base) {
        Vec3[] b = FxRender.basis(n);
        for (int i = 0, m = count(base); i < m; i++) {
            float ang = RNG.nextFloat() * TAU;
            Vec3 radial = b[0].scale(Math.cos(ang)).add(b[1].scale(Math.sin(ang)));
            Vec3 v = n.scale(rand(0.25f, 0.55f) * scale).add(radial.scale(rand(0.12f, 0.3f) * scale));
            splash(at.add(radial.scale(0.3 * scale)), v, rand(0.9f, 1.3f) * scale, rand(1.8f, 2.6f) * scale,
                    10 + RNG.nextInt(7), 0.035f * Math.min(1.5f, scale));
        }
    }

    /** 사방으로 포물선을 그리며 떨어지는 물방울 (n 바깥쪽 반구) */
    private static void dropBurst(Vec3 at, Vec3 n, float speed, int base, float size) {
        for (int i = 0, m = count(base); i < m; i++) {
            Vec3 d = randomDir();
            double dn = d.dot(n);
            if (dn < 0) d = d.subtract(n.scale(2 * dn));
            Vec3 v = d.scale(rand(0.3f, 1.0f) * speed).add(n.scale(0.15 * speed));
            drop(at, v, rand(0.7f, 1.3f) * size, 18 + RNG.nextInt(16), 0.045f);
        }
    }

    /** 퍼지는 물안개 */
    private static void mistBurst(Vec3 at, float radius, int base, float a) {
        for (int i = 0, m = count(base); i < m; i++) {
            Vec3 d = randomDir();
            mist(at.add(d.scale(rand(0.2f, 1f) * radius)), new Vec3(d.x * 0.05, rand(0.01f, 0.04f), d.z * 0.05),
                    0.6f + rand(1.2f, 1.8f) * radius * 0.6f, 1f + rand(3f, 4f) * radius * 0.6f, 24 + RNG.nextInt(14), a);
        }
    }

    /** 손으로 빨려 드는 물방울 */
    private static void gather(Vec3 hand, int n, float dist) {
        for (int i = 0, m = count(n); i < m; i++) {
            Vec3 off = randomDir().scale(rand(dist * 0.7f, dist));
            drop(hand.add(off), off.scale(-1.0 / 5.0), rand(0.14f, 0.22f), 5, 0f);
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

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }

    // =====================================================================
    // 공통 도우미 (도형)
    // =====================================================================

    /** 각 점의 옆 방향 (plane == null 이면 카메라를 향함, 아니면 그 평면 위). 결과: x,y,z × n, 길이 = 폭/2 */
    private static double[] sides(Vec3[] p, int n, float[] w, float wMul, @Nullable Vec3 plane) {
        double[] s = sideBuf(n * 3);
        for (int i = 0; i < n; i++) {
            Vec3 a = p[Math.max(0, i - 1)], b = p[Math.min(n - 1, i + 1)];
            double tx = b.x - a.x, ty = b.y - a.y, tz = b.z - a.z;
            double rx, ry, rz;
            if (plane != null) {
                rx = plane.x;
                ry = plane.y;
                rz = plane.z;
            } else {
                rx = FxRender.cam.x - p[i].x;
                ry = FxRender.cam.y - p[i].y;
                rz = FxRender.cam.z - p[i].z;
            }
            double cx = ty * rz - tz * ry, cy = tz * rx - tx * rz, cz = tx * ry - ty * rx;
            double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
            double k = len < 1.0E-6 ? 0.0 : w[i] * wMul * 0.5 / len;
            s[i * 3] = cx * k;
            s[i * 3 + 1] = cy * k;
            s[i * 3 + 2] = cz * k;
        }
        return s;
    }

    /** 띠 (elemShader 용): 가장자리 열기 0, 가운데 줄 열기 h */
    private static void strip(BufferBuilder bb, Vec3[] p, int n, float[] w, float[] h, float vScale, float vOff, @Nullable Vec3 plane) {
        if (n < 2) return;
        double[] s = sides(p, n, w, 1f, plane);
        double along = 0;
        for (int i = 0; i < n - 1; i++) {
            Vec3 a = p[i], b = p[i + 1];
            double seg = a.distanceTo(b);
            float va = (float) (along * vScale) + vOff, vb = (float) ((along + seg) * vScale) + vOff;
            float ha = h[i], hb = h[i + 1];
            int ia = i * 3, ib = (i + 1) * 3;
            FxRender.vertex(bb, a.x - s[ia], a.y - s[ia + 1], a.z - s[ia + 2], 0f, va, 1f, 1f, 1f, 0f);
            FxRender.vertex(bb, a.x, a.y, a.z, 0.5f, va, 1f, 1f, 1f, ha);
            FxRender.vertex(bb, b.x, b.y, b.z, 0.5f, vb, 1f, 1f, 1f, hb);
            FxRender.vertex(bb, b.x - s[ib], b.y - s[ib + 1], b.z - s[ib + 2], 0f, vb, 1f, 1f, 1f, 0f);
            FxRender.vertex(bb, a.x, a.y, a.z, 0.5f, va, 1f, 1f, 1f, ha);
            FxRender.vertex(bb, a.x + s[ia], a.y + s[ia + 1], a.z + s[ia + 2], 1f, va, 1f, 1f, 1f, 0f);
            FxRender.vertex(bb, b.x + s[ib], b.y + s[ib + 1], b.z + s[ib + 2], 1f, vb, 1f, 1f, 1f, 0f);
            FxRender.vertex(bb, b.x, b.y, b.z, 0.5f, vb, 1f, 1f, 1f, hb);
            along += seg;
        }
    }

    /** 텍스처 띠 (spriteShader(BEAM) 용, 카메라를 향함): u = 가로 0..1, v = 길이 방향으로 흐름 */
    private static void texStrip(BufferBuilder bb, Vec3[] p, int n, float[] w, float wMul, float vScale, float vOff, float[] c, float a) {
        if (n < 2 || a <= 0.001f) return;
        double[] s = sides(p, n, w, wMul, null);
        float r = c[0] * a, g = c[1] * a, b2 = c[2] * a;
        double along = 0;
        for (int i = 0; i < n - 1; i++) {
            Vec3 pa = p[i], pb = p[i + 1];
            double seg = pa.distanceTo(pb);
            float va = (float) (along * vScale) + vOff, vb = (float) ((along + seg) * vScale) + vOff;
            int ia = i * 3, ib = (i + 1) * 3;
            FxRender.vertex(bb, pa.x - s[ia], pa.y - s[ia + 1], pa.z - s[ia + 2], 0f, va, r, g, b2, a);
            FxRender.vertex(bb, pa.x + s[ia], pa.y + s[ia + 1], pa.z + s[ia + 2], 1f, va, r, g, b2, a);
            FxRender.vertex(bb, pb.x + s[ib], pb.y + s[ib + 1], pb.z + s[ib + 2], 1f, vb, r, g, b2, a);
            FxRender.vertex(bb, pb.x - s[ib], pb.y - s[ib + 1], pb.z - s[ib + 2], 0f, vb, r, g, b2, a);
            along += seg;
        }
    }

    /**
     * 점들을 따라가는 꼬인 관 (elemShader 용). 각 점의 틀은 평행 이동으로 이어서 급하게 꺾여도 뒤틀리지 않음.
     * 카메라를 정면으로 보는 면이 밝고 윤곽은 어두움 → 입체적인 물줄기.
     */
    private static void tube(BufferBuilder bb, Vec3[] p, int n, float[] r, float[] h, int around,
                             float twist, float vScale, float vOff, float wobble, float time) {
        if (n < 2) return;
        if (axBuf.length < n * 6) axBuf = new double[n * 6];
        if (alongBuf.length < n) alongBuf = new double[n];
        double[] ax = axBuf, along = alongBuf;
        along[0] = 0;
        Vec3 prevT = UP;
        Vec3 b0 = null;
        for (int i = 0; i < n; i++) {
            Vec3 t = p[Math.min(n - 1, i + 1)].subtract(p[Math.max(0, i - 1)]);
            t = t.lengthSqr() < 1.0E-8 ? prevT : t.normalize();
            if (b0 == null) {
                b0 = FxRender.basis(t)[0];
            } else {
                Vec3 pr = b0.subtract(t.scale(b0.dot(t)));
                b0 = pr.lengthSqr() < 1.0E-8 ? FxRender.basis(t)[0] : pr.normalize();
            }
            Vec3 b1 = t.cross(b0);
            b1 = b1.lengthSqr() < 1.0E-8 ? FxRender.basis(t)[1] : b1.normalize();
            ax[i * 6] = b0.x;
            ax[i * 6 + 1] = b0.y;
            ax[i * 6 + 2] = b0.z;
            ax[i * 6 + 3] = b1.x;
            ax[i * 6 + 4] = b1.y;
            ax[i * 6 + 5] = b1.z;
            prevT = t;
            if (i > 0) along[i] = along[i - 1] + p[i].distanceTo(p[i - 1]);
        }
        for (int i = 0; i < n - 1; i++) {
            float v0 = (float) (along[i] * vScale) + vOff, v1 = (float) (along[i + 1] * vScale) + vOff;
            for (int j = 0; j < around; j++) {
                float u0 = (float) j / around * 2f, u1 = (float) (j + 1) / around * 2f;
                float a0 = TAU * j / around, a1 = TAU * (j + 1) / around;
                tv(bb, p[i], ax, i, a0 + twist * (float) along[i], r[i], h[i], u0, v0, wobble, (float) along[i], time);
                tv(bb, p[i], ax, i, a1 + twist * (float) along[i], r[i], h[i], u1, v0, wobble, (float) along[i], time);
                tv(bb, p[i + 1], ax, i + 1, a1 + twist * (float) along[i + 1], r[i + 1], h[i + 1], u1, v1, wobble, (float) along[i + 1], time);
                tv(bb, p[i + 1], ax, i + 1, a0 + twist * (float) along[i + 1], r[i + 1], h[i + 1], u0, v1, wobble, (float) along[i + 1], time);
            }
        }
    }

    private static void tv(BufferBuilder bb, Vec3 c, double[] ax, int i, float ang, float r, float h,
                           float u, float v, float wobble, float along, float time) {
        double cs = Math.cos(ang), sn = Math.sin(ang);
        double rx = ax[i * 6] * cs + ax[i * 6 + 3] * sn;
        double ry = ax[i * 6 + 1] * cs + ax[i * 6 + 4] * sn;
        double rz = ax[i * 6 + 2] * cs + ax[i * 6 + 5] * sn;
        double rr = r * (1.0 + wobble * Math.sin(ang * 3.0 + along * 1.7 + time * 0.6));
        double x = c.x + rx * rr, y = c.y + ry * rr, z = c.z + rz * rr;
        double vx = FxRender.cam.x - x, vy = FxRender.cam.y - y, vz = FxRender.cam.z - z;
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        float facing = len > 1.0E-4 ? (float) (Math.abs(rx * vx + ry * vy + rz * vz) / len) : 1f;
        FxRender.vertex(bb, x, y, z, u, v, 1f, 1f, 1f, h * (0.15f + 0.85f * facing));
    }

    /** 바닥에 눕힌 소용돌이 팔 (elemShader 용) */
    private static void spiralArms(BufferBuilder bb, Vec3 c, float rOut, float rIn, int arms, float turns, float width, float heat, float rot) {
        final int m = 16;
        Vec3[] pts = P32;
        float[] w = F1, h = F2;
        for (int k = 0; k < arms; k++) {
            for (int j = 0; j < m; j++) {
                float f = j / (m - 1f);
                float rr = rOut + (rIn - rOut) * f;
                double ang = rot + k * TAU / arms + f * TAU * turns;
                pts[j] = c.add(Math.cos(ang) * rr, 0, Math.sin(ang) * rr);
                w[j] = width * (1f - 0.6f * f);
                h[j] = heat * (float) Math.sin(Math.PI * f);
            }
            strip(bb, pts, m, w, h, 0.3f, k * 0.37f, UP);
        }
    }

    /**
     * 바닥 조준 준비 원. 범위 끝(테두리)은 공용 위험 표시만 그림 (적 = 빨간 실선, 내 것·아군 = 점선) —
     * 물 장식(안쪽 물빛 번짐·감겨 드는 물 팔)은 테두리 안쪽에서 부드럽게 끝나 경계로 착각되지 않음.
     */
    private static void groundCircle(Vec3 base, float radius, float k, float time, @Nullable Entity caster) {
        Vec3 c = base.add(0, 0.04, 0);
        RenderSystem.disableCull();
        FxRender.spriteShader(FxRender.GLOW_WHITE);
        BufferBuilder bb = FxRender.quads();
        float g = 0.2f + 0.5f * k * k;
        FxRender.planeQuad(bb, c.add(0, 0.02, 0), UP, radius * 0.95f, 0f, g * C_DEEP[0], g * C_DEEP[1], g * C_DEEP[2], g);
        FxRender.draw(bb);

        FxRender.elemShader(W, 1.6f, 0.3f - 0.25f * k, 0.8f + 0.4f * k);
        bb = FxRender.quads();
        spiralArms(bb, c.add(0, 0.035, 0), radius * 0.88f, radius * 0.15f, 3, 0.6f, 0.5f + 0.4f * k, 0.35f + 0.4f * k, -time * 0.15f);
        FxRender.draw(bb);
        zone(base, radius, k, caster, 1f, time);
    }

    // =====================================================================
    // 터지는 물 구 (물 구슬 적중, 감옥 파열)
    // =====================================================================

    private static final class SplashFx extends Fx {
        final Vec3 pos, normal;
        final float R;
        final int life;

        SplashFx(Vec3 pos, Vec3 normal, float radius, int life) {
            this.pos = pos;
            this.normal = normal;
            this.R = radius;
            this.life = Math.max(6, life);
        }

        @Override
        boolean tick() {
            return ++age > life;
        }

        @Override
        void render(float pt, float time) {
            float t = age + pt;
            if (t > life) return;
            float f = t / life;
            float radius = R * (0.25f + 0.75f * easeOutCubic(t / (life * 0.45f)));
            float erosion = t < 2.5f ? 0.04f : Math.min(1f, (t - 2.5f) / (life * 0.7f));
            RenderSystem.disableCull();
            FxRender.elemShader(W, 1.6f, erosion, 1.05f - 0.45f * f);
            FxManager.BlastSphere.draw(pos, radius, t * 1.5f);
            FxRender.elemShader(W, 2.2f, Math.min(1f, erosion + 0.1f), 1f - f);
            BufferBuilder bb = FxRender.quads();
            FxRender.ring(bb, pos.add(normal.scale(0.05)), normal, radius * 0.7f, radius * 1.45f, 0.9f * (1f - f), t * 0.4f, 40);
            FxRender.draw(bb);
        }

        @Override
        Vec3 anchor() {
            return pos;
        }
    }

    // =====================================================================
    // 1. 물 구슬
    // =====================================================================

    static void trackProjectile(FxManager.ProjectileFx p, Vec3 pos) {
        Vec3 back = p.dir.scale(-1);
        if (p.age == 1) {
            // 발사: 구슬이 맺혔던 손(서버가 만든 자리)에서 비행 방향으로 물이 튀어 나감
            Vec3 o = AimHand.spawnPoint(p);
            flashGlow(o, 1.6f, 2.6f, 3, C_WHITE, 0.9f);
            FxManager.addRing(o.add(p.dir.scale(0.3)), p.dir, 0.2f, 1.9f, 5, 0, 0.9f, W);
            FxManager.addRing(o.add(p.dir.scale(0.9)), p.dir, 0.15f, 1.2f, 4, 1, 0.6f, W);
            for (int i = 0, m = count(10); i < m; i++) {
                Vec3 v = p.dir.scale(rand(0.3f, 0.7f)).add(randomDir().scale(0.14));
                drop(o, v, rand(0.14f, 0.22f), 8 + RNG.nextInt(5), 0.04f);
            }
            for (int i = 0, m = count(3); i < m; i++) {
                splash(o.add(p.dir.scale(0.3)), p.dir.scale(rand(0.1f, 0.2f)).add(randomDir().scale(0.05)), 0.6f, 1.3f, 5, 0f);
            }
        }
        for (int i = 0, m = count(3); i < m; i++) {
            drop(pos.add(randomDir().scale(0.45)), back.scale(0.08).add(randomDir().scale(0.04)),
                    rand(0.16f, 0.24f), 14 + RNG.nextInt(10), 0.03f);
        }
        if (p.age % 2 == 0) {
            for (int i = 0, m = count(1); i < m; i++) {
                mist(pos.add(back.scale(0.8)), new Vec3(0, 0.01, 0), 0.6f, 1.6f, 14, 0.3f);
            }
        }
        for (int i = 0, m = count(1); i < m; i++) {
            splash(pos.add(back.scale(0.5)).add(randomDir().scale(0.15)), back.scale(0.05), 0.7f, 0.3f, 6, 0f);
        }
    }

    static void renderProjectile(FxManager.ProjectileFx p, Vec3 c, float pt, float time) {
        float grow = Math.min(1f, (p.age + pt) / 2.5f);
        float r = 0.55f * (0.5f + 0.5f * grow);
        float wob = 1f + 0.06f * (float) Math.sin(time * 1.7f);
        RenderSystem.disableCull();
        // 일렁이는 물 구
        FxRender.elemShader(W, 1.8f, 0.04f, 0.95f);
        FxManager.BlastSphere.draw(c, r * wob, time * 2.4f);
        // 카메라를 향한 밝은 테두리 + 비행 방향을 감는 가는 고리
        FxRender.elemShader(W, 2.6f, 0.05f, 1.25f);
        BufferBuilder bb = FxRender.quads();
        Vec3 toCam = FxRender.cam.subtract(c);
        if (toCam.lengthSqr() > 1.0E-4) {
            FxRender.ring(bb, c, toCam.normalize(), r * 0.9f, r * 1.16f, 0.95f, time * 0.5f, 32);
        }
        Vec3[] b = FxRender.basis(p.dir);
        double ph = time * 0.45;
        Vec3 tilted = p.dir.scale(0.45).add(b[0].scale(Math.cos(ph) * 0.9)).add(b[1].scale(Math.sin(ph) * 0.9)).normalize();
        FxRender.ring(bb, c, tilted, r * 1.2f, r * 1.32f, 0.6f, -time * 0.7f, 32);
        FxRender.draw(bb);
        // 밝은 심 + 후광
        glow(c, r * 1.5f, C_WHITE, 0.6f);
        glow(c, r * 5.5f, C_AZURE, 0.3f + 0.05f * (float) Math.sin(time * 3.1f));
    }

    private static void bulletImpact(SkillEventPayload e) {
        Vec3 pos = e.pos();
        Vec3 n = e.vec();
        n = n.lengthSqr() < 0.25 ? UP : n.normalize();
        boolean fizzle = e.size2() > 0.5f;
        float R = e.size() > 0.1f ? e.size() : 2.5f;
        float big = fizzle ? 0.5f : 1f;
        boolean blockHit = e.target() < 0 && !fizzle;
        Vec3 ringN = blockHit && Math.abs(n.y) < 0.5 ? n : UP;

        flashGlow(pos, 4.5f * big, 6f * big, 4, C_WHITE, 1f);
        flashGlow(pos, R * 1.6f * big, R * 2.2f * big, 8, C_AZURE, 0.7f);
        Vec3 rc = pos.add(ringN.scale(0.08));
        if (!fizzle) {
            // 물보라 피해 반지름 R 에서 멈추는 고리 (범위가 그대로 읽힘) + 바깥으로 흩어지는 옅은 물결
            FxManager.addRing(rc, ringN, 0.4f, R, 6, 0, 1f, W);
            FxManager.addRing(rc, ringN, 0.3f, R * 0.7f, 6, 1, 0.8f, W);
            FxManager.addRing(rc, ringN, R * 0.9f, R * 1.45f, 10, 3, 0.35f, W);
        } else {
            // 허공에서 다 날아감: 피해 없음 → 작고 옅게 흩어짐
            FxManager.addRing(rc, ringN, 0.3f, 1.4f, 6, 0, 0.6f, W);
        }
        add(new SplashFx(pos, ringN, R * 0.69f * big, 11));
        // 솟구치는 물보라 왕관
        crown(pos, n, 1.0f * big, fizzle ? 10 : 28);
        // 포물선을 그리며 떨어지는 물방울
        dropBurst(pos, n, 0.75f * big, fizzle ? 18 : 55, 0.24f);
        // 물안개
        mistBurst(pos, R * 0.5f * big, fizzle ? 3 : 10, 0.45f);
        if (blockHit) SkillFx.addDecal(pos, n, R * 0.95f, 160, 0.2f, 0.35f, 0.6f);
        if (!fizzle) shake(pos, 1.8f, 14f);
        if (blockHit && n.y > 0.5) {
            // 바닥에 맞음: 흙이 튀고, 젖은 바닥에 물결이 몇 번 번짐 (잔류)
            DebrisFx.burst(pos, 1.2f, 6, 0.45f);
            ripples(pos, R * 1.3f, 3, 4);
        } else if (blockHit && Math.abs(n.y) < 0.5) {
            DebrisFx.directed(pos, new Vec3(n.x, 0, n.z), 2.0f, 45f, 4, 0.4f);
        }
    }

    /** 바닥에 천천히 번지는 물결 고리 n 개 (첫 고리는 delay0 틱 뒤) */
    private static void ripples(Vec3 at, float r, int n, int delay0) {
        Vec3 c = at.add(0, 0.05, 0);
        for (int k = 0; k < n; k++) {
            FxManager.addRing(c, UP, 0.2f + 0.2f * k, r * (1f - 0.15f * k), 22, delay0 + k * 8, 0.38f - 0.08f * k, W);
        }
    }

    /** p 아래 바닥 (6칸까지, 없으면 p) */
    private static Vec3 groundAt(Vec3 p) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) return p;
        Vec3 from = p.add(0, 0.6, 0);
        BlockHitResult down = SubLevels.clip(level, new ClipContext(from, from.add(0, -6.6, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, mc.player));
        return down.getType() == HitResult.Type.MISS ? p : down.getLocation();
    }

    /** 가까이 있으면 화면 번쩍 (멀수록 약하게) */
    private static void flashNear(Vec3 p, int rgb, float strength, int ticks, float range) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double d = mc.player.position().distanceTo(p);
        if (d < range) limitedFlash(rgb, strength * (float) (1 - d / range), ticks);
    }

    /** 화면 번쩍 (여러 스킬이 겹치면 FxLimits 가 한 틱 합계를 제한) */
    private static void limitedFlash(int rgb, float strength, int ticks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float ok = FxLimits.allowFlash(mc.level.getGameTime(), strength);
        if (ok > 0.01f) ScreenFx.flash(rgb, ok, ticks);
    }

    /** 원형 위험 범위 (공용 표시: 적 = 빨간 실선 + 경고, 내 것·아군 = 점선). 그린 뒤 더하기 섞기를 되돌림 */
    private static void zone(Vec3 base, float r, float fill, @Nullable Entity caster, float alpha, float time) {
        if (alpha <= 0.01f) return;
        SkillFx.dangerZone(base, r, clamp01(fill), W, caster, alpha, time);
        FxRender.additive();
        RenderSystem.disableCull();
    }

    /** 직선 위험 범위 (공용 표시). 그린 뒤 더하기 섞기를 되돌림 */
    private static void lane(Vec3 start, Vec3 dir, float len, float halfW, float fill, @Nullable Entity caster, float alpha, float time) {
        if (alpha <= 0.01f || len <= 0.05f) return;
        SkillFx.dangerLane(start, dir, len, halfW, clamp01(fill), W, caster, alpha, time);
        FxRender.additive();
        RenderSystem.disableCull();
    }

    /**
     * 윤곽이 밝고 가운데가 투명한 물 구 (fresnel 흉내). 카메라를 정면으로 보는 면은 열기를 크게 낮춰
     * 안에 갇힌 사람·뒤의 사람이 비쳐 보이고, 가장자리는 짙은 물빛 테로 둥근 부피가 읽힘. elemShader 를 먼저 켤 것.
     * @param core 정면(가운데) 밝기 비율 (0.1 = 거의 투명)
     */
    private static void fresnelSphere(Vec3 c, float r, float heat, float core, float time) {
        if (r <= 0.05f || heat <= 0.001f) return;
        final int lat = 12, lon = 22;
        BufferBuilder bb = FxRender.quads();
        for (int i = 0; i < lat; i++) {
            float v0 = (float) i / lat, v1 = (float) (i + 1) / lat;
            for (int j = 0; j < lon; j++) {
                float u0 = (float) j / lon, u1 = (float) (j + 1) / lon;
                sv(bb, c, r, u0, v0, heat, core, time);
                sv(bb, c, r, u1, v0, heat, core, time);
                sv(bb, c, r, u1, v1, heat, core, time);
                sv(bb, c, r, u0, v1, heat, core, time);
            }
        }
        FxRender.draw(bb);
    }

    private static void sv(BufferBuilder bb, Vec3 c, float r, float u, float v, float heat, float core, float time) {
        double th = Math.PI * v, ph = TAU * u + time * 0.02;
        double nx = Math.sin(th) * Math.cos(ph), ny = Math.cos(th), nz = Math.sin(th) * Math.sin(ph);
        double rr = r * (1.0 + 0.04 * Math.sin(ph * 3 + th * 4 + time * 0.5));
        double x = c.x + nx * rr, y = c.y + ny * rr, z = c.z + nz * rr;
        double vx = FxRender.cam.x - x, vy = FxRender.cam.y - y, vz = FxRender.cam.z - z;
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        float facing = len > 1.0E-4 ? (float) Math.abs((nx * vx + ny * vy + nz * vz) / len) : 1f;
        float rim = (float) Math.pow(1f - facing, 1.6);
        FxRender.vertex(bb, x, y, z, u * 3f, v * 1.5f - time * 0.01f, 1f, 1f, 1f, heat * (core + (1f - core) * rim));
    }

    // =====================================================================
    // 2. 수류 분사
    // =====================================================================

    private record Beam(Vec3 start, Vec3 end, Vec3 dir, boolean hit, Vec3 normal) {}

    private static final class JetFx extends Fx {
        final Player caster;
        final float range, rad;
        final int duration;
        /** 틱마다 한 번 계산한 물줄기 (직전·이번 틱). 프레임은 둘 사이를 보간 → 매 프레임 레이캐스트 없음 */
        @Nullable
        Beam prevBeam, curBeam;

        JetFx(Player caster, float range, float rad, int duration) {
            this.caster = caster;
            this.range = range > 0.5f ? range : 18f;
            this.rad = rad > 0.05f ? rad : 0.9f;
            this.duration = Math.max(4, duration);
            Beam b = beam(1f);
            prevBeam = curBeam = b;
            if (b != null) {
                // 분사 시작: 손에서 터지는 물보라
                flashGlow(b.start(), 2.5f, 3.5f, 4, C_WHITE, 1f);
                FxManager.addRing(b.start().add(b.dir().scale(0.4)), b.dir(), 0.2f, 2.2f, 6, 0, 0.9f, W);
                crown(b.start(), b.dir(), 0.6f, 8);
            }
        }

        @Nullable
        private static Beam lerpBeam(@Nullable Beam a, @Nullable Beam b, float pt) {
            if (b == null) return null;
            if (a == null || a.hit() != b.hit()) return b;
            Vec3 s0 = lerp(a.start(), b.start(), pt), e0 = lerp(a.end(), b.end(), pt);
            Vec3 d = e0.subtract(s0);
            Vec3 dir = d.lengthSqr() < 1.0E-6 ? b.dir() : d.normalize();
            return new Beam(s0, e0, dir, b.hit(), b.normal());
        }

        @Nullable
        Beam beam(float pt) {
            if (caster.isRemoved()) return null;
            Level level = caster.level();
            Vec3 eye = caster.getEyePosition(pt);
            Vec3 look = caster.getViewVector(pt);
            Vec3 start = SkillAim.handPos(level, caster, eye, look, 0.3, 0);
            Vec3 dir = SkillAim.aimDirection(level, caster, eye, look, start, range);
            Vec3 end = start.add(dir.scale(range));
            BlockHitResult bh = SubLevels.clip(level, new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
            boolean hit = bh.getType() != HitResult.Type.MISS;
            Vec3 normal = dir.scale(-1);
            if (hit) {
                end = bh.getLocation();
                normal = Vec3.atLowerCornerOf(bh.getDirection().getNormal());
            }
            return new Beam(start, end, dir, hit, normal);
        }

        @Override
        Vec3 anchor() {
            return caster.position();
        }

        @Override
        boolean tick() {
            age++;
            if (caster.isRemoved() || age > duration + 5) return true;
            prevBeam = curBeam;
            curBeam = beam(1f);
            if (prevBeam == null) prevBeam = curBeam;
            if (age > duration) return false;
            Beam b = curBeam;
            if (b == null) return true;
            Vec3 start = b.start(), end = b.end(), dir = b.dir();
            double len = start.distanceTo(end);

            // 노즐 분무
            FxManager.lod = FxManager.lodAt(start);
            for (int i = 0, m = count(3); i < m; i++) {
                drop(start, dir.scale(rand(0.3f, 0.6f)).add(randomDir().scale(0.12)), rand(0.14f, 0.22f), 8 + RNG.nextInt(6), 0.03f);
            }
            if (age % 2 == 0) {
                for (int i = 0, m = count(1); i < m; i++) {
                    mist(start.add(dir.scale(0.4)), dir.scale(0.05), 0.5f, 1.3f, 10, 0.3f);
                }
            }
            // 줄기를 따라 튀어 나가는 물방울
            for (int i = 0, m = count(3); i < m; i++) {
                Vec3 at = start.add(dir.scale(RNG.nextFloat() * len)).add(randomDir().scale(rad * 0.6));
                drop(at, dir.scale(rand(0.3f, 0.6f)).add(randomDir().scale(0.1)), rand(0.16f, 0.26f), 10 + RNG.nextInt(8), 0.035f);
            }
            // 끝점
            FxManager.lod = FxManager.lodAt(end);
            if (b.hit()) {
                Vec3 n = b.normal();
                Vec3 refl = dir.subtract(n.scale(2 * dir.dot(n)));
                for (int i = 0, m = count(3); i < m; i++) {
                    Vec3 v = n.scale(rand(0.15f, 0.35f)).add(refl.scale(rand(0.1f, 0.2f))).add(randomDir().scale(0.12));
                    splash(end.add(n.scale(0.2)), v, rand(0.9f, 1.3f), rand(1.8f, 2.4f), 8 + RNG.nextInt(5), 0.03f);
                }
                for (int i = 0, m = count(7); i < m; i++) {
                    Vec3 v = refl.scale(rand(0.2f, 0.45f)).add(n.scale(rand(0.15f, 0.35f))).add(randomDir().scale(0.18));
                    drop(end.add(n.scale(0.15)), v, rand(0.18f, 0.3f), 14 + RNG.nextInt(12), 0.045f);
                }
                for (int i = 0, m = count(2); i < m; i++) {
                    mist(end.add(n.scale(0.4)).add(randomDir().scale(0.5)), n.scale(0.04).add(randomDir().scale(0.02)),
                            1.0f, 2.8f, 18 + RNG.nextInt(8), 0.4f);
                }
                if (age % 3 == 0) FxManager.addRing(end.add(n.scale(0.05)), n, 0.3f, 2.8f, 8, 0, 0.7f, W);
                if (age % 6 == 1) SkillFx.addDecal(end, n, 1.5f, 110, 0.2f, 0.35f, 0.6f);
                // 고압 물줄기가 땅을 파냄
                if (age % 5 == 2 && n.y > 0.5) {
                    Vec3 fl = new Vec3(refl.x, 0, refl.z);
                    DebrisFx.directed(end, fl.lengthSqr() < 1.0E-4 ? dir : fl, 2.0f, 40f, 2, 0.35f);
                }
            } else {
                // 허공: 끝에서 흩어지는 물보라
                for (int i = 0, m = count(4); i < m; i++) {
                    drop(end.add(randomDir().scale(0.6)), dir.scale(rand(0.15f, 0.35f)).add(randomDir().scale(0.15)), rand(0.18f, 0.28f), 16 + RNG.nextInt(10), 0.045f);
                }
                for (int i = 0, m = count(1); i < m; i++) {
                    mist(end, dir.scale(0.06), 1.0f, 2.6f, 16, 0.35f);
                }
            }
            return false;
        }

        @Override
        void render(float pt, float time) {
            float t = age + pt;
            float fade = t > duration ? 1f - clamp01((t - duration) / 5f) : 1f;
            if (fade <= 0f) return;
            Beam b = lerpBeam(prevBeam, curBeam, pt);
            if (b == null) return;
            // 서버는 첫 틱부터 물줄기 전체 길이를 판정 → 2틱 만에 끝까지 뻗음 (뻗는 모습은 보이되 늦지 않게)
            float ext = easeOutCubic(Math.min(1f, t / 2f));
            Vec3 start = b.start(), dir = b.dir();
            double L = start.distanceTo(b.end()) * ext;
            if (L < 0.2) return;
            int n = Math.max(3, Math.min(26, (int) (L / 0.75) + 2));
            Vec3[] pts = P32;
            float[] r = F1, h = F2, r2 = F3, h2 = F4;
            Vec3[] bs = FxRender.basis(dir);
            for (int i = 0; i < n; i++) {
                double s = L * i / (n - 1);
                float f = (float) (s / L);
                // 약간 출렁이는 축
                double wx = Math.sin(s * 0.9 - time * 0.9) * 0.08 * f, wy = Math.cos(s * 1.1 - time * 0.8) * 0.08 * f;
                pts[i] = start.add(dir.scale(s)).add(bs[0].scale(wx)).add(bs[1].scale(wy));
                float nozzle = smooth((float) s / 1.2f);
                r[i] = rad * (0.35f + 0.65f * nozzle) * (1f + 0.2f * f) * (0.55f + 0.45f * fade);
                h[i] = 0.95f * (1f - 0.3f * f);
                r2[i] = r[i] * 0.42f;
                h2[i] = 1f;
            }
            RenderSystem.disableCull();
            // 바깥 물줄기 관 (빠르게 흐르며 꼬임)
            FxRender.elemShader(W, 3.4f, 0.1f + 0.6f * (1f - fade), 1.0f * fade + 0.05f);
            BufferBuilder bb = FxRender.quads();
            tube(bb, pts, n, r, h, 10, 0.9f, 0.18f, -time * 0.25f, 0.08f, time);
            FxRender.draw(bb);
            // 하얀 심 (반대로 꼬임)
            FxRender.elemShader(W, 4.6f, 0.04f, 1.35f * fade);
            bb = FxRender.quads();
            tube(bb, pts, n, r2, h2, 8, -1.4f, 0.26f, -time * 0.4f, 0.05f, time);
            FxRender.draw(bb);
            // 빛 띠 3겹 (넓은 하늘색 / 청록 / 가는 흰 심)
            FxRender.spriteShader(FxRender.BEAM);
            bb = FxRender.quads();
            texStrip(bb, pts, n, r, 2.6f, 0.12f, -time * 0.55f, C_AZURE, 0.5f * fade);
            texStrip(bb, pts, n, r, 1.5f, 0.2f, -time * 0.9f, C_AQUA, 0.55f * fade);
            texStrip(bb, pts, n, r, 0.6f, 0.3f, -time * 1.4f, C_WHITE, 0.85f * fade);
            FxRender.draw(bb);
            // 노즐 고리 + 끝점 물보라 고리
            FxRender.elemShader(W, 2.5f, 0.1f, 1.1f * fade);
            bb = FxRender.quads();
            FxRender.ring(bb, start.add(dir.scale(0.15)), dir, rad * 0.3f, rad * 0.7f, 0.9f, time * 0.8f, 24);
            FxRender.ring(bb, start.add(dir.scale(0.6)), dir, rad * 0.55f, rad * 0.9f, 0.6f, -time * 0.6f, 24);
            boolean atEnd = ext >= 0.99f;
            if (b.hit() && atEnd) {
                float pul = 1f + 0.12f * (float) Math.sin(time * 1.9f);
                FxRender.ring(bb, b.end().add(b.normal().scale(0.06)), b.normal(), rad * 0.5f, rad * 2.6f * pul, 0.8f, time * 0.5f, 40);
            }
            FxRender.draw(bb);
            glow(start, 1.3f, C_WHITE, 0.8f * fade);
            glow(start, 3.0f, C_AZURE, 0.45f * fade);
            // 분사 중에만: 손 → 끝점, 반폭 = 서버 판정 굵기 (실시간 조준을 따라감). 끝나는 5틱 페이드에는 테두리 없음
            if (t <= duration) lane(start, dir, (float) L, rad, t / duration, caster, 1f, time);
            if (atEnd) {
                glow(b.end(), rad * 3f, C_WHITE, 0.55f * fade);
                glow(b.end(), rad * 6.5f, C_AZURE, 0.35f * fade);
            }
        }
    }

    // =====================================================================
    // 3. 물의 감옥
    // =====================================================================

    private static final class PrisonFx extends Fx {
        static final int AFTER = 24;
        final Vec3 center, core;
        /** R = 설정 반지름, P = 실제로 끌어당기고 터질 때 맞히는 반지름 (서버 r × 1.4) */
        final float R, Rs, P;
        final int duration;

        @Nullable final Entity caster;

        PrisonFx(@Nullable Entity caster, Vec3 center, float radius, int duration) {
            this.caster = caster;
            this.center = center;
            this.R = radius > 0.2f ? radius : 3.5f;
            this.P = prisonR(R);
            this.Rs = R * 0.9f;
            this.core = center.add(0, 1.2, 0);
            this.duration = Math.max(10, duration);
            // 생성: 바닥에서 물이 솟아 구를 이룸, 고리가 끌어당기는 범위 끝에서 멈춤
            flashGlow(core, 4f, 7f, 6, C_WHITE, 0.8f);
            FxManager.addRing(center.add(0, 0.06, 0), UP, 0.5f, P, 8, 0, 0.9f, W);
            crown(center.add(0, 0.2, 0), UP, 1.1f, 22);
            dropBurst(center.add(0, 0.3, 0), UP, 0.6f, 30, 0.24f);
            mistBurst(center.add(0, 0.5, 0), R * 0.5f, 6, 0.4f);
            SkillFx.addDecal(center, UP, R * 1.2f, duration + 80, 0.2f, 0.35f, 0.6f);
        }

        @Override
        Vec3 anchor() {
            return center;
        }

        @Override
        boolean tick() {
            age++;
            if (age > duration + AFTER) return true;
            FxManager.lod = FxManager.lodAt(center);
            if (age == duration) {
                burst();
                return false;
            }
            if (age > duration) {
                // 잔류: 하늘로 튄 물이 비처럼 떨어짐
                float lf = 1f - (age - duration) / (float) AFTER;
                for (int i = 0, m = count(4); i < m; i++) {
                    if (RNG.nextFloat() > lf) continue;
                    float ang = RNG.nextFloat() * TAU;
                    float rr = P * (float) Math.sqrt(RNG.nextFloat());
                    drop(center.add(Math.cos(ang) * rr, rand(4f, 7f), Math.sin(ang) * rr), new Vec3(0, -rand(0.2f, 0.4f), 0), rand(0.16f, 0.24f), 20, 0.04f);
                }
                return false;
            }
            // 구 안에서 오르는 기포
            for (int i = 0, m = count(4); i < m; i++) {
                Vec3 d = randomDir().scale(Rs * 0.85 * Math.cbrt(RNG.nextDouble()));
                drop(core.add(d), new Vec3(0, rand(0.03f, 0.08f), 0), rand(0.15f, 0.3f), 12 + RNG.nextInt(12), -0.004f);
            }
            // 바닥 소용돌이 가장자리에서 도는 물보라
            for (int i = 0, m = count(2); i < m; i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 at = center.add(Math.cos(ang) * P, 0.2, Math.sin(ang) * P);
                Vec3 v = new Vec3(-Math.sin(ang), 0.05, Math.cos(ang)).scale(0.18).add(Math.cos(ang) * -0.05, 0, Math.sin(ang) * -0.05);
                splash(at, v, 0.8f, 1.4f, 8, 0f);
            }
            // 표면에서 튕겨 나가는 물방울
            for (int i = 0, m = count(1); i < m; i++) {
                Vec3 d = randomDir();
                drop(core.add(d.scale(Rs)), d.scale(0.12).add(0, 0.1, 0), rand(0.18f, 0.26f), 16, 0.04f);
            }
            if (age % 3 == 0) {
                for (int i = 0, m = count(1); i < m; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    mist(center.add(Math.cos(ang) * R * rand(0.4f, 1.2f), 0.3, Math.sin(ang) * R * rand(0.4f, 1.2f)),
                            new Vec3(0, 0.02, 0), 1.2f, 2.8f, 22, 0.3f);
                }
            }
            return false;
        }

        /** 서버가 터뜨리는 바로 그 틱 */
        private void burst() {
            flashGlow(core, 7f, 11f, 5, C_WHITE, 1f);
            flashGlow(core, P * 2.2f, P * 3f, 10, C_AZURE, 0.7f);
            // 피해 반지름 P 에서 멈추는 고리 + 바깥으로 옅게 흩어지는 물결
            FxManager.addRing(center.add(0, 0.07, 0), UP, R * 0.5f, P, 7, 0, 1f, W);
            FxManager.addRing(center.add(0, 0.08, 0), UP, R * 0.3f, P * 0.7f, 7, 2, 0.8f, W);
            FxManager.addRing(center.add(0, 0.09, 0), UP, P * 0.9f, P * 1.4f, 10, 3, 0.35f, W);
            FxManager.addRing(core, UP, R * 0.6f, P * 0.9f, 8, 1, 0.7f, W);
            add(new SplashFx(core, UP, Rs * 1.35f, 14));
            // 사방으로 터지는 물보라
            for (int i = 0, m = count(40); i < m; i++) {
                Vec3 d = randomDir().add(0, 0.3, 0).normalize();
                splash(core.add(d.scale(Rs * 0.7)), d.scale(rand(0.3f, 0.65f)), rand(1.3f, 1.8f), rand(2.4f, 3.2f), 10 + RNG.nextInt(8), 0.035f);
            }
            crown(center.add(0, 0.2, 0), UP, 1.5f, 20);
            dropBurst(core, UP, 1.0f, 70, 0.28f);
            mistBurst(core, Rs * 0.8f, 16, 0.5f);
            SkillFx.addDecal(center, UP, R * 1.5f, 200, 0.2f, 0.35f, 0.6f);
            shake(center, 2.6f, R + 14f);
            // 바닥에서 솟구치는 물기둥 + 패인 땅 + 튀는 흙
            SkillFx.addPillar(center, R * 0.45f, 7f, 9, false, W);
            DebrisFx.burst(center, R * 0.7f, 16, 0.9f);
            DebrisFx.crater(center, R * 0.8f, 140, W);
            ripples(center, P * 1.3f, 2, 6);
            flashNear(center, 0x7FD0FF, 0.22f, 8, R + 8f);
        }

        @Override
        void render(float pt, float time) {
            float t = age + pt;
            if (t > duration) return; // 터진 뒤는 SplashFx 가 그림
            float form = easeOutCubic(clamp01(t / 6f));
            float pre = clamp01((t - (duration - 6)) / 6f); // 터지기 직전 부풀어 오름
            float rs = Rs * (0.25f + 0.75f * form) * (1f + 0.025f * (float) Math.sin(time * 0.55f) + 0.12f * pre * pre);
            RenderSystem.disableCull();
            // 떠 있는 물 구: 윤곽이 밝고 가운데가 투명 → 갇힌 상대가 그대로 보임 (터지기 직전 가운데도 차오름)
            FxRender.elemShader(W, 0.9f + 1.5f * pre, 0.05f, 0.75f + 0.08f * (float) Math.sin(time * 0.3f) + 0.35f * pre);
            fresnelSphere(core, rs, 1f, 0.1f + 0.25f * pre * pre, time);
            // 안쪽 얇은 막 (반대로 흐름, 아주 옅게 — 물 부피의 깊이감)
            FxRender.elemShader(W, 1.4f, 0.2f, 0.35f);
            fresnelSphere(core, rs * 0.86f, 0.8f, 0.04f, -time * 1.3f);

            BufferBuilder bb;
            // 일렁이는 띠 + 퍼지는 파문 + 바닥 소용돌이
            FxRender.elemShader(W, 2f, 0.2f, 0.85f);
            bb = FxRender.quads();
            for (int k = 0; k < 2; k++) {
                double ph = time * (0.05 + 0.03 * k) + k * 2.1;
                double tilt = 0.5 + 0.5 * k;
                Vec3 nrm = new Vec3(Math.sin(tilt) * Math.cos(ph), Math.cos(tilt), Math.sin(tilt) * Math.sin(ph));
                FxRender.ring(bb, core, nrm, rs * 0.98f, rs * 1.07f, 0.55f * form, time * 0.4f * (k == 0 ? 1f : -1f), 48);
            }
            float rp = (t % 10f) / 10f;
            FxRender.ring(bb, core, UP, rs * (0.95f + 0.35f * rp), rs * (1.0f + 0.45f * rp), 0.6f * (1f - rp) * form, time * 0.2f, 48);
            Vec3 g = center.add(0, 0.05, 0);
            FxRender.ring(bb, g, UP, R * 0.3f, P * 0.85f, 0.55f * form, -time * 0.35f, 56);
            spiralArms(bb, g.add(0, 0.01, 0), P * 0.9f, R * 0.15f, 3, 0.8f, 0.9f, 0.75f * form, -time * 0.18f);
            FxRender.draw(bb);
            // 경계 = 공용 위험 표시: 이 원 안이면 끌려 들어가고, 다 차는 순간 터짐
            zone(center, P, t / duration, caster, 1f, time);

            // 안쪽 나선 물줄기 3가닥
            FxRender.elemShader(W, 2.6f, 0.15f, 1.0f);
            bb = FxRender.quads();
            final int m = 18;
            Vec3[] pts = P32;
            float[] w = F1, h = F2;
            for (int k = 0; k < 3; k++) {
                for (int j = 0; j < m; j++) {
                    float s = j / (m - 1f);
                    double y = core.y - rs * 0.85 + s * rs * 1.7;
                    double rr = rs * 0.75 * Math.sin(Math.PI * (0.08 + 0.84 * s));
                    double ang = k * TAU / 3 + s * TAU * 1.25 + time * 0.25;
                    pts[j] = new Vec3(core.x + Math.cos(ang) * rr, y, core.z + Math.sin(ang) * rr);
                    w[j] = 0.35f;
                    h[j] = 0.8f * (float) Math.sin(Math.PI * s) * form;
                }
                strip(bb, pts, m, w, h, 0.25f, -time * 0.1f + k * 0.33f, null);
            }
            FxRender.draw(bb);

            glow(core, rs * 3.2f, C_AZURE, 0.25f + 0.3f * pre);
            glow(core, rs * 1.2f, C_WHITE, 0.12f + 0.45f * pre * pre);
        }
    }

    // =====================================================================
    // 4. 해일
    // =====================================================================

    /** 파도 단면 (앞쪽 거리, 높이, 열기): 뒤쪽 낮은 물판 → 솟는 앞면 → 마루 → 앞으로 말리는 끝 */
    private static final float[] PX = {-6.0f, -3.6f, -2.3f, -1.4f, -0.7f, -0.1f, 0.5f, 1.0f, 1.3f};
    private static final float[] PY = {0.04f, 0.15f, 0.55f, 1.35f, 2.3f, 3.05f, 3.5f, 3.3f, 2.7f};
    private static final float[] PH = {0.0f, 0.12f, 0.25f, 0.4f, 0.55f, 0.75f, 0.95f, 1.0f, 0.75f};
    private static final int CREST = 6;

    private static final class WaveFx extends Fx {
        final Vec3 start, dir, side;
        final float W2, range;
        final int duration;

        @Nullable final Entity caster;

        WaveFx(@Nullable Entity caster, Vec3 start, Vec3 vec, float halfWidth, float range, int duration) {
            this.caster = caster;
            this.start = start;
            Vec3 f = new Vec3(vec.x, 0, vec.z);
            this.dir = f.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : f.normalize();
            this.side = new Vec3(-dir.z, 0, dir.x);
            this.W2 = halfWidth > 0.3f ? halfWidth : 4f;
            this.range = range > 0.5f ? range : 16f;
            this.duration = Math.max(4, duration);
            // 시작: 준비 때 일어났던 물벽 자리에서 터져 오름 (고리 = 파도 폭)
            FxManager.addRing(start.add(0, 0.06, 0), UP, 0.5f, W2, 6, 0, 0.9f, W);
            crown(start.add(dir.scale(0.8)).add(0, 0.2, 0), UP.add(dir.scale(0.6)).normalize(), 1.0f, 14);
            dropBurst(start.add(0, 0.3, 0), UP, 0.5f, 20, 0.22f);
            shake(start, 1.4f, 12f);
        }

        Vec3 front(float t) {
            return start.add(dir.scale(range * Math.min(1f, t / duration)));
        }

        float taper(float x) {
            float e = Math.abs(x / W2);
            float tw = (float) Math.sqrt(Math.max(0.0, 1.0 - Math.pow(e, 6)));
            return 0.12f + 0.88f * tw;
        }

        @Override
        Vec3 anchor() {
            return front(age);
        }

        @Override
        boolean tick() {
            age++;
            if (age > duration + 7) return true;
            Vec3 fr = front(age);
            FxManager.lod = FxManager.lodAt(fr);
            if (age == duration) {
                crash(fr);
                return false;
            }
            if (age > duration) return false;
            float hs = easeOutCubic(Math.min(1f, age / 4f));
            // 마루의 물보라·물방울
            for (int i = 0, m = count(6); i < m; i++) {
                float x = rand(-W2, W2);
                float tp = taper(x);
                Vec3 at = fr.add(dir.scale(0.6)).add(side.scale(x)).add(0, 3.5 * hs * tp, 0);
                mist(at, dir.scale(0.22).add(0, 0.07, 0), 1.0f, 2.6f, 16 + RNG.nextInt(6), 0.45f);
            }
            for (int i = 0, m = count(7); i < m; i++) {
                float x = rand(-W2, W2);
                float tp = taper(x);
                Vec3 at = fr.add(dir.scale(0.8)).add(side.scale(x)).add(0, 3.2 * hs * tp, 0);
                drop(at, dir.scale(rand(0.2f, 0.5f)).add(0, rand(0.1f, 0.3f), 0).add(side.scale(rand(-0.1f, 0.1f))),
                        rand(0.18f, 0.3f), 16 + RNG.nextInt(10), 0.04f);
            }
            // 발밑 물튀김
            for (int i = 0, m = count(4); i < m; i++) {
                float x = rand(-W2, W2);
                Vec3 at = fr.add(dir.scale(0.4)).add(side.scale(x)).add(0, 0.3, 0);
                splash(at, dir.scale(0.2).add(0, 0.1, 0), 1.2f, 2.2f, 8 + RNG.nextInt(4), 0.02f);
            }
            // 마루에서 하늘로 흩날리는 물보라 커튼
            for (int i = 0, m = count(3); i < m; i++) {
                float x = rand(-W2, W2);
                float tp = taper(x);
                Vec3 at = fr.add(dir.scale(0.3)).add(side.scale(x)).add(0, 3.3 * hs * tp, 0);
                splash(at, dir.scale(rand(0.1f, 0.2f)).add(0, rand(0.15f, 0.3f), 0), 1.4f, 2.8f, 9 + RNG.nextInt(5), 0.03f);
            }
            // 앞머리가 흙·돌을 밀어냄
            if (age % 2 == 0) {
                Vec3 g = fr.add(side.scale(rand(-W2, W2) * 0.9f)).add(dir.scale(0.6));
                DebrisFx.directed(g, dir, 2.5f, 30f, 3, 0.55f);
            }
            // 지나간 자리의 젖은 자국
            if (age % 3 == 0) {
                Vec3 at = fr.subtract(dir.scale(1.5));
                SkillFx.addDecal(at.add(side.scale(W2 * 0.5)), UP, W2 * 0.6f, 110, 0.2f, 0.35f, 0.6f);
                SkillFx.addDecal(at.add(side.scale(-W2 * 0.5)), UP, W2 * 0.6f, 110, 0.2f, 0.35f, 0.6f);
            }
            return false;
        }

        /** 끝에 부서지는 파도 */
        private void crash(Vec3 fr) {
            Vec3 c = fr.add(dir.scale(1.2));
            for (int k = -2; k <= 2; k++) {
                Vec3 at = c.add(side.scale(W2 * 0.45 * k)).add(0, 0.2, 0);
                crown(at, UP.add(dir.scale(0.5)).normalize(), 1.2f, 8);
                FxManager.addRing(at.add(0, -0.12, 0), UP, 0.4f, W2 * 0.9f, 10, Math.abs(k), 0.8f, W);
            }
            for (int i = 0, m = count(50); i < m; i++) {
                float x = rand(-W2, W2);
                Vec3 at = c.add(side.scale(x)).add(0, rand(0.5f, 3f), 0);
                drop(at, dir.scale(rand(0.2f, 0.6f)).add(0, rand(0.15f, 0.45f), 0).add(side.scale(rand(-0.15f, 0.15f))),
                        rand(0.2f, 0.32f), 18 + RNG.nextInt(14), 0.045f);
            }
            for (int i = 0, m = count(14); i < m; i++) {
                float x = rand(-W2, W2);
                mist(c.add(side.scale(x)).add(0, rand(0.5f, 2.5f), 0), dir.scale(0.08).add(0, 0.03, 0), 1.6f, 4f, 28 + RNG.nextInt(12), 0.5f);
            }
            flashGlow(c.add(0, 1.5, 0), W2 * 2.5f, W2 * 3.5f, 10, C_AZURE, 0.5f);
            SkillFx.addDecal(c, UP, W2 * 1.1f, 140, 0.2f, 0.35f, 0.6f);
            shake(c, 2.2f, 16f);
            DebrisFx.directed(c, dir, W2 * 0.9f, 60f, 14, 0.85f);
            ripples(c, W2 * 1.6f, 3, 3);
        }

        @Override
        void render(float pt, float time) {
            float t = age + pt;
            float crashK = t > duration ? clamp01((t - duration) / 6f) : 0f;
            float fade = 1f - crashK;
            if (fade <= 0.01f) return;
            float hs = easeOutCubic(Math.min(1f, t / 4f)) * (1f - 0.85f * crashK);
            float erosion = 0.08f + 0.8f * crashK;
            float traveled = range * Math.min(1f, t / duration);
            Vec3 fr = front(t).add(0, 0.05, 0);

            final int cols = 16, rows = PX_ROWS;
            Vec3[] grid = GRID;
            float[] heat = GHEAT;
            for (int c = 0; c <= cols; c++) {
                float x = -W2 + 2f * W2 * c / cols;
                float tp = taper(x);
                float wob = 1f + 0.07f * (float) Math.sin(x * 0.8f + time * 0.35f);
                for (int r = 0; r < rows; r++) {
                    float px = PX[r];
                    if (px < 0) px = Math.max(px, -(traveled + 0.3f));
                    px *= 0.6f + 0.4f * hs;
                    float py = PY[r] * hs * tp * wob;
                    if (r >= 5) px += 0.15f * (float) Math.sin(x * 0.6f - time * 0.5f + r);
                    if (r >= CREST) {
                        // 부서질 때 말린 끝이 앞으로 쏟아짐
                        px += 1.4f * crashK;
                        py *= 1f - 0.5f * crashK;
                    }
                    grid[r * (cols + 1) + c] = fr.add(dir.scale(px)).add(side.scale(x)).add(0, py, 0);
                    heat[r * (cols + 1) + c] = PH[r] * (0.3f + 0.7f * tp) * (0.85f + 0.15f * (float) Math.sin(x * 1.3f + time * 0.5f));
                }
            }
            RenderSystem.disableCull();
            FxRender.elemShader(W, 1.4f, erosion, 1.0f * fade);
            BufferBuilder bb = FxRender.quads();
            for (int r = 0; r < rows - 1; r++) {
                float v0 = r * 0.22f, v1 = (r + 1) * 0.22f;
                for (int c = 0; c < cols; c++) {
                    float u0 = c * 2f * W2 / cols * 0.12f, u1 = (c + 1) * 2f * W2 / cols * 0.12f;
                    int i00 = r * (cols + 1) + c, i01 = i00 + 1, i10 = i00 + cols + 1, i11 = i10 + 1;
                    Vec3 a = grid[i00], b = grid[i01], cc = grid[i11], d = grid[i10];
                    FxRender.vertex(bb, a.x, a.y, a.z, u0, v0, 1f, 1f, 1f, heat[i00]);
                    FxRender.vertex(bb, b.x, b.y, b.z, u1, v0, 1f, 1f, 1f, heat[i01]);
                    FxRender.vertex(bb, cc.x, cc.y, cc.z, u1, v1, 1f, 1f, 1f, heat[i11]);
                    FxRender.vertex(bb, d.x, d.y, d.z, u0, v1, 1f, 1f, 1f, heat[i10]);
                }
            }
            FxRender.draw(bb);

            // 마루의 흰 물거품 띠 + 말린 끝 띠
            FxRender.elemShader(W, 3.0f, Math.min(1f, 0.3f + erosion), 1.2f * fade);
            bb = FxRender.quads();
            Vec3[] line = P32;
            float[] lw = F1, lh = F2;
            for (int c = 0; c <= cols; c++) {
                float tp = taper(-W2 + 2f * W2 * c / cols);
                line[c] = grid[CREST * (cols + 1) + c].add(0, 0.12, 0);
                lw[c] = 0.9f * tp;
                lh[c] = tp;
            }
            strip(bb, line, cols + 1, lw, lh, 0.15f, -time * 0.2f, null);
            for (int c = 0; c <= cols; c++) {
                float tp = taper(-W2 + 2f * W2 * c / cols);
                line[c] = grid[(rows - 1) * (cols + 1) + c];
                lw[c] = 0.5f * tp;
                lh[c] = 0.8f * tp;
            }
            strip(bb, line, cols + 1, lw, lh, 0.2f, time * 0.15f, null);
            // 앞면 아래쪽에서 부글거리는 흰 거품 띠
            for (int c = 0; c <= cols; c++) {
                float x = -W2 + 2f * W2 * c / cols;
                float tp = taper(x);
                line[c] = grid[3 * (cols + 1) + c].add(dir.scale(0.25)).add(0, 0.1 + 0.08 * Math.sin(x * 2.1f + time * 0.9f), 0);
                lw[c] = 1.1f * tp;
                lh[c] = 0.75f * tp;
            }
            strip(bb, line, cols + 1, lw, lh, 0.3f, -time * 0.35f, null);
            FxRender.draw(bb);

            glow(fr.add(0, 2.0 * hs, 0), W2 * 2.6f, C_AZURE, 0.18f * fade);
            // 밀려가는 동안: 출발 → 끝 전체 길 (폭 = 서버 폭), 파도 앞머리가 지난 만큼 차오름. 부서지는 6틱에는 테두리 없음
            if (t <= duration) lane(start, dir, range, W2, t / duration, caster, 1f, time);
        }
    }

    // =====================================================================
    // 5. 해룡 강림 (궁극기)
    // =====================================================================

    private static final class DragonFx extends Fx {
        static final int SEGS = 28;
        static final int RAIN = 36;
        static final float LAG = 0.45f;
        static final float RISE = 0.55f;
        final Vec3 S, T, A;
        final float R;
        final int whirl, flight;
        final double ang0;

        @Nullable final Entity caster;

        DragonFx(@Nullable Entity caster, Vec3 start, Vec3 target, float radius, int whirl, int flight) {
            this.caster = caster;
            this.S = start;
            this.T = target;
            this.R = radius > 0.5f ? radius : 7f;
            this.whirl = Math.max(0, whirl);
            this.flight = Math.max(6, flight);
            this.A = lerp(start, target, 0.5).add(0, 16, 0);
            this.ang0 = Math.atan2(start.z - target.z, start.x - target.x);
            // 시전자 발밑에서 물이 터져 오르며 용이 솟음
            Vec3 feet = start.add(0, -1, 0);
            flashGlow(start, 5f, 9f, 6, C_WHITE, 0.9f);
            FxManager.addRing(feet.add(0, 0.06, 0), UP, 0.5f, 6f, 12, 0, 1f, W);
            crown(feet.add(0, 0.2, 0), UP, 1.4f, 26);
            dropBurst(feet.add(0, 0.4, 0), UP, 0.8f, 40, 0.26f);
            mistBurst(feet.add(0, 0.6, 0), 1.6f, 8, 0.45f);
            shake(start, 1.6f, 20f);
            // 용이 솟는 동안 하늘이 어두워지고 바다색으로 물듦
            ScreenFx.darken(lerp(start, target, 0.5), R * 3f + 16f, 0.4f, this.flight + this.whirl + 30);
            DebrisFx.burst(groundAt(start), 2.0f, 10, 0.7f);
        }

        /** 머리의 경로 (t = 틱). t < 0: 땅속에서 올라옴, t > flight: 땅속으로 꽂힘 */
        Vec3 path(float t) {
            if (t <= 0) return S.add(0, t * 0.6, 0);
            if (t >= flight) return T.add(0, -(t - flight) * 1.6, 0);
            float u = t / flight;
            if (u < RISE) {
                // 시전자 주위를 나선으로 감으며 솟음 (꼭대기에서 잠깐 멈춤)
                float f = u / RISE;
                float ey = easeOutCubic(f);
                float hor = f * f;
                double ang = ang0 + f * TAU * 1.5;
                double rad = 3.6 * Math.sin(Math.PI * f) * (1 - 0.3 * f);
                return new Vec3(S.x + (A.x - S.x) * hor + Math.cos(ang) * rad,
                        S.y + (A.y - S.y) * ey,
                        S.z + (A.z - S.z) * hor + Math.sin(ang) * rad);
            }
            // 점점 빨라지며 내리꽂힘 (2차 베지어, age == flight 에 정확히 도착)
            float f = (u - RISE) / (1f - RISE);
            double g = f * f;
            Vec3 p1 = A.add((T.x - A.x) * 0.35, 3.0, (T.z - A.z) * 0.35);
            double a = (1 - g) * (1 - g), b = 2 * (1 - g) * g, c = g * g;
            return new Vec3(A.x * a + p1.x * b + T.x * c, A.y * a + p1.y * b + T.y * c, A.z * a + p1.z * b + T.z * c);
        }

        @Override
        Vec3 anchor() {
            return age <= flight ? path(age) : T;
        }

        @Override
        boolean tick() {
            age++;
            if (age > flight + Math.max(whirl + 12, RAIN)) return true;
            if (age < flight + 13) shed();
            if (age == (int) (flight * RISE)) roar();
            if (age == flight) impact();
            if (age > flight && age <= flight + whirl) whirlTick();
            if (age > flight && age <= flight + RAIN) rain();
            return false;
        }

        private void shed() {
            Vec3 head = path(age);
            FxManager.lod = FxManager.lodAt(head);
            for (int i = 0, m = count(6); i < m; i++) {
                int s = RNG.nextInt(SEGS);
                float st = age - s * LAG;
                if (st < 0 || st > flight) continue;
                Vec3 at = path(st).add(randomDir().scale(segRadius(s) * 1.1f));
                drop(at, randomDir().scale(0.08), rand(0.2f, 0.32f), 18 + RNG.nextInt(12), 0.035f);
            }
            for (int i = 0, m = count(2); i < m; i++) {
                int s = RNG.nextInt(SEGS);
                float st = age - s * LAG;
                if (st < 0 || st > flight) continue;
                mist(path(st).add(randomDir().scale(0.8)), new Vec3(0, 0.02, 0), 1.4f, 3.2f, 20, 0.35f);
            }
            if (age <= flight) {
                for (int i = 0, m = count(2); i < m; i++) {
                    splash(head.add(randomDir().scale(1.2)), randomDir().scale(0.08), 1.4f, 2.4f, 7, 0.01f);
                }
            }
        }

        /** 꼭대기에서 잠깐 멈춰 포효: 공중 충격파 + 번쩍 */
        private void roar() {
            Vec3 h = path(age);
            FxManager.lod = FxManager.lodAt(h);
            flashGlow(h, 6f, 12f, 6, C_WHITE, 0.9f);
            FxManager.addRing(h, UP, 1f, 9f, 12, 0, 0.9f, W);
            FxManager.addRing(h, UP, 0.6f, 6f, 10, 3, 0.7f, W);
            dropBurst(h, UP, 0.9f, 30, 0.3f);
            shake(h, 1.4f, 40f);
        }

        /** 충돌 뒤: 하늘로 튄 바닷물이 넓게 비처럼 쏟아짐 */
        private void rain() {
            float lf = 1f - (age - flight) / (float) RAIN;
            FxManager.lod = FxManager.lodAt(T);
            for (int i = 0, m = count(5); i < m; i++) {
                if (RNG.nextFloat() > lf) continue;
                float ang = RNG.nextFloat() * TAU;
                float rr = R * 1.8f * (float) Math.sqrt(RNG.nextFloat());
                drop(T.add(Math.cos(ang) * rr, rand(8f, 13f), Math.sin(ang) * rr), new Vec3(0, -rand(0.3f, 0.5f), 0), rand(0.2f, 0.3f), 30, 0.04f);
            }
        }

        private void impact() {
            FxManager.lod = FxManager.lodAt(T);
            Vec3 c = T.add(0, 0.08, 0);
            SkillFx.addPillar(T, 2.5f, 14f, 10, false, W);
            flashGlow(T.add(0, 1.5, 0), 14f, 22f, 7, C_WHITE, 1f);
            flashGlow(T.add(0, 3, 0), R * 4f, R * 6f, 16, C_AZURE, 0.8f);
            // 피해 반지름 R 에서 멈추는 충격파 + 바깥으로 흩어지는 옅은 고리 (궁극기다운 크기는 높이·섬광·흔들림으로)
            FxManager.addRing(c, UP, 1f, R, 8, 0, 1f, W);
            FxManager.addRing(c, UP, 0.6f, R * 0.7f, 8, 2, 0.9f, W);
            FxManager.addRing(c.add(0, 0.02, 0), UP, R * 0.9f, R * 1.6f, 14, 3, 0.45f, W);
            FxManager.addRing(T.add(0, 3, 0), UP, 0.8f, R * 1.1f, 10, 1, 0.6f, W);
            for (int k = 0; k < 3; k++) {
                crown(T.add(0, 0.3, 0), UP, 2.0f + k * 0.6f, 18);
            }
            for (int i = 0, m = count(30); i < m; i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 d = new Vec3(Math.cos(ang), 0.25, Math.sin(ang));
                splash(T.add(d.scale(R * 0.4)).add(0, 0.4, 0), d.scale(rand(0.35f, 0.7f)), 1.6f, 3.2f, 12 + RNG.nextInt(6), 0.02f);
            }
            dropBurst(T.add(0, 0.5, 0), UP, 1.6f, 120, 0.34f);
            mistBurst(T.add(0, 1, 0), R * 0.6f, 24, 0.55f);
            SkillFx.addDecal(T, UP, R * 1.15f, 260, 0.2f, 0.35f, 0.6f);
            // 땅이 크게 패고 조각이 사방으로 + 바깥으로 밀려가는 둥근 해일 벽
            DebrisFx.burst(T, R, 48, 1.5f);
            DebrisFx.crater(T, R * 1.15f, whirl + 220, W);
            add(new TsunamiRingFx(T, R));
            ripples(T, R * 1.3f, 2, 14);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                double d = mc.player.position().distanceTo(T);
                if (d < 48) limitedFlash(d < 20 ? 0xE6F7FF : 0x6FC8FF, 0.4f + 0.45f * (float) (1 - d / 48), 18);
            }
            shake(T, 6.5f, 48f);
        }

        private void whirlTick() {
            FxManager.lod = FxManager.lodAt(T);
            for (int i = 0, m = count(5); i < m; i++) {
                float ang = RNG.nextFloat() * TAU;
                float rr = R * rand(0.4f, 1.2f);
                Vec3 at = T.add(Math.cos(ang) * rr, 0.25, Math.sin(ang) * rr);
                Vec3 v = new Vec3(-Math.sin(ang) * 0.3 - Math.cos(ang) * 0.08, 0.05, Math.cos(ang) * 0.3 - Math.sin(ang) * 0.08);
                splash(at, v, 1.0f, 1.8f, 9, 0f);
            }
            for (int i = 0, m = count(3); i < m; i++) {
                float ang = RNG.nextFloat() * TAU;
                float rr = R * rand(0.1f, 0.4f);
                drop(T.add(Math.cos(ang) * rr, 0.3, Math.sin(ang) * rr), new Vec3(rand(-0.08f, 0.08f), rand(0.25f, 0.5f), rand(-0.08f, 0.08f)),
                        rand(0.2f, 0.3f), 20, 0.04f);
            }
            for (int i = 0, m = count(1); i < m; i++) {
                float ang = RNG.nextFloat() * TAU;
                mist(T.add(Math.cos(ang) * R * 0.8, 0.4, Math.sin(ang) * R * 0.8), new Vec3(0, 0.02, 0), 1.6f, 3.6f, 24, 0.35f);
            }
            // 서버가 끌어당기는 바로 그 틱마다 테두리에서 안으로 조여드는 고리
            if ((age - flight) % 10 == 0) FxManager.addRing(T.add(0, 0.07, 0), UP, R, R * 0.2f, 10, 0, 0.6f, W);
        }

        float segRadius(int i) {
            float f = i / (SEGS - 1f);
            return 0.3f + 1.3f * (float) Math.pow(1f - f, 0.85);
        }

        @Override
        void render(float pt, float time) {
            float t = age + pt;
            if (t - (SEGS - 1) * LAG < flight + 0.5f) renderBody(t, time);
            if (t > flight && whirl > 0) renderWhirl(t, time);
            // 떨어질 곳: 비행 중에는 충돌까지 차오름, 소용돌이 동안은 서버가 끌어당기고 피해를 주는 10틱마다 차오름
            if (t <= flight) zone(T, R, t / flight, caster, 1f, time);
            else if (t <= flight + whirl) zone(T, R, ((age - flight) % 10 + pt) / 10f, caster, 1f, time);
        }

        private void renderBody(float t, float time) {
            Vec3[] pts = P32;
            float[] r = F1, h = F2, w = F3, h2 = F4;
            float appear = 0.4f + 0.6f * clamp01(t / 4f);
            for (int i = 0; i < SEGS; i++) {
                pts[i] = path(t - i * LAG);
                float f = i / (SEGS - 1f);
                r[i] = segRadius(i) * (1f + 0.08f * (float) Math.sin(i * 0.8f - time * 0.5f)) * appear;
                h[i] = 0.9f * (1f - 0.4f * f);
                w[i] = r[i] * 0.55f;
                h2[i] = 1f - 0.5f * f;
            }
            RenderSystem.disableCull();
            // 몸통 (물이 꼬리 쪽으로 흐르며 꼬임)
            FxRender.elemShader(W, 2.2f, 0.06f, 1.0f);
            BufferBuilder bb = FxRender.quads();
            tube(bb, pts, SEGS, r, h, 10, 0.6f, 0.2f, -time * 0.12f, 0.07f, time);
            FxRender.draw(bb);
            // 구슬 마디 (머리 + 가까우면 몸통 몇 군데)
            FxRender.elemShader(W, 1.6f, 0.03f, 1.05f);
            FxManager.BlastSphere.draw(pts[0], r[0] * 1.05f, time * 1.4f);
            if (pts[0].distanceToSqr(FxRender.cam) < 48 * 48) {
                for (int i = 7; i < SEGS - 4; i += 7) FxManager.BlastSphere.draw(pts[i], r[i] * 1.12f, time * 1.2f + i);
            }
            // 몸을 따라 빛나는 심
            FxRender.elemShader(W, 4f, 0.1f, 1.3f);
            bb = FxRender.quads();
            strip(bb, pts, SEGS, w, h2, 0.25f, -time * 0.3f, null);
            FxRender.draw(bb);

            // 머리 방향
            Vec3 H = pts[0];
            Vec3 F = H.subtract(path(t - 0.35f));
            if (F.lengthSqr() < 1.0E-6) F = H.subtract(pts[1]);
            F = F.lengthSqr() < 1.0E-6 ? UP : F.normalize();
            Vec3 upv = UP.subtract(F.scale(UP.dot(F)));
            upv = upv.lengthSqr() < 1.0E-4 ? FxRender.basis(F)[0] : upv.normalize();
            Vec3 rt = F.cross(upv).normalize();
            float hr = r[0];

            // 뿔·수염·등지느러미 (빛 줄기)
            FxRender.spriteShader(FxRender.SPARK_WHITE);
            bb = FxRender.quads();
            for (int s = -1; s <= 1; s += 2) {
                Vec3 base = H.add(upv.scale(hr * 0.55)).add(rt.scale(hr * 0.45 * s)).add(F.scale(hr * 0.1));
                Vec3 tip = H.subtract(F.scale(hr * 1.9)).add(upv.scale(hr * 1.5)).add(rt.scale(hr * 0.95 * s));
                FxRender.streak(bb, tip, base, hr * 0.38f, C_AQUA[0], C_AQUA[1], C_AQUA[2], 1f);
                Vec3 snout = H.add(F.scale(hr * 0.95)).add(rt.scale(hr * 0.35 * s)).subtract(upv.scale(hr * 0.15));
                Vec3 wt = pts[4].add(rt.scale(hr * 1.4 * s)).add(upv.scale(Math.sin(time * 0.4 + s) * 0.5));
                FxRender.streak(bb, wt, snout, 0.18f, C_WHITE[0] * 0.8f, C_WHITE[1] * 0.8f, C_WHITE[2] * 0.8f, 0.8f);
            }
            for (int i = 4; i < SEGS - 4; i += 4) {
                Vec3 tg = pts[i - 1].subtract(pts[i + 1]);
                if (tg.lengthSqr() < 1.0E-6) continue;
                tg = tg.normalize();
                Vec3 fu = UP.subtract(tg.scale(UP.dot(tg)));
                if (fu.lengthSqr() < 1.0E-4) continue;
                fu = fu.normalize();
                Vec3 fb = pts[i].add(fu.scale(r[i] * 0.8));
                Vec3 ft = fb.add(fu.scale(r[i] * 1.1)).subtract(tg.scale(r[i] * 1.2));
                float fa = 0.75f * (1f - i / (float) SEGS) + 0.2f;
                FxRender.streak(bb, ft, fb, r[i] * 0.5f, C_AZURE[0] * fa, C_AZURE[1] * fa, C_AZURE[2] * fa, fa);
            }
            FxRender.draw(bb);

            // 빛나는 두 눈
            for (int s = -1; s <= 1; s += 2) {
                Vec3 eye = H.add(F.scale(hr * 0.6)).add(upv.scale(hr * 0.38)).add(rt.scale(hr * 0.5 * s));
                glow(eye, 0.55f, C_WHITE, 1f);
                glow(eye, 1.8f, C_AQUA, 0.65f);
            }
            glow(H, hr * 5.5f, C_AZURE, 0.35f);
            glow(pts[SEGS / 2], 6f, C_AZURE, 0.18f);
        }

        private void renderWhirl(float t, float time) {
            float lt = t - flight;
            float env = smooth(lt / 5f) * (1f - clamp01((lt - whirl) / 12f));
            if (env <= 0.01f) return;
            Vec3 c = T.add(0, 0.06, 0);
            RenderSystem.disableCull();
            // 바닥에서 도는 소용돌이 고리 + 나선 팔
            FxRender.elemShader(W, 1.8f, 0.12f + 0.5f * (1f - env), 1.05f * env);
            BufferBuilder bb = FxRender.quads();
            // 소용돌이 = 서버가 0.5초마다 끌어당기고 피해를 주는 반지름 R (테두리 실선)
            FxRender.ring(bb, c, UP, R * 0.15f, R * 0.88f, 0.75f, -time * 0.45f, 64);
            spiralArms(bb, c.add(0, 0.02, 0), R * 0.9f, R * 0.12f, 4, 0.9f, 1.3f, 0.9f, -time * 0.22f);
            FxRender.draw(bb);
            // 가운데 솟는 물기둥
            FxRender.elemShader(W, 2.6f, 0.25f + 0.5f * (1f - env), 0.9f * env);
            SkillFx.column(T, R * 0.22f, R * 0.08f, 4.5f * env + 0.5f, 0.85f, 0.9f, 0f, 0.1f, time);
            // 감아 오르는 물줄기 3가닥
            FxRender.elemShader(W, 3f, 0.15f, 1.1f * env);
            bb = FxRender.quads();
            final int m = 16;
            Vec3[] pts = P32;
            float[] w = F1, h = F2;
            for (int k = 0; k < 3; k++) {
                for (int j = 0; j < m; j++) {
                    float s = j / (m - 1f);
                    double rr = R * 0.55 * (1 - s * 0.75) + 0.3;
                    double ang = k * TAU / 3 + s * TAU * 1.4 - time * 0.4;
                    pts[j] = T.add(Math.cos(ang) * rr, s * 6.0 * env, Math.sin(ang) * rr);
                    w[j] = 0.55f * (1f - s * 0.6f);
                    h[j] = (float) Math.sin(Math.PI * s);
                }
                strip(bb, pts, m, w, h, 0.25f, -time * 0.2f + k * 0.3f, null);
            }
            FxRender.draw(bb);
            glow(T.add(0, 1, 0), R * 2.2f, C_AZURE, 0.25f * env);
        }
    }

    /** 해룡 충돌: 바깥으로 밀려가며 낮아지는 둥근 물의 벽 */
    private static final class TsunamiRingFx extends Fx {
        static final int LIFE = 18;
        final Vec3 c;
        final float R;

        TsunamiRingFx(Vec3 c, float R) {
            this.c = c;
            this.R = R;
        }

        @Override
        boolean tick() {
            age++;
            if (age <= LIFE * 0.7f && age % 2 == 0) {
                FxManager.lod = FxManager.lodAt(c);
                float r = radius(age);
                for (int i = 0, m = count(8); i < m; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    Vec3 d = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                    splash(c.add(d.scale(r)).add(0, height(age) * rand(0.5f, 1f), 0), d.scale(rand(0.15f, 0.3f)).add(0, rand(0.1f, 0.25f), 0),
                            1.6f, 3f, 10 + RNG.nextInt(5), 0.03f);
                }
            }
            return age > LIFE;
        }

        /** 충돌 피해 반지름 R 까지 빠르게 밀려가 조금 넘어선 뒤 무너짐 (경계가 읽힘) */
        float radius(float t) {
            return R * (0.3f + 0.85f * easeOutCubic(clamp01(t / (LIFE * 0.6f))));
        }

        float height(float t) {
            float f = clamp01(t / LIFE);
            return 4.2f * (float) Math.sin(Math.PI * Math.min(1f, f * 1.4f)) * (1f - f * 0.5f) + 0.2f;
        }

        @Override
        void render(float pt, float time) {
            float t = age + pt;
            float f = clamp01(t / LIFE);
            float fade = 1f - f * f;
            if (fade <= 0.01f) return;
            float r = radius(t);
            RenderSystem.disableCull();
            FxRender.elemShader(W, 2.0f, 0.1f + 0.75f * f, 1.05f * fade);
            SkillFx.column(c, r * 0.97f, r * 1.06f, height(t), 0.95f, 0.04f, time * 0.02f, 0.08f, time);
            FxRender.elemShader(W, 3.0f, Math.min(1f, 0.25f + 0.7f * f), 1.2f * fade);
            BufferBuilder bb = FxRender.quads();
            FxRender.ring(bb, c.add(0, height(t) * 0.92, 0), UP, r * 0.96f, r * 1.1f, 0.9f * fade, time * 0.4f, 64);
            FxRender.ring(bb, c.add(0, 0.06, 0), UP, r * 0.8f, r * 1.12f, 0.7f * fade, -time * 0.3f, 64);
            FxRender.draw(bb);
        }

        @Override
        Vec3 anchor() {
            return c;
        }
    }

    // =====================================================================
    // 준비 동작
    // =====================================================================

    static void tickCharge(FxManager.ChargeFx c, float k) {
        Entity e = c.caster;
        switch (c.skill) {
            case WATER_BULLET -> gather(AimHand.hand(c, 1f, AimHand.SIDE), 3, 1.6f);
            case WATER_JET -> {
                // 물줄기가 나갈 손(서버 Jet 과 같은 자리) + 고정된 시전 방향
                Vec3 hand = AimHand.hand(c, 1f, AimHand.SIDE);
                gather(hand, 3, 1.4f);
                Vec3 look = c.aimDir();
                Vec3[] b = FxRender.basis(look);
                for (int j = 0; j < 2; j++) {
                    if (RNG.nextFloat() > FxManager.amount() * FxManager.lod) continue;
                    double ang = c.age * 1.1 + j * Math.PI;
                    Vec3 off = b[0].scale(Math.cos(ang) * 0.8).add(b[1].scale(Math.sin(ang) * 0.8));
                    drop(hand.add(off), off.scale(-0.15).add(look.scale(0.05)), 0.2f, 5, 0f);
                }
                if (c.age % 2 == 0) mist(hand, look.scale(0.05), 0.4f, 1.0f, 8, 0.25f);
            }
            case WATER_PRISON -> {
                FxManager.lod = FxManager.lodAt(c.target);
                float r = prisonR(c.size);
                for (int i = 0, m = count(3 + (int) (6 * k)); i < m; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = r * (float) Math.sqrt(RNG.nextFloat());
                    drop(c.target.add(Math.cos(ang) * rr, 0.1, Math.sin(ang) * rr), new Vec3(0, rand(0.06f, 0.15f) * (0.5f + k), 0),
                            rand(0.14f, 0.22f), 10 + RNG.nextInt(10), -0.003f);
                }
                // 테두리에서 솟는 물 (경계가 어디인지)
                for (int i = 0, m = count(1 + (int) (3 * k)); i < m; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    splash(c.target.add(Math.cos(ang) * r, 0.3, Math.sin(ang) * r), new Vec3(0, 0.06, 0), 0.6f + 0.5f * k, 0.3f, 7, 0f);
                }
                gather(AimHand.hand(c, 1f, AimHand.SIDE), 2, 1.4f);
            }
            case WATER_WAVE -> {
                // 발밑 앞에서 파도 벽이 일어남 (폭 = 실제 폭, 방향 = 고정된 시전 방향) → 해일이 바로 그 자리에서 출발
                Vec3 feet = e.position();
                Vec3 fwd = c.flatDir(), side = new Vec3(-fwd.z, 0, fwd.x);
                float hw = waveHalf(c);
                for (int i = 0, m = count(4 + (int) (5 * k)); i < m; i++) {
                    float x = rand(-hw, hw);
                    drop(feet.add(side.scale(x)).add(fwd.scale(rand(0.2f, 1.2f))).add(0, 0.1, 0),
                            new Vec3(0, rand(0.12f, 0.25f), 0).add(fwd.scale(0.04)), rand(0.16f, 0.24f), 10, 0.02f);
                }
                for (int i = 0, m = count(2 + (int) (2 * k)); i < m; i++) {
                    float x = rand(-hw, hw);
                    splash(feet.add(side.scale(x)).add(fwd.scale(0.6)).add(0, 0.25, 0), new Vec3(0, 0.08, 0).add(fwd.scale(0.03)),
                            0.8f + 0.5f * k, 0.4f, 7, 0f);
                }
            }
            case WATER_DRAGON -> {
                Vec3 feet = e.position();
                for (int i = 0; i < 3; i++) {
                    if (RNG.nextFloat() > FxManager.amount() * FxManager.lod) continue;
                    double ang = c.age * 0.55 + i * TAU / 3;
                    double rr = 1.7;
                    Vec3 at = feet.add(Math.cos(ang) * rr, 0.2 + RNG.nextFloat() * 6 * k, Math.sin(ang) * rr);
                    Vec3 v = new Vec3(-Math.sin(ang) * 0.25, 0.3 + 0.3 * k, Math.cos(ang) * 0.25);
                    splash(at, v, 1.0f + 0.5f * k, 0.5f, 8, -0.01f);
                }
                if (c.age % 3 == 0) mist(feet.add(0, 0.3, 0), new Vec3(0, 0.03, 0), 1.4f, 3f, 20, 0.3f);
                FxManager.lod = FxManager.lodAt(c.target);
                float r = c.size;
                for (int i = 0, m = count(3 + (int) (5 * k)); i < m; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = r * (float) Math.sqrt(RNG.nextFloat());
                    drop(c.target.add(Math.cos(ang) * rr, 0.1, Math.sin(ang) * rr), new Vec3(0, rand(0.08f, 0.2f) * (0.5f + k), 0),
                            rand(0.16f, 0.26f), 12 + RNG.nextInt(10), -0.003f);
                }
                for (int i = 0, m = count(2); i < m; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    splash(c.target.add(Math.cos(ang) * r, 0.3, Math.sin(ang) * r), new Vec3(0, 0.08, 0), 0.8f + 0.8f * k, 0.4f, 8, 0f);
                }
                // 모을수록 땅이 울리고 목표 테두리에서 흙이 들썩임
                if (c.age % 2 == 0) shake(c.target, 0.15f + 0.45f * k, r * 4f);
                if (k > 0.5f && c.age % 4 == 0) {
                    float ang = RNG.nextFloat() * TAU;
                    DebrisFx.burst(c.target.add(Math.cos(ang) * r, 0, Math.sin(ang) * r), 0.8f, 2, 0.3f + 0.4f * k);
                }
            }
            default -> { }
        }
    }

    static void renderCharge(FxManager.ChargeFx c, float pt, float k, float time) {
        Entity e = c.caster;
        if (e.isRemoved()) return;
        switch (c.skill) {
            case WATER_BULLET -> {
                Vec3 hand = AimHand.hand(c, pt, AimHand.SIDE);
                Vec3 look = c.aimDir();
                float s = fpScale(e) * (0.45f + 0.55f * easeOutCubic(k * 1.4f));
                RenderSystem.disableCull();
                FxRender.elemShader(W, 2f, 0f, 1.05f);
                FxManager.BlastSphere.draw(hand, (0.1f + 0.25f * k) * s, time * 3f);
                FxRender.elemShader(W, 2.4f, 0.05f, 1f);
                BufferBuilder bb = FxRender.quads();
                FxRender.ring(bb, hand, look, 0.42f * s, 0.52f * s, 0.8f, time * 0.6f, 32);
                FxRender.draw(bb);
                Vec3[] b = FxRender.basis(look);
                for (int j = 0; j < 3; j++) {
                    double ang = time * 0.5 + j * TAU / 3;
                    Vec3 p = hand.add(b[0].scale(Math.cos(ang) * 0.55 * s)).add(b[1].scale(Math.sin(ang) * 0.55 * s))
                            .add(look.scale(0.1 * Math.sin(time * 0.7 + j)));
                    glow(p, 0.32f * s, C_WHITE, 0.9f);
                    glow(p, 0.9f * s, C_AZURE, 0.5f);
                }
                glow(hand, (0.8f + 1.4f * k) * s, C_AZURE, 0.4f + 0.3f * k);
            }
            case WATER_JET -> {
                Vec3 hand = AimHand.hand(c, pt, AimHand.SIDE);
                Vec3 look = c.aimDir();
                float s = fpScale(e) * (0.5f + 0.5f * k);
                RenderSystem.disableCull();
                FxRender.elemShader(W, 2.6f, 0.08f, 1.0f + 0.3f * k);
                BufferBuilder bb = FxRender.quads();
                FxRender.ring(bb, hand.add(look.scale(0.1)), look, 0.3f * s, 0.42f * s, 0.9f, time * 0.9f, 32);
                FxRender.ring(bb, hand.subtract(look.scale(0.2)), look, 0.55f * s, 0.66f * s, 0.7f, -time * 0.7f, 32);
                // 손으로 감겨 드는 물줄기 2가닥
                final int m = 12;
                Vec3[] pts = P32;
                float[] w = F1, h = F2;
                Vec3[] b = FxRender.basis(look);
                for (int strand = 0; strand < 2; strand++) {
                    for (int j = 0; j < m; j++) {
                        float f = j / (m - 1f);
                        double rr = (0.75 * (1 - f) + 0.08) * s;
                        double ang = f * TAU * 1.5 + time * 0.5 + strand * Math.PI;
                        pts[j] = hand.subtract(look.scale(1.0 * (1 - f) * s)).add(b[0].scale(Math.cos(ang) * rr)).add(b[1].scale(Math.sin(ang) * rr));
                        w[j] = 0.16f * s;
                        h[j] = (float) Math.sin(Math.PI * f) * (0.4f + 0.6f * k);
                    }
                    strip(bb, pts, m, w, h, 0.4f, -time * 0.3f, null);
                }
                FxRender.draw(bb);
                glow(hand, (0.9f + 1.2f * k) * s, C_AZURE, 0.45f + 0.3f * k);
                glow(hand, 0.4f * s, C_WHITE, 0.7f);
            }
            case WATER_PRISON -> {
                // 실제로 끌려 들어가는 원통(반지름 r × 1.4) 그대로: 바닥 원 + 차오르는 물벽
                float pr = prisonR(c.size);
                groundCircle(c.target, pr, k, time, e);
                FxRender.elemShader(W, 1.2f, 0.55f - 0.25f * k, 0.5f + 0.4f * k);
                RenderSystem.disableCull();
                SkillFx.column(c.target, pr, pr * 0.98f, 0.3f + 2.2f * k, 0.55f, 0.15f, 0f, 0.04f, time);
                glow(AimHand.hand(c, pt, AimHand.SIDE), (0.6f + 0.8f * k) * fpScale(e), C_AZURE, 0.35f + 0.3f * k);
            }
            case WATER_WAVE -> {
                Vec3 feet = e.getPosition(pt);
                Vec3 fwd = c.flatDir(), side = new Vec3(-fwd.z, 0, fwd.x);
                float hw = waveHalf(c);
                float ek = easeOutCubic(k);
                RenderSystem.disableCull();
                // 발 주위 소용돌이 (작게)
                FxRender.elemShader(W, 1.8f, 0.3f - 0.2f * k, 0.6f + 0.4f * k);
                SkillFx.column(feet, 0.7f + 0.3f * k, 0.45f + 0.2f * k, 0.3f + 0.9f * k, 0.6f, 0.4f, 0f, 0.08f, time);
                // 앞쪽에 일어나는 낮은 물벽: 서버 해일과 같은 폭(±hw)·방향, 모일수록 높아짐
                FxRender.elemShader(W, 2.2f, 0.25f - 0.2f * k, 0.8f + 0.4f * k);
                BufferBuilder bb = FxRender.quads();
                final int cols = 14;
                float wallH = 0.3f + 1.5f * ek;
                for (int i = 0; i <= cols; i++) {
                    float x = -hw + 2f * hw * i / cols;
                    float e2 = Math.abs(x / hw);
                    float tp = 0.25f + 0.75f * (float) Math.sqrt(Math.max(0.0, 1.0 - e2 * e2 * e2 * e2));
                    float hh = wallH * tp * (1f + 0.08f * (float) Math.sin(x * 1.3f + time * 0.6f));
                    P32[i] = feet.add(side.scale(x)).add(fwd.scale(0.7 + 0.25 * Math.sin(x * 0.7f))).add(0, hh * 0.5 + 0.05, 0);
                    F1[i] = hh;
                    F2[i] = (0.45f + 0.55f * k) * tp;
                }
                strip(bb, P32, cols + 1, F1, F2, 0.2f, -time * 0.2f, fwd);
                // 바닥 테두리: 폭 끝 두 점과 앞줄
                FxRender.ring(bb, feet.add(0, 0.05, 0), UP, 0.3f + 0.6f * k, 0.6f + 0.9f * k, 0.5f + 0.4f * k, -time * 0.4f, 40);
                FxRender.draw(bb);
                glow(feet.add(fwd.scale(0.8)).add(0, 0.8, 0), 2.2f + 2f * k, C_AZURE, 0.25f + 0.25f * k);
                glow(feet.add(side.scale(hw)).add(fwd.scale(0.7)).add(0, 0.3, 0), 1.0f, C_WHITE, 0.5f * k);
                glow(feet.subtract(side.scale(hw)).add(fwd.scale(0.7)).add(0, 0.3, 0), 1.0f, C_WHITE, 0.5f * k);
                // 곧 밀려갈 길 (주변 사람도 피할 수 있게): 서버 해일과 같은 폭·사거리·방향
                lane(feet, fwd, Math.max(1f, com.blackocean.skills.client.ClientSkillStats.range(c.skill)), hw, k * 0.5f, e, 0.85f, time);
            }
            case WATER_DRAGON -> {
                groundCircle(c.target, c.size, k, time, e);
                Vec3 feet = e.getPosition(pt);
                float h = 2f + 8f * easeOutCubic(k);
                RenderSystem.disableCull();
                FxRender.elemShader(W, 2.0f + k, 0.35f - 0.25f * k, 0.6f + 0.5f * k);
                SkillFx.column(feet, 1.6f, 0.8f + 0.4f * k, h, 0.8f, 0.55f, 0f, 0.1f, time);
                FxRender.elemShader(W, 2.8f, 0.3f - 0.2f * k, 0.7f + 0.5f * k);
                SkillFx.column(feet, 0.9f, 0.45f, h * 1.05f, 0.9f, -0.8f, 0.4f, 0.07f, time);
                FxRender.elemShader(W, 1.5f, 0.1f, 1f);
                BufferBuilder bb = FxRender.quads();
                FxRender.ring(bb, feet.add(0, 0.05, 0), UP, 0.8f + 0.8f * k, 2.2f + 1.2f * k, 0.6f + 0.4f * k, -time * 0.5f, 56);
                FxRender.draw(bb);
                glow(feet.add(0, h * 0.5, 0), h * 0.9f + 3f, C_AZURE, 0.25f + 0.3f * k);
            }
            default -> { }
        }
    }
}
