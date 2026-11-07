package com.blackocean.skills.client.fx;

import com.blackocean.skills.Element;
import com.blackocean.skills.SkillsMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static com.blackocean.skills.client.fx.FxManager.RNG;
import static com.blackocean.skills.client.fx.FxManager.clamp01;
import static com.blackocean.skills.client.fx.FxManager.count;
import static com.blackocean.skills.client.fx.FxManager.easeOutCubic;
import static com.blackocean.skills.client.fx.FxManager.rand;
import static com.blackocean.skills.client.fx.FxManager.randomDir;

/**
 * 천둥 연출의 공용 부품: 금빛 번개 줄기(Arc), 퍼지는 충격파 벽(ShockWall), 폭발 돔(Blast), 빛기둥(Pillar),
 * 바닥 무늬(Mark: 룬 원·빛나는 금 간 땅). 모두 개수 상한이 있고 clear() 로 한 번에 비운다.
 * 색: 흰빛 심 + 금빛 번짐 + 보랏빛 가장자리 (thunder_ramp).
 */
final class ThunderParts {
    static final Element EL = Element.THUNDER;
    static final Vec3 UP = new Vec3(0, 1, 0);
    static final float TAU = (float) (Math.PI * 2);
    static final ResourceLocation RUNE = SkillsMod.id("textures/fx/thunder_rune.png");
    static final ResourceLocation CRACKS = SkillsMod.id("textures/fx/thunder_cracks.png");

    // 색
    static final float[] GOLD = {1f, 0.74f, 0.22f};
    static final float[] PALE = {1f, 0.93f, 0.7f};
    static final float[] VIOLET = {0.66f, 0.36f, 1f};
    static final float[] DUST = {0.34f, 0.29f, 0.24f};

    private static final int MAX_ARCS = 260, MAX_WALLS = 24, MAX_BLASTS = 16, MAX_PILLARS = 6, MAX_MARKS = 40;
    static final List<Arc> ARCS = new ArrayList<>();
    static final List<ShockWall> WALLS = new ArrayList<>();
    static final List<Blast> BLASTS = new ArrayList<>();
    static final List<Pillar> PILLARS = new ArrayList<>();
    static final List<Mark> MARKS = new ArrayList<>();

    private ThunderParts() {}

    static boolean isEmpty() {
        return ARCS.isEmpty() && WALLS.isEmpty() && BLASTS.isEmpty() && PILLARS.isEmpty() && MARKS.isEmpty();
    }

    static void clear() {
        ARCS.clear();
        WALLS.clear();
        BLASTS.clear();
        PILLARS.clear();
        MARKS.clear();
    }

    static void tick() {
        ARCS.removeIf(Arc::tick);
        WALLS.removeIf(ShockWall::tick);
        BLASTS.removeIf(Blast::tick);
        PILLARS.removeIf(Pillar::tick);
        MARKS.removeIf(Mark::tick);
    }

    /** 바닥 무늬 → 번개 줄기 → 흐르는 셰이더(돔·벽·기둥) 순서 */
    static void render(float pt, float time) {
        RenderSystem.disableCull();
        if (!MARKS.isEmpty()) {
            renderMarks(RUNE, pt);
            renderMarks(CRACKS, pt);
        }
        if (!ARCS.isEmpty()) {
            FxRender.spriteShader(FxRender.BEAM);
            BufferBuilder bb = FxRender.quads();
            for (Arc a : ARCS) a.emit(bb, pt);
            FxRender.draw(bb);
        }
        for (Blast b : BLASTS) b.render(pt, time);
        for (ShockWall w : WALLS) w.render(pt, time);
        for (Pillar p : PILLARS) p.render(pt, time);
        RenderSystem.disableCull();
    }

    private static void renderMarks(ResourceLocation tex, float pt) {
        boolean any = false;
        for (Mark m : MARKS) if (m.tex == tex) { any = true; break; }
        if (!any) return;
        FxRender.spriteShader(tex);
        BufferBuilder bb = FxRender.quads();
        for (Mark m : MARKS) if (m.tex == tex) m.emit(bb, pt);
        FxRender.draw(bb);
    }

