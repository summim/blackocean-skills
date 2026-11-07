package com.blackocean.skills.client.fx;

import com.blackocean.skills.Element;
import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillAim;
import com.blackocean.skills.SkillServer;
import com.blackocean.skills.SkillTargets;
import com.blackocean.skills.SkillsMod;
import com.blackocean.skills.SubLevels;
import com.blackocean.skills.client.ClientSkillStats;
import com.blackocean.skills.client.SkillBarHud;
import com.mojang.blaze3d.platform.GlStateManager;
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
 * 스킬 키를 꾹 누르고 있는 동안 보이는 조준 표시. 서버와 같은 계산(SkillAim)으로 그려서 실제 판정과 맞는다.
 * <pre>
 *  PROJECTILE : 손에서 앞으로 흐르는 화살표 길 (폭 = 판정 폭, 벽·적에서 멈춤) + 떨어지는 곳의 폭발 범위 마법진
 *  LINE       : 번개·물줄기 길 + 끝 표시 (번개는 연쇄 범위)
 *  GROUND     : 조준한 바닥의 마법진 + 발밑 최대 사거리 원 (바닥이 없으면 빨간 표시)
 *               궁극기는 겹 마법진 + 육각 바닥 무늬, 기둥·회오리는 높이 고리, 천둥 도약은 포물선 궤적
 *  SELF       : 발밑 마법진
 *  DASH/WAVE  : 땅 위로 앞으로 뻗는 길 (폭·거리, 벽에서 멈춤). 얼음 가시는 땅 높이를 따라감
 *  BLINK      : 도착 지점 마법진 + 길 (갈 곳이 없으면 빨간 표시)
 *  CONE       : 부채꼴 (얼음 파편 = 손 높이, 파편 7줄기가 벽에서 멈춤 / 뇌명 일격 = 바닥, 벽 뒤는 잘림)
 *  범위는 서버 판정 반지름 그대로: 회오리 실선 r+0.5·점선 r×1.6(대폭풍 ×2.3), 감옥 r×1.4, 낙뢰 흩어짐 범위, 폭풍 마지막 번개·
 *  포효 폭발 안쪽 고리, 심판 여진 점선, 돌진 폭 r+0.6, 물줄기 굵기 r, 투사체 길이 = 실제 비행 거리.
 *  키를 떼면 이 표시를 계산한 시선(shownAim)을 그대로 서버에 보냄. 거절될 조준이면 HUD 에 서버와 같은 안내.
 * </pre>
 * <b>성능</b>: 블록·생물 검사(레이캐스트)는 전부 틱(초당 20번)에만 하고 결과를 두 장(직전·이번 틱) 보관,
 * 매 프레임은 둘 사이를 보간해서 그리기만 한다 (부드럽고 떨림 없음). 프레임마다 새 객체를 만들지 않고,
 * 그림 한 장(aim_atlas)에 모든 무늬를 모아 셰이더·텍스처 전환을 최소화 (그리기 호출 최대 3번).
 * 색은 능력 색, 쿨타임이 많이 남았으면 빨갛게.
 */
public final class AimPreview {
    static final ResourceLocation ATLAS = SkillsMod.id("textures/fx/aim_atlas.png");
    static final ResourceLocation ARROW = SkillsMod.id("textures/fx/aim_arrow.png");
    /** 이만큼(틱) 누르고 있어야 보임 (톡 치는 빠른 사용에는 번쩍이지 않게) */
    private static final int SHOW_AFTER = 2;
    private static final double TAU = Math.PI * 2;

    // ---- aim_atlas.png 영역 (u0, v0, u1, v1) — 가장자리 번짐을 막으려고 1px 안쪽 ----
    private static final float[] R_RUNE = reg(0, 0, 256, 256);
    private static final float[] R_FILL = reg(256, 0, 512, 256);
    private static final float[] R_MARK = reg(0, 256, 256, 512);
    private static final float[] R_LINE = reg(256, 260, 320, 508);
    private static final float[] R_DOT = reg(320, 256, 384, 320);
    private static final float[] R_DIAM = reg(384, 256, 448, 320);
    private static final float[] R_HEX = reg(320, 320, 512, 512);

    // ---- 능력 색 (프레임마다 배열을 만들지 않게 미리) ----
    private static final float[][] COLORS = new float[Element.VALUES.length][];
    private static final float[] BLOCKED = {0.75f, 0.28f, 0.22f};
    private static final float[] INVALID = {0.9f, 0.18f, 0.14f};

    static {
        for (Element e : Element.VALUES) {
            COLORS[e.ordinal()] = switch (e) {
                case FIRE -> new float[]{1f, 0.55f, 0.12f};
                case LIGHTNING -> new float[]{0.45f, 0.8f, 1f};
                case WIND -> new float[]{0.45f, 1f, 0.7f};
                case WATER -> new float[]{0.3f, 0.65f, 1f};
                case ICE -> new float[]{0.7f, 0.94f, 1f};
                case THUNDER -> new float[]{1f, 0.8f, 0.25f};
            };
        }
    }

    // =====================================================================
    // 조준 상태
    // =====================================================================

    @Nullable
    private static Skill aiming;
    private static int held;

    // ---- 틱마다 계산한 조준 결과 (prev = 직전 틱, cur = 이번 틱, lp = 이번 프레임 보간값) ----
    private static final int N = 24;
    /** A = 시작(손·발), B = 끝, NR = 끝 면 방향, D = 방향, T = 목표 바닥, G = 보조 바닥(발밑·착지·연쇄), X = 추가 값 */
    private static final int A = 0, B = 3, NR = 6, D = 9, T = 12, G = 15, X0 = 18, X1 = 19;
    private static final double[] prev = new double[N], cur = new double[N], lp = new double[N];
    /** 경로 점 (부채꼴 광선 끝, 땅을 따라가는 가시 길) */
    private static final int MAX_PATH = 16;
    private static final double[] prevPath = new double[MAX_PATH * 3], curPath = new double[MAX_PATH * 3], lpPath = new double[MAX_PATH * 3];
    private static int pathN;
    private static boolean haveSnap, valid, hasNormal, hitEntity, flatArrow, stoppedByWall;
    @Nullable
    private static Skill snapSkill;
    /** 이번 틱 조준 결과를 계산한 시선 (키를 떼면 이 방향 그대로 서버에 보냄 → 보이던 것 = 실제 공격) */
    private static float snapYaw, snapPitch;
    /** 서버가 거절할 이유 (보이는 빨간 표시와 같은 판정): 0 = 없음 */
    public static final int OK = 0, NO_GROUND = 1, NO_SPACE = 2;
    private static int refusal;

    private AimPreview() {}

    /**
     * 지금 보이는 조준 표시를 계산한 시선 {yaw, pitch}. 이 스킬의 조준이 화면에 보이는 중이 아니면 null
     * (아주 빠른 톡 → 아무것도 안 보였으니 지금 시선을 쓰면 됨).
     */
    @Nullable
    public static float[] shownAim(Skill s) {
        if (!visible() || !haveSnap || snapSkill != s || aiming != s) return null;
        return new float[]{snapYaw, snapPitch};
    }

    /** 지금 보이는 조준이 서버에서 거절될 이유 (OK / NO_GROUND / NO_SPACE) */
    public static int refusal() {
        return visible() && haveSnap ? refusal : OK;
    }

    public static void start(Skill s) {
        aiming = s;
        held = 0;
        haveSnap = false;
    }

    public static void stop() {
        aiming = null;
        held = 0;
        haveSnap = false;
    }

    @Nullable
    public static Skill aiming() {
        return aiming;
    }

    public static boolean visible() {
        return aiming != null && held >= SHOW_AFTER;
    }

