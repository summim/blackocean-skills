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
import net.minecraft.world.level.ClipContext;
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
 * 불 능력 연출 설계 (2.3). 이 파일은 5번 궁극기 "작열의 태양"을 그리고, 1~4번은 FxManager(주먹·탄환)·SkillFx(기둥·고리)가 그린다.
 * <p>
 * 불의 말투: 위로 말려 오르는 혀 모양 불길, 흰-노랑 핵 → 주황 → 짙은 빨강 가장자리, 어두운 연기로 대비,
 * 사라질 때는 연기가 피어오르고 불씨가 떠다니다 꺼진다. 무거운 것 = 주먹·태양, 가벼운 것 = 탄환.
 * 빛 번짐은 겹친 부드러운 후광(가짜 블룸), 바닥 빛은 바닥 빛 웅덩이 그림(가짜 조명)으로. 피해 범위는 전부 SkillFx.danger*.
 * <pre>
 * ┌ 1 화염 주먹 (무거움) ───────────────────────────────────────────────────────────
 * │ 정지 화면 실루엣 : 앞이 뭉툭한 3D 주먹 + 뒤로 길게 꼬리 치는 불꽃 혀, 그 뒤 나선 2가닥
 * │ 주된 형태       : fist_core/fist_shell 메시 (정면 흰빛 → 윤곽 빨강, 3D 라 어느 각도에서도 부피)
 * │ 보조 층         : 카메라를 향한 궤적 띠 + 궤적 단면 고리(앞·뒤에서 볼 때 띠 대신 보임) + 나선 띠(40~60% 밝기)
 * │                  + 잔상 메시 3개 + 떨어지는 불씨·연기, 아래 바닥 빛 웅덩이
 * │ 충격 모양       : 흰 핵 섬광 → 판정 반지름까지 부푸는 불덩이 구 + 땅 조각·금 간 자국 + 짧은 불기둥, 판정 끝 번쩍(≤4틱 정점)
 * │ 사라짐          : 바닥 잔불이 핥아 오르다 줄고, 연기 기둥이 피어오르며 불씨가 떠다니다 꺼짐 (테두리 없음)
 * │ 무거움 표현     : 3겹 고리가 조여 드는 긴 준비(0.4초), 손 앞 원뿔 고리 + 반동 불꽃, 낮게 울리는 비행음, 큰 흔들림·밀림
 * ├ 2 불꽃 탄환 (가벼움) ───────────────────────────────────────────────────────────
 * │ 실루엣 : 작은 불덩이 5발이 좌우 손에서 번갈아, 가늘고 짧은 꼬리
 * │ 주된 형태 : 꿈틀대는 작은 불덩이 구 (3D) / 보조 : 가는 궤적 띠 + 단면 고리 + 불씨 몇 개
 * │ 충격 : 작은 섬광 + 작은 폭발 구 + 작은 링, 흔들림 아주 약하게 / 사라짐 : 연기 한두 줌, 그을음 점
 * │ 가벼움 표현 : 준비가 짧고(0.2초) 손 고리만, 발사마다 작은 총구 섬광, 빠른 연사, 가벼운 파삭 소리
 * ├ 3 불기둥 ───────────────────────────────────────────────────────────────────────
 * │ 실루엣 : 바닥에서 하늘로 꼬이며 솟는 굵은 2겹 원통 (위로 갈수록 가늘어짐)
 * │ 주된 형태 : 꼬인 원통 메시 2겹 (바깥 빨강 · 안 흰 심지, 반대로 꼬임) — 원통이라 앞·옆·뒤 모두 같은 두께
 * │ 보조 : 바닥 불꽃 고리, 기둥을 따라 감아 오르는 불꽃, 꼭대기 불씨, 기둥을 감싼 후광
 * │ 예고 : dangerZone(판정 반지름) 이 0.55초 동안 차오름 + 테두리 불꽃이 굵어짐 + 막바지에 가운데 작은 불길
 * │ 충격 : 솟는 4틱 동안 흰 섬광 기둥 + 땅이 터짐 + 판정 끝 번쩍
 * │ 지속 피해 : 남아 태우는 동안 dangerZone(0.85r, 가득 참) — 서버가 0.5초마다 태우는 범위와 같음
 * │ 사라짐 : 기둥이 가라앉으며 노이즈로 갉아 먹혀 사라지고 연기·불씨만 남음 (테두리 없음)
 * ├ 4 화염 고리 ─────────────────────────────────────────────────────────────────────
 * │ 실루엣 : 몸을 자이로처럼 도는 불 고리 3개 → 바닥을 따라 퍼지는 낮은 불의 벽
 * │ 주된 형태 : 퍼지는 원통 벽 2겹 (원통이라 각도 무관) / 보조 : 바닥 불길 띠, 벽 앞머리 흰 띠, 튀는 불꽃
 * │ 예고 : 시전자를 따라다니는 dangerZone 이 준비 동안 차오름
 * │ 충격 : 벽이 퍼지는 5틱 = 서버 판정 시간. 그동안 dangerZone 의 채움이 벽 앞머리를 따라 판정 끝까지 → 끝 번쩍
 * │ 사라짐 : 벽이 낮아지며 타서 사라지고 바닥 연기 (테두리 없음)
 * ├ 5 작열의 태양 (궁극기, 가장 무거움) ───────────────────────────────────────────
 * │ 실루엣 : 머리 위 거대한 구 + 비스듬히 도는 코로나 고리 3개 + 표면에서 솟았다 떨어지는 홍염 고리
 * │ 주된 형태 : 이글거리는 핵 구 + 겉껍질 구 (3D 구라 어느 각도에서도 둥긂)
 * │ 보조 : 코로나·홍염 띠, 굵은 궤적 띠 + 단면 고리, 뒤로 흘리는 불꽃·비처럼 떨어지는 불씨·열기 연기,
 * │        아래 바닥 빛 웅덩이 (높이에 따라 크기·밝기)
 * │ 예고 : 비행 중 떨어질 자리에 dangerZone (폭발 구가 바닥과 만나는 원 = 실제로 맞는 범위), 다가갈수록 차오름
 * │ 충격 : 흰 섬광 핵 → 주 불덩이 → 바깥 껍질, 둘레 불기둥 7개(장식), 큰 충격파·땅 조각, 버섯 연기, 판정 끝 번쩍
 * │ 지속 피해 : 불바다 = dangerZone(불바다 반지름, 가득 참) 이 유지 시간 내내 (0.5초마다 피해)
 * │ 사라짐 : 버섯 연기가 위로 퍼지고 불바다 불길이 줄며 마지막 20틱에 위험 범위도 함께 사라짐
 * │ 무거움 표현 : 1.5초 준비(빨려 드는 불꽃·불씨·빛줄기), 느린 비행, 화면 번쩍·흔들림(제한기 거침), 낮은 폭음
 * └──────────────────────────────────────────────────────────────────────────────
 * </pre>
 */
