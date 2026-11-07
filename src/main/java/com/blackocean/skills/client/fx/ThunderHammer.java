package com.blackocean.skills.client.fx;

import com.blackocean.skills.Element;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 뇌신의 망치 메시 (코드로 만든 상자 12개: 머리·양 끝 마개·얼굴판·가운데 띠·목·손잡이·손잡이 띠·끝 마개).
 * <pre>
 *  지역 좌표 (크기 1 기준):  x = 머리 방향(길이 1.7)  y = 손잡이 → 머리 (머리 가운데 y=0.5, 손잡이 잡는 곳 y=-0.75)
 *                           z = 머리 두께
 * </pre>
 * 정점마다 법선을 들고 있어서 "카메라 정면 = 흰빛 / 윤곽 = 보랏빛" 열기를 계산한다 (천둥 색 단계표).
 * 겉껍질(SHELL)은 상자마다 0.06 씩 키운 것 — 윤곽만 밝게 그려 빛나는 테두리.
 */
final class ThunderHammer {
    /** 머리 가운데 / 손잡이 잡는 곳 / 무게 중심의 지역 y */
    static final float HEAD_Y = 0.5f, GRIP_Y = -0.75f, MASS_Y = 0.2f;
    /** x,y,z, nx,ny,nz, u,v (삼각형으로 풀어 둠) */
    private static float[] core, shell;

    private ThunderHammer() {}

    private static void build() {
        List<float[]> c = new ArrayList<>(), s = new ArrayList<>();
        float[][] boxes = {
                {-0.60f, 0.24f, -0.29f, 0.60f, 0.76f, 0.29f},   // 머리
                {-0.76f, 0.16f, -0.36f, -0.56f, 0.84f, 0.36f},  // 왼쪽 마개
                {0.56f, 0.16f, -0.36f, 0.76f, 0.84f, 0.36f},    // 오른쪽 마개
                {-0.84f, 0.28f, -0.24f, -0.76f, 0.72f, 0.24f},  // 얼굴판
                {0.76f, 0.28f, -0.24f, 0.84f, 0.72f, 0.24f},
                {-0.13f, 0.20f, -0.33f, 0.13f, 0.80f, 0.33f},   // 가운데 띠
                {-0.12f, 0.10f, -0.12f, 0.12f, 0.24f, 0.12f},   // 목
                {-0.07f, -1.00f, -0.07f, 0.07f, 0.10f, 0.07f},  // 손잡이
                {-0.095f, -0.35f, -0.095f, 0.095f, -0.29f, 0.095f}, // 손잡이 띠
                {-0.095f, -0.62f, -0.095f, 0.095f, -0.56f, 0.095f},
                {-0.12f, -1.14f, -0.12f, 0.12f, -1.00f, 0.12f}, // 끝 마개
        };
        for (float[] b : boxes) {
            box(c, b, 0f);
            if (b[4] - b[1] > 0.1f) box(s, b, 0.06f); // 얇은 띠는 껍질 생략
        }
        core = flatten(c);
        shell = flatten(s);
    }

    private static float[] flatten(List<float[]> verts) {
        float[] out = new float[verts.size() * 8];
        for (int i = 0; i < verts.size(); i++) System.arraycopy(verts.get(i), 0, out, i * 8, 8);
        return out;
    }

