package com.blackocean.skills.client.fx;

import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillsRegistry;
import com.blackocean.skills.client.FireFistTravelSound;
import com.blackocean.skills.client.SkillsClientConfig;
import com.blackocean.skills.Element;
import com.blackocean.skills.SkillAim;
import com.blackocean.skills.SkillServer;
import com.blackocean.skills.client.ClientSkillStats;
import com.blackocean.skills.entity.SkillProjectile;
import com.blackocean.skills.network.SkillEventPayload;
import com.blackocean.skills.network.SkillEvents;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
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
    /** 막 생긴 투사체: 어떤 스킬인지(생성 정보)가 한 틱 늦게 올 수 있어서 다음 틱에 연출을 붙인다 */
    private static final java.util.List<SkillProjectile> JOINED = new java.util.ArrayList<>();
    /** 투사체가 나타난 바로 그 자리 (= 서버 발사 지점). 궤적이 손에서 시작하도록 기억해 둠 */
    private static final java.util.List<Vec3> JOINED_AT = new java.util.ArrayList<>();
    /** 손에서 목표로 꽂히는 짧은 불꽃 줄기 */
    private static final List<Tracer> TRACERS = new ArrayList<>();
    private static final int MAX_CHARGES = 64, MAX_TRACERS = 32;
    private static final List<ImpactFx> IMPACTS = new ArrayList<>();
    private static final List<RingWave> RINGS = new ArrayList<>();
    private static final List<Sprite> SPRITES = new ArrayList<>();
    /** 이번 프레임에만 그리는 후광 (주먹·손 빛). 그리고 나서 바로 비움. */
    private static final List<Sprite> FRAME_GLOWS = new ArrayList<>();

    private static ClientLevel lastLevel;

    /** 목록 상한 (여럿이 동시에 쏴도 무한히 쌓이지 않게) */
    private static final int MAX_IMPACTS = 48, MAX_RINGS = 320;

    /** 스프라이트를 종류·능력별로 한 번에 나눠 둠 (프레임마다 재사용) */
    private static final int NK = Sprite.KINDS.length, NE = Element.VALUES.length;
    @SuppressWarnings("unchecked")
    private static final ArrayList<Sprite>[] BUCKETS = new ArrayList[NK * NE];

    static {
        for (int i = 0; i < BUCKETS.length; i++) BUCKETS[i] = new ArrayList<>();
    }

    /** 이번 프레임 후광 풀 (매 프레임 새로 만들지 않음) */
    private static final Sprite[] GLOW_POOL = new Sprite[768];
    private static int glowPoolUsed;

    /** 그리기 도우미 재사용 */
    private static final ArrayList<Vec3> TRAIL_PTS = new ArrayList<>(32);
    private static final Quaternionf MESH_Q = new Quaternionf();
    private static final Vector3f MESH_P = new Vector3f(), MESH_N = new Vector3f();

    private FxManager() {}

    public static void clear() {
        CHARGES.clear();
        PROJECTILES.values().forEach(p -> p.stopSound());
        PROJECTILES.clear();
        JOINED.clear();
        JOINED_AT.clear();
        TRACERS.clear();
        IMPACTS.clear();
        RINGS.clear();
        SPRITES.clear();
        FRAME_GLOWS.clear();
        SkillFx.clear();
        FireFx.clear();
        LightningFx.clear();
        WindFx.clear();
        WaterFx.clear();
        IceFx.clear();
        ThunderFx.clear();
        DebrisFx.clear();
        DamageNumbers.clear();
        CasterPose.clear();
        CameraShake.clear();
    }

    static void addRing(Vec3 center, Vec3 normal, float r0, float r1, int life, int delay, float alpha) {
        addRingWave(new RingWave(center, normal, r0, r1, life, delay, alpha));
    }

    /** 능력 색 충격파 링 */
    static void addRing(Vec3 center, Vec3 normal, float r0, float r1, int life, int delay, float alpha, Element el) {
        float[] c = FxRender.ringTintRef(el);
        addRing(center, normal, r0, r1, life, delay, alpha, c[0], c[1], c[2]);
    }

    /** 원하는 색의 충격파 링 (흰빛 충격파 등) */
    static void addRing(Vec3 center, Vec3 normal, float r0, float r1, int life, int delay, float alpha, float r, float g, float b) {
        RingWave w = new RingWave(center, normal, r0, r1, life, delay, alpha);
        w.cr = r;
        w.cg = g;
        w.cb = b;
        addRingWave(w);
    }

    private static void addRingWave(RingWave w) {
        if (RINGS.size() >= MAX_RINGS) RINGS.remove(0);
        RINGS.add(w);
    }

    static void addFrameGlow(Sprite s) {
        FRAME_GLOWS.add(s);
    }

    /** 이번 프레임에만 그리는 후광 (풀에서 꺼내 씀 — 새 Sprite 를 만들지 않음). 그리기 중에만 부를 것 */
    static void frameGlow(double x, double y, double z, float size, float r, float g, float b, float a) {
        if (a <= 0.004f || size <= 0f) return;
        Sprite s;
        if (glowPoolUsed < GLOW_POOL.length) {
            s = GLOW_POOL[glowPoolUsed];
            if (s == null) s = GLOW_POOL[glowPoolUsed] = new Sprite(Sprite.Kind.GLOW, Vec3.ZERO, Vec3.ZERO, 1f, 1f, 1);
            glowPoolUsed++;
        } else {
            s = new Sprite(Sprite.Kind.GLOW, Vec3.ZERO, Vec3.ZERO, 1f, 1f, 1);
        }
        FRAME_GLOWS.add(s.place(x, y, z, size, r, g, b, a));
    }

    static void frameGlow(Vec3 p, float size, float r, float g, float b, float a) {
        frameGlow(p.x, p.y, p.z, size, r, g, b, a);
    }

    /** 입자 양 배율 = 설정 particleAmount x 품질(fxQuality) */
    static float amount() {
        return SkillsClientConfig.PARTICLE_AMOUNT.get().floatValue() * SkillsClientConfig.quality().particles;
    }

    /** 한 화면에 동시에 있을 수 있는 입자 최대 수. 여럿이 동시에 쏴도 프레임이 무너지지 않게. */
    private static final int MAX_SPRITES = 2500;
    /** 지금 만드는 연출이 카메라에서 얼마나 먼지에 따른 입자 양 배율 (1 = 가까움). */
    static float lod = 1f;

    static void spawn(Sprite s) {
        if (SPRITES.size() < MAX_SPRITES) SPRITES.add(s);
    }

    /** 멀리서 터지는 건 작게 보이니 입자를 줄인다. */
    static float lodAt(Vec3 p) {
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double d = cam.distanceTo(p);
        if (d < 32) return 1f;
        if (d < 64) return 0.6f;
        if (d < 128) return 0.3f;
        return 0.15f;
    }

    static int count(int base) {
        float f = base * amount() * lod;
        int n = (int) f;
        return RNG.nextFloat() < f - n ? n + 1 : n;
    }

    // =====================================================================
    // 신호 처리
    // =====================================================================

    /**
     * 내가 스킬 키를 뗀 순간 (서버 허락 전에 미리 연출 시작 → 손맛).
     * 바닥 조준 스킬은 서버와 같은 계산(SkillAim)으로 자리를 미리 정하고, 서버 신호가 오면 서버 자리로 맞춘다.
     */
    public static void startPredictedCharge(net.minecraft.world.entity.player.Player self, Skill skill) {
        for (ChargeFx c : CHARGES) {
            if (c.caster == self && !c.done) return;
        }
        Vec3 target = self.position();
        float size = 0f;
        if (skill.aim == Skill.Aim.GROUND) {
            Vec3 t = SkillAim.groundTarget(self.level(), self, self.getEyePosition(), self.getLookAngle(), ClientSkillStats.range(skill));
            if (t == null) return; // 바닥 없음 → 서버도 거절함
            target = t;
            size = ClientSkillStats.radius(skill);
        }
        ChargeFx pc = new ChargeFx(self, skill, Math.max(1, ClientSkillStats.chargeTicks(skill)), true, target, size);
        pc.yaw = self.getYRot();
        pc.pitch = self.getXRot();
        addCharge(pc);
        CasterPose.start(self, skill, pc.duration, pc.yaw, pc.pitch);
        chargeSound(self, skill);
    }

    /** 모으는 소리: 서버가 주변 사람에게 틀고(본인 제외), 본인은 여기서 바로 튼다 */
    private static void chargeSound(Entity caster, Skill skill) {
        SkillServer.ChargeSound cs = SkillServer.chargeSound(skill);
        playAt(caster.position(), SkillsRegistry.snd(cs.name()), cs.volume(), cs.pitch());
    }

    /** 이 플레이어가 지금 스킬을 모으는 중인지 (내 화면 기준) */
    public static boolean isCharging(Entity e) {
        for (ChargeFx c : CHARGES) {
            if (c.caster == e && !c.done) return true;
        }
        return false;
    }

    /** 서버가 거절함(쿨타임·비활성·조준 실패) → 미리 시작한 내 준비 연출을 바로 지움. */
    public static void cancelPredictedCharge() {
        Entity self = Minecraft.getInstance().player;
        CHARGES.removeIf(c -> c.caster == self && c.predicted && !c.confirmed);
    }

    /** 투사체가 내 화면에 나타남 (매 틱 모든 엔티티를 훑지 않도록 등장할 때만 등록). */
    public static void onProjectileJoin(SkillProjectile proj) {
        if (JOINED.size() >= 128) return;
        JOINED.add(proj);
        JOINED_AT.add(proj.position());
    }

    private static void addCharge(ChargeFx c) {
        if (CHARGES.size() >= MAX_CHARGES) CHARGES.remove(0);
        CHARGES.add(c);
    }

    /** 손 → 목표로 날아가 꽂히는 짧은 불꽃 줄기 (불기둥 시전 손짓 등). life 틱 뒤 머리가 b 에 닿음 */
    static void addTracer(Vec3 a, Vec3 b, int life, float width, Element el) {
        if (TRACERS.size() >= MAX_TRACERS) TRACERS.remove(0);
        TRACERS.add(new Tracer(a, b, life, width, el));
    }

    public static void onCastStart(Entity caster, Skill skill, int chargeTicks, Vec3 target, float size) {
        onCastStart(caster, skill, chargeTicks, target, size, caster.getYRot(), caster.getXRot());
    }

    /** yaw/pitch = 서버가 고정한 시전 방향 (키를 뗀 순간의 시선) */
    public static void onCastStart(Entity caster, Skill skill, int chargeTicks, Vec3 target, float size, float yaw, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (caster == mc.player) {
            for (ChargeFx c : CHARGES) {
                if (c.caster == caster && c.predicted && !c.confirmed && !c.done && c.skill == skill) {
                    c.confirmed = true;
                    c.duration = Math.max(1, chargeTicks);
                    c.yaw = yaw;
                    c.pitch = pitch;
                    if (skill.aim == Skill.Aim.GROUND) {
                        c.target = target; // 서버가 정한 자리
                        c.size = size;
                    }
                    return;
                }
            }
        }
        CHARGES.removeIf(c -> c.caster == caster); // 같은 사람의 오래된 준비 연출 정리
        ChargeFx c = new ChargeFx(caster, skill, Math.max(1, chargeTicks), false, target, size);
        c.confirmed = true;
        c.yaw = yaw;
        c.pitch = pitch;
        addCharge(c);
        if (caster == mc.player) chargeSound(caster, skill); // 예약 발동 등 미리 시작 못 한 경우 (남의 시전 소리는 서버가 틀어 줌)
    }

    /** 서버의 스킬 연출 신호 (서버가 피해를 준 바로 그 틱) */
    public static void onEvent(SkillEventPayload e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity caster = e.casterId() >= 0 ? mc.level.getEntity(e.casterId()) : null;
        Skill skill = Skill.byIndex(e.skill());
        lod = lodAt(e.pos());
        if (e.kind() == SkillEvents.TRANSFORM) {
            Element el = Element.byIndex(e.target());
            if (el != null) transform(e.pos(), el, caster);
            return;
        }
        if (e.kind() == SkillEvents.HIT) {
            DamageNumbers.add(e);
            return;
        }
        if (e.kind() == SkillEvents.FREEZE) {
            IceFx.onStatus(e);
            return;
        }
        if (e.kind() == SkillEvents.STUN) {
            ThunderFx.onStatus(e);
            return;
        }
        if (skill == null) return;
        if (e.kind() == SkillEvents.ULT_CAST) {
            ScreenFx.ultimate(skill, caster, e.pos());
            return;
        }
        if (e.kind() == SkillEvents.IMPACT || e.kind() == SkillEvents.PIERCE) {
            if (e.kind() == SkillEvents.IMPACT) {
                ProjectileFx proj = PROJECTILES.get(e.duration());
                if (proj != null) proj.impact(e.pos());
            }
        } else if (caster != null) {
            CHARGES.removeIf(c -> c.caster == caster && c.skill == skill); // 발동 → 준비 연출 끝
        }
        switch (skill.element) {
            case FIRE -> {
                switch (e.kind()) {
                    case SkillEvents.IMPACT -> {
                        if (skill == Skill.FIRE_SUN) FireFx.onEvent(e, skill, caster);
                        else fistImpact(e, skill == Skill.FIRE_BULLETS);
                    }
                    case SkillEvents.ERUPT -> SkillFx.addPillar(e.pos(), e.size(), e.size2(), e.duration(), false);
                    case SkillEvents.BURST -> SkillFx.addRingBurst(e.pos(), e.size(), e.duration());
                    default -> FireFx.onEvent(e, skill, caster);
                }
            }
            case LIGHTNING -> LightningFx.onEvent(e, skill, caster);
            case WIND -> WindFx.onEvent(e, skill, caster);
            case WATER -> WaterFx.onEvent(e, skill, caster);
            case ICE -> IceFx.onEvent(e, skill, caster);
            case THUNDER -> ThunderFx.onEvent(e, skill, caster);
        }
    }

    /** 화염 주먹·불꽃 탄환 적중 */
    private static void fistImpact(SkillEventPayload p, boolean small) {
        Vec3 pos = p.pos();
        Vec3 n = p.vec();
        boolean fizzle = p.size2() > 0.5f;
        boolean blockHit = p.target() < 0 && !fizzle;
        // 벽에 맞으면 벽면을 따라, 그 외(바닥·생물·공중)에는 수평으로 충격파가 퍼짐
        Vec3 ringNormal = blockHit && Math.abs(n.y) < 0.5 ? n : UP;
        boolean wall = blockHit && Math.abs(n.y) < 0.5;
        Vec3 ground = null;
        if (!fizzle && !wall) {
            ground = blockHit && n.y > 0.6 ? pos : DebrisFx.findGround(pos.add(0, 0.3, 0), small ? 1.6 : 3.6);
        }
        // 판정 반지름 (서버가 보낸 값) — 링 끝이 실제 범위와 같게
        float radius = p.size() > 0.1f ? p.size() : (small ? 1.6f : 3.5f);
        // 생물에 맞음: n = 날아온 반대 방향 → 꿰뚫고 나가는 쪽 = -n
        Vec3 through = p.target() >= 0 && n.lengthSqr() > 0.25 ? n.scale(-1).normalize() : null;
        if (IMPACTS.size() >= MAX_IMPACTS) IMPACTS.remove(0);
        IMPACTS.add(new ImpactFx(pos, n, ringNormal, fizzle, small, ground, radius, through));
        if (p.target() >= 0) hitFlash(p.target(), small ? 0.6f : 1f);
        if (!small && !fizzle) {
            // 화염 주먹: 맞은 자리에서 짧게 솟구치는 불기둥 + 땅이 터져 조각이 튀고 금 간 자국
            SkillFx.addPillar(pos.subtract(0, blockHit && n.y > 0.7 ? 0 : 0.6, 0), 0.95f, 4.2f, 6, true);
            if (ground != null) {
                DebrisFx.burst(ground, UP, radius * 0.65f, 28, 1.0f, Element.FIRE);
                DebrisFx.crater(ground, radius * 0.8f, 160, Element.FIRE);
            } else if (wall) {
                DebrisFx.burst(pos, n, radius * 0.45f, 16, 0.9f, Element.FIRE);
                SkillFx.addDecal(pos, n, radius * 0.65f, 140);
            }
        } else if (small && !fizzle) {
            if (ground != null && pos.y - ground.y < 1.4) DebrisFx.burst(ground, UP, 0.7f, 5, 0.45f, null);
            else if (wall) DebrisFx.burst(pos, n, 0.5f, 3, 0.4f, null);
            if (blockHit) SkillFx.addDecal(pos, n, radius * 0.55f, 80);
        }
    }

    /**
     * 맞은 생물이 한순간 번쩍 (타격감). 몸 가운데에 짧은 흰빛 + 능력 색 번짐.
     * 다른 능력 모듈도 쓸 수 있게 열어 둠.
     */
    static void hitFlash(int entityId, float strength) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || entityId < 0) return;
        Entity t = mc.level.getEntity(entityId);
        if (t == null || t.isRemoved()) return;
        hitFlash(t, strength, Element.FIRE);
    }

    static void hitFlash(Entity t, float strength, Element el) {
        Vec3 c = t.getBoundingBox().getCenter();
        float s = Math.max(0.6f, t.getBbHeight()) * strength;
        float[] tc = FxRender.tintRef(el);
        spawn(new Sprite(Sprite.Kind.GLOW, c, Vec3.ZERO, 1.6f * s, 2.6f * s, 2).color(1f, 1f, 1f, 0.9f));
        spawn(new Sprite(Sprite.Kind.GLOW, c, Vec3.ZERO, 2.4f * s, 3.2f * s, 5).color(tc[0], tc[1], tc[2], 0.6f).elem(el));
    }

    /** 열매를 먹은 순간: 그 능력 색의 기둥 + 고리 + 화면 번쩍 (본인) */
    private static void transform(Vec3 pos, Element el, @org.jetbrains.annotations.Nullable Entity who) {
        float[] c = FxRender.tintRef(el);
        spawn(new Sprite(Sprite.Kind.GLOW, pos.add(0, 1, 0), Vec3.ZERO, 6f, 12f, 16).color(c[0], c[1], c[2], 1f).elem(el));
        addRing(pos.add(0, 0.05, 0), UP, 0.5f, 8f, 16, 0, 1f, el);
        addRing(pos.add(0, 1.0, 0), UP, 0.3f, 5f, 12, 3, 0.8f, el);
        for (int i = 0; i < count(60); i++) {
            Vec3 d = randomDir();
            spawn(new Sprite(Sprite.Kind.SPARK, pos.add(0, 1, 0), d.scale(rand(0.3f, 0.9f)).add(0, 0.25, 0), 0.12f, 0.12f, 14 + RNG.nextInt(10))
                    .color(c[0], c[1], c[2], 1f).physics(0.01f, 0.92f).elem(el));
        }
        SkillFx.addPillar(pos, 1.0f, 7f, 14, true, el);
        if (who == Minecraft.getInstance().player) ScreenFx.flash(el.color, 0.65f, 18);
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
        CasterPose.tick(mc.level.getGameTime());

        CHARGES.removeIf(c -> c.tick());

        if (!JOINED.isEmpty()) {
            for (int i = 0; i < JOINED.size(); i++) {
                SkillProjectile proj = JOINED.get(i);
                Vec3 at = JOINED_AT.get(i);
                if (!proj.isRemoved() && !PROJECTILES.containsKey(proj.getId())) PROJECTILES.put(proj.getId(), new ProjectileFx(proj, at));
            }
            JOINED.clear();
            JOINED_AT.clear();
        }

        // 날아가는 주먹 따라가기
        Iterator<ProjectileFx> it = PROJECTILES.values().iterator();
        while (it.hasNext()) {
            ProjectileFx s = it.next();
            if (!s.impacted) {
                if (s.proj.isRemoved() || s.proj.level() != mc.level) s.impact(s.last); // 적중 신호 없이 사라짐 (멀어짐 등)
                else s.track(s.proj);
            }
            if (s.tickAfter()) {
                s.stopSound();
                it.remove();
            }
        }

        IMPACTS.removeIf(ImpactFx::tick);
        TRACERS.removeIf(Tracer::tick);
        SkillFx.tick();
        FireFx.tick();
        LightningFx.tick();
        WindFx.tick();
        WaterFx.tick();
        IceFx.tick();
        ThunderFx.tick();
        DebrisFx.tick();
        AimPreview.tick();
        RINGS.removeIf(r -> ++r.age >= r.delay + r.life);
        SPRITES.removeIf(Sprite::tick);
    }

    // =====================================================================
    // 그리기 (매 프레임)
    // =====================================================================

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (Minecraft.getInstance().level == null) return;
        if (CHARGES.isEmpty() && PROJECTILES.isEmpty() && IMPACTS.isEmpty() && TRACERS.isEmpty() && RINGS.isEmpty() && SPRITES.isEmpty() && SkillFx.isEmpty()
                && FireFx.isEmpty() && LightningFx.isEmpty() && WindFx.isEmpty() && WaterFx.isEmpty() && IceFx.isEmpty() && ThunderFx.isEmpty() && DebrisFx.isEmpty() && !AimPreview.active()) return;

        FxRender.begin(event);
        try {
            bucketSprites();
            float pt = FxRender.partial;
            float time = (Minecraft.getInstance().level.getGameTime() % 24000L) + pt;

            // 1) 연기 (반투명 덮기) — 불 뒤에 깔리도록 먼저
            FxRender.alphaBlend();
            SkillFx.renderAlpha(pt);
            FireFx.renderAlpha(pt, time);
            LightningFx.renderAlpha(pt, time);
            WindFx.renderAlpha(pt, time);
            WaterFx.renderAlpha(pt, time);
            IceFx.renderAlpha(pt, time);
            ThunderFx.renderAlpha(pt, time);
            DebrisFx.render(pt, time);
            FxRender.alphaBlend();
            AimPreview.render(pt, time);
            FxRender.alphaBlend();
            drawSprites(Sprite.Kind.SMOKE, pt);

            // 2) 이하 전부 더하기 섞기
            FxRender.additive();
            for (ProjectileFx s : PROJECTILES.values()) s.renderTrail(pt, time);
            for (ProjectileFx s : PROJECTILES.values()) s.renderBody(pt, time);
            for (ChargeFx c : CHARGES) c.render(pt, time);
            for (ImpactFx i : IMPACTS) i.render(pt);
            for (Tracer t : TRACERS) t.render(pt, time);
            FxRender.additive();
            SkillFx.renderAdditive(pt, time);
            FxRender.additive();
            FireFx.renderAdditive(pt, time);
            FxRender.additive();
            LightningFx.renderAdditive(pt, time);
            FxRender.additive();
            WindFx.renderAdditive(pt, time);
            FxRender.additive();
            WaterFx.renderAdditive(pt, time);
            IceFx.renderAdditive(pt, time);
            ThunderFx.renderAdditive(pt, time);
            FxRender.additive();
            DebrisFx.renderAdditive(pt, time);
            FxRender.additive();
            drawRings(pt);
            drawSprites(Sprite.Kind.FLAME, pt);
            drawSprites(Sprite.Kind.EMBER, pt);
            drawSprites(Sprite.Kind.SPARK, pt);
            drawSprites(Sprite.Kind.GLOW, pt);
        } catch (RuntimeException ex) {
            // 연출 하나의 오류로 게임 전체가 멈추지 않게: 기록하고 연출을 모두 비움
            fxError("render", ex);
            Tesselator.getInstance().clear();
            clear();
        } finally {
            FRAME_GLOWS.clear();
            glowPoolUsed = 0;
            for (ArrayList<Sprite> b : BUCKETS) b.clear();
            FxRender.end();
        }
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static int fxErrors;

    /** 연출 오류 기록 (같은 오류로 기록이 넘치지 않게 몇 번만) */
    public static void fxError(String where, Throwable ex) {
        if (fxErrors++ < 5) LOG.error("[BlackOcean Skills] 연출 오류 ({}) — 연출을 비웁니다", where, ex);
    }

    /** 종류·능력별로 나눔 (GLOW·SPARK 는 흰 그림을 색칠하므로 능력 상관없이 한 칸) */
    private static int bucket(Sprite.Kind kind, Element el) {
        boolean shared = kind == Sprite.Kind.GLOW || kind == Sprite.Kind.SPARK;
        return kind.ordinal() * NE + (shared ? 0 : el.ordinal());
    }

    private static void bucketSprites() {
        for (ArrayList<Sprite> b : BUCKETS) b.clear();
        for (int i = 0, n = SPRITES.size(); i < n; i++) {
            Sprite s = SPRITES.get(i);
            BUCKETS[bucket(s.kind, s.elem)].add(s);
        }
    }

    private static void drawSprites(Sprite.Kind kind, float pt) {
        if (kind == Sprite.Kind.GLOW && !FRAME_GLOWS.isEmpty()) BUCKETS[bucket(kind, Element.FIRE)].addAll(FRAME_GLOWS);
        for (int e = 0; e < NE; e++) {
            ArrayList<Sprite> list = BUCKETS[kind.ordinal() * NE + e];
            if (list.isEmpty()) continue;
            // 능력마다 그림이 달라서 능력별로 나눠 그림 (한 묶음 = 그림 하나 = 그리기 한 번)
            FxRender.spriteShader(FxRender.spriteTexture(kind, Element.VALUES[e]));
            RenderSystem.disableCull();
            BufferBuilder bb = FxRender.quads();
            for (int i = 0, n = list.size(); i < n; i++) emitSprite(bb, list.get(i), kind, pt);
            FxRender.draw(bb);
        }
    }

    private static void emitSprite(BufferBuilder bb, Sprite s, Sprite.Kind kind, float pt) {
        float t = s.t(pt);
        double x = s.ix(pt), y = s.iy(pt), z = s.iz(pt);
        float size = s.size(pt);
        switch (kind) {
            case FLAME -> {
                // 64칸 그림 묶음을 넘기며 불꽃이 일렁이게
                int f = (s.frameOffset + (int) ((s.age + pt) * 2.2f)) & 63;
                float u0 = (f & 7) / 8f, v0 = (f >> 3) / 8f;
                float a = Sprite.fadeInOut(t, 0.12f) * s.alpha;
                FxRender.billboard(bb, x, y, z, size, s.rot, u0, v0, u0 + 0.125f, v0 + 0.125f, s.r * a, s.g * a, s.b * a, a);
            }
            case GLOW -> {
                float a = (s.life <= 1 ? 1f : Sprite.fadeInOut(t, 0.15f)) * s.alpha;
                FxRender.billboard(bb, x, y, z, size, s.rot, 0, 0, 1, 1, s.r * a, s.g * a, s.b * a, a);
            }
            case EMBER -> {
                float flicker = 0.7f + 0.3f * (float) Math.sin((s.age + pt) * 1.7f + s.frameOffset);
                float a = Sprite.fadeInOut(t, 0.1f) * s.alpha * flicker;
                FxRender.billboard(bb, x, y, z, size, s.rot, 0, 0, 1, 1, s.r * a, s.g * a, s.b * a, a);
            }
            case SPARK -> {
                float a = (1f - t) * s.alpha;
                FxRender.streak(bb, x - s.vx * 2.2, y - s.vy * 2.2, z - s.vz * 2.2, x, y, z, size, s.r * a, s.g * a, s.b * a, a);
            }
            case SMOKE -> {
                int f = Math.min(63, (int) (t * 63f));
                float u0 = (f & 7) / 8f, v0 = (f >> 3) / 8f;
                float a = Sprite.fadeInOut(t, 0.2f) * s.alpha;
                FxRender.billboard(bb, x, y, z, size, s.rot, u0, v0, u0 + 0.125f, v0 + 0.125f, s.r, s.g, s.b, a);
            }
        }
    }

    private static void drawRings(float pt) {
        if (RINGS.isEmpty()) return;
        FxRender.spriteShader(FxRender.SHOCK_RING_WHITE);
        RenderSystem.disableCull();
        BufferBuilder bb = FxRender.quads();
        for (RingWave r : RINGS) {
            float local = r.age + pt - r.delay;
            if (local < 0) continue;
            float t = Math.min(1f, local / r.life);
            float e = 1f - (1f - t) * (1f - t) * (1f - t); // 빠르게 퍼졌다가 느려짐
            float radius = r.r0 + (r.r1 - r.r0) * e;
            float a = (1f - t) * (1f - t) * r.alpha;
            FxRender.planeQuad(bb, r.center, r.normal, radius, r.rot, a * r.cr, a * r.cg, a * r.cb, a);
        }
        FxRender.draw(bb);
    }

    // =====================================================================
    // 도우미
    // =====================================================================

    /**
     * 화면에서만 트는 소리. 여러 스킬이 겹칠 때 소리가 뭉개지지 않게 제한:
     * 같은 소리는 가까운 곳(4칸)에서 3틱 안에 한 번, 전체는 한 틱에 6개까지.
     */
    static void playAt(Vec3 p, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !FxLimits.allowSound(level.getGameTime(), sound, p)) return;
        level.playLocalSound(p.x, p.y, p.z, sound, SoundSource.PLAYERS, volume, pitch, false);
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
        Quaternionf q = MESH_Q.rotationTo(0f, 0f, 1f, (float) dir.x, (float) dir.y, (float) dir.z);
        float ox = (float) (center.x - FxRender.cam.x);
        float oy = (float) (center.y - FxRender.cam.y);
        float oz = (float) (center.z - FxRender.cam.z);
        Vector3f p = MESH_P, n = MESH_N;
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
        final Skill skill;
        final boolean predicted;
        /** 바닥 조준 스킬: 자리 (서버 신호가 오면 서버 자리로 바뀜) */
        Vec3 target;
        /** 바닥 조준 스킬: 반지름 */
        float size;
        boolean confirmed;
        boolean done;
        int duration;
        int age;
        /** 고정된 시전 방향 (서버가 실제로 쏠 방향). 모으는 연출은 이 방향으로 정렬할 것 */
        float yaw, pitch;
        /** aimDir 캐시 (yaw/pitch 가 바뀌면 다시 계산) */
        private float cYaw = Float.NaN, cPitch = Float.NaN;
        private Vec3 cAim = new Vec3(0, 0, 1), cRight = new Vec3(1, 0, 0);
        /** 벽 앞에서 서버가 당기는 앞쪽 거리 (SkillAim.handPos 와 같게, 매 틱 갱신) */
        private double wallFwd = 1.6;

        /** 고정된 시전 방향 (단위 벡터) */
        Vec3 aimDir() {
            if (yaw != cYaw || pitch != cPitch) {
                cYaw = yaw;
                cPitch = pitch;
                cAim = Vec3.directionFromRotation(pitch, yaw);
                Vec3 r = cAim.cross(UP);
                cRight = r.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : r.normalize();
            }
            return cAim;
        }

        /** 고정된 수평 방향 (단위 벡터) */
        Vec3 flatDir() {
            return Vec3.directionFromRotation(0, yaw);
        }

        /** 고정된 조준의 오른쪽 (단위 벡터) */
        Vec3 aimRight() {
            aimDir();
            return cRight;
        }

        ChargeFx(Entity caster, Skill skill, int duration, boolean predicted, Vec3 target, float size) {
            this.caster = caster;
            this.skill = skill;
            this.duration = Math.max(1, duration);
            this.predicted = predicted;
            this.target = target;
            this.size = size > 0 ? size : 2.2f;
        }

        float progress(float pt) {
            return Math.min(1f, (age + pt) / duration);
        }

        /**
         * 서버가 투사체·번개를 실제로 내보내는 지점 (SkillAim.handPos 와 같은 식, 고정된 조준 방향).
         * 모으는 연출은 여기에 모이고, 투사체는 정확히 여기서 나간다.
         * @param side + 오른쪽 / - 왼쪽  @param lift 1 보다 크면 머리 위 (태양)
         */
        Vec3 origin(float pt, double side, double lift) {
            Vec3 eye = caster.getEyePosition(pt);
            Vec3 look = aimDir();
            boolean over = lift > 1.0;
            double fwd = over ? 0.6 : wallFwd;
            return new Vec3(eye.x + look.x * fwd + cRight.x * side, eye.y + look.y * fwd + (over ? lift : -0.3),
                    eye.z + look.z * fwd + cRight.z * side);
        }

        /** 서버와 같은 투사체 방향 (손 → 조준점). 발사 순간 한 번만 부를 것 (광선 검사) */
        Vec3 shotDir(Vec3 start) {
            if (caster instanceof net.minecraft.world.entity.player.Player pl) {
                return SkillAim.aimDirection(pl.level(), pl, caster.getEyePosition(), aimDir(), start, ClientSkillStats.range(skill));
            }
            return aimDir();
        }

        /** 몸 가운데 (가슴) — 손짓 시작점 */
        Vec3 chest(float pt) {
            return caster.getPosition(pt).add(0, caster.getBbHeight() * 0.62, 0);
        }

        private void updateWall() {
            Vec3 eye = caster.getEyePosition();
            Vec3 look = aimDir();
            Vec3 nominal = eye.add(look.scale(1.6)).add(0, -0.3, 0);
            net.minecraft.world.phys.BlockHitResult wall = com.blackocean.skills.SubLevels.clip(caster.level(),
                    new net.minecraft.world.level.ClipContext(eye, nominal, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, caster));
            wallFwd = wall.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? 1.6
                    : Math.max(0.3, 1.6 * (wall.getLocation().distanceTo(eye) - 0.15) / nominal.distanceTo(eye));
        }

        /** @return true 면 끝 */
        boolean tick() {
            if (done) return true;
            age++;
            if (caster.isRemoved()) return true;
            // 서버가 거절하면(쿨타임 등) 미리 시작한 연출을 지운다
            if (predicted && !confirmed && age > duration + 6) return true;
            float k = progress(0f);
            if (skill.aim == Skill.Aim.PROJECTILE || skill.aim == Skill.Aim.LINE) updateWall();

            if (skill.element != Element.FIRE || skill == Skill.FIRE_SUN) {
                // 번개·바람·물·작열의 태양: 능력별 연출 모듈
                lod = lodAt(caster.position());
                switch (skill.element) {
                    case FIRE -> FireFx.tickCharge(this, k);
                    case LIGHTNING -> LightningFx.tickCharge(this, k);
                    case WIND -> WindFx.tickCharge(this, k);
                    case WATER -> WaterFx.tickCharge(this, k);
                    case ICE -> IceFx.tickCharge(this, k);
                    case THUNDER -> ThunderFx.tickCharge(this, k);
                }
                // 투사체는 나가는 순간 끝, 나머지는 서버 발동 신호가 올 때까지 조금 더 기다림 (신호가 오면 바로 지움)
                int wait = skill.aim == Skill.Aim.PROJECTILE ? 0 : 10;
                if (age >= duration + wait && (confirmed || !predicted)) {
                    done = true;
                    return true;
                }
                return false;
            }

            switch (skill) {
                case FIRE_PILLAR -> {
                    lod = lodAt(target);
                    SkillFx.tickPillarWarn(target, size, k);
                    Vec3 hand = origin(1f, 0.3, 0);
                    tickHandSuck(hand, 2, 1.0f);
                    // 놓기: 손에서 목표 바닥으로 불꽃 줄기가 꽂힘 (분출 순간에 맞춰 도착)
                    if (age == Math.max(1, duration - 3)) {
                        Vec3 to = target.add(0, 0.2, 0);
                        addTracer(hand, to, 3, 0.9f, Element.FIRE);
                        releaseFlash(hand, to.subtract(hand).normalize(), 0.6f);
                    }
                    // 분출 신호가 조금 늦게 와도 경고 원이 깜빡이지 않도록 여유를 둠 (신호가 오면 바로 지움)
                    if (age >= duration + 10) { done = true; return true; }
                    return false;
                }
                case FLAME_RING -> {
                    lod = lodAt(caster.position());
                    SkillFx.tickRingCharge(caster, age, k);
                    if (age >= duration + 10) { done = true; return true; }
                    return false;
                }
                case FIRE_BULLETS -> {
                    Vec3 r = origin(1f, 0.38, 0), l = origin(1f, -0.38, 0);
                    lod = lodAt(r);
                    tickHandSuck(r, 2, 0.8f);
                    tickHandSuck(l, 2, 0.8f);
                    // 발사 섬광은 투사체가 나타날 때마다 그 자리에서 (5발 모두 손 위치에 맞게) → 여기서는 끝만 표시
                    if (age >= duration && (confirmed || !predicted)) {
                        done = true;
                        return true;
                    }
                    return false;
                }
                default -> {
                    Vec3 hand = origin(1f, 0.3, 0);
                    Vec3 look = aimDir();
                    lod = lodAt(hand);
                    tickHandSuck(hand, 4, 1.0f);
                    if (age % 2 == 0) {
                        for (int i = 0; i < count(2); i++) {
                            Sprite f = new Sprite(Sprite.Kind.FLAME, hand.add(randomDir().scale(0.3)), look.scale(-0.04).add(0, 0.03, 0), 0.6f, 1.0f, 5)
                                    .color(1f, 1f, 1f, 0.85f);
                            f.frameOffset = RNG.nextInt(64);
                            spawn(f);
                        }
                    }
                    // 손 주위를 나선으로 감아 도는 불씨 (조준 축을 따라 앞으로 조여 듦)
                    Vec3 right = aimRight(), up = right.cross(look);
                    for (int j = 0; j < 2; j++) {
                        double ang = age * 0.9 + j * Math.PI;
                        double rr = 0.9 * (1.0 - 0.45 * k);
                        Vec3 off = right.scale(Math.cos(ang) * rr).add(up.scale(Math.sin(ang) * rr));
                        Sprite em = new Sprite(Sprite.Kind.EMBER, hand.add(off).subtract(look.scale(0.4)), off.scale(-0.12).add(look.scale(0.08)), 0.2f, 0.06f, 5)
                                .color(1f, 0.8f, 0.4f, 1f);
                        spawn(em);
                    }
                    if (age >= duration && (confirmed || !predicted)) {
                        launchBurst(hand, shotDir(hand));
                        done = true;
                        return true;
                    }
                    return false;
                }
            }
        }

        /** 사방에서 손으로 빨려 드는 불씨 (정확히 수명 끝에 손에 닿음) */
        private void tickHandSuck(Vec3 hand, int n, float reach) {
            for (int i = 0; i < count(n); i++) {
                Vec3 off = randomDir().scale(rand(1.3f, 2.0f) * reach);
                Sprite s = new Sprite(Sprite.Kind.EMBER, hand.add(off), off.scale(-1.0 / 5.0), 0.16f, 0.06f, 5)
                        .color(1f, 0.75f, 0.35f, 1f);
                s.rot = RNG.nextFloat() * 6.28f;
                spawn(s);
            }
        }

        /** 손짓: 가슴에서 손(발사 지점)으로 뻗는 짧은 불꽃 줄기 + 손에서 나가는 쪽으로 터지는 작은 빛 */
        private void releaseFlash(Vec3 hand, Vec3 dir, float s) {
            Vec3 body = chest(1f);
            Vec3 arm = hand.subtract(body);
            Vec3 armDir = arm.lengthSqr() > 1.0E-4 ? arm.normalize() : dir;
            for (int i = 0; i < count(5); i++) {
                Vec3 at = body.add(arm.scale(rand(0.2f, 0.9f)));
                spawn(new Sprite(Sprite.Kind.SPARK, at, armDir.scale(rand(0.25f, 0.45f)).add(dir.scale(0.2)), 0.09f, 0.09f, 3 + RNG.nextInt(2))
                        .color(1f, 0.85f, 0.5f, 1f).physics(0f, 0.8f));
            }
            spawn(new Sprite(Sprite.Kind.GLOW, hand, Vec3.ZERO, 1.6f * s, 3.2f * s, 3).color(1f, 0.85f, 0.55f, 1f));
            addRing(hand.add(dir.scale(0.3)), dir, 0.15f, 1.4f * s, 4, 0, 0.8f, 1f, 0.92f, 0.75f);
        }

        /** 화염 주먹 발사: 손에서 조준 방향으로 터지는 섬광·고리·불꽃 (모두 실제 발사 방향으로 정렬) */
        private void launchBurst(Vec3 hand, Vec3 look) {
            spawn(new Sprite(Sprite.Kind.GLOW, hand, Vec3.ZERO, 4.2f, 5.4f, 4).color(1f, 0.85f, 0.55f, 1f));
            // 손 앞에서 원뿔처럼 벌어지는 고리 2개 (가까운 고리가 크고 빨리, 먼 고리가 작고 늦게)
            addRingWave(new RingWave(hand.add(look.scale(0.6)), look, 0.3f, 2.6f, 5, 0, 1f));
            addRingWave(new RingWave(hand.add(look.scale(1.6)), look, 0.2f, 1.7f, 5, 1, 0.7f));
            for (int i = 0; i < count(18); i++) {
                Vec3 v = look.scale(rand(0.4f, 0.9f)).add(randomDir().scale(0.22));
                spawn(new Sprite(Sprite.Kind.SPARK, hand, v, 0.1f, 0.1f, 5 + RNG.nextInt(4))
                        .color(1f, 0.8f, 0.4f, 1f).physics(0.03f, 0.86f));
            }
            for (int i = 0; i < count(6); i++) {
                Vec3 v = look.scale(rand(0.15f, 0.35f)).add(randomDir().scale(0.1));
                Sprite f = new Sprite(Sprite.Kind.FLAME, hand.add(look.scale(0.4)), v, 1.0f, 1.8f, 5 + RNG.nextInt(3)).physics(0f, 0.82f);
                f.frameOffset = RNG.nextInt(64);
                spawn(f);
            }
            // 반동: 뒤쪽(몸 쪽)으로 짧게 뿜어지는 불꽃 → 힘을 실어 내지른 느낌
            for (int i = 0; i < count(4); i++) {
                Vec3 v = look.scale(-rand(0.08f, 0.18f)).add(randomDir().scale(0.08));
                Sprite f = new Sprite(Sprite.Kind.FLAME, hand.subtract(look.scale(0.3)), v, 0.7f, 1.2f, 4 + RNG.nextInt(2)).physics(0f, 0.8f);
                f.frameOffset = RNG.nextInt(64);
                spawn(f);
            }
            // 손에서 터지는 흰빛 핵
            spawn(new Sprite(Sprite.Kind.GLOW, hand, Vec3.ZERO, 1.6f, 3.6f, 3).color(1f, 1f, 0.95f, 1f));
            addRing(hand.add(look.scale(0.9)), look, 0.2f, 2.2f, 4, 0, 0.8f, 1f, 0.92f, 0.75f);
            releaseFlash(hand, look, 0.8f);
            Minecraft mc = Minecraft.getInstance();
            if (caster == mc.player) {
                CameraShake.add(1.1f);
                CameraShake.kick(-0.9f, 0f);
            }
        }

        /** (다른 능력 모듈용) 실제 시선 기준 손 위치. 불·번개는 origin() 을 씀 */
        Vec3 handPos(float pt, double side) {
            Minecraft mc = Minecraft.getInstance();
            Vec3 eye = caster.getEyePosition(pt);
            Vec3 look = caster.getViewVector(pt);
            Vec3 right = look.cross(UP);
            right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
            boolean firstPerson = caster == mc.player && mc.options.getCameraType().isFirstPerson();
            double fwd = firstPerson ? 1.1 : 0.75;
            return eye.add(look.scale(fwd)).add(right.scale(side)).add(0, -0.38, 0);
        }

        void render(float pt, float time) {
            if (done) return;
            float k = progress(pt);
            if (skill.element != Element.FIRE || skill == Skill.FIRE_SUN) {
                switch (skill.element) {
                    case FIRE -> FireFx.renderCharge(this, pt, k, time);
                    case LIGHTNING -> LightningFx.renderCharge(this, pt, k, time);
                    case WIND -> WindFx.renderCharge(this, pt, k, time);
                    case WATER -> WaterFx.renderCharge(this, pt, k, time);
                    case ICE -> IceFx.renderCharge(this, pt, k, time);
                    case THUNDER -> ThunderFx.renderCharge(this, pt, k, time);
                }
                FxRender.additive();
                return;
            }
            switch (skill) {
                case FIRE_PILLAR -> {
                    SkillFx.renderPillarWarn(target, size, k, time);
                    handGlow(origin(pt, 0.3, 0), 0.5f + 0.8f * k, 0.5f * k + 0.2f);
                    return;
                }
                case FLAME_RING -> {
                    SkillFx.renderRingCharge(caster, pt, k, time);
                    return;
                }
                default -> { }
            }

            Minecraft mc = Minecraft.getInstance();
            boolean firstPerson = caster == mc.player && mc.options.getCameraType().isFirstPerson();
            Vec3 look = aimDir();

            if (skill == Skill.FIRE_BULLETS) {
                float s = (firstPerson ? 0.6f : 0.85f) * (0.5f + 0.5f * k);
                FxRender.fireShader(1.6f, 0.1f, 0.95f);
                RenderSystem.disableCull();
                BufferBuilder bb = FxRender.quads();
                for (int i = 0; i < 2; i++) {
                    double side = i == 0 ? 0.38 : -0.38;
                    Vec3 h = origin(pt, side, 0);
                    FxRender.ring(bb, h, look, 0.22f * s, 0.34f * s, 0.9f, time * 0.6f * (side > 0 ? 1 : -1), 32);
                    handGlow(h, (0.5f + 0.9f * k) * s, 0.5f + 0.4f * k);
                }
                FxRender.draw(bb);
                return;
            }

            // 화염 주먹: 발사 지점 주위 고리 3겹 (안쪽 2개는 조준 방향, 바깥 1개는 비스듬히 돌며 자이로처럼)
            // 막바지에 고리가 조준 축으로 눕고 앞으로 모이며 조여 듦 → 곧 나간다는 신호
            float s = (firstPerson ? 0.7f : 1f) * (0.4f + 0.6f * easeOutCubic(k * 1.6f)) * (1f - 0.25f * smooth((k - 0.75f) / 0.25f));
            Vec3 hand = origin(pt, 0.3, 0);
            Vec3 right = aimRight(), up = right.cross(look);
            double tilt = 1.1 * (1.0 - 0.6 * k), ph = time * 0.35;
            Vec3 tilted = look.scale(Math.cos(tilt)).add(right.scale(Math.sin(tilt) * Math.cos(ph))).add(up.scale(Math.sin(tilt) * Math.sin(ph)));

            FxRender.fireShader(1.4f + 1.2f * k, 0.06f, 1.0f + 0.25f * k);
            RenderSystem.disableCull();
            BufferBuilder bb = FxRender.quads();
            FxRender.ring(bb, hand.add(look.scale(0.1 + 0.25 * k)), look, 0.36f * s, 0.52f * s, 0.95f, time * 0.4f, 40);
            FxRender.ring(bb, hand.subtract(look.scale(0.18 - 0.3 * k)), look, 0.62f * s, 0.76f * s, 0.8f, -time * 0.3f, 48);
            FxRender.ring(bb, hand, tilted.normalize(), 0.92f * s, 1.02f * s, 0.6f + 0.3f * k, time * 0.6f, 56);
            FxRender.draw(bb);

            handGlow(hand, (0.8f + 1.9f * k) * s, 0.5f + 0.45f * k);
            if (k > 0.8f) frameGlow(hand, 0.6f + 1.2f * k, 1f, 0.95f, 0.85f, (k - 0.8f) / 0.2f * 0.7f);
        }

        private void handGlow(Vec3 at, float size, float alpha) {
            frameGlow(at, size, 1f, 0.6f, 0.25f, alpha);
        }
    }

    // =====================================================================
    // 손 → 목표 불꽃 줄기 (불기둥 시전 손짓)
    // =====================================================================

    static final class Tracer {
        final Vec3 a, b;
        final int life;
        final float width;
        final Element el;
        int age;

        Tracer(Vec3 a, Vec3 b, int life, float width, Element el) {
            this.a = a;
            this.b = b;
            this.life = Math.max(1, life);
            this.width = width;
            this.el = el;
        }

        /** 머리 위치 (0 → 1, 끝으로 갈수록 빨라짐) */
        private static float head(float t) {
            return clamp01(t * t * 0.6f + t * 0.4f);
        }

        boolean tick() {
            age++;
            if (age <= life) {
                lod = lodAt(b);
                Vec3 p = a.add(b.subtract(a).scale(head((float) age / life)));
                float[] c = FxRender.tintRef(el);
                for (int i = 0; i < count(3); i++) {
                    spawn(new Sprite(Sprite.Kind.SPARK, p, randomDir().scale(rand(0.05f, 0.18f)), 0.09f, 0.09f, 3 + RNG.nextInt(3))
                            .color(Math.min(1f, c[0] + 0.3f), Math.min(1f, c[1] + 0.3f), Math.min(1f, c[2] + 0.3f), 1f).physics(0.02f, 0.85f).elem(el));
                }
            }
            return age > life + 3;
        }

        void render(float pt, float time) {
            float t = (age + pt) / life;
            float h = head(Math.min(1f, t));
            float tail = Math.min(1f, clamp01(h - 0.45f) + clamp01(t - 1f) * 1.5f);
            if (tail >= h - 0.01f) return;
            Vec3 d = b.subtract(a);
            Vec3 p0 = a.add(d.scale(tail)), p1 = a.add(d.scale(h));
            float fade = t <= 1f ? 1f : clamp01(1f - (t - 1f) / 0.6f);
            FxRender.elemShader(el, 3f, 0.1f, 1.3f * fade, 0.5f);
            RenderSystem.disableCull();
            BufferBuilder bb = FxRender.quads();
            Vec3 s0 = ProjectileFx.side(p0, p1, width * 0.3f), s1 = ProjectileFx.side(p0, p1, width);
            // 꼬리(가늘고 식음) → 머리(굵고 뜨거움)
            FxRender.vertex(bb, p0.x - s0.x, p0.y - s0.y, p0.z - s0.z, 0f, 0f, 1, 1, 1, 0f);
            FxRender.vertex(bb, p0.x, p0.y, p0.z, 0.5f, 0f, 1, 1, 1, 0.3f * fade);
            FxRender.vertex(bb, p1.x, p1.y, p1.z, 0.5f, 1f, 1, 1, 1, fade);
            FxRender.vertex(bb, p1.x - s1.x, p1.y - s1.y, p1.z - s1.z, 0f, 1f, 1, 1, 1, 0f);
            FxRender.vertex(bb, p0.x, p0.y, p0.z, 0.5f, 0f, 1, 1, 1, 0.3f * fade);
            FxRender.vertex(bb, p0.x + s0.x, p0.y + s0.y, p0.z + s0.z, 1f, 0f, 1, 1, 1, 0f);
            FxRender.vertex(bb, p1.x + s1.x, p1.y + s1.y, p1.z + s1.z, 1f, 1f, 1, 1, 1, 0f);
            FxRender.vertex(bb, p1.x, p1.y, p1.z, 0.5f, 1f, 1, 1, 1, fade);
            FxRender.draw(bb);
            float[] c = FxRender.tintRef(el);
            frameGlow(p1, 1.4f * width + 0.6f, c[0], c[1], c[2], 0.6f * fade);
        }
    }

    // =====================================================================
    // 2. 비행
    // =====================================================================

    static final class ProjectileFx {
        Entity entity;
        final SkillProjectile proj;
        final Skill skill;
        final ArrayDeque<Vec3> trail = new ArrayDeque<>(); // 최근 위치, 앞이 최신
        Vec3 dir = new Vec3(0, 0, 1);
        Vec3 last;
        boolean impacted;
        Vec3 impactPos;
        int age;
        int afterTicks;
        FireFistTravelSound sound;
        /** 불꽃 탄환 (작은 불덩이, 소리 없음, 궤적 가늘고 짧음) */
        final boolean small;
        /** 화염 주먹·불꽃 탄환이 아닌 모양 (태양·바람 칼날·물 구슬) → 능력별 모듈이 그림 */
        final boolean module;

        /** 서버가 투사체를 만든 자리 (= 손). 궤적이 여기서 시작함 */
        final Vec3 origin;
        /** 떨어질 자리 예고 (능력 모듈이 채움, 없으면 null): 바닥 위치·바닥에 닿는 판정 반지름·차오름 0→1 */
        @org.jetbrains.annotations.Nullable
        Vec3 landAt;
        float landR, landFill;

        ProjectileFx(SkillProjectile e) {
            this(e, e.position());
        }

        ProjectileFx(SkillProjectile e, Vec3 spawnedAt) {
            this.entity = e;
            this.proj = e;
            this.skill = e.getSkill();
            this.small = skill.shape == Skill.Shape.BULLET;
            this.module = skill.shape != Skill.Shape.FIST && skill.shape != Skill.Shape.BULLET;
            this.origin = spawnedAt;
            this.last = e.position();
            Vec3 v = e.getDeltaMovement();
            if (v.lengthSqr() > 1.0E-6) dir = v.normalize();
            // 궤적의 맨 끝 = 손 (화면에 나타나기 전 한 틱 동안 날아간 구간도 이어서 그림)
            if (spawnedAt.distanceToSqr(last) > 1.0E-4 && spawnedAt.distanceToSqr(last) < 64) trail.addFirst(spawnedAt);
            if (skill.shape == Skill.Shape.FIST) {
                sound = new FireFistTravelSound(e);
                Minecraft.getInstance().getSoundManager().play(sound);
            }
            if (small) muzzle(spawnedAt, dir);
        }

        /** 불꽃 탄환 한 발마다: 그 손에서 발사 방향으로 터지는 작은 섬광 (5발 모두 실제 손 위치) */
        private static void muzzle(Vec3 at, Vec3 dir) {
            lod = lodAt(at);
            spawn(new Sprite(Sprite.Kind.GLOW, at, Vec3.ZERO, 1.3f, 2.2f, 2).color(1f, 0.95f, 0.8f, 1f));
            spawn(new Sprite(Sprite.Kind.GLOW, at, Vec3.ZERO, 1.8f, 1.2f, 4).color(1f, 0.55f, 0.2f, 0.8f));
            addRing(at.add(dir.scale(0.35)), dir, 0.1f, 0.9f, 3, 0, 0.9f, 1f, 0.85f, 0.55f);
            for (int i = 0; i < count(5); i++) {
                Vec3 v = dir.scale(rand(0.35f, 0.7f)).add(randomDir().scale(0.15));
                spawn(new Sprite(Sprite.Kind.SPARK, at, v, 0.07f, 0.07f, 3 + RNG.nextInt(3)).color(1f, 0.85f, 0.45f, 1f).physics(0.02f, 0.82f));
            }
            Sprite f = new Sprite(Sprite.Kind.FLAME, at.add(dir.scale(0.3)), dir.scale(0.12), 0.8f, 1.3f, 3).physics(0f, 0.8f);
            f.frameOffset = RNG.nextInt(64);
            spawn(f);
        }

        void track(SkillProjectile e) {
            Vec3 p = e.position();
            lod = lodAt(p);
            Vec3 d = p.subtract(last);
            if (d.lengthSqr() > 1.0E-4) dir = d.normalize();
            last = p;
            trail.addFirst(p);
            while (trail.size() > (small ? 7 : 14)) trail.removeLast();
            age++;

            if (module) {
                switch (skill.element) {
                    case FIRE -> FireFx.trackProjectile(this, p);
                    case LIGHTNING -> LightningFx.trackProjectile(this, p);
                    case WIND -> WindFx.trackProjectile(this, p);
                    case WATER -> WaterFx.trackProjectile(this, p);
                    case ICE -> IceFx.trackProjectile(this, p);
                    case THUNDER -> ThunderFx.trackProjectile(this, p);
                }
                return;
            }

            Vec3 back = dir.scale(-1);
            if (small) {
                // 탄환: 작은 불꽃 꼬리와 불씨만
                if (RNG.nextFloat() < amount() * lod) {
                    Sprite f = new Sprite(Sprite.Kind.FLAME, p.add(back.scale(0.5)), back.scale(0.05).add(0, 0.02, 0), 0.7f, 0.25f, 5 + RNG.nextInt(3));
                    f.frameOffset = RNG.nextInt(64);
                    spawn(f);
                }
                if (RNG.nextFloat() < amount() * lod) {
                    Sprite em = new Sprite(Sprite.Kind.EMBER, p.add(randomDir().scale(0.3)), back.scale(0.08), 0.11f, 0.04f, 10 + RNG.nextInt(8))
                            .color(1f, 0.6f, 0.25f, 1f).physics(-0.003f, 0.94f);
                    em.frameOffset = RNG.nextInt(100);
                    spawn(em);
                }
                return;
            }

            // 꼬리에서 떨어지는 불꽃·불씨·연기
            for (int i = 0; i < count(3); i++) {
                Vec3 at = p.add(back.scale(rand(0.8f, 1.8f))).add(randomDir().scale(0.4));
                Sprite f = new Sprite(Sprite.Kind.FLAME, at, back.scale(0.06).add(0, 0.03, 0), 1.5f, 0.45f, 7 + RNG.nextInt(5));
                f.frameOffset = RNG.nextInt(64);
                spawn(f);
            }
            for (int i = 0; i < count(4); i++) {
                Vec3 at = p.add(randomDir().scale(0.9));
                Sprite em = new Sprite(Sprite.Kind.EMBER, at, back.scale(0.1).add(randomDir().scale(0.03)), 0.14f, 0.05f, 14 + RNG.nextInt(10))
                        .color(1f, 0.6f, 0.25f, 1f).physics(-0.003f, 0.94f);
                em.sway = 0.004f;
                em.frameOffset = RNG.nextInt(100);
                spawn(em);
            }
            if (RNG.nextFloat() < 0.5f * amount() * lod) {
                spawn(new Sprite(Sprite.Kind.SPARK, p, back.scale(0.3).add(randomDir().scale(0.15)), 0.08f, 0.08f, 5 + RNG.nextInt(4))
                        .color(1f, 0.75f, 0.35f, 1f).physics(0.03f, 0.9f));
            }
            if (age % 2 == 0 && RNG.nextFloat() < amount() * lod) {
                Sprite sm = new Sprite(Sprite.Kind.SMOKE, p.add(back.scale(1.8)), new Vec3(0, 0.03, 0), 1.0f, 2.4f, 16)
                        .color(0.25f, 0.22f, 0.2f, 0.32f).physics(0f, 0.96f);
                sm.rot = RNG.nextFloat() * 6.28f;
                spawn(sm);
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
            // 궤적 꼬리가 적중 지점 쪽으로 빠르게 줄어듦 (남아서 흐려 보이지 않게)
            if (!trail.isEmpty()) trail.removeLast();
            if (!trail.isEmpty() && small) trail.removeLast();
            return afterTicks > 8;
        }

        void stopSound() {
            if (sound != null) sound.fadeOut();
        }

        Vec3 head(float pt) {
            if (impacted || entity == null) return impactPos != null ? impactPos : last;
            return entity.getPosition(pt);
        }

        void renderTrail(float pt, float time) {
            if (trail.size() < 2) return;
            List<Vec3> pts = TRAIL_PTS;
            pts.clear();
            pts.add(head(pt));
            int i = 0;
            for (Vec3 v : trail) {
                if (i++ == 0 && !impacted) continue; // 가장 최신 점은 보간된 머리보다 앞이라 건너뜀
                pts.add(v);
            }
            if (pts.size() < 2) return;
            float fade = impacted ? Math.max(0f, 1f - (afterTicks + pt) / (small ? 3f : 6f)) : 1f;
            if (fade <= 0f) return;

            if (skill.shape == Skill.Shape.BLADE) return; // 바람 칼날은 모듈이 잔상으로 그림
            float wScale = switch (skill.shape) {
                case BULLET -> 0.38f;
                case SUN -> 2.6f;
                case ORB -> 0.55f;
                case SPEAR -> 0.5f;
                case SHARD -> 0.2f;
                case HAMMER -> 0.8f;
                default -> 1f;
            };
            FxRender.elemShader(skill.element, 2.2f, 0.22f, 1.2f);
            RenderSystem.disableCull();
            BufferBuilder bb = FxRender.quads();
            double along = 0;
            int n = pts.size();
            for (int k = 0; k < n - 1; k++) {
                Vec3 a = pts.get(k), b = pts.get(k + 1);
                double segLen = a.distanceTo(b);
                float t0 = (float) k / (n - 1), t1 = (float) (k + 1) / (n - 1);
                Vec3 sa = side(a, b, width(t0) * wScale), sb = side(a, b, width(t1) * wScale);
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
            if (skill.shape == Skill.Shape.FIST) renderSpirals(pts, fade, time);
        }

        /** 주먹 뒤로 꼬이며 따라오는 불꽃 띠 2가닥 (나선) */
        private void renderSpirals(List<Vec3> pts, float fade, float time) {
            int n = pts.size();
            if (n < 3) return;
            Vec3[] b = FxRender.basis(dir);
            FxRender.fireShader(2.8f, 0.15f, 1.15f);
            BufferBuilder bb = FxRender.quads();
            for (int strand = 0; strand < 2; strand++) {
                Vec3 prev = null;
                float prevT = 0f;
                for (int k = 0; k < n; k++) {
                    float t = (float) k / (n - 1);
                    double ang = k * 1.15 - time * 0.9 + strand * Math.PI;
                    double rad = 1.1 * Math.sqrt(t) * (1.0 - 0.45 * t) + 0.25;
                    Vec3 p = pts.get(k).add(b[0].scale(Math.cos(ang) * rad)).add(b[1].scale(Math.sin(ang) * rad));
                    if (prev != null) {
                        float w0 = 0.42f * (1f - prevT) + 0.06f, w1 = 0.42f * (1f - t) + 0.06f;
                        Vec3 sa = side(prev, p, w0), sb = side(prev, p, w1);
                        float h0 = (1f - prevT) * smooth(prevT / 0.1f) * 0.95f * fade, h1 = (1f - t) * smooth(t / 0.1f) * 0.95f * fade;
                        float v0 = prevT * 3f + strand * 0.5f, v1 = t * 3f + strand * 0.5f;
                        FxRender.vertex(bb, prev.x - sa.x, prev.y - sa.y, prev.z - sa.z, 0f, v0, 1, 1, 1, 0f);
                        FxRender.vertex(bb, prev.x, prev.y, prev.z, 0.5f, v0, 1, 1, 1, h0);
                        FxRender.vertex(bb, p.x, p.y, p.z, 0.5f, v1, 1, 1, 1, h1);
                        FxRender.vertex(bb, p.x - sb.x, p.y - sb.y, p.z - sb.z, 0f, v1, 1, 1, 1, 0f);
                        FxRender.vertex(bb, prev.x, prev.y, prev.z, 0.5f, v0, 1, 1, 1, h0);
                        FxRender.vertex(bb, prev.x + sa.x, prev.y + sa.y, prev.z + sa.z, 1f, v0, 1, 1, 1, 0f);
                        FxRender.vertex(bb, p.x + sb.x, p.y + sb.y, p.z + sb.z, 1f, v1, 1, 1, 1, 0f);
                        FxRender.vertex(bb, p.x, p.y, p.z, 0.5f, v1, 1, 1, 1, h1);
                    }
                    prev = p;
                    prevT = t;
                }
            }
            FxRender.draw(bb);
        }

        private static float width(float t) {
            return 1.8f * (float) Math.pow(1f - t, 0.7) + 0.12f;
        }

        static Vec3 side(Vec3 a, Vec3 b, float w) {
            Vec3 d = b.subtract(a);
            Vec3 toCam = FxRender.cam.subtract(a);
            Vec3 s = d.cross(toCam);
            if (s.lengthSqr() < 1.0E-8) return Vec3.ZERO;
            return s.normalize().scale(w * 0.5);
        }

        void renderBody(float pt, float time) {
            if (impacted || entity == null) return;
            Vec3 c = head(pt);
            if (module) {
                switch (skill.element) {
                    case FIRE -> FireFx.renderProjectile(this, c, pt, time);
                    case LIGHTNING -> LightningFx.renderProjectile(this, c, pt, time);
                    case WIND -> WindFx.renderProjectile(this, c, pt, time);
                    case WATER -> WaterFx.renderProjectile(this, c, pt, time);
                    case ICE -> IceFx.renderProjectile(this, c, pt, time);
                    case THUNDER -> ThunderFx.renderProjectile(this, c, pt, time);
                }
                FxRender.additive();
                return;
            }
            if (small) {
                // 탄환: 꿈틀거리는 작은 불덩이 + 빛
                float r = 0.36f * Math.min(1f, 0.5f + (age + pt) / 3f);
                FxRender.fireShader(2.0f, 0.0f, 1.15f);
                RenderSystem.disableCull();
                BlastSphere.draw(c, r, time * 3f);
                frameGlow(c, 2.0f, 1f, 0.55f, 0.2f, 0.4f);
                return;
            }
            float grow = Math.min(1f, (age + pt) / 2.5f);
            float scale = 1.3f * (0.55f + 0.45f * grow) * (1f + 0.04f * (float) Math.sin(time * 1.9f));
            ObjMesh core = ObjMesh.get("fist_core");
            ObjMesh shell = ObjMesh.get("fist_shell");

            // 잔상: 최근 위치들에 점점 흐려지고 더 많이 타 버린 주먹을 남긴다
            // 잔상은 메시를 3번 더 그리므로 멀리 있는 주먹에서는 생략 (어차피 작게 보임)
            if (SkillsClientConfig.AFTERIMAGES.get() && c.distanceToSqr(FxRender.cam) < 64 * 64) {
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
            frameGlow(c, 6.5f * scale / 1.3f, 1f, 0.55f, 0.2f, 0.32f + 0.05f * (float) Math.sin(time * 3.1f));
            // 주먹 한가운데 흰빛 핵
            frameGlow(c, 1.9f * scale, 1f, 0.92f, 0.8f, 0.45f);
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

    /**
     * 화염 주먹·불꽃 탄환 적중.
     * <pre>
     *  0~2틱  섬광 (흰빛 핵 → 주황 번짐) + 흰 충격파가 판정 반지름까지 한 번에
     *  0~9틱  폭발 구가 판정 반지름까지 부풂, 가장자리 고리가 정확히 반지름에서 번쩍
     *  3틱    타닥 소리
     *  6~30틱 바닥 잔불·불씨·연기 (점점 약해짐)
     * </pre>
     */
    static final class ImpactFx {
        final Vec3 pos;
        final Vec3 normal;
        final boolean fizzle;
        /** 불꽃 탄환 적중 (작은 폭발) */
        final boolean small;
        /** 아래 바닥 (없으면 null) — 잔류 불길·불씨가 피어오르는 자리 */
        @org.jetbrains.annotations.Nullable
        final Vec3 ground;
        /** 판정 반지름 (서버 값) */
        final float radius;
        final Vec3 ringNormal;
        final int life;
        int age;

        ImpactFx(Vec3 pos, Vec3 normal, Vec3 ringNormal, boolean fizzle, boolean small) {
            this(pos, normal, ringNormal, fizzle, small, null, small ? 1.6f : 3.5f, null);
        }

        ImpactFx(Vec3 pos, Vec3 normal, Vec3 ringNormal, boolean fizzle, boolean small, @org.jetbrains.annotations.Nullable Vec3 ground) {
            this(pos, normal, ringNormal, fizzle, small, ground, small ? 1.6f : 3.5f, null);
        }

        /** @param through 생물을 맞혔을 때 꿰뚫고 나가는 방향 (불꽃이 그쪽으로 쏠림), 없으면 null */
        ImpactFx(Vec3 pos, Vec3 normal, Vec3 ringNormal, boolean fizzle, boolean small, @org.jetbrains.annotations.Nullable Vec3 ground,
                 float radius, @org.jetbrains.annotations.Nullable Vec3 through) {
            this.pos = pos;
            this.normal = normal;
            this.fizzle = fizzle;
            this.small = small;
            this.ground = ground;
            this.radius = Math.max(0.5f, radius);
            this.ringNormal = ringNormal;
            // 불덩이·불꽃 크기는 반지름에 맞춤 (주먹 3.5 = 1)
            float big = fizzle ? 0.45f : this.radius / 3.5f;
            boolean lite = fizzle || small;
            this.life = lite ? 12 : 34;
            lod = lodAt(pos);
            float R = this.radius;

            // 섬광 (흰빛 핵 → 넓은 주황 번짐)
            if (!lite) spawn(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, 3f, 11f, 3).color(1f, 1f, 1f, 1f));
            spawn(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, 7.5f * big, 9f * big, 4).color(1f, 0.95f, 0.8f, 1f));
            spawn(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, 4f * big, 6f * big, 9).color(1f, 0.5f, 0.15f, 0.8f));
            if (!lite) spawn(new Sprite(Sprite.Kind.GLOW, pos, Vec3.ZERO, R * 2.6f, R * 3.4f, 12).color(1f, 0.35f, 0.08f, 0.32f));

            // 충격파 링: 흰빛 빠른 링(살짝 넘어감) → 판정 끝 링 → 안쪽 링. 끝이 실제 범위와 같게
            Vec3 rc = pos.add(ringNormal.scale(0.08));
            if (!fizzle) {
                addRing(rc, ringNormal, 0.3f, R * 1.25f, lite ? 4 : 5, 0, lite ? 0.6f : 0.9f, 1f, 0.92f, 0.75f);
                addRingWave(new RingWave(rc, ringNormal, R * 0.3f, R, lite ? 6 : 9, 0, lite ? 0.7f : 1f));
                if (!lite) {
                    addRingWave(new RingWave(rc, ringNormal, 0.3f, R * 0.6f, 6, 1, 0.8f));
                    addRingWave(new RingWave(pos, normal.lengthSqr() > 0.5 ? normal : UP, 0.3f, R * 0.8f, 6, 2, 0.55f));
                }
            } else {
                addRingWave(new RingWave(rc, ringNormal, 0.2f, 1.2f, 5, 0, 0.5f));
            }

            // 바깥으로 터지는 불꽃 덩어리 (생물에 맞으면 꿰뚫고 나가는 쪽으로 쏠림)
            for (int i = 0; i < count(lite ? 5 : 24); i++) {
                Vec3 d = randomDir().add(0, 0.35, 0);
                if (through != null) d = d.add(through.scale(0.9));
                d = d.normalize();
                Sprite f = new Sprite(Sprite.Kind.FLAME, pos.add(d.scale(0.4)), d.scale(rand(0.16f, 0.32f) * Math.max(0.6f, big)),
                        rand(1.4f, 2.1f) * big, rand(2.0f, 3.0f) * big, 8 + RNG.nextInt(6)).physics(-0.008f, 0.82f);
                f.frameOffset = RNG.nextInt(64);
                f.rot = RNG.nextFloat() * 6.28f;
                spawn(f);
            }
            // 사방으로 튀는 불꽃 줄기 (맞은 면 바깥쪽으로, 생물이면 관통 쪽으로 더)
            for (int i = 0; i < count(lite ? 10 : 56); i++) {
                Vec3 d = randomDir();
                if (d.dot(normal) < 0) d = d.subtract(normal.scale(2 * d.dot(normal)));
                if (through != null && i % 2 == 0) d = d.add(through.scale(1.4)).normalize();
                spawn(new Sprite(Sprite.Kind.SPARK, pos, d.scale(rand(0.55f, 1.25f) * Math.max(0.6f, big)), 0.12f, 0.12f, 7 + RNG.nextInt(7))
                        .color(1f, 0.82f, 0.45f, 1f).physics(0.045f, 0.9f));
            }
            // 잔류: 천천히 떠오르다 꺼지는 불씨
            for (int i = 0; i < count(lite ? 5 : 28); i++) {
                Vec3 at = pos.add(randomDir().scale(rand(0.2f, R * 0.7f)));
                Sprite em = new Sprite(Sprite.Kind.EMBER, at,
                        new Vec3(rand(-0.03f, 0.03f), rand(0.02f, 0.06f), rand(-0.03f, 0.03f)),
                        rand(0.12f, 0.2f), 0.04f, 18 + RNG.nextInt(16))
                        .color(1f, rand(0.45f, 0.7f), 0.2f, 1f).physics(-0.002f, 0.96f);
                em.sway = 0.003f;
                em.frameOffset = RNG.nextInt(100);
                spawn(em);
            }
            // 잔류: 퍼지며 옅어지는 연기
            for (int i = 0; i < count(lite ? 2 : 10); i++) {
                Vec3 d = randomDir();
                Vec3 at = pos.add(d.scale(rand(0.2f, 1.2f) * big));
                Sprite sm = new Sprite(Sprite.Kind.SMOKE, at, new Vec3(d.x * 0.03, rand(0.02f, 0.05f), d.z * 0.03),
                        rand(1.4f, 2.0f) * big, rand(3.4f, 4.4f) * big, 22 + RNG.nextInt(10))
                        .color(0.2f, 0.18f, 0.17f, 0.5f).physics(0f, 0.95f);
                sm.rot = RNG.nextFloat() * 6.28f;
                spawn(sm);
            }

            if (!fizzle) {
                if (small) {
                    CameraShake.addAt(pos, 0.6f, 8f);
                } else {
                    CameraShake.addAt(pos, 3.4f, 18f);
                    CameraShake.kickFrom(pos, 2.2f, 14f);
                    ScreenFx.impact(pos, 0xFFB060, 0.3f, 10f);
                }
            }
        }

        /** @return true 면 끝 */
        boolean tick() {
            age++;
            if (age == 3 && !fizzle && !small) {
                playAt(pos, SkillsRegistry.FIRE_FIST_CRACKLE.get(), 1.6f, 0.9f + RNG.nextFloat() * 0.2f);
            }
            if (!fizzle && !small && age >= 6) aftermath();
            return age > life;
        }

        /** 잔류: 바닥에서 핥아 오르는 작은 불길·불씨, 옅게 피어오르는 연기 (점점 줄어듦) */
        private void aftermath() {
            lod = lodAt(pos);
            float k = 1f - (float) age / life;
            Vec3 g = ground != null ? ground : pos;
            float rr0 = radius * 0.5f;
            if (age < 24 && RNG.nextFloat() < 0.8f * k) {
                for (int i = 0; i < count(2); i++) {
                    float ang = RNG.nextFloat() * 6.28f;
                    float rr = rr0 * (float) Math.sqrt(RNG.nextFloat());
                    Vec3 at = g.add(Math.cos(ang) * rr, 0.25, Math.sin(ang) * rr);
                    Sprite f = new Sprite(Sprite.Kind.FLAME, at, new Vec3(0, rand(0.04f, 0.09f), 0), rand(0.7f, 1.2f) * (0.4f + 0.6f * k),
                            0.25f, 7 + RNG.nextInt(5)).physics(-0.004f, 0.92f);
                    f.frameOffset = RNG.nextInt(64);
                    spawn(f);
                }
            }
            if (age % 2 == 0) {
                for (int i = 0; i < count(2); i++) {
                    Vec3 at = g.add(rand(-rr0, rr0), 0.2, rand(-rr0, rr0));
                    Sprite em = new Sprite(Sprite.Kind.EMBER, at, new Vec3(rand(-0.02f, 0.02f), rand(0.04f, 0.1f), rand(-0.02f, 0.02f)),
                            rand(0.1f, 0.17f), 0.03f, 14 + RNG.nextInt(12)).color(1f, rand(0.45f, 0.7f), 0.2f, 1f).physics(-0.002f, 0.96f);
                    em.sway = 0.003f;
                    em.frameOffset = RNG.nextInt(100);
                    spawn(em);
                }
            }
            if (age % 4 == 0 && RNG.nextFloat() < k) {
                Sprite sm = new Sprite(Sprite.Kind.SMOKE, g.add(rand(-1f, 1f), 0.6, rand(-1f, 1f)), new Vec3(0, rand(0.03f, 0.06f), 0),
                        rand(1.2f, 1.8f), rand(3.2f, 4.2f), 24 + RNG.nextInt(10)).color(0.17f, 0.15f, 0.14f, 0.36f * k).physics(0f, 0.97f);
                sm.rot = RNG.nextFloat() * 6.28f;
                spawn(sm);
            }
        }

        void render(float pt) {
            float t = age + pt;
            if (!fizzle && !small && ground != null && t < 28f) {
                // 식어 가는 바닥 열기
                float k = 1f - t / 28f;
                frameGlow(ground.x, ground.y + 0.4, ground.z, radius * 1.3f, 1f, 0.45f, 0.12f, 0.35f * k * k);
            }
            // 판정 끝 가장자리: 폭발이 다 부푼 순간 한 번 또렷하게 번쩍 (범위가 바로 읽힘)
            if (!fizzle && t < 8f) {
                float k = t < 2f ? t / 2f : 1f - (t - 2f) / 6f;
                FxRender.spriteShader(FxRender.BEAM);
                RenderSystem.disableCull();
                BufferBuilder bb = FxRender.quads();
                float w = small ? 0.12f : 0.22f;
                FxRender.band(bb, pos.add(ringNormal.scale(0.1)), ringNormal, radius - w, radius + w * 0.3f, 1f * k, 0.62f * k, 0.25f * k, k, small ? 24 : 56, false);
                FxRender.draw(bb);
            }
            // 폭발 구: 판정 반지름까지 빠르게 부풀었다가 노이즈 모양으로 타서 사라짐
            if (t > 12f) return;
            float big = fizzle ? 0.45f : radius / 3.5f;
            float r = (0.17f + 0.75f * easeOutCubic(t / 5f)) * (fizzle ? 1.6f : radius);
            float erosion = t < 3f ? 0f : Math.min(1f, (t - 3f) / 8.5f);
            float intensity = 1.05f - 0.4f * Math.min(1f, t / 10f);
            FxRender.elemShader(Element.FIRE, 1.4f, erosion, intensity, t < 4f ? 0.6f * (1f - t / 4f) : 0f);
            RenderSystem.disableCull();
            BlastSphere.draw(pos, r, t);
            // 흰빛으로 타오르는 핵 (처음 몇 틱)
            if (!fizzle && t < 5f) {
                float k = 1f - t / 5f;
                float rc = (0.35f + 1.3f * easeOutCubic(t / 2f)) * big;
                FxRender.elemShader(Element.FIRE, 3f, 0.25f * (1f - k), 1.6f + 0.8f * k, 1f);
                BlastSphere.draw(pos, rc, t * 2f + 30f);
            }
        }
    }

    /** 바닥이나 벽을 따라 퍼지는 충격파 링. */
    static final class RingWave {
        final Vec3 center, normal;
        final float r0, r1, alpha, rot;
        final int life, delay;
        int age;
        /** 링 색 (기본 = 불 주황) */
        float cr = 1f, cg = 0.5f, cb = 0.18f;

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
        /** 정점마다 x,y,z,u,v + 울퉁불퉁 계산용 미리 구한 사인 값 (그릴 때 삼각함수를 안 씀) */
        private static final int STRIDE = 10;
        private static float[] verts;

        private static float[] build() {
            List<float[]> tris = new ArrayList<>();
            for (int i = 0; i < LAT; i++) {
                for (int j = 0; j < LON; j++) {
                    float[] a = pt(i, j), b = pt(i + 1, j), c = pt(i + 1, j + 1), d = pt(i, j + 1);
                    tris.add(a); tris.add(b); tris.add(c);
                    tris.add(a); tris.add(c); tris.add(d);
                }
            }
            float[] out = new float[tris.size() * STRIDE];
            for (int k = 0; k < tris.size(); k++) {
                float[] v = tris.get(k);
                int o = k * STRIDE;
                System.arraycopy(v, 0, out, o, 5);
                float nx = v[0], ny = v[1], nz = v[2];
                double th = Math.acos(Math.max(-1f, Math.min(1f, ny))), ph = Math.atan2(nz, nx);
                double a = 3 * ph + 1.3, b = 5 * ph + 2 * th + 0.7;
                out[o + 5] = (float) Math.sin(4 * th);
                out[o + 6] = (float) Math.sin(a);
                out[o + 7] = (float) Math.cos(a);
                out[o + 8] = (float) Math.sin(b);
                out[o + 9] = (float) Math.cos(b);
            }
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
            // 울퉁불퉁한 불덩어리: sin(A + t1), sin(B - t2) 를 덧셈 정리로 (정점마다 삼각함수 없이)
            float s1 = (float) Math.sin(time * 0.15), c1 = (float) Math.cos(time * 0.15);
            float s2 = (float) Math.sin(time * 0.1), c2 = (float) Math.cos(time * 0.1);
            float[] vs = verts;
            BufferBuilder bb = FxRender.triangles();
            for (int k = 0; k < vs.length; k += STRIDE) {
                float nx = vs[k], ny = vs[k + 1], nz = vs[k + 2];
                float t1 = (vs[k + 6] * c1 + vs[k + 7] * s1) * vs[k + 5];
                float t2 = vs[k + 8] * c2 - vs[k + 9] * s2;
                float rr = radius * (1f + 0.13f * t1 + 0.09f * t2);
                float x = ox + nx * rr, y = oy + ny * rr, z = oz + nz * rr;
                float len = (float) Math.sqrt(x * x + y * y + z * z);
                float facing = len > 1.0E-4f ? Math.abs(nx * x + ny * y + nz * z) / len : 1f;
                // 구 아래쪽은 바닥에 눌려 조금 어둡게
                float heat = (0.12f + 0.6f * facing) * (0.75f + 0.25f * (ny * 0.5f + 0.5f));
                bb.addVertex(x, y, z).setUv(vs[k + 3], vs[k + 4]).setColor(1f, 1f, 1f, heat);
            }
            FxRender.draw(bb);
        }
    }
}