final class FireFx {
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final float TAU = (float) (Math.PI * 2);

    /** 준비 태양 최대 반지름 / 비행 태양 반지름 */
    private static final float SUN_R = 2.2f;

    private static final List<SunBlast> BLASTS = new ArrayList<>();
    private static final List<FireField> FIELDS = new ArrayList<>();

    private FireFx() {}

    // =====================================================================
    // 계약
    // =====================================================================

    static void onEvent(SkillEventPayload e, Skill skill, @Nullable Entity caster) {
        if (skill != Skill.FIRE_SUN) return;
        if (e.kind() == SkillEvents.IMPACT) {
            if (BLASTS.size() >= 8) BLASTS.remove(0);
            BLASTS.add(new SunBlast(e.pos(), e.vec(), Math.max(2f, e.size()), e.size2() > 0.5f));
        } else if (e.kind() == SkillEvents.FIELD) {
            if (FIELDS.size() >= 16) FIELDS.remove(0);
            FireField f = new FireField(e.pos(), Math.max(1f, e.size()), Math.max(10, e.duration()));
            f.caster = caster;
            FIELDS.add(f);
        }
    }

    static void tick() {
        BLASTS.removeIf(SunBlast::tick);
        FIELDS.removeIf(FireField::tick);
    }

    static void renderAdditive(float pt, float time) {
        for (FireField f : FIELDS) f.render(pt, time);
        for (SunBlast b : BLASTS) b.render(pt, time);
    }

    static void renderAlpha(float pt, float time) {
        // 연기는 SMOKE 입자로 그려지므로 여기서 따로 그릴 것 없음
    }

    static boolean isEmpty() {
        return BLASTS.isEmpty() && FIELDS.isEmpty();
    }

    static void clear() {
        BLASTS.clear();
        FIELDS.clear();
    }

    // =====================================================================
    // 준비: 머리 위에 태양이 생김
    // =====================================================================

    /** 서버 발사 위치와 같은 머리 위 지점 (SkillAim.handPos lift 2.6 과 같은 계산, 고정된 조준 방향) */
    private static Vec3 sunAt(FxManager.ChargeFx c, float pt) {
        return c.origin(pt, 0, 2.6);
    }

    /** 준비 진행도 k 에 따른 태양 반지름 */
    private static float chargeRadius(float k) {
        float e = 0.5f * easeOutCubic(k) + 0.5f * smooth(k);
        return 0.3f + (SUN_R - 0.3f) * e;
    }

    static void tickCharge(FxManager.ChargeFx c, float k) {
        Entity caster = c.caster;
        if (caster == null || caster.isRemoved()) return;
        Vec3 sun = sunAt(c, 1f);
        float r = chargeRadius(k);

        // 사방에서 빨려 드는 불꽃 (4~7블록 밖에서 출발해 정확히 수명 끝에 표면에 닿음)
        for (int i = 0; i < count(2 + (int) (4 * k)); i++) {
            Vec3 d = randomDir();
            float dist = rand(4f, 7f);
            int life = 8 + RNG.nextInt(5);
            Vec3 at = sun.add(d.scale(dist));
            Vec3 v = d.scale(-(dist - r * 0.8f) / life);
            Sprite f = new Sprite(Sprite.Kind.FLAME, at, v, rand(1.0f, 1.6f), 0.35f, life).color(1f, 1f, 1f, 0.9f);
            f.frameOffset = RNG.nextInt(64);
            f.rot = RNG.nextFloat() * TAU;
            spawn(f);
        }
        for (int i = 0; i < count(4 + (int) (7 * k)); i++) {
            Vec3 d = randomDir();
            float dist = rand(4f, 7f);
            int life = 6 + RNG.nextInt(5);
            Vec3 v = d.scale(-(dist - r * 0.7f) / life);
            Sprite em = new Sprite(Sprite.Kind.EMBER, sun.add(d.scale(dist)), v, rand(0.16f, 0.26f), 0.06f, life)
                    .color(1f, rand(0.6f, 0.85f), 0.3f, 1f);
            em.frameOffset = RNG.nextInt(100);
            spawn(em);
        }
        // 빨려 드는 빛줄기 (속도로 늘어나 보이는 불꽃 줄기)
        if (k > 0.3f) {
            for (int i = 0; i < count(2 + (int) (3 * k)); i++) {
                Vec3 d = randomDir();
                float dist = rand(4.5f, 6.5f);
                int life = 5 + RNG.nextInt(3);
                spawn(new Sprite(Sprite.Kind.SPARK, sun.add(d.scale(dist)), d.scale(-(dist - r) / life), 0.12f, 0.12f, life)
                        .color(1f, 0.85f, 0.5f, 1f));
            }
        }
        // 표면에서 피어오르는 작은 불꽃
        for (int i = 0; i < count(1 + (int) (3 * k)); i++) {
            Vec3 d = randomDir();
            Sprite f = new Sprite(Sprite.Kind.FLAME, sun.add(d.scale(r * 0.95f)), d.scale(0.05).add(0, 0.04, 0),
                    0.6f + 0.9f * r * 0.5f, 0.3f, 6 + RNG.nextInt(4));
            f.frameOffset = RNG.nextInt(64);
            spawn(f);
        }

        // 끝: 시전자 주변이 따뜻하게 번쩍 (태양이 완성되는 순간)
        if (c.age == c.duration) {
            Vec3 body = caster.position().add(0, 1.0, 0);
            spawn(new Sprite(Sprite.Kind.GLOW, sun, Vec3.ZERO, 9f, 15f, 7).color(1f, 0.92f, 0.75f, 1f));
            spawn(new Sprite(Sprite.Kind.GLOW, body, Vec3.ZERO, 10f, 16f, 9).color(1f, 0.6f, 0.25f, 0.6f));
            FxManager.addRing(caster.position().add(0, 0.06, 0), UP, 0.6f, 7.5f, 12, 0, 0.9f, Element.FIRE);
            FxManager.addRing(sun, UP, r * 1.1f, r * 3.6f, 9, 0, 0.9f, Element.FIRE);
            // 던지는 방향으로 열리는 고리 (태양이 그쪽으로 날아감)
            Vec3 aim = c.aimDir();
            FxManager.addRing(sun.add(aim.scale(r * 1.2)), aim, r * 0.6f, r * 2.4f, 7, 0, 0.9f, 1f, 0.92f, 0.75f);
            for (int i = 0; i < count(30); i++) {
                Vec3 d = randomDir();
                spawn(new Sprite(Sprite.Kind.SPARK, sun.add(d.scale(r)), d.scale(rand(0.4f, 0.9f)), 0.13f, 0.13f, 8 + RNG.nextInt(6))
                        .color(1f, 0.85f, 0.5f, 1f).physics(0.03f, 0.9f));
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                double d = mc.player.position().distanceTo(caster.position());
                if (caster == mc.player) {
                    ScreenFx.flash(0xFFB060, 0.35f, 10);
                    ScreenFx.whiteFlash(0.25f, 3);
                    CameraShake.add(1.6f);
                    CameraShake.kick(-1.2f, 0f);
                } else if (d < 24) {
                    ScreenFx.flash(0xFFB060, (float) (0.25 * (1 - d / 24)), 10);
                    CameraShake.add((float) (1.0 * (1 - d / 24)));
                }
            }
        }
    }

