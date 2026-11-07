package com.blackocean.skills.client.fx;

import com.blackocean.skills.SkillsMod;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

/**
 * 연출을 그리는 공통 도구.
 * 모든 좌표는 "카메라 기준 상대 좌표"로 넣는다 (멀리 가도 떨림이 없게).
 * <ul>
 *   <li>더하기 섞기(additive): 겹칠수록 밝아짐. 불·섬광·불씨에 사용. 텍스처의 검은색은 안 보임.</li>
 *   <li>알파 섞기: 반투명하게 덮음. 연기에만 사용.</li>
 * </ul>
 */
public final class FxRender {
    public static final ResourceLocation NOISE = tex("fire_noise_a");
    public static final ResourceLocation FLAME_SHEET = tex("flame_sheet");
    public static final ResourceLocation TRAIL = tex("trail_flame");
    public static final ResourceLocation SHOCK_RING = tex("shock_ring");
    public static final ResourceLocation GLOW = tex("flash_glow");
    public static final ResourceLocation SPARK = tex("spark");
    public static final ResourceLocation EMBER = tex("ember");
    public static final ResourceLocation SMOKE = tex("smoke_sheet");
    // ---- 능력 공용 흰 그림 (색은 정점 색으로 입힘) ----
    public static final ResourceLocation GLOW_WHITE = tex("glow_white");
    public static final ResourceLocation SPARK_WHITE = tex("spark_white");
    public static final ResourceLocation EMBER_WHITE = tex("ember_white");
    public static final ResourceLocation SHOCK_RING_WHITE = tex("shock_ring_white");
    public static final ResourceLocation BEAM = tex("beam");
    public static final ResourceLocation LEAF = tex("leaf");
    public static final ResourceLocation DROPLET = tex("droplet");
    public static final ResourceLocation MIST = tex("mist_sheet");
    public static final ResourceLocation PLASMA_SHEET = tex("plasma_sheet");
    public static final ResourceLocation WISP_SHEET = tex("wisp_sheet");
    public static final ResourceLocation SPLASH_SHEET = tex("splash_sheet");
    /** 얼음: 서리 안개 (plasma_sheet 와 같은 칸 배치) · 얼음 조각 */
    public static final ResourceLocation FROST_SHEET = tex("frost_sheet");
    public static final ResourceLocation SHARD = tex("ice_shard");
    private static final ResourceLocation[] RAMPS = {tex("fire_ramp"), tex("lightning_ramp"), tex("wind_ramp"), tex("water_ramp"), tex("ice_ramp"), tex("thunder_ramp")};

    static Vec3 cam = Vec3.ZERO;
    static float partial;
    static final Vector3f LEFT = new Vector3f();
    static final Vector3f UP = new Vector3f();
    /** 카메라가 보는 방향 (화면 뒤쪽 물체 거르기용) */
    static final Vector3f FWD = new Vector3f();

    /** 셰이더 공급자: 매번 새 람다를 만들지 않도록 하나만 둔다 */
    private static final java.util.function.Supplier<ShaderInstance> FIRE_SUPPLIER = () -> FxShaders.fireFlow;
    private static final java.util.function.Supplier<ShaderInstance> TEX_COLOR_SUPPLIER = GameRenderer::getPositionTexColorShader;
    @org.jetbrains.annotations.Nullable
    private static ShaderInstance cachedFire;
    @org.jetbrains.annotations.Nullable
    private static com.mojang.blaze3d.shaders.AbstractUniform fireParams;

    private FxRender() {}

    private static ResourceLocation tex(String name) {
        return SkillsMod.id("textures/fx/" + name + ".png");
    }

