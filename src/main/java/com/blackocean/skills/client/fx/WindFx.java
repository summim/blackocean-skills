package com.blackocean.skills.client.fx;

import com.blackocean.skills.Element;
import com.blackocean.skills.Skill;
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

import java.util.ArrayDeque;
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
 * 바람 능력 연출.
 *
 * <h2>2.3 디자인 (바람 = 초승달·나선 호, 얇은 반투명 속도선, 실려 가는 잎·흙먼지 / 늘어나며 가늘어져 사라짐 / 옅은 민트·흰빛, 성김)</h2>
 * 위계: 주 형태 = 가장 밝고 대비 큼 → 보조 층 40~60 % → 적중 = 짧은 정점(4틱 이하) → 잔류 = 옅게, 테두리 선 없음.
 * <pre>
 * 1 진공 참격 (가벼움)
 *   실루엣  : 비행 방향으로 볼록한 흰 초승달 날 하나.
 *   주 형태 : 초승달 면 + 같은 호를 ±22° 기울인 얇은 면 2장 + 카메라를 향한 앞날 띠 (위·옆·뒤 어디서 봐도 호가 남음).
 *   보조    : 잔상 칼날 4장(점점 옅게), 칼끝에서 벗겨지는 바람결, 잎, 속도선.
 *   적중    : 관통 = X자 두 줄 참격 섬광(7틱, 정점 2틱) / 벽·바닥 = 면을 따라 퍼지는 돌풍 고리 + 베인 조각.
 *   사라짐  : 바람결이 뒤로 늘어나며 가늘어짐. 투사체라 위험 범위 표시 없음 (조준 표시가 길을 보여 줌).
 *   경중    : 준비 5틱, 손의 작은 고리 → 빠른 비행, 적중은 짧고 날카롭게, 흔들림 약하게.
 * 2 질풍 돌진 (가벼움·빠름)
 *   실루엣  : 몸 앞의 뾰족한 바람 원뿔 + 몸 뒤로 길게 끌리는 속도선 다발.
 *   주 형태 : 3D 원뿔 메시 2겹, 속도선 6줄은 이동 축을 따라 카메라를 향하는 띠 (옆에서는 줄, 뒤에서는 원형 다발).
 *   보조    : 몸 잔상 빛, 넓은 바람 꼬리, 잎·흙먼지.
 *   적중    : 출발·도착 음속 고리 2겹 + 박차고 나간 흙 / 멈춘 자리 앞으로 쏟아지는 흙.
 *   위험    : 돌진하는 동안 dangerLane(출발 → 도착, 반폭 r + 0.6 = 서버 부딪힘 폭), 끝나면 바로 꺼짐.
 *   사라짐  : 속도선·꼬리가 뒤로 늘어나며 10틱에 흐려짐.
 *   경중    : 준비 2틱 웅크림 → 순간 가속 → 갑자기 멈춤 (속도 변화로 무게).
 * 3 회오리 (무거움)
 *   실루엣  : 아래가 좁고 위가 넓은 꼬인 깔때기.
 *   주 형태 : 3D 원통 메시 3겹 (바깥 / 반대로 꼬인 가운데 / 흰 심). 윤곽이 밝고 가운데가 어두움, 몸 높이(바닥 ~3칸)는
 *             정면 쪽을 크게 어둡게 → 띄워진 상대가 바람 속에서도 보임.
 *   보조    : 감아 오르는 나선 줄기 5, 실제 바닥 블록 조각·잎이 돌며 솟음, 바닥 흙먼지 띠(반투명).
 *   적중    : 끝 틱에 바깥으로 터지는 고리가 끌어당김 범위 끝까지 + 조각을 내던짐.
 *   위험    : dangerZone(r + 0.5, 0.5초 피해 주기마다 차오름) + dangerZone(r × 1.6, 끝 폭발까지 차오름).
 *   사라짐  : 깔때기가 넓어지며 10틱에 흐려지고 잎·흙먼지가 내려앉음 (테두리 없음).
 *   경중    : 10틱 바닥 표시 → 생성 충격 → 60틱 유지 → 폭발.
 * 4 폭풍 방벽 (중간)
 *   실루엣  : 몸에서 바깥으로 퍼지는 낮은 바람 벽 고리.
 *   주 형태 : 3D 원통 띠 2겹 (앞 벽 + 뒤따르는 낮은 벽).
 *   보조    : 방사 속도선 28줄, 잎·흙먼지 고리.
 *   적중    : 발동 틱 한 번 판정 → 벽이 5틱 만에 정확히 r 까지.
 *   위험    : 준비 중 dangerZone(r, 모임 진행도), 벽이 퍼지는 5틱 동안 dangerZone(r, 1).
 *   사라짐  : r 에서 멈춘 벽이 6틱에 흩어짐, 남는 흙먼지에는 테두리 없음.
 *   경중    : 7틱 압축(범위 끝에서 몸으로 빨려 드는 바람) → 한 번에 터짐.
 * 5 천공의 대폭풍 (궁극기, 가장 무거움)
 *   실루엣  : 22칸 거대 깔때기 + 꼭대기의 어두운 폭풍 구름 고리, 하늘이 어두워짐.
 *   주 형태 : 회오리 3겹 + 바깥 옅은 막 1겹 (같은 3D 메시, 같은 몸 높이 비움).
 *   보조    : 날아다니는 잔해, 시전자를 감아 오르는 바람 띠(준비), 3틱마다 바닥 충격파.
 *   적중    : 끝 틱 거대 충격파 3겹 + 화면 번쩍(FxLimits 로 제한).
 *   위험    : dangerZone(r + 0.5) + dangerZone(r × 2.3).
 *   사라짐  : 40틱 동안 하늘로 올라갔던 잎·흙이 내려앉음 (테두리 없음).
 *   경중    : 24틱 준비(땅울림 점점 강하게) → 100틱 유지 → 폭발.
 * </pre>
 * 사람이 가려지지 않게: 회오리 깔때기는 몸 높이(바닥 ~3칸)에서 카메라를 정면으로 보는 면(가운데)을 크게 어둡게 하고
 * 윤곽(옆면)만 밝게 → 띄워진 상대와 시전자가 바람 속에서도 보임. 반투명 흙먼지 막도 몸 높이에서 옅게.
 * 끝난 뒤 남는 연출(내려앉는 잎·흙먼지, 금 간 자국)에는 테두리 선이 없음 = 이제 안전.
 *
 * <pre>
 *  1 진공 참격   : 비행 방향으로 누운 거대한 초승달 칼날 + 잔상 칼날 + 끝에서 벗겨지는 바람결 / 관통 시 X자 참격 / 적중 시 돌풍으로 산산이
 *  2 질풍 돌진   : 앞쪽 바람 원뿔 + 길게 늘어지는 속도선 + 몸 잔상 + 출발·도착 음속 고리
 *  3 회오리      : 아래가 좁고 위가 넓은 꼬인 깔때기 3겹 + 솟는 나선 줄기 + 잔해 + 바닥 소용돌이 → 끝에 바깥으로 폭발
 *  4 폭풍 방벽   : 바깥으로 퍼지는 낮은 바람 벽 + 방사 속도선 + 충격파 여러 겹 + 흙먼지 고리
 *  5 천공의 대폭풍: 회오리의 거대판 (22블록) + 하늘 어두워짐 + 강한 화면 흔들림 + 날아다니는 잔해
 * </pre>
 * 무거운 순간에는 땅이 뜯겨 나감 (DebrisFx: 돌진 출발·도착, 회오리 테두리, 방벽, 칼날 적중) +
 * 회오리에 빨려 들어가 빙빙 돌며 솟는 실제 바닥 블록 조각 (WindDebris) + 금 간 땅 자국 (잔류).
 * <pre>
 * 단계 (기본 설정값, 틱 = 1/20초)   준비            발동                    지속                     적중·끝                 잔류
 *  참격   : 5틱, 손(서버 손 0.3)에 칼날 윤곽 → 손에서 돌풍 고리 → 2.4칸/틱 13틱 비행 → 관통 X자 / 벽에서 흩어짐 → 7~12틱
 *  돌진   : 2틱, 등 뒤로 바람 모임·발밑 화살촉 → 출발 음속 고리 → 7틱 돌진(바닥 양옆 선 = 부딪힘 폭 r+0.6) → 도착 고리 → 10틱 페이드
 *  회오리 : 10틱 바닥 표시(실선 r+0.5 = 띄움·피해, 점선 r×1.6 = 끌어당김) → 생성 충격파가 점선까지 → 60틱 유지 → 끝 틱에 점선까지 날려 보냄 → 20틱
 *  방벽   : 7틱, 범위 끝(점선 r)에서 몸으로 빨려 듦 → 바람 벽이 5틱 만에 정확히 r 까지 → 점선 테두리 14틱
 *  대폭풍 : 24틱 바닥 표시(점선 r×2.3) → 100틱 유지 → 끝 틱에 날려 보냄 → 40틱
 * </pre>
 */
final class WindFx {
    private WindFx() {}

    private static final Element W = Element.WIND;
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final float TAU = (float) (Math.PI * 2);
    private static final float[] TINT = FxRender.tint(Element.WIND);

    /** 바람 칼날 크기 */
    private static final float BLADE_R = 1.8f;
    private static final float BLADE_ARC = (float) Math.toRadians(150);
    private static final float BLADE_THICK = 0.75f;
    /** 칼날 두께용으로 기울인 보조 면의 각도 (라디안, 약 22°) */
    private static final float BLADE_TILT_3D = 0.38f;
    /** 칼날 준비 고리 3겹 (프레임마다 배열을 만들지 않게) */
    private static final float[] BLADE_TILT = {0.3f, 1.1f, 1.4f}, BLADE_RIN = {0.28f, 0.48f, 0.66f}, BLADE_ROUT = {0.4f, 0.58f, 0.74f};

    /** 회오리가 띄우고 0.5초마다 피해를 주는 반지름 (서버 Tornado: d &lt;= r + 0.5) */
    static float liftR(float r) {
        return r + 0.5f;
    }

    /** 회오리가 끌어당기고 끝에 날려 보내는 반지름 (서버 Tornado: r × 1.6, 대폭풍 r × 2.3) */
    static float pullR(float r, boolean big) {
        return r * (big ? 2.3f : 1.6f);
    }

    /** 폭풍 방벽 반지름 (서버 설정값; 주변 사람의 화면에서도 같은 값) */
    private static float burstR(FxManager.ChargeFx c) {
        return Math.max(1f, com.blackocean.skills.client.ClientSkillStats.radius(c.skill));
    }

    private static final List<Fx> FX = new ArrayList<>();
    private static final List<Fx> SHOCKS = new ArrayList<>();

    // =====================================================================
    // 계약
    // =====================================================================

    static void onEvent(SkillEventPayload e, Skill skill, @Nullable Entity caster) {
        Vec3 pos = e.pos();
        FxManager.lod = FxManager.lodAt(pos);
        switch (e.kind()) {
            case SkillEvents.PIERCE -> add(new SlashFx(pos, safeDir(e.vec(), new Vec3(0, 0, 1))));
            case SkillEvents.IMPACT -> bladeShatter(pos, safeDir(e.vec(), UP), e.size2() > 0.5f);
            case SkillEvents.DASH -> add(new DashFx(caster, pos, flat(e.vec()), Math.max(0.5f, e.size()), Math.max(1f, e.size2()), Math.max(2, e.duration())));
            case SkillEvents.TORNADO -> {
                boolean big = skill == Skill.WIND_TEMPEST || skill.ultimate();
                float r = Math.max(1f, e.size());
                float h = e.size2() > 0.5f ? e.size2() : (big ? 22f : 7f);
                int dur = Math.max(10, e.duration());
                add(new TornadoFx(caster, pos, r, h, dur, big));
                if (big) {
                    ScreenFx.darken(pos, r * 2.5f, 0.45f, dur + 40);
                    flashNear(pos, 0xBFFFE6, 0.4f, 14, 56f);
                }
            }
            case SkillEvents.BURST -> add(new BurstFx(caster, pos, Math.max(1f, e.size()), Math.max(12, e.duration())));
            default -> { }
        }
    }

    static void tick() {
        FX.removeIf(Fx::tick);
        SHOCKS.removeIf(Fx::tick);
        WindDebris.tick();
    }

    static void renderAdditive(float pt, float time) {
        if (FX.isEmpty() && SHOCKS.isEmpty()) return;
        RenderSystem.disableCull();
        for (Fx f : FX) f.render(pt, time);
        for (Fx f : SHOCKS) f.render(pt, time);
    }

    static void renderAlpha(float pt, float time) {
        WindDebris.render(pt);
        if (FX.isEmpty()) return;
        boolean any = false;
        for (Fx f : FX) {
            if (f instanceof TornadoFx) {
                any = true;
                break;
            }
        }
        if (!any) return;
        FxRender.spriteShader(FxRender.MIST);
        RenderSystem.disableCull();
        BufferBuilder bb = FxRender.quads();
        for (Fx f : FX) {
            if (f instanceof TornadoFx t) t.renderMist(bb, pt, time);
        }
        FxRender.draw(bb);
    }

    static boolean isEmpty() {
        return FX.isEmpty() && SHOCKS.isEmpty() && WindDebris.isEmpty();
    }

    static void clear() {
        FX.clear();
        SHOCKS.clear();
        WindDebris.clear();
    }

    private static void add(Fx f) {
        if (FX.size() >= 32) FX.remove(0);
        FX.add(f);
    }

    private static void shock(Vec3 c, Vec3 n, float r0, float r1, float width, int life, int delay, float heat) {
        if (SHOCKS.size() >= 48) SHOCKS.remove(0);
        SHOCKS.add(new ShockFx(c, n, r0, r1, width, life, delay, heat));
    }