    static void renderCharge(FxManager.ChargeFx c, float pt, float k, float time) {
        Entity caster = c.caster;
        if (caster == null || caster.isRemoved()) return;
        Vec3 sun = sunAt(c, pt);
        float pulse = 1f + 0.04f * (float) Math.sin(time * (1.5f + 3f * k));
        float r = chargeRadius(k) * pulse;
        Minecraft mc = Minecraft.getInstance();
        boolean self1st = caster == mc.player && mc.options.getCameraType().isFirstPerson();

        RenderSystem.disableCull();
        // 핵: 점점 강해지는 불덩이
        FxRender.elemShader(Element.FIRE, 1.6f + 0.8f * k, 0.0f, 1.15f + 0.4f * k);
        FxManager.BlastSphere.draw(sun, r, time * 2.2f);

        // 코로나 고리 (점점 나타남)
        float ringA = smooth(k * 2.2f);
        if (ringA > 0.01f) {
            FxRender.elemShader(Element.FIRE, 1.8f, 0.08f, 0.9f + 0.4f * k);
            BufferBuilder bb = FxRender.quads();
            corona(bb, sun, r, ringA, time, 0.5f + 0.9f * k);
            FxRender.draw(bb);
        }

        // 홍염 (반쯤 모였을 때부터)
        if (k > 0.3f) {
            FxRender.elemShader(Element.FIRE, 2.4f, 0.12f, 1.25f);
            BufferBuilder bb = FxRender.quads();
            flares(bb, sun, r, time, 3, smooth((k - 0.3f) / 0.4f), 0);
            FxRender.draw(bb);
        }

        // 시전자 발밑에 비치는 따뜻한 빛
        FxRender.spriteShader(FxRender.GLOW_WHITE);
        BufferBuilder gb = FxRender.quads();
        float ga = 0.12f + 0.3f * k;
        FxRender.planeQuad(gb, caster.getPosition(pt).add(0, 0.05, 0), UP, 3f + 4f * k, 0f, ga, ga * 0.5f, ga * 0.16f, ga);
        FxRender.draw(gb);

        // 후광
        float hs = self1st ? 0.55f : 1f;
        frameGlow(sun, r * 2.2f, 1f, 0.9f, 0.7f, (0.25f + 0.35f * k) * hs);
        frameGlow(sun, r * 4.2f + 2f * k, 1f, 0.5f, 0.15f, (0.2f + 0.3f * k) * hs);
    }

    // =====================================================================
    // 비행
    // =====================================================================

    static void trackProjectile(FxManager.ProjectileFx p, Vec3 pos) {
        predictLanding(p, pos);
        if (p.age % 2 == 1 || p.groundAt == null) p.groundAt = groundBelow(pos, 18.0);
        Vec3 back = p.dir.scale(-1);
        // 뒤로 흘러내리는 불꽃
        for (int i = 0; i < count(6); i++) {
            Vec3 d = randomDir();
            if (d.dot(back) < 0) d = d.subtract(back.scale(1.6 * d.dot(back))).normalize();
            Vec3 at = pos.add(d.scale(SUN_R * rand(0.85f, 1.1f)));
            Sprite f = new Sprite(Sprite.Kind.FLAME, at, back.scale(rand(0.1f, 0.22f)).add(d.scale(0.04)).add(0, 0.03, 0),
                    rand(1.8f, 2.6f), rand(0.5f, 0.9f), 8 + RNG.nextInt(6)).physics(-0.006f, 0.9f);
            f.frameOffset = RNG.nextInt(64);
            f.rot = RNG.nextFloat() * TAU;
            spawn(f);
        }
        // 비처럼 떨어지는 불씨
        for (int i = 0; i < count(5); i++) {
            Vec3 at = pos.add(randomDir().scale(SUN_R * rand(0.8f, 1.2f)));
            Sprite em = new Sprite(Sprite.Kind.EMBER, at, back.scale(rand(0.05f, 0.15f)).add(rand(-0.04f, 0.04f), -rand(0.02f, 0.08f), rand(-0.04f, 0.04f)),
                    rand(0.16f, 0.26f), 0.05f, 18 + RNG.nextInt(14))
                    .color(1f, rand(0.5f, 0.8f), 0.22f, 1f).physics(0.008f, 0.97f);
            em.sway = 0.004f;
            em.frameOffset = RNG.nextInt(100);
            spawn(em);
        }
        // 튀는 불꽃 줄기
        for (int i = 0; i < count(2); i++) {
            Vec3 d = randomDir();
            spawn(new Sprite(Sprite.Kind.SPARK, pos.add(d.scale(SUN_R)), d.scale(rand(0.2f, 0.5f)).add(back.scale(0.3)), 0.12f, 0.12f, 6 + RNG.nextInt(5))
                    .color(1f, 0.8f, 0.42f, 1f).physics(0.03f, 0.9f));
        }
        // 열기 연기
        for (int i = 0; i < count(2); i++) {
            Vec3 at = pos.add(back.scale(rand(2.4f, 3.6f))).add(randomDir().scale(1.2));
            Sprite sm = new Sprite(Sprite.Kind.SMOKE, at, back.scale(0.03).add(0, rand(0.03f, 0.06f), 0), rand(2.0f, 2.8f), rand(4.5f, 6f), 24 + RNG.nextInt(12))
                    .color(0.2f, 0.17f, 0.15f, 0.42f).physics(0f, 0.96f);
            sm.rot = RNG.nextFloat() * TAU;
            spawn(sm);
        }
    }