    // ---------- 눈부심 방지 (2.3) ----------
    // 1) 카메라 1.5칸 안의 모든 연출은 가까울수록 옅어짐 (0.4칸 안 = 안 보임) → 얼굴 앞에서 터져도 화면이 하얗게 덮이지 않음
    // 2) 큰 더하기 층(불덩이 구·기둥·큰 후광)이 카메라와 캐릭터 몸 사이를 지나가면 그 부분만 옅게 → 몸·대상이 계속 보임
    private static final double NEAR_FADE = 1.5, NEAR_CUT = 0.4;
    private static final int MAX_SHIELD = 8;
    /** 몸 가운데 (카메라 기준), 거리, 단위 방향, 반지름 */
    private static final float[] SH_X = new float[MAX_SHIELD], SH_Y = new float[MAX_SHIELD], SH_Z = new float[MAX_SHIELD],
            SH_D = new float[MAX_SHIELD], SH_R = new float[MAX_SHIELD];
    private static int shieldCount;
    /** true 인 동안 vertex() 가 정점마다 몸 가림 검사 (큰 덩어리 도형만 켬: 구·기둥·메시) */
    static boolean shieldLayers;
    /** 0 = 더하기, 1 = 알파, 2 = 어둡게(곱하기) — vertex() 가 옅게 할 때 무엇을 줄일지 */
    private static int blendMode;
    /** billboard 가 이미 중심 기준으로 계산했을 때 정점별 검사를 건너뜀 */
    private static boolean skipVertexFade;

    /** 가까이 있을수록 0 (카메라 기준 상대 좌표의 거리 제곱) */
    private static float nearFade(double d2) {
        if (d2 >= NEAR_FADE * NEAR_FADE) return 1f;
        float k = (float) ((Math.sqrt(d2) - NEAR_CUT) / (NEAR_FADE - NEAR_CUT));
        k = k < 0f ? 0f : (k > 1f ? 1f : k);
        return k * k * (3f - 2f * k);
    }

    /** 카메라→몸 사이에 있으면 줄어드는 배율 (0.45 ~ 1). (vx,vy,vz) = 카메라 기준 상대 좌표, extra = 도형 반지름 */
    private static float shieldAt(double vx, double vy, double vz, float extra) {
        float f = 1f;
        double l2 = vx * vx + vy * vy + vz * vz;
        for (int i = 0; i < shieldCount; i++) {
            double t = vx * SH_X[i] + vy * SH_Y[i] + vz * SH_Z[i];
            float rad = SH_R[i];
            if (t < 0.3 || t > SH_D[i] - rad * 0.6) continue;
            double perp2 = l2 - t * t;
            double rr = rad + extra;
            if (perp2 >= rr * rr) continue;
            float k = 1f - (float) (Math.sqrt(Math.max(0.0, perp2)) / rr);
            k = k * k * (3f - 2f * k);
            f = Math.min(f, 1f - 0.55f * k);
        }
        return f;
    }