    static boolean active() {
        return visible() && haveSnap;
    }

    // =====================================================================
    // 틱: 레이캐스트는 여기서만
    // =====================================================================

    static void tick() {
        Skill s = aiming;
        if (s == null) {
            haveSnap = false;
            return;
        }
        held++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;

        boolean wasValid = valid, wasNormal = hasNormal;
        int wasPath = pathN;
        System.arraycopy(cur, 0, prev, 0, N);
        System.arraycopy(curPath, 0, prevPath, 0, MAX_PATH * 3);
        compute(mc.level, p, s);
        // 처음이거나 모양이 바뀌면(바닥 없음 ↔ 있음 등) 보간하지 않고 바로 맞춤
        if (!haveSnap || snapSkill != s || wasValid != valid || wasNormal != hasNormal || wasPath != pathN) {
            System.arraycopy(cur, 0, prev, 0, N);
            System.arraycopy(curPath, 0, prevPath, 0, MAX_PATH * 3);
        }
        snapSkill = s;
        haveSnap = true;
    }

    private static void put(int at, Vec3 v) {
        cur[at] = v.x;
        cur[at + 1] = v.y;
        cur[at + 2] = v.z;
    }

    private static void putPath(int i, Vec3 v) {
        curPath[i * 3] = v.x;
        curPath[i * 3 + 1] = v.y;
        curPath[i * 3 + 2] = v.z;
    }