    static void frameGlows(float pt) {
        for (Blast b : BLASTS) b.frameGlows(pt);
        for (Pillar p : PILLARS) p.frameGlows(pt);
    }

    // =====================================================================
    // 추가 도우미 (상한 유지)
    // =====================================================================

    static void addArc(Arc a) {
        if (ARCS.size() >= MAX_ARCS) ARCS.remove(0);
        ARCS.add(a);
    }

    static void addWall(ShockWall w) {
        if (WALLS.size() >= MAX_WALLS) WALLS.remove(0);
        WALLS.add(w);
    }

    static void addBlast(Blast b) {
        if (BLASTS.size() >= MAX_BLASTS) BLASTS.remove(0);
        BLASTS.add(b);
    }

    static void addPillar(Pillar p) {
        if (PILLARS.size() >= MAX_PILLARS) PILLARS.remove(0);
        PILLARS.add(p);
    }

    static void addMark(Mark m) {
        if (MARKS.size() >= MAX_MARKS) MARKS.remove(0);
        MARKS.add(m);
    }

    /** 간단한 금빛 번개: a → b */
    static void arc(Vec3 a, Vec3 b, float w, int life, int delay, int depth, int branches, float jag, boolean flat, float violet) {
        addArc(new Arc(a, b, w, life, delay, depth, branches, jag, flat, violet));
    }

    static Sprite sp(Sprite.Kind kind, Vec3 p, Vec3 v, float s0, float s1, int life) {
        return new Sprite(kind, p, v, s0, s1, life).elem(EL);
    }

    static void frameGlow(Vec3 p, float size, float[] c, float a) {
        if (a <= 0.005f) return;
        Sprite s = new Sprite(Sprite.Kind.GLOW, p, Vec3.ZERO, size, size, 1).color(c[0], c[1], c[2], a).elem(EL);
        FxManager.addFrameGlow(s);
    }

    static double distToPlayer(Vec3 p) {
        Entity pl = Minecraft.getInstance().player;
        return pl == null ? 1.0E9 : pl.position().distanceTo(p);
    }

    /** 몸 표면을 기어 다니는 짧은 금빛 전기 */
    static void bodyArcs(Entity e, int n, float violet) {
        Vec3 base = e.position();
        float h = Math.max(0.5f, e.getBbHeight());
        float w = Math.max(0.25f, e.getBbWidth() * 0.6f);
        for (int i = 0; i < count(n); i++) {
            float ang = RNG.nextFloat() * TAU;
            Vec3 p0 = base.add(Math.cos(ang) * w, rand(0.1f, h), Math.sin(ang) * w);
            Vec3 p1 = p0.add(randomDir().scale(rand(0.4f, 0.9f)));
            arc(p0, p1, 0.24f, 2 + RNG.nextInt(2), 0, 3, RNG.nextInt(2), 0.35f, false, violet);
        }
    }

    /** 바닥 위 각도 a (라디안), 반지름 r 의 점 */
    static Vec3 polar(Vec3 c, double a, double r, double y) {
        return new Vec3(c.x + Math.cos(a) * r, c.y + y, c.z + Math.sin(a) * r);
    }

    static float smooth(float t) {
        t = clamp01(t);
        return t * t * (3f - 2f * t);
    }

    // =====================================================================
    // 도형
    // =====================================================================

