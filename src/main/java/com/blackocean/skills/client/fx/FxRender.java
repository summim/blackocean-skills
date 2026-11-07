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

    private FxRender() {}

    private static ResourceLocation tex(String name) {
        return SkillsMod.id("textures/fx/" + name + ".png");
    }

    static void begin(RenderLevelStageEvent event) {
        Camera camera = event.getCamera();
        cam = camera.getPosition();
        partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        LEFT.set(camera.getLeftVector());
        UP.set(camera.getUpVector());

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
    }

    static void alphaBlend() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    // ---------- 셰이더 ----------

    /** 일반 텍스처 + 정점 색 셰이더 (바닐라). */
    static void spriteShader(ResourceLocation texture) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
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
        ShaderInstance s = FxShaders.fireFlow;
        if (s == null) {
            spriteShader(TRAIL);
            return false;
        }
        s.safeGetUniform("FireParams").set(speed, erosion, intensity, 0.0f);
        RenderSystem.setShader(() -> s);
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
        bb.addVertex((float) (wx - cam.x), (float) (wy - cam.y), (float) (wz - cam.z)).setUv(u, v).setColor(r, g, b, a);
    }

    /** 카메라를 항상 바라보는 사각형. */
    static void billboard(BufferBuilder bb, Vec3 p, float size, float rot,
                          float u0, float v0, float u1, float v1,
                          float r, float g, float b, float a) {
        float h = size * 0.5f;
        float c = (float) Math.cos(rot) * h, s = (float) Math.sin(rot) * h;
        float ax = LEFT.x * c + UP.x * s, ay = LEFT.y * c + UP.y * s, az = LEFT.z * c + UP.z * s;
        float bx = UP.x * c - LEFT.x * s, by = UP.y * c - LEFT.y * s, bz = UP.z * c - LEFT.z * s;
        vertex(bb, p.x - ax - bx, p.y - ay - by, p.z - az - bz, u1, v1, r, g, b, a);
        vertex(bb, p.x - ax + bx, p.y - ay + by, p.z - az + bz, u1, v0, r, g, b, a);
        vertex(bb, p.x + ax + bx, p.y + ay + by, p.z + az + bz, u0, v0, r, g, b, a);
        vertex(bb, p.x + ax - bx, p.y + ay - by, p.z + az - bz, u0, v1, r, g, b, a);
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
        Vec3[] t = basis(n);
        double cs = Math.cos(rot) * radius, sn = Math.sin(rot) * radius;
        Vec3 ax = t[0].scale(cs).add(t[1].scale(sn));
        Vec3 ay = t[1].scale(cs).subtract(t[0].scale(sn));
        vertex(bb, c.x - ax.x - ay.x, c.y - ax.y - ay.y, c.z - ax.z - ay.z, 0, 0, r, g, b, a);
        vertex(bb, c.x + ax.x - ay.x, c.y + ax.y - ay.y, c.z + ax.z - ay.z, 1, 0, r, g, b, a);
        vertex(bb, c.x + ax.x + ay.x, c.y + ax.y + ay.y, c.z + ax.z + ay.z, 1, 1, r, g, b, a);
        vertex(bb, c.x - ax.x + ay.x, c.y - ax.y + ay.y, c.z - ax.z + ay.z, 0, 1, r, g, b, a);
    }

    /**
     * 도넛 모양 고리 (화염 셰이더용). 안쪽·바깥 가장자리는 열기 0, 가운데 줄은 열기 1.
     * spin 으로 고리 위 밝은 무늬가 돌아가 보이게 한다.
     */
    static void ring(BufferBuilder bb, Vec3 c, Vec3 n, float rIn, float rOut, float heat, float spin, int segments) {
        Vec3[] t = basis(n);
        float rMid = (rIn + rOut) * 0.5f;
        float[] radii = {rIn, rMid, rOut};
        float[] heats = {0.0f, heat, 0.0f};
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
            // 고리 둘레를 따라 밝기가 3번 출렁이며 회전 → 도는 것이 눈에 보임
            float w0 = 0.55f + 0.45f * (float) Math.sin(a0 * 3 + spin);
            float w1 = 0.55f + 0.45f * (float) Math.sin(a1 * 3 + spin);
            float u0 = (float) i / segments * 3f, u1 = (float) (i + 1) / segments * 3f;
            for (int k = 0; k < 2; k++) {
                Vec3 p00 = ringPoint(c, t, a0, radii[k]);
                Vec3 p10 = ringPoint(c, t, a1, radii[k]);
                Vec3 p11 = ringPoint(c, t, a1, radii[k + 1]);
                Vec3 p01 = ringPoint(c, t, a0, radii[k + 1]);
                vertex(bb, p00.x, p00.y, p00.z, u0, k * 0.5f, 1, 1, 1, heats[k] * w0);
                vertex(bb, p10.x, p10.y, p10.z, u1, k * 0.5f, 1, 1, 1, heats[k] * w1);
                vertex(bb, p11.x, p11.y, p11.z, u1, (k + 1) * 0.5f, 1, 1, 1, heats[k + 1] * w1);
                vertex(bb, p01.x, p01.y, p01.z, u0, (k + 1) * 0.5f, 1, 1, 1, heats[k + 1] * w0);
            }
        }
    }

    private static Vec3 ringPoint(Vec3 c, Vec3[] t, double a, float r) {
        return c.add(t[0].scale(Math.cos(a) * r)).add(t[1].scale(Math.sin(a) * r));
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