    private static void compute(Level level, LocalPlayer p, Skill s) {
        // 서버(SkillServer.withAim)는 보낸 yaw/pitch 로 getLookAngle() 을 만듦 → 같은 식으로 시선 계산
        snapYaw = p.getYRot();
        snapPitch = p.getXRot();
        Vec3 eye = p.getEyePosition(), look = Vec3.directionFromRotation(snapPitch, snapYaw);
        float range = ClientSkillStats.range(s), radius = ClientSkillStats.radius(s);
        refusal = OK;
        valid = true;
        hasNormal = false;
        hitEntity = false;
        flatArrow = true;
        stoppedByWall = false;
        pathN = 0;
        Vec3 feet = p.position();
        put(G, feet);
        switch (s.aim) {
            case PROJECTILE, LINE -> {
                double side = s == Skill.FIRE_SUN ? 0 : s == Skill.FIRE_BULLETS ? 0.38 : 0.3;
                double lift = s == Skill.FIRE_SUN ? 2.6 : 0;
                Vec3 start = SkillAim.handPos(level, p, eye, look, side, lift);
                Vec3 dir = SkillAim.aimDirection(level, p, eye, look, start, range);
                // 투사체는 ceil(사거리/속도) 틱 동안 날아감 (SkillProjectile.create) → 실제로 사라지는 거리
                Vec3 end = start.add(dir.scale(s.aim == Skill.Aim.PROJECTILE ? flight(s, range) : range));
                Vec3 normal = Vec3.ZERO;
                BlockHitResult path = SubLevels.clip(level, new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                if (path.getType() != HitResult.Type.MISS) {
                    end = path.getLocation();
                    Direction f = path.getDirection();
                    normal = new Vec3(f.getStepX(), f.getStepY(), f.getStepZ());
                    hasNormal = true;
                }
                if (stopsAtEntity(s)) {
                    final Vec3 s0 = start, s1 = end;
                    final float r = s.hit;
                    EntityHitResult target = ProjectileUtil.getEntityHitResult(level, p, start, end, new AABB(start, end).inflate(r + 1.0),
                            e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && e != p && !SkillTargets.isSameRide(p, e)
                                    && e.getBoundingBox().inflate(r).clip(s0, s1).isPresent());
                    if (target != null) {
                        Vec3 hit = target.getEntity().getBoundingBox().inflate(r).clip(start, end).orElse(target.getLocation());
                        if (hit.distanceToSqr(start) < end.distanceToSqr(start)) {
                            end = hit;
                            normal = new Vec3(0, 1, 0);
                            hasNormal = true;
                            hitEntity = true;
                        }
                    }
                }
                flatArrow = dir.x * dir.x + dir.z * dir.z > 1.0E-4;
                put(A, start);
                put(B, end);
                put(D, dir);
                put(NR, normal);
                if (s == Skill.LIGHTNING_BOLT) put(T, SkillAim.groundBelow(level, p, end));
            }
            case CONE -> {
                Vec3 origin, dir;
                double half;
                boolean hand = s.shape != Skill.Shape.NONE; // 투사체 부채꼴(얼음 파편) = 손 높이, 아니면 바닥
                if (hand) {
                    origin = SkillAim.handPos(level, p, eye, look, 0, 0);
                    dir = SkillAim.aimDirection(level, p, eye, look, origin, range);
                    half = Math.min(80.0, Math.max(1.0, radius));
                } else {
                    origin = SkillAim.groundBelow(level, p, feet);
                    dir = SkillAim.flatLook(look, p.getYRot());
                    half = Math.min(179.0, Math.max(1.0, radius));
                }
                flatArrow = dir.x * dir.x + dir.z * dir.z > 1.0E-4;
                put(A, origin);
                put(D, dir);
                cur[X0] = half;
                cur[X1] = hand ? 1 : 0;
                pathN = 13; // 얼음 파편 7줄기 = 짝수 번째 광선
                double h = Math.toRadians(half);
                // 얼음 파편: 투사체 비행 거리 / 뇌명 일격: 서버 inCone 은 거리 + 상대 몸 반폭(약 0.3)까지
                double reach = hand ? flight(s, range) : range + 0.3;
                Vec3 eyeO = origin.add(0, 0.8, 0); // 뇌명 일격 시야 검사 높이 (SkillTargets.inCone)
                for (int i = 0; i < pathN; i++) {
                    double a = -h + 2 * h * i / (pathN - 1);
                    Vec3 rd = dir.yRot((float) a).normalize();
                    Vec3 end = origin.add(rd.scale(reach));
                    if (hand) {
                        BlockHitResult bh = SubLevels.clip(level, new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                        if (bh.getType() != HitResult.Type.MISS) end = bh.getLocation();
                    } else {
                        // 충격파는 벽 뒤를 맞히지 않음 (서버가 시전 지점에서 시야 검사) → 벽에서 부채꼴을 자름
                        BlockHitResult bh = SubLevels.clip(level, new ClipContext(eyeO, end.add(0, 0.8, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                        if (bh.getType() != HitResult.Type.MISS) end = bh.getLocation().subtract(0, 0.8, 0);
                    }
                    putPath(i, end);
                }
            }
            case GROUND -> {
                Vec3 target = SkillAim.groundTarget(level, p, eye, look, range);
                if (target == null) {
                    // 서버도 같은 계산으로 거절함 (msg no_target) → 빨간 "바닥 없음" 표시 + HUD 안내
                    valid = false;
                    refusal = NO_GROUND;
                    put(B, eye.add(look.scale(Math.min(range, 12))));
                    put(D, look);
                    return;
                }
                put(T, target);
                if (s == Skill.THUNDER_LEAP) {
                    // 서버(IceThunderEffects.Leap)와 같은 계산: 수평 거리 제한, 포물선 높이 2.5 + 거리 × 0.25
                    Vec3 flat = target.subtract(feet).multiply(1, 0, 1);
                    double fl = flat.length();
                    Vec3 dest = fl > range ? feet.add(flat.scale(range / fl)).add(0, target.y - feet.y, 0) : target;
                    put(A, feet);
                    put(B, dest);
                    cur[X0] = 2.5 + fl * 0.25;
                    put(T, fl > range ? SkillAim.groundBelow(level, p, dest.add(0, 1.0, 0)) : target);
                }
            }
            case SELF -> put(T, SkillAim.groundBelow(level, p, feet));
            case DASH, WAVE -> {
                Vec3 start = SkillAim.groundBelow(level, p, feet);
                Vec3 dir = SkillAim.flatLook(look, p.getYRot());
                Vec3 from = start.add(0, 1.0, 0);
                BlockHitResult wall = SubLevels.clip(level, new ClipContext(from, from.add(dir.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                double len = range;
                if (wall.getType() != HitResult.Type.MISS) {
                    double d = wall.getLocation().distanceTo(from);
                    // 해일·얼음 가시는 서버와 똑같이 벽에서 멈춤, 돌진은 몸이 벽 앞에서 멈춤
                    len = s.aim == Skill.Aim.WAVE ? Math.max(s == Skill.ICE_SPIKES ? 1.5 : 1.0, d) : Math.max(0.5, d - 0.4);
                    stoppedByWall = true;
                }
                put(A, start);
                put(B, start.add(dir.scale(len)));
                put(D, dir);
                cur[X0] = len;
                if (s == Skill.ICE_SPIKES) {
                    // 가시는 땅 높이를 따라 솟음 (서버 groundAt 과 같은 검사)
                    pathN = Math.max(2, Math.min(MAX_PATH, (int) Math.ceil(len / 1.5) + 1));
                    for (int i = 0; i < pathN; i++) {
                        Vec3 q = start.add(dir.scale(len * i / (pathN - 1)));
                        Vec3 top = q.add(0, 3.0, 0);
                        BlockHitResult down = SubLevels.clip(level, new ClipContext(top, top.add(0, -6.5, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
                        putPath(i, down.getType() == HitResult.Type.MISS ? q : down.getLocation());
                    }
                }
            }
            case BLINK -> {
                Vec3 dest = SkillAim.blinkDestination(level, p, feet, eye, look, range);
                if (dest == null) {
                    valid = false;
                    refusal = NO_SPACE;
                    put(B, eye.add(look.scale(2)));
                    put(D, look);
                    return;
                }
                put(A, feet);
                put(B, dest);
                put(T, SkillAim.groundBelow(level, p, dest));
            }
        }
    }

    /** 투사체가 실제로 날아가는 거리 (SkillProjectile.create: 속도 0.2~3.9, ceil(max(2, 사거리) / 속도) 틱) */
    private static double flight(Skill s, double range) {
        double speed = Math.max(0.2, Math.min(3.9, ClientSkillStats.speed(s)));
        return Math.ceil(Math.max(2.0, range) / speed) * speed;
    }

    /** 날아가는 길의 판정 반폭: 투사체 = 고정 판정(hit), 물줄기 = 설정 굵기(radius, 서버 Jet) */
    private static float beamHalf(Skill s, float radius) {
        return Math.max(0.3f, s == Skill.WATER_JET ? radius : s.hit);
    }

    /** 길 반폭 (서버 판정과 같게) */
    private static float pathHalf(Skill s, float radius) {
        // 돌진: 시전자 몸 상자(반폭 0.3)를 radius 만큼 키워 부딪힘 + 상대 몸 반폭 0.3 / 해일·가시: 폭의 절반
        if (s.aim == Skill.Aim.DASH) return Math.max(0.6f, radius + 0.6f);
        return Math.max(0.5f, radius);
    }

    /** 바닥 범위의 주 테두리 (서버가 실제로 판정하는 반지름) */
    private static float mainRadius(Skill s, float r) {
        return switch (s) {
            case WIND_WHIRLWIND, WIND_TEMPEST -> r + 0.5f;          // 띄우고 0.5초마다 피해 (Tornado: d <= r + 0.5)
            case WATER_PRISON -> r * 1.4f;                          // 끌어당김·피해·파열 (Prison: r × 1.4)
            // 낙뢰 3번 모두 원 r 안 (Strikes: 2·3번째는 중심에서 r - 범위 안, 첫 번째는 max(1.5, 0.8r)) → max(r, 1.5)
            case LIGHTNING_STRIKE -> Math.max(r, 1.5f);
            default -> r;
        };
    }

    /** 바깥 점선 테두리 (끌려 들어오는 범위·여진 등). 0 = 없음 */
    private static float outerRadius(Skill s, float r) {
        return switch (s) {
            case WIND_WHIRLWIND -> r * 1.6f;     // 끌어당김 + 끝에 날려 보냄
            case WIND_TEMPEST -> r * 2.3f;
            case THUNDER_JUDGMENT -> r * 1.9f;   // 마지막 여진 (r × (1 + 0.3 × 3))
            default -> 0f;
        };
    }

    /** 안쪽 테두리 (가장 센 곳). 0 = 없음 */
    private static float innerRadius(Skill s, float r) {
        return switch (s) {
            case LIGHTNING_STRIKE -> Math.max(1.5f, r * 0.8f); // 첫 낙뢰는 정확히 가운데
            case LIGHTNING_STORM -> Math.max(3f, r * 0.45f);   // 마지막 거대 번개
            case THUNDER_ROAR -> Math.max(1f, r) * 0.6f;       // 끌어당긴 뒤 폭발
            default -> 0f;
        };
    }

    /** 생물에 맞으면 멈추는지 (바람 칼날은 꿰뚫고, 물줄기는 지나감) */
    private static boolean stopsAtEntity(Skill s) {
        if (s.aim == Skill.Aim.LINE) return s == Skill.LIGHTNING_BOLT;
        return s.shape != Skill.Shape.BLADE;
    }

    /** 바닥 조준 스킬의 세로 크기 (기둥·회오리 높이, 하늘에서 떨어지는 것의 시작 높이). 0 = 없음 */
    private static float heightOf(Skill s) {
        return switch (s) {
            case FIRE_PILLAR -> 9f;
            case WIND_WHIRLWIND -> 7f;
            case WIND_TEMPEST -> 22f;
            case WATER_PRISON -> 4.5f; // 서버 Prison: 바닥 위 4.5칸까지 끌어당김
            case LIGHTNING_STORM -> 16f;
            case ICE_AGE -> 16f;
            case THUNDER_JUDGMENT -> 7f;
            default -> 0f;
        };
    }

    // =====================================================================
    // 그리기 (매 프레임: 보간 + 정점만)
    // =====================================================================

    // 지금 그리는 단계의 상태 (프레임마다 객체를 만들지 않으려고 정적 필드)
    private static BufferBuilder bb;
    private static boolean glowPass;
    private static float cr, cg, cb;

    static void render(float pt, float time) {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Skill s = aiming;
        if (p == null || mc.level == null || s == null) return;

        for (int i = 0; i < N; i++) lp[i] = prev[i] + (cur[i] - prev[i]) * pt;
        for (int i = 0; i < pathN * 3; i++) lpPath[i] = prevPath[i] + (curPath[i] - prevPath[i]) * pt;

        float fadeIn = Math.min(1f, (held - SHOW_AFTER + pt) / 3f);
        boolean blocked = SkillBarHud.remaining(s) > SkillServer.BUFFER_TICKS;
        float[] c = !valid ? INVALID : blocked ? BLOCKED : COLORS[s.element.ordinal()];
        cr = c[0];
        cg = c[1];
        cb = c[2];
        float a = (blocked ? 0.55f : 0.9f) * fadeIn;
        float range = ClientSkillStats.range(s), radius = ClientSkillStats.radius(s);

        RenderSystem.disableCull();
        // 1) 무늬 (미리 곱한 알파 섞기)
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        FxRender.spriteShader(ATLAS);
        glowPass = false;
        bb = FxRender.quads();
        scene(s, a, range, radius, time);
        FxRender.draw(bb);

        // 2) 흐르는 화살표 (일반 알파 섞기)
        if (valid && usesArrow(s)) {
            FxRender.alphaBlend();
            FxRender.spriteShader(ARROW);
            bb = FxRender.quads();
            arrows(s, a, radius, time);
            FxRender.draw(bb);
        }

        // 3) 빛 번짐 (더하기 섞기, 같은 무늬를 옅게 한 번 더)
        if (!blocked) {
            FxRender.additive();
            FxRender.spriteShader(ATLAS);
            glowPass = true;
            bb = FxRender.quads();
            scene(s, a * 0.45f, range, radius, time);
            FxRender.draw(bb);
            glowPass = false;
        }
        bb = null;
        // 4) 실제 판정 경계 = 공용 위험 표시 (시전자 본인 = SELF 스타일). 키를 떼면 같은 함수가 같은 크기로
        //    최종 예고·판정 범위를 그리므로, 조준 → 발동이 한 가지 그림 언어로 이어짐
        //    같은 스킬을 모으는 중(그 사이 같은 키를 또 누름)이면 준비 연출이 이미 실제 범위를 그림 → 두 번 그리지 않음
        if (valid && !FxManager.isCharging(p, s)) {
            FxRender.additive();
            dangers(s, a, range, radius, time, p);
        }
        FxRender.alphaBlend();
    }

    /**
     * 공용 위험 표시 (SkillFx.dangerZone/Lane, 시전자 = 나 → SELF 스타일, 차오름 0 = 아직 조준 중).
     * 바닥 범위·끌어당김 범위·돌진/해일 길·물줄기 길이 여기서 그려짐 (이 파일의 같은 테두리 선은 빼서 겹치지 않음).
     * 부채꼴과 얼음 가시 길은 벽·땅 높이에 맞춰 잘린 모양이 더 정확해서 이 파일이 직접 그림.
     */
    private static void dangers(Skill s, float a, float range, float radius, float time, LocalPlayer p) {
        Element el = s.element;
        switch (s.aim) {
            case GROUND, SELF -> {
                Vec3 t = new Vec3(lp[T], lp[T + 1], lp[T + 2]);
                float main = s.aim == Skill.Aim.GROUND ? mainRadius(s, radius) : radius;
                SkillFx.dangerZone(t, Math.max(0.5f, main), 0f, el, p, a, time);
                float outer = outerRadius(s, radius);
                if (outer > 0f) SkillFx.dangerZone(t, outer, 0f, el, p, a * 0.8f, time);
            }
            case BLINK -> SkillFx.dangerZone(new Vec3(lp[T], lp[T + 1], lp[T + 2]), Math.max(0.5f, radius), 0f, el, p, a, time);
            case DASH, WAVE -> {
                if (s == Skill.ICE_SPIKES) break;
                Vec3 start = new Vec3(lp[A], lp[A + 1], lp[A + 2]);
                Vec3 dir = new Vec3(lp[D], 0, lp[D + 2]);
                if (dir.lengthSqr() < 1.0E-6) break;
                SkillFx.dangerLane(start, dir.normalize(), (float) lp[X0], pathHalf(s, radius), 0f, el, p, a, time);
            }
            case LINE -> {
                Vec3 start = new Vec3(lp[A], lp[A + 1], lp[A + 2]), end = new Vec3(lp[B], lp[B + 1], lp[B + 2]);
                Vec3 d = end.subtract(start);
                double len = d.length();
                if (len > 0.2) SkillFx.dangerLane(start, d.scale(1.0 / len), (float) len, beamHalf(s, radius), 0f, el, p, a, time);
            }
            case PROJECTILE -> {
                // 바닥에 떨어져 터지는 범위 (벽에 맞는 경우는 벽 면의 마법진이 보여 줌)
                boolean splash = s.shape != Skill.Shape.BLADE && radius > 0.3f;
                if (splash && hasNormal && lp[NR + 1] > 0.5) {
                    SkillFx.dangerZone(new Vec3(lp[B], lp[B + 1], lp[B + 2]), radius, 0f, el, p, a * 0.9f, time);
                }
            }
            default -> { }
        }
    }

    private static boolean usesArrow(Skill s) {
        return switch (s.aim) {
            case PROJECTILE, LINE, DASH, WAVE, BLINK, CONE -> true;
            default -> false;
        };
    }

    /** 무늬 전체 (단계마다 한 번씩 불림: glowPass 면 채우기는 빼고 빛나는 선·고리만) */
    private static void scene(Skill s, float a, float range, float radius, float time) {
        if (!valid) {
            // 바닥·도착 지점 없음: 화면 쪽을 향한 빨간 표시
            double bx = lp[B], by = lp[B + 1], bz = lp[B + 2];
            double nx = -lp[D], ny = -lp[D + 1], nz = -lp[D + 2];
            normalQuad(R_MARK, bx, by, bz, nx, ny, nz, 0.9, time * 0.05, a * 0.9f);
            normalQuad(R_RUNE, bx, by, bz, nx, ny, nz, 1.25, -time * 0.03, a * 0.6f);
            return;
        }
        switch (s.aim) {
            case PROJECTILE, LINE -> projectile(s, a, radius, time);
            case GROUND -> ground(s, a, range, radius, time);
            case SELF -> {
                area(lp[T], lp[T + 1] + 0.06, lp[T + 2], radius, a, time, false, false);
                extraRings(s, lp[T], lp[T + 1] + 0.06, lp[T + 2], radius, a, time);
            }
            case DASH, WAVE -> path(s, a, radius, time);
            case BLINK -> {
                double gx = lp[T], gy = lp[T + 1] + 0.06, gz = lp[T + 2];
                area(gx, gy, gz, radius, a, time, false, false);
                flatQuad(R_MARK, gx, gy + 0.01, gz, Math.min(1.1, radius * 0.4), -time * 0.05, a);
            }
            case CONE -> cone(a, range, time);
        }
    }

    // ---------------------------------------------------------------------
    // 모양별
    // ---------------------------------------------------------------------

    private static void projectile(Skill s, float a, float radius, float time) {
        double sx = lp[A], sy = lp[A + 1], sz = lp[A + 2];
        double ex = lp[B], ey = lp[B + 1], ez = lp[B + 2];
        float splash = s.shape == Skill.Shape.BLADE || s.aim == Skill.Aim.LINE ? 0f : radius;
        float hitR = beamHalf(s, radius);
        // 손에서 나가는 빛
        billboard(R_DOT, sx, sy, sz, 0.55 + 0.1 * Math.sin(time * 0.5), a * 0.8f);
        // 길 양옆 가는 선 (물줄기·번개 줄기는 공용 위험 표시가 길을 그림)
        if (flatArrow && s.aim == Skill.Aim.PROJECTILE) {
            double dx = lp[D], dz = lp[D + 2];
            double l = Math.sqrt(dx * dx + dz * dz);
            if (l > 1.0E-4) {
                double px = -dz / l * hitR, pz = dx / l * hitR;
                segLine(sx + px, sy, sz + pz, ex + px, ey, ez + pz, 0.035, a * 0.15f, a * 0.55f, true);
                segLine(sx - px, sy, sz - pz, ex - px, ey, ez - pz, 0.035, a * 0.15f, a * 0.55f, true);
            }
        }
        if (hasNormal) {
            double nx = lp[NR], ny = lp[NR + 1], nz = lp[NR + 2];
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl < 1.0E-3) {
                nx = 0;
                ny = 1;
                nz = 0;
            } else {
                nx /= nl;
                ny /= nl;
                nz /= nl;
            }
            double cx = ex + nx * 0.06, cy = ey + ny * 0.06, cz = ez + nz * 0.06;
            if (splash > 0) {
                if (!glowPass) normalQuad(R_FILL, cx, cy, cz, nx, ny, nz, Math.max(0.6f, splash), 0, a * 0.45f);
                normalQuad(R_RUNE, cx, cy, cz, nx, ny, nz, Math.max(0.6f, splash), time * 0.03, a * 0.85f);
                float wave = (time * 0.05f) % 1f;
                normalQuad(R_FILL, cx, cy, cz, nx, ny, nz, Math.max(0.6f, splash) * wave, 0, a * 0.35f * (1f - wave));
            }
            normalQuad(R_MARK, cx + nx * 0.01, cy + ny * 0.01, cz + nz * 0.01, nx, ny, nz,
                    hitEntity ? 0.9 : Math.max(0.5f, hitR) * 1.2, -time * 0.06, a * (hitEntity ? 1f : 0.8f));
            if (hitEntity) billboard(R_DIAM, ex, ey, ez, 0.8 + 0.2 * Math.sin(time * 0.9), a);
        } else {
            // 허공에서 끝남: 끝 지점에 날아가는 쪽을 향한 옅은 표시
            normalQuad(R_MARK, ex, ey, ez, -lp[D], -lp[D + 1], -lp[D + 2], Math.max(0.5f, splash * 0.5f), time * 0.05, a * 0.45f);
        }
        if (s == Skill.LIGHTNING_BOLT) {
            // 연쇄 범위 (맞은 곳 아래 바닥)
            double gy = lp[T + 1] + 0.06;
            if (!glowPass) flatQuad(R_FILL, lp[T], gy, lp[T + 2], radius, 0, a * 0.18f);
            ringLine(lp[T], gy + 0.01, lp[T + 2], radius, 0.06, a * 0.5f, 6, time * 0.12, 0.25f);
        }
    }

    private static void ground(Skill s, float a, float range, float radius, float time) {
        // 발밑 최대 사거리 (점선이 천천히 돔)
        if (!glowPass) ringLine(lp[G], lp[G + 1] + 0.05, lp[G + 2], range, 0.05, a * 0.3f, 24, time * 0.05, 0.15f);
        double tx = lp[T], ty = lp[T + 1] + 0.06, tz = lp[T + 2];
        boolean ult = s.ultimate();
        float main = mainRadius(s, radius);
        area(tx, ty, tz, main, a, time, ult, false);
        extraRings(s, tx, ty, tz, radius, a, time);
        float h = heightOf(s);
        if (h > 0) column(tx, ty, tz, main, h, a, time, ult);
        if (s == Skill.THUNDER_LEAP) leapArc(a, time);
    }

    /** 바깥 점선(끌려 오는 범위·여진) + 안쪽 고리(가장 센 곳) */
    private static void extraRings(Skill s, double x, double y, double z, float radius, float a, float time) {
        float outer = outerRadius(s, radius);
        if (outer > 0f) {
            // 바깥 테두리 선은 공용 위험 표시(dangers)가 그림 — 여기는 안쪽으로 흐르는 점만
            if (!glowPass) {
                // 안쪽으로 흐르는 점 (끌려 들어감) / 여진은 바깥으로
                float f = (time * 0.03f) % 1f;
                boolean pull = s != Skill.THUNDER_JUDGMENT;
                float main = mainRadius(s, radius);
                double rr = pull ? outer - (outer - main) * f : main + (outer - main) * f;
                ringLine(x, y + 0.02, z, rr, 0.04, a * 0.4f * (float) Math.sin(Math.PI * f), 0, 0, 1f);
            }
        }
        float inner = innerRadius(s, radius);
        if (inner > 0f) {
            if (!glowPass) flatQuad(R_FILL, x, y + 0.002, z, inner, 0, a * 0.3f);
            ringLine(x, y + 0.02, z, inner, 0.07, a * 0.9f, 6, time * 0.2, 0.4f);
        }
    }

    /** 범위 마법진: 부드러운 바닥 빛 + 도는 룬 고리 + 빛이 도는 테두리 + 퍼지는 파동 + 가운데 표식. 궁극기는 겹 마법진 */
    private static void area(double x, double y, double z, float r, float a, float time, boolean ult, boolean edge) {
        r = Math.max(0.5f, r);
        if (!glowPass) {
            flatQuad(R_FILL, x, y, z, r, 0, a * 0.42f);
            if (ult) flatQuad(R_HEX, x, y + 0.004, z, r * 0.97, time * 0.004, a * 0.35f);
        }
        flatQuad(R_RUNE, x, y + 0.008, z, r * 0.98, time * 0.018, a * 0.9f);
        // 판정 끝 테두리는 공용 위험 표시(dangers)가 그림. edge = true 는 판정 범위가 아닌 표식(돌진 끝 등)
        if (edge) ringLine(x, y + 0.012, z, r, 0.08 + r * 0.008, a, 3, time * 0.16, 0.5f);
        float wave = (time * 0.035f) % 1f;
        ringLine(x, y + 0.014, z, r * (0.15f + 0.85f * wave), 0.05 + 0.04 * wave, a * 0.65f * (1f - wave), 0, 0, 1f);
        flatQuad(R_MARK, x, y + 0.016, z, Math.min(1.3, 0.35 + r * 0.22), -time * 0.04, a * 0.85f);
        if (ult) {
            // 바깥 역회전 룬 고리 + 도는 다이아 8개 + 바깥 점선
            flatQuad(R_RUNE, x, y + 0.006, z, r * 1.24, -time * 0.011, a * 0.6f);
            ringLine(x, y + 0.01, z, r * 1.32, 0.045, a * 0.7f, 8, -time * 0.1, 0.1f);
            for (int i = 0; i < 8; i++) {
                double ang = i * TAU / 8 - time * 0.011;
                double rr = r * 1.18;
                flatQuad(R_DIAM, x + Math.cos(ang) * rr, y + 0.02, z + Math.sin(ang) * rr, 0.35 + r * 0.025, ang, a * 0.9f);
            }
            float w2 = ((time * 0.035f) + 0.5f) % 1f;
            ringLine(x, y + 0.015, z, r * (0.15f + 1.1f * w2), 0.06, a * 0.5f * (1f - w2), 0, 0, 1f);
        }
    }

    /** 세로 크기 표시: 위쪽 옅은 고리 + 둘레에서 위로 솟는 빛줄기 3개 (궁극기는 가운데 빛기둥) */
    private static void column(double x, double y, double z, float r, float h, float a, float time, boolean ult) {
        double top = y + h;
        float rr = Math.max(0.5f, r * 0.75f);
        ringLine(x, top, z, rr, 0.06, a * 0.35f, 4, -time * 0.12, 0.3f);
        for (int i = 0; i < 3; i++) {
            double ang = i * TAU / 3 + time * 0.02;
            double px = x + Math.cos(ang) * r, pz = z + Math.sin(ang) * r;
            double qx = x + Math.cos(ang) * rr, qz = z + Math.sin(ang) * rr;
            camLine(px, y + 0.05, pz, qx, top, qz, 0.05, a * 0.45f, 0f);
            // 위로 올라가는 빛 구슬
            float f = (float) ((time * 0.03 + i / 3.0) % 1.0);
            billboard(R_DOT, px + (qx - px) * f, y + h * f, pz + (qz - pz) * f, 0.45, a * 0.8f * (1f - f));
        }
        if (ult) camLine(x, y + 0.05, z, x, top, z, 0.18 + r * 0.02, a * 0.3f, a * 0.05f);
    }

    /** 천둥 도약: 발에서 착지점까지 포물선 (서버와 같은 식) + 궤적을 따라 흐르는 빛 구슬 */
    private static void leapArc(float a, float time) {
        double x0 = lp[A], y0 = lp[A + 1] + 0.1, z0 = lp[A + 2];
        double x1 = lp[B], y1 = lp[B + 1] + 0.1, z1 = lp[B + 2];
        double arc = lp[X0];
        final int n = 24;
        double px = x0, py = y0, pz = z0;
        for (int i = 1; i <= n; i++) {
            double k = (double) i / n;
            double qx = x0 + (x1 - x0) * k, qy = y0 + (y1 - y0) * k + 4 * arc * k * (1 - k), qz = z0 + (z1 - z0) * k;
            double k0 = (double) (i - 1) / n;
            float a0 = a * (float) Math.min(1.0, k0 * 6) * 0.85f, a1 = a * (float) Math.min(1.0, k * 6) * 0.85f;
            camLine(px, py, pz, qx, qy, qz, 0.07, a0, a1);
            px = qx;
            py = qy;
            pz = qz;
        }
        for (int j = 0; j < 4; j++) {
            double k = (time * 0.04 + j / 4.0) % 1.0;
            double bx = x0 + (x1 - x0) * k, by = y0 + (y1 - y0) * k + 4 * arc * k * (1 - k), bz = z0 + (z1 - z0) * k;
            billboard(R_DOT, bx, by, bz, 0.5, a * (float) Math.sin(Math.PI * k));
        }
        // 꼭대기 표시
        double tx = (x0 + x1) * 0.5, ty = (y0 + y1) * 0.5 + arc, tz = (z0 + z1) * 0.5;
        billboard(R_DIAM, tx, ty, tz, 0.55, a * 0.6f);
    }

    /** 돌진·해일·얼음 가시: 길 양쪽 테두리 선 + 앞으로 쓸고 가는 가로줄 + 끝 표시 */
    private static void path(Skill s, float a, float radius, float time) {
        float halfW = pathHalf(s, radius);
        double dx = lp[D], dz = lp[D + 2];
        double l = Math.sqrt(dx * dx + dz * dz);
        if (l < 1.0E-4) return;
        dx /= l;
        dz /= l;
        double px = -dz * halfW, pz = dx * halfW;
        int n = pathN >= 2 ? pathN : 2;
        // 길 점 (가시는 땅을 따라, 나머지는 시작~끝 직선)
        for (int i = 0; i < n - 1; i++) {
            double ax, ay, az, bx, by, bz;
            if (pathN >= 2) {
                ax = lpPath[i * 3]; ay = lpPath[i * 3 + 1] + 0.08; az = lpPath[i * 3 + 2];
                bx = lpPath[i * 3 + 3]; by = lpPath[i * 3 + 4] + 0.08; bz = lpPath[i * 3 + 5];
            } else {
                ax = lp[A]; ay = lp[A + 1] + 0.08; az = lp[A + 2];
                bx = lp[B]; by = lp[B + 1] + 0.08; bz = lp[B + 2];
            }
            float f0 = (float) i / (n - 1), f1 = (float) (i + 1) / (n - 1);
            float a0 = a * (0.3f + 0.6f * f0), a1 = a * (0.3f + 0.6f * f1);
            if (s == Skill.ICE_SPIKES) {
                // 땅 높이를 따라가는 길이라 직접 그림 (돌진·해일의 곧은 길 테두리는 공용 위험 표시가 그림)
                segLine(ax + px, ay, az + pz, bx + px, by, bz + pz, 0.06, a0, a1, true);
                segLine(ax - px, ay, az - pz, bx - px, by, bz - pz, 0.06, a0, a1, true);
            }
            if (!glowPass) fillStrip(ax, ay, az, bx, by, bz, px, pz, a0 * 0.25f, a1 * 0.25f);
        }
        // 앞으로 쓸고 가는 가로줄
        float f = (time * 0.045f) % 1f;
        double fx, fy, fz;
        if (pathN >= 2) {
            double idx = f * (n - 1);
            int i0 = Math.min(n - 2, (int) idx);
            double t = idx - i0;
            fx = lpPath[i0 * 3] + (lpPath[i0 * 3 + 3] - lpPath[i0 * 3]) * t;
            fy = lpPath[i0 * 3 + 1] + (lpPath[i0 * 3 + 4] - lpPath[i0 * 3 + 1]) * t + 0.1;
            fz = lpPath[i0 * 3 + 2] + (lpPath[i0 * 3 + 5] - lpPath[i0 * 3 + 2]) * t;
        } else {
            fx = lp[A] + (lp[B] - lp[A]) * f;
            fy = lp[A + 1] + (lp[B + 1] - lp[A + 1]) * f + 0.1;
            fz = lp[A + 2] + (lp[B + 2] - lp[A + 2]) * f;
        }
        segLine(fx - px, fy, fz - pz, fx + px, fy, fz + pz, 0.09, a * (1f - f) * 0.9f, a * (1f - f) * 0.9f, true);
        // 끝
        double ex, ey, ez;
        if (pathN >= 2) {
            ex = lpPath[(n - 1) * 3]; ey = lpPath[(n - 1) * 3 + 1] + 0.1; ez = lpPath[(n - 1) * 3 + 2];
        } else {
            ex = lp[B]; ey = lp[B + 1] + 0.1; ez = lp[B + 2];
        }
        if (s.aim == Skill.Aim.WAVE) {
            // 파도가 멈추는 앞줄 (벽에 막히면 더 밝게)
            float ea = a * (stoppedByWall ? 1f : 0.75f);
            segLine(ex - px, ey, ez - pz, ex + px, ey, ez + pz, 0.12, ea, ea, true);
            for (int k = -1; k <= 1; k += 2) {
                billboard(R_DIAM, ex + px * k, ey + 0.1, ez + pz * k, 0.55, ea);
            }
        } else {
            area(ex, ey - 0.03, ez, Math.max(0.8f, halfW), a * 0.8f, time, false, true);
        }
    }

    /** 부채꼴: 바닥(뇌명 일격) 또는 손 높이(얼음 파편, 파편 줄기가 벽에서 멈춤) */
    private static void cone(float a, float range, float time) {
        boolean hand = lp[X1] > 0.5;
        int n = pathN;
        if (n < 2) return;
        double ox = lp[A], oy = lp[A + 1] + (hand ? 0 : 0.07), oz = lp[A + 2];
        double lift = hand ? 0 : 0.07;
        // 채우기 (원점 + 광선 끝들로 된 부채)
        if (!glowPass) {
            for (int i = 0; i < n - 1; i++) {
                double ax = lpPath[i * 3], ay = lpPath[i * 3 + 1] + lift, az = lpPath[i * 3 + 2];
                double bx = lpPath[i * 3 + 3], by = lpPath[i * 3 + 4] + lift, bz = lpPath[i * 3 + 5];
                float u = (R_FILL[0] + R_FILL[2]) * 0.5f, v = (R_FILL[1] + R_FILL[3]) * 0.5f;
                float ue = R_FILL[2] - 0.004f;
                float fa = a * 0.32f;
                vtx(ox, oy, oz, u, v, fa * 0.6f);
                vtx(ox, oy, oz, u, v, fa * 0.6f);
                vtx(bx, by, bz, ue, v, fa);
                vtx(ax, ay, az, ue, v, fa);
            }
        }
        // 양쪽 가장자리 선 + 끝 호
        int last = (n - 1) * 3;
        edge(hand, ox, oy, oz, lpPath[0], lpPath[1] + lift, lpPath[2], 0.06, a * 0.3f, a);
        edge(hand, ox, oy, oz, lpPath[last], lpPath[last + 1] + lift, lpPath[last + 2], 0.06, a * 0.3f, a);
        for (int i = 0; i < n - 1; i++) {
            edge(hand, lpPath[i * 3], lpPath[i * 3 + 1] + lift, lpPath[i * 3 + 2],
                    lpPath[i * 3 + 3], lpPath[i * 3 + 4] + lift, lpPath[i * 3 + 5], 0.07, a, a);
        }
        // 얼음 파편 7줄기 (짝수 번째 광선) + 끝의 다이아
        if (hand) {
            for (int i = 0; i < n; i += 2) {
                double ex = lpPath[i * 3], ey = lpPath[i * 3 + 1], ez = lpPath[i * 3 + 2];
                camLine(ox, oy, oz, ex, ey, ez, 0.03, a * 0.1f, a * 0.55f);
                billboard(R_DIAM, ex, ey, ez, 0.5, a * 0.9f);
            }
        }
        // 바깥으로 퍼지는 충격파 호 2개
        for (int w = 0; w < 2; w++) {
            float f = (float) ((time * 0.05 + w * 0.5) % 1.0);
            float wa = a * 0.8f * (1f - f) * Math.min(1f, f * 5f);
            double px = 0, py = 0, pz = 0;
            for (int i = 0; i < n; i++) {
                double qx = ox + (lpPath[i * 3] - ox) * f, qy = oy + (lpPath[i * 3 + 1] + lift - oy) * f, qz = oz + (lpPath[i * 3 + 2] - oz) * f;
                if (i > 0) edge(hand, px, py, pz, qx, qy, qz, 0.05, wa, wa);
                px = qx;
                py = qy;
                pz = qz;
            }
        }
        billboard(R_DOT, ox, oy + (hand ? 0 : 0.2), oz, 0.6, a * 0.8f);
    }

    private static void edge(boolean cam, double x0, double y0, double z0, double x1, double y1, double z1, double hw, float a0, float a1) {
        if (cam) camLine(x0, y0, z0, x1, y1, z1, hw, a0, a1);
        else segLine(x0, y0, z0, x1, y1, z1, hw, a0, a1, true);
    }

    // ---------------------------------------------------------------------
    // 흐르는 화살표 (aim_arrow.png, 일반 알파 섞기)
    // ---------------------------------------------------------------------

    private static void arrows(Skill s, float a, float radius, float time) {
        switch (s.aim) {
            case PROJECTILE, LINE -> arrow(lp[A], lp[A + 1], lp[A + 2], lp[B], lp[B + 1], lp[B + 2], beamHalf(s, radius), a, flatArrow, time);
            case DASH, WAVE -> {
                float halfW = pathHalf(s, radius);
                if (pathN >= 2) {
                    for (int i = 0; i < pathN - 1; i++) {
                        arrow(lpPath[i * 3], lpPath[i * 3 + 1] + 0.08, lpPath[i * 3 + 2], lpPath[i * 3 + 3], lpPath[i * 3 + 4] + 0.08, lpPath[i * 3 + 5],
                                halfW * 0.85f, a * 0.8f, true, time);
                    }
                } else {
                    arrow(lp[A], lp[A + 1] + 0.08, lp[A + 2], lp[B], lp[B + 1] + 0.08, lp[B + 2], halfW, a, true, time);
                }
            }
            case BLINK -> {
                double dy = lp[B + 1] - lp[A + 1];
                double dx = lp[B] - lp[A], dz = lp[B + 2] - lp[A + 2];
                double l2 = dx * dx + dy * dy + dz * dz;
                if (l2 > 0.25) arrow(lp[A], lp[A + 1] + 0.08, lp[A + 2], lp[B], lp[B + 1] + 0.08, lp[B + 2], 0.45f, a * 0.8f, dy * dy < 0.5 * l2, time);
            }
            case CONE -> {
                double l = ClientSkillStats.range(s) * 0.55;
                double oy = lp[A + 1] + (lp[X1] > 0.5 ? 0 : 0.08);
                arrow(lp[A], oy, lp[A + 2], lp[A] + lp[D] * l, oy + lp[D + 1] * l, lp[A + 2] + lp[D + 2] * l, 0.45f, a * 0.6f, flatArrow, time);
            }
            default -> { }
        }
    }

    /** 앞으로 흐르는 화살표 띠. flat 이면 땅과 나란하게, 아니면 카메라를 향하게 */
    private static void arrow(double sx, double sy, double sz, double ex, double ey, double ez, float halfW, float a, boolean flat, float time) {
        double dx = ex - sx, dy = ey - sy, dz = ez - sz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.3) return;
        dx /= len;
        dy /= len;
        dz /= len;
        double qx, qy, qz;
        if (flat) {
            qx = -dz;
            qy = 0;
            qz = dx;
        } else {
            double cx = FxRender.cam.x - sx, cy = FxRender.cam.y - sy, cz = FxRender.cam.z - sz;
            qx = dy * cz - dz * cy;
            qy = dz * cx - dx * cz;
            qz = dx * cy - dy * cx;
        }
        double ql = Math.sqrt(qx * qx + qy * qy + qz * qz);
        if (ql < 1.0E-6) {
            qx = 1;
            qy = 0;
            qz = 0;
            ql = 1;
        }
        qx = qx / ql * halfW;
        qy = qy / ql * halfW;
        qz = qz / ql * halfW;
        float tile = Math.max(0.6f, halfW * 2f) * 1.2f;
        int steps = Math.max(1, (int) Math.ceil(len / 1.5));
        for (int i = 0; i < steps; i++) {
            double t0 = len * i / steps, t1 = len * (i + 1) / steps;
            double x0 = sx + dx * t0, y0 = sy + dy * t0, z0 = sz + dz * t0;
            double x1 = sx + dx * t1, y1 = sy + dy * t1, z1 = sz + dz * t1;
            float a0 = a * fadeAlong(t0, len), a1 = a * fadeAlong(t1, len);
            float v0 = (float) (t0 / tile) - time * 0.06f, v1 = (float) (t1 / tile) - time * 0.06f;
            FxRender.vertex(bb, x0 - qx, y0 - qy, z0 - qz, 0f, v0, cr, cg, cb, a0);
            FxRender.vertex(bb, x0 + qx, y0 + qy, z0 + qz, 1f, v0, cr, cg, cb, a0);
            FxRender.vertex(bb, x1 + qx, y1 + qy, z1 + qz, 1f, v1, cr, cg, cb, a1);
            FxRender.vertex(bb, x1 - qx, y1 - qy, z1 - qz, 0f, v1, cr, cg, cb, a1);
        }
    }

    private static float fadeAlong(double t, double len) {
        float in = (float) Math.min(1.0, t / 0.8);
        float out = (float) Math.min(1.0, (len - t) / 0.6 + 0.4);
        return in * Math.min(1f, out);
    }

    // ---------------------------------------------------------------------
    // 기본 도형 (aim_atlas, 미리 곱한 색)
    // ---------------------------------------------------------------------

    private static float[] reg(int x0, int y0, int x1, int y1) {
        return new float[]{(x0 + 1) / 512f, (y0 + 1) / 512f, (x1 - 1) / 512f, (y1 - 1) / 512f};
    }

    private static void vtx(double x, double y, double z, float u, float v, float a) {
        if (a < 0f) a = 0f;
        FxRender.vertex(bb, x, y, z, u, v, cr * a, cg * a, cb * a, a);
    }

    /** 바닥과 나란한 정사각형 (가운데 x,y,z, 반지름, 회전) */
    private static void flatQuad(float[] r, double x, double y, double z, double radius, double rot, float a) {
        if (a <= 0.003f || radius <= 0.01) return;
        double c = Math.cos(rot) * radius, s = Math.sin(rot) * radius;
        // 축 1 = (c, s), 축 2 = (-s, c)
        vtx(x - c + s, y, z - s - c, r[0], r[1], a);
        vtx(x + c + s, y, z + s - c, r[2], r[1], a);
        vtx(x + c - s, y, z + s + c, r[2], r[3], a);
        vtx(x - c - s, y, z - s + c, r[0], r[3], a);
    }

    /** 법선 n 에 수직인 정사각형 (벽·비탈) */
    private static void normalQuad(float[] r, double x, double y, double z, double nx, double ny, double nz, double radius, double rot, float a) {
        if (a <= 0.003f || radius <= 0.01) return;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1.0E-6) {
            nx = 0;
            ny = 1;
            nz = 0;
        } else {
            nx /= nl;
            ny /= nl;
            nz /= nl;
        }
        // t0 = n × ref, t1 = n × t0
        double rx = 0, ry = 1, rz = 0;
        if (Math.abs(ny) >= 0.9) {
            rx = 1;
            ry = 0;
        }
        double t0x = ny * rz - nz * ry, t0y = nz * rx - nx * rz, t0z = nx * ry - ny * rx;
        double l0 = Math.sqrt(t0x * t0x + t0y * t0y + t0z * t0z);
        t0x /= l0;
        t0y /= l0;
        t0z /= l0;
        double t1x = ny * t0z - nz * t0y, t1y = nz * t0x - nx * t0z, t1z = nx * t0y - ny * t0x;
        double c = Math.cos(rot) * radius, s = Math.sin(rot) * radius;
        double ax = t0x * c + t1x * s, ay = t0y * c + t1y * s, az = t0z * c + t1z * s;
        double bx = t1x * c - t0x * s, by = t1y * c - t0y * s, bz = t1z * c - t0z * s;
        vtx(x - ax - bx, y - ay - by, z - az - bz, r[0], r[1], a);
        vtx(x + ax - bx, y + ay - by, z + az - bz, r[2], r[1], a);
        vtx(x + ax + bx, y + ay + by, z + az + bz, r[2], r[3], a);
        vtx(x - ax + bx, y - ay + by, z - az + bz, r[0], r[3], a);
    }

    /** 카메라를 향한 정사각형 */
    private static void billboard(float[] r, double x, double y, double z, double size, float a) {
        if (a <= 0.003f) return;
        float h = (float) (size * 0.5);
        float lx = FxRender.LEFT.x * h, ly = FxRender.LEFT.y * h, lz = FxRender.LEFT.z * h;
        float ux = FxRender.UP.x * h, uy = FxRender.UP.y * h, uz = FxRender.UP.z * h;
        vtx(x - lx - ux, y - ly - uy, z - lz - uz, r[2], r[3], a);
        vtx(x - lx + ux, y - ly + uy, z - lz + uz, r[2], r[1], a);
        vtx(x + lx + ux, y + ly + uy, z + lz + uz, r[0], r[1], a);
        vtx(x + lx - ux, y + ly - uy, z + lz - uz, r[0], r[3], a);
    }

    /**
     * 바닥과 나란한 고리 선. lobes &gt; 0 이면 밝은 마디가 고리를 따라 돌아감 (phase 로 회전).
     * base = 마디 사이 밝기 (1 = 고름).
     */
    private static void ringLine(double x, double y, double z, double radius, double halfW, float a, int lobes, double phase, float base) {
        if (a <= 0.003f || radius <= 0.02) return;
        int seg = (int) Math.max(24, Math.min(112, radius * 10));
        double rin = Math.max(0, radius - halfW), rout = radius + halfW;
        double c0 = 1, s0 = 0;
        float w0 = lobeW(0, lobes, phase, base);
        for (int i = 1; i <= seg; i++) {
            double ang = TAU * i / seg;
            double c1 = Math.cos(ang), s1 = Math.sin(ang);
            float w1 = lobeW(ang, lobes, phase, base);
            vtx(x + c0 * rin, y, z + s0 * rin, R_LINE[0], R_LINE[1], a * w0);
            vtx(x + c0 * rout, y, z + s0 * rout, R_LINE[2], R_LINE[1], a * w0);
            vtx(x + c1 * rout, y, z + s1 * rout, R_LINE[2], R_LINE[3], a * w1);
            vtx(x + c1 * rin, y, z + s1 * rin, R_LINE[0], R_LINE[3], a * w1);
            c0 = c1;
            s0 = s1;
            w0 = w1;
        }
    }

    private static float lobeW(double ang, int lobes, double phase, float base) {
        if (lobes <= 0) return 1f;
        double k = Math.max(0.0, Math.cos(ang * lobes - phase));
        k *= k;
        return base + (1f - base) * (float) (k * k);
    }

    /** 선분 띠. flat 이면 바닥과 나란하게, 아니면 카메라를 향하게 */
    private static void segLine(double x0, double y0, double z0, double x1, double y1, double z1, double halfW, float a0, float a1, boolean flat) {
        if (!flat) {
            camLine(x0, y0, z0, x1, y1, z1, halfW, a0, a1);
            return;
        }
        if (a0 <= 0.003f && a1 <= 0.003f) return;
        double dx = x1 - x0, dz = z1 - z0;
        double l = Math.sqrt(dx * dx + dz * dz);
        if (l < 1.0E-5) return;
        double px = -dz / l * halfW, pz = dx / l * halfW;
        vtx(x0 - px, y0, z0 - pz, R_LINE[0], R_LINE[1], a0);
        vtx(x0 + px, y0, z0 + pz, R_LINE[2], R_LINE[1], a0);
        vtx(x1 + px, y1, z1 + pz, R_LINE[2], R_LINE[3], a1);
        vtx(x1 - px, y1, z1 - pz, R_LINE[0], R_LINE[3], a1);
    }

    /** 카메라를 향한 선분 띠 */
    private static void camLine(double x0, double y0, double z0, double x1, double y1, double z1, double halfW, float a0, float a1) {
        if (a0 <= 0.003f && a1 <= 0.003f) return;
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double cx = FxRender.cam.x - x0, cy = FxRender.cam.y - y0, cz = FxRender.cam.z - z0;
        double px = dy * cz - dz * cy, py = dz * cx - dx * cz, pz = dx * cy - dy * cx;
        double l = Math.sqrt(px * px + py * py + pz * pz);
        if (l < 1.0E-8) return;
        px = px / l * halfW;
        py = py / l * halfW;
        pz = pz / l * halfW;
        vtx(x0 - px, y0 - py, z0 - pz, R_LINE[0], R_LINE[1], a0);
        vtx(x0 + px, y0 + py, z0 + pz, R_LINE[2], R_LINE[1], a0);
        vtx(x1 + px, y1 + py, z1 + pz, R_LINE[2], R_LINE[3], a1);
        vtx(x1 - px, y1 - py, z1 - pz, R_LINE[0], R_LINE[3], a1);
    }

    /** 길 바닥의 옅은 채우기 (가운데 밝고 양옆 어두움 = LINE 그림을 넓게) */
    private static void fillStrip(double ax, double ay, double az, double bx, double by, double bz, double px, double pz, float a0, float a1) {
        // FILL 그림의 가운데 가로줄 (가장자리 밝은 테 → 옅은 가운데 → 밝은 테) 을 길 폭에 펼침
        float u0 = R_FILL[0] + 0.004f, u1 = R_FILL[2] - 0.004f;
        float vm = (R_FILL[1] + R_FILL[3]) * 0.5f;
        vtx(ax - px, ay, az - pz, u0, vm, a0);
        vtx(ax + px, ay, az + pz, u1, vm, a0);
        vtx(bx + px, by, bz + pz, u1, vm, a1);
        vtx(bx - px, by, bz - pz, u0, vm, a1);
    }
}