    /** 중간점 변위로 꺾인 선 (LightningFx 와 같은 방식) */
    static Vec3[] jagged(Vec3 a, Vec3 b, int depth, float jag, boolean flat) {
        int n = 1 << depth;
        Vec3[] p = new Vec3[n + 1];
        p[0] = a;
        p[n] = b;
        for (int step = n; step > 1; step >>= 1) {
            int half = step >> 1;
            for (int i = 0; i < n; i += step) {
                Vec3 p0 = p[i], p1 = p[i + step];
                double dx = p1.x - p0.x, dy = p1.y - p0.y, dz = p1.z - p0.z;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                Vec3 r = randomDir();
                double rx = r.x, ry = r.y, rz = r.z;
                if (len > 1.0E-6) {
                    double ux = dx / len, uy = dy / len, uz = dz / len;
                    double dot = rx * ux + ry * uy + rz * uz;
                    rx -= ux * dot;
                    ry -= uy * dot;
                    rz -= uz * dot;
                }
                if (flat) ry *= 0.2;
                double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
                double mag = len * jag * (0.35 + 0.65 * RNG.nextDouble());
                double s = rl > 1.0E-6 ? mag / rl : 0.0;
                p[i + half] = new Vec3((p0.x + p1.x) * 0.5 + rx * s, (p0.y + p1.y) * 0.5 + ry * s, (p0.z + p1.z) * 0.5 + rz * s);
            }
        }
        return p;
    }