    private static void collectShields(Camera camera) {
        shieldCount = 0;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;
        net.minecraft.world.entity.Entity camEnt = camera.getEntity();
        boolean detached = camera.isDetached();
        for (net.minecraft.world.entity.LivingEntity e : mc.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(cam, cam).inflate(24.0), x -> x.isAlive() && !x.isInvisible())) {
            if (shieldCount >= MAX_SHIELD) break;
            if (e == camEnt && !detached) continue; // 1인칭: 내 몸은 화면에 없음
            Vec3 c = e.getBoundingBox().getCenter();
            double dx = c.x - cam.x, dy = c.y - cam.y, dz = c.z - cam.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 1.2 || d > 24.0) continue;
            if (dx * FWD.x + dy * FWD.y + dz * FWD.z < 0) continue; // 화면 뒤
            SH_X[shieldCount] = (float) (dx / d);
            SH_Y[shieldCount] = (float) (dy / d);
            SH_Z[shieldCount] = (float) (dz / d);
            SH_D[shieldCount] = (float) d;
            SH_R[shieldCount] = Math.max(0.45f, Math.max(e.getBbWidth(), e.getBbHeight()) * 0.5f);
            shieldCount++;
        }
    }

    static void begin(RenderLevelStageEvent event) {
        Camera camera = event.getCamera();
        cam = camera.getPosition();
        partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        LEFT.set(camera.getLeftVector());
        UP.set(camera.getUpVector());
        FWD.set(camera.getLookVector());
        shieldLayers = false;
        skipVertexFade = false;
        try {
            collectShields(camera);
        } catch (RuntimeException ex) {
            shieldCount = 0;
        }

        // 카메라 회전을 정확히 한 번만 적용하도록 모델뷰 행렬을 직접 지정
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        mv.mul(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
    }

    static void end() {
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    // ---------- 섞기 방식 ----------

    static void additive() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        blendMode = 0;
    }

    static void alphaBlend() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        blendMode = 1;
    }

    /**
     * 곱하기 어둡게: 바탕 × (1 - 그림색). 밝은 바닥(눈·모래) 위에서도 선이 읽히게 경고 선 밑에 어두운 테두리를 깔 때 씀.
     * 정점 색이 클수록 더 어두워짐 (0 = 그대로). 끝나면 additive() 로 돌릴 것.
     */
    static void darken() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR);
        blendMode = 2;
    }

    // ---------- 셰이더 ----------

    /** 일반 텍스처 + 정점 색 셰이더 (바닐라). */
    static void spriteShader(ResourceLocation texture) {
        RenderSystem.setShader(TEX_COLOR_SUPPLIER);
        RenderSystem.setShaderTexture(0, texture);
    }

    /**
     * 흐르는 화염 셰이더. 정점 색의 알파 = "열기"(1 = 흰빛 중심, 0 = 붉은 가장자리).
     * @param speed 불이 흐르는 속도
     * @param erosion 0 = 꽉 참, 1 = 다 타서 사라짐 (외곽선을 갉아 먹는 정도)
     * @param intensity 전체 밝기 배율
     * @return 셰이더를 못 불러왔으면 false → 대신 궤적 텍스처로 그림
     */
    static boolean fireShader(float speed, float erosion, float intensity) {
        return elemShader(com.blackocean.skills.Element.FIRE, speed, erosion, intensity);
    }

    /**
     * 능력 색의 흐르는 셰이더 (불·번개·바람·물). 색 단계표(Sampler1)만 바뀌고 모양 계산은 같다.
     * 정점 색 알파 = 열기 (1 = 흰빛 중심, 0 = 가장자리 색).
     */
    static boolean elemShader(com.blackocean.skills.Element el, float speed, float erosion, float intensity) {
        return elemShader(el, speed, erosion, intensity, 0f);
    }

    /**
     * whiteHot: 0 = 보통, 1 = 뜨거운 곳이 하얗게 타오름 (폭발 핵·섬광 기둥).
     */
    static boolean elemShader(com.blackocean.skills.Element el, float speed, float erosion, float intensity, float whiteHot) {
        ShaderInstance s = FxShaders.fireFlow;
        if (s == null) {
            spriteShader(TRAIL);
            return false;
        }
        if (s != cachedFire || fireParams == null) {
            cachedFire = s;
            fireParams = s.safeGetUniform("FireParams");
        }
        fireParams.set(speed, erosion, intensity, whiteHot);
        RenderSystem.setShader(FIRE_SUPPLIER);
        RenderSystem.setShaderTexture(0, NOISE);
        RenderSystem.setShaderTexture(1, RAMPS[el.ordinal()]);
        return true;
    }

    /** 스프라이트 종류·능력별 그림 */
    static ResourceLocation spriteTexture(Sprite.Kind kind, com.blackocean.skills.Element el) {
        return switch (kind) {
            case FLAME -> switch (el) {
                case FIRE -> FLAME_SHEET;
                case LIGHTNING -> PLASMA_SHEET;
                case WIND -> WISP_SHEET;
                case WATER -> SPLASH_SHEET;
                case ICE -> FROST_SHEET;
                case THUNDER -> PLASMA_SHEET;
            };
            case GLOW -> GLOW_WHITE;
            case SPARK -> SPARK_WHITE;
            case EMBER -> switch (el) {
                case WIND -> LEAF;
                case WATER -> DROPLET;
                case ICE -> SHARD;
                default -> EMBER_WHITE;
            };
            case SMOKE -> el == com.blackocean.skills.Element.FIRE ? SMOKE : MIST;
        };
    }

    /** 능력 대표 색 (빛·불꽃 줄기 등) */
    static float[] tint(com.blackocean.skills.Element el) {
        return switch (el) {
            case FIRE -> new float[]{1f, 0.55f, 0.18f};
            case LIGHTNING -> new float[]{0.55f, 0.82f, 1f};
            case WIND -> new float[]{0.6f, 1f, 0.8f};
            case WATER -> new float[]{0.35f, 0.7f, 1f};
            case ICE -> new float[]{0.72f, 0.92f, 1f};
            case THUNDER -> new float[]{1f, 0.82f, 0.3f};
        };
    }

    private static final float[][] TINTS = new float[com.blackocean.skills.Element.VALUES.length][];
    private static final float[][] RING_TINTS = new float[com.blackocean.skills.Element.VALUES.length][];

    static {
        for (com.blackocean.skills.Element e : com.blackocean.skills.Element.VALUES) {
            TINTS[e.ordinal()] = tint(e);
            RING_TINTS[e.ordinal()] = ringTint(e);
        }
    }

    /** tint 의 공유 배열 (새로 만들지 않음 — 읽기만 할 것) */
    static float[] tintRef(com.blackocean.skills.Element el) {
        return TINTS[el.ordinal()];
    }

    /** ringTint 의 공유 배열 (읽기만 할 것) */
    static float[] ringTintRef(com.blackocean.skills.Element el) {
        return RING_TINTS[el.ordinal()];
    }

    /** 충격파 링 색 */
    static float[] ringTint(com.blackocean.skills.Element el) {
        return switch (el) {
            case FIRE -> new float[]{1f, 0.5f, 0.15f};
            case LIGHTNING -> new float[]{0.45f, 0.75f, 1f};
            case WIND -> new float[]{0.55f, 1f, 0.78f};
            case WATER -> new float[]{0.3f, 0.65f, 1f};
            case ICE -> new float[]{0.65f, 0.9f, 1f};
            case THUNDER -> new float[]{1f, 0.78f, 0.25f};
        };
    }

    // ---------- 버퍼 ----------

    static BufferBuilder quads() {
        return Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    static BufferBuilder triangles() {
        return Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    static void draw(BufferBuilder bb) {
        MeshData data = bb.build();
        if (data != null) {
            BufferUploader.drawWithShader(data);
        }
    }

    // ---------- 도형 ----------

    static void vertex(BufferBuilder bb, double wx, double wy, double wz, float u, float v, float r, float g, float b, float a) {
        vertexRel(bb, wx - cam.x, wy - cam.y, wz - cam.z, u, v, r, g, b, a);
    }

    /**
     * 카메라 기준 상대 좌표로 정점 하나 (메시처럼 직접 계산하는 곳용). 가까운 곳 옅게·몸 가림 옅게를 여기서 한 번에.
     * 더하기·어둡게 섞기에서는 색을, 알파 섞기에서는 알파를 줄인다 (화염 셰이더는 알파 = 열기라서 건드리면 안 됨).
     */
    static void vertexRel(BufferBuilder bb, double x, double y, double z, float u, float v, float r, float g, float b, float a) {
        if (!skipVertexFade) {
            double d2 = x * x + y * y + z * z;
            float f = nearFade(d2);
            if (shieldLayers && blendMode == 0 && shieldCount > 0) f *= shieldAt(x, y, z, 0f);
            if (f < 0.999f) {
                if (blendMode == 1) a *= f;
                else {
                    r *= f;
                    g *= f;
                    b *= f;
                }
            }
        }
        bb.addVertex((float) x, (float) y, (float) z).setUv(u, v).setColor(r, g, b, a);
    }

    /** 사각형 하나(중심 p, 크기 size)를 통째로 옅게 할 배율 (가까움 + 큰 것은 몸 가림) */
    private static float quadFade(double px, double py, double pz, float size) {
        double x = px - cam.x, y = py - cam.y, z = pz - cam.z;
        // 큰 사각형은 가장 가까운 부분 기준으로 (반지름만큼 당겨서)
        double d = Math.sqrt(x * x + y * y + z * z);
        double dn = Math.max(0.0, d - size * 0.35);
        float f = nearFade(dn * dn);
        if (size >= 1.5f && blendMode == 0 && shieldCount > 0) f *= shieldAt(x, y, z, size * 0.25f);
        return f;
    }

    /** 카메라를 항상 바라보는 사각형. */
    static void billboard(BufferBuilder bb, Vec3 p, float size, float rot,
                          float u0, float v0, float u1, float v1,
                          float r, float g, float b, float a) {
        float h = size * 0.5f;
        float qf = quadFade(p.x, p.y, p.z, size);
        if (qf <= 0.002f) return;
        if (qf < 0.999f) {
            if (blendMode == 1) a *= qf;
            else {
                r *= qf;
                g *= qf;
                b *= qf;
            }
        }
        skipVertexFade = true;
        float c = (float) Math.cos(rot) * h, s = (float) Math.sin(rot) * h;
        float ax = LEFT.x * c + UP.x * s, ay = LEFT.y * c + UP.y * s, az = LEFT.z * c + UP.z * s;
        float bx = UP.x * c - LEFT.x * s, by = UP.y * c - LEFT.y * s, bz = UP.z * c - LEFT.z * s;
        vertex(bb, p.x - ax - bx, p.y - ay - by, p.z - az - bz, u1, v1, r, g, b, a);
        vertex(bb, p.x - ax + bx, p.y - ay + by, p.z - az + bz, u1, v0, r, g, b, a);
        vertex(bb, p.x + ax + bx, p.y + ay + by, p.z + az + bz, u0, v0, r, g, b, a);
        vertex(bb, p.x + ax - bx, p.y + ay - by, p.z + az - bz, u0, v1, r, g, b, a);
        skipVertexFade = false;
    }

    /** billboard 의 좌표 버전 (Vec3 를 만들지 않음) */
    static void billboard(BufferBuilder bb, double px, double py, double pz, float size, float rot,
                          float u0, float v0, float u1, float v1,
                          float r, float g, float b, float a) {
        float h = size * 0.5f;
        float qf = quadFade(px, py, pz, size);
        if (qf <= 0.002f) return;
        if (qf < 0.999f) {
            if (blendMode == 1) a *= qf;
            else {
                r *= qf;
                g *= qf;
                b *= qf;
            }
        }
        skipVertexFade = true;
        float c = (float) Math.cos(rot) * h, s = (float) Math.sin(rot) * h;
        float ax = LEFT.x * c + UP.x * s, ay = LEFT.y * c + UP.y * s, az = LEFT.z * c + UP.z * s;
        float bx = UP.x * c - LEFT.x * s, by = UP.y * c - LEFT.y * s, bz = UP.z * c - LEFT.z * s;
        vertex(bb, px - ax - bx, py - ay - by, pz - az - bz, u1, v1, r, g, b, a);
        vertex(bb, px - ax + bx, py - ay + by, pz - az + bz, u1, v0, r, g, b, a);
        vertex(bb, px + ax + bx, py + ay + by, pz + az + bz, u0, v0, r, g, b, a);
        vertex(bb, px + ax - bx, py + ay - by, pz + az - bz, u0, v1, r, g, b, a);
        skipVertexFade = false;
    }

    /** streak 의 좌표 버전 (Vec3 를 만들지 않음) */
    static void streak(BufferBuilder bb, double tx, double ty, double tz, double hx, double hy, double hz,
                       float width, float r, float g, float b, float a) {
        double dx = hx - tx, dy = hy - ty, dz = hz - tz;
        double cx = cam.x - (tx + hx) * 0.5, cy = cam.y - (ty + hy) * 0.5, cz = cam.z - (tz + hz) * 0.5;
        double sx = dy * cz - dz * cy, sy = dz * cx - dx * cz, sz = dx * cy - dy * cx;
        double sl = sx * sx + sy * sy + sz * sz;
        if (sl < 1.0E-8) return;
        double k = width * 0.5 / Math.sqrt(sl);
        sx *= k;
        sy *= k;
        sz *= k;
        vertex(bb, tx - sx, ty - sy, tz - sz, 0, 1, r, g, b, a);
        vertex(bb, tx + sx, ty + sy, tz + sz, 0, 0, r, g, b, a);
        vertex(bb, hx + sx, hy + sy, hz + sz, 1, 0, r, g, b, a);
        vertex(bb, hx - sx, hy - sy, hz - sz, 1, 1, r, g, b, a);
    }

    /** 움직이는 방향으로 길게 늘어난 사각형 (튀는 불꽃). */
    static void streak(BufferBuilder bb, Vec3 tail, Vec3 head, float width, float r, float g, float b, float a) {
        Vec3 dir = head.subtract(tail);
        Vec3 mid = tail.add(head).scale(0.5);
        Vec3 toCam = cam.subtract(mid);
        Vec3 side = dir.cross(toCam);
        if (side.lengthSqr() < 1.0E-8) return;
        side = side.normalize().scale(width * 0.5);
        vertex(bb, tail.x - side.x, tail.y - side.y, tail.z - side.z, 0, 1, r, g, b, a);
        vertex(bb, tail.x + side.x, tail.y + side.y, tail.z + side.z, 0, 0, r, g, b, a);
        vertex(bb, head.x + side.x, head.y + side.y, head.z + side.z, 1, 0, r, g, b, a);
        vertex(bb, head.x - side.x, head.y - side.y, head.z - side.z, 1, 1, r, g, b, a);
    }

    /** 법선 n 에 수직인 평면 위의 정사각형 (충격파 링 텍스처용). */
    static void planeQuad(BufferBuilder bb, Vec3 c, Vec3 n, float radius, float rot, float r, float g, float b, float a) {
        basisInto(n.x, n.y, n.z);
        double cs = Math.cos(rot) * radius, sn = Math.sin(rot) * radius;
        double axx = BA[0] * cs + BB[0] * sn, axy = BA[1] * cs + BB[1] * sn, axz = BA[2] * cs + BB[2] * sn;
        double ayx = BB[0] * cs - BA[0] * sn, ayy = BB[1] * cs - BA[1] * sn, ayz = BB[2] * cs - BA[2] * sn;
        vertex(bb, c.x - axx - ayx, c.y - axy - ayy, c.z - axz - ayz, 0, 0, r, g, b, a);
        vertex(bb, c.x + axx - ayx, c.y + axy - ayy, c.z + axz - ayz, 1, 0, r, g, b, a);
        vertex(bb, c.x + axx + ayx, c.y + axy + ayy, c.z + axz + ayz, 1, 1, r, g, b, a);
        vertex(bb, c.x - axx + ayx, c.y - axy + ayy, c.z - axz + ayz, 0, 1, r, g, b, a);
    }

    /** basis() 의 결과를 공유 배열 BA, BB 에 넣음 (그리기 스레드 전용, 새로 만들지 않음) */
    private static final double[] BA = new double[3], BB = new double[3];

    private static void basisInto(double nx, double ny, double nz) {
        double rx, ry, rz;
        if (Math.abs(ny) < 0.9) { rx = 0; ry = 1; rz = 0; } else { rx = 1; ry = 0; rz = 0; }
        double ax = ny * rz - nz * ry, ay = nz * rx - nx * rz, az = nx * ry - ny * rx;
        double al = Math.sqrt(ax * ax + ay * ay + az * az);
        if (al < 1.0E-9) al = 1;
        ax /= al; ay /= al; az /= al;
        double bx = ny * az - nz * ay, by = nz * ax - nx * az, bz = nx * ay - ny * ax;
        double bl = Math.sqrt(bx * bx + by * by + bz * bz);
        if (bl < 1.0E-9) bl = 1;
        BA[0] = ax; BA[1] = ay; BA[2] = az;
        BB[0] = bx / bl; BB[1] = by / bl; BB[2] = bz / bl;
    }

    /**
     * 도넛 모양 고리 (화염 셰이더용). 안쪽·바깥 가장자리는 열기 0, 가운데 줄은 열기 1.
     * spin 으로 고리 위 밝은 무늬가 돌아가 보이게 한다.
     */
    static void ring(BufferBuilder bb, Vec3 c, Vec3 n, float rIn, float rOut, float heat, float spin, int segments) {
        basisInto(n.x, n.y, n.z);
        double ax = BA[0], ay = BA[1], az = BA[2], bx = BB[0], by = BB[1], bz = BB[2];
        float rMid = (rIn + rOut) * 0.5f;
        double ca0 = 1, sa0 = 0;
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
            double ca1 = Math.cos(a1), sa1 = Math.sin(a1);
            // 고리 둘레를 따라 밝기가 3번 출렁이며 회전 → 도는 것이 눈에 보임
            float w0 = 0.55f + 0.45f * (float) Math.sin(a0 * 3 + spin);
            float w1 = 0.55f + 0.45f * (float) Math.sin(a1 * 3 + spin);
            float u0 = (float) i / segments * 3f, u1 = (float) (i + 1) / segments * 3f;
            double d0x = ax * ca0 + bx * sa0, d0y = ay * ca0 + by * sa0, d0z = az * ca0 + bz * sa0;
            double d1x = ax * ca1 + bx * sa1, d1y = ay * ca1 + by * sa1, d1z = az * ca1 + bz * sa1;
            for (int k = 0; k < 2; k++) {
                float ra = k == 0 ? rIn : rMid, rb = k == 0 ? rMid : rOut;
                float ha = k == 0 ? 0f : heat, hb = k == 0 ? heat : 0f;
                vertex(bb, c.x + d0x * ra, c.y + d0y * ra, c.z + d0z * ra, u0, k * 0.5f, 1, 1, 1, ha * w0);
                vertex(bb, c.x + d1x * ra, c.y + d1y * ra, c.z + d1z * ra, u1, k * 0.5f, 1, 1, 1, ha * w1);
                vertex(bb, c.x + d1x * rb, c.y + d1y * rb, c.z + d1z * rb, u1, (k + 1) * 0.5f, 1, 1, 1, hb * w1);
                vertex(bb, c.x + d0x * rb, c.y + d0y * rb, c.z + d0z * rb, u0, (k + 1) * 0.5f, 1, 1, 1, hb * w0);
            }
            ca0 = ca1;
            sa0 = sa1;
        }
    }

    /**
     * 또렷한 띠 고리 (BEAM 텍스처와 함께, spriteShader(BEAM)). 범위 끝 표시·예고 원용.
     * 밝기가 둘레를 따라 출렁이지 않고 고르다 (ring() 과 다름). 색은 미리 알파를 곱한 값 (더하기 섞기).
     * @param flat true 면 띠 전체가 고르게 밝음 (채워지는 원판), false 면 가운데 줄이 밝고 양쪽이 부드럽게 사라짐 (선)
     */
    static void band(BufferBuilder bb, Vec3 c, Vec3 n, float rIn, float rOut, float r, float g, float b, float a, int segments, boolean flat) {
        if (rOut <= 0.01f || a <= 0.003f) return;
        rIn = Math.max(0f, rIn);
        basisInto(n.x, n.y, n.z);
        double ax = BA[0], ay = BA[1], az = BA[2], bx = BB[0], by = BB[1], bz = BB[2];
        float uIn = flat ? 0.5f : 0f, uOut = flat ? 0.5f : 1f;
        double ca0 = 1, sa0 = 0;
        for (int i = 0; i < segments; i++) {
            double a1 = Math.PI * 2 * (i + 1) / segments;
            double ca1 = Math.cos(a1), sa1 = Math.sin(a1);
            double d0x = ax * ca0 + bx * sa0, d0y = ay * ca0 + by * sa0, d0z = az * ca0 + bz * sa0;
            double d1x = ax * ca1 + bx * sa1, d1y = ay * ca1 + by * sa1, d1z = az * ca1 + bz * sa1;
            vertex(bb, c.x + d0x * rIn, c.y + d0y * rIn, c.z + d0z * rIn, uIn, 0f, r, g, b, a);
            vertex(bb, c.x + d1x * rIn, c.y + d1y * rIn, c.z + d1z * rIn, uIn, 1f, r, g, b, a);
            vertex(bb, c.x + d1x * rOut, c.y + d1y * rOut, c.z + d1z * rOut, uOut, 1f, r, g, b, a);
            vertex(bb, c.x + d0x * rOut, c.y + d0y * rOut, c.z + d0z * rOut, uOut, 0f, r, g, b, a);
            ca0 = ca1;
            sa0 = sa1;
        }
    }

    /** n 에 수직인 두 방향. */
    static Vec3[] basis(Vec3 n) {
        Vec3 ref = Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 a = n.cross(ref).normalize();
        Vec3 b = n.cross(a).normalize();
        return new Vec3[]{a, b};
    }

    static Matrix4f identity() {
        return new Matrix4f();
    }
}
