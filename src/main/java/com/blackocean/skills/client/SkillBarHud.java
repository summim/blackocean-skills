package com.blackocean.skills.client;

import com.blackocean.skills.Element;
import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillServer;
import com.blackocean.skills.SkillsMod;
import com.blackocean.skills.client.fx.AimPreview;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.Locale;

/**
 * 스킬 칸 5개 (핫바 오른쪽). 내 능력의 스킬 아이콘, 키 번호, 쿨타임(어두운 막 + 남은 초).
 * 5번(궁극기) 칸은 조금 크고 금색 테두리, 준비되면 반짝인다.
 * <ul>
 *   <li>준비 완료 순간: 테두리 반짝</li>
 *   <li>눌렀을 때: 흰 테두리, 예약됨: 노란 깜빡임, 쿨타임 중에 누름·거절: 빨간 테두리</li>
 * </ul>
 * 왼손잡이 보조손 칸·핫바 공격 표시와 겹치지 않게 옆으로 비키고, 자리가 없으면 체력 칸 위로 올라간다.
 */
public final class SkillBarHud {
    private static final int N = Skill.VALUES.length;
    private static final int SLOT = 20, ULT = 24, GAP = 3;
    private static final ResourceLocation[] ICONS = new ResourceLocation[N];

    static {
        for (Skill s : Skill.VALUES) ICONS[s.ordinal()] = SkillsMod.id("textures/gui/skills/" + s.id + ".png");
    }

    private static final int[] remaining = new int[N];
    private static final int[] total = new int[N];
    private static final int[] readyFlash = new int[N];
    private static final int[] pressFlash = new int[N];
    private static final int[] deniedFlash = new int[N];
    private static final int[] queued = new int[N];
    private static int ticks;

    private SkillBarHud() {}

    public static void set(Skill s, int remainingTicks, int totalTicks) {
        int i = s.ordinal();
        boolean wasCooling = remaining[i] > 0;
        remaining[i] = Math.max(0, remainingTicks);
        total[i] = Math.max(remaining[i], totalTicks);
        if (remaining[i] == 0 && wasCooling) readyFlash[i] = 14;
        queued[i] = 0;
    }

