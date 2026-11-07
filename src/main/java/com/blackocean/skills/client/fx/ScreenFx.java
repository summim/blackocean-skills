package com.blackocean.skills.client.fx;

import com.blackocean.skills.Element;
import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillsMod;
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
 *   <li>darken: 근처에 있으면 하늘·안개가 어두워짐 (뇌운 등)</li>
 *   <li>ultimate: 궁극기 제목 배너 + 색 테두리 + 번쩍</li>
 * </ul>
 */
public final class ScreenFx {
    private static int flashColor;
    private static float flashStrength;
    private static int flashTicks, flashAge;

    private static Vec3 darkCenter = Vec3.ZERO;
    private static float darkRadius, darkStrength;
    private static int darkTicks, darkAge;

    @Nullable
    private static Component bannerTitle;
    @Nullable
    private static Component bannerSub;
    private static int bannerColor, bannerAge, bannerTicks;

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
        double d = mc.player.position().distanceTo(pos);
        Component who = caster != null ? caster.getDisplayName() : Component.literal("?");
        bannerTitle = Component.translatable(skill.translationKey());
        bannerSub = Component.translatable("hud.blackocean_skills.ult_by", who);
        bannerColor = skill.element.color;
        bannerAge = 0;
        bannerTicks = 50;
        float near = (float) Math.max(0.0, 1.0 - d / 64.0);
        flash(skill.element.color, 0.35f + 0.35f * near, 14);
        CameraShake.add(0.6f + 1.2f * near);
    }

    public static void tick() {
        if (flashAge < flashTicks) flashAge++;
        if (darkAge < darkTicks) darkAge++;
        if (bannerTitle != null && ++bannerAge > bannerTicks) bannerTitle = null;
    }

    public static void clear() {
        flashTicks = flashAge = 0;
        flashStrength = 0;
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
        if (mc.options.hideGui && bannerTitle == null && flashAge >= flashTicks) return;
        int w = g.guiWidth(), h = g.guiHeight();
        float pt = mc.isPaused() ? 0f : delta.getGameTimeDeltaPartialTick(false);

        // 어두움: 화면 가장자리부터 살짝 (안개색과 함께)
        float dark = darkness();
        if (dark > 0) vignette(g, w, h, 0x05070F, dark * 0.55f, 0.35f);

        // 번쩍: 가운데는 옅게, 가장자리는 진하게
        if (flashAge < flashTicks) {
            float t = Math.min(1f, (flashAge + pt) / flashTicks);
            float s = flashStrength * (1f - t) * (1f - t);
            int a = (int) (s * 0.55f * 255);
            if (a > 0) g.fill(0, 0, w, h, (a << 24) | (flashColor & 0xFFFFFF));
            vignette(g, w, h, flashColor, s, 0.25f);
        }

        // 궁극기 배너
        if (bannerTitle != null && !mc.options.hideGui) {
            float t = (bannerAge + pt) / bannerTicks;
            float in = Math.min(1f, t / 0.12f), out = Math.min(1f, (1f - t) / 0.25f);
            float a = Math.max(0f, Math.min(in, out));
            if (a > 0.02f) {
                Font font = mc.font;
                int alpha = (int) (a * 255) << 24;
                int cy = h / 4;
                // 띠
                int bandA = (int) (a * 150);
                g.fillGradient(0, cy - 18, w / 2, cy + 26, 0, (bandA << 24));
                g.fillGradient(w / 2, cy - 18, w, cy + 26, (bandA << 24), 0);
                g.fill(w / 2 - (int) (w * 0.3f * in), cy - 19, w / 2 + (int) (w * 0.3f * in), cy - 18, alpha | (bannerColor & 0xFFFFFF));
                g.fill(w / 2 - (int) (w * 0.3f * in), cy + 26, w / 2 + (int) (w * 0.3f * in), cy + 27, alpha | (bannerColor & 0xFFFFFF));
                float scale = 2.6f - 0.6f * in; // 크게 나타났다가 자리 잡음
                g.pose().pushPose();
                g.pose().translate(w / 2f, cy, 0);
                g.pose().scale(scale, scale, 1f);
                int tw = font.width(bannerTitle);
                g.drawString(font, bannerTitle, -tw / 2, -4, alpha | (bannerColor & 0xFFFFFF), true);
                g.pose().popPose();
                if (bannerSub != null) {
                    int sw = font.width(bannerSub);
                    g.drawString(font, bannerSub, w / 2 - sw / 2, cy + 14, alpha | 0xE8E0D0, true);
                }
            }
        }
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

    @SuppressWarnings("unused")
    private static Element unusedElement() {
        return Element.FIRE;
    }
}
