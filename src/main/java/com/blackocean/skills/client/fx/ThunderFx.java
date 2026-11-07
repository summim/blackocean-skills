package com.blackocean.skills.client.fx;

import com.blackocean.skills.Skill;
import com.blackocean.skills.client.ClientSkillStats;
import com.blackocean.skills.client.SkillsClientConfig;
import com.blackocean.skills.network.SkillEventPayload;
import com.blackocean.skills.network.SkillEvents;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.blackocean.skills.client.fx.FxManager.RNG;
import static com.blackocean.skills.client.fx.FxManager.clamp01;
import static com.blackocean.skills.client.fx.FxManager.count;
import static com.blackocean.skills.client.fx.FxManager.easeOutCubic;
import static com.blackocean.skills.client.fx.FxManager.rand;
import static com.blackocean.skills.client.fx.FxManager.randomDir;
import static com.blackocean.skills.client.fx.FxManager.spawn;
import static com.blackocean.skills.client.fx.ThunderParts.CRACKS;
import static com.blackocean.skills.client.fx.ThunderParts.DUST;
import static com.blackocean.skills.client.fx.ThunderParts.EL;
import static com.blackocean.skills.client.fx.ThunderParts.GOLD;
import static com.blackocean.skills.client.fx.ThunderParts.PALE;
import static com.blackocean.skills.client.fx.ThunderParts.RUNE;
import static com.blackocean.skills.client.fx.ThunderParts.TAU;
import static com.blackocean.skills.client.fx.ThunderParts.UP;
import static com.blackocean.skills.client.fx.ThunderParts.VIOLET;
import static com.blackocean.skills.client.fx.ThunderParts.arc;
import static com.blackocean.skills.client.fx.ThunderParts.bodyArcs;
import static com.blackocean.skills.client.fx.ThunderParts.distToPlayer;
import static com.blackocean.skills.client.fx.ThunderParts.frameGlow;
import static com.blackocean.skills.client.fx.ThunderParts.polar;
import static com.blackocean.skills.client.fx.ThunderParts.smooth;
import static com.blackocean.skills.client.fx.ThunderParts.sp;

/**
 * 천둥 능력 연출 (번개와 다름: 번개 = 빠르고 가는 푸른 줄기, 천둥 = 무겁고 굵은 금빛 + 보랏빛, 거대한 망치, 땅울림).
 *
 * <h3>천둥의 공통 언어 (2.3 설계)</h3>
 * <ul>
 *   <li>리듬: <b>느리고 무거운 예비 → 멈칫(떨림) → 한순간의 빠른 타격 → 거대한 충격파 벽</b>. 젖히기는 부드럽게 느려지며 끝까지 가고,
 *       마지막에 잠깐 떨며 버틴 뒤, 휘두름은 세제곱으로 가속해 1~2틱 안에 끝남. 맞는 틱 = 서버 피해 틱.</li>
 *   <li>모양: 기하학적 3D 망치 메시 (상자 11개, 뒷면 버리기 + 빛 테두리 껍질 — 앞·옆·뒤 어디서 봐도 두께가 있음),
 *       룬 원, 바닥에서 솟는 원통/부채꼴 충격파 벽 (곡면이라 옆에서도 벽으로 읽힘). 번개 줄기는 금빛 굵은 띠.</li>
 *   <li>층: 망치·충격파 벽 (가장 밝음: 흰 심 + 금빛) → 보랏빛 번개·음속 고리 (보조) → 흰 섬광·돔 (≤ 4틱 정점) →
 *       흙먼지·식어 가는 금 간 땅·기어 다니는 금빛 불씨 (여운: 옅고 테두리 없음). 어두운 흙먼지가 금빛과 대비.</li>
 *   <li>판정: 피해 범위는 전부 SkillFx.dangerZone/dangerCone (정확한 판정 크기, 적·아군 모양 구분). 터지는 순간의 범위는
 *       ThunderParts.addEdge (가득 찬 dangerZone 4틱). 그 뒤 금·불씨는 테두리 없음.</li>
 *   <li>가벼움 vs 무거움: 포효·망치는 중간(짧은 예비, 흔들림 중간), 뇌명 일격·도약은 무거움(분명한 젖힘/도약, 큰 벽),
 *       뇌신 강림은 가장 무거움(하늘이 어두워지고 긴 예비 → 화면 섬광 → 여진 3번).</li>
 * </ul>
 *
 * <h3>스킬별 설계</h3>
 * <pre>
 *  1 뇌신의 망치 (중간)
 *    정지 화면 : 공중에서 앞으로 굴러 날아가는 금빛 망치 + 머리가 그리는 회전 고리 + 보랏빛 번개 꼬리
 *    주 형태   : 망치 메시 (본체 + 빛 테두리 + 잔상 3개). 준비: 노이즈로 맺히며 천천히 뒤로 젖혀 떨다가 마지막 14% 에 휙 던짐
 *    보조 층   : 음속 고리 (진행 방향에 수직 → 옆에서 타원), 몸통을 감는 번개, 불씨·화염 꼬리
 *    적중 모양 : 흰 섬광 → 금빛 돔 → 바닥 원통 충격파 벽 + 위로 치솟는 번개 + 바닥을 기는 번개 + 판정 R 4틱
 *    사라짐    : 금 간 땅이 지직거리며 식고, 흙먼지와 금빛 불씨가 떠오르다 꺼짐
 *  2 천둥 도약 (무거움)
 *    정지 화면 : 포물선 위의 금빛 잔상 띠 + 착지점의 룬 원과 차오르는 위험 범위
 *    주 형태   : 몸을 따라오는 굵은 금빛 띠, 착지 때 거대한 원통 충격파 벽
 *    보조 층   : 박차는 반동(뒤로 튀는 흙), 몸을 기는 전기, 착지점 룬 위 불씨
 *    판정      : 뛰기 전부터 착지 범위 dangerZone, 비행 동안 착지 틱에 맞춰 차오름 → 착지 틱 가득 4틱
 *    사라짐    : 잔상 띠가 8틱에 걸쳐 짧아지고, 패인 자국·금 간 땅이 식음
 *  3 뇌명 일격 (무거움)
 *    정지 화면 : 머리 위로 높이 젖힌 거대한 망치 + 바닥의 부채꼴 위험 범위
 *    주 형태   : 망치가 느리게 젖혀져 떨며 버티다가 2틱 만에 세제곱 가속으로 내려찍음 (서버 피해 틱과 같음)
 *    보조 층   : 부채꼴로 달려 나가는 높은 충격파 벽 2겹, 갈라지는 땅(빛나는 금), 바닥을 기는 금빛 번개, 음속 고리
 *    판정      : 준비 = dangerCone 이 차오름 → 내려찍는 2틱 = 거의 가득 → 맞는 틱 가득 3틱 → 끝
 *    사라짐    : 금이 식으며 잔전기가 가끔 튀고 흙먼지가 가라앉음 (테두리 없음)
 *  4 천둥 포효 (중간)
 *    정지 화면 : 가슴의 금빛 에너지 구 + 사방에서 빨려 드는 번개 줄기 + 좁혀 드는 음파 고리
 *    주 형태   : 에너지 구 (끌어당기는 동안 커짐) → 터지는 금빛 돔
 *    보조 층   : 빨려 드는 불꽃·번개, 몸을 기는 전기, 흙먼지
 *    판정      : 끌어당김 범위 R (dangerZone, 채움 없음 = 끌려옴) + 터지는 범위 0.6R (dangerZone, 터지는 틱에 가득) → 가득 4틱
 *    사라짐    : 구 모양으로 터진 번개가 깜빡이며 꺼지고 패인 자국이 식음
 *  5 뇌신 강림 (궁극기, 가장 무거움)
 *    정지 화면 : 어두운 먹구름 아래, 하늘 높이 솟은 시전자 머리 위의 거대한 빛의 망치 + 목표 바닥의 거대한 룬 원
 *    주 형태   : 거대한 망치 (솟는 동안 노이즈로 맺힘 → 6틱 동안 가속하며 내려꽂힘, 잔상 2개)
 *    보조 층   : 금빛으로 번쩍이는 먹구름, 구름 → 망치 번개, 시전자를 감싼 기둥, 룬 원 둘레 빛 띠
 *    판정      : 내려꽂는 순간까지 차오르는 dangerZone R → 가득 4틱 → 여진 3번: 다음 여진 범위(×1.3, ×1.6, ×1.9)가
 *                10틱마다 차오르며 매번 가득 4틱
 *    사라짐    : 빛기둥이 꺼지고, 넓은 금 간 땅 위로 불씨·잔전기·흙먼지가 70틱에 걸쳐 잦아듦
 *  기절 (STUN)
 *    정지 화면 : 머리 위를 도는 금빛 별 4개 + 별 사이를 잇는 작은 번개 + 얇은 금빛 고리
 *    주 형태   : 교차한 빛줄기 별 (머리 위에만 → 몸·얼굴을 가리지 않음)
 *    보조 층   : 처음 6틱 몸을 기는 전기, 떨어지는 작은 불꽃
 *    사라짐    : 6틱에 걸쳐 별이 흐려짐. 기절한 본인 1인칭은 30% 밝기 (시야를 덮지 않게)
 * </pre>
 *
 * <h3>예전 요약</h3>
 * <pre>
 *  1 뇌신의 망치 : 손에 금빛 망치가 노이즈로 맺힘 → 회전하며 날아가는 망치 메시(본체+빛 테두리+잔상) + 보랏빛 번개 꼬리
 *                 + 음속 고리 → 천둥 폭발 (흰 섬광, 돔, 충격파 벽, 위로 치솟는 번개, 파편·패인 자국, 화면 흔들림)
 *  2 천둥 도약   : 발밑 금빛 고리 → 도약 폭발 + 흙먼지 + 몸을 따라오는 금빛 잔상 띠, 착지점에 좁혀 드는 카운트다운 룬 원
 *                 → 내려찍기 (충격파 벽, 금 간 땅, 파편, 강한 흔들림)
 *  3 뇌명 일격   : 머리 위로 든 망치 → 땅을 내려찍는 망치 + 부채꼴로 달려 나가는 충격파·땅 갈라짐·금빛 번개 + 음속 고리
 *  4 천둥 포효   : 사방에서 빨려 드는 번개 줄기·좁혀 드는 음파 고리·가슴의 에너지 구 → 금빛 천둥 폭발
 *  5 뇌신 강림   : 금빛 기운에 싸여 하늘로 솟구침, 하늘이 어두워지고 금빛으로 번쩍이는 먹구름, 구름에서 번개를 받아
 *                 머리 위에 맺히는 거대한 빛의 망치, 목표 바닥에 커지는 룬 원 → 내려꽂힘 → 눈부신 섬광·거대한 돔·빛기둥·
 *                 하늘에서 내리꽂는 번개·대규모 파편과 빛나는 금 간 땅 → 여진 3번 (점점 넓어지는 충격파)
 *  기절(STUN)   : 머리 위를 도는 금빛 별 4개 + 별 사이 작은 번개 + 고리, 처음엔 몸을 기는 전기
 * </pre>
 * 모든 목록은 상한이 있고, 입자 수는 FxManager.count (설정·거리 LOD) 를 따른다.
 */
final class ThunderFx {
    private static final int MAX_STUNS = 48;
    private static final List<LeapFx> LEAPS = new ArrayList<>();
    private static final List<ClapFx> CLAPS = new ArrayList<>();
    private static final List<RoarFx> ROARS = new ArrayList<>();
    private static final List<JudgmentFx> JUDGMENTS = new ArrayList<>();
    private static final List<StunFx> STUNS = new ArrayList<>();

    private ThunderFx() {}

    // =====================================================================
    // 계약 함수
    // =====================================================================

    static void onEvent(SkillEventPayload e, Skill skill, @Nullable Entity caster) {
        switch (e.kind()) {
            case SkillEvents.IMPACT -> hammerImpact(e, caster);
            case SkillEvents.LEAP -> {
                if (e.size2() < 0.5f) {
                    if (LEAPS.size() >= 12) LEAPS.remove(0);
                    LEAPS.add(new LeapFx(e.pos(), e.vec(), Math.max(1, e.duration()), Math.max(0.5f, e.size()), e.casterId()));
                } else {
                    for (LeapFx l : LEAPS) if (l.casterId == e.casterId() && e.casterId() >= 0) l.landed = true;
                    leapSlam(e.pos(), Math.max(0.5f, e.size()), caster);
                }
            }
            case SkillEvents.CLAP -> {
                Vec3 dir = new Vec3(e.ax(), 0, e.az());
                dir = dir.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : dir.normalize();
                if (CLAPS.size() >= 12) CLAPS.remove(0);
                CLAPS.add(new ClapFx(e.pos(), dir, Math.max(2f, e.size()), Math.max(5f, Math.min(90f, e.size2())), caster));
            }
            case SkillEvents.ROAR -> {
                if (e.size2() < 0.5f) {
                    if (ROARS.size() >= 12) ROARS.remove(0);
                    ROARS.add(new RoarFx(e.pos(), Math.max(1f, e.size()), Math.max(1, e.duration()), e.casterId()));
                } else {
                    for (RoarFx r : ROARS) if (r.casterId == e.casterId()) r.done = true;
                    roarBurst(e.pos(), Math.max(0.8f, e.size()), caster);
                }
            }
            case SkillEvents.JUDGMENT -> judgment(e, caster);
            default -> { }
        }
    }

    /** STUN (skill 없음): target = 엔티티, size = 너비, size2 = 키, duration = 틱 */
    static void onStatus(SkillEventPayload e) {
        if (e.kind() != SkillEvents.STUN) return;
        int dur = Math.max(1, e.duration());
        for (StunFx s : STUNS) {
            if (s.id == e.target() && e.target() >= 0) {
                s.life = Math.max(s.life - s.age, dur) + s.age;
                s.crackle = Math.max(s.crackle, s.age + 6);
                return;
            }
        }
        if (STUNS.size() >= MAX_STUNS) STUNS.remove(0);
        STUNS.add(new StunFx(e.target(), e.pos(), Math.max(0.3f, e.size()), Math.max(0.4f, e.size2()), dur));
    }

    static void tick() {
        ThunderParts.tick();
        LEAPS.removeIf(LeapFx::tick);
        CLAPS.removeIf(ClapFx::tick);
        ROARS.removeIf(RoarFx::tick);
        JUDGMENTS.removeIf(JudgmentFx::tick);
        STUNS.removeIf(StunFx::tick);
    }

