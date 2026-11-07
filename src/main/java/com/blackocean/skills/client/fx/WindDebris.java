package com.blackocean.skills.client.fx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import static com.blackocean.skills.client.fx.FxManager.RNG;

/**
 * 회오리에 빨려 들어가 깔때기를 따라 빙빙 돌며 솟는 땅 조각 (화면에만 보임).
 * 조각은 바닥 블록의 실제 그림(블록 atlas)을 잘라 쓰고, 회오리가 끝나면 바깥으로 내던져져 포물선으로 떨어진다.
 * <p>성능: 고정 크기 배열(최대 {@value #MAX}개), 객체 생성 없음, 한 번의 그리기 호출.</p>
 */
final class WindDebris {
    static final int MAX = 220;
    private static final float TAU = (float) (Math.PI * 2);

    // ---- 조각 상태 (구조체 배열) ----
    private static int n;
    private static final int[] owner = new int[MAX];
    /** 0 = 회오리를 따라 돎, 1 = 날아감(포물선) */
    private static final byte[] mode = new byte[MAX];
    private static final double[] cx = new double[MAX], cy = new double[MAX], cz = new double[MAX];
    private static final float[] ang = new float[MAX], rad = new float[MAX], hgt = new float[MAX], spin = new float[MAX], rise = new float[MAX], topH = new float[MAX];
    private static final double[] x = new double[MAX], y = new double[MAX], z = new double[MAX];
    private static final double[] px = new double[MAX], py = new double[MAX], pz = new double[MAX];
    private static final double[] vx = new double[MAX], vy = new double[MAX], vz = new double[MAX];
    private static final float[] size = new float[MAX], rot = new float[MAX], rotV = new float[MAX], shade = new float[MAX];
    private static final float[] u0 = new float[MAX], v0 = new float[MAX], u1 = new float[MAX], v1 = new float[MAX];
    private static final int[] age = new int[MAX], life = new int[MAX];

    private WindDebris() {}

    static boolean isEmpty() {
        return n == 0;
    }

    static void clear() {
        n = 0;
    }

    /**
     * 회오리 바닥에서 조각 하나를 뜯어 올림.
     * @param id 회오리 번호 (끝날 때 fling 으로 한꺼번에 내던짐)
     * @param ringR 조각이 도는 반지름 (깔때기 바닥 근처)
     * @param maxH 이 높이까지 감아 오른 뒤 바깥으로 튕겨 나감
     */
    static void lift(int id, Vec3 base, float ringR, float maxH, float sizeMul) {
        if (n >= MAX) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        int i = n++;
        owner[i] = id;
        mode[i] = 0;
        cx[i] = base.x;
        cy[i] = base.y;
        cz[i] = base.z;
        ang[i] = RNG.nextFloat() * TAU;
        rad[i] = ringR * (0.7f + RNG.nextFloat() * 0.8f);
        hgt[i] = 0.1f;
        spin[i] = 0.18f + RNG.nextFloat() * 0.16f;
        rise[i] = (0.06f + RNG.nextFloat() * 0.12f) * Math.max(1f, maxH / 8f);
        topH[i] = maxH * (0.35f + RNG.nextFloat() * 0.55f);
        size[i] = (0.14f + RNG.nextFloat() * 0.26f) * sizeMul;
        rot[i] = RNG.nextFloat() * TAU;
        rotV[i] = (RNG.nextFloat() - 0.5f) * 0.9f;
        age[i] = 0;
        life[i] = 200;
        double sx = base.x + Math.cos(ang[i]) * rad[i], sz = base.z + Math.sin(ang[i]) * rad[i];
        pick(level, i, sx, base.y, sz);
        place(i);
        px[i] = x[i];
        py[i] = y[i];
        pz[i] = z[i];
    }

    /** 이 회오리의 조각을 전부 바깥으로 내던짐 */
    static void fling(int id, float power) {
        for (int i = 0; i < n; i++) {
            if (owner[i] != id || mode[i] != 0) continue;
            launch(i, power);
        }
    }

    private static void launch(int i, float power) {
        mode[i] = 1;
        double c = Math.cos(ang[i]), s = Math.sin(ang[i]);
        double out = (0.35 + RNG.nextFloat() * 0.45) * power;
        double tan = rad[i] * spin[i] * 0.9;
        vx[i] = c * out - s * tan;
        vz[i] = s * out + c * tan;
        vy[i] = (0.15 + RNG.nextFloat() * 0.35) * power;
        life[i] = age[i] + 30 + RNG.nextInt(30);
        rotV[i] *= 1.6f;
    }