    // =====================================================================
    // 준비 연출
    // =====================================================================

    static void tickCharge(FxManager.ChargeFx c, float k) {
        Entity caster = c.caster;
        if (caster == null || caster.isRemoved()) return;
        switch (c.skill) {
            case WIND_BLADE -> {
                // 칼날이 나갈 손(서버와 같은 자리) + 고정된 시전 방향
                Vec3 hand = AimHand.hand(c, 1f, AimHand.SIDE);
                FxManager.lod = FxManager.lodAt(hand);
                Vec3 look = c.aimDir();
                Vec3[] b = FxRender.basis(look);
                for (int j = 0; j < 2; j++) {
                    if (RNG.nextFloat() > FxManager.amount() * FxManager.lod) continue;
                    double ang = c.age * 1.1 + j * Math.PI;
                    Vec3 radial = b[0].scale(Math.cos(ang)).add(b[1].scale(Math.sin(ang)));
                    Vec3 tangent = b[0].scale(-Math.sin(ang)).add(b[1].scale(Math.cos(ang)));
                    wisp(hand.add(radial.scale(0.55)), tangent.scale(0.14).add(look.scale(0.03)), 0.55f + 0.3f * k, 0.2f, 5, 0.85f);
                }
                for (int i = 0; i < count(2); i++) {
                    Vec3 off = randomDir().scale(rand(1.3f, 1.9f));
                    leaf(hand.add(off), off.scale(-1.0 / 5.0), 5, 0.16f);
                }
                for (int i = 0; i < count(2); i++) {
                    Vec3 off = randomDir().scale(rand(1.0f, 1.6f));
                    streakSpark(hand.add(off), off.scale(-1.0 / 4.0), 0.06f, 4);
                }
            }
            case WIND_DASH -> {
                // 짧은 웅크림(0.1초): 등 뒤로 바람이 모이고 발이 땅을 박참 → 돌진 방향이 먼저 읽힘
                Vec3 feet = caster.position();
                Vec3 fwd = c.flatDir();
                FxManager.lod = FxManager.lodAt(feet);
                if (c.age == 1) {
                    FxManager.addRing(feet.add(0, 0.06, 0), UP, 0.2f, 2.0f, 5, 0, 0.8f, W);
                    shock(feet.add(0, 0.9, 0).subtract(fwd.scale(0.5)), fwd, 0.3f, 1.4f, 0.45f, 4, 0, 0.8f);
                }
                for (int i = 0; i < count(4); i++) {
                    Vec3 d = fwd.scale(-1).add(rand(-0.5f, 0.5f), 0, rand(-0.5f, 0.5f));
                    dust(feet.add(0, 0.15, 0), d.scale(rand(0.1f, 0.2f)).add(0, 0.02, 0), 0.5f, 1.4f, 10 + RNG.nextInt(5), 0.35f);
                }
                // 뒤에서 몸 쪽으로 빨려 드는 바람결 (곧 앞으로 터져 나감)
                for (int i = 0; i < count(3); i++) {
                    Vec3 at = feet.add(0, rand(0.3f, 1.6f), 0).subtract(fwd.scale(rand(1.6f, 2.4f))).add(rand(-0.6f, 0.6f), 0, rand(-0.6f, 0.6f));
                    streakSpark(at, fwd.scale(rand(0.35f, 0.5f)), 0.07f, 4);
                }
            }
            case WIND_WHIRLWIND, WIND_TEMPEST -> {
                boolean big = c.skill == Skill.WIND_TEMPEST;
                Vec3 t = c.target;
                float R = c.size;
                FxManager.lod = FxManager.lodAt(t);
                float sc = R / 3f;
                float pull = pullR(R, big);
                // 끌어당기는 범위 가장자리에서 안쪽으로 빨려 드는 바람결 (어디까지 끌려가는지 보임)
                for (int i = 0; i < count((big ? 6 : 3) + (int) ((big ? 8 : 4) * k)); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    Vec3 radial = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                    Vec3 tangent = new Vec3(-Math.sin(ang), 0, Math.cos(ang));
                    Vec3 at = t.add(radial.scale(pull * rand(0.92f, 1.0f))).add(0, rand(0.1f, 0.5f), 0);
                    float sp = (pull - liftR(R)) / 6f + 0.1f;
                    streakSpark(at, tangent.scale(0.3 * sc).add(radial.scale(-sp)).add(0, 0.02, 0), 0.09f * (big ? 1.6f : 1f), 6 + RNG.nextInt(3));
                }
                // 떠오르는 흙먼지
                for (int i = 0; i < count((big ? 4 : 2) + (int) (3 * k)); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = R * (float) Math.sqrt(RNG.nextFloat());
                    Vec3 tangent = new Vec3(-Math.sin(ang), 0, Math.cos(ang));
                    dust(t.add(Math.cos(ang) * rr, 0.15, Math.sin(ang) * rr), tangent.scale(0.05 * sc).add(0, rand(0.04f, 0.1f), 0),
                            0.8f * sc, 2.2f * sc, 18 + RNG.nextInt(10), 0.3f);
                }
                for (int i = 0; i < count(2 + (int) (2 * k)); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = R * rand(0.4f, 1.0f);
                    Vec3 tangent = new Vec3(-Math.sin(ang), 0, Math.cos(ang));
                    leaf(t.add(Math.cos(ang) * rr, 0.2, Math.sin(ang) * rr), tangent.scale(0.2 * sc).add(0, rand(0.08f, 0.18f), 0), 12 + RNG.nextInt(8), 0.2f);
                }
                // 모을수록 땅이 들썩이고 테두리가 뜯겨 나감 (기대감)
                if (k > 0.45f && c.age % (big ? 3 : 6) == 0) {
                    float ang = RNG.nextFloat() * TAU;
                    float er = liftR(R);
                    DebrisFx.burst(t.add(Math.cos(ang) * er, 0, Math.sin(ang) * er), 0.8f, big ? 3 : 1, 0.35f + 0.4f * k);
                }
                if (big && c.age % 2 == 0) shakeAt(t, 0.15f + 0.45f * k, R * 4f);
                if (big) {
                    // 시전자 둘레를 감아 높이 솟는 바람
                    Vec3 cp = caster.position();
                    float top = 2f + 10f * k;
                    for (int j = 0; j < 3; j++) {
                        if (RNG.nextFloat() > FxManager.amount() * FxManager.lod) continue;
                        double ang = c.age * 0.7 + j * TAU / 3.0;
                        double y = (c.age * 0.45 + j * 2.0) % top;
                        Vec3 radial = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                        Vec3 tangent = new Vec3(-Math.sin(ang), 0, Math.cos(ang));
                        wisp(cp.add(radial.scale(1.3)).add(0, y, 0), tangent.scale(0.3).add(0, 0.3, 0), 1.0f + 0.4f * k, 0.5f, 6, 0.9f);
                    }
                }
            }
            case WIND_BURST -> {
                Vec3 mid = caster.position().add(0, 1.0, 0);
                FxManager.lod = FxManager.lodAt(mid);
                float R = burstR(c);
                // 범위 가장자리에서 몸으로 빨려 드는 바람 (모이는 시간 안에 정확히 몸에 닿음)
                int left = Math.max(2, c.duration - c.age + 1);
                double travel = (1.0 - Math.pow(0.88, left)) / 0.12; // 속도선은 틱마다 0.88 배로 느려짐
                for (int i = 0; i < count(5 + (int) (4 * k)); i++) {
                    Vec3 off = new Vec3(rand(-1f, 1f), rand(-0.25f, 0.4f), rand(-1f, 1f));
                    if (off.lengthSqr() < 1.0E-3) continue;
                    off = off.normalize().scale(R * rand(0.75f, 1.0f));
                    streakSpark(mid.add(off), off.scale(-0.9 / travel), 0.09f, left);
                }
                for (int i = 0; i < count(2); i++) {
                    Vec3 off = randomDir().scale(rand(2.5f, 3.5f));
                    leaf(mid.add(off), off.scale(-1.0 / 7.0), 7, 0.2f);
                }
                if (RNG.nextFloat() < FxManager.amount() * FxManager.lod) {
                    Vec3 off = randomDir().scale(2.4);
                    wisp(mid.add(off), off.scale(-1.0 / 6.0), 1.1f, 0.4f, 6, 0.8f);
                }
            }
            default -> { }
        }
    }

    static void renderCharge(FxManager.ChargeFx c, float pt, float k, float time) {
        Entity caster = c.caster;
        if (caster == null || caster.isRemoved()) return;
        RenderSystem.disableCull();
        switch (c.skill) {
            case WIND_BLADE -> {
                Minecraft mc = Minecraft.getInstance();
                boolean fp = caster == mc.player && mc.options.getCameraType().isFirstPerson();
                float s = (fp ? 0.65f : 1f) * (0.45f + 0.55f * easeOutCubic(Math.min(1f, k * 1.4f)));
                Vec3 hand = AimHand.hand(c, pt, AimHand.SIDE);
                Vec3 look = c.aimDir();
                Vec3[] b = FxRender.basis(look);
                FxRender.elemShader(W, 2.4f, 0.08f, 1.0f + 0.3f * k);
                BufferBuilder bb = FxRender.quads();
                for (int i = 0; i < 3; i++) {
                    double ph = time * (0.45 + 0.5 * k) * (i == 1 ? -1 : 1) + i * 2.1;
                    Vec3 n = look.scale(Math.cos(BLADE_TILT[i])).add(b[0].scale(Math.sin(BLADE_TILT[i]) * Math.cos(ph))).add(b[1].scale(Math.sin(BLADE_TILT[i]) * Math.sin(ph)));
                    dashedRing(bb, hand, n.normalize(), BLADE_RIN[i] * s, BLADE_ROUT[i] * s, 0.75f + 0.25f * k, time * (0.4f + 0.25f * i), 5 + i, 0.6f);
                }
                // 손끝에 맺히는 칼날의 윤곽 (나갈 칼날과 같은 방향·눕기 → 무엇이 어느 쪽으로 나갈지 미리 읽힘)
                float kk = easeOutCubic(k);
                crescent(bb, hand.add(look.scale(0.25)), look, sideOf(look), BLADE_R * (0.25f + 0.3f * kk) * s, BLADE_ARC * (0.6f + 0.4f * kk),
                        BLADE_THICK * 0.5f * s, 0.35f + 0.65f * kk, time * 0.05f);
                FxRender.draw(bb);
                frameGlow(hand, (0.9f + 1.6f * k) * s, 0.35f + 0.4f * k);
            }
            case WIND_DASH -> {
                Vec3 feet = caster.getPosition(pt).add(0, 0.06, 0);
                Vec3 fwd = c.flatDir();
                float ek = easeOutCubic(k);
                FxRender.elemShader(W, 2.6f, 0.1f, 1.1f);
                BufferBuilder bb = FxRender.quads();
                float rr = 0.4f + 1.1f * ek;
                FxRender.ring(bb, feet, UP, rr * 0.6f, rr, 0.9f * (1f - 0.4f * k), time * 0.6f, 40);
                // 등 뒤에서 조여드는 바람 고리 (돌진 방향을 향함)
                Vec3 back = feet.add(0, 0.9, 0).subtract(fwd.scale(0.6 + 0.6 * (1f - ek)));
                FxRender.ring(bb, back, fwd, 1.3f - 0.7f * ek, 1.55f - 0.7f * ek, 0.8f * (0.4f + 0.6f * k), -time * 0.8f, 32);
                // 발밑에서 앞으로 뻗는 짧은 화살촉 (돌진 방향)
                Vec3 side = new Vec3(-fwd.z, 0, fwd.x);
                Vec3 tip = feet.add(fwd.scale(1.2 + 1.4 * ek));
                ribbonSide(bb, feet.add(side.scale(0.7)), tip, side, 0.25f, 0.08f, 0.6f * k, 0.9f * k, 0f, 0.5f);
                ribbonSide(bb, feet.subtract(side.scale(0.7)), tip, side, 0.25f, 0.08f, 0.6f * k, 0.9f * k, 0f, 0.5f);
                FxRender.draw(bb);
                frameGlow(feet.add(0, 0.5, 0), 2.0f, 0.3f);
            }
            case WIND_WHIRLWIND, WIND_TEMPEST -> renderGroundCharge(c, pt, k, time, c.skill == Skill.WIND_TEMPEST);
            case WIND_BURST -> {
                Vec3 mid = caster.getPosition(pt).add(0, 1.0, 0);
                float R = burstR(c);
                FxRender.elemShader(W, 2.2f, 0.1f, 1.0f + 0.3f * k);
                BufferBuilder bb = FxRender.quads();
                // 몸으로 조여드는 바람 고리 (압축)
                for (int i = 0; i < 3; i++) {
                    float frac = (time * 0.09f + i / 3f) % 1f;
                    float r = 3.4f * (1f - frac) + 0.35f;
                    float h = 4f * frac * (1f - frac) * (0.5f + 0.5f * k);
                    Vec3 n = i == 1 ? new Vec3(Math.sin(time * 0.05), 1.6, Math.cos(time * 0.05)).normalize() : UP;
                    FxRender.ring(bb, mid.add(0, (i - 1) * 0.35, 0), n, r * 0.86f, r, h, time * 0.5f, 48);
                }
                // 발밑에서 몸으로 조여드는 바람 띠 (장식: 테두리처럼 보이지 않게 부드러운 고리, 범위보다 훨씬 안쪽)
                Vec3 feet = caster.getPosition(pt).add(0, 0.05, 0);
                float fr = 2.2f - 1.4f * k;
                FxRender.ring(bb, feet.add(0, 0.01, 0), UP, fr * 0.7f, fr, 0.55f + 0.3f * k, -time * 0.3f, 32);
                FxRender.draw(bb);
                frameGlow(mid, 1.2f + 2.6f * k, 0.3f + 0.45f * k);
                // 실제 판정 범위 (주변 사람이 피할 수 있게, 다 차는 순간 터짐)
                zone(caster.getPosition(pt), R, k, caster, 1f, time);
            }
            default -> { }
        }
    }