    public static void resetAll() {
        for (Skill s : Skill.VALUES) set(s, 0, 0);
    }

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
        queued[s.ordinal()] = 0;
    }

    static void flashQueued(Skill s) {
        queued[s.ordinal()] = 50;
    }

    static void castStarted(Skill s) {
        queued[s.ordinal()] = 0;
    }

    static void tick() {
        ticks++;
        for (int i = 0; i < N; i++) {
            if (remaining[i] > 0) {
                remaining[i]--;
                if (remaining[i] == 0) readyFlash[i] = 14;
            } else if (readyFlash[i] > 0) {
                readyFlash[i]--;
            }
            if (pressFlash[i] > 0) pressFlash[i]--;
            if (deniedFlash[i] > 0) deniedFlash[i]--;
            if (queued[i] > 0) queued[i]--;
        }
    }

    static void clear() {
        for (int i = 0; i < N; i++) remaining[i] = total[i] = readyFlash[i] = pressFlash[i] = deniedFlash[i] = queued[i] = 0;
    }

    public static void register(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, SkillsMod.id("skill_bar"), SkillBarHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Element el = ClientElement.get();
        if (el == null || mc.options.hideGui || mc.player == null || mc.player.isSpectator() || !SkillsClientConfig.COOLDOWN_HUD.get()) return;

        Font font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        int barW = 4 * SLOT + ULT + 4 * GAP;
        int x0 = w / 2 + 91 + 8;
        // 핫바 오른쪽에 그려지는 것들을 피함: 왼손잡이의 보조손 칸, 핫바식 공격 표시
        if (mc.player.getMainArm() == HumanoidArm.LEFT && !mc.player.getOffhandItem().isEmpty()) x0 += 29;
        if (mc.options.attackIndicator().get() == AttackIndicatorStatus.HOTBAR) x0 += 24;
        int base = h - 1; // 칸 아래쪽 기준선
        if (x0 + barW > w - 2) {
            x0 = w / 2 - barW / 2;
            base = h - 39 - 12;
        }
        float pt = mc.isPaused() ? 0f : delta.getGameTimeDeltaPartialTick(false);
        int elColor = 0xFF000000 | el.color;

        int x = x0;
        for (int slot = 0; slot < Skill.SLOTS; slot++) {
            Skill s = Skill.of(el, slot);
            int i = s.ordinal();
            boolean ult = s.ultimate();
            int size = ult ? ULT : SLOT;
            int y = base - size;
            boolean cooling = remaining[i] > 0;
            boolean aimingThis = AimPreview.aiming() == s;
            if (aimingThis) y -= 3; // 조준 중인 칸은 살짝 올라옴

            int border;
            if (deniedFlash[i] > 0) border = 0xFFE03030;
            else if (pressFlash[i] > 0) border = 0xFFFFF4D8;
            else if (queued[i] > 0) border = (queued[i] / 3) % 2 == 0 ? 0xFFFFE14A : 0xFFB89A30;
            else if (readyFlash[i] > 0) border = (readyFlash[i] / 2) % 2 == 0 ? 0xFFFFFFFF : elColor;
            else if (aimingThis) border = 0xFFFFE0A0;
            else if (ult) border = cooling ? 0xFF5A4A20 : pulse(0xFFE8B84A, 0xFFFFE89A, ticks + pt);
            else border = cooling ? 0xFF3A3434 : darker(elColor);

            if (ult) g.fill(x - 2, y - 2, x + size + 2, y + size + 2, cooling ? 0x80000000 : 0x60FFD060);
            g.fill(x - 1, y - 1, x + size + 1, y + size + 1, border);
            g.fill(x, y, x + size, y + size, 0xD0140C0A);
            int icon = size - 4;
            g.blit(ICONS[i], x + 2, y + 2, icon, icon, 0f, 0f, 64, 64, 64, 64);

            if (cooling) {
                float left = Math.max(0f, remaining[i] - pt);
                float frac = total[i] > 0 ? left / total[i] : 0f;
                int cover = Math.round(icon * Math.min(1f, frac));
                g.fill(x + 2, y + 2, x + 2 + icon, y + 2 + cover, 0xB0000000);
                g.fill(x + 2, y + 2 + cover, x + 2 + icon, y + 2 + icon, 0x30000000);
                String sec = left >= 200f ? String.valueOf(Math.round(left / 20f)) : String.format(Locale.ROOT, "%.1f", left / 20f);
                g.drawString(font, sec, x + size / 2 - font.width(sec) / 2, y + size / 2 - 4, 0xFFFFE0B0, true);
            }
            String label = SkillsClient.SKILL_KEYS[slot].getTranslatedKeyMessage().getString();
            if (label.length() > 2) label = label.substring(0, 2);
            g.drawString(font, label, x + size - font.width(label), y + size - 8, cooling ? 0xFF9A9090 : 0xFFFFFFFF, true);
            x += size + GAP;
        }

        // 조준 중 안내 (조준점 아래)
        Skill aim = AimPreview.aiming();
        if (aim != null && AimPreview.visible()) {
            int cx = w / 2, cy = h / 2 + 12;
            Component name = Component.translatable(aim.translationKey());
            g.drawString(font, name, cx - font.width(name) / 2, cy, 0xFF000000 | aim.element.color, true);
            int left = remaining[aim.ordinal()];
            Component hint = left > SkillServer.BUFFER_TICKS
                    ? Component.translatable("hud.blackocean_skills.aim_cooldown", String.format(Locale.ROOT, "%.1f", left / 20f))
                    : Component.translatable("hud.blackocean_skills.aim_hint");
            g.drawString(font, hint, cx - font.width(hint) / 2, cy + 10, left > SkillServer.BUFFER_TICKS ? 0xFFFF7060 : 0xFFE0D6C8, true);
        }
    }

    private static int darker(int argb) {
        int r = ((argb >> 16) & 0xFF) * 3 / 4, gg = ((argb >> 8) & 0xFF) * 3 / 4, b = (argb & 0xFF) * 3 / 4;
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    private static int pulse(int a, int b, float t) {
        float k = 0.5f + 0.5f * (float) Math.sin(t * 0.25f);
        int r = (int) (((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int gg = (int) (((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = (int) ((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (gg << 8) | bl;
    }
}