    static void renderAlpha(float pt, float time) {
        if (JUDGMENTS.isEmpty()) return;
        FxRender.spriteShader(FxRender.MIST);
        RenderSystem.disableCull();
        BufferBuilder bb = FxRender.quads();
        for (JudgmentFx j : JUDGMENTS) j.renderCloud(bb, pt, time);
        FxRender.draw(bb);
    }

    static void renderAdditive(float pt, float time) {
        if (isEmpty()) return;
        FxRender.additive();
        RenderSystem.disableCull();
        for (JudgmentFx j : JUDGMENTS) j.renderGround(pt, time);
        for (LeapFx l : LEAPS) l.renderGround(pt, time);
        ThunderParts.render(pt, time);
        // 갈라진 땅 (납작한 빛 띠)
        if (!CLAPS.isEmpty()) {
            FxRender.spriteShader(FxRender.BEAM);
            BufferBuilder bb = FxRender.quads();
            for (ClapFx c : CLAPS) c.emitCracks(bb, pt);
            FxRender.draw(bb);
        }
        for (ClapFx c : CLAPS) c.render(pt, time);
        for (LeapFx l : LEAPS) l.renderTrail(pt, time);
        for (RoarFx r : ROARS) r.render(pt, time);
        for (JudgmentFx j : JUDGMENTS) j.render(pt, time);
        if (!STUNS.isEmpty()) renderStuns(pt, time);
        ThunderParts.frameGlows(pt);
        for (JudgmentFx j : JUDGMENTS) j.frameGlows(pt);
        RenderSystem.disableCull();
        FxRender.additive();
    }

    static boolean isEmpty() {
        return ThunderParts.isEmpty() && LEAPS.isEmpty() && CLAPS.isEmpty() && ROARS.isEmpty() && JUDGMENTS.isEmpty() && STUNS.isEmpty();
    }

    static void clear() {
        ThunderParts.clear();
        LEAPS.clear();
        CLAPS.clear();
        ROARS.clear();
        JUDGMENTS.clear();
        STUNS.clear();
    }

    // =====================================================================
    // 준비 동작
    // =====================================================================

    static void tickCharge(FxManager.ChargeFx c, float k) {
        Entity ce = c.caster;
        if (ce == null || ce.isRemoved()) return;
        switch (c.skill) {
            case THUNDER_HAMMER -> {
                IceThunderAim.update(c, 0.3);
                hammerPose(c, 1f, k, POSE);
                Vec3 head = POSE.head();
                FxManager.lod = FxManager.lodAt(head);
                // 망치 머리로 빨려 드는 금빛 불씨 + 지직거리는 전기 (젖힐수록 세짐)
                for (int i = 0; i < count(3); i++) {
                    Vec3 off = randomDir().scale(rand(1.2f, 1.9f));
                    spawn(sp(Sprite.Kind.EMBER, head.add(off), off.scale(-0.2), 0.16f, 0.05f, 5).color(1f, 0.8f, 0.35f, 1f));
                }
                if (RNG.nextFloat() < 0.5f + 0.5f * k) {
                    arc(head.add(randomDir().scale(0.2)), head.add(randomDir().scale(rand(0.5f, 0.9f))), 0.26f + 0.12f * k, 2, 0, 3, 1, 0.32f, false, 0.35f);
                }
                if (IceThunderAim.releasing(c)) hammerRelease(IceThunderAim.hand(c, 1f, 0.3), IceThunderAim.shotDir(c), ce);
            }
            case THUNDERCLAP -> {
                Vec3 f = c.flatDir();
                Vec3 grip = clapGrip(ce.position(), f, ce);
                Vec3 head = grip.add(clapY(f, clapChargePhi(k)).scale((ThunderHammer.HEAD_Y - ThunderHammer.GRIP_Y) * clapScale(k)));
                FxManager.lod = FxManager.lodAt(head);
                for (int i = 0; i < count(2); i++) {
                    Vec3 off = randomDir().scale(rand(1.3f, 2.0f));
                    spawn(sp(Sprite.Kind.EMBER, head.add(off), off.scale(-0.2), 0.16f, 0.05f, 5).color(1f, 0.8f, 0.35f, 1f));
                }
                if (RNG.nextFloat() < 0.6f) {
                    arc(head.add(randomDir().scale(0.2)), head.add(randomDir().scale(rand(0.6f, 1.1f))), 0.3f, 2, 0, 3, 1, 0.32f, false, 0.4f);
                }
                if (c.age % 3 == 0) bodyArcs(ce, 1, 0.3f);
            }
            case THUNDER_LEAP -> {
                Vec3 feet = ce.position();
                FxManager.lod = FxManager.lodAt(feet);
                bodyArcs(ce, 1 + (int) (2 * k), 0.3f);
                for (int i = 0; i < count(2 + (int) (3 * k)); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = rand(0.3f, 1.4f);
                    spawn(sp(Sprite.Kind.SPARK, polar(feet, ang, rr, 0.1), new Vec3(0, rand(0.1f, 0.3f), 0), 0.07f, 0.07f, 6 + RNG.nextInt(5))
                            .color(1f, 0.8f, 0.35f, 1f).physics(-0.004f, 0.94f));
                }
                if (c.age % 3 == 0) dust(feet, 1.2f, 2, 0.6f);
                tickRuneSparks(c.target, c.size, k * 0.6f);
            }
            case THUNDER_ROAR -> {
                Vec3 chest = ce.position().add(0, 1.1, 0);
                FxManager.lod = FxManager.lodAt(chest);
                float R = Math.max(2f, ClientSkillStats.radius(c.skill));
                bodyArcs(ce, 2 + (int) (3 * k), 0.4f);
                for (int i = 0; i < count(3 + (int) (4 * k)); i++) {
                    Vec3 d = randomDir();
                    Vec3 at = chest.add(d.scale(R * rand(0.5f, 0.9f)));
                    spawn(sp(Sprite.Kind.SPARK, at, chest.subtract(at).scale(0.16), 0.08f, 0.08f, 6)
                            .color(1f, 0.82f, 0.4f, 1f).physics(0f, 0.95f));
                }
            }
            case THUNDER_JUDGMENT -> {
                FxManager.lod = FxManager.lodAt(ce.position());
                bodyArcs(ce, 2 + (int) (3 * k), 0.35f);
                if (c.age % 4 == 1) {
                    Vec3 head = ce.position().add(0, ce.getBbHeight() + 0.3, 0);
                    arc(head, head.add(rand(-2.5f, 2.5f), 22 + rand(0f, 5f), rand(-2.5f, 2.5f)), 1.0f + 0.6f * k, 5, 0, 5, 2, 0.2f, false, 0.35f);
                    spawn(sp(Sprite.Kind.GLOW, head, Vec3.ZERO, 2.2f, 3.6f, 4).color(1f, 0.85f, 0.45f, 1f));
                }
                tickRuneSparks(c.target, c.size, k);
            }
            default -> { }
        }
    }

    static void renderCharge(FxManager.ChargeFx c, float pt, float k, float time) {
        Entity ce = c.caster;
        if (ce == null || ce.isRemoved()) return;
        switch (c.skill) {
            case THUNDER_HAMMER -> {
                // 서버가 망치를 만드는 자리 S 뒤에서 젖혔다가, 마지막 30% 에 앞으로 휘둘러 S 에서 날아가는 망치와 같은 자세가 됨
                hammerPose(c, pt, k, POSE);
                float ero = 0.88f * (float) Math.pow(1f - Math.min(1f, k * 1.3f), 1.4);
                ThunderHammer.draw(POSE.mass, POSE.Y, POSE.X, POSE.s, ThunderHammer.MASS_Y, ero, 0.75f + 0.55f * k, time * 0.02f, true);
                FxRender.additive();
                Vec3 head = POSE.head();
                float fl = 0.85f + 0.15f * (float) Math.sin(time * 3.3f);
                frameGlow(head, (1.4f + 2.6f * k) * fl * POSE.s / 0.6f, GOLD, 0.35f + 0.35f * k);
                frameGlow(head, 0.5f + 0.7f * k, PALE, 0.6f * k);
                frameGlow(POSE.mass, 0.8f + 0.6f * k, VIOLET, 0.25f + 0.2f * k);
            }
            case THUNDERCLAP -> {
                // 머리 위로 젖힌 망치 (뇌명 일격의 휘두름이 이 자세에서 그대로 이어짐)
                Vec3 f = c.flatDir();
                Vec3 grip = clapGrip(ce.getPosition(pt), f, ce);
                float s = clapScale(k);
                // 다 젖힌 뒤 무게를 버티며 떨림 (곧 내려찍음)
                double phi = clapChargePhi(k) + 0.04 * Math.sin(time * 2.7) * smooth((k - 0.7f) / 0.3f);
                Vec3 Y = clapY(f, phi), X = clapX(f, phi);
                float ero = 0.88f * (float) Math.pow(1f - Math.min(1f, k * 1.3f), 1.4);
                ThunderHammer.draw(grip, Y, X, s, ThunderHammer.GRIP_Y, ero, 0.75f + 0.55f * k, time * 0.02f, true);
                FxRender.additive();
                Vec3 head = grip.add(Y.scale((ThunderHammer.HEAD_Y - ThunderHammer.GRIP_Y) * s));
                float fl = 0.85f + 0.15f * (float) Math.sin(time * 3.3f);
                frameGlow(head, (1.4f + 2.6f * k) * fl * s / 0.6f, GOLD, 0.35f + 0.35f * k);
                frameGlow(head, 0.5f + 0.7f * k, PALE, 0.6f * k);
                // 바닥에 부채꼴 판정 범위 (실제 판정 거리·각도). 준비 끝 = 85% (내려찍는 2틱 동안 마저 차서 맞는 틱에 가득)
                float range = Math.max(1f, ClientSkillStats.range(Skill.THUNDERCLAP));
                float halfDeg = Math.max(5f, Math.min(90f, ClientSkillStats.radius(Skill.THUNDERCLAP)));
                float half = (float) Math.toRadians(halfDeg);
                float base = (float) Math.atan2(f.z, f.x);
                Vec3 o = ce.getPosition(pt);
                SkillFx.dangerCone(o, f, range, halfDeg, CLAP_CHARGE_FILL * k, EL, ce, 0.5f + 0.5f * smooth(k / 0.3f), time);
                FxRender.additive();
                RenderSystem.disableCull();
                // 장식: 부채꼴 안을 차오르는 금빛 띠 (테두리 아님)
                FxRender.elemShader(EL, 2.4f, 0.3f, 0.25f + 0.4f * k);
                BufferBuilder bb = FxRender.quads();
                ThunderParts.band(bb, o.add(0, 0.07, 0), 0.6f, 0.6f + (range - 0.6f) * 0.9f * easeOutCubic(k), base - half * 0.95f, base + half * 0.95f, 0.35f, ThunderParts.segs(range, 2 * half), time * 0.02f);
                FxRender.draw(bb);
            }
            case THUNDER_LEAP -> {
                Vec3 feet = ce.getPosition(pt).add(0, 0.05, 0);
                FxRender.elemShader(EL, 2.4f, 0.3f - 0.25f * k, 0.8f + 0.6f * k);
                BufferBuilder bb = FxRender.quads();
                FxRender.ring(bb, feet, UP, 0.4f + 0.9f * k, 0.7f + 1.1f * k, 0.6f + 0.4f * k, time * 0.5f, 40);
                FxRender.ring(bb, feet.add(0, 0.01, 0), UP, 1.6f - 0.8f * k, 1.75f - 0.8f * k, 0.5f * k, -time * 0.7f, 40);
                FxRender.draw(bb);
                frameGlow(feet.add(0, 0.6, 0), 2f + 2.5f * k, GOLD, 0.3f + 0.3f * k);
                // 착지 자리: 실제 착지 판정 범위 (뛰기 전부터 다른 사람에게 보임, 비행 중에 차오름)
                SkillFx.dangerZone(c.target, c.size, 0f, EL, ce, 0.6f + 0.4f * k, time);
                FxRender.additive();
                RenderSystem.disableCull();
                renderRune(c.target, c.size * 0.8f, 0.3f + 0.35f * k, time, 1f);
            }
            case THUNDER_ROAR -> {
                Vec3 feet = ce.getPosition(pt);
                Vec3 chest = feet.add(0, 1.1, 0);
                float fl = 0.85f + 0.15f * (float) Math.sin(time * 4.1f);
                frameGlow(chest, (1.5f + 4f * k * k) * fl, GOLD, 0.35f + 0.45f * k);
                frameGlow(chest, 0.6f + 1.2f * k, PALE, 0.5f + 0.4f * k);
                // 끌려 오는 범위 R (바깥 경계) + 터지는 범위 0.6R (안쪽 고리, 서버 Roar 와 같은 비율)
                float R = Math.max(2f, ClientSkillStats.radius(c.skill));
                // 끌어당김 범위 (채움 없음) + 터지는 범위 (포효 뒤 12틱에 가득 → 준비 동안엔 조금만)
                SkillFx.dangerZone(feet, R, 0f, EL, ce, 0.3f + 0.4f * k, time);
                SkillFx.dangerZone(feet, R * ROAR_BURST, 0.15f * k, EL, ce, 0.4f + 0.5f * k, time);
                FxRender.additive();
                RenderSystem.disableCull();
            }
            case THUNDER_JUDGMENT -> {
                Vec3 p = ce.getPosition(pt);
                frameGlow(p.add(0, 1.0, 0), 2.5f + 3f * k, GOLD, 0.3f + 0.4f * k);
                frameGlow(p.add(0, ce.getBbHeight() + 0.4, 0), 0.8f + 1.6f * k, PALE, 0.6f + 0.3f * k);
                // 내려찍힐 자리: 판정 범위(= 실제 반지름)는 처음부터 (채움은 솟구친 뒤 시작), 안쪽 룬은 점점 커짐
                SkillFx.dangerZone(c.target, c.size, 0f, EL, ce, 0.5f + 0.4f * k, time);
                FxRender.additive();
                RenderSystem.disableCull();
                renderRune(c.target, c.size * (0.2f + 0.2f * k), 0.25f + 0.4f * k, time, 1f);
            }
            default -> { }
        }
    }

    /** 서버 Roar: 끌어당긴 뒤 반지름 × 0.6 안을 터뜨림 */
    static final float ROAR_BURST = 0.6f;

    // ---- 망치 자세 (뇌신의 망치 준비 → 날아가는 망치) ----

