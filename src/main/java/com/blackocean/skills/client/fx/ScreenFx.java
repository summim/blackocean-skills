package com.blackocean.skills.client.fx;

import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillsMod;
import com.blackocean.skills.client.SkillsClientConfig;
import com.mojang.math.Axis;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.jetbrains.annotations.Nullable;

/**
 * 화면 전체 연출 (궁극기·큰 폭발용). 모두 클라이언트 혼자 계산한다.
 * <ul>
 *   <li>flash: 화면 전체가 그 색으로 번쩍 (가장자리 쪽이 더 진함)</li>
 *   <li>whiteFlash: 아주 짧은 흰빛 번쩍 (폭발 핵) — 색 번쩍과 따로 겹쳐짐</li>
 *   <li>darken: 근처에 있으면 하늘·안개가 어두워짐 (뇌운 등)</li>
 *   <li>ultimate: 궁극기 연출 — 위아래 검은 띠 + 능력 색으로 쓸고 지나가는 제목 배너 + 속도선 + 색 갈라짐 번쩍
 *       (멀수록 약하게, 전부 1.5초 안에 끝남. 검은 띠는 HUD 아래에 그려져 핫바를 가리지 않음)</li>
 * </ul>
 */
public final class ScreenFx {
    /** 궁극기 연출 길이 (틱). 1.5초 */
    private static final int CINE_TICKS = 30;
    private static final int SPEED_LINES = 44;

    private static int flashColor;
    private static float flashStrength;
    private static int flashTicks, flashAge;

    private static float whiteStrength;
    private static int whiteTicks, whiteAge;

    private static Vec3 darkCenter = Vec3.ZERO;
    private static float darkRadius, darkStrength;
    private static int darkTicks, darkAge;

    @Nullable
    private static Component bannerTitle;
    @Nullable
    private static Component bannerSub;
    private static int bannerColor, bannerAge, bannerTicks;
    /** 궁극기 연출 세기 (1 = 시전자·바로 옆, 0 = 아주 멀리) */
    private static float cineNear;
    private static int cineSeed;

    /** 속도선 (각도·길이·두께 비율), 연출마다 한 번 섞음 */
    private static final float[] LINE_ANG = new float[SPEED_LINES], LINE_LEN = new float[SPEED_LINES], LINE_W = new float[SPEED_LINES],
            LINE_PH = new float[SPEED_LINES];

    private ScreenFx() {}

    /** 화면 번쩍. rgb = 0xRRGGBB, strength 0~1 */
    public static void flash(int rgb, float strength, int ticks) {
        if (strength >= flashStrength * Math.max(0f, 1f - (float) flashAge / Math.max(1, flashTicks))) {
            flashColor = rgb;
            flashStrength = Math.min(1f, strength);
            flashTicks = Math.max(1, ticks);
            flashAge = 0;
        }
    }

    /** 아주 짧은 흰빛 번쩍 (폭발 핵·거대 번개). 색 번쩍과 따로 겹쳐짐 */
    public static void whiteFlash(float strength, int ticks) {
        float cur = whiteAge < whiteTicks ? whiteStrength * (1f - (float) whiteAge / whiteTicks) : 0f;
        if (strength >= cur) {
            whiteStrength = Math.min(1f, strength);
            whiteTicks = Math.max(1, ticks);
            whiteAge = 0;
        }
    }