    /**
     * 태양이 떨어질 자리: 날아가는 직선을 남은 사거리만큼 검사 → 맞는 곳의 폭발 구가 바닥과 만나는 원 (= 실제로 맞는 범위).
     * 공중에서 끝나 바닥까지 폭발이 안 닿으면 예고 없음.
     */
    private static void predictLanding(FxManager.ProjectileFx p, Vec3 pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        float R = com.blackocean.skills.client.ClientSkillStats.radius(Skill.FIRE_SUN);
        float range = com.blackocean.skills.client.ClientSkillStats.range(Skill.FIRE_SUN);
        double traveled = p.origin.distanceTo(pos);
        double left = Math.max(0.5, range - traveled);
        Vec3 end = pos.add(p.dir.scale(left));
        BlockHitResult hit = SubLevels.clip(mc.level, new ClipContext(pos, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        Vec3 at = hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
        Vec3 ground;
        if (hit.getType() != HitResult.Type.MISS && hit.getDirection() == net.minecraft.core.Direction.UP) ground = at;
        else ground = groundBelow(at.add(0, 0.3, 0), R + 1.0);
        if (ground == null) {
            p.landAt = null;
            return;
        }
        double h = Math.max(0.0, at.y + 0.25 - ground.y);
        if (h >= R) {
            p.landAt = null;
            return;
        }
        p.landAt = ground;
        p.landR = (float) Math.sqrt(R * R - h * h);
        double toGo = pos.distanceTo(at);
        p.landFill = (float) clamp01((float) (traveled / Math.max(0.5, traveled + toGo)));
    }

    static void renderProjectile(FxManager.ProjectileFx p, Vec3 c, float pt, float time) {
        if (p.landAt != null) SkillFx.dangerZone(p.landAt, p.landR, p.landFill, Element.FIRE, p.proj.getOwner(), 1f, time);
        float grow = clamp01((p.age + pt) / 3f);
        float r = SUN_R * (0.88f + 0.12f * grow) * (1f + 0.035f * (float) Math.sin(time * 2.3f));
        boolean near = c.distanceToSqr(FxRender.cam) < 96 * 96;
        // 바닥 빛 웅덩이 (가짜 조명): 태양이 낮을수록 작고 진하게
        if (p.groundAt != null) {
            double hgt = c.y - p.groundAt.y;
            if (hgt > -1.0 && hgt < 18.0) {
                float k = 1f - (float) Math.max(0.0, hgt) / 18f;
                float ga = 0.5f * k;
                FxRender.spriteShader(FxRender.GLOW_WHITE);
                BufferBuilder gb = FxRender.quads();
                Vec3 gp = new Vec3(c.x, p.groundAt.y + 0.07, c.z);
                FxRender.planeQuad(gb, gp, UP, 3.5f + 5f * (1f - k), 0f, ga, ga * 0.5f, ga * 0.15f, ga);
                FxRender.planeQuad(gb, gp.add(0, 0.01, 0), UP, 1.6f + 2f * (1f - k), 0f, ga * 0.6f, ga * 0.5f, ga * 0.3f, ga * 0.6f);
                FxRender.draw(gb);
            }
        }

        RenderSystem.disableCull();
        // 핵: 강하게 이글거리는 구
        FxRender.elemShader(Element.FIRE, 2.0f, 0.0f, 1.45f);
        FxManager.BlastSphere.draw(c, r, time * 2.6f);
        // 겉껍질: 조금 크고 노이즈로 갉아 먹혀 흩어지는 층 (멀면 생략)
        if (near) {
            FxRender.elemShader(Element.FIRE, 2.8f, 0.3f + 0.05f * (float) Math.sin(time * 0.7f), 0.95f);
            FxManager.BlastSphere.draw(c, r * 1.22f, time * 3.4f + 50f);
        }

        FxRender.elemShader(Element.FIRE, 1.9f, 0.06f, 1.2f);
        BufferBuilder bb = FxRender.quads();
        corona(bb, c, r, 1f, time, 1.3f);
        FxRender.draw(bb);

        FxRender.elemShader(Element.FIRE, 2.4f, 0.12f, 1.3f);
        bb = FxRender.quads();
        flares(bb, c, r, time, near ? 4 : 2, 1f, 17);
        FxRender.draw(bb);

        frameGlow(c, r * 2.3f, 1f, 0.92f, 0.75f, 0.42f);
        frameGlow(c, 9f, 1f, 0.5f, 0.15f, 0.38f + 0.05f * (float) Math.sin(time * 3.1f));
        frameGlow(c, 15f, 1f, 0.35f, 0.08f, 0.16f);
    }

    // =====================================================================
    // 공통 도형
    // =====================================================================

    /** 태양 둘레를 비스듬히 돌며 기울기도 바뀌는 코로나 고리 3개 */
    private static void corona(BufferBuilder bb, Vec3 c, float r, float alpha, float time, float speed) {
        float[] tilts = {0.35f, 1.1f, 1.45f};
        float[] rIn = {1.32f, 1.58f, 1.9f};
        float[] rOut = {1.48f, 1.7f, 1.98f};
        float[] heat = {0.95f, 0.8f, 0.6f};
        for (int i = 0; i < 3; i++) {
            float ph = time * 0.06f * speed * (i == 1 ? -1f : 1f) + i * 2.1f;
            Vec3 n = new Vec3(Math.sin(tilts[i]) * Math.cos(ph), Math.cos(tilts[i]), Math.sin(tilts[i]) * Math.sin(ph));
            FxRender.ring(bb, c, n, r * rIn[i], r * rOut[i], heat[i] * alpha, time * (0.35f + 0.25f * i) * speed * (i == 2 ? -1f : 1f), 48);
        }
    }

    /**
     * 표면에서 솟았다가 다시 표면으로 떨어지는 홍염 고리 (태양 플레어).
     * 시간으로만 계산 (상태 없음): 칸마다 주기적으로 새 자리에서 피어남.
     */
    private static void flares(BufferBuilder bb, Vec3 c, float r, float time, int slots, float strength, int salt) {
        if (strength <= 0.01f) return;
        for (int i = 0; i < slots; i++) {
            float period = 13f + 4f * i;
            float tt = time + i * 5.3f;
            int cycle = (int) Math.floor(tt / period);
            float local = (tt - cycle * period) / period;
            int seed = cycle * 7919 + i * 131 + salt * 31;
            Vec3 a = hashDir(seed);
            Vec3 ref = Math.abs(a.y) < 0.9 ? UP : new Vec3(1, 0, 0);
            Vec3 tan = a.cross(ref).normalize();
            float rotA = hash(seed + 3) * TAU;
            Vec3 tan2 = a.cross(tan);
            Vec3 t = tan.scale(Math.cos(rotA)).add(tan2.scale(Math.sin(rotA)));
            Vec3 b = a.add(t.scale(0.55 + 0.45 * hash(seed + 5))).normalize();
            float rise = easeOutCubic(local * 1.6f);
            float fade = (float) Math.sin(Math.PI * local);
            float lift = (0.35f + 0.45f * hash(seed + 7)) * rise;
            arc(bb, c, r * 0.96f, a, b, lift, r * 0.22f * (0.6f + 0.4f * fade), 0.95f * fade * strength, local * 2f + i * 0.37f);
        }
    }

    /** 구 표면의 두 방향 a→b 를 잇는, 바깥으로 부푼 띠 (카메라를 향함, 가운데 줄이 가장 뜨거움) */
    private static void arc(BufferBuilder bb, Vec3 c, float R, Vec3 a, Vec3 b, float lift, float width, float heat, float uvOff) {
        if (heat <= 0.01f) return;
        final int n = 12;
        Vec3 prev = null;
        float prevW = 0f, prevH = 0f, prevV = 0f;
        for (int s = 0; s <= n; s++) {
            float f = (float) s / n;
            float bump = (float) Math.sin(Math.PI * f);
            Vec3 dir = a.scale(1 - f).add(b.scale(f)).normalize();
            Vec3 p = c.add(dir.scale(R * (1f + lift * bump)));
            float w = width * (0.35f + 0.65f * bump);
            float h = heat * (0.4f + 0.6f * bump);
            float v = uvOff + f * 1.4f;
            if (prev != null) {
                Vec3 sa = side(prev, p, prevW), sb = side(prev, p, w);
                FxRender.vertex(bb, prev.x - sa.x, prev.y - sa.y, prev.z - sa.z, 0f, prevV, 1, 1, 1, 0f);
                FxRender.vertex(bb, prev.x, prev.y, prev.z, 0.5f, prevV, 1, 1, 1, prevH);
                FxRender.vertex(bb, p.x, p.y, p.z, 0.5f, v, 1, 1, 1, h);
                FxRender.vertex(bb, p.x - sb.x, p.y - sb.y, p.z - sb.z, 0f, v, 1, 1, 1, 0f);
                FxRender.vertex(bb, prev.x, prev.y, prev.z, 0.5f, prevV, 1, 1, 1, prevH);
                FxRender.vertex(bb, prev.x + sa.x, prev.y + sa.y, prev.z + sa.z, 1f, prevV, 1, 1, 1, 0f);
                FxRender.vertex(bb, p.x + sb.x, p.y + sb.y, p.z + sb.z, 1f, v, 1, 1, 1, 0f);
                FxRender.vertex(bb, p.x, p.y, p.z, 0.5f, v, 1, 1, 1, h);
            }
            prev = p;
            prevW = w;
            prevH = h;
            prevV = v;
        }
    }

    private static Vec3 side(Vec3 a, Vec3 b, float w) {
        Vec3 d = b.subtract(a);
        Vec3 s = d.cross(FxRender.cam.subtract(a));
        if (s.lengthSqr() < 1.0E-8) return Vec3.ZERO;
        return s.normalize().scale(w * 0.5);
    }

    private static float hash(int n) {
        n = (n << 13) ^ n;
        n = n * (n * n * 15731 + 789221) + 1376312589;
        return (n & 0x7fffffff) / (float) 0x7fffffff;
    }

    private static Vec3 hashDir(int seed) {
        double u = hash(seed) * 2.0 - 1.0, th = hash(seed + 1) * Math.PI * 2;
        double s = Math.sqrt(Math.max(0.0, 1 - u * u));
        return new Vec3(s * Math.cos(th), u, s * Math.sin(th));
    }

    private static void frameGlow(Vec3 at, float size, float r, float g, float b, float a) {
        if (a <= 0.005f) return;
        FxManager.frameGlow(at, size, r, g, b, a);
    }

    /** 아래쪽 바닥 찾기 (없으면 null). 배 위 좌표도 맞도록 SubLevels.clip 사용 */
    @Nullable
    private static Vec3 groundBelow(Vec3 from, double depth) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        BlockHitResult hit = SubLevels.clip(mc.level, new ClipContext(from, from.subtract(0, depth, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS ? null : hit.getLocation();
    }

    // =====================================================================
    // 적중: 거대한 폭발
    // =====================================================================

    static final class SunBlast {
        static final int LIFE = 52;
        final Vec3 pos;
        final float size;
        /** 바닥 (없으면 null → 불기둥·그을음 없음) */
        @Nullable final Vec3 ground;
        /** 폭발 구가 바닥과 만나는 원의 반지름 (= 바닥에서 맞는 범위) */
        float footR;
        final float[] pillarAng = new float[7];
        final int[] pillarDelay = new int[7];
        int age;

        SunBlast(Vec3 pos, Vec3 normal, float size, boolean air) {
            this.pos = pos;
            this.size = size;
            Vec3 n = normal.lengthSqr() > 0.25 ? normal.normalize() : UP;
            Vec3 g = n.y > 0.7 && !air ? pos : groundBelow(pos.add(0, 0.5, 0), size * 1.2 + 1.0);
            this.ground = g;
            float a0 = RNG.nextFloat() * TAU;
            for (int i = 0; i < pillarAng.length; i++) {
                pillarAng[i] = a0 + TAU * i / pillarAng.length + rand(-0.2f, 0.2f);
                pillarDelay[i] = 1 + i + RNG.nextInt(2);
            }
            FxManager.lod = FxManager.lodAt(pos);

            // 흰빛 섬광 + 넓은 주황 번짐
            spawn(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, size * 0.8f, size * 3.2f, 4).color(1f, 1f, 1f, 1f));
            spawn(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, size * 1.6f, size * 2.4f, 6).color(1f, 0.97f, 0.88f, 1f));
            spawn(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, size * 2.0f, size * 2.6f, 14).color(1f, 0.55f, 0.18f, 0.8f));
            spawn(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, size * 2.4f, size * 3f, 18).color(1f, 0.35f, 0.08f, 0.3f));

            // 충격파 링: 흰빛(판정 끝을 살짝 넘음) → 판정 끝 → 안쪽. 바닥 원의 끝 = 실제로 맞는 범위
            Vec3 rc = (g != null ? g : pos).add(0, 0.1, 0);
            this.footR = g != null ? (float) Math.sqrt(Math.max(0.0, size * size - Math.pow(Math.max(0.0, pos.y - g.y), 2))) : size;
            float fr = Math.max(1f, footR);
            FxManager.addRing(rc, UP, 0.6f, fr * 1.15f, 6, 0, 0.95f, 1f, 0.95f, 0.82f);
            FxManager.addRing(rc, UP, 1.0f, fr, 12, 0, 1f, Element.FIRE);
            FxManager.addRing(rc, UP, 0.8f, fr * 0.6f, 10, 2, 0.9f, Element.FIRE);
            FxManager.addRing(pos, UP, 0.8f, size * 0.9f, 10, 1, 0.8f, Element.FIRE);
            Vec3 tilt = new Vec3(rand(-1f, 1f), 0.25, rand(-1f, 1f)).normalize();
            FxManager.addRing(pos, tilt, 0.6f, size * 0.8f, 9, 1, 0.7f, Element.FIRE);
            if (Math.abs(n.y) < 0.7) FxManager.addRing(pos.add(n.scale(0.1)), n, 0.6f, size, 10, 0, 0.8f, Element.FIRE);

            // 사방으로 튀는 불꽃 줄기 (수백 개)
            for (int i = 0; i < count(220); i++) {
                Vec3 d = randomDir().add(0, 0.25, 0).normalize();
                spawn(new Sprite(Sprite.Kind.SPARK, pos.add(d.scale(rand(0.3f, 1.5f))), d.scale(rand(0.7f, 2.0f)), rand(0.13f, 0.2f), 0.13f, 10 + RNG.nextInt(14))
                        .color(1f, rand(0.75f, 0.9f), rand(0.35f, 0.55f), 1f).physics(0.045f, 0.91f));
            }
            // 터져 나가는 불꽃 덩어리
            for (int i = 0; i < count(90); i++) {
                Vec3 d = randomDir().add(0, 0.3, 0).normalize();
                Sprite f = new Sprite(Sprite.Kind.FLAME, pos.add(d.scale(rand(0.5f, size * 0.3f))), d.scale(rand(0.25f, 0.6f)),
                        rand(2.4f, 3.6f), rand(3.6f, 5.2f), 12 + RNG.nextInt(10)).physics(-0.01f, 0.86f);
                f.frameOffset = RNG.nextInt(64);
                f.rot = RNG.nextFloat() * TAU;
                spawn(f);
            }
            // 오래 떠다니는 불씨
            for (int i = 0; i < count(140); i++) {
                Vec3 at = pos.add(randomDir().scale(rand(0.5f, size)));
                Sprite em = new Sprite(Sprite.Kind.EMBER, at,
                        new Vec3(rand(-0.08f, 0.08f), rand(0.03f, 0.14f), rand(-0.08f, 0.08f)),
                        rand(0.16f, 0.28f), 0.05f, 30 + RNG.nextInt(40))
                        .color(1f, rand(0.45f, 0.75f), 0.2f, 1f).physics(-0.002f, 0.96f);
                em.sway = 0.005f;
                em.frameOffset = RNG.nextInt(100);
                spawn(em);
            }

            if (g != null) {
                // 땅이 크게 터져 조각이 사방으로 + 넓게 금 간 자국 (능력 색으로 오래 빛남)
                DebrisFx.burst(g, UP, size * 0.7f, 110, 1.5f, Element.FIRE);
                DebrisFx.crater(g, size * 0.9f, 360, Element.FIRE);
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                double d = mc.player.position().distanceTo(pos);
                float flash;
                if (d < 40) flash = (float) (0.6 + 0.35 * (1 - d / 40));
                else flash = (float) Math.max(0.0, 0.6 * (1 - (d - 40) / 100));
                if (flash > 0.02f) ScreenFx.flash(0xFFB060, flash, 26);
                if (d < 48) ScreenFx.whiteFlash((float) (0.75 * (1 - d / 48)), 4);
                CameraShake.addAt(pos, 6.0f, 72f);
                CameraShake.kickFrom(pos, 4.0f, 40f);
            }
        }

        /** @return true 면 끝 */
        boolean tick() {
            age++;
            if (age > LIFE) return true;
            FxManager.lod = FxManager.lodAt(pos);

            // 두 번째 땅 울림: 바깥쪽 땅이 한 번 더 들썩이며 조각이 튐
            if (ground != null && age == 5) DebrisFx.burst(ground, UP, size * 0.95f, 45, 1.0f, null);
            if (ground != null && age == 4) CameraShake.addAt(ground, 2.5f, 40f);

            // 둘레에서 차례로 솟는 불기둥
            if (ground != null) {
                for (int i = 0; i < pillarAng.length; i++) {
                    if (age != pillarDelay[i]) continue;
                    double rr = size * 0.55 * rand(0.9f, 1.1f);
                    Vec3 top = ground.add(Math.cos(pillarAng[i]) * rr, 3.0, Math.sin(pillarAng[i]) * rr);
                    Vec3 base = groundBelow(top, 7.0);
                    if (base == null) base = top.subtract(0, 3.0, 0);
                    boolean big = i % 3 == 0;
                    SkillFx.addPillar(base, big ? 1.3f : 1.0f, big ? rand(9f, 11f) : rand(6.5f, 8.5f), 14 + RNG.nextInt(6), !big, Element.FIRE, false);
                }
            }

            Vec3 g = ground != null ? ground : pos;
            // 버섯 연기: 줄기 (떠오름) + 갓 (위에서 옆으로 퍼짐)
            if (age >= 3 && age <= 30) {
                for (int i = 0; i < count(3); i++) {
                    Vec3 at = g.add(rand(-1f, 1f) * size * 0.25, rand(0.5f, 2.5f), rand(-1f, 1f) * size * 0.25);
                    Sprite sm = new Sprite(Sprite.Kind.SMOKE, at, new Vec3(rand(-0.02f, 0.02f), rand(0.22f, 0.38f), rand(-0.02f, 0.02f)),
                            rand(3f, 4.5f), rand(6f, 8f), 40 + RNG.nextInt(20))
                            .color(0.11f, 0.09f, 0.08f, 0.62f).physics(0f, 0.955f);
                    sm.rot = RNG.nextFloat() * TAU;
                    spawn(sm);
                }
            }
            if (age >= 8 && age <= 34 && age % 2 == 0) {
                float capY = (float) (pos.y - g.y) + size * (0.9f + 0.6f * clamp01((age - 8) / 26f));
                for (int i = 0; i < count(4); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    Vec3 d = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                    Vec3 at = g.add(d.scale(rand(0.2f, 1f) * size * 0.4)).add(0, capY, 0);
                    Sprite sm = new Sprite(Sprite.Kind.SMOKE, at, d.scale(rand(0.08f, 0.16f)).add(0, rand(0.02f, 0.06f), 0),
                            rand(4f, 5.5f), rand(8f, 11f), 50 + RNG.nextInt(20))
                            .color(0.13f, 0.11f, 0.1f, 0.55f).physics(0f, 0.96f);
                    sm.rot = RNG.nextFloat() * TAU;
                    spawn(sm);
                }
            }
            // 퍼지는 바닥 연기·불꽃
            if (age >= 2 && age <= 14) {
                for (int i = 0; i < count(4); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    Vec3 d = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                    Sprite f = new Sprite(Sprite.Kind.FLAME, g.add(d.scale(size * rand(0.3f, 0.9f))).add(0, 0.6, 0),
                            d.scale(rand(0.15f, 0.3f)).add(0, rand(0.05f, 0.15f), 0), rand(2f, 3f), rand(0.8f, 1.4f), 9 + RNG.nextInt(6))
                            .physics(-0.01f, 0.88f);
                    f.frameOffset = RNG.nextInt(64);
                    spawn(f);
                }
                for (int i = 0; i < count(2); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    Vec3 d = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                    Sprite sm = new Sprite(Sprite.Kind.SMOKE, g.add(d.scale(size * rand(0.5f, 1.0f))).add(0, 0.8, 0),
                            d.scale(rand(0.1f, 0.2f)).add(0, 0.03, 0), rand(3f, 4f), rand(6f, 8f), 36 + RNG.nextInt(16))
                            .color(0.16f, 0.14f, 0.13f, 0.5f).physics(0f, 0.95f);
                    sm.rot = RNG.nextFloat() * TAU;
                    spawn(sm);
                }
            }
            // 위로 날아오르는 불씨
            if (age <= 26) {
                for (int i = 0; i < count(4); i++) {
                    Vec3 at = g.add(rand(-1f, 1f) * size * 0.5, rand(0.5f, size), rand(-1f, 1f) * size * 0.5);
                    Sprite em = new Sprite(Sprite.Kind.EMBER, at, new Vec3(rand(-0.05f, 0.05f), rand(0.1f, 0.25f), rand(-0.05f, 0.05f)),
                            rand(0.16f, 0.26f), 0.05f, 26 + RNG.nextInt(20))
                            .color(1f, rand(0.45f, 0.75f), 0.2f, 1f).physics(-0.002f, 0.96f);
                    em.sway = 0.005f;
                    em.frameOffset = RNG.nextInt(100);
                    spawn(em);
                }
            }
            return false;
        }

        void render(float pt, float time) {
            float t = age + pt;
            if (t > LIFE) return;
            // 아주 멀면 겉껍질·버섯 갓 구는 생략 (작게 보임)
            boolean near = pos.distanceToSqr(FxRender.cam) < 112 * 112;
            RenderSystem.disableCull();

            // 1) 흰빛 섬광 핵: 순간 부풀었다가 빠르게 타서 사라짐
            if (t < 12f) {
                float rc = size * (0.15f + 0.4f * easeOutCubic(t / 3.5f)) * (1f - 0.35f * clamp01((t - 4f) / 8f));
                float er = clamp01((t - 5f) / 7f);
                FxRender.elemShader(Element.FIRE, 2.6f, er, 2.1f * (1f - 0.5f * clamp01(t / 12f)), 1f - clamp01(t / 8f));
                FxManager.BlastSphere.draw(pos, rc, time * 3f);
            }
            // 2) 주 불덩이
            if (t < 34f) {
                float r1 = size * (0.25f + 0.75f * easeOutCubic(t / 10f));
                float er = t < 9f ? 0.02f : clamp01((t - 9f) / 24f);
                FxRender.elemShader(Element.FIRE, 1.6f, er, 1.35f - 0.55f * clamp01(t / 30f));
                FxManager.BlastSphere.draw(pos, r1, time * 1.8f);
            }
            // 3) 바깥 불꽃 껍질 (판정 끝까지, 더 빨리 흩어짐)
            if (near && t > 1f && t < 28f) {
                float r2 = size * (0.2f + 0.8f * easeOutCubic((t - 1f) / 9f));
                float er = 0.25f + 0.75f * clamp01((t - 6f) / 20f);
                FxRender.elemShader(Element.FIRE, 2.4f, er, 0.95f);
                FxManager.BlastSphere.draw(pos, r2, time * 2.6f + 40f);
            }
            // 4) 버섯 줄기 + 떠오르는 갓
            Vec3 g = ground != null ? ground : pos.subtract(0, size * 0.3, 0);
            if (t > 4f && t < 46f) {
                float k = clamp01((t - 4f) / 14f);
                float fade = 1f - clamp01((t - 26f) / 20f);
                float stemH = (float) (pos.y - g.y) + size * (0.4f + 1.1f * easeOutCubic(k));
                float er = 0.2f + 0.8f * clamp01((t - 18f) / 28f);
                FxRender.elemShader(Element.FIRE, 2.2f, er, 1.1f * fade + 0.05f);
                SkillFx.column(g, size * 0.3f, size * 0.17f, stemH, 0.9f * fade, 0.35f, 0f, 0.1f, time);
                Vec3 cap = g.add(0, stemH, 0);
                if (near) {
                    FxRender.elemShader(Element.FIRE, 1.8f, Math.min(1f, er + 0.1f), 1.0f * fade + 0.05f);
                    FxManager.BlastSphere.draw(cap, size * (0.35f + 0.25f * easeOutCubic(k)), time * 1.5f + 90f);
                }
                frameGlow(cap, size * 1.4f, 1f, 0.5f, 0.15f, 0.3f * fade);
            }
            // 5) 바닥 열기 고리 (판정 끝까지) + 끝이 한순간 또렷하게
            // 폭발 순간 바닥 판정 끝이 짧게 번쩍 (충격 정점 ≤ 4틱, 그 뒤 여운에는 테두리 없음)
            if (ground != null) SkillFx.edgeFlash(ground.add(0, 0.09, 0), UP, Math.max(1f, footR), t, 6f, 1f, 0.6f, 0.25f);
            if (ground != null && t < 40f) {
                float fade = 1f - clamp01((t - 12f) / 28f);
                float rr = footR * (0.4f + 0.6f * easeOutCubic(t / 10f));
                FxRender.elemShader(Element.FIRE, 1.4f, 0.15f + 0.6f * (1f - fade), 1.0f * fade);
                BufferBuilder bb = FxRender.quads();
                FxRender.ring(bb, ground.add(0, 0.07, 0), UP, rr * 0.55f, rr * 1.05f, 0.8f * fade, time * 0.25f, 64);
                FxRender.draw(bb);
            }
            // 6) 후광
            float gfade = 1f - clamp01(t / 26f);
            frameGlow(pos, size * 2.6f, 1f, 0.5f, 0.15f, 0.45f * gfade);
            frameGlow(pos, size * 1.3f, 1f, 0.9f, 0.7f, 0.5f * (1f - clamp01(t / 12f)));
        }
    }

