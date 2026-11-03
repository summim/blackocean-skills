package com.blackocean.skills.client;

import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillsMod;
import com.blackocean.skills.client.fx.AimPreview;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 스킬 칸 4개 (핫바 오른쪽). 칸마다 아이콘, 키 번호, 쿨타임(위에서부터 덮이는 어두운 막 + 남은 초).
 * <ul>
 *   <li>준비 완료 순간: 주황 테두리가 반짝</li>
 *   <li>눌렀을 때: 흰 테두리가 잠깐</li>
 *   <li>쿨타임 중에 누름·거절: 빨간 테두리가 잠깐</li>
 * </ul>
 * 화면이 좁아 핫바 오른쪽에 자리가 없으면 체력 칸 위로 올라간다.
 */
public final class SkillBarHud {
    private static final int N = Skill.VALUES.length;
    private static final int SLOT = 20, GAP = 3;
    private static final ResourceLocation[] ICONS = new ResourceLocation[N];

    static {
        for (Skill s : Skill.VALUES) {
            ICONS[s.ordinal()] = SkillsMod.id("textures/gui/skills/" + s.id + ".png");
        }
    }

    private static final int[] remaining = new int[N];
    private static final int[] total = new int[N];
    private static final int[] readyFlash = new int[N];
    private static final int[] pressFlash = new int[N];
    private static final int[] deniedFlash = new int[N];
    /** 예약됨 (쿨타임 끝나는 순간·지금 모으는 스킬이 끝나는 순간 바로 나감) */
    private static final int[] queued = new int[N];

    private SkillBarHud() {}

    public static void set(Skill s, int remainingTicks, int totalTicks) {
        int i = s.ordinal();
        boolean wasCooling = remaining[i] > 0;
        remaining[i] = Math.max(0, remainingTicks);
        total[i] = Math.max(remaining[i], totalTicks);
        if (remaining[i] == 0 && wasCooling) readyFlash[i] = 12;
        queued[i] = 0;
    }

    static void flashQueued(Skill s) {
        queued[s.ordinal()] = 40;
    }

    /** 내 시전이 시작됨 → 예약 표시 끔 */
    static void castStarted(Skill s) {
        queued[s.ordinal()] = 0;
    }

    public static void resetAll() {
        for (Skill s : Skill.VALUES) set(s, 0, 0);
    }

    /** 쿨타임 중이면 true → 이때는 키를 눌러도 가짜 준비 연출을 시작하지 않는다. */
    public static boolean coolingDown(Skill s) {
        return remaining[s.ordinal()] > 0;
    }

    public static int remaining(Skill s) {
        return remaining[s.ordinal()];
    }

    static void flashPressed(Skill s) {
        pressFlash[s.ordinal()] = 5;
    }

    static void flashDenied(Skill s) {
        deniedFlash[s.ordinal()] = 8;
    }

    static void tick() {
        for (int i = 0; i < N; i++) {
            if (remaining[i] > 0) {
                remaining[i]--;
                if (remaining[i] == 0) readyFlash[i] = 12;
            } else if (readyFlash[i] > 0) {
                readyFlash[i]--;
            }
            if (pressFlash[i] > 0) pressFlash[i]--;
            if (queued[i] > 0) queued[i]--;
            if (deniedFlash[i] > 0) deniedFlash[i]--;
        }
    }

    static void clear() {
        for (int i = 0; i < N; i++) {
            remaining[i] = total[i] = readyFlash[i] = pressFlash[i] = deniedFlash[i] = queued[i] = 0;
        }
    }

    public static void register(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, SkillsMod.id("skill_bar"), SkillBarHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.player.isSpectator() || !SkillsClientConfig.COOLDOWN_HUD.get()) return;

        Font font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        int barW = N * SLOT + (N - 1) * GAP;
        int x0 = w / 2 + 91 + 8;
        int y0 = h - SLOT - 1;
        if (x0 + barW > w - 2) {
            // 자리가 없으면 체력·배고픔 칸 위, 가운데
            x0 = w / 2 - barW / 2;
            y0 = h - 39 - 12 - SLOT;
        }
        float pt = mc.isPaused() ? 0f : delta.getGameTimeDeltaPartialTick(false);

        for (Skill s : Skill.VALUES) {
            int i = s.ordinal();
            int x = x0 + i * (SLOT + GAP), y = y0;
            boolean cooling = remaining[i] > 0;

            // 테두리 색: 거절(빨강) > 누름(흰색) > 준비 완료 반짝(밝은 주황) > 평소
            int border;
            boolean aimingThis = AimPreview.aiming() == s;
            if (deniedFlash[i] > 0) border = 0xFFE03030;
            else if (pressFlash[i] > 0) border = 0xFFFFF4D8;
            else if (queued[i] > 0) border = (queued[i] / 3) % 2 == 0 ? 0xFFFFE14A : 0xFFB89A30;
            else if (aimingThis) border = 0xFFFFC870;
            else if (readyFlash[i] > 0) border = (readyFlash[i] / 2) % 2 == 0 ? 0xFFFFD27A : 0xFFFF8A2A;
            else border = cooling ? 0xFF3A3434 : 0xFFB8561E;

            if (aimingThis) y -= 3; // 조준 중인 칸은 살짝 올라옴
            g.fill(x - 1, y - 1, x + SLOT + 1, y + SLOT + 1, border);
            g.fill(x, y, x + SLOT, y + SLOT, 0xD0140C0A);
            g.blit(ICONS[i], x + 2, y + 2, 16, 16, 0f, 0f, 64, 64, 64, 64);

            if (cooling) {
                float left = Math.max(0f, remaining[i] - pt);
                float frac = total[i] > 0 ? left / total[i] : 0f;
                int cover = Math.round(16 * Math.min(1f, frac));
                g.fill(x + 2, y + 2, x + 18, y + 2 + cover, 0xB0000000);
                g.fill(x + 2, y + 2 + cover, x + 18, y + 18, 0x30000000);
                String sec = left >= 200f ? String.valueOf(Math.round(left / 20f)) : String.format("%.1f", left / 20f);
                g.drawString(font, sec, x + SLOT / 2 - font.width(sec) / 2, y + 6, 0xFFFFE0B0, true);
            }

            // 키 번호 (오른쪽 아래)
            Component key = SkillsClient.SKILL_KEYS[i].getTranslatedKeyMessage();
            String label = key.getString();
            if (label.length() > 2) label = label.substring(0, 2);
            g.drawString(font, label, x + SLOT - font.width(label), y + SLOT - 8, cooling ? 0xFF9A9090 : 0xFFFFFFFF, true);
        }

        // 조준 중 안내 (조준점 아래)
        Skill aim = AimPreview.aiming();
        if (aim != null && AimPreview.visible()) {
            int cx = w / 2, cy = h / 2 + 12;
            Component name = Component.translatable(aim.translationKey());
            g.drawString(font, name, cx - font.width(name) / 2, cy, 0xFFFFC870, true);
            Component hint;
            int color;
            int left = remaining[aim.ordinal()];
            if (left > com.blackocean.skills.SkillServer.BUFFER_TICKS) {
                hint = Component.translatable("hud.blackocean_skills.aim_cooldown", String.format("%.1f", left / 20f));
                color = 0xFFFF7060;
            } else {
                hint = Component.translatable("hud.blackocean_skills.aim_hint");
                color = 0xFFE0D6C8;
            }
            g.drawString(font, hint, cx - font.width(hint) / 2, cy + 10, color, true);
        }
    }
}