    private static final HammerPose POSE = new HammerPose();

    private static final class HammerPose {
        Vec3 mass = Vec3.ZERO, Y = UP, X = new Vec3(1, 0, 0);
        float s = 0.575f;

        Vec3 head() {
            return mass.add(Y.scale((ThunderHammer.HEAD_Y - ThunderHammer.MASS_Y) * s));
        }
    }

    /** 날아가는 망치의 크기 (ProjectileFx 첫 틱과 같게) */
    private static final float FLY_SCALE = 1.15f * 0.5f;

    /**
     * 망치 준비 자세. 투사체와 같은 틀(fwd = 서버 발사 방향, up = right × fwd)에서
     * 0~70%: 머리를 뒤로 젖힘 (th 0 → -1.15), 70~100%: 가속하며 앞으로 휘둘러 th = 0, 무게 중심 = 서버 생성 자리 S.
     * 날아가는 망치는 th = 0 에서 앞으로 돌기 시작하므로 끊김 없이 이어진다.
     */
    private static void hammerPose(FxManager.ChargeFx c, float pt, float k, HammerPose out) {
        Vec3 S = IceThunderAim.hand(c, pt, 0.3), fwd = IceThunderAim.shotDir(c);
        Vec3 right = fwd.cross(UP);
        right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 up = right.cross(fwd).normalize();
        // 천천히 젖힘(0~75%) → 무게를 버티며 떨림 → 마지막 14% 에 세제곱 가속으로 휙 (무거운 예비, 갑작스런 타격)
        float cock = smooth(k / 0.75f);
        float sw = clamp01((k - 0.86f) / 0.14f);
        sw = sw * sw * sw;
        float back = cock * (1f - sw);
        float hold = smooth((k - 0.55f) / 0.2f) * (1f - sw);
        double th = -1.2 * back + 0.05 * Math.sin((c.age + pt) * 2.9) * hold;
        out.Y = up.scale(Math.cos(th)).add(fwd.scale(Math.sin(th)));
        out.X = fwd.scale(Math.cos(th)).subtract(up.scale(Math.sin(th)));
        out.mass = S.subtract(fwd.scale(0.75 * back)).add(up.scale(0.2 * back));
        out.s = FLY_SCALE * (0.6f + 0.4f * easeOutCubic(Math.min(1f, k * 1.4f)));
    }

    /** 망치가 손에서 나가는 순간: S 에서 조준 방향으로 음속 고리 2개 + 앞으로 튀는 불꽃 + 번개 */
    private static void hammerRelease(Vec3 S, Vec3 d, Entity caster) {
        FxManager.lod = FxManager.lodAt(S);
        spawn(sp(Sprite.Kind.GLOW, S, Vec3.ZERO, 2.2f, 3.6f, 4).color(1f, 0.95f, 0.8f, 1f));
        FxManager.addRing(S.add(d.scale(0.6)), d, 0.3f, 2.4f, 6, 0, 1f, EL);
        FxManager.addRing(S.add(d.scale(1.6)), d, 0.2f, 1.5f, 5, 1, 0.7f, EL);
        arc(S, S.add(d.scale(2.2)).add(randomDir().scale(0.3)), 0.5f, 2, 0, 3, 1, 0.25f, false, 0.4f);
        for (int i = 0; i < count(12); i++) {
            Vec3 v = d.scale(rand(0.4f, 0.9f)).add(randomDir().scale(0.25));
            spawn(sp(Sprite.Kind.SPARK, S, v, 0.1f, 0.1f, 5 + RNG.nextInt(4)).color(1f, 0.82f, 0.4f, 1f).physics(0.03f, 0.88f));
        }
        if (IceThunderAim.firstPersonSelf(caster)) {
            CameraShake.add(1.0f);
            CameraShake.kick(-1.0f, 0f);
        }
    }

    // ---- 뇌명 일격 자세 (준비의 젖힘 → ClapFx 의 내려찍기가 같은 손잡이·같은 평면에서 이어짐) ----

    /** 젖힌 끝 각도 / 내려찍은 끝 각도 (Y = UP cosφ + f sinφ) */
    static final double CLAP_PHI0 = -1.0, CLAP_PHI1 = Math.PI - 0.56;
    static final float CLAP_SCALE = 0.95f;

    /** 준비가 끝날 때 부채꼴이 차오른 정도 (나머지는 내려찍는 HIT 틱 동안) */
    static final float CLAP_CHARGE_FILL = 0.85f;

    /** 느리게 들어 올려 80% 에 다 젖힘 → 그 뒤는 버팀 (떨림은 그리기에서) */
    private static double clapChargePhi(float k) {
        return -0.35 + (CLAP_PHI0 + 0.35) * smooth(k / 0.8f);
    }

    private static float clapScale(float k) {
        return CLAP_SCALE * (0.6f + 0.4f * easeOutCubic(Math.min(1f, k * 1.4f)));
    }

    static Vec3 clapY(Vec3 f, double phi) {
        return UP.scale(Math.cos(phi)).add(f.scale(Math.sin(phi)));
    }

    static Vec3 clapX(Vec3 f, double phi) {
        return f.scale(Math.cos(phi)).subtract(UP.scale(Math.sin(phi)));
    }

    /** 손잡이 자리 (발 기준). 1인칭 본인은 오른쪽으로 비켜서 손잡이가 눈앞을 지나지 않게 */
    static Vec3 clapGrip(Vec3 feet, Vec3 f, @Nullable Entity caster) {
        double side = caster != null && IceThunderAim.firstPersonSelf(caster) ? 0.75 : 0.25;
        return feet.add(0, 1.35, 0).add(f.scale(0.4)).add(IceThunderAim.flatRight(f).scale(side));
    }

    // =====================================================================
    // 1. 뇌신의 망치 (투사체)
    // =====================================================================

    static void trackProjectile(FxManager.ProjectileFx p, Vec3 pos) {
        Vec3 back = p.dir.scale(-1);
        // 몸통을 감는 금빛·보랏빛 전기
        for (int i = 0; i < Math.max(1, count(2)); i++) {
            Vec3 s = pos.add(randomDir().scale(0.5));
            arc(s, s.add(randomDir().scale(rand(0.7f, 1.4f))).add(back.scale(0.4)), 0.32f, 2, 0, 3, 1, 0.32f, false, 0.45f);
        }
        // 지나온 길을 잇는 굵은 번개 꼬리
        if (p.trail.size() >= 3) {
            Iterator<Vec3> it = p.trail.iterator();
            it.next();
            it.next();
            Vec3 prev = it.next();
            arc(prev.add(randomDir().scale(0.3)), pos.add(back.scale(0.8)), 0.6f, 3, 0, 4, 1, 0.22f, false, 0.55f);
        }
        // 음속 고리 (무거운 물체가 공기를 찢는 느낌)
        if (p.age % 3 == 0) FxManager.addRing(pos.add(back.scale(0.6)), p.dir, 0.4f, 2.0f, 6, 0, 0.55f, EL);
        for (int i = 0; i < count(4); i++) {
            spawn(sp(Sprite.Kind.SPARK, pos.add(randomDir().scale(0.6)), back.scale(rand(0.2f, 0.45f)).add(randomDir().scale(0.12)), 0.09f, 0.09f, 5 + RNG.nextInt(5))
                    .color(1f, 0.8f, 0.35f, 1f).physics(0.03f, 0.9f));
        }
        for (int i = 0; i < count(3); i++) {
            Sprite em = sp(Sprite.Kind.EMBER, pos.add(randomDir().scale(0.9)), back.scale(0.08).add(randomDir().scale(0.03)), rand(0.12f, 0.2f), 0.04f, 12 + RNG.nextInt(10))
                    .color(1f, rand(0.6f, 0.85f), 0.25f, 1f).physics(-0.002f, 0.94f);
            em.frameOffset = RNG.nextInt(100);
            spawn(em);
        }
        if (RNG.nextFloat() < 0.8f * FxManager.amount() * FxManager.lod) {
            Sprite f = sp(Sprite.Kind.FLAME, pos.add(back.scale(rand(0.6f, 1.4f))), back.scale(0.05), rand(1.0f, 1.5f), rand(1.8f, 2.4f), 6 + RNG.nextInt(4))
                    .color(1f, 0.82f, 0.45f, 0.9f);
            f.frameOffset = RNG.nextInt(64);
            f.rot = RNG.nextFloat() * TAU;
            spawn(f);
        }
    }

    static void renderProjectile(FxManager.ProjectileFx p, Vec3 c, float pt, float time) {
        float grow = Math.min(1f, (p.age + pt) / 3f);
        float s = 1.15f * (0.5f + 0.5f * grow);
        float spinRate = 0.85f;
        float th = (p.age + pt) * spinRate;
        Vec3 fwd = p.dir;
        Vec3 right = fwd.cross(UP);
        right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 up = right.cross(fwd).normalize();

        // 잔상: 최근 위치 2~3개에 흐려진 망치 (멀면 생략)
        if (SkillsClientConfig.AFTERIMAGES.get() && c.distanceToSqr(FxRender.cam) < 64 * 64) {
            int k = 0;
            for (Vec3 q : p.trail) {
                if (k > 3) break;
                if (k > 0) {
                    float a = th - k * spinRate;
                    Vec3 Y = up.scale(Math.cos(a)).add(fwd.scale(Math.sin(a)));
                    Vec3 X = fwd.scale(Math.cos(a)).subtract(up.scale(Math.sin(a)));
                    ThunderHammer.draw(q, Y, X, s * (1f + 0.05f * k), ThunderHammer.MASS_Y, 0.35f + 0.15f * k, 0.5f - 0.12f * k, k * 0.21f, false);
                }
                k++;
            }
        }
        Vec3 Y = up.scale(Math.cos(th)).add(fwd.scale(Math.sin(th)));
        Vec3 X = fwd.scale(Math.cos(th)).subtract(up.scale(Math.sin(th)));
        ThunderHammer.draw(c, Y, X, s, ThunderHammer.MASS_Y, 0.03f, 1.15f, time * 0.03f, true);
        FxRender.additive();

        // 회전 궤적 고리 (망치 머리가 그리는 원)
        FxRender.elemShader(EL, 3f, 0.3f, 0.9f);
        BufferBuilder bb = FxRender.quads();
        float rr = (ThunderHammer.HEAD_Y - ThunderHammer.MASS_Y + 0.4f) * s;
        FxRender.ring(bb, c, right, rr * 0.8f, rr * 1.15f, 0.7f, -th * 1.0f, 36);
        FxRender.draw(bb);

        Vec3 head = c.add(Y.scale((ThunderHammer.HEAD_Y - ThunderHammer.MASS_Y) * s));
        float fl = 0.9f + 0.1f * (float) Math.sin(time * 3.7f);
        frameGlow(c, 6f * s * fl, GOLD, 0.32f);
        frameGlow(head, 2.4f * s, PALE, 0.5f);
        frameGlow(c, 3.2f * s, VIOLET, 0.15f);
    }

    /** 망치 적중: size = 폭발 반지름, size2 = 1 이면 허공에서 끝 */
    private static void hammerImpact(SkillEventPayload e, @Nullable Entity caster) {
        Vec3 pos = e.pos();
        Vec3 n = e.vec();
        n = n.lengthSqr() < 0.25 ? UP : n.normalize();
        float R = e.size() > 0.1f ? e.size() : 3.5f;
        boolean fizzle = e.size2() > 0.5f;
        boolean blockHit = e.target() < 0 && !fizzle;
        Vec3 ringN = blockHit && Math.abs(n.y) < 0.5 ? n : UP;
        FxManager.lod = FxManager.lodAt(pos);
        if (fizzle) {
            // 허공에서 끝: 피해 없음 → 판정 범위도 그리지 않음 (작고 가벼운 폭발만)
            thunderBlast(pos, ringN, R * 0.6f, 0.55f, false, caster, false);
            return;
        }
        thunderBlast(pos, ringN, R, 1.0f, true, caster, true);
        if (ringN == UP) {
            DebrisFx.burst(pos, R * 0.8f, 26, 0.9f);
            DebrisFx.crater(pos, R * 0.7f, 160, EL);
        } else {
            DebrisFx.burst(pos, R * 0.5f, 14, 0.6f);
        }
    }