    /** 바닥 블록 그림의 한 조각을 고름 (지형 파티클처럼 그림의 1/4 크기) */
    private static void pick(ClientLevel level, int i, double sx, double sy, double sz) {
        BlockPos pos = BlockPos.containing(sx, sy - 0.2, sz);
        BlockState st = level.getBlockState(pos);
        if (st.isAir() || st.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE) st = Blocks.DIRT.defaultBlockState();
        TextureAtlasSprite sp = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(st);
        float fu = RNG.nextFloat() * 0.75f, fv = RNG.nextFloat() * 0.75f;
        float du = sp.getU1() - sp.getU0(), dv = sp.getV1() - sp.getV0();
        u0[i] = sp.getU0() + du * fu;
        v0[i] = sp.getV0() + dv * fv;
        u1[i] = u0[i] + du * 0.25f;
        v1[i] = v0[i] + dv * 0.25f;
        BlockPos lp = pos.above();
        int light = Math.max(level.getBrightness(LightLayer.BLOCK, lp), level.getBrightness(LightLayer.SKY, lp) - level.getSkyDarken());
        shade[i] = 0.3f + 0.7f * Math.max(0, Math.min(15, light)) / 15f;
    }

    private static void place(int i) {
        x[i] = cx[i] + Math.cos(ang[i]) * rad[i];
        y[i] = cy[i] + hgt[i];
        z[i] = cz[i] + Math.sin(ang[i]) * rad[i];
    }

    static void tick() {
        int w = 0;
        for (int i = 0; i < n; i++) {
            px[i] = x[i];
            py[i] = y[i];
            pz[i] = z[i];
            age[i]++;
            rot[i] += rotV[i];
            if (mode[i] == 0) {
                ang[i] += spin[i];
                hgt[i] += rise[i];
                rad[i] *= 1.004f; // 위로 갈수록 깔때기가 넓어짐
                place(i);
                if (hgt[i] >= topH[i] || age[i] > 120) launch(i, 1f);
            } else {
                vy[i] -= 0.045;
                vx[i] *= 0.985;
                vz[i] *= 0.985;
                x[i] += vx[i];
                y[i] += vy[i];
                z[i] += vz[i];
            }
            boolean dead = age[i] >= life[i] || (mode[i] == 1 && y[i] < cy[i] - 2.0);
            if (!dead) {
                if (w != i) copy(i, w);
                w++;
            }
        }
        n = w;
    }

    private static void copy(int from, int to) {
        owner[to] = owner[from];
        mode[to] = mode[from];
        cx[to] = cx[from];
        cy[to] = cy[from];
        cz[to] = cz[from];
        ang[to] = ang[from];
        rad[to] = rad[from];
        hgt[to] = hgt[from];
        spin[to] = spin[from];
        rise[to] = rise[from];
        topH[to] = topH[from];
        x[to] = x[from];
        y[to] = y[from];
        z[to] = z[from];
        px[to] = px[from];
        py[to] = py[from];
        pz[to] = pz[from];
        vx[to] = vx[from];
        vy[to] = vy[from];
        vz[to] = vz[from];
        size[to] = size[from];
        rot[to] = rot[from];
        rotV[to] = rotV[from];
        shade[to] = shade[from];
        u0[to] = u0[from];
        v0[to] = v0[from];
        u1[to] = u1[from];
        v1[to] = v1[from];
        age[to] = age[from];
        life[to] = life[from];
    }

    /** 알파 섞기 단계에서 블록 그림으로 그림 (한 번의 그리기 호출) */
    static void render(float pt) {
        if (n == 0) return;
        FxRender.alphaBlend();
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        RenderSystem.disableCull();
        BufferBuilder bb = FxRender.quads();
        float lx = FxRender.LEFT.x, ly = FxRender.LEFT.y, lz = FxRender.LEFT.z;
        float ux = FxRender.UP.x, uy = FxRender.UP.y, uz = FxRender.UP.z;
        for (int i = 0; i < n; i++) {
            double wx = px[i] + (x[i] - px[i]) * pt, wy = py[i] + (y[i] - py[i]) * pt, wz = pz[i] + (z[i] - pz[i]) * pt;
            float h = size[i] * 0.5f;
            float r = rot[i] + rotV[i] * pt;
            float c = (float) Math.cos(r) * h, s = (float) Math.sin(r) * h;
            float ax = lx * c + ux * s, ay = ly * c + uy * s, az = lz * c + uz * s;
            float bx = ux * c - lx * s, by = uy * c - ly * s, bz = uz * c - lz * s;
            float k = shade[i];
            float a = Math.min(1f, (life[i] - age[i] - pt) / 6f);
            if (a <= 0f) continue;
            FxRender.vertex(bb, wx - ax - bx, wy - ay - by, wz - az - bz, u1[i], v1[i], k, k, k, a);
            FxRender.vertex(bb, wx - ax + bx, wy - ay + by, wz - az + bz, u1[i], v0[i], k, k, k, a);
            FxRender.vertex(bb, wx + ax + bx, wy + ay + by, wz + az + bz, u0[i], v0[i], k, k, k, a);
            FxRender.vertex(bb, wx + ax - bx, wy + ay - by, wz + az - bz, u0[i], v1[i], k, k, k, a);
        }
        FxRender.draw(bb);
    }
}