    /** 바닥 조준(회오리·대폭풍) 준비: 도는 점선 고리, 안으로 감기는 나선, 가운데 빛 */
    private static void renderGroundCharge(FxManager.ChargeFx c, float pt, float k, float time, boolean big) {
        Vec3 t = c.target.add(0, 0.05, 0);
        float R = c.size;
        float pulse = 0.8f + 0.2f * (float) Math.sin(time * (1.0f + 2.0f * k));
        float a = (0.45f + 0.55f * k) * pulse;

        // 바닥 빛 번짐
        FxRender.spriteShader(FxRender.GLOW_WHITE);
        BufferBuilder bb = FxRender.quads();
        float g = 0.18f + 0.35f * k * k;
        FxRender.planeQuad(bb, t.add(0, 0.01, 0), UP, liftR(R) * 1.25f, 0f, TINT[0] * g, TINT[1] * g, TINT[2] * g, g);
        FxRender.draw(bb);

        // 테두리는 공용 위험 범위만 (안쪽 = 띄우고 0.5초마다 피해, 바깥 = 끌려 들어가고 끝에 날려 보냄 — 서버와 같은 반지름).
        // 안쪽 장식은 테두리처럼 보이는 고리 없이 감겨 드는 나선만
        float lr = liftR(R), pr = pullR(R, big);
        zone(c.target, lr, k, c.caster, 1f, time);
        zone(c.target, pr, k * 0.85f, c.caster, 0.8f, time);
        FxRender.elemShader(W, 1.8f + k, 0.06f, 1.0f + 0.25f * k);
        bb = FxRender.quads();
        // 안쪽으로 감겨 드는 나선 4줄 (바닥에 누움)
        int arms = big ? 6 : 4;
        final int steps = 12;
        for (int j = 0; j < arms; j++) {
            Vec3 prev = null;
            float prevH = 0f;
            for (int s = 0; s <= steps; s++) {
                float f = (float) s / steps;
                double ang = j * TAU / arms + f * 2.6 - time * (0.1 + 0.18 * k);
                double rr = R * (1.0 - 0.85 * f);
                Vec3 p = t.add(Math.cos(ang) * rr, 0.03, Math.sin(ang) * rr);
                float wave = 0.5f + 0.5f * (float) Math.sin(f * 9f - time * 0.7f);
                float h = (float) Math.sin(Math.PI * f) * (0.4f + 0.6f * wave) * a;
                if (prev != null) {
                    Vec3 d = p.subtract(prev);
                    Vec3 side = d.cross(UP);
                    if (side.lengthSqr() > 1.0E-8) {
                        side = side.normalize();
                        float w = (0.35f + 0.25f * k) * R / 3f;
                        ribbonSide(bb, prev, p, side, w, w * 0.9f, prevH, h, f * 2f, f * 2f + 0.17f);
                    }
                }
                prev = p;
                prevH = h;
            }
        }
        FxRender.draw(bb);

        // 끝 무렵 가운데에서 피어오르는 작은 회오리
        if (k > 0.5f) {
            float kk = (k - 0.5f) / 0.5f;
            FxRender.elemShader(W, 2.4f, 0.35f - 0.25f * kk, 0.9f);
            funnel(t, R * 0.12f * kk, R * 0.35f * kk, 1.4f, (big ? 3.5f : 1.8f) * kk, 0.8f, 0.6f, 0.35f, 0f, 0.08f, time, 0, 0, 16, 5, 0.6f);
        }

        if (big) {
            // 시전자 둘레를 감아 오르는 바람 띠
            Entity cs = c.caster;
            Vec3 base = cs.getPosition(pt);
            float top = 2f + 10f * k;
            FxRender.elemShader(W, 3f, 0.1f, 1.1f);
            bb = FxRender.quads();
            final int segs = 18;
            for (int j = 0; j < 3; j++) {
                Vec3 prev = null;
                float prevH = 0f;
                for (int s = 0; s <= segs; s++) {
                    float f = (float) s / segs;
                    double ang = j * TAU / 3.0 + f * 7.0 - time * 0.45;
                    double rr = 1.0 + 0.5 * f + 0.15 * Math.sin(time * 0.2 + j);
                    Vec3 p = base.add(Math.cos(ang) * rr, f * top, Math.sin(ang) * rr);
                    float h = (float) Math.sin(Math.PI * f) * (0.6f + 0.4f * k);
                    if (prev != null) ribbonCam(bb, prev, p, 0.3f, 0.3f, prevH, h, f * 3f, f * 3f + 0.17f);
                    prev = p;
                    prevH = h;
                }
            }
            FxRender.draw(bb);
            frameGlow(base.add(0, 1.0, 0), 2.5f + 3f * k, 0.25f + 0.35f * k);
        }
    }

    // =====================================================================
    // 진공 참격 (투사체)
    // =====================================================================