    /**
     * 큰 폭발 한 번에: 거리에 따라 흰빛 + 색 번쩍.
     * @param radius 이 거리 밖이면 번쩍이지 않음
     */
    public static void impact(Vec3 pos, int rgb, float strength, float radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || radius <= 0f) return;
        double d = mc.player.position().distanceTo(pos);
        if (d >= radius) return;
        float f = (float) (1.0 - d / radius);
        whiteFlash(strength * f * f * 0.85f, 3);
        flash(rgb, strength * (0.35f + 0.65f * f), 8 + (int) (14 * strength));
    }

    /** center 에서 radius 안쪽에 있으면 하늘·안개가 strength 만큼 어두워짐 (ticks 동안, 앞뒤로 부드럽게) */
    public static void darken(Vec3 center, float radius, float strength, int ticks) {
        darkCenter = center;
        darkRadius = radius;
        darkStrength = strength;
        darkTicks = Math.max(1, ticks);
        darkAge = 0;
    }

    /** 궁극기 시전 (서버 ULT_CAST 신호) */
    static void ultimate(Skill skill, @Nullable Entity caster, Vec3 pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        boolean self = caster == mc.player;
        double d = self ? 0.0 : mc.player.position().distanceTo(pos);
        float near = self ? 1f : (float) Math.max(0.0, 1.0 - d / 72.0);
        near = near * near * (3f - 2f * near);
        // 이미 더 가까운 궁극기 연출이 한창이면 그대로 둠 (먼 궁극기가 덮어쓰지 않게)
        if (bannerTitle != null && bannerAge < CINE_TICKS * 0.6f && cineNear > near + 0.15f) return;

        Component who = caster != null ? caster.getDisplayName() : Component.literal("?");
        bannerTitle = Component.translatable(skill.translationKey());
        bannerSub = Component.translatable("hud.blackocean_skills.ult_by", who);
        bannerColor = skill.element.color;
        bannerAge = 0;
        bannerTicks = CINE_TICKS;
        cineNear = near;
        cineSeed = FxManager.RNG.nextInt();
        java.util.Random r = new java.util.Random(cineSeed);
        for (int i = 0; i < SPEED_LINES; i++) {
            LINE_ANG[i] = (float) (Math.PI * 2 * (i + r.nextFloat() * 0.8f) / SPEED_LINES);
            LINE_LEN[i] = 0.35f + 0.5f * r.nextFloat();
            LINE_W[i] = 0.5f + r.nextFloat();
            LINE_PH[i] = r.nextFloat() * 0.25f;
        }

        whiteFlash(0.2f + 0.45f * near, 4);
        flash(skill.element.color, 0.3f + 0.4f * near, 16);
        CameraShake.add(0.6f + 1.8f * near);
        if (near > 0.2f) CameraShake.kick(-1.2f * near, 0f);
    }

    public static void tick() {
        if (flashAge < flashTicks) flashAge++;
        if (whiteAge < whiteTicks) whiteAge++;
        if (darkAge < darkTicks) darkAge++;
        if (bannerTitle != null && ++bannerAge > bannerTicks) bannerTitle = null;
    }

    public static void clear() {
        flashTicks = flashAge = 0;
        flashStrength = 0;
        whiteTicks = whiteAge = 0;
        whiteStrength = 0;
        darkTicks = darkAge = 0;
        bannerTitle = null;
    }

    public static void register(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.CROSSHAIR, SkillsMod.id("screen_fx"), ScreenFx::render);
    }

    private static float darkness() {
        if (darkAge >= darkTicks) return 0f;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0f;
        double d = mc.player.position().distanceTo(darkCenter);
        float space = (float) Math.max(0.0, Math.min(1.0, (darkRadius + 16 - d) / 16.0));
        float t = (float) darkAge / darkTicks;
        float time = Math.min(1f, t / 0.15f) * Math.min(1f, (1f - t) / 0.2f);
        return darkStrength * space * time;
    }

    /** 하늘·안개 어둡게 */
    public static void onFogColor(ViewportEvent.ComputeFogColor e) {
        float k = darkness();
        if (k <= 0) return;
        float m = 1f - 0.75f * k;
        e.setRed(e.getRed() * m);
        e.setGreen(e.getGreen() * m);
        e.setBlue(e.getBlue() * (m + 0.08f * k));
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        boolean flashing = flashAge < flashTicks || whiteAge < whiteTicks;
        if (bannerTitle == null && !flashing && darkAge >= darkTicks) return;
        int w = g.guiWidth(), h = g.guiHeight();
        float pt = mc.isPaused() ? 0f : delta.getGameTimeDeltaPartialTick(false);

        // 어두움: 화면 가장자리부터 살짝 (안개색과 함께)
        float dark = darkness();
        if (dark > 0) vignette(g, w, h, 0x05070F, dark * 0.55f, 0.35f);

        // 궁극기 영화 연출 (배너 아래 깔리는 것들)
        boolean cine = bannerTitle != null && !mc.options.hideGui;
        float ct = cine ? Math.min(1f, (bannerAge + pt) / bannerTicks) : 1f;
        boolean full = cine && cineNear > 0.18f && SkillsClientConfig.ULT_CINEMATIC.get();
        if (full) {
            speedLines(g, w, h, ct, cineNear);
            letterbox(g, w, h, ct, cineNear);
        }

        // 번쩍: 가운데는 옅게, 가장자리는 진하게
        if (flashAge < flashTicks) {
            float t = Math.min(1f, (flashAge + pt) / flashTicks);
            float s = flashStrength * (1f - t) * (1f - t);
            int a = (int) (s * 0.55f * 255);
            if (a > 0) g.fill(0, 0, w, h, (a << 24) | (flashColor & 0xFFFFFF));
            vignette(g, w, h, flashColor, s, 0.25f);
        }
        if (whiteAge < whiteTicks) {
            float t = Math.min(1f, (whiteAge + pt) / whiteTicks);
            float s = whiteStrength * (1f - t);
            int a = (int) (s * s * 0.85f * 255);
            if (a > 0) g.fill(0, 0, w, h, (a << 24) | 0xFFFDF6);
        }
        if (full) chromatic(g, w, h, ct, cineNear);

        if (cine) banner(g, mc.font, w, h, ct, full ? cineNear : 0f);
    }

    // =====================================================================
    // 궁극기 영화 연출 조각
    // =====================================================================

    /** 들어옴 0→1 (앞 a), 나감 1→0 (끝 b) 곱 */
    private static float env(float t, float in, float out) {
        float i = t < in ? t / in : 1f;
        float o = t > 1f - out ? (1f - t) / out : 1f;
        i = i * i * (3f - 2f * i);
        o = o * o * (3f - 2f * o);
        return Math.max(0f, Math.min(i, o));
    }

    /** 위아래 검은 띠 (HUD 아래 층이라 핫바·체력은 그대로 보임) */
    private static void letterbox(GuiGraphics g, int w, int h, float t, float near) {
        float e = env(t, 0.14f, 0.3f);
        int bh = (int) (h * 0.095f * e * (0.5f + 0.5f * near));
        if (bh <= 0) return;
        int a = (int) (235 * Math.min(1f, 0.6f + 0.4f * near)) << 24;
        g.fill(0, 0, w, bh, a);
        g.fill(0, h - bh, w, h, a);
        // 띠 안쪽 가장자리에 능력 색 가는 선
        int line = ((int) (200 * e) << 24) | (bannerColor & 0xFFFFFF);
        g.fill(0, bh, w, bh + 1, line);
        g.fill(0, h - bh - 1, w, h - bh, line);
    }

    /** 가운데로 빨려 드는 속도선 */
    private static void speedLines(GuiGraphics g, int w, int h, float t, float near) {
        float e = env(t, 0.08f, 0.45f);
        if (e <= 0.01f) return;
        float cx = w * 0.5f, cy = h * 0.5f;
        float outer = (float) Math.sqrt(cx * cx + cy * cy) * 1.05f;
        int rgb = mixWhite(bannerColor, 0.45f);
        int count = near > 0.6f ? SPEED_LINES : SPEED_LINES / 2;
        for (int i = 0; i < count; i++) {
            float lt = (t * 2.2f + LINE_PH[i]) % 1f; // 선마다 밖 → 안으로 흘러 들어옴
            float len = outer * LINE_LEN[i] * 0.55f;
            float r1 = outer * (1.05f - 0.55f * lt);
            float r0 = Math.max(outer * 0.32f, r1 - len);
            if (r1 <= r0) continue;
            int a = (int) (150 * e * near * (1f - lt * 0.6f));
            if (a <= 3) continue;
            float thick = Math.max(0.6f, LINE_W[i] * (1.2f + 1.6f * near));
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0f);
            g.pose().mulPose(Axis.ZP.rotation(LINE_ANG[i]));
            // 바깥쪽이 진하고 안쪽으로 갈수록 옅어지도록 두 토막
            float mid = (r0 + r1) * 0.5f;
            fillF(g, mid, -thick * 0.5f, r1, thick * 0.5f, (a << 24) | rgb);
            fillF(g, r0, -thick * 0.3f, mid, thick * 0.3f, ((a / 2) << 24) | rgb);
            g.pose().popPose();
        }
    }

    /** 시작 순간 색 갈라짐 (왼쪽 붉게·오른쪽 푸르게 어긋난 테두리) */
    private static void chromatic(GuiGraphics g, int w, int h, float t, float near) {
        float k = 1f - Math.min(1f, t / 0.22f);
        if (k <= 0.01f) return;
        k = k * k * near;
        int off = (int) (w * 0.035f * k) + 1;
        int a = (int) (110 * k);
        if (a <= 2) return;
        int bw = (int) (w * 0.12f);
        // 왼쪽 붉은 테두리 (밖에서 안으로 옅어짐)
        for (int i = 0; i < 4; i++) {
            int ai = a * (4 - i) / 4;
            int x0 = off + bw * i / 4, x1 = off + bw * (i + 1) / 4;
            g.fill(x0, 0, x1, h, (ai << 24) | 0xFF2850);
            g.fill(w - x1, 0, w - x0, h, (ai << 24) | 0x28D8FF);
        }
    }

    /** 능력 색으로 화면을 가로질러 쓸고 지나가는 띠 + 제목 */
    private static void banner(GuiGraphics g, Font font, int w, int h, float t, float near) {
        Component title = bannerTitle;
        if (title == null) return;
        float a = env(t, 0.1f, 0.22f);
        if (a <= 0.02f) return;
        float in = Math.min(1f, t / 0.18f);
        float ein = 1f - (1f - in) * (1f - in) * (1f - in);
        boolean small = near <= 0f;
        int cy = small ? (int) (h * 0.18f) : (int) (h * 0.3f);
        int col = bannerColor & 0xFFFFFF;

        g.pose().pushPose();
        g.pose().translate(w * 0.5f, cy, 0f);
        if (!small) g.pose().mulPose(Axis.ZP.rotationDegrees(-3.5f));

        // 띠: 왼쪽에서 오른쪽으로 펼쳐짐 (가운데 진하고 양 끝 옅음)
        float half = w * 0.62f;
        float bandH = small ? 12f : 22f + 6f * near;
        float right = -half + 2f * half * ein;
        int seg = 14;
        for (int i = 0; i < seg; i++) {
            float x0 = -half + 2f * half * i / seg, x1 = -half + 2f * half * (i + 1) / seg;
            if (x0 >= right) break;
            x1 = Math.min(x1, right);
            float c = 1f - Math.abs((x0 + x1) * 0.5f / half);
            int ba = (int) (a * (small ? 110 : 165) * c);
            fillF(g, x0, -bandH, x1, bandH, (ba << 24) | darken(col, 0.28f));
            int ea = (int) (a * 230 * c);
            fillF(g, x0, -bandH - 1.5f, x1, -bandH, (ea << 24) | col);
            fillF(g, x0, bandH, x1, bandH + 1.5f, (ea << 24) | col);
        }
        // 앞머리 빛줄기 (띠가 펼쳐지는 끝에서 하얗게)
        if (in < 1f && !small) {
            float sx = right;
            int sa = (int) (255 * a * (1f - in));
            fillF(g, sx - 26f, -bandH - 4f, sx, bandH + 4f, (sa / 3 << 24) | mixWhite(col, 0.6f));
            fillF(g, sx - 5f, -bandH - 6f, sx + 1f, bandH + 6f, (sa << 24) | 0xFFFFFF);
        }
        // 지나간 자리 위아래로 흐르는 가는 줄
        if (!small) {
            float sweep = ((t * 1.6f) % 1f) * 2f * half - half;
            int la = (int) (140 * a);
            fillF(g, sweep - w * 0.25f, -bandH - 7f, sweep, -bandH - 6f, (la << 24) | col);
            fillF(g, -sweep, bandH + 6f, -sweep + w * 0.25f, bandH + 7f, (la << 24) | col);
        }

        // 제목: 오른쪽에서 미끄러져 들어오며 크게 → 자리 잡음
        int alpha = (int) (a * 255) << 24;
        float scale = small ? 1.5f : (2.2f + 0.9f * near) * (1.25f - 0.25f * ein);
        float slide = small ? 0f : (1f - ein) * w * 0.18f;
        int tw = font.width(title);
        g.pose().pushPose();
        g.pose().translate(slide, 0f, 0f);
        g.pose().scale(scale, scale, 1f);
        // 번진 그림자 (능력 색)
        if (!small) g.drawString(font, title, -tw / 2 + 1, -3, ((int) (a * 120) << 24) | col, false);
        g.drawString(font, title, -tw / 2, -4, alpha | mixWhite(col, 0.25f + 0.5f * (1f - ein)), true);
        g.pose().popPose();
        if (bannerSub != null && !small) {
            int sw = font.width(bannerSub);
            g.drawString(font, bannerSub, (int) (-sw / 2 - slide * 0.5f), (int) (bandH * 0.55f) + 2, alpha | 0xE8E0D0, true);
        }
        g.pose().popPose();
    }

    private static void fillF(GuiGraphics g, float x0, float y0, float x1, float y1, int argb) {
        int ix0 = Math.round(x0), iy0 = Math.round(y0), ix1 = Math.round(x1), iy1 = Math.round(y1);
        if (ix1 <= ix0) ix1 = ix0 + 1;
        if (iy1 <= iy0) iy1 = iy0 + 1;
        g.fill(ix0, iy0, ix1, iy1, argb);
    }

    private static int mixWhite(int rgb, float k) {
        int r = (rgb >> 16) & 255, gg = (rgb >> 8) & 255, b = rgb & 255;
        r = (int) (r + (255 - r) * k);
        gg = (int) (gg + (255 - gg) * k);
        b = (int) (b + (255 - b) * k);
        return (r << 16) | (gg << 8) | b;
    }

    private static int darken(int rgb, float k) {
        int r = (rgb >> 16) & 255, gg = (rgb >> 8) & 255, b = rgb & 255;
        return ((int) (r * k) << 16) | ((int) (gg * k) << 8) | (int) (b * k);
    }

    /** 가장자리가 진한 테두리 번짐 */
    private static void vignette(GuiGraphics g, int w, int h, int rgb, float strength, float size) {
        int a = (int) (Math.min(1f, strength) * 200);
        if (a <= 2) return;
        int c = (a << 24) | (rgb & 0xFFFFFF);
        int bw = (int) (w * size), bh = (int) (h * size);
        g.fillGradient(0, 0, w, bh, c, 0);
        g.fillGradient(0, h - bh, w, h, 0, c);
        // 좌우 (세로 그라데이션만 지원 → 얇은 띠를 겹쳐서)
        int steps = 8;
        for (int i = 0; i < steps; i++) {
            int ai = (int) (a * (1f - (float) i / steps) / 2.2f);
            int ci = (ai << 24) | (rgb & 0xFFFFFF);
            int x0 = bw * i / steps, x1 = bw * (i + 1) / steps;
            g.fill(x0, 0, x1, h, ci);
            g.fill(w - x1, 0, w - x0, h, ci);
        }
    }
}