    private static void box(List<float[]> out, float[] b, float g) {
        float x0 = b[0] - g, y0 = b[1] - g, z0 = b[2] - g, x1 = b[3] + g, y1 = b[4] + g, z1 = b[5] + g;
        // 면마다: 법선, 네 모서리
        face(out, 1, 0, 0, new float[][]{{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}});
        face(out, -1, 0, 0, new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}});
        face(out, 0, 1, 0, new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}});
        face(out, 0, -1, 0, new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}});
        face(out, 0, 0, 1, new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}});
        face(out, 0, 0, -1, new float[][]{{x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}});
    }

    private static void face(List<float[]> out, float nx, float ny, float nz, float[][] q) {
        // 바깥에서 볼 때 반시계 방향이 되도록 맞춤 (뒷면 버리기용)
        float ax = q[1][0] - q[0][0], ay = q[1][1] - q[0][1], az = q[1][2] - q[0][2];
        float bx = q[2][0] - q[0][0], by = q[2][1] - q[0][1], bz = q[2][2] - q[0][2];
        float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
        if (cx * nx + cy * ny + cz * nz < 0) {
            float[] t = q[1];
            q[1] = q[3];
            q[3] = t;
        }
        int[] idx = {0, 1, 2, 0, 2, 3};
        for (int i : idx) {
            float x = q[i][0], y = q[i][1], z = q[i][2];
            // 면 방향에 맞춘 평면 UV (늘어짐 없음)
            float u, v;
            if (nx != 0) { u = z; v = y; } else if (ny != 0) { u = x; v = z; } else { u = x; v = y; }
            out.add(new float[]{x, y, z, nx, ny, nz, u * 0.9f, v * 0.9f});
        }
    }

    /**
     * 망치 하나를 그린다 (더하기 섞기 상태에서). c 위치가 지역 좌표 (0, anchorY, 0) 에 온다.
     * @param Y      손잡이 → 머리 방향 (단위 벡터)
     * @param xHint  머리 방향 힌트 (Y 에 수직으로 맞춤)
     * @param s      크기 (1 = 길이 약 2블록)
     * @param erosion 0 = 꽉 참, 1 = 다 사라짐 (노이즈로 갉아 먹힘 → 나타나기·사라지기)
     * @param intensity 밝기 배율
     */
    static void draw(Vec3 c, Vec3 Y, Vec3 xHint, float s, float anchorY, float erosion, float intensity, float uvShift, boolean withShell) {
        if (core == null) build();
        if (intensity <= 0.01f || erosion >= 0.99f) return;
        Vec3 X = xHint.subtract(Y.scale(xHint.dot(Y)));
        if (X.lengthSqr() < 1.0E-6) {
            X = Y.cross(Math.abs(Y.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0));
        }
        X = X.normalize();
        Vec3 Z = X.cross(Y).normalize();
        float ox = (float) (c.x - FxRender.cam.x - Y.x * anchorY * s);
        float oy = (float) (c.y - FxRender.cam.y - Y.y * anchorY * s);
        float oz = (float) (c.z - FxRender.cam.z - Y.z * anchorY * s);
        float[] m = {(float) X.x, (float) X.y, (float) X.z, (float) Y.x, (float) Y.y, (float) Y.z, (float) Z.x, (float) Z.y, (float) Z.z};

        // 본체: 정면이 흰빛, 윤곽으로 갈수록 금빛 → 보랏빛
        FxRender.elemShader(Element.THUNDER, 1.4f, erosion, intensity);
        RenderSystem.enableCull();
        emit(core, ox, oy, oz, m, s, 0.28f, 0.62f, 0f, uvShift);
        RenderSystem.disableCull();
        if (withShell) {
            // 빛나는 테두리: 윤곽만 밝음, 바깥이 계속 갉아 먹혀 일렁임
            FxRender.elemShader(Element.THUNDER, 2.6f, Math.min(0.98f, 0.38f + erosion * 0.6f), intensity * 0.95f);
            emit(shell, ox, oy, oz, m, s, 0.02f, 0f, 0.9f, uvShift + 0.37f);
        }
    }

    private static void emit(float[] mesh, float ox, float oy, float oz, float[] m, float s, float base, float face, float rim, float uvShift) {
        BufferBuilder bb = FxRender.triangles();
        for (int k = 0; k < mesh.length; k += 8) {
            float lx = mesh[k] * s, ly = mesh[k + 1] * s, lz = mesh[k + 2] * s;
            float px = ox + m[0] * lx + m[3] * ly + m[6] * lz;
            float py = oy + m[1] * lx + m[4] * ly + m[7] * lz;
            float pz = oz + m[2] * lx + m[5] * ly + m[8] * lz;
            float nx = m[0] * mesh[k + 3] + m[3] * mesh[k + 4] + m[6] * mesh[k + 5];
            float ny = m[1] * mesh[k + 3] + m[4] * mesh[k + 4] + m[7] * mesh[k + 5];
            float nz = m[2] * mesh[k + 3] + m[5] * mesh[k + 4] + m[8] * mesh[k + 5];
            float len = (float) Math.sqrt(px * px + py * py + pz * pz);
            float facing = len > 1.0E-4f ? Math.abs(nx * px + ny * py + nz * pz) / len : 1f;
            float edge = 1f - facing;
            float h = base + face * facing + rim * edge * (float) Math.sqrt(edge);
            h = h < 0f ? 0f : (h > 1f ? 1f : h);
            bb.addVertex(px, py, pz).setUv(mesh[k + 6] + uvShift, mesh[k + 7]).setColor(1f, 1f, 1f, h);
        }
        FxRender.draw(bb);
    }
}
