package com.blackocean.skills.client.fx;

import com.blackocean.skills.Element;
import com.blackocean.skills.SkillsMod;
import com.blackocean.skills.SubLevels;
import com.blackocean.skills.client.SkillsClientConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.blackocean.skills.client.fx.FxManager.RNG;
import static com.blackocean.skills.client.fx.FxManager.rand;

/**
 * 지형 파괴 연출 (화면에만 보임 — 실제 블록은 그대로).
 * 맞은 자리 주변 땅의 실제 블록 모양·색으로 된 조각들이 튀어 오르고, 굴러 떨어지고, 사라진다.
 * 금 간 땅 무늬·흙먼지도 함께.
 * <pre>
 *  조각  : 주변 땅의 실제 블록(공기·물·블록 엔티티 제외, 없으면 바로 밑 블록)의 모델을 0.2~0.6 크기로 줄여
 *          그 자리의 밝기로 그린다. 중력으로 떨어지며 돌고, 바닥에 1~2번 튀고, 멈춘 뒤 작아지며 사라짐.
 *  먼지  : 그 블록 색의 흙먼지 덩어리 (연기 입자)
 *  땅    : 금 간 자국 무늬 (반투명) + 능력 색으로 빛나는 금 (더하기 섞기), 빛이 먼저 식고 자국은 나중에 사라짐
 * </pre>
 * 성능: 조각 최대 {@value #MAX_CHUNKS}개 (넘으면 오래된 것부터 지움), 거리·설정(debris, fxQuality)에 따라 수가 줄어듦,
 * 모델 정점은 블록 종류마다 한 번만 꺼내 둠, 그리기 중 새 객체를 만들지 않음.
 */
public final class DebrisFx {
    private static final int MAX_CHUNKS = 350;
    private static final int MAX_CRATERS = 24;
    /** 블록 모델 하나에서 그릴 최대 면 수 (복잡한 모델 비용 제한) */
    private static final int MAX_QUADS = 40;
    private static final double RENDER_DIST = 112;
    private static final float GRAVITY = 0.055f;
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final float TAU = (float) (Math.PI * 2);
    private static final Direction[] DIRS = Direction.values();
    /** 먼지는 밝은 안개 그림(바람·물 연기)으로 그리고 색을 입힘 */
    private static final Element DUST_SHEET = Element.WIND;

    static final ResourceLocation CRATER = SkillsMod.id("textures/fx/crater.png");
    static final ResourceLocation CRATER_GLOW = SkillsMod.id("textures/fx/crater_glow.png");

    private static final List<Chunk> CHUNKS = new ArrayList<>();
    private static final List<Crater> CRATERS = new ArrayList<>();
    private static final Map<BlockState, Model> MODELS = new HashMap<>();
    private static final RandomSource MODEL_RAND = RandomSource.create(42L);
    private static final BlockPos.MutableBlockPos MPOS = new BlockPos.MutableBlockPos();
    private static final BlockPos.MutableBlockPos SPOS = new BlockPos.MutableBlockPos();
    /** 맞은 점 바로 밑(대신 쓸) 블록 자리 */
    private static final BlockPos.MutableBlockPos FPOS = new BlockPos.MutableBlockPos();

    // 그리기용 (재사용)
    private static final Matrix4f MAT = new Matrix4f();
    private static final Quaternionf QTMP = new Quaternionf();
    private static final Vector3f NTMP = new Vector3f();

    /** 이번 burst 에서 쓰는 먼지 색 (마지막으로 찾은 블록) */
    private static int lastDustRgb = 0x8A7A64;
    private static int craterLayer;

    private DebrisFx() {}

    // =====================================================================
    // 공개 API
    // =====================================================================

    /**
     * 사방으로 터지는 파편.
     * @param ground 바닥 위 중심
     * @param radius 파편이 튀어나오는 땅의 반지름
     * @param count  조각 수 (설정·거리에 따라 줄어듦)
     * @param power  튀는 세기 (0.3 약함 ~ 1.5 궁극기)
     */
    public static void burst(Vec3 ground, float radius, int count, float power) {
        burst(ground, UP, radius, count, power, null);
    }