    // =====================================================================
    // 불바다
    // =====================================================================

    static final class FireField {
        final Vec3 pos;
        final float radius;
        final int duration;
        int age;
        @Nullable Entity caster;

        FireField(Vec3 pos, float radius, int duration) {
            this.pos = pos;
            this.radius = radius;
            this.duration = duration;
        }

        /** 0→1 시작 번짐, 끝 20틱 동안 1→0 */
        float fade(float t) {
            return clamp01(t / 8f) * (1f - clamp01((t - (duration - 20)) / 20f));
        }

        boolean tick() {
            age++;
            if (age > duration) return true;
            FxManager.lod = FxManager.lodAt(pos);
            float f = fade(age);
            float spread = 0.4f + 0.6f * clamp01(age / 8f);
            // 핥아 오르는 불길
            for (int i = 0; i < count((int) (7 * f) + 1); i++) {
                float ang = RNG.nextFloat() * TAU;
                float rr = radius * spread * (float) Math.sqrt(RNG.nextFloat());
                Vec3 at = pos.add(Math.cos(ang) * rr, rand(0.2f, 0.5f), Math.sin(ang) * rr);
                Sprite fl = new Sprite(Sprite.Kind.FLAME, at, new Vec3(rand(-0.02f, 0.02f), rand(0.06f, 0.14f), rand(-0.02f, 0.02f)),
                        rand(1.0f, 1.9f) * (0.5f + 0.5f * f), rand(0.3f, 0.6f), 10 + RNG.nextInt(8)).physics(-0.005f, 0.93f);
                fl.frameOffset = RNG.nextInt(64);
                fl.rot = rand(-0.2f, 0.2f);
                spawn(fl);
            }
            // 불씨
            for (int i = 0; i < count((int) (3 * f) + 1); i++) {
                float ang = RNG.nextFloat() * TAU;
                float rr = radius * spread * (float) Math.sqrt(RNG.nextFloat());
                Vec3 at = pos.add(Math.cos(ang) * rr, 0.3, Math.sin(ang) * rr);
                Sprite em = new Sprite(Sprite.Kind.EMBER, at, new Vec3(rand(-0.03f, 0.03f), rand(0.06f, 0.16f), rand(-0.03f, 0.03f)),
                        rand(0.14f, 0.22f), 0.04f, 20 + RNG.nextInt(20))
                        .color(1f, rand(0.45f, 0.75f), 0.2f, 1f).physics(-0.003f, 0.96f);
                em.sway = 0.004f;
                em.frameOffset = RNG.nextInt(100);
                spawn(em);
            }
            // 연기
            if (age % 3 == 0) {
                for (int i = 0; i < count(2); i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = radius * (float) Math.sqrt(RNG.nextFloat());
                    Vec3 at = pos.add(Math.cos(ang) * rr, rand(0.8f, 1.6f), Math.sin(ang) * rr);
                    Sprite sm = new Sprite(Sprite.Kind.SMOKE, at, new Vec3(0, rand(0.04f, 0.08f), 0), rand(1.8f, 2.6f), rand(4f, 5.5f), 30 + RNG.nextInt(14))
                            .color(0.17f, 0.15f, 0.14f, 0.42f * (0.4f + 0.6f * f)).physics(0f, 0.96f);
                    sm.rot = RNG.nextFloat() * TAU;
                    spawn(sm);
                }
            }
            return false;
        }

