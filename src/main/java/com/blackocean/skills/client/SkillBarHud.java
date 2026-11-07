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

import org.jetbrains.annotations.Nullable;

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

    // =====================================================================
    // 캐시 (프레임마다 문자열·번역·자원 검사를 다시 하지 않게)
    // =====================================================================

    /** 능력 × 키 → 스킬 (Skill.of 는 30개를 훑으므로 미리) */
    private static final Skill[][] TABLE = new Skill[Element.VALUES.length][Skill.SLOTS];
    /** 아이콘 그림이 있는지: 0 = 아직 모름, 1 = 있음, 2 = 없음 (얼음·천둥처럼 그림이 아직 없으면 글자 아이콘) */
    private static final byte[] iconState = new byte[N];
    private static final String[] glyph = new String[N];
    private static final int[] cdKey = new int[N];
    private static final String[] cdText = new String[N];
    private static final int[] cdWidth = new int[N];
    private static final String[] keyLabel = new String[Skill.SLOTS];
    private static final int[] keyWidth = new int[Skill.SLOTS];
    private static int keyLabelTick = Integer.MIN_VALUE;
    @Nullable
    private static Skill aimNameSkill;
    @Nullable
    private static Component aimName;
    private static int aimNameWidth;
    private static int aimHintKey = Integer.MIN_VALUE;
    @Nullable
    private static Component aimHint;
    private static int aimHintWidth;

    static {
        for (Skill s : Skill.VALUES) TABLE[s.element.ordinal()][s.slot] = s;
        java.util.Arrays.fill(cdKey, Integer.MIN_VALUE);
    }

    /** 자원 다시 불러오기·언어 변경 때 (SkillsClient 의 reload listener) */
    static void invalidateCaches() {
        java.util.Arrays.fill(iconState, (byte) 0);
        java.util.Arrays.fill(glyph, null);
        java.util.Arrays.fill(cdKey, Integer.MIN_VALUE);
        keyLabelTick = Integer.MIN_VALUE;
        aimNameSkill = null;
        aimHintKey = Integer.MIN_VALUE;
    }

    private static boolean hasIcon(Minecraft mc, int i) {
        byte st = iconState[i];
        if (st == 0) {
            st = mc.getResourceManager().getResource(ICONS[i]).isPresent() ? (byte) 1 : (byte) 2;
            iconState[i] = st;
        }
        return st == 1;
    }

    private static String glyphOf(Skill s) {
        String g = glyph[s.ordinal()];
        if (g == null) {
            String name = Component.translatable(s.translationKey()).getString();
            g = name.isEmpty() ? "?" : name.substring(0, Character.charCount(name.codePointAt(0)));
            glyph[s.ordinal()] = g;
        }
        return g;
    }

    /** 남은 쿨타임 글자 (10초 이상은 정수, 아래는 소수 한 자리). 보이는 값이 바뀔 때만 새로 만듦 */
    private static String cooldownText(Font font, int i, float left) {
        int key = left >= 200f ? 100000 + Math.round(left / 20f) : Math.round(left / 2f);
        if (key != cdKey[i]) {
            cdKey[i] = key;
            cdText[i] = key >= 100000 ? String.valueOf(key - 100000) : (key / 10) + "." + (key % 10);
            cdWidth[i] = font.width(cdText[i]);
        }
        return cdText[i];
    }

    private static void refreshKeyLabels(Font font) {
        if (ticks - keyLabelTick < 20 && keyLabelTick != Integer.MIN_VALUE) return;
        keyLabelTick = ticks;
        for (int slot = 0; slot < Skill.SLOTS; slot++) {
            String label = SkillsClient.SKILL_KEYS[slot].getTranslatedKeyMessage().getString();
            if (label.length() > 2) label = label.substring(0, 2);
            keyLabel[slot] = label;
            keyWidth[slot] = font.width(label);
        }
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Element el = ClientElement.get();
        if (el == null || mc.options.hideGui || mc.player == null || mc.player.isSpectator() || !SkillsClientConfig.COOLDOWN_HUD.get()) return;

        Font font = mc.font;
        refreshKeyLabels(font);
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
        Skill aimSkill = AimPreview.aiming();
        Skill[] row = TABLE[el.ordinal()];

        int x = x0;
        for (int slot = 0; slot < Skill.SLOTS; slot++) {
            Skill s = row[slot];
            int i = s.ordinal();
            boolean ult = s.ultimate();
            int size = ult ? ULT : SLOT;
            int y = base - size;
            boolean cooling = remaining[i] > 0;
            boolean aimingThis = aimSkill == s;
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
            if (hasIcon(mc, i)) {
                g.blit(ICONS[i], x + 2, y + 2, icon, icon, 0f, 0f, 64, 64, 64, 64);
            } else {
                // 그림이 없는 스킬: 능력 색 바탕 + 스킬 이름 첫 글자
                g.fillGradient(x + 2, y + 2, x + 2 + icon, y + 2 + icon, 0xE0000000 | (el.color & 0xFFFFFF), 0xE0000000 | (darker(elColor) & 0x7F7F7F));
                String gl = glyphOf(s);
                g.drawString(font, gl, x + size / 2 - font.width(gl) / 2, y + size / 2 - 4, 0xFFFFFFFF, true);
            }

            if (cooling) {
                float left = Math.max(0f, remaining[i] - pt);
                float frac = total[i] > 0 ? left / total[i] : 0f;
                int cover = Math.round(icon * Math.min(1f, frac));
                g.fill(x + 2, y + 2, x + 2 + icon, y + 2 + cover, 0xB0000000);
                g.fill(x + 2, y + 2 + cover, x + 2 + icon, y + 2 + icon, 0x30000000);
                String sec = cooldownText(font, i, left);
                g.drawString(font, sec, x + size / 2 - cdWidth[i] / 2, y + size / 2 - 4, 0xFFFFE0B0, true);
            }
            g.drawString(font, keyLabel[slot], x + size - keyWidth[slot], y + size - 8, cooling ? 0xFF9A9090 : 0xFFFFFFFF, true);
            x += size + GAP;
        }

        // 조준 중 안내 (조준점 아래)
        if (aimSkill != null && AimPreview.visible()) {
            int cx = w / 2, cy = h / 2 + 12;
            if (aimNameSkill != aimSkill || aimName == null) {
                aimNameSkill = aimSkill;
                aimName = Component.translatable(aimSkill.translationKey());
                aimNameWidth = font.width(aimName);
            }
            g.drawString(font, aimName, cx - aimNameWidth / 2, cy, 0xFF000000 | aimSkill.element.color, true);
            int left = remaining[aimSkill.ordinal()];
            boolean blocked = left > SkillServer.BUFFER_TICKS;
            int key = blocked ? Math.round(left / 2f) : -1;
            if (key != aimHintKey || aimHint == null) {
                aimHintKey = key;
                aimHint = blocked
                        ? Component.translatable("hud.blackocean_skills.aim_cooldown", (key / 10) + "." + (key % 10))
                        : Component.translatable("hud.blackocean_skills.aim_hint");
                aimHintWidth = font.width(aimHint);
            }
            g.drawString(font, aimHint, cx - aimHintWidth / 2, cy + 10, blocked ? 0xFFFF7060 : 0xFFE0D6C8, true);
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