    static void trackProjectile(FxManager.ProjectileFx p, Vec3 pos) {
        Vec3 fwd = p.dir;
        Vec3 side = sideOf(fwd);
        Vec3 back = fwd.scale(-1);
        if (p.age == 1) {
            // 발사: 칼날이 만들어진 손(준비 연출이 모였던 자리)에서 비행 방향으로 터지는 돌풍 고리 + 손에서 칼날까지 이어지는 바람결
            Vec3 o = AimHand.spawnPoint(p);
            FxManager.addRing(o.add(fwd.scale(0.3)), fwd, 0.3f, 2.6f, 5, 0, 0.9f, W);
            FxManager.addRing(o.add(fwd.scale(1.1)), fwd, 0.2f, 1.7f, 4, 1, 0.65f, W);
            shock(o.add(fwd.scale(0.2)), fwd, 0.3f, 2.0f, 0.45f, 4, 0, 0.9f);
            spawn(new Sprite(Sprite.Kind.GLOW, o, Vec3.ZERO, 1.8f, 3.0f, 3).color(0.9f, 1f, 0.96f, 0.9f));
            double gap = o.distanceTo(pos);
            for (int i = 0; i < count(8); i++) {
                Vec3 at = o.add(fwd.scale(RNG.nextFloat() * gap)).add(side.scale(rand(-1.2f, 1.2f)));
                streakSpark(at, fwd.scale(rand(0.7f, 1.1f)), 0.06f, 3 + RNG.nextInt(2));
            }
        }
        // 칼날 양 끝 (crescent 와 같은 계산)
        Vec3 o = pos.subtract(fwd.scale(BLADE_R * 0.75));
        double half = BLADE_ARC * 0.5;
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            Vec3 radial = fwd.scale(Math.cos(half)).add(side.scale(Math.sin(half) * sgn));
            Vec3 tip = o.add(radial.scale(BLADE_R));
            for (int i = 0; i < count(2); i++) {
                Vec3 at = tip.add(back.scale(rand(0f, 1.2f))).add(randomDir().scale(0.15));
                wisp(at, back.scale(0.08).add(side.scale(0.05 * sgn)), rand(0.7f, 1.0f), 0.25f, 5 + RNG.nextInt(4), 0.85f);
            }
            if (RNG.nextFloat() < 0.6f * FxManager.amount() * FxManager.lod) {
                streakSpark(tip, back.scale(0.35).add(side.scale(0.06 * sgn)), 0.07f, 5 + RNG.nextInt(3));
            }
        }
        for (int i = 0; i < count(2); i++) {
            Vec3 at = pos.add(back.scale(rand(0.5f, 2.0f))).add(randomDir().scale(0.9));
            leaf(at, back.scale(0.1).add(randomDir().scale(0.05)), 14 + RNG.nextInt(10), 0.2f);
        }
        for (int i = 0; i < count(2); i++) {
            Vec3 at = pos.add(randomDir().scale(0.8));
            streakSpark(at, back.scale(rand(0.4f, 0.7f)), 0.05f, 4 + RNG.nextInt(3));
        }
    }

    static void renderProjectile(FxManager.ProjectileFx p, Vec3 c, float pt, float time) {
        Vec3 fwd = p.dir;
        Vec3 side0 = sideOf(fwd);
        Vec3 nrm = fwd.cross(side0).normalize();
        float grow = Math.min(1f, (p.age + pt) / 2f);
        float R = BLADE_R * (0.55f + 0.45f * grow);
        float wob = 0.13f * (float) Math.sin(time * 0.8f) + 0.05f * (float) Math.sin(time * 2.3f + 1.0f);
        RenderSystem.disableCull();

        // 잔상 칼날 (지난 위치들)
        if (c.distanceToSqr(FxRender.cam) < 64 * 64) {
            int k = 0;
            for (Vec3 tp : p.trail) {
                if (k > 4) break;
                if (k > 0) {
                    float fade = 0.6f - 0.11f * k;
                    Vec3 sk = rotate(side0, nrm, wob - 0.14f * k);
                    FxRender.elemShader(W, 1.8f, 0.22f + 0.13f * k, fade);
                    BufferBuilder bb = FxRender.quads();
                    crescent(bb, tp, fwd, sk, R * (1f - 0.05f * k), BLADE_ARC * (1f - 0.06f * k), BLADE_THICK * (1f - 0.12f * k), 0.8f, k * 0.21f);
                    FxRender.draw(bb);
                }
                k++;
            }
        }

        // 본체 칼날 + 앞날의 흰 심
        Vec3 side = rotate(side0, nrm, wob);
        FxRender.elemShader(W, 2.8f, 0.04f, 1.25f);
        BufferBuilder bb = FxRender.quads();
        crescent(bb, c, fwd, side, R, BLADE_ARC, BLADE_THICK, 1f, time * 0.05f);
        crescent(bb, c.add(fwd.scale(0.03)), fwd, side, R * 0.995f, BLADE_ARC * 0.9f, BLADE_THICK * 0.32f, 1f, 0.5f + time * 0.07f);
        // 두께: 같은 호를 비행 축 둘레로 ±22° 기울인 얇은 면 2장 (보조 밝기) → 칼날을 옆·뒤에서 봐도 납작한 선이 아닌 렌즈 모양
        Vec3 nrmW = fwd.cross(side).normalize();
        for (int sg = -1; sg <= 1; sg += 2) {
            Vec3 tilt = rotate(side, nrmW, BLADE_TILT_3D * sg);
            crescent(bb, c.subtract(fwd.scale(0.04)), fwd, tilt, R * 0.97f, BLADE_ARC * 0.92f, BLADE_THICK * 0.55f, 0.5f, 0.25f + time * 0.05f);
        }
        FxRender.draw(bb);
        // 앞날: 호를 따라 카메라를 향하는 흰 띠 (면이 카메라와 나란해져도 앞날 호가 그대로 보임)
        FxRender.elemShader(W, 3.2f, 0.05f, 1.2f);
        bb = FxRender.quads();
        bladeEdge(bb, c, fwd, side, R, BLADE_ARC * 0.94f, 0.22f, 0.95f, time * 0.06f);
        FxRender.draw(bb);

        frameGlow(c.add(fwd.scale(R * 0.15)), 3.4f * (0.6f + 0.4f * grow), 0.32f);
    }

    /** 칼날이 벽·바닥에 부딪혀(또는 허공에서 다 날아가) 돌풍으로 흩어짐 */
    private static void bladeShatter(Vec3 pos, Vec3 n, boolean fizzle) {
        float s = fizzle ? 0.7f : 1f;
        spawn(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, 3.0f * s, 4.5f * s, 5).color(0.85f, 1f, 0.95f, 0.9f));
        spawn(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, 5.0f * s, 7.0f * s, 9).color(TINT[0], TINT[1], TINT[2], 0.45f));
        Vec3 rc = pos.add(n.scale(0.08));
        FxManager.addRing(rc, n, 0.4f, 4.2f * s, 9, 0, 0.9f, W);
        FxManager.addRing(rc, n, 0.3f, 2.6f * s, 7, 2, 0.7f, W);
        shock(rc, n, 0.4f, 3.4f * s, 0.8f * s, 8, 0, 1f);
        Vec3[] b = FxRender.basis(n);
        // 면을 따라 흩어지는 바람결
        for (int i = 0; i < count(fizzle ? 8 : 14); i++) {
            double ang = RNG.nextFloat() * TAU;
            Vec3 d = b[0].scale(Math.cos(ang)).add(b[1].scale(Math.sin(ang))).add(n.scale(rand(0.05f, 0.35f))).normalize();
            wisp(pos.add(d.scale(0.4)), d.scale(rand(0.2f, 0.38f)), rand(0.9f, 1.4f) * s, rand(1.6f, 2.2f) * s, 7 + RNG.nextInt(5), 0.85f);
        }
        for (int i = 0; i < count(fizzle ? 12 : 22); i++) {
            double ang = RNG.nextFloat() * TAU;
            Vec3 d = b[0].scale(Math.cos(ang)).add(b[1].scale(Math.sin(ang))).add(n.scale(rand(0f, 0.5f))).normalize();
            streakSpark(pos, d.scale(rand(0.6f, 1.1f)), 0.08f, 6 + RNG.nextInt(5));
        }
        for (int i = 0; i < count(fizzle ? 6 : 12); i++) {
            Vec3 d = randomDir().add(n.scale(0.6)).normalize();
            leaf(pos, d.scale(rand(0.25f, 0.5f)), 18 + RNG.nextInt(14), 0.22f);
        }
        if (!fizzle) {
            for (int i = 0; i < count(6); i++) {
                Vec3 d = randomDir().add(n).normalize();
                dust(pos.add(d.scale(0.3)), d.scale(rand(0.06f, 0.14f)), 0.8f, 2.4f, 20 + RNG.nextInt(10), 0.35f);
            }
            // 칼날이 땅·벽을 베어 낸 조각
            if (n.y > 0.5) DebrisFx.burst(pos, 1.0f, 6, 0.5f);
            else if (Math.abs(n.y) < 0.5) DebrisFx.directed(pos, new Vec3(n.x, 0, n.z), 2.5f, 50f, 6, 0.5f);
        }
        shakeAt(pos, fizzle ? 0.4f : 0.9f, 12f);
    }

    // =====================================================================
    // 효과 객체
    // =====================================================================

    abstract static class Fx {
        int age;

        /** @return true 면 끝 */
        abstract boolean tick();

        abstract void render(float pt, float time);
    }

    /** 펼쳐지는 흐르는 바람 고리 (원형 띠 메시) */
    static final class ShockFx extends Fx {
        final Vec3 c, n;
        final float r0, r1, width, heat;
        final int life, delay;

        ShockFx(Vec3 c, Vec3 n, float r0, float r1, float width, int life, int delay, float heat) {
            this.c = c;
            this.n = n.lengthSqr() < 1.0E-6 ? UP : n.normalize();
            this.r0 = r0;
            this.r1 = r1;
            this.width = width;
            this.life = Math.max(1, life);
            this.delay = delay;
            this.heat = heat;
        }

        @Override
        boolean tick() {
            return ++age >= delay + life;
        }

        @Override
        void render(float pt, float time) {
            float local = age + pt - delay;
            if (local < 0) return;
            float f = Math.min(1f, local / life);
            float e = easeOutCubic(f);
            float r = r0 + (r1 - r0) * e;
            float w = width * (1f - 0.5f * f);
            float h = heat * (1f - f) * (1f - f);
            if (h <= 0.01f) return;
            FxRender.elemShader(W, 2.2f, 0.12f + 0.6f * f, 1.15f);
            BufferBuilder bb = FxRender.quads();
            FxRender.ring(bb, c, n, Math.max(0.01f, r - w), r, h, time * 0.4f, 48);
            FxRender.draw(bb);
        }
    }

    /** 관통: X자로 교차하는 두 줄기 참격이 번쩍이며 퍼짐 */
    static final class SlashFx extends Fx {
        final Vec3 pos, dir;
        final float roll;
        static final int LIFE = 7;

        SlashFx(Vec3 pos, Vec3 dir) {
            this.pos = pos;
            this.dir = dir;
            this.roll = RNG.nextFloat() * TAU;
            spawn(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, 2.4f, 3.6f, 4).color(0.9f, 1f, 0.96f, 1f));
            spawn(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, 3.5f, 5.0f, 7).color(TINT[0], TINT[1], TINT[2], 0.5f));
            FxManager.addRing(pos, dir, 0.2f, 2.0f, 6, 0, 0.9f, W);
            Vec3[] b = FxRender.basis(dir);
            // 참격 두 방향을 따라 튀는 바람결
            for (int j = 0; j < 2; j++) {
                double a = roll + Math.PI * 0.25 + j * Math.PI * 0.5;
                Vec3 sd = b[0].scale(Math.cos(a)).add(b[1].scale(Math.sin(a)));
                for (int i = 0; i < count(7); i++) {
                    double sg = RNG.nextBoolean() ? 1 : -1;
                    streakSpark(pos, sd.scale(sg * rand(0.5f, 1.0f)).add(dir.scale(rand(0.1f, 0.5f))), 0.07f, 5 + RNG.nextInt(4));
                }
            }
            for (int i = 0; i < count(5); i++) {
                streakSpark(pos, dir.scale(rand(0.6f, 1.2f)).add(randomDir().scale(0.3)), 0.06f, 5 + RNG.nextInt(3));
            }
            for (int i = 0; i < count(4); i++) {
                wisp(pos.add(randomDir().scale(0.3)), dir.scale(rand(0.1f, 0.25f)).add(randomDir().scale(0.06)), 0.9f, 1.6f, 6 + RNG.nextInt(3), 0.8f);
            }
            for (int i = 0; i < count(4); i++) {
                leaf(pos, randomDir().scale(rand(0.15f, 0.35f)).add(dir.scale(0.15)), 14 + RNG.nextInt(8), 0.2f);
            }
            shakeAt(pos, 0.5f, 10f);
        }

        @Override
        boolean tick() {
            return ++age >= LIFE;
        }

        @Override
        void render(float pt, float time) {
            float t = Math.min(1f, (age + pt) / (LIFE - 1));
            float e = easeOutCubic(t);
            float R = 0.7f + 1.4f * e;
            float h = (float) Math.pow(1f - t, 1.5);
            if (h <= 0.01f) return;
            Vec3[] b = FxRender.basis(dir);
            FxRender.elemShader(W, 3.0f, 0.05f + 0.55f * t, 1.3f);
            BufferBuilder bb = FxRender.quads();
            for (int j = 0; j < 2; j++) {
                double a = roll + Math.PI * 0.25 + j * Math.PI * 0.5;
                Vec3 fwd = b[0].scale(Math.cos(a)).add(b[1].scale(Math.sin(a)));
                Vec3 side = dir.cross(fwd).normalize();
                // 원호의 가운데가 맞은 곳을 지나도록 중심을 뒤로
                Vec3 c = pos.subtract(fwd.scale(R * 0.25f)).add(dir.scale(0.1 * j));
                crescent(bb, c, fwd, side, R, (float) Math.toRadians(140), 0.32f * (1f - 0.4f * t), h, j * 0.5f + time * 0.05f);
                // 같은 호의 카메라 향 앞날 (X자가 어느 각도에서도 두 줄로 읽힘)
                bladeEdge(bb, c, fwd, side, R, (float) Math.toRadians(130), 0.14f * (1f - 0.5f * t), h * 0.9f, j * 0.5f);
            }
            FxRender.draw(bb);
            frameGlow(pos, 2.4f * (1f + e), 0.4f * h);
        }
    }

    // ---------------------------------------------------------------------
    // 질풍 돌진
    // ---------------------------------------------------------------------

    static final class DashFx extends Fx {
        @Nullable final Entity caster;
        final Vec3 start, dir;
        final float rad, dist;
        final int dur;
        final Vec3[] basis;
        /** 몸 가운데 위치 기록 (앞이 최신) */
        final ArrayDeque<Vec3> hist = new ArrayDeque<>();
        Vec3 lastBody;
        final float[] lineAng = new float[6];
        final float[] lineRad = new float[6];
        /** 그리기용 점 (프레임마다 배열을 만들지 않음, hist 최대 10개) */
        final Vec3[] pts = new Vec3[11];

        /** 서버 판정 폭: 시전자 몸 상자(반폭 0.3)를 radius 만큼 키운 상자 + 상대 몸 반폭 0.3 */
        float hitEdge() {
            return rad + 0.6f;
        }

        DashFx(@Nullable Entity caster, Vec3 start, Vec3 dir, float rad, float dist, int dur) {
            this.caster = caster;
            this.start = start;
            this.dir = dir;
            this.rad = rad;
            this.dist = dist;
            this.dur = dur;
            this.basis = FxRender.basis(dir);
            for (int i = 0; i < lineAng.length; i++) {
                lineAng[i] = i * TAU / lineAng.length + rand(-0.3f, 0.3f);
                lineRad[i] = rand(0.45f, 0.95f);
            }
            Vec3 body = start.add(0, 0.9, 0);
            lastBody = body;
            hist.addFirst(body);
            // 출발 음속 고리 + 발밑 돌풍
            FxManager.addRing(body, dir, 0.3f, 3.0f, 7, 0, 1f, W);
            FxManager.addRing(body.add(dir.scale(0.8)), dir, 0.2f, 1.8f, 5, 1, 0.8f, W);
            FxManager.addRing(start.add(0, 0.06, 0), UP, 0.3f, 3.2f, 9, 0, 0.8f, W);
            shock(body, dir, 0.4f, 2.6f, 0.6f, 6, 0, 1f);
            spawn(new Sprite(Sprite.Kind.GLOW, body, Vec3.ZERO, 2.8f, 4.2f, 5).color(0.85f, 1f, 0.95f, 0.85f));
            for (int i = 0; i < count(10); i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 d = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                dust(start.add(d.scale(0.5)).add(0, 0.2, 0), d.scale(rand(0.12f, 0.26f)).add(0, 0.03, 0), 0.7f, 2.0f, 16 + RNG.nextInt(10), 0.38f);
            }
            for (int i = 0; i < count(12); i++) {
                Vec3 d = dir.scale(-rand(0.3f, 0.8f)).add(randomDir().scale(0.35));
                streakSpark(body, d, 0.08f, 6 + RNG.nextInt(4));
            }
            // 박차고 나간 땅: 뒤로 튀는 흙덩이 + 금 간 발자국
            Vec3 g = groundAt(start);
            DebrisFx.directed(g, dir.scale(-1), 3.5f, 35f, 10, 0.7f);
            DebrisFx.burst(g, 1.2f, 6, 0.45f);
            DebrisFx.crater(g, 1.4f, 90, W);
            Minecraft mc = Minecraft.getInstance();
            if (caster != null && caster == mc.player) {
                CameraShake.add(0.8f);
                limitedFlash(0xC8FFE8, 0.12f, 5); // 음속 돌파 번쩍
            }
        }

        Vec3 sim(float a) {
            return start.add(dir.scale(dist * clamp01(a / dur))).add(0, 0.9, 0);
        }

        boolean follow() {
            return caster != null && !caster.isRemoved();
        }

        Vec3 bodyNow() {
            if (follow()) return caster.position().add(0, caster.getBbHeight() * 0.5, 0);
            return sim(age);
        }

        Vec3 bodyAt(float pt) {
            if (age > dur + 1) return lastBody;
            if (follow()) return caster.getPosition(pt).add(0, caster.getBbHeight() * 0.5, 0);
            return sim(age + pt);
        }

        @Override
        boolean tick() {
            age++;
            if (age > dur + 14) return true;
            FxManager.lod = FxManager.lodAt(lastBody);
            if (age <= dur + 1) {
                Vec3 p = bodyNow();
                lastBody = p;
                hist.addFirst(p);
                while (hist.size() > 10) hist.removeLast();
            } else if (hist.size() > 1) {
                hist.removeLast();
            }
            Vec3 p = lastBody;
            Vec3 back = dir.scale(-1);
            if (age <= dur) {
                for (int i = 0; i < count(5); i++) {
                    Vec3 off = basis[0].scale(rand(-1f, 1f)).add(basis[1].scale(rand(-1.1f, 1.1f)));
                    streakSpark(p.add(off).add(dir.scale(rand(0f, 1.5f))), back.scale(rand(0.6f, 1.0f)), 0.06f, 4 + RNG.nextInt(3));
                }
                for (int i = 0; i < count(3); i++) {
                    leaf(p.add(back.scale(rand(0.3f, 1.2f))).add(randomDir().scale(0.6)), back.scale(rand(0.1f, 0.25f)).add(randomDir().scale(0.08)), 16 + RNG.nextInt(10), 0.2f);
                }
                for (int i = 0; i < count(2); i++) {
                    Vec3 feet = p.add(0, -0.8, 0);
                    dust(feet.add(rand(-0.5f, 0.5f), 0, rand(-0.5f, 0.5f)), back.scale(0.06).add(rand(-0.06f, 0.06f), rand(0.02f, 0.06f), rand(-0.06f, 0.06f)),
                            0.7f, 2.0f, 16 + RNG.nextInt(8), 0.32f);
                }
                for (int i = 0; i < count(2); i++) {
                    wisp(p.add(back.scale(rand(0.4f, 1.4f))).add(randomDir().scale(0.4)), back.scale(0.1), rand(1.0f, 1.5f), 0.4f, 5 + RNG.nextInt(3), 0.8f);
                }
            }
            if (age == dur) {
                // 도착 음속 고리
                FxManager.addRing(p.add(dir.scale(0.6)), dir, 0.4f, 3.4f, 8, 0, 1f, W);
                FxManager.addRing(p.add(0, -0.85, 0), UP, 0.3f, 2.8f, 8, 1, 0.7f, W);
                shock(p.add(dir.scale(0.4)), dir, 0.4f, 2.8f, 0.6f, 7, 0, 1f);
                spawn(new Sprite(Sprite.Kind.GLOW, p, Vec3.ZERO, 2.4f, 4.0f, 5).color(0.85f, 1f, 0.95f, 0.8f));
                for (int i = 0; i < count(12); i++) {
                    Vec3 d = dir.scale(rand(0.2f, 0.6f)).add(randomDir().scale(0.45));
                    streakSpark(p, d, 0.07f, 6 + RNG.nextInt(4));
                }
                for (int i = 0; i < count(6); i++) {
                    wisp(p.add(randomDir().scale(0.4)), dir.scale(0.15).add(randomDir().scale(0.12)), 1.0f, 2.0f, 7, 0.8f);
                }
                // 멈춰 선 자리: 앞으로 쏟아지는 흙덩이 + 바닥 충격
                Vec3 ge = groundAt(p);
                DebrisFx.burst(ge, 1.6f, 8, 0.6f);
                DebrisFx.directed(ge, dir, 3f, 40f, 8, 0.7f);
                for (int i = 0; i < count(8); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    Vec3 d = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                    dust(ge.add(d.scale(0.4)).add(0, 0.2, 0), d.scale(rand(0.12f, 0.24f)).add(dir.scale(0.08)), 0.7f, 2.2f, 16 + RNG.nextInt(8), 0.38f);
                }
                shakeAt(p, 0.7f, 10f);
            }
            return false;
        }

        @Override
        void render(float pt, float time) {
            float t = age + pt;
            float fadeEnd = 1f - clamp01((t - dur) / 10f);
            if (fadeEnd <= 0f) return;
            Vec3 cur = bodyAt(pt);
            RenderSystem.disableCull();

            float edge = hitEdge();
            // 앞쪽 바람 원뿔 (2겹, 바깥 원뿔 = 부딪히는 폭)
            if (t < dur + 4) {
                float cf = t <= dur ? Math.min(1f, t / 1.5f) : 1f - (t - dur) / 4f;
                FxRender.elemShader(W, 3.2f, 0.08f + 0.4f * (1f - cf), 1.2f);
                BufferBuilder bb = FxRender.quads();
                cone(bb, cur.subtract(dir.scale(0.3)), dir, basis, 1.15f * cf, 2.8f, 1f * cf, time);
                cone(bb, cur.subtract(dir.scale(0.9)), dir, basis, edge * cf, 3.6f, 0.45f * cf, -time * 0.8f);
                FxRender.draw(bb);
            }

            // 돌진 중에만: 출발 → 도착 전체 길 = 서버가 부딪힘을 판정하는 폭 (공용 위험 표시, 몸이 지나갈수록 차오름).
            // 멈추면 바로 꺼짐 → 남는 속도선·잔상에는 테두리가 없음
            if (t <= dur) lane(start, dir, dist, edge, t / dur, caster, 1f, time);

            // 지나온 길의 속도선 + 넓은 바람 꼬리
            int n = Math.min(hist.size(), pts.length);
            if (n >= 2) {
                pts[0] = cur;
                int i = 0;
                for (Vec3 v : hist) {
                    if (i > 0 && i < n) pts[i] = v;
                    i++;
                }
                FxRender.elemShader(W, 3.4f, 0.15f, 1.2f);
                BufferBuilder bb = FxRender.quads();
                for (int l = 0; l < lineAng.length; l++) {
                    double a = lineAng[l];
                    Vec3 off = basis[0].scale(Math.cos(a) * lineRad[l]).add(basis[1].scale(Math.sin(a) * lineRad[l] * 1.3));
                    for (int s = 0; s < n - 1; s++) {
                        float f0 = (float) s / (n - 1), f1 = (float) (s + 1) / (n - 1);
                        float h0 = (1f - f0) * FxManager.smooth(f0 / 0.15f) * 0.95f * fadeEnd;
                        float h1 = (1f - f1) * FxManager.smooth(f1 / 0.15f) * 0.95f * fadeEnd;
                        ribbonCam(bb, pts[s].add(off), pts[s + 1].add(off), 0.13f, 0.08f, h0, h1, f0 * 2f + l * 0.3f, f1 * 2f + l * 0.3f);
                    }
                }
                // 넓은 바람 꼬리: 이동 축을 따라 가로·세로로 교차한 띠 2장 (뒤에서 봐도 + 모양 단면이 남아 납작해지지 않음)
                Vec3 sideH = new Vec3(-dir.z, 0, dir.x);
                for (int s = 0; s < n - 1; s++) {
                    float f0 = (float) s / (n - 1), f1 = (float) (s + 1) / (n - 1);
                    float h0 = (1f - f0) * FxManager.smooth(f0 / 0.2f) * 0.32f * fadeEnd;
                    float h1 = (1f - f1) * FxManager.smooth(f1 / 0.2f) * 0.32f * fadeEnd;
                    float w0 = 1.8f * (1f - 0.5f * f0), w1 = 1.8f * (1f - 0.5f * f1);
                    ribbonSide(bb, pts[s], pts[s + 1], sideH, w0, w1, h0, h1, f0 * 1.5f, f1 * 1.5f);
                    ribbonSide(bb, pts[s], pts[s + 1], UP, w0 * 0.8f, w1 * 0.8f, h0, h1, f0 * 1.5f + 0.4f, f1 * 1.5f + 0.4f);
                }
                FxRender.draw(bb);
            }

            // 몸 잔상 (지난 자리마다 빛 기둥)
            int idx = 0;
            for (Vec3 v : hist) {
                if (idx > 8) break;
                if (idx > 0) {
                    float a = 0.3f * (1f - idx / 9f) * fadeEnd;
                    frameGlow(v.add(0, -0.5, 0), 1.2f, a);
                    frameGlow(v, 1.5f, a);
                    frameGlow(v.add(0, 0.55, 0), 1.1f, a);
                }
                idx++;
            }
            if (t <= dur + 2) frameGlow(cur.add(dir.scale(0.6)), 2.6f, 0.35f * fadeEnd);
        }
    }

    // ---------------------------------------------------------------------
    // 회오리 / 천공의 대폭풍
    // ---------------------------------------------------------------------

    static final class TornadoFx extends Fx {
        final Vec3 base;
        final float R, H;
        final int dur;
        final boolean big;
        final float r0, r1, seed;
        final int id = ++idSeq;
        /** 끝난 뒤 낙엽·흙먼지가 내려앉는 시간 */
        final int linger;

        @Nullable final Entity caster;

        TornadoFx(@Nullable Entity caster, Vec3 base, float R, float H, int dur, boolean big) {
            this.caster = caster;
            this.base = base;
            this.R = R;
            this.H = H;
            this.dur = dur;
            this.big = big;
            this.r0 = R * 0.45f;
            this.r1 = R * 1.2f;
            this.seed = RNG.nextFloat() * 10f;
            FxManager.lod = FxManager.lodAt(base);
            Vec3 rc = base.add(0, 0.06, 0);
            // 생성: 충격파가 끌어당기는 범위 끝까지 한 번 훑고(어디까지 휘말리는지), 안쪽 고리가 띄우는 범위에 멈춤
            FxManager.addRing(rc, UP, R * 0.3f, pullR(R, big), big ? 10 : 8, 0, 1f, W);
            FxManager.addRing(rc, UP, R * 0.2f, liftR(R), 7, 2, 0.8f, W);
            shock(rc, UP, R * 0.3f, pullR(R, big), R * 0.4f, big ? 10 : 8, 0, 1f);
            spawn(new Sprite(Sprite.Kind.GLOW, base.add(0, 1.0, 0), Vec3.ZERO, R * 2.5f, R * 3.5f, 7).color(0.85f, 1f, 0.95f, 0.7f));
            for (int i = 0; i < count(big ? 40 : 18); i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 d = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                Vec3 tg = new Vec3(-Math.sin(ang), 0, Math.cos(ang));
                dust(base.add(d.scale(R * rand(0.3f, 0.9f))).add(0, 0.3, 0), d.scale(rand(0.1f, 0.25f) * R / 3f).add(tg.scale(0.1)).add(0, 0.04, 0),
                        R * 0.35f, R * 0.9f, 22 + RNG.nextInt(14), 0.4f);
            }
            for (int i = 0; i < count(big ? 40 : 16); i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 d = new Vec3(Math.cos(ang) * 0.4, 1, Math.sin(ang) * 0.4).normalize();
                streakSpark(base.add(0, 0.3, 0), d.scale(rand(0.6f, 1.3f) * (big ? 1.5f : 1f)), big ? 0.16f : 0.1f, 8 + RNG.nextInt(8));
            }
            shakeAt(base, big ? 3.0f : 1.2f, big ? R * 4f : R * 3f);
            this.linger = big ? 40 : 20; // 끝난 뒤 내려앉는 낙엽·흙먼지 (짧게: 다음 동작이 묻히지 않게)
            // 땅이 뜯겨 나감: 사방으로 튀는 조각 + 금 간 자국(바람빛) + 처음 빨려 드는 조각들
            DebrisFx.burst(base, R * 0.9f, big ? 36 : 14, big ? 1.3f : 0.65f);
            DebrisFx.crater(base, R * (big ? 1.35f : 1.05f), dur + (big ? 160 : 80), W);
            for (int i = 0; i < count(big ? 26 : 10); i++) WindDebris.lift(id, base, R * 0.8f, H * 0.75f, big ? 1.6f : 1f);
        }

        float grow(float t) {
            return easeOutCubic(clamp01(t / (big ? 14f : 8f)));
        }

        float end(float t) {
            return clamp01((t - dur) / 10f);
        }

        double swayX(float t) {
            return (Math.sin(t * 0.06 + seed) * (big ? 0.18 : 0.22) + Math.sin(t * 0.17 + seed * 2.0) * 0.05) * R;
        }

        double swayZ(float t) {
            return (Math.cos(t * 0.047 + seed * 1.3) * (big ? 0.18 : 0.22) + Math.cos(t * 0.13 + seed) * 0.05) * R;
        }

        /** 높이 비율 f 에서의 깔때기 반지름 (같은 곡선을 그림과 입자가 함께 씀) */
        float radiusAt(float f) {
            return r0 + (r1 - r0) * (float) Math.pow(clamp01(f), 1.4);
        }

        Vec3 axisAt(float f, float t) {
            double s = Math.pow(clamp01(f), 1.6);
            return base.add(swayX(t) * s, H * f * grow(t), swayZ(t) * s);
        }

        @Override
        boolean tick() {
            age++;
            float t = age;
            if (t > dur + Math.max(16, linger)) return true;
            FxManager.lod = FxManager.lodAt(base);
            float sz = big ? 2.2f : 1f;
            if (t > dur) settle();
            if (t <= dur) {
                float g = grow(t);
                // 바닥 블록 조각이 빨려 들어가 깔때기를 따라 솟음
                for (int i = 0; i < count(big ? 4 : 1); i++) {
                    WindDebris.lift(id, base, radiusAt(0.05f) * 1.15f, Math.max(2f, H * g * 0.8f), big ? 1.6f : 1f);
                }
                // 테두리의 땅이 계속 뜯겨 나감
                if (age % (big ? 5 : 9) == 0) {
                    float ang = RNG.nextFloat() * TAU;
                    float er = liftR(R);
                    DebrisFx.burst(base.add(Math.cos(ang) * er, 0, Math.sin(ang) * er), 1.2f, big ? 4 : 2, big ? 0.9f : 0.55f);
                }
                // 깔때기를 따라 감아 오르는 바람결
                for (int i = 0; i < count(big ? 8 : 4); i++) {
                    float f = RNG.nextFloat() * 0.9f;
                    float ang = RNG.nextFloat() * TAU;
                    Vec3 ax = axisAt(f, t);
                    float rr = radiusAt(f) * rand(0.85f, 1.05f);
                    Vec3 tg = new Vec3(-Math.sin(ang), 0, Math.cos(ang));
                    wisp(ax.add(Math.cos(ang) * rr, 0, Math.sin(ang) * rr), tg.scale(0.3 * sz).add(0, 0.25 * sz, 0), rand(1.2f, 2.0f) * sz, 0.6f * sz, 7 + RNG.nextInt(4), 0.85f);
                }
                // 빙빙 도는 나뭇잎·잔해
                for (int i = 0; i < count(big ? 12 : 5); i++) {
                    float f = RNG.nextFloat() * 0.85f * g;
                    float ang = RNG.nextFloat() * TAU;
                    Vec3 ax = axisAt(f, t);
                    float rr = radiusAt(f) * rand(0.8f, 1.35f);
                    Vec3 tg = new Vec3(-Math.sin(ang), 0, Math.cos(ang));
                    Vec3 in = new Vec3(-Math.cos(ang), 0, -Math.sin(ang));
                    leaf(ax.add(Math.cos(ang) * rr, 0, Math.sin(ang) * rr), tg.scale(rand(0.35f, 0.6f) * sz).add(in.scale(0.08)).add(0, rand(0.1f, 0.3f), 0),
                            10 + RNG.nextInt(8), big ? 0.35f : 0.22f);
                }
                // 바닥을 휩쓰는 흙먼지 고리
                for (int i = 0; i < count(big ? 7 : 3); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = R * rand(0.6f, 1.6f);
                    Vec3 tg = new Vec3(-Math.sin(ang), 0, Math.cos(ang));
                    Vec3 in = new Vec3(-Math.cos(ang), 0, -Math.sin(ang));
                    boolean gray = RNG.nextBoolean();
                    Sprite sm = new Sprite(Sprite.Kind.SMOKE, base.add(Math.cos(ang) * rr, rand(0.2f, 0.8f), Math.sin(ang) * rr),
                            tg.scale(0.22 * sz).add(in.scale(0.06 * sz)).add(0, rand(0.03f, 0.08f), 0), 1.2f * sz, 3.0f * sz, 18 + RNG.nextInt(10))
                            .color(gray ? 0.62f : 0.8f, gray ? 0.64f : 0.74f, gray ? 0.62f : 0.62f, 0.35f).elem(W).physics(-0.002f, 0.95f);
                    sm.rot = RNG.nextFloat() * TAU;
                    spawn(sm);
                }
                // 빠른 속도선
                for (int i = 0; i < count(big ? 6 : 3); i++) {
                    float f = RNG.nextFloat() * 0.9f;
                    float ang = RNG.nextFloat() * TAU;
                    Vec3 ax = axisAt(f, t);
                    float rr = radiusAt(f) * 1.1f;
                    Vec3 tg = new Vec3(-Math.sin(ang), 0.15, Math.cos(ang));
                    streakSpark(ax.add(Math.cos(ang) * rr, 0, Math.sin(ang) * rr), tg.scale(rand(0.5f, 0.9f) * sz), big ? 0.14f : 0.08f, 4 + RNG.nextInt(3));
                }
                if (big) {
                    // 사방으로 날아다니는 잔해
                    for (int i = 0; i < count(3); i++) {
                        float f = RNG.nextFloat() * 0.7f;
                        float ang = RNG.nextFloat() * TAU;
                        Vec3 ax = axisAt(f, t);
                        float rr = radiusAt(f);
                        Vec3 out = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                        Vec3 tg = new Vec3(-Math.sin(ang), 0, Math.cos(ang));
                        Sprite sm = new Sprite(Sprite.Kind.SMOKE, ax.add(out.scale(rr)), tg.scale(rand(0.6f, 1.0f)).add(out.scale(rand(0.2f, 0.45f))).add(0, rand(0.1f, 0.4f), 0),
                                rand(0.4f, 0.7f), rand(0.8f, 1.2f), 16 + RNG.nextInt(10)).color(0.45f, 0.43f, 0.4f, 0.75f).elem(W).physics(0.03f, 0.97f);
                        sm.rot = RNG.nextFloat() * TAU;
                        spawn(sm);
                    }
                    if (age % 3 == 0) {
                        shock(base.add(0, 0.08, 0), UP, R * 0.5f, R * 2.0f, R * 0.25f, 12, 0, 0.5f);
                    }
                }
                // 가까우면 땅울림
                Player me = Minecraft.getInstance().player;
                if (me != null) {
                    double d = me.position().distanceTo(base);
                    double range = big ? R * 3.5 : R * 2.0;
                    if (d < range && age % 2 == 0) CameraShake.add((float) ((big ? 1.4 : 0.35) * (1 - d / range)));
                }
            }
            if (age == dur) burstOut();
            return false;
        }

        /** 끝: 서버가 바깥으로 날려 보내는 순간 */
        void burstOut() {
            float sz = big ? 2.2f : 1f;
            Vec3 rc = base.add(0, 0.08, 0);
            // 서버가 끌어당기는 범위(pullR) 안의 모두를 날려 보냄 → 바닥 충격파가 정확히 그 테두리까지
            float pr = pullR(R, big);
            FxManager.addRing(rc, UP, R * 0.5f, pr, 8, 0, 1f, W);
            FxManager.addRing(rc, UP, pr * 0.9f, pr * 1.35f, 10, 3, 0.45f, W);
            FxManager.addRing(base.add(0, H * 0.35, 0), UP, R * 0.6f, pr * 0.95f, 9, 1, 0.8f, W);
            FxManager.addRing(base.add(0, H * 0.7, 0), UP, R * 0.8f, pr * 0.85f, 9, 2, 0.6f, W);
            shock(rc, UP, R * 0.6f, pr, R * 0.5f, 8, 0, 1f);
            shock(base.add(0, H * 0.4, 0), UP, R * 0.8f, pr * 0.9f, R * 0.4f, 9, 1, 0.8f);
            spawn(new Sprite(Sprite.Kind.GLOW, base.add(0, H * 0.35, 0), Vec3.ZERO, H * 0.8f + R * 2f, H * 1.1f + R * 3f, 9).color(0.85f, 1f, 0.95f, 0.6f));
            for (int i = 0; i < count(big ? 50 : 22); i++) {
                float f = RNG.nextFloat() * 0.9f;
                float ang = RNG.nextFloat() * TAU;
                Vec3 out = new Vec3(Math.cos(ang), rand(0.05f, 0.3f), Math.sin(ang));
                Vec3 ax = axisAt(f, age);
                wisp(ax.add(out.scale(radiusAt(f) * 0.8f)), out.scale(rand(0.35f, 0.6f) * sz), rand(1.4f, 2.0f) * sz, rand(2.4f, 3.2f) * sz, 9 + RNG.nextInt(6), 0.85f);
            }
            for (int i = 0; i < count(big ? 60 : 26); i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 out = new Vec3(Math.cos(ang), rand(0.0f, 0.4f), Math.sin(ang)).normalize();
                streakSpark(base.add(0, rand(0.5f, H * 0.6f), 0), out.scale(rand(0.8f, 1.6f) * sz), big ? 0.16f : 0.1f, 7 + RNG.nextInt(6));
            }
            for (int i = 0; i < count(big ? 30 : 12); i++) {
                Vec3 d = randomDir().add(0, 0.5, 0).normalize();
                leaf(base.add(0, rand(0.5f, H * 0.5f), 0), d.scale(rand(0.4f, 0.8f) * sz), 20 + RNG.nextInt(14), big ? 0.35f : 0.22f);
            }
            for (int i = 0; i < count(big ? 20 : 10); i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 out = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                dust(base.add(out.scale(R * 0.7f)).add(0, 0.4, 0), out.scale(rand(0.25f, 0.45f) * sz).add(0, 0.03, 0), 1.2f * sz, 3.6f * sz, 22 + RNG.nextInt(10), 0.4f);
            }
            shakeAt(base, big ? 4.0f : 1.6f, big ? R * 5f : R * 4f);
            // 빨려 올라간 조각을 바깥으로 내던지고, 바닥이 한 번 더 터짐
            WindDebris.fling(id, big ? 1.8f : 1.1f);
            DebrisFx.burst(base, R * 1.5f, big ? 40 : 16, big ? 1.5f : 0.9f);
            if (big) {
                flashNear(base, 0xF0FFF8, 0.5f, 12, 64f);
                shock(base.add(0, H * 0.6, 0), UP, R, pr * 1.2f, R * 0.6f, 12, 0, 0.9f);
                FxManager.addRing(base.add(0, 0.1, 0), UP, R, pr * 1.5f, 14, 2, 0.55f, W);
            }
        }

        /** 끝난 뒤: 하늘로 올라갔던 낙엽·흙먼지가 천천히 내려앉음 */
        void settle() {
            float lf = 1f - clamp01((age - dur) / (float) linger);
            if (lf <= 0f) return;
            float spread = R * (big ? 3f : 2f);
            for (int i = 0; i < count(big ? 3 : 1); i++) {
                if (RNG.nextFloat() > lf) continue;
                Vec3 at = base.add(rand(-spread, spread), rand(H * 0.25f, H * 0.7f), rand(-spread, spread));
                leaf(at, new Vec3(rand(-0.05f, 0.05f), -rand(0.02f, 0.06f), rand(-0.05f, 0.05f)), 40 + RNG.nextInt(30), big ? 0.32f : 0.22f);
            }
            if (age % 3 == 0 && RNG.nextFloat() < lf) {
                float ang = RNG.nextFloat() * TAU;
                float rr = R * rand(0.6f, 1.8f);
                dust(base.add(Math.cos(ang) * rr, 0.3, Math.sin(ang) * rr), new Vec3(-Math.sin(ang) * 0.05, 0.01, Math.cos(ang) * 0.05),
                        1.0f * (big ? 2f : 1f), 3.0f * (big ? 2f : 1f), 30 + RNG.nextInt(15), 0.3f * lf);
            }
        }

        @Override
        void render(float pt, float time) {
            float t = age + pt;
            float en = end(t);
            float fade = 1f - en;
            if (fade <= 0.001f) return;
            float g = grow(t);
            float hh = H * g * (1f + 0.15f * en);
            float widen = 1f + 0.7f * en;
            float erosion = Math.min(1f, 0.05f + 0.9f * en + (t < 4f ? (1f - t / 4f) * 0.5f : 0f));
            double sx = swayX(t), sz = swayZ(t);
            int seg = big ? 22 : 20, rows = big ? 12 : 9;
            float twist = big ? 0.26f : 0.62f;
            RenderSystem.disableCull();
            // 띄워진 상대·시전자가 보이게 몸 높이(띄움 높이 포함)를 비움
            maskH = big ? 6f : 4.2f;
            maskK = 0.78f;

            if (big) {
                // 가장 바깥 옅은 막
                FxRender.elemShader(W, 1.0f, Math.min(1f, erosion + 0.2f), 0.55f * fade + 0.05f);
                funnel(base, r0 * 1.35f * widen, r1 * 1.3f * widen, 1.4f, hh * 1.02f, 0.45f, twist * 0.8f, 0.12f, 0.7f, 0.1f, time, sx, sz, seg, rows, 0.8f);
            }
            // 바깥 깔때기
            FxRender.elemShader(W, 1.5f, erosion, 0.95f * fade + 0.05f);
            funnel(base, r0 * widen, r1 * widen, 1.4f, hh, 0.8f, twist, big ? 0.16f : 0.3f, 0f, 0.09f, time, sx, sz, seg, rows, 0.7f);
            // 가운데 깔때기 (반대로 꼬임, 더 빠름)
            FxRender.elemShader(W, 2.2f, erosion * 0.9f, 1.05f * fade + 0.05f);
            funnel(base, r0 * 0.68f * widen, r1 * 0.8f * widen, 1.3f, hh * 0.97f, 0.9f, -twist * 1.2f, big ? 0.22f : 0.42f, 0.37f, 0.07f, time, sx, sz, seg, rows, 0.55f);
            // 심 (가늘고 하얗게) — 상대가 띄워지는 바로 그 자리라 몸 높이에서 더 많이 비움
            maskK = 0.92f;
            FxRender.elemShader(W, 3.0f, erosion * 0.8f, 1.2f * fade + 0.05f);
            funnel(base, r0 * 0.3f * widen, r1 * 0.42f * widen, 1.2f, hh * 0.9f, 1f, twist * 1.6f, big ? 0.3f : 0.55f, 0.71f, 0.05f, time, sx, sz, 14, rows, 0.3f);
            maskK = 0f;

            // 깔때기를 감아 솟는 나선 줄기
            FxRender.elemShader(W, 3.0f, erosion * 0.7f, 1.15f * fade);
            BufferBuilder bb = FxRender.quads();
            int strands = big ? 8 : 5;
            final int steps = 13;
            float len = 0.45f;
            float rise = big ? 0.011f : 0.028f;
            float spinR = big ? 0.2f : 0.36f;
            for (int j = 0; j < strands; j++) {
                float f0 = ((time * rise + j * 0.37f + seed) % (1f + len)) - len;
                float ph = j * TAU / strands;
                Vec3 prev = null;
                float prevH = 0f, prevF = 0f;
                for (int s = 0; s <= steps; s++) {
                    float ss = (float) s / steps;
                    float f = f0 + len * ss;
                    if (f < 0f || f > 1f) {
                        prev = null;
                        continue;
                    }
                    double ang = ph + spinR * time + f * (twist * H * 1.3f);
                    float rr = radiusAt(f) * widen * 1.12f;
                    double sw = Math.pow(f, 1.6);
                    Vec3 p = base.add(Math.cos(ang) * rr + sx * sw, f * hh, Math.sin(ang) * rr + sz * sw);
                    float h = (float) Math.sin(Math.PI * ss) * 0.95f * fade;
                    if (prev != null) {
                        float w0 = (big ? 0.9f : 0.32f) * (0.5f + prevF), w1 = (big ? 0.9f : 0.32f) * (0.5f + f);
                        ribbonCam(bb, prev, p, w0, w1, prevH, h, ss * 2f + j * 0.3f, ss * 2f + j * 0.3f + 0.15f);
                    }
                    prev = p;
                    prevH = h;
                    prevF = f;
                }
            }
            FxRender.draw(bb);

            // 바닥 소용돌이 (장식: 부드러운 띠, 테두리 선 없음 — 범위 끝은 아래 공용 위험 표시가 맡음)
            FxRender.elemShader(W, 1.4f, erosion, 0.95f * fade);
            bb = FxRender.quads();
            Vec3 gc = base.add(0, 0.06, 0);
            float lr = liftR(R), pr = pullR(R, big);
            float pulse = 1f + 0.04f * (float) Math.sin(time * 0.4f);
            FxRender.ring(bb, gc, UP, R * 0.35f, lr * 0.9f * pulse, 0.85f * fade, time * 0.35f, 56);
            FxRender.draw(bb);
            if (t <= dur) {
                // 안쪽 = 띄우고 0.5초(서버 10틱)마다 피해 → 피해 주기마다 차오름 / 바깥 = 끌려 들어가고 끝 틱에 날려 보냄
                zone(base, lr, ((age % 10) + pt) / 10f, caster, 1f, time);
                zone(base, pr, t / dur, caster, 0.85f, time);
            }

            // 전체를 감싸는 빛
            Vec3 mid = axisAt(0.45f, t);
            frameGlow(mid, hh * 0.9f + R * 2f, (big ? 0.16f : 0.22f) * fade);
            frameGlow(base.add(0, 0.6, 0), R * 2.6f, 0.25f * fade);
        }

        /** 반투명 흙먼지 막 (밝은 하늘에서도 회오리 모양이 읽히도록) */
        void renderMist(BufferBuilder bb, float pt, float time) {
            float t = age + pt;
            float fade = 1f - end(t);
            if (fade <= 0.001f) return;
            float g = grow(t);
            int n = big ? 14 : 8;
            float widen = 1f + 0.7f * end(t);
            for (int i = 0; i < n; i++) {
                float f = (i + 0.5f) / n;
                Vec3 ax = axisAt(f, t);
                float rr = radiusAt(f) * widen;
                int frame = 16 + (i * 5) % 24;
                float u0 = (frame & 7) / 8f, v0 = (frame >> 3) / 8f;
                float a = (big ? 0.2f : 0.16f) * fade * g * (1f - 0.5f * f);
                // 몸 높이의 흙먼지 막은 옅게 (안의 사람을 덮지 않게)
                a *= 0.3f + 0.7f * clamp01((float) (ax.y - base.y - (big ? 6.0 : 4.2)) / 2f);
                float shade = big ? 0.62f : 0.82f;
                FxRender.billboard(bb, ax, rr * 2.3f, time * 0.04f * (i % 2 == 0 ? 1 : -1) + i, u0, v0, u0 + 0.125f, v0 + 0.125f,
                        shade, shade + 0.03f, shade, a);
            }
            // 바닥 흙먼지 띠
            int m = big ? 10 : 6;
            for (int i = 0; i < m; i++) {
                double ang = i * TAU / m + time * 0.05;
                float rr = R * 1.2f * widen;
                Vec3 p = base.add(Math.cos(ang) * rr, 0.6 * (big ? 2 : 1), Math.sin(ang) * rr);
                int frame = 20 + (i * 3) % 20;
                float u0 = (frame & 7) / 8f, v0 = (frame >> 3) / 8f;
                FxRender.billboard(bb, p, R * (big ? 1.4f : 1.3f), (float) ang, u0, v0, u0 + 0.125f, v0 + 0.125f, 0.8f, 0.75f, 0.64f, 0.22f * fade * g);
            }
            if (big) {
                // 꼭대기에서 도는 폭풍 구름 고리
                float topY = H * g;
                int m2 = 14;
                for (int i = 0; i < m2; i++) {
                    double ang = i * TAU / m2 + time * 0.012;
                    double rr = R * (2.1 + 0.45 * Math.sin(i * 1.7));
                    Vec3 p = base.add(Math.cos(ang) * rr + swayX(t), topY * 0.95 + Math.sin(i * 2.3) * 1.2, Math.sin(ang) * rr + swayZ(t));
                    int frame = 8 + (i * 7) % 40;
                    float u0 = (frame & 7) / 8f, v0 = (frame >> 3) / 8f;
                    FxRender.billboard(bb, p, R * 2.6f, (float) ang * 2f, u0, v0, u0 + 0.125f, v0 + 0.125f, 0.34f, 0.38f, 0.4f, 0.4f * fade * g);
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // 폭풍 방벽
    // ---------------------------------------------------------------------

    /**
     * 폭풍 방벽. 서버는 발동 틱에 반지름 R 안을 한 번에 판정 → 바람 벽이 EXPAND 틱 만에 정확히 R 까지 퍼지고 멈춤,
     * 테두리에 잠깐 남는 점선 고리로 범위를 보여 준 뒤 빠르게 사라짐.
     */
    static final class BurstFx extends Fx {
        static final int LINGER = 14, EXPAND = 5;
        final Vec3 c;
        final float R;
        final int life;
        final float[] ang = new float[28];
        final float[] hgt = new float[28];
        final float[] len = new float[28];

        @Nullable final Entity caster;

        BurstFx(@Nullable Entity caster, Vec3 c, float R, int life) {
            this.caster = caster;
            this.c = c;
            this.R = R;
            this.life = life;
            for (int i = 0; i < ang.length; i++) {
                ang[i] = i * TAU / ang.length + rand(-0.1f, 0.1f);
                hgt[i] = rand(0.3f, 2.0f);
                len[i] = rand(0.35f, 0.6f);
            }
            FxManager.lod = FxManager.lodAt(c);
            Vec3 mid = c.add(0, 1.0, 0);
            spawn(new Sprite(Sprite.Kind.GLOW, mid, Vec3.ZERO, 6f, 9f, 5).color(0.9f, 1f, 0.96f, 1f));
            spawn(new Sprite(Sprite.Kind.GLOW, mid, Vec3.ZERO, R * 1.8f, R * 2.6f, 11).color(TINT[0], TINT[1], TINT[2], 0.5f));
            Vec3 rc = c.add(0, 0.07, 0);
            FxManager.addRing(rc, UP, 0.6f, R, 7, 0, 1f, W);
            FxManager.addRing(rc, UP, 0.4f, R * 0.8f, 7, 2, 0.75f, W);
            FxManager.addRing(mid, UP, 0.5f, R * 0.95f, 6, 1, 0.7f, W);
            FxManager.addRing(mid.add(0, 0.8, 0), UP, 0.4f, R * 0.75f, 6, 2, 0.5f, W);
            shock(rc, UP, 0.8f, R, 1.2f, EXPAND + 2, 0, 1f);
            shock(mid, UP, 0.6f, R * 0.95f, 0.7f, EXPAND + 2, 1, 0.8f);
            // 방사 속도선
            for (int i = 0; i < count(44); i++) {
                float a = RNG.nextFloat() * TAU;
                Vec3 d = new Vec3(Math.cos(a), rand(0.0f, 0.25f), Math.sin(a)).normalize();
                streakSpark(c.add(0, rand(0.3f, 1.8f), 0), d.scale(rand(0.9f, 1.6f)), 0.1f, 7 + RNG.nextInt(5));
            }
            // 바깥으로 흩날리는 나뭇잎·바람결·흙먼지 고리
            for (int i = 0; i < count(18); i++) {
                Vec3 d = new Vec3(rand(-1f, 1f), rand(0.1f, 0.6f), rand(-1f, 1f)).normalize();
                leaf(mid, d.scale(rand(0.4f, 0.8f)), 18 + RNG.nextInt(12), 0.22f);
            }
            for (int i = 0; i < count(16); i++) {
                float a = RNG.nextFloat() * TAU;
                Vec3 d = new Vec3(Math.cos(a), 0.05, Math.sin(a));
                wisp(c.add(d.scale(1.0)).add(0, rand(0.4f, 1.4f), 0), d.scale(rand(0.4f, 0.6f)), rand(1.2f, 1.8f), rand(2.4f, 3.2f), 8 + RNG.nextInt(5), 0.85f);
            }
            for (int i = 0; i < count(22); i++) {
                float a = RNG.nextFloat() * TAU;
                Vec3 d = new Vec3(Math.cos(a), 0, Math.sin(a));
                dust(c.add(d.scale(0.8)).add(0, 0.25, 0), d.scale(rand(0.4f, 0.6f) * R / 7f + 0.12f).add(0, 0.02, 0), 0.9f, 2.8f, 18 + RNG.nextInt(10), 0.4f);
            }
            shakeAt(c, 2.2f, R + 10f);
            // 발밑 땅이 터져 나감 + 바람빛으로 금 간 자국
            DebrisFx.burst(c, R * 0.5f, 18, 0.9f);
            DebrisFx.crater(c, 2.4f, 110, W);
            flashNear(c, 0xD8FFF0, 0.2f, 7, R + 4f);
        }

        float radius(float t, float delay) {
            float f = easeOutCubic(clamp01((t - delay) / EXPAND));
            return 0.8f + (R - 0.8f) * f;
        }

        @Override
        boolean tick() {
            age++;
            if (age > life + LINGER) return true;
            FxManager.lod = FxManager.lodAt(c);
            if (age > EXPAND && age % 2 == 0 && RNG.nextFloat() < 1f - (age - EXPAND) / (float) (life - EXPAND + LINGER)) {
                // 잔류: 가장자리에서 천천히 도는 흙먼지
                float a = RNG.nextFloat() * TAU;
                Vec3 d = new Vec3(Math.cos(a), 0, Math.sin(a));
                dust(c.add(d.scale(R * rand(0.8f, 1.05f))).add(0, 0.2, 0), new Vec3(-d.z * 0.06, 0.01, d.x * 0.06), 0.9f, 2.6f, 24 + RNG.nextInt(10), 0.28f);
            }
            if (age <= EXPAND + 1) {
                float rr = radius(age, 0f);
                for (int i = 0; i < count(8); i++) {
                    float a = RNG.nextFloat() * TAU;
                    Vec3 d = new Vec3(Math.cos(a), 0, Math.sin(a));
                    wisp(c.add(d.scale(rr)).add(0, rand(0.3f, 1.3f), 0), d.scale(rand(0.15f, 0.28f)).add(0, 0.03, 0), rand(0.9f, 1.4f), 0.5f, 5 + RNG.nextInt(4), 0.8f);
                }
                for (int i = 0; i < count(3); i++) {
                    float a = RNG.nextFloat() * TAU;
                    Vec3 d = new Vec3(Math.cos(a), 0, Math.sin(a));
                    dust(c.add(d.scale(rr)).add(0, 0.2, 0), d.scale(0.15).add(0, 0.03, 0), 0.8f, 2.2f, 16 + RNG.nextInt(8), 0.3f);
                }
            }
            return false;
        }

        @Override
        void render(float pt, float time) {
            float t = age + pt;
            // 판정은 발동 틱 한 번 → 벽이 그 범위(R)까지 퍼지는 동안만 공용 위험 표시(다 찬 상태)를 보여 주고 꺼짐.
            // 그 뒤 남는 흙먼지에는 테두리가 없음 (= 이제 안전)
            if (t < EXPAND + 1f) zone(c, R, 1f, caster, 1f - clamp01((t - EXPAND + 1f) / 2f), time);
            // 벽은 테두리에 닿은 뒤 짧게 흩어짐
            float fade = 1f - clamp01((t - EXPAND) / 6f);
            if (fade <= 0f) return;
            float erosion = Math.min(1f, 0.05f + 0.9f * clamp01((t - EXPAND * 0.6f) / 8f));
            float r1 = radius(t, 0f);
            float f1 = (r1 - 0.8f) / Math.max(0.1f, R - 0.8f);
            RenderSystem.disableCull();
            // 낮게 퍼지는 바람 벽 (2겹)
            FxRender.elemShader(W, 2.4f, erosion, 1.1f * fade);
            funnel(c, r1, r1, 1f, 2.4f * (1f - 0.6f * f1) + 0.35f, 0.95f, 0.12f, 0.3f, 0f, 0.06f, time, 0, 0, 36, 4, 0.75f);
            if (t > 2f) {
                float r2 = radius(t, 2.5f);
                FxRender.elemShader(W, 1.8f, Math.min(1f, erosion + 0.15f), 0.85f * fade);
                funnel(c, r2, r2 * 1.03f, 1f, 1.3f * (1f - 0.5f * f1) + 0.2f, 0.8f, -0.2f, -0.25f, 0.5f, 0.05f, time, 0, 0, 32, 3, 0.6f);
            }
            // 바닥 바람 띠
            FxRender.elemShader(W, 1.4f, erosion, 0.9f * fade);
            BufferBuilder bb = FxRender.quads();
            FxRender.ring(bb, c.add(0, 0.05, 0), UP, Math.max(0.1f, r1 - 2.0f), r1 + 0.3f, 0.85f * fade, time * 0.25f, 64);
            FxRender.draw(bb);
            // 방사 속도선 (벽 앞머리에서 바깥으로)
            FxRender.spriteShader(FxRender.SPARK_WHITE);
            bb = FxRender.quads();
            float a = 0.9f * fade;
            for (int i = 0; i < ang.length; i++) {
                double cs = Math.cos(ang[i] + t * 0.01), sn = Math.sin(ang[i] + t * 0.01);
                float in = r1 * (1f - len[i]), out = r1 * 1.08f + 0.6f;
                Vec3 tail = c.add(cs * in, hgt[i], sn * in);
                Vec3 head = c.add(cs * out, hgt[i] * 1.05, sn * out);
                FxRender.streak(bb, tail, head, 0.2f, TINT[0] * a, TINT[1] * a, TINT[2] * a, a);
            }
            FxRender.draw(bb);
            frameGlow(c.add(0, 1.0, 0), R * 1.6f, 0.25f * fade * (1f - f1 * 0.6f));
        }
    }

    // =====================================================================
    // 도형
    // =====================================================================

    /**
     * 초승달 칼날 (fwd·side 평면 위, 볼록한 쪽이 fwd).
     * 바깥 앞날(반지름 R)이 가장 밝고, 안쪽으로 갈수록 어두워짐. 가운데가 두껍고 양 끝이 뾰족함.
     */
    private static void crescent(BufferBuilder bb, Vec3 c, Vec3 fwd, Vec3 side, float R, float arc, float thick, float heat, float uvOff) {
        final int seg = 20;
        Vec3 o = c.subtract(fwd.scale(R * 0.75f));
        float half = arc * 0.5f;
        Vec3 pOut0 = null, pLead0 = null, pIn0 = null;
        float h0 = 0f, u0 = 0f;
        for (int i = 0; i <= seg; i++) {
            float s = (float) i / seg;
            double th = -half + arc * s;
            Vec3 radial = fwd.scale(Math.cos(th)).add(side.scale(Math.sin(th)));
            float taper = (float) Math.pow(Math.sin(Math.PI * s), 0.75);
            float w = thick * taper;
            Vec3 pOut = o.add(radial.scale(R + w * 0.18f));
            Vec3 pLead = o.add(radial.scale(R));
            Vec3 pIn = o.add(radial.scale(R - w));
            float h = heat * (0.45f + 0.55f * taper);
            float u = s * 2f + uvOff;
            if (pOut0 != null) {
                // 바깥 얇은 띠 (앞날 → 바깥 가장자리)
                vtx(bb, pLead0, u0, 0.5f, h0);
                vtx(bb, pLead, u, 0.5f, h);
                vtx(bb, pOut, u, 0.4f, 0f);
                vtx(bb, pOut0, u0, 0.4f, 0f);
                // 안쪽 두꺼운 몸통 (앞날 → 안쪽)
                vtx(bb, pIn0, u0, 1f, 0f);
                vtx(bb, pIn, u, 1f, 0f);
                vtx(bb, pLead, u, 0.5f, h);
                vtx(bb, pLead0, u0, 0.5f, h0);
            }
            pOut0 = pOut;
            pLead0 = pLead;
            pIn0 = pIn;
            h0 = h;
            u0 = u;
        }
    }

    /**
     * 초승달의 앞날 호를 따라 카메라를 향하는 띠 (crescent 와 같은 호 계산). 양 끝이 뾰족하게 가늘어짐.
     * 칼날 면이 카메라와 나란한 각도(옆·뒤)에서도 호 모양이 선으로 남게 하는 축 띠.
     */
    private static void bladeEdge(BufferBuilder bb, Vec3 c, Vec3 fwd, Vec3 side, float R, float arc, float width, float heat, float uvOff) {
        final int seg = 16;
        Vec3 o = c.subtract(fwd.scale(R * 0.75f));
        float half = arc * 0.5f;
        Vec3 prev = null;
        float pw = 0f, ph = 0f;
        for (int i = 0; i <= seg; i++) {
            float s = (float) i / seg;
            double th = -half + arc * s;
            Vec3 p = o.add(fwd.scale(Math.cos(th) * R)).add(side.scale(Math.sin(th) * R));
            float taper = (float) Math.pow(Math.sin(Math.PI * s), 0.6);
            float w = width * taper, h = heat * (0.35f + 0.65f * taper);
            if (prev != null) ribbonCam(bb, prev, p, pw, w, ph, h, s * 2f + uvOff - 0.12f, s * 2f + uvOff);
            prev = p;
            pw = w;
            ph = h;
        }
    }

    /**
     * 꼬이며 흔들리는 속이 빈 깔때기 (SkillFx.column 의 바람판: 기울기·회전·반지름 곡선 추가).
     * @param curve 반지름 곡선 지수 (1 = 직선, &gt;1 = 위쪽에서 급히 넓어짐)
     * @param spin 틱당 회전 (라디안)
     * @param rimMix 1 = 윤곽이 밝음(투명한 바람), 0 = 정면이 밝음
     */
    /** funnel 의 몸 높이 비우기 (maskH = 바닥에서 이 높이까지, maskK = 0 이면 끔). 그리는 쪽이 켜고 끔 */
    private static float maskH, maskK;

    private static void funnel(Vec3 base, float r0, float r1, float curve, float h, float heat, float twist, float spin, float uvOff,
                               float wobble, float time, double swX, double swZ, int seg, int rows, float rimMix) {
        if (h <= 0.05f || heat <= 0.001f) return;
        BufferBuilder bb = FxRender.quads();
        for (int k = 0; k < rows; k++) {
            float y0 = h * k / rows, y1 = h * (k + 1) / rows;
            for (int i = 0; i < seg; i++) {
                float a0 = TAU * i / seg, a1 = TAU * (i + 1) / seg;
                float u0 = (float) i / seg * 2f + uvOff, u1 = (float) (i + 1) / seg * 2f + uvOff;
                fv(bb, base, a0, y0, h, r0, r1, curve, heat, twist, spin, wobble, time, swX, swZ, u0, rimMix);
                fv(bb, base, a1, y0, h, r0, r1, curve, heat, twist, spin, wobble, time, swX, swZ, u1, rimMix);
                fv(bb, base, a1, y1, h, r0, r1, curve, heat, twist, spin, wobble, time, swX, swZ, u1, rimMix);
                fv(bb, base, a0, y1, h, r0, r1, curve, heat, twist, spin, wobble, time, swX, swZ, u0, rimMix);
            }
        }
        FxRender.draw(bb);
    }

    private static void fv(BufferBuilder bb, Vec3 base, float a, float y, float h, float r0, float r1, float curve, float heat,
                           float twist, float spin, float wobble, float time, double swX, double swZ, float u, float rimMix) {
        float f = y / h;
        float shape = curve == 1f ? f : (float) Math.pow(f, curve);
        float r = (r0 + (r1 - r0) * shape) * (1f + wobble * (float) Math.sin(a * 3f + y * 1.3f + time * 0.5f));
        float ang = a + twist * y + spin * time;
        double cx = Math.cos(ang), cz = Math.sin(ang);
        double sw = Math.pow(f, 1.6);
        double x = base.x + cx * r + swX * sw, z = base.z + cz * r + swZ * sw, wy = base.y + y;
        double vx = x - FxRender.cam.x, vy = wy - FxRender.cam.y, vz = z - FxRender.cam.z;
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        float facing = len > 1.0E-4 ? (float) (Math.abs(cx * vx + cz * vz) / len) : 1f;
        float look = rimMix * (1f - facing) + (1f - rimMix) * facing;
        float bands = 0.72f + 0.28f * (float) Math.sin(a * 4f + y * 0.9f - time * 0.4f);
        float top = 1f - clamp01((f - 0.82f) / 0.18f);
        float bottom = 0.55f + 0.45f * clamp01(f / 0.06f);
        float hv = heat * (0.25f + 0.75f * look) * bands * top * bottom;
        if (maskK > 0f) {
            // 몸 높이에서 카메라를 정면으로 보는 면(실루엣 가운데)을 어둡게 → 안의 사람이 비쳐 보임, 윤곽은 그대로 밝음
            float band = 1f - clamp01((y - maskH) / 1.2f);
            hv *= 1f - maskK * band * facing * facing;
        }
        FxRender.vertex(bb, x, wy, z, u, -y * 0.16f, 1f, 1f, 1f, hv);
    }

    /** 바람 원뿔 (밑면 base, 꼭짓점 base + dir*len), 꼭짓점 쪽이 밝음 */
    private static void cone(BufferBuilder bb, Vec3 base, Vec3 dir, Vec3[] b, float rad, float len, float heat, float time) {
        if (rad <= 0.01f || heat <= 0.001f) return;
        final int seg = 16, rows = 5;
        for (int k = 0; k < rows; k++) {
            float f0 = (float) k / rows, f1 = (float) (k + 1) / rows;
            for (int i = 0; i < seg; i++) {
                float a0 = TAU * i / seg, a1 = TAU * (i + 1) / seg;
                cv(bb, base, dir, b, a0, f0, rad, len, heat, time, (float) i / seg);
                cv(bb, base, dir, b, a1, f0, rad, len, heat, time, (float) (i + 1) / seg);
                cv(bb, base, dir, b, a1, f1, rad, len, heat, time, (float) (i + 1) / seg);
                cv(bb, base, dir, b, a0, f1, rad, len, heat, time, (float) i / seg);
            }
        }
    }

    private static void cv(BufferBuilder bb, Vec3 base, Vec3 dir, Vec3[] b, float a, float f, float rad, float len, float heat, float time, float u) {
        float r = rad * (float) Math.pow(1f - f, 0.8);
        double ang = a + f * 2.0 + time * 0.5;
        double cs = Math.cos(ang), sn = Math.sin(ang);
        double x = base.x + dir.x * len * f + (b[0].x * cs + b[1].x * sn) * r;
        double y = base.y + dir.y * len * f + (b[0].y * cs + b[1].y * sn) * r;
        double z = base.z + dir.z * len * f + (b[0].z * cs + b[1].z * sn) * r;
        float hv = heat * (0.3f + 0.7f * f) * clamp01(f / 0.25f + 0.1f) * (0.75f + 0.25f * (float) Math.sin(a * 3f - time * 0.8f));
        FxRender.vertex(bb, x, y, z, u * 2f, -f * 1.2f, 1f, 1f, 1f, hv);
    }

    /** 점선 고리 (dashes 개의 조각, duty = 조각이 차지하는 비율) */
    private static void dashedRing(BufferBuilder bb, Vec3 c, Vec3 n, float rIn, float rOut, float heat, float rot, int dashes, float duty) {
        Vec3[] t = FxRender.basis(n);
        float rMid = (rIn + rOut) * 0.5f;
        final int sub = 4;
        for (int d = 0; d < dashes; d++) {
            double start = rot + TAU * d / dashes;
            double span = TAU / dashes * duty;
            for (int s = 0; s < sub; s++) {
                float s0 = (float) s / sub, s1 = (float) (s + 1) / sub;
                double a0 = start + span * s0, a1 = start + span * s1;
                float w0 = (float) Math.sin(Math.PI * s0), w1 = (float) Math.sin(Math.PI * s1);
                w0 = 0.25f + 0.75f * w0;
                w1 = 0.25f + 0.75f * w1;
                Vec3 i0 = rp(c, t, a0, rIn), i1 = rp(c, t, a1, rIn);
                Vec3 m0 = rp(c, t, a0, rMid), m1 = rp(c, t, a1, rMid);
                Vec3 o0 = rp(c, t, a0, rOut), o1 = rp(c, t, a1, rOut);
                vtx(bb, i0, s0, 0f, 0f);
                vtx(bb, i1, s1, 0f, 0f);
                vtx(bb, m1, s1, 0.5f, heat * w1);
                vtx(bb, m0, s0, 0.5f, heat * w0);
                vtx(bb, m0, s0, 0.5f, heat * w0);
                vtx(bb, m1, s1, 0.5f, heat * w1);
                vtx(bb, o1, s1, 1f, 0f);
                vtx(bb, o0, s0, 1f, 0f);
            }
        }
    }

    private static Vec3 rp(Vec3 c, Vec3[] t, double a, float r) {
        return c.add(t[0].scale(Math.cos(a) * r)).add(t[1].scale(Math.sin(a) * r));
    }

    /** 카메라를 향하는 띠 한 칸 (가운데가 밝고 양옆이 어두움) */
    private static void ribbonCam(BufferBuilder bb, Vec3 a, Vec3 b, float wa, float wb, float ha, float hb, float v0, float v1) {
        Vec3 d = b.subtract(a);
        Vec3 toCam = FxRender.cam.subtract(a);
        Vec3 s = d.cross(toCam);
        if (s.lengthSqr() < 1.0E-8) return;
        ribbonSide(bb, a, b, s.normalize(), wa, wb, ha, hb, v0, v1);
    }

    /** 정해진 옆 방향으로 펼친 띠 한 칸 */
    private static void ribbonSide(BufferBuilder bb, Vec3 a, Vec3 b, Vec3 side, float wa, float wb, float ha, float hb, float v0, float v1) {
        if (ha <= 0.001f && hb <= 0.001f) return;
        Vec3 sa = side.scale(wa * 0.5), sb = side.scale(wb * 0.5);
        vtx(bb, a.subtract(sa), 0f, v0, 0f);
        vtx(bb, a, 0.5f, v0, ha);
        vtx(bb, b, 0.5f, v1, hb);
        vtx(bb, b.subtract(sb), 0f, v1, 0f);
        vtx(bb, a, 0.5f, v0, ha);
        vtx(bb, a.add(sa), 1f, v0, 0f);
        vtx(bb, b.add(sb), 1f, v1, 0f);
        vtx(bb, b, 0.5f, v1, hb);
    }

    private static void vtx(BufferBuilder bb, Vec3 p, float u, float v, float heat) {
        FxRender.vertex(bb, p.x, p.y, p.z, u, v, 1f, 1f, 1f, heat);
    }

    // =====================================================================
    // 도우미
    // =====================================================================

    /** 비행 방향 × 위 (칼날이 눕는 옆 방향). 수직으로 날면 다른 축으로 대체 */
    private static Vec3 sideOf(Vec3 dir) {
        Vec3 s = dir.cross(UP);
        if (s.lengthSqr() < 1.0E-4) s = dir.cross(new Vec3(1, 0, 0));
        if (s.lengthSqr() < 1.0E-8) return new Vec3(1, 0, 0);
        return s.normalize();
    }

    /** v 를 축 fwd 둘레로 돌림 (v ⟂ fwd 이고 nrm = fwd × v 일 때) */
    private static Vec3 rotate(Vec3 v, Vec3 nrm, float ang) {
        return v.scale(Math.cos(ang)).add(nrm.scale(Math.sin(ang)));
    }

    private static Vec3 safeDir(Vec3 v, Vec3 fallback) {
        return v.lengthSqr() < 1.0E-6 ? fallback : v.normalize();
    }

    private static Vec3 flat(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0, v.z);
        if (f.lengthSqr() < 1.0E-6) return v.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : v.normalize();
        return f.normalize();
    }

    private static int idSeq;

    /** p 아래 바닥 (6칸까지, 없으면 p) — 땅 조각 연출의 기준점 */
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
        Player me = Minecraft.getInstance().player;
        if (me == null) return;
        double d = me.position().distanceTo(p);
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

    private static void shakeAt(Vec3 p, float amp, float range) {
        Player me = Minecraft.getInstance().player;
        if (me == null) return;
        double d = me.position().distanceTo(p);
        if (d < range) CameraShake.add((float) (amp * (1 - d / range)));
    }

    /** 이번 프레임만 그리는 빛 (FxManager 의 풀에서 꺼내 씀 — 프레임마다 Sprite 를 만들지 않음) */
    private static void frameGlow(Vec3 at, float size, float alpha) {
        if (alpha <= 0.003f || size <= 0.01f) return;
        FxManager.frameGlow(at, size, TINT[0], TINT[1], TINT[2], alpha);
    }

    /** 바람결 (wisp 그림 불꽃) */
    private static void wisp(Vec3 at, Vec3 v, float s0, float s1, int life, float a) {
        Sprite f = new Sprite(Sprite.Kind.FLAME, at, v, s0, s1, life).color(0.78f, 1f, 0.9f, a).elem(W).physics(0f, 0.92f);
        f.frameOffset = RNG.nextInt(64);
        f.rot = RNG.nextFloat() * TAU;
        spawn(f);
    }

    /** 나뭇잎 */
    private static void leaf(Vec3 at, Vec3 v, int life, float size) {
        Sprite s = new Sprite(Sprite.Kind.EMBER, at, v, size, size * 0.6f, life)
                .color(rand(0.55f, 0.75f), 1f, rand(0.5f, 0.7f), 1f).elem(W).physics(0.003f, 0.94f);
        s.sway = 0.01f;
        s.rot = RNG.nextFloat() * TAU;
        s.frameOffset = RNG.nextInt(100);
        spawn(s);
    }

    /** 바람 속도선 */
    private static void streakSpark(Vec3 at, Vec3 v, float w, int life) {
        spawn(new Sprite(Sprite.Kind.SPARK, at, v, w, w, life).color(0.75f, 1f, 0.9f, 1f).physics(0f, 0.88f));
    }

    /** 흙먼지 (베이지 안개) */
    private static void dust(Vec3 at, Vec3 v, float s0, float s1, int life, float a) {
        Sprite s = new Sprite(Sprite.Kind.SMOKE, at, v, s0, s1, life).color(0.8f, 0.74f, 0.62f, a).elem(W).physics(-0.001f, 0.93f);
        s.rot = RNG.nextFloat() * TAU;
        spawn(s);
    }
}