    /**
     * 공용 천둥 폭발: 흰 섬광 → 금빛 돔 → 충격파 벽·링 → 위로 치솟는 번개 → 튀는 불꽃·플라즈마·흙먼지 → 빛나는 금 간 땅.
     * @param power 0.5 약함 ~ 1.4 강함 (궁극기는 따로)
     */
    private static void thunderBlast(Vec3 p, Vec3 ringN, float R, float power, boolean upBolts, @Nullable Entity caster, boolean hurts) {
        FxManager.lod = FxManager.lodAt(p);
        // 맞은 틱의 판정 범위 (가득 4틱). 공중에서 맞았으면 바로 아래 땅에 (3칸 안)
        if (hurts) {
            Vec3 g = ThunderParts.groundAt(caster, p);
            ThunderParts.addEdge(p.y - g.y < 3.0 ? g : p, R, 4, 0, 1f, caster);
        }
        ThunderParts.addBlast(new ThunderParts.Blast(p.add(ringN.scale(0.3)), R * 0.85f, power, 14, 4));
        spawn(sp(Sprite.Kind.GLOW, p, Vec3.ZERO, 6f * power + 2f, 9f * power + 2f, 5).color(1f, 0.97f, 0.88f, 1f));
        spawn(sp(Sprite.Kind.GLOW, p, Vec3.ZERO, R * 2.5f, R * 3.4f, 12).color(1f, 0.7f, 0.2f, 0.7f));
        spawn(sp(Sprite.Kind.GLOW, p, Vec3.ZERO, R * 1.8f, R * 2.6f, 9).color(0.62f, 0.32f, 1f, 0.35f));
        // 충격파: 가장 밝은 고리와 충격파 벽이 폭발 피해 반지름 R 에서 멈춤 + 경계를 잠깐 또렷하게
        Vec3 rc = p.add(ringN.scale(0.07));
        FxManager.addRing(rc, ringN, 0.5f, R * ThunderParts.RK, 9, 0, 1f, EL);
        FxManager.addRing(rc, ringN, 0.3f, R * 0.7f, 7, 1, 0.85f, EL);
        FxManager.addRing(p.add(ringN.scale(1.2)), ringN, 0.3f, R * 0.9f, 7, 1, 0.6f, EL);
        if (ringN == UP) {
            // 거대한 충격파 벽 (판정 반지름 R 에서 멈춤)
            ThunderParts.addWall(ThunderParts.ShockWall.ring(p, R * 0.3f, R, 2.0f * power + 0.6f, 9, 0));
            ThunderParts.addMark(new ThunderParts.Mark(CRACKS, p, UP, R * 1.05f, 120, 0, 3f, 0f, 0.85f, GOLD));
            SkillFx.addDecal(p, UP, R * 0.75f, 160, 0.6f, 0.5f, 0.42f);
        } else {
            ThunderParts.addMark(new ThunderParts.Mark(CRACKS, p, ringN, R * 0.8f, 90, 0, 2f, 0f, 0.7f, GOLD));
        }
        // 위로 치솟는 번개
        if (upBolts) {
            int nb = 3 + (power > 1.05f ? 1 : 0);
            for (int i = 0; i < nb; i++) {
                Vec3 top = p.add(rand(-1f, 1f) * R * 0.6f, rand(5f, 9f) * power, rand(-1f, 1f) * R * 0.6f);
                arc(p.add(0, 0.2, 0), top, 1.1f * power, 6 + RNG.nextInt(3), i, 5, 2, 0.22f, false, 0.4f);
            }
        }
        // 바닥을 기어 나가는 번개
        int ng = Math.max(4, count((int) (10 * power)));
        float base = RNG.nextFloat() * TAU;
        Vec3[] tb = FxRender.basis(ringN);
        for (int i = 0; i < ng; i++) {
            double a = base + i * TAU / ng + rand(-0.2f, 0.2f);
            Vec3 d = tb[0].scale(Math.cos(a)).add(tb[1].scale(Math.sin(a)));
            Vec3 s0 = p.add(d.scale(R * 0.2)).add(ringN.scale(0.15));
            Vec3 s1 = p.add(d.scale(R * rand(0.75f, 1.0f))).add(ringN.scale(0.12));
            arc(s0, s1, 0.5f * power, 5 + RNG.nextInt(3), RNG.nextInt(2), 4, 1, 0.24f, ringN == UP, 0.3f);
        }
        for (int i = 0; i < count((int) (60 * power)); i++) {
            Vec3 d = randomDir();
            if (d.dot(ringN) < 0) d = d.subtract(ringN.scale(2 * d.dot(ringN)));
            spawn(sp(Sprite.Kind.SPARK, p, d.scale(rand(0.6f, 1.4f) * power), 0.12f, 0.12f, 8 + RNG.nextInt(9))
                    .color(1f, rand(0.75f, 0.9f), 0.4f, 1f).physics(0.045f, 0.9f));
        }
        for (int i = 0; i < count((int) (20 * power)); i++) {
            Vec3 d = randomDir().add(ringN.scale(0.4)).normalize();
            Sprite f = sp(Sprite.Kind.FLAME, p.add(d.scale(0.5)), d.scale(rand(0.15f, 0.34f) * power), rand(1.3f, 1.9f) * power, rand(2.2f, 3f) * power, 8 + RNG.nextInt(6))
                    .color(1f, rand(0.75f, 0.9f), 0.45f, 1f).physics(-0.006f, 0.84f);
            f.frameOffset = RNG.nextInt(64);
            f.rot = RNG.nextFloat() * TAU;
            spawn(f);
        }
        for (int i = 0; i < count((int) (28 * power)); i++) {
            Vec3 at = p.add(randomDir().scale(rand(0.3f, 1.0f) * R));
            Sprite em = sp(Sprite.Kind.EMBER, at, new Vec3(rand(-0.03f, 0.03f), rand(0.02f, 0.07f), rand(-0.03f, 0.03f)), rand(0.12f, 0.22f), 0.04f, 26 + RNG.nextInt(24))
                    .color(1f, rand(0.65f, 0.85f), 0.25f, 1f).physics(-0.002f, 0.96f);
            em.sway = 0.003f;
            em.frameOffset = RNG.nextInt(100);
            spawn(em);
        }
        dust(p, R, (int) (10 * power), power);
        double d = distToPlayer(p);
        if (d < R + 16) CameraShake.add((float) (3.0 * power * (1 - d / (R + 16))));
        if (d < 10) ThunderParts.flash(0xFFE6A0, 0.28f * power * (float) (1 - d / 10), 6);
    }

    /** 흙먼지 (반투명 연기) */
    private static void dust(Vec3 g, float R, int n, float power) {
        for (int i = 0; i < count(n); i++) {
            float ang = RNG.nextFloat() * TAU;
            float rr = R * rand(0.2f, 0.9f);
            Vec3 at = polar(g, ang, rr, rand(0.2f, 0.8f));
            Vec3 v = new Vec3(Math.cos(ang) * rand(0.06f, 0.16f) * power, rand(0.02f, 0.06f), Math.sin(ang) * rand(0.06f, 0.16f) * power);
            float sh = rand(0.85f, 1.15f);
            Sprite sm = sp(Sprite.Kind.SMOKE, at, v, rand(1.4f, 2.2f) * power, rand(3.4f, 4.8f) * power, 24 + RNG.nextInt(18))
                    .color(DUST[0] * sh, DUST[1] * sh, DUST[2] * sh, 0.5f).physics(0f, 0.92f);
            sm.rot = RNG.nextFloat() * TAU;
            spawn(sm);
        }
    }

    // =====================================================================
    // 공용: 룬 원
    // =====================================================================

    /** 룬 원 + 흐르는 테두리 (더하기 섞기, 매 프레임) */
    private static void renderRune(Vec3 base, float R, float a, float time, float spinDir) {
        if (a <= 0.01f || R <= 0.05f) return;
        Vec3 c = base.add(0, 0.06, 0);
        RenderSystem.disableCull();
        FxRender.spriteShader(RUNE);
        BufferBuilder bb = FxRender.quads();
        FxRender.planeQuad(bb, c, UP, R, time * 0.03f * spinDir, a * GOLD[0], a * GOLD[1], a * GOLD[2], a);
        float a2 = a * 0.55f;
        FxRender.planeQuad(bb, c.add(0, 0.01, 0), UP, R * 0.52f, -time * 0.06f * spinDir, a2 * PALE[0], a2 * PALE[1], a2 * PALE[2], a2);
        FxRender.draw(bb);
        FxRender.spriteShader(FxRender.GLOW_WHITE);
        bb = FxRender.quads();
        float g = a * 0.35f;
        FxRender.planeQuad(bb, c.add(0, 0.02, 0), UP, R * 1.25f, 0f, g, g * 0.75f, g * 0.3f, g);
        FxRender.draw(bb);
        FxRender.elemShader(EL, 2.2f, 0.25f, 0.6f + 0.6f * a);
        bb = FxRender.quads();
        FxRender.ring(bb, c.add(0, 0.03, 0), UP, R * 0.93f, R, 0.5f + 0.5f * a, time * 0.4f, ThunderParts.segs(R, TAU));
        FxRender.draw(bb);
    }

    /** 룬 원 위로 떠오르는 금빛 불씨·지직거림 (매 틱) */
    private static void tickRuneSparks(Vec3 base, float R, float k) {
        FxManager.lod = FxManager.lodAt(base);
        float area = Math.max(1f, R / 4f);
        for (int i = 0; i < count((int) ((1 + 4 * k) * area)); i++) {
            float ang = RNG.nextFloat() * TAU;
            float rr = R * (float) Math.sqrt(RNG.nextFloat());
            Sprite em = sp(Sprite.Kind.EMBER, polar(base, ang, rr, 0.1), new Vec3(0, rand(0.05f, 0.12f), 0), rand(0.1f, 0.2f), 0.04f, 14 + RNG.nextInt(10))
                    .color(1f, rand(0.7f, 0.9f), 0.3f, 1f).physics(-0.003f, 0.95f);
            em.frameOffset = RNG.nextInt(100);
            spawn(em);
        }
        if (RNG.nextFloat() < 0.3f + 0.6f * k) {
            float a0 = RNG.nextFloat() * TAU, a1 = a0 + (R > 6 ? 0.3f : 0.6f) * (RNG.nextBoolean() ? 1 : -1);
            arc(polar(base, a0, R, 0.15), polar(base, a1, R, 0.15), 0.3f + 0.25f * k, 2, 0, 3, 0, 0.28f, true, 0.4f);
        }
    }

    // =====================================================================
    // 2. 천둥 도약
    // =====================================================================

    private static void leapSlam(Vec3 c, float R, @Nullable Entity caster) {
        FxManager.lod = FxManager.lodAt(c);
        thunderBlast(c, UP, R, 1.25f, true, caster, true);
        ThunderParts.addWall(ThunderParts.ShockWall.ring(c, R * 0.2f, R, 3.4f, 7, 0));
        DebrisFx.burst(c, R, 34, 1.0f);
        DebrisFx.crater(c, R * 0.9f, 140, EL);
        Minecraft mc = Minecraft.getInstance();
        if (caster != null && caster == mc.player) {
            CameraShake.add(3.4f);
            ThunderParts.flash(0xFFE7A0, 0.3f, 7);
        }
    }

    static final class LeapFx {
        static final int HIST = 12;
        final Vec3 start, dest;
        final int ticks, casterId;
        final float R;
        final double arcH;
        final Vec3[] hist = new Vec3[HIST];
        int histN, age, landedAge = -1;
        boolean landed;

        LeapFx(Vec3 start, Vec3 dest, int ticks, float R, int casterId) {
            this.start = start;
            this.dest = dest;
            this.ticks = ticks;
            this.R = R;
            this.casterId = casterId;
            Vec3 flat = dest.subtract(start).multiply(1, 0, 1);
            this.arcH = 2.5 + flat.length() * 0.25; // 서버와 같은 공식
            FxManager.lod = FxManager.lodAt(start);
            // 도약 폭발 (발판을 박차는 반동: 흙·조각이 뛰는 반대쪽으로 튐)
            spawn(sp(Sprite.Kind.GLOW, start.add(0, 0.6, 0), Vec3.ZERO, 3.5f, 6f, 5).color(1f, 0.95f, 0.8f, 1f));
            FxManager.addRing(start.add(0, 0.06, 0), UP, 0.4f, 3.2f, 8, 0, 1f, EL);
            FxManager.addRing(start.add(0, 0.06, 0), UP, 0.3f, 2.0f, 6, 2, 0.8f, EL);
            ThunderParts.addWall(ThunderParts.ShockWall.ring(start, 0.4f, 2.6f, 0.9f, 8, 0));
            ThunderParts.addMark(new ThunderParts.Mark(CRACKS, start, UP, 1.8f, 70, 0, 2f, 0f, 0.6f, GOLD));
            Vec3 back = flat.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, -1) : flat.normalize().scale(-1);
            DebrisFx.directed(start, back, 2.6f, 55f, 10, 0.6f);
            dust(start, 1.8f, 8, 0.7f);
            for (int i = 0; i < count(30); i++) {
                Vec3 d = new Vec3(rand(-1f, 1f), rand(0.4f, 1.6f), rand(-1f, 1f)).normalize();
                spawn(sp(Sprite.Kind.SPARK, start.add(0, 0.2, 0), d.scale(rand(0.4f, 1.0f)), 0.1f, 0.1f, 7 + RNG.nextInt(7))
                        .color(1f, 0.82f, 0.4f, 1f).physics(0.04f, 0.9f));
            }
            for (int i = 0; i < 3; i++) {
                float ang = RNG.nextFloat() * TAU;
                arc(start.add(0, 0.1, 0), polar(start, ang, rand(1.5f, 2.6f), 0.1), 0.45f, 4, 0, 3, 1, 0.28f, true, 0.3f);
            }
            if (distToPlayer(start) < 6) CameraShake.add(1.2f);
        }

        Vec3 at(double t) {
            double k = Math.min(1.0, Math.max(0.0, t));
            return start.lerp(dest, k).add(0, 4 * arcH * k * (1 - k), 0);
        }

