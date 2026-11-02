package com.blackocean.skills.client;

import com.blackocean.skills.SkillsMod;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 화염 주먹 쿨타임 표시 (조준점 바로 아래 작은 막대 + 남은 초).
 * 서버가 보내 준 값으로 시작해서 클라이언트가 매 틱 줄여 나간다.
 * 다 차면 "화염 주먹 준비!" 가 잠깐 떴다가 사라진다.
 */
public final class CooldownHud {
    private static int remaining;
    private static int total;
    private static int readyFlash;

    private CooldownHud() {}

    public static void set(int remainingTicks, int totalTicks) {
        boolean wasCooling = remaining > 0;
        remaining = Math.max(0, remainingTicks);
        total = Math.max(remaining, totalTicks);
        if (remaining == 0 && wasCooling) readyFlash = 24;
    }

    /** 쿨타임 중이면 true → 이때는 키를 눌러도 가짜 준비 연출을 시작하지 않는다. */
    public static boolean coolingDown() {
        return remaining > 0;
    }

    public static int remaining() {
        return remaining;
    }

    static void tick() {
        if (remaining > 0) {
            remaining--;
            if (remaining == 0) readyFlash = 24;
        } else if (readyFlash > 0) {
            readyFlash--;
        }
    }

    static void clear() {
        remaining = 0;
        total = 0;
        readyFlash = 0;
    }

    public static void register(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, SkillsMod.id("fire_fist_cooldown"), CooldownHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || !SkillsClientConfig.COOLDOWN_HUD.get()) return;
        if (remaining <= 0 && readyFlash <= 0) return;

        Font font = mc.font;
        int cx = g.guiWidth() / 2;
        int y = g.guiHeight() / 2 + 9;

        if (remaining > 0) {
            float pt = mc.isPaused() ? 0f : delta.getGameTimeDeltaPartialTick(false);
            float left = Math.max(0f, remaining - pt);
            float frac = total > 0 ? 1f - left / total : 1f;
            int w = 30, h = 3, x0 = cx - w / 2;
            g.fill(x0 - 1, y - 1, x0 + w + 1, y + h + 1, 0x90000000);
            g.fill(x0, y, x0 + Math.round(w * frac), y + h, 0xFFFF8A2A);
            String sec = String.format("%.1f", left / 20f);
            g.drawString(font, sec, cx - font.width(sec) / 2, y + h + 3, 0xFFFFD08A, true);
        } else {
            int a = Math.min(255, readyFlash * 20);
            if (a < 8) return;
            Component msg = Component.translatable("hud.blackocean_skills.ready");
            g.drawString(font, msg, cx - font.width(msg) / 2, y + 2, (a << 24) | 0xFFC060, true);
        }
    }
}