        void render(float pt, float time) {
            float t = age + pt;
            float f = fade(t);
            if (f <= 0.01f) return;
            float spread = 0.4f + 0.6f * easeOutCubic(clamp01(t / 8f));
            float r = radius * spread;
            float pulse = 0.85f + 0.15f * (float) Math.sin(time * 0.5f);
            Vec3 c = pos.add(0, 0.06, 0);

            RenderSystem.disableCull();
            // 낮게 깔린 열기 원판 + 가장자리 불길 띠
            FxRender.elemShader(Element.FIRE, 1.1f, 0.35f + 0.4f * (1f - f), 0.75f * f);
            BufferBuilder bb = FxRender.quads();
            FxRender.ring(bb, c, UP, r * 0.05f, r * 0.95f, 0.4f * pulse * f, time * 0.08f, 48);
            FxRender.ring(bb, c.add(0, 0.01, 0), UP, r * 0.78f, r, 0.8f * f, -time * 0.15f, 56);
            FxRender.draw(bb);

            // 불바다 = 계속 피해를 주는 범위: 서버 판정 반지름 그대로 위험 범위 (가득 참). 끝 무렵 함께 사라짐
            // 서버 Field: 1, 11, 21, … 틱에 태움 → 첫 틱부터 또렷하게, 마지막으로 태우는 틱 뒤 2틱에 꺼짐 (불길 장식의 페이드와 따로)
            float lastBurn = 1 + ((duration - 2) / 10) * 10;
            float za = 1f - clamp01((t - lastBurn) / 2f);
            if (za > 0.01f) SkillFx.dangerZone(pos, radius, 1f, Element.FIRE, caster, za, time);

            // 바닥 붉은 번짐
            FxRender.spriteShader(FxRender.GLOW_WHITE);
            bb = FxRender.quads();
            float a = 0.38f * f * pulse;
            FxRender.planeQuad(bb, c.add(0, 0.02, 0), UP, r * 1.15f, 0f, a, a * 0.42f, a * 0.1f, a);
            FxRender.draw(bb);

            frameGlow(pos.add(0, 1.2, 0), r * 2.2f, 1f, 0.45f, 0.12f, 0.18f * f);
        }
    }
}