        @Nullable
        Entity caster() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || casterId < 0) return null;
            Entity e = mc.level.getEntity(casterId);
            return e == null || e.isRemoved() ? null : e;
        }

        boolean tick() {
            age++;
            if (landed && landedAge < 0) landedAge = age;
            if (landedAge >= 0 ? age - landedAge > 8 : age > ticks + 30) return true;
            if (landedAge >= 0 || age > ticks + 2) {
                // 착지 후엔 잔상 띠만 사그라짐
                if (histN > 0) histN--;
                return false;
            }
            Entity e = caster();
            Vec3 body = (e != null ? e.position() : at((double) age / ticks)).add(0, 0.9, 0);
            for (int i = HIST - 1; i > 0; i--) hist[i] = hist[i - 1];
            hist[0] = body;
            histN = Math.min(HIST, histN + 1);
            FxManager.lod = FxManager.lodAt(body);
            // 금빛 잔상 + 지직거리는 몸
            spawn(sp(Sprite.Kind.GLOW, body, Vec3.ZERO, 2.4f, 1.2f, 7).color(1f, 0.75f, 0.3f, 0.55f));
            if (e != null) bodyArcs(e, 2, 0.4f);
            if (histN >= 3) arc(hist[2], hist[0], 0.55f, 3, 0, 4, 1, 0.25f, false, 0.45f);
            for (int i = 0; i < count(4); i++) {
                spawn(sp(Sprite.Kind.SPARK, body.add(randomDir().scale(0.5)), randomDir().scale(rand(0.08f, 0.25f)), 0.08f, 0.08f, 6 + RNG.nextInt(5))
                        .color(1f, 0.8f, 0.4f, 1f).physics(0.03f, 0.9f));
            }
            for (int i = 0; i < count(2); i++) {
                Sprite em = sp(Sprite.Kind.EMBER, body.add(randomDir().scale(0.6)), new Vec3(0, rand(-0.02f, 0.03f), 0), rand(0.12f, 0.2f), 0.04f, 14 + RNG.nextInt(10))
                        .color(1f, rand(0.65f, 0.85f), 0.25f, 1f).physics(0.002f, 0.95f);
                em.frameOffset = RNG.nextInt(100);
                spawn(em);
            }
            tickRuneSparks(dest, R, (float) age / ticks);
            return false;
        }

        /** 착지점: 좁혀 드는 카운트다운 고리 + 룬 원 */
        void renderGround(float pt, float time) {
            if (landedAge >= 0) return;
            // 서버는 이벤트 틱에 이미 한 번 움직였으므로 +1 (착지 틱 = ticks)
            float k = clamp01((age + 1 + pt) / ticks);
            float rem = 1f - k;
            // 착지 판정 범위 (= 실제 반지름): 착지 틱에 가득 차도록 차오름. 안쪽 룬은 장식 (바깥 고리가 경계 안쪽)
            SkillFx.dangerZone(dest, R, k, EL, caster(), 1f, time);
            FxRender.additive();
            RenderSystem.disableCull();
            renderRune(dest, R * (0.8f - 0.15f * rem), 0.35f + 0.45f * k, time, -1f);
            Vec3 c = dest.add(0, 0.08, 0);
            frameGlow(c.add(0, 0.3, 0), R * (1.5f + 1.0f * k), GOLD, 0.15f + 0.3f * k * k);
        }

        /** 몸을 따라오는 금빛 잔상 띠 */
        void renderTrail(float pt, float time) {
            if (histN < 2) return;
            Entity e = caster();
            Vec3 head = landedAge < 0 && e != null ? e.getPosition(pt).add(0, 0.9, 0) : hist[0];
            float fade = landedAge >= 0 ? clamp01(1f - (age - landedAge + pt) / 8f) : 1f;
            FxRender.elemShader(EL, 2.4f, 0.2f, 1.2f * fade);
            BufferBuilder bb = FxRender.quads();
            Vec3 prev = head;
            for (int i = 0; i < histN; i++) {
                Vec3 q = hist[i];
                if (q == null) break;
                float t0 = (float) i / histN, t1 = (float) (i + 1) / histN;
                strip(bb, prev, q, 1.3f * (1f - t0) + 0.15f, 1.3f * (1f - t1) + 0.15f, (1f - t0) * fade, (1f - t1) * fade, t0 * 3f + time * 0.05f, t1 * 3f + time * 0.05f);
                prev = q;
            }
            FxRender.draw(bb);
            if (landedAge < 0) frameGlow(head, 3.2f, GOLD, 0.4f);
        }
    }

    /** 카메라를 향한 두 갈래 띠 (가운데 밝음) */
    private static void strip(BufferBuilder bb, Vec3 a, Vec3 b, float w0, float w1, float h0, float h1, float v0, float v1) {
        Vec3 d = b.subtract(a);
        Vec3 s = d.cross(FxRender.cam.subtract(a));
        if (s.lengthSqr() < 1.0E-8) return;
        s = s.normalize();
        Vec3 sa = s.scale(w0 * 0.5), sb = s.scale(w1 * 0.5);
        FxRender.vertex(bb, a.x - sa.x, a.y - sa.y, a.z - sa.z, 0f, v0, 1, 1, 1, 0f);
        FxRender.vertex(bb, a.x, a.y, a.z, 0.5f, v0, 1, 1, 1, h0);
        FxRender.vertex(bb, b.x, b.y, b.z, 0.5f, v1, 1, 1, 1, h1);
        FxRender.vertex(bb, b.x - sb.x, b.y - sb.y, b.z - sb.z, 0f, v1, 1, 1, 1, 0f);
        FxRender.vertex(bb, a.x, a.y, a.z, 0.5f, v0, 1, 1, 1, h0);
        FxRender.vertex(bb, a.x + sa.x, a.y + sa.y, a.z + sa.z, 1f, v0, 1, 1, 1, 0f);
        FxRender.vertex(bb, b.x + sb.x, b.y + sb.y, b.z + sb.z, 1f, v1, 1, 1, 1, 0f);
        FxRender.vertex(bb, b.x, b.y, b.z, 0.5f, v1, 1, 1, 1, h1);
    }

    // =====================================================================
    // 3. 뇌명 일격 (부채꼴 충격파)
    // =====================================================================

    static final class ClapFx {
        /**
         * 서버: 신호를 보내고 HIT(2)틱 뒤에 부채꼴 전체를 한 번에 맞힘 → 망치는 그 2틱 동안 세제곱 가속으로 내려찍고,
         * 땅에 닿는 틱(= 피해 틱)에 섬광·충격파 벽·흔들림. 충격파는 그 뒤 6틱 만에 끝까지 (대부분 처음 3틱에).
         */
        static final int HIT = 2, WAVE = 6, LIFE = 44 + HIT;
        final Vec3 o, dir, grip, hit;
        final float range, half, halfDeg, base;
        final List<Vec3[]> cracks = new ArrayList<>();
        final float[] crackLen;
        @Nullable
        final Entity caster;
        int age;
        boolean struck;

        ClapFx(Vec3 o, Vec3 dir, float range, float halfDeg, @Nullable Entity caster) {
            this.o = o;
            this.dir = dir;
            this.range = range;
            this.halfDeg = halfDeg;
            this.half = (float) Math.toRadians(halfDeg);
            this.base = (float) Math.atan2(dir.z, dir.x);
            this.caster = caster;
            // 준비 때 젖힌 망치와 같은 손잡이 → 내려찍은 망치 머리가 닿는 자리 = 충격의 중심
            this.grip = clapGrip(o, dir, caster);
            Vec3 headEnd = grip.add(clapY(dir, CLAP_PHI1).scale((ThunderHammer.HEAD_Y - ThunderHammer.GRIP_Y) * CLAP_SCALE));
            this.hit = new Vec3(headEnd.x, o.y, headEnd.z);
            // 갈라지는 땅 (부채꼴 안쪽으로 뻗는 금, 끝은 사거리 안)
            int n = Math.min(9, 4 + (int) (range / 3));
            crackLen = new float[n];
            for (int i = 0; i < n; i++) {
                float a = base + half * (n == 1 ? 0 : (2f * i / (n - 1) - 1f)) * 0.9f + rand(-0.06f, 0.06f);
                float L = range * rand(0.7f, 0.98f);
                Vec3 s0 = polar(o, a, 0.6, 0.05), s1 = polar(o, a, L, 0.05);
                Vec3[] pts = ThunderParts.jagged(s0, s1, 5, 0.12f, true);
                for (int j = 0; j < pts.length; j++) pts[j] = new Vec3(pts[j].x, o.y + 0.05, pts[j].z);
                cracks.add(pts);
                crackLen[i] = L;
            }
            // 내려찍기 시작: 공기를 가르는 짧은 금빛 줄 (아직 섬광 없음 — 타격은 HIT 틱에)
            FxManager.lod = FxManager.lodAt(o);
            Vec3 top = grip.add(clapY(dir, CLAP_PHI0).scale((ThunderHammer.HEAD_Y - ThunderHammer.GRIP_Y) * CLAP_SCALE));
            arc(top, hit.add(0, 0.6, 0), 0.5f, HIT + 1, 0, 3, 0, 0.18f, false, 0.3f);
        }

        /** 망치가 땅에 닿는 틱 (= 서버 피해 틱) */
        private void strike() {
            struck = true;
            FxManager.lod = FxManager.lodAt(o);
            spawn(sp(Sprite.Kind.GLOW, hit.add(0, 0.5, 0), Vec3.ZERO, 5f, 8f, 4).color(1f, 0.96f, 0.85f, 1f));
            spawn(sp(Sprite.Kind.GLOW, hit, Vec3.ZERO, 6f, 9f, 8).color(1f, 0.7f, 0.2f, 0.6f));
            // 음속 고리 (공격 방향으로 세운 것 2개 + 망치가 박힌 바닥)
            Vec3 boom = o.add(0, 1.0, 0).add(dir.scale(1.6));
            FxManager.addRing(boom, dir, 0.4f, 4.2f, 6, 0, 1f, EL);
            FxManager.addRing(boom.add(dir.scale(1.4)), dir, 0.3f, 6.0f, 8, 1, 0.7f, EL);
            FxManager.addRing(hit.add(0, 0.06, 0), UP, 0.3f, 2.6f, 6, 0, 0.9f, EL);
            // 부채꼴로 달려 나가는 거대한 충격파 벽 2겹 (사거리에서 멈춤)
            ThunderParts.addWall(new ThunderParts.ShockWall(o, 0.8f, range, 2.8f, WAVE + 2, 0, base - half, base + half));
            ThunderParts.addWall(new ThunderParts.ShockWall(o, 0.5f, range * 0.7f, 1.6f, WAVE + 3, 1, base - half, base + half));
            ThunderParts.addMark(new ThunderParts.Mark(CRACKS, hit, UP, 2.2f, 90, 0, 2f, 0f, 0.75f, GOLD));
            DebrisFx.directed(o, dir, range, (float) Math.toDegrees(half), 40, 1.0f);
            DebrisFx.crater(hit, 1.8f, 110, EL);
            for (int i = 0; i < count(30); i++) {
                Vec3 d = new Vec3(rand(-1f, 1f), rand(0.3f, 1.4f), rand(-1f, 1f)).add(dir.scale(0.6)).normalize();
                spawn(sp(Sprite.Kind.SPARK, hit.add(0, 0.2, 0), d.scale(rand(0.4f, 1.1f)), 0.1f, 0.1f, 7 + RNG.nextInt(7))
                        .color(1f, 0.82f, 0.4f, 1f).physics(0.045f, 0.9f));
            }
            dust(hit, 2.2f, 6, 0.9f);
            double d = distToPlayer(o);
            Minecraft mc = Minecraft.getInstance();
            if (caster != null && caster == mc.player) {
                CameraShake.add(2.8f);
                CameraShake.kick(1.3f, 0f);
                ThunderParts.flash(0xFFE7A0, 0.2f, 5);
            } else if (d < range + 12) {
                CameraShake.add((float) (2.4 * (1 - d / (range + 12))));
            }
        }

        /** 충격파 앞머리 (맞은 틱부터 잰 시간) */
        float waveR(float t) {
            return range * easeOutCubic(clamp01(t / WAVE));
        }

        boolean tick() {
            age++;
            if (age > LIFE) return true;
            if (age >= HIT && !struck) strike();
            int local = age - HIT;
            if (local < 0) return false;
            if (local <= WAVE + 1) {
                float r = waveR(local);
                Vec3 front = polar(o, base, r, 0);
                FxManager.lod = FxManager.lodAt(front);
                float spanArc = Math.max(1f, half * r);
                int nArc = Math.max(1, count(Math.min(4, 1 + (int) (spanArc / 3f))));
                for (int i = 0; i < nArc; i++) {
                    float a = base + rand(-half, half);
                    arc(polar(o, a, Math.max(0.5f, r - 2f), 0.15), polar(o, a + rand(-0.06f, 0.06f), Math.min(range, r + 0.8f), 0.12), 0.55f, 3, 0, 4, 1, 0.22f, true, 0.35f);
                }
                for (int i = 0; i < count((int) (2 + spanArc * 0.8f)); i++) {
                    float a = base + rand(-half, half);
                    Vec3 at = polar(o, a, r, 0.2);
                    spawn(sp(Sprite.Kind.SPARK, at, new Vec3(Math.cos(a) * 0.2, rand(0.2f, 0.45f), Math.sin(a) * 0.2), 0.09f, 0.09f, 6 + RNG.nextInt(6))
                            .color(1f, 0.82f, 0.4f, 1f).physics(0.04f, 0.92f));
                }
                for (int i = 0; i < count((int) (1 + spanArc * 0.25f)); i++) {
                    float a = base + rand(-half, half);
                    Vec3 at = polar(o, a, r, rand(0.3f, 0.8f));
                    float sh = rand(0.85f, 1.15f);
                    Sprite sm = sp(Sprite.Kind.SMOKE, at, new Vec3(Math.cos(a) * 0.08, rand(0.02f, 0.06f), Math.sin(a) * 0.08), rand(1.4f, 2f), rand(3.2f, 4.2f), 20 + RNG.nextInt(10))
                            .color(DUST[0] * sh, DUST[1] * sh, DUST[2] * sh, 0.5f).physics(0f, 0.92f);
                    sm.rot = RNG.nextFloat() * TAU;
                    spawn(sm);
                }
            } else if (local < 28 && RNG.nextFloat() < 0.25f) {
                // 식어 가는 금 위의 잔전기
                Vec3[] c = cracks.get(RNG.nextInt(cracks.size()));
                int i = RNG.nextInt(c.length - 1);
                arc(c[i].add(0, 0.05, 0), c[i + 1].add(0, 0.05, 0).add(randomDir().scale(0.4)), 0.25f, 2, 0, 3, 0, 0.3f, true, 0.4f);
            }
            return false;
        }

        /** 갈라진 땅 (장식, 테두리 없음 — 판정 부채꼴은 render 에서 SkillFx.dangerCone) */
        void emitCracks(BufferBuilder bb, float pt) {
            float t = age + pt - HIT;
            if (t < 0f) return;
            float r = waveR(t);
            float cool = clamp01((t - WAVE) / (LIFE - HIT - WAVE));
            float a = (float) Math.pow(1f - cool, 1.5) * (t < WAVE + 3 ? 1f : 0.85f);
            if (a < 0.01f) return;
            for (int i = 0; i < cracks.size(); i++) {
                Vec3[] c = cracks.get(i);
                int shown = (int) Math.ceil((c.length - 1) * clamp01(r / crackLen[i])) + 1;
                float ga = a * 0.55f;
                ThunderParts.flatRibbon(bb, c, shown, 0.9f, ga * 1f, ga * 0.65f, ga * 0.2f, ga);
                ThunderParts.flatRibbon(bb, c, shown, 0.22f, a, a * 0.92f, a * 0.7f, a);
            }
        }

        void render(float pt, float time) {
            float t = age + pt;
            // 판정 부채꼴: 내려찍는 동안 마저 차서 맞는 틱에 가득 → 3틱 뒤 사라짐
            if (t < HIT + 3f) {
                float fill = t < HIT ? CLAP_CHARGE_FILL + (1f - CLAP_CHARGE_FILL) * clamp01(t / HIT) : 1f;
                float a = t < HIT ? 1f : 1f - smooth((t - HIT) / 3f);
                SkillFx.dangerCone(o, dir, range, halfDeg, fill, EL, caster, a, time);
                FxRender.additive();
                RenderSystem.disableCull();
            }
            float lt = t - HIT;
            if (lt >= 0f && lt < WAVE + 1) {
                float r = waveR(lt);
                Vec3 front = polar(o, base, r, 0.8);
                frameGlow(front, 3f + r * half, GOLD, 0.35f * (1f - lt / (WAVE + 1)));
            }
            // 땅을 내려찍는 망치: 젖힌 자세(φ0)에서 HIT 틱 동안 세제곱 가속 (느린 시작 → 갑작스런 끝), 박힌 채 흩어짐
            if (lt < 11f) {
                float sw = clamp01(t / HIT);
                double phi = CLAP_PHI0 + (CLAP_PHI1 - CLAP_PHI0) * sw * sw * sw;
                Vec3 Y = clapY(dir, phi), X = clapX(dir, phi);
                float ero = lt < 3f ? 0.05f : clamp01((lt - 3f) / 8f);
                // 내려찍는 동안 바로 뒤에 흐린 잔상 1개 (빠르기)
                if (t < HIT && t > 0.3f) {
                    float sw2 = clamp01((t - 0.5f) / HIT);
                    double phi2 = CLAP_PHI0 + (CLAP_PHI1 - CLAP_PHI0) * sw2 * sw2 * sw2;
                    ThunderHammer.draw(grip, clapY(dir, phi2), clapX(dir, phi2), CLAP_SCALE, ThunderHammer.GRIP_Y, 0.45f, 0.5f, 0.3f, false);
                }
                ThunderHammer.draw(grip, Y, X, CLAP_SCALE, ThunderHammer.GRIP_Y, ero, 1.25f, time * 0.03f, true);
                FxRender.additive();
            }
        }
    }

    // =====================================================================
    // 4. 천둥 포효
    // =====================================================================

    private static void roarBurst(Vec3 g, float R, @Nullable Entity caster) {
        FxManager.lod = FxManager.lodAt(g);
        // R = 서버 폭발 반지름 (끌어당김 반지름 × 0.6). 돔·고리·번개 모두 R 안에서 끝남
        Vec3 chest = g.add(0, 1.1, 0);
        thunderBlast(g, UP, R, 1.15f, true, caster, true);
        ThunderParts.addBlast(new ThunderParts.Blast(chest, R * 0.9f, 1.2f, 14, 3));
        FxManager.addRing(chest, UP, 0.5f, R * ThunderParts.RK, 8, 0, 0.9f, EL);
        // 사방으로 터지는 굵은 번개 (구 모양)
        for (int i = 0; i < Math.max(5, count(9)); i++) {
            Vec3 d = randomDir();
            if (d.y < -0.2) d = new Vec3(d.x, -d.y * 0.5, d.z);
            arc(chest, chest.add(d.normalize().scale(R * rand(0.8f, 1.05f))), 0.8f, 5, RNG.nextInt(2), 5, 2, 0.22f, false, 0.4f);
        }
        DebrisFx.burst(g, R, 30, 0.9f);
        DebrisFx.crater(g, R * 0.8f, 130, EL);
        Minecraft mc = Minecraft.getInstance();
        if (caster != null && caster == mc.player) {
            CameraShake.add(3.0f);
            ThunderParts.flash(0xFFE7A0, 0.3f, 7);
        }
    }

    static final class RoarFx {
        final Vec3 g;
        final float R;
        final int pull, casterId;
        int age;
        boolean done;

        RoarFx(Vec3 g, float R, int pull, int casterId) {
            this.g = g;
            this.R = R;
            this.pull = pull;
            this.casterId = casterId;
            FxManager.lod = FxManager.lodAt(g);
            // 포효 순간: 바깥으로 한 번 퍼지는 음파
            FxManager.addRing(g.add(0, 1.2, 0), UP, 0.5f, R * ThunderParts.RK, 7, 0, 0.8f, EL);
            spawn(sp(Sprite.Kind.GLOW, g.add(0, 1.3, 0), Vec3.ZERO, 3f, 5f, 5).color(1f, 0.95f, 0.8f, 1f));
            if (distToPlayer(g) < R + 8) CameraShake.add(1.2f);
        }

        Vec3 center(float pt) {
            Entity e = caster();
            return e != null ? e.getPosition(pt) : g;
        }

        @Nullable
        Entity caster() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || casterId < 0) return null;
            Entity e = mc.level.getEntity(casterId);
            return e == null || e.isRemoved() ? null : e;
        }

        boolean tick() {
            age++;
            if (done || age > pull + 6) return true;
            Vec3 c = center(1f);
            Vec3 chest = c.add(0, 1.1, 0);
            FxManager.lod = FxManager.lodAt(c);
            float k = clamp01((age + 1f) / pull);
            // 사방에서 빨려 드는 번개 줄기
            for (int i = 0; i < Math.max(1, count(2 + (int) (2 * k))); i++) {
                Vec3 d = randomDir();
                if (d.y < -0.3) d = new Vec3(d.x, -d.y * 0.3, d.z);
                Vec3 from = chest.add(d.normalize().scale(R * rand(0.8f, 1.1f)));
                Vec3 to = chest.add(from.subtract(chest).scale(rand(0.08f, 0.2f)));
                arc(from, to, 0.45f + 0.25f * k, 3, 0, 4, 1, 0.24f, false, 0.4f);
            }
            // 좁혀 드는 음파 고리
            if (age % 3 == 1) {
                FxManager.addRing(chest, UP, R * 1.15f, 0.6f, 8, 0, 0.75f, EL);
                FxManager.addRing(c.add(0, 0.06, 0), UP, R * 1.2f, 0.8f, 9, 1, 0.6f, EL);
                Vec3 tilt = new Vec3(rand(-1f, 1f), rand(0.3f, 1f), rand(-1f, 1f)).normalize();
                FxManager.addRing(chest, tilt, R * 1.0f, 0.5f, 8, 0, 0.45f, EL);
            }
            for (int i = 0; i < count(6); i++) {
                Vec3 d = randomDir();
                Vec3 at = chest.add(d.scale(R * rand(0.6f, 1.0f)));
                spawn(sp(Sprite.Kind.SPARK, at, chest.subtract(at).scale(0.17), 0.09f, 0.09f, 6)
                        .color(1f, 0.82f, 0.4f, 1f).physics(0f, 0.95f));
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && casterId >= 0) {
                Entity e = mc.level.getEntity(casterId);
                if (e != null && !e.isRemoved()) bodyArcs(e, 2, 0.45f);
            }
            if (age % 2 == 0) dust(c, R * 0.9f, 1, 0.6f);
            return false;
        }

        void render(float pt, float time) {
            if (done) return;
            // 서버는 이벤트 틱에 이미 한 번 끌어당김 → +1 (k = 1 인 순간 터짐)
            float k = clamp01((age + 1 + pt) / pull);
            Vec3 c = center(pt);
            Vec3 chest = c.add(0, 1.1, 0);
            // 가슴에 모이는 에너지 구
            float r = 0.25f + 0.65f * k;
            FxRender.elemShader(EL, 4f, 0.05f, 1.2f + 0.6f * k);
            FxManager.BlastSphere.draw(chest, r * (1f + 0.06f * (float) Math.sin(time * 2.3f)), time * 2f);
            // 판정: 끌어당기는 범위 R (채움 없음, 점점 옅어짐) + 터지는 범위 0.6R (터지는 틱에 가득)
            Entity ce = caster();
            SkillFx.dangerZone(c, R, 0f, EL, ce, 0.75f * (1f - 0.5f * k), time);
            SkillFx.dangerZone(c, R * ROAR_BURST, 0.15f + 0.85f * k, EL, ce, 1f, time);
            FxRender.additive();
            RenderSystem.disableCull();
            // 장식: 좁혀 드는 고리가 터지는 범위 바로 바깥에서 멈춤 (끌려 오는 움직임)
            FxRender.elemShader(EL, 2.6f, 0.2f, 0.6f + 0.3f * k);
            BufferBuilder bb = FxRender.quads();
            float br = R * ROAR_BURST + 0.35f;
            float rr = R * 0.97f + (br - R * 0.97f) * easeOutCubic(k);
            FxRender.ring(bb, c.add(0, 0.06, 0), UP, rr - 0.35f, rr, 0.7f + 0.3f * k, time * 0.7f, ThunderParts.segs(rr, TAU));
            FxRender.ring(bb, chest, UP, rr * 0.7f, rr * 0.78f, 0.45f, -time * 0.9f, ThunderParts.segs(rr, TAU));
            FxRender.draw(bb);
            float fl = 0.85f + 0.15f * (float) Math.sin(time * 4.3f);
            frameGlow(chest, (2f + 5f * k * k) * fl, GOLD, 0.35f + 0.45f * k);
            frameGlow(chest, 0.8f + 1.6f * k, PALE, 0.6f + 0.4f * k);
            frameGlow(chest, 3f + 3f * k, VIOLET, 0.15f + 0.15f * k);
        }
    }

    // =====================================================================
    // 5. 뇌신 강림 (궁극기)
    // =====================================================================

    private static void judgment(SkillEventPayload e, @Nullable Entity caster) {
        int phase = Math.round(e.size2());
        float R = Math.max(1f, e.size());
        if (phase == 0) {
            if (JUDGMENTS.size() >= 3) JUDGMENTS.remove(0);
            JUDGMENTS.add(new JudgmentFx(e.pos(), e.vec(), R, Math.max(1, e.duration()), e.casterId(), false));
            return;
        }
        JudgmentFx j = find(e.casterId(), e.pos());
        if (j == null) {
            if (JUDGMENTS.size() >= 3) JUDGMENTS.remove(0);
            j = new JudgmentFx(e.pos(), e.pos(), phase == 1 ? R : R / 1.3f, 1, e.casterId(), true);
            JUDGMENTS.add(j);
        }
        if (phase == 1) j.impact(R);
        else j.aftershock(Math.max(1, e.duration()), R);
    }

    @Nullable
    private static JudgmentFx find(int casterId, Vec3 target) {
        JudgmentFx best = null;
        double bd = 1.0E9;
        for (JudgmentFx j : JUDGMENTS) {
            double d = j.target.distanceTo(target);
            if (casterId >= 0 && j.casterId == casterId) d -= 1000;
            if (d < bd && d < 24) {
                bd = d;
                best = j;
            }
        }
        return best;
    }

    static final class JudgmentFx {
        /** 서버 Judgment 와 같은 값: 내려꽂는 틱, 여진 간격, 여진 수, 여진마다 반지름 +30% */
        static final int SLAM = 6, AFTER_GAP = 10, AFTERSHOCKS = 3, PUFFS = 56, FLASHES = 5;
        static final float AFTER_GROW = 0.3f;
        final Vec3 start, target, cloudC, hf;
        final float R, cloudR, hs;
        final int rise, casterId;
        int age, impactAge = -1, endAge, shocks;
        float impactR;
        Vec3 headEnd;
        // 먹구름
        final float[] pa = new float[PUFFS], pr = new float[PUFFS], ps = new float[PUFFS], py = new float[PUFFS],
                pshade = new float[PUFFS], prot = new float[PUFFS];
        final int[] pf = new int[PUFFS];
        final Vec3[] flashPos = new Vec3[FLASHES];
        final int[] flashAge = new int[FLASHES], flashLife = new int[FLASHES];
        final float[] flashSize = new float[FLASHES];

        JudgmentFx(Vec3 start, Vec3 target, float R, int rise, int casterId, boolean late) {
            this.start = start;
            this.target = target;
            this.R = R;
            this.rise = rise;
            this.casterId = casterId;
            this.impactR = R;
            Vec3 flat = target.subtract(start).multiply(1, 0, 1);
            this.hf = flat.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : flat.normalize();
            this.hs = 3.2f + R * 0.12f;
            this.headEnd = target.add(0, 0.3 * hs, 0);
            double topY = Math.max(start.y + 7, target.y);
            this.cloudC = new Vec3((start.x + target.x) * 0.5, topY + 13, (start.z + target.z) * 0.5);
            this.cloudR = Math.max(R * 1.5f, (float) flat.length() * 0.5f + R);
            this.endAge = late ? 110 : rise + SLAM + AFTER_GAP * AFTERSHOCKS + 70;
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
            if (late) {
                age = rise + SLAM;
                return;
            }
            ScreenFx.darken(target, R + 14, 0.8f, rise + SLAM + 3 * 10 + 40);
            // 솟구치는 순간
            FxManager.lod = FxManager.lodAt(start);
            spawn(sp(Sprite.Kind.GLOW, start.add(0, 1, 0), Vec3.ZERO, 5f, 9f, 6).color(1f, 0.96f, 0.85f, 1f));
            FxManager.addRing(start.add(0, 0.06, 0), UP, 0.5f, 6f, 12, 0, 1f, EL);
            FxManager.addRing(start.add(0, 0.06, 0), UP, 0.3f, 3.8f, 9, 2, 0.85f, EL);
            ThunderParts.addWall(ThunderParts.ShockWall.ring(start, 0.5f, 5f, 1.4f, 11, 0));
            ThunderParts.addPillar(new ThunderParts.Pillar(start, 0.9f, 14f, 10));
            ThunderParts.addMark(new ThunderParts.Mark(CRACKS, start, UP, 2.6f, 140, 0, 2f, 0f, 0.7f, GOLD));
            DebrisFx.burst(start, 2.2f, 16, 0.7f);
            dust(start, 2.5f, 10, 0.9f);
            Minecraft mc = Minecraft.getInstance();
            double d = distToPlayer(start);
            if (d < 20) CameraShake.add((float) (2.0 * (1 - d / 20)));
            if (caster() == mc.player && mc.player != null) ThunderParts.flash(0xFFE7A0, 0.25f, 8);
        }

        @Nullable
        Entity caster() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || casterId < 0) return null;
            Entity e = mc.level.getEntity(casterId);
            return e == null || e.isRemoved() ? null : e;
        }

        /** 시전자 위치 (보이지 않으면 서버와 같은 경로로 추정) */
        Vec3 casterPos(float pt) {
            Entity e = caster();
            if (e != null) return e.getPosition(pt);
            // 서버와 같은 길: 이벤트 틱에 이미 1틱 진행, 1-(1-x)^2 로 감속하며 7칸 위로 → 6틱에 목표로
            float t = age + 1 + pt;
            if (t <= rise) {
                double x = clamp01(t / rise);
                return start.add(0, 7.0 * (1 - (1 - x) * (1 - x)), 0);
            }
            return start.add(0, 7, 0).lerp(target, clamp01((t - rise) / SLAM));
        }

        float form(float t) {
            return smooth(t / Math.max(1f, rise * 0.85f));
        }

        float fade(float t) {
            float in = clamp01(t / 15f);
            float out = 1f - clamp01((t - (endAge - 60)) / 60f);
            return in * out;
        }

        Vec3 puffPos(int i, float t) {
            float omega = 0.03f * (1.4f - 0.7f * pr[i] / cloudR);
            float ang = pa[i] + t * omega;
            return cloudC.add(Math.cos(ang) * pr[i], py[i], Math.sin(ang) * pr[i]);
        }

        /** 망치 머리 가운데 / 손잡이 → 머리 방향 (지금 단계에 맞게) */
        Vec3 hammerHead(float t, float pt) {
            if (impactAge >= 0 || t >= rise + SLAM) return headEnd;
            Vec3 grip = casterPos(pt).add(0, 2.4, 0);
            if (t <= rise) return grip.add(0, (ThunderHammer.HEAD_Y - ThunderHammer.GRIP_Y) * hs * (0.6f + 0.4f * form(t)), 0);
            Vec3 from = start.add(0, 7 + 2.4 + (ThunderHammer.HEAD_Y - ThunderHammer.GRIP_Y) * hs, 0);
            float e = clamp01((t - rise) / SLAM);
            return from.lerp(headEnd, e * e);
        }

        Vec3 hammerY(float t) {
            if (t <= rise) return UP;
            double phi = Math.PI * Math.min(1.0, Math.pow(clamp01((t - rise) / SLAM), 1.5));
            return UP.scale(Math.cos(phi)).add(hf.scale(Math.sin(phi)));
        }

        boolean tick() {
            age++;
            for (int i = 0; i < FLASHES; i++) if (flashAge[i] < flashLife[i]) flashAge[i]++;
            if (age > endAge) return true;
            boolean storm = impactAge < 0 || age - impactAge < 50;
            FxManager.lod = FxManager.lodAt(cloudC);
            // 구름 속 금빛 번쩍임 + 구름 사이 번개
            if (storm && RNG.nextFloat() < 0.45f) {
                for (int i = 0; i < FLASHES; i++) {
                    if (flashAge[i] >= flashLife[i]) {
                        flashPos[i] = puffPos(RNG.nextInt(PUFFS), age).add(0, -1, 0);
                        flashAge[i] = 0;
                        flashLife[i] = 3 + RNG.nextInt(4);
                        flashSize[i] = rand(10f, 16f);
                        break;
                    }
                }
                if (RNG.nextFloat() < 0.5f) {
                    float ang = RNG.nextFloat() * TAU, rr = cloudR * 0.8f * (float) Math.sqrt(RNG.nextFloat());
                    Vec3 s0 = cloudC.add(Math.cos(ang) * rr, -1.8, Math.sin(ang) * rr);
                    float a2 = RNG.nextFloat() * TAU, len = rand(4f, 9f);
                    arc(s0, s0.add(Math.cos(a2) * len, rand(-0.5f, 0.5f), Math.sin(a2) * len), 0.7f, 4, 0, 4, 1, 0.25f, true, 0.45f);
                }
            }
            if (storm && RNG.nextFloat() < 0.5f) {
                float ang = RNG.nextFloat() * TAU, rr = cloudR * (float) Math.sqrt(RNG.nextFloat());
                Sprite sm = sp(Sprite.Kind.SMOKE, cloudC.add(Math.cos(ang) * rr, -1.5 + rand(-1f, 1f), Math.sin(ang) * rr),
                        new Vec3(-Math.sin(ang) * 0.1, 0, Math.cos(ang) * 0.1), rand(5f, 8f), rand(8f, 11f), 30 + RNG.nextInt(16))
                        .color(0.16f, 0.12f, 0.2f, 0.5f * fade(age)).physics(0f, 0.98f);
                sm.rot = RNG.nextFloat() * TAU;
                spawn(sm);
            }

            if (impactAge < 0) {
                float t = age;
                if (t <= rise) {
                    // 금빛 기운에 싸인 시전자 + 머리 위에서 맺히는 망치 (구름에서 번개를 받음)
                    Entity e = caster();
                    Vec3 body = casterPos(1f).add(0, 1, 0);
                    FxManager.lod = FxManager.lodAt(body);
                    if (e != null) bodyArcs(e, 2, 0.35f);
                    for (int i = 0; i < count(4); i++) {
                        float ang = RNG.nextFloat() * TAU;
                        spawn(sp(Sprite.Kind.SPARK, polar(body, ang, rand(0.5f, 1.2f), rand(-1f, 1f)),
                                new Vec3(-Math.sin(ang) * 0.15, rand(0.15f, 0.35f), Math.cos(ang) * 0.15), 0.08f, 0.08f, 7 + RNG.nextInt(5))
                                .color(1f, 0.82f, 0.4f, 1f).physics(-0.005f, 0.92f));
                    }
                    Vec3 head = hammerHead(t, 1f);
                    if (age % 3 == 0) {
                        Vec3 from = puffPos(RNG.nextInt(PUFFS), age).add(0, -1.5, 0);
                        arc(from, head.add(randomDir().scale(hs * 0.3)), 1.3f + form(t), 4, 0, 5, 2, 0.2f, false, 0.4f);
                    }
                    if (RNG.nextFloat() < 0.7f) {
                        Vec3 s0 = head.add(randomDir().scale(hs * 0.6));
                        arc(s0, s0.add(randomDir().scale(hs * rand(0.4f, 0.8f))), 0.5f, 2, 0, 3, 1, 0.3f, false, 0.45f);
                    }
                    for (int i = 0; i < count(3); i++) {
                        Vec3 off = randomDir().scale(hs * rand(1.0f, 1.6f));
                        spawn(sp(Sprite.Kind.EMBER, head.add(off), off.scale(-0.15), 0.25f, 0.08f, 6).color(1f, 0.85f, 0.4f, 1f));
                    }
                    tickRuneSparks(target, R, form(t));
                } else if (t <= rise + SLAM + 4) {
                    // 내려꽂히는 망치: 하늘에서 망치까지 번개가 따라옴
                    Vec3 head = hammerHead(Math.min(t, rise + SLAM), 1f);
                    FxManager.lod = FxManager.lodAt(head);
                    arc(puffPos(RNG.nextInt(PUFFS), age).add(0, -1.5, 0), head, 2.2f, 3, 0, 5, 2, 0.18f, false, 0.4f);
                    for (int i = 0; i < count(10); i++) {
                        spawn(sp(Sprite.Kind.SPARK, head.add(randomDir().scale(hs * 0.5)), randomDir().scale(rand(0.2f, 0.6f)).add(0, 0.4, 0), 0.12f, 0.12f, 6 + RNG.nextInt(5))
                                .color(1f, 0.85f, 0.45f, 1f).physics(0.02f, 0.9f));
                    }
                    tickRuneSparks(target, R, 1f);
                }
            } else {
                // 잔류: 금 간 땅 위의 불씨와 잔전기
                int since = age - impactAge;
                FxManager.lod = FxManager.lodAt(target);
                float k = 1f - clamp01(since / 90f);
                for (int i = 0; i < count((int) (6 * k) + 1); i++) {
                    float ang = RNG.nextFloat() * TAU, rr = impactR * 1.3f * (float) Math.sqrt(RNG.nextFloat());
                    Sprite em = sp(Sprite.Kind.EMBER, polar(target, ang, rr, 0.15), new Vec3(rand(-0.01f, 0.01f), rand(0.04f, 0.12f), rand(-0.01f, 0.01f)),
                            rand(0.12f, 0.24f), 0.04f, 20 + RNG.nextInt(20)).color(1f, rand(0.6f, 0.85f), 0.25f, 1f).physics(-0.003f, 0.96f);
                    em.frameOffset = RNG.nextInt(100);
                    spawn(em);
                }
                if (since < 70 && RNG.nextFloat() < 0.6f * k + 0.1f) {
                    float a0 = RNG.nextFloat() * TAU;
                    float r0 = impactR * rand(0.1f, 1.2f);
                    Vec3 s0 = polar(target, a0, r0, 0.12);
                    arc(s0, s0.add(rand(-2f, 2f), rand(0f, 0.3f), rand(-2f, 2f)), 0.4f, 2 + RNG.nextInt(2), 0, 3, 1, 0.3f, true, 0.4f);
                }
                if (since < 40 && since % 2 == 0) dust(target, impactR * 1.2f, 1, 1.2f);
            }
            return false;
        }

        void impact(float r) {
            if (impactAge >= 0) return;
            impactAge = age;
            impactR = r;
            Vec3 c = target;
            FxManager.lod = Math.max(0.3f, FxManager.lodAt(c));
            spawn(sp(Sprite.Kind.GLOW, c.add(0, 1.5, 0), Vec3.ZERO, 16f, 24f, 7).color(1f, 0.98f, 0.92f, 1f));
            spawn(sp(Sprite.Kind.GLOW, c.add(0, 1, 0), Vec3.ZERO, r * 4f, r * 6f, 16).color(1f, 0.72f, 0.2f, 0.8f));
            spawn(sp(Sprite.Kind.GLOW, c.add(0, 1, 0), Vec3.ZERO, r * 3f, r * 5f, 14).color(0.62f, 0.32f, 1f, 0.4f));
            // 돔·충격파 벽·가장 밝은 고리 모두 피해 반지름 r 에서 멈춤 (하늘의 옅은 고리만 더 넓음)
            ThunderParts.addBlast(new ThunderParts.Blast(c.add(0, 0.5, 0), r * 0.95f, 2.0f, 24, 5));
            ThunderParts.addBlast(new ThunderParts.Blast(c.add(0, 0.5, 0), r * 0.55f, 1.5f, 16, 2));
            ThunderParts.addPillar(new ThunderParts.Pillar(c, Math.max(1.2f, r * 0.3f), 46f, 18));
            ThunderParts.addWall(ThunderParts.ShockWall.ring(c, r * 0.3f, r, 5.2f, 12, 0));
            ThunderParts.addWall(ThunderParts.ShockWall.ring(c, r * 0.2f, r * 0.7f, 2.8f, 10, 2));
            ThunderParts.addEdge(c, r, 4, 0, 1f, caster());
            Vec3 rc = c.add(0, 0.07, 0);
            FxManager.addRing(rc, UP, 1f, r * ThunderParts.RK, 12, 0, 1f, EL);
            FxManager.addRing(rc, UP, 0.6f, r * 0.7f, 10, 2, 0.9f, EL);
            FxManager.addRing(c.add(0, 3, 0), UP, 0.5f, r * 1.1f, 11, 1, 0.6f, EL);
            FxManager.addRing(c.add(0, 8, 0), UP, 0.5f, r * 1.6f, 12, 2, 0.35f, EL);
            ThunderParts.addMark(new ThunderParts.Mark(CRACKS, c, UP, r * 1.1f, 220, 0, 5f, 0f, 1f, GOLD));
            ThunderParts.addMark(new ThunderParts.Mark(RUNE, c, UP, r, 22, 0, 0f, 0.02f, 1f, PALE));
            SkillFx.addDecal(c, UP, r * 0.95f, 260, 0.5f, 0.42f, 0.34f);
            // 하늘에서 내리꽂는 번개
            arc(c.add(0, 42, 0), c, 4.5f, 14, 0, 6, 3, 0.15f, false, 0.3f);
            for (int i = 0; i < 4; i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 top = polar(c, ang, r * rand(0.6f, 1.4f), 34 + rand(0f, 8f));
                arc(top, polar(c, ang, r * rand(0.1f, 0.5f), 0), 2.0f, 10, i * 2, 5, 2, 0.2f, false, 0.4f);
            }
            int ng = Math.max(8, count(16));
            float base = RNG.nextFloat() * TAU;
            for (int i = 0; i < ng; i++) {
                double a = base + i * TAU / ng + rand(-0.15f, 0.15f);
                arc(polar(c, a, r * 0.25, 0.15), polar(c, a, r * rand(0.8f, 1.0f), 0.12), 0.8f, 7 + RNG.nextInt(4), RNG.nextInt(3), 5, 2, 0.22f, true, 0.35f);
            }
            for (int i = 0; i < count(130); i++) {
                Vec3 d = new Vec3(rand(-1f, 1f), rand(0.2f, 1.5f), rand(-1f, 1f)).normalize();
                spawn(sp(Sprite.Kind.SPARK, c.add(0, 0.4, 0), d.scale(rand(0.7f, 2.0f)), 0.15f, 0.15f, 10 + RNG.nextInt(12))
                        .color(1f, rand(0.75f, 0.92f), 0.4f, 1f).physics(0.05f, 0.92f));
            }
            for (int i = 0; i < count(50); i++) {
                float ang = RNG.nextFloat() * TAU;
                Vec3 d = new Vec3(Math.cos(ang), rand(0.05f, 0.9f), Math.sin(ang));
                Sprite f = sp(Sprite.Kind.FLAME, c.add(d.scale(r * 0.3)).add(0, 0.6, 0), d.scale(rand(0.2f, 0.5f)), rand(2f, 3f), rand(3.5f, 5f), 9 + RNG.nextInt(8))
                        .color(1f, rand(0.75f, 0.9f), 0.45f, 1f).physics(-0.006f, 0.86f);
                f.frameOffset = RNG.nextInt(64);
                f.rot = RNG.nextFloat() * TAU;
                spawn(f);
            }
            dust(c, r * 1.3f, 26, 1.6f);
            DebrisFx.burst(c, r, 90, 1.5f);
            DebrisFx.crater(c, r, 320, EL);
            double d = distToPlayer(c);
            if (d < 48) ThunderParts.flash(0xFFF4D0, 0.5f + 0.5f * (float) (1 - d / 48), 24);
            if (d < 72) CameraShake.add((float) (6.5 * (1 - d / 72)));
        }

        void aftershock(int n, float rr) {
            Vec3 c = target;
            shocks = Math.max(shocks, n);
            FxManager.lod = FxManager.lodAt(c);
            // 여진 반지름 rr (서버: r × (1 + 0.3n)) 에서 정확히 멈춤
            ThunderParts.addWall(ThunderParts.ShockWall.ring(c, rr * 0.35f, rr, 2.2f + 0.3f * n, 9, 0));
            ThunderParts.addEdge(c, rr, 4, 0, 1f, caster());
            FxManager.addRing(c.add(0, 0.07, 0), UP, rr * 0.3f, rr * ThunderParts.RK, 9, 0, 0.95f, EL);
            FxManager.addRing(c.add(0, 1.2, 0), UP, rr * 0.2f, rr * 0.8f, 8, 2, 0.6f, EL);
            ThunderParts.addMark(new ThunderParts.Mark(CRACKS, c, UP, impactR * 1.1f, 30, 0, 0f, 0f, 0.6f, GOLD));
            spawn(sp(Sprite.Kind.GLOW, c.add(0, 1, 0), Vec3.ZERO, rr * 2f, rr * 3f, 8).color(1f, 0.75f, 0.3f, 0.5f));
            int nb = Math.max(5, count(8 + 2 * n));
            float base = RNG.nextFloat() * TAU;
            for (int i = 0; i < nb; i++) {
                double a = base + i * TAU / nb;
                Vec3 s0 = polar(c, a, rr * rand(0.85f, 1.05f), 0.1);
                arc(s0, s0.add(rand(-0.8f, 0.8f), rand(2f, 4.5f), rand(-0.8f, 0.8f)), 0.6f, 5, RNG.nextInt(3), 4, 1, 0.26f, false, 0.4f);
            }
            for (int i = 0; i < count(30); i++) {
                float ang = RNG.nextFloat() * TAU;
                spawn(sp(Sprite.Kind.SPARK, polar(c, ang, rr * rand(0.7f, 1.05f), 0.2), new Vec3(Math.cos(ang) * 0.2, rand(0.3f, 0.7f), Math.sin(ang) * 0.2),
                        0.11f, 0.11f, 8 + RNG.nextInt(7)).color(1f, 0.82f, 0.4f, 1f).physics(0.045f, 0.92f));
            }
            dust(c, rr, 8, 1.0f);
            DebrisFx.burst(c, rr, 24, 0.6f + 0.1f * n);
            double d = distToPlayer(c);
            if (d < rr + 24) CameraShake.add((float) ((2.6 - 0.3 * n) * (1 - d / (rr + 24))));
            endAge = Math.max(endAge, age + (n >= AFTERSHOCKS ? 50 : 60));
        }

        /** 반투명 단계: 도는 먹구름 (금빛 번쩍임이 안쪽에서 밝힘) */
        void renderCloud(BufferBuilder bb, float pt, float time) {
            float t = age + pt;
            float f = fade(t);
            if (f <= 0.01f) return;
            for (int i = 0; i < PUFFS; i++) {
                Vec3 pos = puffPos(i, t);
                float lit = 0f;
                for (int j = 0; j < FLASHES; j++) {
                    if (flashAge[j] >= flashLife[j] || flashPos[j] == null) continue;
                    double dd = flashPos[j].distanceTo(pos);
                    if (dd < 10) lit += (float) (1 - dd / 10) * (1f - (float) flashAge[j] / flashLife[j]);
                }
                if (impactAge >= 0) {
                    float since = t - impactAge;
                    if (since < 10) lit += 1f - since / 10f;
                }
                lit = Math.min(1f, lit);
                float sh = pshade[i];
                float r = 0.15f * sh + 0.75f * lit, g = 0.12f * sh + 0.52f * lit, b = 0.2f * sh + 0.2f * lit;
                float size = ps[i] * (0.6f + 0.4f * clamp01(t / 20f));
                int fr = pf[i];
                float u0 = (fr & 7) / 8f, v0 = (fr >> 3) / 8f;
                FxRender.billboard(bb, pos, size, prot[i] + time * 0.004f, u0, v0, u0 + 0.125f, v0 + 0.125f, r, g, b, 0.85f * f);
            }
        }

        /** 목표 바닥의 거대한 룬 원 */
        void renderGround(float pt, float time) {
            float t = age + pt;
            float a;
            float rad;
            if (impactAge < 0) {
                // 경계(= 실제 반지름 R)는 처음부터 또렷하게, 룬과 차오르는 앞머리는 내려찍는 순간 경계에 닿음
                float k = form(t);
                float hitK = clamp01((t + 1f) / (rise + SLAM));
                rad = R * (0.2f + 0.8f * easeOutCubic(hitK));
                a = 0.35f + 0.6f * k + (t > rise ? 0.25f : 0f);
                // 판정 범위 R: 내려꽂는 틱에 가득 차도록 차오름
                SkillFx.dangerZone(target, R, hitK, EL, caster(), 1f, time);
                FxRender.additive();
                RenderSystem.disableCull();
            } else {
                float since = t - impactAge;
                // 다음 여진이 닿을 범위 (서버: R × (1 + 0.3n), 10틱마다) — 다가올수록 밝아짐
                int next = shocks + 1;
                if (next <= AFTERSHOCKS && since < AFTER_GAP * AFTERSHOCKS + 2) {
                    float p = clamp01((since - AFTER_GAP * (next - 1)) / AFTER_GAP);
                    SkillFx.dangerZone(target, R * (1f + AFTER_GROW * next), p, EL, caster(), 0.35f + 0.65f * p, time);
                    FxRender.additive();
                    RenderSystem.disableCull();
                }
                // 여운: 룬은 경계 안쪽(0.8R)에서 빨리 꺼짐 — 판정 경계처럼 읽히지 않게
                if (since > 14) return;
                rad = R * 0.8f;
                a = 1.1f * (1f - since / 14f);
            }
            renderRune(target, rad, Math.min(1.2f, a), time * (impactAge < 0 ? 1f : 0.5f), 1f);
            // 룬 원 둘레에서 솟는 빛 띠
            if (impactAge < 0) {
                FxRender.elemShader(EL, 2.6f, 0.35f, 0.5f + 0.5f * form(t));
                BufferBuilder bb = FxRender.quads();
                ThunderParts.wall(bb, target.add(0, 0.05, 0), rad, 1.2f + 1.2f * form(t), 0f, TAU, 0.6f, ThunderParts.segs(rad, TAU), time * 0.02f);
                FxRender.draw(bb);
            }
        }

        void render(float pt, float time) {
            float t = age + pt;
            // 시전자를 감싼 금빛 기운 (솟구치는 동안)
            if (impactAge < 0 && t <= rise + SLAM) {
                Vec3 p = casterPos(pt);
                float k = form(t);
                FxRender.elemShader(EL, 3f, 0.3f, 1.0f);
                SkillFx.column(p.add(0, -0.6, 0), 0.9f, 0.6f, 3.2f, 0.55f + 0.35f * k, 0.6f, time * 0.02f, 0.1f, time);
                if (t <= rise) {
                    // 땅에서 시전자까지 이어진 빛줄기
                    float h = (float) Math.max(0.5, p.y - start.y);
                    FxRender.elemShader(EL, 3.5f, 0.2f + 0.6f * clamp01(t / rise), 1.1f);
                    SkillFx.column(start, 0.45f, 0.3f, h, 0.7f * (1f - 0.6f * clamp01(t / rise)), 0.4f, 0.3f, 0.05f, time);
                }
            }
            // 거대한 빛의 망치
            float ero, inten;
            if (impactAge < 0) {
                if (t <= rise) {
                    float k = form(t);
                    ero = 0.92f * (1f - k);
                    inten = 0.5f + 0.9f * k;
                } else {
                    ero = 0.02f;
                    inten = 1.5f;
                }
            } else {
                float since = t - impactAge;
                if (since > 20) return;
                ero = 0.03f + 0.97f * clamp01((since - 3f) / 17f);
                inten = 1.6f - 0.6f * clamp01(since / 20f);
            }
            float scale = hs * (impactAge < 0 && t <= rise ? 0.6f + 0.4f * form(t) : 1f);
            Vec3 Y = impactAge >= 0 ? UP.scale(-1) : hammerY(t);
            Vec3 X = impactAge >= 0 ? hf.scale(-1) : hf.scale(Math.cos(Math.acos(Math.max(-1, Math.min(1, Y.dot(UP)))))).subtract(UP.scale(Math.sin(Math.acos(Math.max(-1, Math.min(1, Y.dot(UP)))))));
            Vec3 head = hammerHead(t, pt);
            // 내려꽂는 동안 잔상
            if (impactAge < 0 && t > rise && t < rise + SLAM + 1) {
                for (int k = 1; k <= 2; k++) {
                    float tk = t - k * 0.8f;
                    ThunderHammer.draw(hammerHead(tk, pt), hammerY(tk), X, scale, ThunderHammer.HEAD_Y, 0.3f + 0.2f * k, 0.7f - 0.2f * k, k * 0.3f, false);
                }
            }
            ThunderHammer.draw(head, Y, X, scale, ThunderHammer.HEAD_Y, ero, inten, time * 0.025f, true);
            FxRender.additive();
        }

        void frameGlows(float pt) {
            float t = age + pt;
            for (int j = 0; j < FLASHES; j++) {
                if (flashAge[j] >= flashLife[j] || flashPos[j] == null) continue;
                float k = 1f - (flashAge[j] + pt) / flashLife[j];
                if (k > 0f) frameGlow(flashPos[j], flashSize[j], GOLD, 0.7f * k);
            }
            if (impactAge < 0) {
                Vec3 head = hammerHead(t, pt);
                float k = t <= rise ? form(t) : 1f;
                float fl = 0.9f + 0.1f * (float) Math.sin(t * 2.9f);
                frameGlow(head, hs * (3f + 2f * k) * fl, GOLD, 0.25f + 0.35f * k);
                frameGlow(head, hs * 1.2f, PALE, 0.3f + 0.4f * k);
                frameGlow(casterPos(pt).add(0, 1, 0), 4f + 2f * k, GOLD, 0.35f);
            } else {
                float since = t - impactAge;
                if (since < 14) frameGlow(target.add(0, 2, 0), R * 7f, PALE, 0.8f * (1f - since / 14f));
            }
        }
    }

    // =====================================================================
    // 기절: 머리 위를 도는 금빛 별
    // =====================================================================

    static final class StunFx {
        static final int STARS = 4;
        final int id;
        final Vec3 fallback;
        final float w, h;
        int life, age, crackle = 6;

        StunFx(int id, Vec3 pos, float w, float h, int life) {
            this.id = id;
            this.fallback = pos;
            this.w = w;
            this.h = h;
            this.life = life;
        }

        @Nullable
        Entity entity() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || id < 0) return null;
            Entity e = mc.level.getEntity(id);
            return e == null || e.isRemoved() ? null : e;
        }

        Vec3 feet(float pt) {
            Entity e = entity();
            return e != null ? e.getPosition(pt) : fallback;
        }

        float haloR() {
            return Math.max(0.35f, w * 0.65f);
        }

        Vec3 star(Vec3 head, int i, float time) {
            double a = time * 0.28 + i * TAU / STARS;
            float r = haloR();
            return head.add(Math.cos(a) * r, Math.sin(time * 0.31 + i * 1.7) * 0.07, Math.sin(a) * r);
        }

        boolean tick() {
            age++;
            if (age > life) return true;
            Entity e = entity();
            if (e == null && (id >= 0 || age > 40)) return true; // 사라졌거나 멀어짐
            FxManager.lod = FxManager.lodAt(feet(1f));
            if (age <= crackle) {
                if (e != null) bodyArcs(e, 2, 0.4f);
            } else if (e != null && RNG.nextFloat() < 0.1f) {
                bodyArcs(e, 1, 0.4f);
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && age % 3 == 0) {
                float time = (mc.level.getGameTime() % 24000L);
                Vec3 head = feet(1f).add(0, h + 0.35, 0);
                int i = RNG.nextInt(STARS);
                arc(star(head, i, time), star(head, (i + 1) % STARS, time), 0.16f, 2, 0, 2, 0, 0.3f, false, 0.4f);
            }
            if (RNG.nextFloat() < 0.3f * FxManager.amount()) {
                spawn(sp(Sprite.Kind.SPARK, feet(1f).add(0, h + 0.35, 0).add(randomDir().scale(haloR())), new Vec3(0, -0.05, 0), 0.05f, 0.05f, 6)
                        .color(1f, 0.85f, 0.4f, 1f).physics(0.01f, 0.9f));
            }
            return false;
        }
    }

    private static void renderStuns(float pt, float time) {
        FxRender.elemShader(EL, 3f, 0.3f, 1.0f);
        BufferBuilder bb = FxRender.quads();
        boolean any = false;
        for (StunFx s : STUNS) {
            float a = stunAlpha(s, pt);
            if (a <= 0.01f) continue;
            Vec3 head = s.feet(pt).add(0, s.h + 0.35, 0);
            float r = s.haloR();
            FxRender.ring(bb, head, UP, r * 0.82f, r * 1.12f, 0.75f * a, time * 0.6f, 24);
            any = true;
        }
        if (any) FxRender.draw(bb);
        else bb.build(); // 빈 버퍼 정리
        // 별: 두 갈래로 교차한 반짝이 + 후광
        FxRender.spriteShader(FxRender.SPARK_WHITE);
        bb = FxRender.quads();
        any = false;
        Vec3 L = new Vec3(FxRender.LEFT.x, FxRender.LEFT.y, FxRender.LEFT.z), U = new Vec3(FxRender.UP.x, FxRender.UP.y, FxRender.UP.z);
        for (StunFx s : STUNS) {
            float a = stunAlpha(s, pt);
            if (a <= 0.01f) continue;
            Vec3 head = s.feet(pt).add(0, s.h + 0.35, 0);
            for (int i = 0; i < StunFx.STARS; i++) {
                Vec3 p = s.star(head, i, time);
                float tw = 0.75f + 0.25f * (float) Math.sin(time * 1.3f + i * 2.1f);
                float len = 0.32f * tw;
                float al = a * tw;
                FxRender.streak(bb, p.subtract(L.scale(len)), p.add(L.scale(len)), 0.09f, al, al * 0.88f, al * 0.5f, al);
                FxRender.streak(bb, p.subtract(U.scale(len)), p.add(U.scale(len)), 0.09f, al, al * 0.88f, al * 0.5f, al);
                any = true;
            }
        }
        if (any) FxRender.draw(bb);
        else bb.build();
        for (StunFx s : STUNS) {
            float a = stunAlpha(s, pt);
            if (a <= 0.01f) continue;
            Vec3 head = s.feet(pt).add(0, s.h + 0.35, 0);
            for (int i = 0; i < StunFx.STARS; i++) {
                Vec3 p = s.star(head, i, time);
                frameGlow(p, 0.5f, GOLD, 0.7f * a);
                frameGlow(p, 0.18f, PALE, a);
            }
            frameGlow(head, s.haloR() * 3f, VIOLET, 0.12f * a);
        }
    }

    private static float stunAlpha(StunFx s, float pt) {
        float t = s.age + pt;
        float a = clamp01(t / 3f) * clamp01((s.life - t) / 6f);
        // 기절한 본인(1인칭): 머리 위 별이 올려다볼 때 시야를 덮지 않게 옅게
        Entity e = s.entity();
        return e != null && IceThunderAim.firstPersonSelf(e) ? a * 0.3f : a;
    }
}
