package com.blackocean.skills.client.fx;

import com.blackocean.skills.Element;
import com.blackocean.skills.network.SkillEventPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 온라인 RPG 식 피해 숫자 (시전자 화면에만). 능력 색, 툭 튀어나왔다가 위로 떠오르며 사라짐.
 * 큰 피해·마무리 일격은 크고 금빛 테두리. 같은 대상에 짧게 연속으로 들어간 피해는 숫자를 겹치지 않게 비켜 놓는다.
 * <ul>
 *   <li>처음 2틱은 하얗게 번쩍이며 크게 튀어나옴 → 능력 색으로 돌아옴 (밝은 폭발 위에서도 읽히게 테두리 8방향)</li>
 *   <li>마무리 일격: 금빛 + 더 크게 + 처음에 살짝 떨림</li>
 *   <li>맞은 생물에 짧은 흰빛 (시전자 화면의 타격 확인)</li>
 * </ul>
 */
public final class DamageNumbers {
    private static final int LIFE = 26, MAX = 64;

    private static final class Num {
        final Vec3 pos;
        final String text;
        final int color;
        final boolean big, kill;
        final float dx, dz;
        final long born;

        Num(Vec3 pos, String text, int color, boolean big, boolean kill, float dx, float dz, long born) {
            this.pos = pos;
            this.text = text;
            this.color = color;
            this.big = big;
            this.kill = kill;
            this.dx = dx;
            this.dz = dz;
            this.born = born;
        }
    }

    private static final List<Num> NUMS = new ArrayList<>();
    private static long lastSound;
    /** 그리기 재사용 (매 프레임 새로 만들지 않음) */
    private static final PoseStack PS = new PoseStack();
    private static final int[][] OUTLINE = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};

    private DamageNumbers() {}

    static void add(SkillEventPayload e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !Float.isFinite(e.size()) || !com.blackocean.skills.client.SkillsClientConfig.DAMAGE_NUMBERS.get()) return;
        float dmg = e.size();
        Element el = Element.byIndex(e.duration());
        int color = el != null ? el.color : 0xFFFFFF;
        boolean kill = e.size2() > 0.5f, big = dmg >= 10f || kill;
        String text = dmg >= 10f ? String.valueOf(Math.round(dmg)) : String.format(Locale.ROOT, "%.1f", dmg);
        long now = mc.level.getGameTime();
        int stacked = 0;
        for (Num n : NUMS) if (now - n.born < 8 && n.pos.distanceToSqr(e.x(), e.y(), e.z()) < 1.0) stacked++;
        float ang = mc.level.random.nextFloat() * Mth.TWO_PI;
        float spread = 0.25f + 0.18f * Math.min(4, stacked);
        if (NUMS.size() >= MAX) NUMS.remove(0);
        NUMS.add(new Num(new Vec3(e.x(), e.y() + 0.15 + 0.22 * Math.min(4, stacked), e.z()), text, color, big, kill,
                Mth.cos(ang) * spread, Mth.sin(ang) * spread, now));
        // 맞은 생물이 한순간 번쩍 (타격 확인)
        Entity hit = e.target() >= 0 ? mc.level.getEntity(e.target()) : null;
        if (hit != null && !hit.isRemoved()) {
            Vec3 c = hit.getBoundingBox().getCenter();
            float sz = Math.max(0.6f, hit.getBbHeight()) * (big ? 1.2f : 0.9f);
            FxManager.spawn(new Sprite(Sprite.Kind.GLOW, c, Vec3.ZERO, 1.4f * sz, 2.2f * sz, 2).color(1f, 1f, 1f, 0.7f));
        }
        // 맞힌 느낌 (너무 잦으면 생략)
        if (now - lastSound >= 2) {
            lastSound = now;
            mc.player.playSound(kill ? SoundEvents.PLAYER_ATTACK_CRIT : SoundEvents.PLAYER_ATTACK_STRONG, kill ? 0.55f : 0.3f, big ? 0.8f : 1.25f);
        }
    }

    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || NUMS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui) {
            NUMS.clear();
            return;
        }
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        long now = mc.level.getGameTime();
        Camera cam = event.getCamera();
        Vec3 cp = cam.getPosition();
        Font font = mc.font;
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        PoseStack ps = PS;
        ps.last().pose().identity().mul(event.getModelViewMatrix());
        ps.last().normal().identity();
        NUMS.removeIf(n -> now - n.born > LIFE);
        for (Num n : NUMS) {
            float age = (now - n.born) + pt;
            float a = Mth.clamp(age / LIFE, 0f, 1f);
            // 0~3틱: 크게 튀어나옴 → 줄어들며 위로 떠오름 → 마지막 30% 동안 흐려짐
            float pop = age < 3 ? (n.kill ? 1.9f : 1.6f) - (n.kill ? 0.3f : 0.2f) * age : 1f;
            float rise = 0.9f * (1 - (1 - a) * (1 - a));
            double x = n.pos.x + n.dx * a, y = n.pos.y + rise, z = n.pos.z + n.dz * a;
            // 마무리 일격: 처음 4틱 살짝 떨림
            if (n.kill && age < 4) {
                float sh = 0.04f * (1f - age / 4f);
                x += Mth.sin(age * 9.1f) * sh;
                y += Mth.cos(age * 7.3f) * sh;
            }
            double dist = Math.sqrt(cp.distanceToSqr(x, y, z));
            if (dist > 64) continue;
            float alpha = a < 0.7f ? 1f : 1f - (a - 0.7f) / 0.3f;
            int ai = Math.max(8, (int) (alpha * 255)) << 24;
            float scale = 0.026f * (n.kill ? 1.7f : n.big ? 1.45f : 1f) * pop * (float) Math.max(1.0, Math.sqrt(dist / 6.0));
            ps.pushPose();
            ps.translate(x - cp.x, y - cp.y, z - cp.z);
            ps.mulPose(cam.rotation());
            ps.scale(scale, -scale, scale);
            float w = font.width(n.text) / 2f;
            int base = n.kill ? 0xFFE27A : brighten(n.color);
            // 처음 2틱은 하얗게 번쩍 → 능력 색
            int fill = ai | (age < 2f ? mixWhite(base, 1f - age / 2f) : base);
            int edge = ai | (n.kill ? 0x5A2A00 : 0x101010);
            // 테두리 (8방향, 밝은 폭발 위에서도 읽힘) + 본문
            for (int[] o : OUTLINE) {
                font.drawInBatch(n.text, -w + o[0], o[1], edge, false, ps.last().pose(), buf, Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
            }
            ps.translate(0, 0, -0.01f);
            font.drawInBatch(n.text, -w, 0, fill, false, ps.last().pose(), buf, Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
            ps.popPose();
        }
        buf.endBatch();
    }

    private static int mixWhite(int rgb, float k) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r = r + (int) ((255 - r) * k);
        g = g + (int) ((255 - g) * k);
        b = b + (int) ((255 - b) * k);
        return (r << 16) | (g << 8) | b;
    }

    private static int brighten(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r = r + (255 - r) / 3;
        g = g + (255 - g) / 3;
        b = b + (255 - b) / 3;
        return (r << 16) | (g << 8) | b;
    }

    static void clear() {
        NUMS.clear();
    }
}
