package com.blackocean.skills.client.fx;

import com.blackocean.skills.SkillsMod;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 아주 작은 OBJ 읽기 도구 (삼각형, 위치/UV/법선만).
 * 메시는 Blender 또는 art_source/generators/make_meshes.py 로 만든다. +Z = 앞, 1 = 1블록.
 */
public final class ObjMesh {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<String, ObjMesh> CACHE = new HashMap<>();

    /** 삼각형 정점을 풀어서 저장: 정점 i 의 값은 [i*3 .. i*3+2] */
    public final float[] pos;
    public final float[] nrm;
    public final float[] uv;
    public final int count;

    private ObjMesh(float[] pos, float[] nrm, float[] uv) {
        this.pos = pos;
        this.nrm = nrm;
        this.uv = uv;
        this.count = pos.length / 3;
    }

    public static ObjMesh get(String name) {
        return CACHE.computeIfAbsent(name, ObjMesh::load);
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static ObjMesh load(String name) {
        ResourceLocation rl = SkillsMod.id("models/fx/" + name + ".obj");
        List<float[]> v = new ArrayList<>(), vt = new ArrayList<>(), vn = new ArrayList<>();
        List<int[]> f = new ArrayList<>();
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(rl);
            if (res.isEmpty()) {
                LOG.error("[BlackOcean Skills] 메시 파일이 없습니다: {}", rl);
                return new ObjMesh(new float[0], new float[0], new float[0]);
            }
            try (BufferedReader r = res.get().openAsReader()) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] t = line.trim().split("\\s+");
                    if (t.length == 0) continue;
                    switch (t[0]) {
                        case "v" -> v.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3])});
                        case "vt" -> vt.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2])});
                        case "vn" -> vn.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3])});
                        case "f" -> {
                            // 다각형은 부채꼴로 삼각형 분할
                            for (int k = 2; k + 1 < t.length; k++) {
                                f.add(idx(t[1]));
                                f.add(idx(t[k]));
                                f.add(idx(t[k + 1]));
                            }
                        }
                        default -> { }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("[BlackOcean Skills] 메시를 읽지 못했습니다: {}", rl, e);
            return new ObjMesh(new float[0], new float[0], new float[0]);
        }
        int n = f.size();
        float[] pos = new float[n * 3], nrm = new float[n * 3], uv = new float[n * 2];
        for (int i = 0; i < n; i++) {
            int[] ix = f.get(i);
            float[] p = v.get(ix[0]);
            System.arraycopy(p, 0, pos, i * 3, 3);
            if (ix[1] >= 0 && ix[1] < vt.size()) System.arraycopy(vt.get(ix[1]), 0, uv, i * 2, 2);
            if (ix[2] >= 0 && ix[2] < vn.size()) System.arraycopy(vn.get(ix[2]), 0, nrm, i * 3, 3);
            else nrm[i * 3 + 1] = 1f;
        }
        LOG.info("[BlackOcean Skills] 메시 {} 불러옴 ({}개 삼각형)", name, n / 3);
        return new ObjMesh(pos, nrm, uv);
    }

    /** "a/b/c" → 0부터 세는 번호 (없으면 -1) */
    private static int[] idx(String s) {
        String[] p = s.split("/");
        int a = Integer.parseInt(p[0]) - 1;
        int b = p.length > 1 && !p[1].isEmpty() ? Integer.parseInt(p[1]) - 1 : -1;
        int c = p.length > 2 && !p[2].isEmpty() ? Integer.parseInt(p[2]) - 1 : -1;
        return new int[]{a, b, c};
    }
}