    /** 꺾인 선을 카메라를 향한 띠로 (색은 알파를 미리 곱한 값). count = 앞에서부터 그릴 점 수 */
    static void ribbon(BufferBuilder bb, Vec3[] pts, int count, float w, float endScale, float r, float g, float b, float a, float vOff) {
        int n = Math.min(pts.length, count) - 1;
        if (n <= 0 || w <= 0.001f) return;
        Vec3 cam = FxRender.cam;
        float v = vOff;
        int total = pts.length - 1;
        for (int i = 0; i < n; i++) {
            Vec3 p0 = pts[i], p1 = pts[i + 1];
            double dx = p1.x - p0.x, dy = p1.y - p0.y, dz = p1.z - p0.z;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0E-5) continue;
            double ux = dx / len, uy = dy / len, uz = dz / len;
            double cx = cam.x - (p0.x + p1.x) * 0.5, cy = cam.y - (p0.y + p1.y) * 0.5, cz = cam.z - (p0.z + p1.z) * 0.5;
            double sx = uy * cz - uz * cy, sy = uz * cx - ux * cz, sz = ux * cy - uy * cx;
            double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1.0E-6) continue;
            sx /= sl;
            sy /= sl;
            sz /= sl;
            float f0 = 1f - (1f - endScale) * i / total, f1 = 1f - (1f - endScale) * (i + 1) / total;
            double h0 = w * f0 * 0.5, h1 = w * f1 * 0.5;
            double ext = Math.min(len * 0.3, w * f0 * 0.2);
            double ax = p0.x - ux * ext, ay = p0.y - uy * ext, az = p0.z - uz * ext;
            double bx = p1.x + ux * ext, by = p1.y + uy * ext, bz = p1.z + uz * ext;
            float v1 = v + (float) len * 0.12f;
            FxRender.vertex(bb, ax - sx * h0, ay - sy * h0, az - sz * h0, 0f, v, r, g, b, a);
            FxRender.vertex(bb, ax + sx * h0, ay + sy * h0, az + sz * h0, 1f, v, r, g, b, a);
            FxRender.vertex(bb, bx + sx * h1, by + sy * h1, bz + sz * h1, 1f, v1, r, g, b, a);
            FxRender.vertex(bb, bx - sx * h1, by - sy * h1, bz - sz * h1, 0f, v1, r, g, b, a);
            v = v1;
        }
    }

    /** 바닥에 납작하게 깔린 띠 (갈라진 땅). count = 앞에서부터 그릴 점 수 */
    static void flatRibbon(BufferBuilder bb, Vec3[] pts, int count, float w, float r, float g, float b, float a) {
        int n = Math.min(pts.length, count) - 1;
        int total = pts.length - 1;
        for (int i = 0; i < n; i++) {
            Vec3 p0 = pts[i], p1 = pts[i + 1];
            double dx = p1.x - p0.x, dz = p1.z - p0.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0E-5) continue;
            double sx = -dz / len, sz = dx / len;
            float f0 = 1f - 0.75f * i / total, f1 = 1f - 0.75f * (i + 1) / total;
            double h0 = w * f0 * 0.5, h1 = w * f1 * 0.5;
            FxRender.vertex(bb, p0.x - sx * h0, p0.y, p0.z - sz * h0, 0f, 0f, r, g, b, a);
            FxRender.vertex(bb, p0.x + sx * h0, p0.y, p0.z + sz * h0, 1f, 0f, r, g, b, a);
            FxRender.vertex(bb, p1.x + sx * h1, p1.y, p1.z + sz * h1, 1f, 1f, r, g, b, a);
            FxRender.vertex(bb, p1.x - sx * h1, p1.y, p1.z - sz * h1, 0f, 1f, r, g, b, a);
        }
    }

    /** 바닥에서 솟은 원호 벽 (흐르는 셰이더용: 아래쪽 열기 높고 위로 0) */
    static void wall(BufferBuilder bb, Vec3 c, float r, float h, float a0, float a1, float heat, int seg, float uvOff) {
        float[] ys = {0f, h * 0.3f, h};
        float[] hs = {heat * 0.85f, heat, 0f};
        for (int i = 0; i < seg; i++) {
            double t0 = a0 + (a1 - a0) * i / seg, t1 = a0 + (a1 - a0) * (i + 1) / seg;
            float u0 = (float) i / seg * 4f + uvOff, u1 = (float) (i + 1) / seg * 4f + uvOff;
            double c0 = Math.cos(t0), s0 = Math.sin(t0), c1 = Math.cos(t1), s1 = Math.sin(t1);
            for (int k = 0; k < 2; k++) {
                float ya = ys[k], yb = ys[k + 1];
                // 위쪽은 조금 안으로 기울어 돔처럼
                double ra = r * (1 - 0.06 * ya / Math.max(0.01, h)), rb = r * (1 - 0.06 * yb / Math.max(0.01, h));
                FxRender.vertex(bb, c.x + c0 * ra, c.y + ya, c.z + s0 * ra, u0, ya * 0.3f, 1, 1, 1, hs[k]);
                FxRender.vertex(bb, c.x + c1 * ra, c.y + ya, c.z + s1 * ra, u1, ya * 0.3f, 1, 1, 1, hs[k]);
                FxRender.vertex(bb, c.x + c1 * rb, c.y + yb, c.z + s1 * rb, u1, yb * 0.3f, 1, 1, 1, hs[k + 1]);
                FxRender.vertex(bb, c.x + c0 * rb, c.y + yb, c.z + s0 * rb, u0, yb * 0.3f, 1, 1, 1, hs[k + 1]);
            }
        }
    }

    /** 바닥에 깔린 원호 띠 (가운데 줄이 밝음) */
    static void band(BufferBuilder bb, Vec3 c, float rIn, float rOut, float a0, float a1, float heat, int seg, float uvOff) {
        float rMid = rIn + (rOut - rIn) * 0.7f;
        float[] radii = {rIn, rMid, rOut};
        float[] hs = {0f, heat, 0f};
        for (int i = 0; i < seg; i++) {
            double t0 = a0 + (a1 - a0) * i / seg, t1 = a0 + (a1 - a0) * (i + 1) / seg;
            float u0 = (float) i / seg * 4f + uvOff, u1 = (float) (i + 1) / seg * 4f + uvOff;
            double c0 = Math.cos(t0), s0 = Math.sin(t0), c1 = Math.cos(t1), s1 = Math.sin(t1);
            for (int k = 0; k < 2; k++) {
                float ra = radii[k], rb = radii[k + 1];
                FxRender.vertex(bb, c.x + c0 * ra, c.y, c.z + s0 * ra, u0, k * 0.5f, 1, 1, 1, hs[k]);
                FxRender.vertex(bb, c.x + c1 * ra, c.y, c.z + s1 * ra, u1, k * 0.5f, 1, 1, 1, hs[k]);
                FxRender.vertex(bb, c.x + c1 * rb, c.y, c.z + s1 * rb, u1, (k + 1) * 0.5f, 1, 1, 1, hs[k + 1]);
                FxRender.vertex(bb, c.x + c0 * rb, c.y, c.z + s0 * rb, u0, (k + 1) * 0.5f, 1, 1, 1, hs[k + 1]);
            }
        }
    }

    /** 둘레 길이에 맞춘 조각 수 (16 ~ 96) */
    static int segs(float r, float span) {
        return Math.max(12, Math.min(96, (int) (r * span * 2.2f)));
    }

    // =====================================================================
    // 금빛 번개 줄기
    // =====================================================================

    static final class Arc {
        private static final float GLOW_W = 0.9f, CORE_W = 0.22f;
        final Vec3 a, b;
        final float width, jag;
        final int life, delay, depth, branches;
        final boolean flat;
        final float gr, gg, gb;
        final List<Vec3[]> paths = new ArrayList<>(3);
        final float[] pathW;
        int age, regen;
        float flick = 1f, vOff;

        Arc(Vec3 a, Vec3 b, float width, int life, int delay, int depth, int branches, float jag, boolean flat, float violet) {
            this.a = a;
            this.b = b;
            this.width = width;
            this.life = Math.max(1, life);
            this.delay = Math.max(0, delay);
            this.depth = Math.max(1, Math.min(6, depth));
            this.branches = Math.max(0, Math.min(3, branches));
            this.jag = jag;
            this.flat = flat;
            this.pathW = new float[this.branches + 1];
            float[] c = RNG.nextFloat() < violet ? VIOLET : GOLD;
            float v = rand(-0.06f, 0.06f);
            this.gr = c[0];
            this.gg = Math.max(0f, c[1] + v);
            this.gb = c[2];
            rebuild();
            regen = 1 + RNG.nextInt(2);
        }

        void rebuild() {
            paths.clear();
            Vec3[] main = jagged(a, b, depth, jag, flat);
            paths.add(main);
            pathW[0] = 1f;
            int n = main.length - 1;
            double total = a.distanceTo(b);
            for (int k = 0; k < branches && n >= 2; k++) {
                int idx = 1 + RNG.nextInt(Math.max(1, (int) (n * 0.65f)));
                Vec3 s = main[Math.min(idx, n - 1)];
                Vec3 rem = b.subtract(s);
                double rl = rem.length();
                if (rl < 0.3) continue;
                Vec3 dir = rem.scale(1.0 / rl).add(randomDir().scale(0.9));
                if (flat) dir = new Vec3(dir.x, dir.y * 0.2, dir.z);
                if (dir.lengthSqr() < 1.0E-6) continue;
                dir = dir.normalize();
                double bl = Math.min(rl * rand(0.25f, 0.5f) + 0.3, total * 0.4 + 0.5);
                paths.add(jagged(s, s.add(dir.scale(bl)), Math.max(2, depth - 1), jag * 1.15f, flat));
                pathW[paths.size() - 1] = rand(0.35f, 0.6f) * (1f - 0.4f * idx / n);
            }
            vOff = RNG.nextFloat();
        }

        boolean tick() {
            age++;
            if (age < delay) return false;
            int local = age - delay;
            if (local >= life) return true;
            if (--regen <= 0) {
                rebuild();
                regen = 1 + RNG.nextInt(2);
            }
            flick = local < 2 ? 1f : (RNG.nextFloat() < 0.18f ? 0.3f : rand(0.65f, 1f));
            return false;
        }

        void emit(BufferBuilder bb, float pt) {
            if (age < delay) return;
            float t = (age - delay + pt) / life;
            if (t >= 1f) return;
            float fade = t < 0.2f ? 1f : clamp01((1f - t) / 0.8f);
            float al = fade * fade * flick;
            if (al < 0.01f) return;
            for (int p = 0; p < paths.size(); p++) {
                Vec3[] pts = paths.get(p);
                float w = width * pathW[p];
                float end = p == 0 ? 0.6f : 0.1f;
                float ga = al * 0.5f;
                ribbon(bb, pts, pts.length, w * GLOW_W, end, gr * ga, gg * ga, gb * ga, ga, vOff);
                float ca = al * (p == 0 ? 1f : 0.85f);
                ribbon(bb, pts, pts.length, w * CORE_W, end, ca, 0.95f * ca, 0.8f * ca, ca, vOff + 0.5f);
            }
        }
    }

    // =====================================================================
    // 바닥을 따라 퍼지는 충격파 벽 (원 전체 또는 부채꼴)
    // =====================================================================

    static final class ShockWall {
        final Vec3 c;
        final float r0, r1, h, a0, a1;
        final int life, delay;
        int age;

        ShockWall(Vec3 c, float r0, float r1, float h, int life, int delay, float a0, float a1) {
            this.c = c.add(0, 0.05, 0);
            this.r0 = r0;
            this.r1 = r1;
            this.h = h;
            this.life = Math.max(2, life);
            this.delay = delay;
            this.a0 = a0;
            this.a1 = a1;
        }

        static ShockWall ring(Vec3 c, float r0, float r1, float h, int life, int delay) {
            return new ShockWall(c, r0, r1, h, life, delay, 0f, TAU);
        }

        boolean tick() {
            return ++age > delay + life;
        }

        void render(float pt, float time) {
            float local = age + pt - delay;
            if (local < 0) return;
            float t = local / life;
            if (t >= 1f) return;
            float e = easeOutCubic(t);
            float r = r0 + (r1 - r0) * e;
            float fade = (1f - t);
            float heat = fade * (0.6f + 0.4f * fade);
            FxRender.elemShader(EL, 2.8f, 0.12f + 0.75f * t, 1.25f * fade + 0.15f);
            BufferBuilder bb = FxRender.quads();
            int seg = segs(r, a1 - a0);
            wall(bb, c, r, h * (1f - 0.55f * t), a0, a1, heat, seg, time * 0.02f);
            band(bb, c, r * 0.72f, r * 1.02f, a0, a1, heat * 0.85f, seg, time * 0.03f + 0.5f);
            FxRender.draw(bb);
        }
    }

    // =====================================================================
    // 천둥 폭발 돔 (흰빛 심 + 금빛 겉 + 보랏빛 윤곽, 노이즈로 타서 사라짐)
    // =====================================================================

    static final class Blast {
        final Vec3 c;
        final float R, power;
        final int life, expand;
        int age;

        Blast(Vec3 c, float R, float power, int life, int expand) {
            this.c = c;
            this.R = R;
            this.power = power;
            this.life = Math.max(6, life);
            this.expand = Math.max(1, expand);
        }

        float radius(float t) {
            return R * (0.12f + 0.88f * easeOutCubic(t / expand));
        }

        boolean tick() {
            age++;
            if (age > life) return true;
            if (age <= expand + 3) {
                FxManager.lod = FxManager.lodAt(c);
                float r = radius(age);
                for (int i = 0; i < count(power > 1.5f ? 6 : 3); i++) {
                    Vec3 d = randomDir();
                    if (d.y < -0.1) d = new Vec3(d.x, -d.y, d.z);
                    Vec3 s0 = c.add(d.scale(r));
                    Vec3 s1 = c.add(d.add(randomDir().scale(0.4)).normalize().scale(r * rand(1.0f, 1.25f)));
                    arc(s0, s1, 0.45f * power, 2, 0, 3, 1, 0.3f, false, 0.35f);
                }
            }
            return false;
        }

        void render(float pt, float time) {
            float t = age + pt;
            if (t > life) return;
            float r = radius(t);
            float erosion = 0.04f + 0.96f * clamp01((t - expand) / (life - expand));
            float fade = 1f - clamp01((t - life * 0.55f) / (life * 0.45f));
            FxRender.elemShader(EL, 3.2f, erosion, 1.35f * fade + 0.1f);
            RenderSystem.disableCull();
            FxManager.BlastSphere.draw(c, r, time);
            if (t < expand + 4) {
                float k = 1f - clamp01(t / (expand + 4));
                FxRender.elemShader(EL, 4.5f, 0.05f, 1.9f * k);
                FxManager.BlastSphere.draw(c, r * 0.5f, time * 1.7f);
            }
        }

        void frameGlows(float pt) {
            float t = (age + pt) / Math.max(1f, life * 0.6f);
            if (t >= 1f) return;
            float a = (1f - t) * (1f - t);
            frameGlow(c, R * 3.2f, GOLD, 0.55f * a);
            if (age < 5) frameGlow(c, R * 1.6f + 3f, PALE, 1f - (age + pt) / 5f);
        }
    }

    // =====================================================================
    // 빛기둥 (꼬이는 원통 2겹)
    // =====================================================================

    static final class Pillar {
        final Vec3 base;
        final float r, h;
        final int life;
        int age;

        Pillar(Vec3 base, float r, float h, int life) {
            this.base = base;
            this.r = r;
            this.h = h;
            this.life = Math.max(4, life);
        }

        boolean tick() {
            return ++age > life;
        }

        void render(float pt, float time) {
            float t = (age + pt) / life;
            if (t >= 1f) return;
            float grow = easeOutCubic(Math.min(1f, (age + pt) / 3f));
            float heat = (float) Math.pow(1f - t, 1.4);
            FxRender.elemShader(EL, 3.4f, 0.08f + 0.7f * t, 1.3f);
            SkillFx.column(base, r * (1f + 0.5f * t), r * 0.7f * (1f + 0.5f * t), h * grow, heat, 0.18f, time * 0.01f, 0.07f, time);
            FxRender.elemShader(EL, 4.5f, 0.05f + 0.5f * t, 1.6f);
            SkillFx.column(base, r * 0.38f, r * 0.26f, h * grow, heat * 1.1f, -0.35f, 0.5f, 0.03f, time * 1.4f);
        }

        void frameGlows(float pt) {
            float t = (age + pt) / life;
            if (t >= 1f) return;
            float a = (1f - t);
            for (int i = 0; i < 4; i++) frameGlow(base.add(0, h * (0.1f + 0.25f * i), 0), r * 5f, GOLD, 0.35f * a);
        }
    }

    // =====================================================================
    // 바닥 무늬 (룬 원, 빛나는 금 간 땅) — 그림을 평면에 붙여 더하기 섞기
    // =====================================================================

    static final class Mark {
        final Vec3 p, n;
        final ResourceLocation tex;
        final float R, spin, rot, grow, alpha, r, g, b;
        final int life, delay;
        int age;
        float flick = 1f;

        Mark(ResourceLocation tex, Vec3 p, Vec3 n, float R, int life, int delay, float grow, float spin, float alpha, float[] col) {
            this.tex = tex;
            this.p = p.add(n.scale(0.06));
            this.n = n;
            this.R = R;
            this.life = Math.max(2, life);
            this.delay = delay;
            this.grow = grow;
            this.spin = spin;
            this.alpha = alpha;
            this.rot = RNG.nextFloat() * TAU;
            this.r = col[0];
            this.g = col[1];
            this.b = col[2];
        }

        boolean tick() {
            age++;
            // 처음 1초는 틈의 빛이 지직거림
            flick = age - delay < 20 && RNG.nextFloat() < 0.25f ? rand(0.55f, 0.85f) : 1f;
            return age > delay + life;
        }

        void emit(BufferBuilder bb, float pt) {
            float local = age + pt - delay;
            if (local < 0) return;
            float t = local / life;
            if (t >= 1f) return;
            float in = clamp01(local / 2f);
            float out = (float) Math.pow(1f - t, 1.6);
            float a = alpha * in * out * flick;
            if (a < 0.005f) return;
            float rad = grow > 0 ? R * (0.35f + 0.65f * easeOutCubic(local / grow)) : R;
            FxRender.planeQuad(bb, p, n, rad, rot + spin * local, r * a, g * a, b * a, a);
        }
    }
}