    /**
     * 면 방향이 있는 파편 (벽에 맞으면 벽 밖으로 튐).
     * @param hot null 이 아니면 조각이 처음 잠깐 그 능력 색으로 달아올라 빛나며 불씨를 흘림
     */
    public static void burst(Vec3 ground, Vec3 normal, float radius, int count, float power, @Nullable Element hot) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        float prevLod = FxManager.lod;
        FxManager.lod = FxManager.lodAt(ground);
        try {
            int n = scaled(count, ground);
            Vec3 nrm = normal.lengthSqr() > 1.0E-4 ? normal.normalize() : UP;
            boolean wall = nrm.y < 0.6;
            BlockState fallback = fallback(level, ground, nrm);
            int fallbackDust = lastDustRgb;
            makeRoom(n);
            Vec3[] b = wall ? FxRender.basis(nrm) : null;
            for (int i = 0; i < n; i++) {
                float ang = RNG.nextFloat() * TAU;
                float rr = radius * (float) Math.sqrt(RNG.nextFloat());
                double sx = ground.x + Math.cos(ang) * rr, sz = ground.z + Math.sin(ang) * rr, sy = ground.y;
                if (b != null) {
                    // 벽면 위에 퍼뜨리고 벽 안쪽 블록을 고름
                    double u = Math.cos(ang) * rr, v = Math.sin(ang) * rr;
                    sx = ground.x + b[0].x * u + b[1].x * v - nrm.x * 0.3;
                    sy = ground.y + b[0].y * u + b[1].y * v - nrm.y * 0.3;
                    sz = ground.z + b[0].z * u + b[1].z * v - nrm.z * 0.3;
                }
                BlockState st = sample(level, sx, sy, sz, !wall);
                if (st == null) {
                    st = fallback;
                    SPOS.set(FPOS);
                }
                if (st == null) continue;
                // 바깥으로 갈수록 낮고 멀리, 가운데는 높이 솟음
                float edge = radius > 0 ? rr / radius : 0f;
                float up = rand(0.22f, 0.5f) * power * (1.15f - 0.45f * edge);
                float out = rand(0.06f, 0.2f) * power * (0.6f + 0.8f * edge) + 0.02f;
                double vx, vy, vz;
                if (!wall) {
                    vx = Math.cos(ang) * out;
                    vz = Math.sin(ang) * out;
                    vy = up;
                } else {
                    Vec3 d = FxManager.randomDir();
                    if (d.dot(nrm) < 0) d = d.subtract(nrm.scale(2 * d.dot(nrm)));
                    d = d.add(nrm.scale(0.8)).normalize();
                    double sp = (up + out) * 0.9;
                    vx = d.x * sp;
                    vy = d.y * sp + 0.12 * power;
                    vz = d.z * sp;
                    sx += nrm.x * 0.6;
                    sy += nrm.y * 0.6;
                    sz += nrm.z * 0.6;
                }
                float size = rand(0.2f, 0.42f) + rand(0f, 0.18f) * Math.min(1f, power);
                double y0 = wall ? sy : Math.max(sy, SPOS.getY() + 1.0) + size * 0.5;
                addChunk(level, st, sx, y0, sz, vx, vy, vz, size, 34 + RNG.nextInt(28), hot);
            }
            int dust = Math.min(n, 6 + count / 3);
            if (n > 0 || fallback != null) dust(ground.add(nrm.scale(0.2)), radius, FxManager.count(dust), power, n > 0 ? lastDustRgb : fallbackDust);
        } finally {
            FxManager.lod = prevLod;
        }
    }

    /** 한쪽 방향(부채꼴·직선)으로 몰려 튀는 파편. dir = 수평 방향 */
    public static void directed(Vec3 ground, Vec3 dir, float range, float halfAngleDeg, int count, float power) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Vec3 d = new Vec3(dir.x, 0, dir.z);
        if (d.lengthSqr() < 1.0E-6) {
            burst(ground, Math.max(1f, range * 0.3f), count, power);
            return;
        }
        d = d.normalize();
        float prevLod = FxManager.lod;
        FxManager.lod = FxManager.lodAt(ground);
        try {
            int n = scaled(count, ground);
            BlockState fallback = fallback(level, ground, UP);
            int fallbackDust = lastDustRgb;
            makeRoom(n);
            double base = Math.atan2(d.z, d.x);
            float half = (float) Math.toRadians(Math.max(0f, halfAngleDeg));
            for (int i = 0; i < n; i++) {
                double ang = base + rand(-half, half);
                float dist = Math.max(0.3f, range * (float) Math.sqrt(RNG.nextFloat()));
                double cx = Math.cos(ang), cz = Math.sin(ang);
                double sx = ground.x + cx * dist, sz = ground.z + cz * dist;
                // 부채꼴은 땅 높이가 달라질 수 있으니 조금 위에서부터 찾음
                BlockState st = sample(level, sx, ground.y + 1.0, sz, true);
                if (st == null) {
                    st = fallback;
                    SPOS.set(FPOS);
                }
                if (st == null) continue;
                float f = range > 0 ? dist / range : 0f;
                float up = rand(0.18f, 0.45f) * power * (1.1f - 0.4f * f);
                float fwd = rand(0.1f, 0.26f) * power;
                float size = rand(0.2f, 0.4f) + rand(0f, 0.18f) * Math.min(1f, power);
                double y0 = SPOS.getY() + 1.0 + size * 0.5;
                addChunk(level, st, sx, y0, sz, cx * fwd + rand(-0.03f, 0.03f), up, cz * fwd + rand(-0.03f, 0.03f), size,
                        30 + RNG.nextInt(26), null);
            }
            // 먼지는 길을 따라
            int dust = FxManager.count(Math.min(12, 4 + count / 3));
            int rgb = n > 0 ? lastDustRgb : fallbackDust;
            for (int i = 0; i < dust; i++) {
                double ang = base + rand(-half, half) * 0.8;
                float dist = range * RNG.nextFloat();
                Vec3 at = ground.add(Math.cos(ang) * dist, 0.3, Math.sin(ang) * dist);
                dust(at, 0.6f, 1, power * 0.7f, rgb);
            }
        } finally {
            FxManager.lod = prevLod;
        }
    }

    /** 금 간 땅 + 패인 자국 무늬 (el 색으로 틈이 빛남, null = 빛 없음) */
    public static void crater(Vec3 ground, float radius, int lifeTicks, @Nullable Element el) {
        if (radius <= 0.05f || lifeTicks <= 0) return;
        if (!enabled()) {
            // 파편 연출을 끈 사람에게는 예전처럼 그을음 자국만
            SkillFx.addDecal(ground, UP, radius * 0.85f, lifeTicks);
            return;
        }
        if (CRATERS.size() >= MAX_CRATERS) CRATERS.remove(0);
        CRATERS.add(new Crater(ground, radius, lifeTicks, el, craterLayer++ & 7));
        // 가장자리에 굴러다니는 잔해 (멈춰 있는 조각)
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        int n = scaled(3 + (int) (radius * 1.6f), ground);
        if (n <= 0) return;
        makeRoom(n);
        BlockState fallback = fallback(level, ground, UP);
        for (int i = 0; i < n; i++) {
            float ang = RNG.nextFloat() * TAU;
            float rr = radius * rand(0.55f, 1.05f);
            double sx = ground.x + Math.cos(ang) * rr, sz = ground.z + Math.sin(ang) * rr;
            BlockState st = sample(level, sx, ground.y + 0.6, sz, true);
            if (st == null) {
                st = fallback;
                SPOS.set(FPOS);
            }
            if (st == null) continue;
            float size = rand(0.18f, 0.38f);
            Chunk c = addChunk(level, st, sx, SPOS.getY() + 1.0 + size * 0.3, sz, 0, 0, 0, size,
                    Math.min(lifeTicks, 120 + RNG.nextInt(60)), null);
            if (c != null) {
                c.resting = true;
                c.spin = 0f;
            }
        }
    }

    /**
     * 아래쪽 바닥 찾기 (없으면 null). 배(Sable) 위 좌표도 맞도록 SubLevels.clip 사용.
     * 다른 연출 모듈도 burst/crater 의 ground 를 구할 때 쓸 수 있음.
     */
    @Nullable
    public static Vec3 findGround(Vec3 from, double depth) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        BlockHitResult hit = SubLevels.clip(mc.level, new ClipContext(from, from.subtract(0, depth, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS ? null : hit.getLocation();
    }

    /** 지형 파편 연출이 켜져 있는지 (설정 debris) */
    public static boolean enabled() {
        try {
            return SkillsClientConfig.DEBRIS.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    // =====================================================================
    // FxManager 가 부름
    // =====================================================================

    static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clear();
            return;
        }
        if (!CHUNKS.isEmpty()) {
            int w = 0;
            for (int i = 0, n = CHUNKS.size(); i < n; i++) {
                Chunk c = CHUNKS.get(i);
                if (!c.tick(level)) CHUNKS.set(w++, c);
            }
            CHUNKS.subList(w, CHUNKS.size()).clear();
        }
        if (!CRATERS.isEmpty()) {
            float prevLod = FxManager.lod;
            int w = 0;
            for (int i = 0, n = CRATERS.size(); i < n; i++) {
                Crater c = CRATERS.get(i);
                if (!c.tick()) CRATERS.set(w++, c);
            }
            CRATERS.subList(w, CRATERS.size()).clear();
            FxManager.lod = prevLod;
        }
        if (MODELS.size() > 512) MODELS.clear();
    }

    /** 반투명 단계: 금 간 자국 + 블록 조각 */
    static void render(float pt, float time) {
        if (!CRATERS.isEmpty()) {
            FxRender.alphaBlend();
            FxRender.spriteShader(CRATER);
            RenderSystem.disableCull();
            BufferBuilder bb = FxRender.quads();
            for (Crater c : CRATERS) c.emitMark(bb, pt);
            FxRender.draw(bb);
        }
        if (!CHUNKS.isEmpty()) renderChunks(pt);
    }

    /** 더하기 섞기 단계: 빛나는 금 + 달아오른 조각의 빛 */
    static void renderAdditive(float pt, float time) {
        boolean any = false;
        for (Crater c : CRATERS) {
            if (c.el != null && c.age < c.glowLife) {
                any = true;
                break;
            }
        }
        if (any) {
            FxRender.spriteShader(CRATER_GLOW);
            RenderSystem.disableCull();
            BufferBuilder bb = FxRender.quads();
            for (Crater c : CRATERS) c.emitGlow(bb, pt, time);
            FxRender.draw(bb);
        }
        for (Chunk c : CHUNKS) {
            if (c.hot == null || c.age >= Chunk.HOT_TICKS) continue;
            float k = 1f - (c.age + pt) / Chunk.HOT_TICKS;
            if (k <= 0f) continue;
            float[] tc = FxRender.tintRef(c.hot);
            FxManager.frameGlow(c.x(pt), c.y(pt), c.z(pt), c.size * 2.6f, tc[0], tc[1], tc[2], 0.55f * k * k);
        }
    }

    static boolean isEmpty() {
        return CHUNKS.isEmpty() && CRATERS.isEmpty();
    }

    static void clear() {
        CHUNKS.clear();
        CRATERS.clear();
        MODELS.clear();
    }

    // =====================================================================
    // 내부
    // =====================================================================

    /** 설정·거리에 따른 조각 수 */
    private static int scaled(int count, Vec3 at) {
        if (count <= 0 || !enabled()) return 0;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double d = cam.distanceTo(at);
        float dist = d < 24 ? 1f : d < 48 ? 0.65f : d < 96 ? 0.35f : d < 160 ? 0.15f : 0f;
        float amount = Mth.clamp(SkillsClientConfig.PARTICLE_AMOUNT.get().floatValue(), 0.3f, 1.25f);
        float f = count * dist * amount * SkillsClientConfig.quality().debris;
        int n = (int) f;
        if (RNG.nextFloat() < f - n) n++;
        return Math.min(n, MAX_CHUNKS / 2);
    }

    private static void makeRoom(int n) {
        int over = CHUNKS.size() + n - MAX_CHUNKS;
        if (over > 0) CHUNKS.subList(0, Math.min(over, CHUNKS.size())).clear();
    }

    /**
     * (x, y, z) 기둥에서 조각으로 쓸 블록 찾기 (위에서 아래로 몇 칸). 찾은 블록 자리는 SPOS.
     * 공기·풀처럼 부딪히지 않는 블록은 건너뛰고, 물·블록 엔티티·모델 없는 블록이면 포기.
     */
    @Nullable
    private static BlockState sample(ClientLevel level, double x, double y, double z, boolean column) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        int top = Mth.floor(y + (column ? 0.45 : 0.0));
        int bottom = column ? top - 3 : top;
        for (int by = top; by >= bottom; by--) {
            SPOS.set(bx, by, bz);
            BlockState s = level.getBlockState(SPOS);
            if (s.isAir()) continue;
            if (!s.getFluidState().isEmpty()) return null;
            if (s.hasBlockEntity() || s.getRenderShape() != RenderShape.MODEL) return null;
            if (s.getCollisionShape(level, SPOS).isEmpty()) continue;
            return s;
        }
        return null;
    }

    /** 맞은 점 바로 밑(벽이면 벽 안) 블록. 자리는 FPOS, 먼지 색은 lastDustRgb 에 */
    @Nullable
    private static BlockState fallback(ClientLevel level, Vec3 p, Vec3 nrm) {
        BlockState s = sample(level, p.x - nrm.x * 0.3, p.y - nrm.y * 0.3, p.z - nrm.z * 0.3, true);
        if (s == null) s = sample(level, p.x, p.y - 0.2, p.z, true);
        if (s != null) {
            FPOS.set(SPOS);
            lastDustRgb = dustColor(level, s, SPOS);
        }
        return s;
    }

    private static int dustColor(ClientLevel level, BlockState s, BlockPos pos) {
        try {
            int c = s.getMapColor(level, pos).col;
            return c == 0 ? 0x8A7A64 : c;
        } catch (RuntimeException e) {
            return 0x8A7A64;
        }
    }

    @Nullable
    private static Chunk addChunk(ClientLevel level, BlockState st, double x, double y, double z,
                                  double vx, double vy, double vz, float size, int life, @Nullable Element hot) {
        Model m = model(st);
        if (m == null) return null;
        Chunk c = new Chunk(m, x, y, z, vx, vy, vz, size, life, hot);
        BlockPos bp = SPOS;
        int tint = -1;
        if (m.anyTinted) {
            try {
                tint = Minecraft.getInstance().getBlockColors().getColor(st, level, bp, 0);
            } catch (RuntimeException ignored) {
                tint = -1;
            }
        }
        if (tint != -1) {
            c.tr = ((tint >> 16) & 255) / 255f;
            c.tg = ((tint >> 8) & 255) / 255f;
            c.tb = (tint & 255) / 255f;
        }
        lastDustRgb = dustColor(level, st, bp);
        MPOS.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        c.light = LevelRenderer.getLightColor(level, MPOS);
        CHUNKS.add(c);
        return c;
    }

    @Nullable
    private static Model model(BlockState st) {
        Model m = MODELS.get(st);
        if (m != null) return m.empty ? null : m;
        m = buildModel(st);
        MODELS.put(st, m);
        return m.empty ? null : m;
    }

    private static Model buildModel(BlockState st) {
        List<BakedQuad> quads = new ArrayList<>();
        try {
            BakedModel bm = Minecraft.getInstance().getBlockRenderer().getBlockModel(st);
            for (Direction d : DIRS) {
                MODEL_RAND.setSeed(42L);
                quads.addAll(bm.getQuads(st, d, MODEL_RAND));
            }
            MODEL_RAND.setSeed(42L);
            quads.addAll(bm.getQuads(st, null, MODEL_RAND));
        } catch (RuntimeException e) {
            quads.clear();
        }
        int n = Math.min(MAX_QUADS, quads.size());
        int[][] verts = new int[n][];
        float[] nx = new float[n], ny = new float[n], nz = new float[n];
        boolean[] tinted = new boolean[n];
        boolean any = false;
        int k = 0;
        for (int i = 0; i < n; i++) {
            BakedQuad q = quads.get(i);
            int[] v = q.getVertices();
            if (v == null || v.length < 32) continue;
            verts[k] = v;
            Direction d = q.getDirection();
            nx[k] = d.getStepX();
            ny[k] = d.getStepY();
            nz[k] = d.getStepZ();
            tinted[k] = q.isTinted();
            any |= tinted[k];
            k++;
        }
        return new Model(k, verts, nx, ny, nz, tinted, any);
    }

    /** 흙먼지 덩어리 */
    private static void dust(Vec3 c, float radius, int n, float power, int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        // 블록 색을 조금 회색 쪽으로 (먼지는 옅게 보임)
        r = 0.35f + 0.5f * r;
        g = 0.33f + 0.5f * g;
        b = 0.3f + 0.5f * b;
        for (int i = 0; i < n; i++) {
            float ang = RNG.nextFloat() * TAU;
            float rr = radius * (0.3f + 0.8f * RNG.nextFloat());
            Vec3 d = new Vec3(Math.cos(ang), 0, Math.sin(ang));
            Vec3 at = c.add(d.scale(rr)).add(0, rand(0.1f, 0.6f), 0);
            Vec3 v = d.scale(rand(0.04f, 0.14f) * (0.5f + power)).add(0, rand(0.01f, 0.05f), 0);
            Sprite sm = new Sprite(Sprite.Kind.SMOKE, at, v, rand(1.0f, 1.8f) * (0.7f + 0.4f * power), rand(3f, 4.6f) * (0.7f + 0.5f * power),
                    28 + RNG.nextInt(24)).color(r, g, b, rand(0.35f, 0.5f)).physics(-0.0015f, 0.92f).elem(DUST_SHEET);
            sm.rot = RNG.nextFloat() * TAU;
            FxManager.spawn(sm);
        }
    }

    private static void renderChunks(float pt) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource src = mc.renderBuffers().bufferSource();
        RenderType rt = Sheets.cutoutBlockSheet();
        VertexConsumer vc = src.getBuffer(rt);
        Vec3 cam = FxRender.cam;
        Vector3f fwd = FxRender.FWD;
        double maxD2 = RENDER_DIST * RENDER_DIST;
        for (Chunk c : CHUNKS) {
            double dx = c.x(pt) - cam.x, dy = c.y(pt) - cam.y, dz = c.z(pt) - cam.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > maxD2) continue;
            if (d2 > 4 && dx * fwd.x + dy * fwd.y + dz * fwd.z < -1.0) continue; // 카메라 뒤
            float s = c.scale(pt);
            if (s <= 0.01f) continue;
            c.prevRot.slerp(c.rot, pt, QTMP);
            MAT.translation((float) dx, (float) dy, (float) dz).rotate(QTMP).scale(s).translate(-0.5f, -0.5f, -0.5f);
            Model m = c.model;
            int light = c.light;
            for (int q = 0; q < m.count; q++) {
                int[] v = m.verts[q];
                NTMP.set(m.nx[q], m.ny[q], m.nz[q]).rotate(QTMP);
                float r = 1f, g = 1f, b = 1f;
                if (m.tinted[q]) {
                    r = c.tr;
                    g = c.tg;
                    b = c.tb;
                }
                for (int k = 0; k < 4; k++) {
                    int o = k * 8;
                    vc.addVertex(MAT, Float.intBitsToFloat(v[o]), Float.intBitsToFloat(v[o + 1]), Float.intBitsToFloat(v[o + 2]))
                            .setColor(r, g, b, 1f)
                            .setUv(Float.intBitsToFloat(v[o + 4]), Float.intBitsToFloat(v[o + 5]))
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(light)
                            .setNormal(NTMP.x, NTMP.y, NTMP.z);
                }
            }
        }
        // 조각은 불투명: 깊이를 쓰고 그린 뒤 연출용 상태(깊이 안 씀·면 양쪽)로 되돌림
        RenderSystem.depthMask(true);
        src.endBatch(rt);
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        FxRender.alphaBlend();
    }

    // =====================================================================
    // 블록 모델 (블록 종류마다 한 번)
    // =====================================================================

    private static final class Model {
        final int count;
        final int[][] verts;
        final float[] nx, ny, nz;
        final boolean[] tinted;
        final boolean anyTinted, empty;

        Model(int count, int[][] verts, float[] nx, float[] ny, float[] nz, boolean[] tinted, boolean anyTinted) {
            this.count = count;
            this.verts = verts;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.tinted = tinted;
            this.anyTinted = anyTinted;
            this.empty = count == 0;
        }
    }

    // =====================================================================
    // 조각 하나
    // =====================================================================

    private static final class Chunk {
        static final int HOT_TICKS = 18;
        final Model model;
        final float size;
        final int life;
        @Nullable final Element hot;
        final Quaternionf rot = new Quaternionf(), prevRot = new Quaternionf();
        double x, y, z, px, py, pz, vx, vy, vz;
        float ax, ay, az, spin;
        float tr = 1f, tg = 1f, tb = 1f;
        int age, bounces, light;
        boolean resting;

        Chunk(Model model, double x, double y, double z, double vx, double vy, double vz, float size, int life, @Nullable Element hot) {
            this.model = model;
            this.x = this.px = x;
            this.y = this.py = y;
            this.z = this.pz = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.size = size;
            this.life = Math.max(8, life);
            this.hot = hot;
            rot.rotationXYZ(RNG.nextFloat() * TAU, RNG.nextFloat() * TAU, RNG.nextFloat() * TAU);
            prevRot.set(rot);
            newAxis();
            // 작고 빠를수록 빨리 돎
            spin = (float) (0.15 + 0.35 * RNG.nextFloat()) * (0.5f / Math.max(0.2f, size)) * (float) Math.min(1.5, 0.4 + Math.sqrt(vx * vx + vy * vy + vz * vz) * 2);
        }

        private void newAxis() {
            Vec3 a = FxManager.randomDir();
            ax = (float) a.x;
            ay = (float) a.y;
            az = (float) a.z;
        }

        double x(float pt) {
            return px + (x - px) * pt;
        }

        double y(float pt) {
            return py + (y - py) * pt;
        }

        double z(float pt) {
            return pz + (z - pz) * pt;
        }

        /** 크기: 처음 2틱 동안 튀어나오며 커지고, 마지막 14틱 동안 작아지며 사라짐 */
        float scale(float pt) {
            float t = age + pt;
            float in = Math.min(1f, 0.55f + t * 0.25f);
            float out = Math.min(1f, (life - t) / 14f);
            return size * in * Math.max(0f, out);
        }

        /** @return true 면 지움 */
        boolean tick(ClientLevel level) {
            age++;
            if (age >= life) return true;
            px = x;
            py = y;
            pz = z;
            prevRot.set(rot);
            float half = size * 0.5f;
            if (!resting) {
                vy -= GRAVITY;
                vx *= 0.985;
                vy *= 0.985;
                vz *= 0.985;
                double nx = x + vx, ny = y + vy, nz = z + vz;
                // 벽: 옆으로 가다 블록에 박히면 튕겨 나옴
                if (vx * vx + vz * vz > 1.0E-4) {
                    double top = solidTop(level, nx, y - half + 0.3, nz);
                    if (top > y - half + 0.3) {
                        vx *= -0.3;
                        vz *= -0.3;
                        nx = x;
                        nz = z;
                    }
                }
                // 바닥: 아래로 떨어지다 블록 윗면에 닿으면 튐 (1~2번)
                if (vy < 0) {
                    double bottom = ny - half;
                    double top = solidTop(level, nx, bottom, nz);
                    if (top > bottom && y - half >= top - 0.6) {
                        ny = top + half;
                        bounces++;
                        if (bounces >= 3 || -vy < 0.11) {
                            resting = true;
                            vy = 0;
                            vx *= 0.35;
                            vz *= 0.35;
                            spin *= 0.25f;
                        } else {
                            if (bounces == 1 && size > 0.34f && -vy > 0.3) puff(nx, top, nz);
                            vy = -vy * 0.36;
                            vx *= 0.6;
                            vz *= 0.6;
                            spin *= 0.6f;
                            newAxis();
                        }
                    }
                }
                x = nx;
                y = ny;
                z = nz;
                if (y < level.getMinBuildHeight() - 8) return true;
            } else {
                // 바닥에서 조금 미끄러지다 멈춤
                vx *= 0.6;
                vz *= 0.6;
                x += vx;
                z += vz;
                spin *= 0.7f;
            }
            if (spin > 0.002f) rot.rotateAxis(spin, ax, ay, az);
            if (hot != null && age < HOT_TICKS && (age & 1) == 0 && RNG.nextFloat() < 0.5f * FxManager.amount()) {
                float[] tc = FxRender.tintRef(hot);
                Sprite em = new Sprite(Sprite.Kind.EMBER, new Vec3(x, y, z), new Vec3(rand(-0.02f, 0.02f), rand(0.02f, 0.06f), rand(-0.02f, 0.02f)),
                        rand(0.1f, 0.16f), 0.03f, 10 + RNG.nextInt(8)).color(tc[0], tc[1], tc[2], 1f).physics(-0.002f, 0.95f).elem(hot);
                em.frameOffset = RNG.nextInt(100);
                FxManager.spawn(em);
            }
            if ((age & 3) == 0) {
                MPOS.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
                light = LevelRenderer.getLightColor(level, MPOS);
            }
            return false;
        }

        private void puff(double px, double py, double pz) {
            if (RNG.nextFloat() > 0.5f * FxManager.amount()) return;
            Sprite sm = new Sprite(Sprite.Kind.SMOKE, new Vec3(px, py + 0.15, pz), new Vec3(rand(-0.02f, 0.02f), 0.02, rand(-0.02f, 0.02f)),
                    0.5f + size, 1.4f + size * 2f, 16 + RNG.nextInt(10)).color(0.62f, 0.58f, 0.52f, 0.35f).physics(0f, 0.9f).elem(DUST_SHEET);
            sm.rot = RNG.nextFloat() * TAU;
            FxManager.spawn(sm);
        }
    }

    /** (x, y, z) 칸의 부딪히는 모양 윗면 높이 (없으면 아주 낮은 값) */
    private static double solidTop(ClientLevel level, double x, double y, double z) {
        MPOS.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        BlockState s = level.getBlockState(MPOS);
        if (s.isAir()) return -1.0E9;
        VoxelShape sh = s.getCollisionShape(level, MPOS);
        if (sh.isEmpty()) return -1.0E9;
        return MPOS.getY() + sh.max(Direction.Axis.Y);
    }

    // =====================================================================
    // 금 간 자국
    // =====================================================================

    private static final class Crater {
        final Vec3 pos;
        final float radius, rot;
        final int life, glowLife, layer, seed;
        @Nullable final Element el;
        int age;

        Crater(Vec3 pos, float radius, int life, @Nullable Element el, int layer) {
            this.pos = pos;
            this.radius = radius;
            this.life = life;
            this.el = el;
            this.layer = layer;
            this.rot = RNG.nextFloat() * TAU;
            this.glowLife = Math.max(20, (int) (life * 0.45f));
            this.seed = RNG.nextInt(1000);
        }

        boolean tick() {
            age++;
            if (age >= life) return true;
            // 식어 가는 금에서 올라오는 불씨·전기 (잔류)
            if (el != null && age < glowLife && (age % 3) == 0) {
                FxManager.lod = FxManager.lodAt(pos);
                float k = 1f - (float) age / glowLife;
                int n = FxManager.count((int) (1 + radius * 0.6f * k));
                float[] tc = FxRender.tintRef(el);
                for (int i = 0; i < n; i++) {
                    float ang = RNG.nextFloat() * TAU;
                    float rr = radius * 0.8f * (float) Math.sqrt(RNG.nextFloat());
                    Vec3 at = pos.add(Math.cos(ang) * rr, 0.1, Math.sin(ang) * rr);
                    if (el == Element.LIGHTNING || el == Element.THUNDER) {
                        FxManager.spawn(new Sprite(Sprite.Kind.SPARK, at, new Vec3(rand(-0.05f, 0.05f), rand(0.06f, 0.16f), rand(-0.05f, 0.05f)),
                                0.06f, 0.06f, 5 + RNG.nextInt(5)).color(tc[0], tc[1], tc[2], 1f).physics(0.01f, 0.9f).elem(el));
                    } else {
                        Sprite em = new Sprite(Sprite.Kind.EMBER, at, new Vec3(rand(-0.015f, 0.015f), rand(0.03f, 0.08f), rand(-0.015f, 0.015f)),
                                rand(0.1f, 0.18f), 0.03f, 18 + RNG.nextInt(16)).color(tc[0], tc[1], tc[2], 1f).physics(-0.002f, 0.96f).elem(el);
                        em.sway = 0.003f;
                        em.frameOffset = RNG.nextInt(100);
                        FxManager.spawn(em);
                    }
                }
            }
            return false;
        }

        void emitMark(BufferBuilder bb, float pt) {
            float t = (age + pt) / life;
            float a = (t < 0.03f ? t / 0.03f : (t > 0.7f ? 1f - (t - 0.7f) / 0.3f : 1f)) * 0.9f;
            if (a <= 0.01f) return;
            // 처음 순간 살짝 커지며 패임
            float grow = 0.8f + 0.2f * Math.min(1f, (age + pt) / 3f);
            FxRender.planeQuad(bb, pos.add(0, 0.025 + layer * 0.003, 0), UP, radius * grow, rot, 1f, 1f, 1f, a);
        }

        void emitGlow(BufferBuilder bb, float pt, float time) {
            if (el == null) return;
            float t = (age + pt) / glowLife;
            if (t >= 1f) return;
            float k = (1f - t);
            k = k * k;
            float flick = 0.85f + 0.15f * (float) Math.sin(time * 0.9f + seed);
            // 처음 몇 틱은 하얗게 달아올랐다가 능력 색으로 식음
            float white = Math.max(0f, 1f - (age + pt) / 6f);
            float[] tc = FxRender.tintRef(el);
            float a = k * flick;
            float r = (tc[0] + (1f - tc[0]) * white) * a, g = (tc[1] + (1f - tc[1]) * white) * a, b = (tc[2] + (1f - tc[2]) * white) * a;
            float grow = 0.8f + 0.2f * Math.min(1f, (age + pt) / 3f);
            FxRender.planeQuad(bb, pos.add(0, 0.04 + layer * 0.003, 0), UP, radius * grow, rot, r, g, b, a);
        }
    }
}
