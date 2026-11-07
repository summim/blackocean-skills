package com.blackocean.skills.api;

import com.blackocean.skills.Element;
import com.blackocean.skills.PlayerElements;
import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 다른 모드(별풍선 연동 등)에서 부르는 창구. 반드시 서버 메인 스레드에서 호출.
 * <pre>
 * BlackOceanSkillsApi.cast(player, Skill.LIGHTNING_STORM, true);   // 쿨타임 무시하고 궁극기 (보상)
 * BlackOceanSkillsApi.castSlot(player, 4, true);                   // 그 사람 능력의 5번(궁극기)
 * BlackOceanSkillsApi.setElement(player, Element.WATER);           // 능력 바꾸기
 * BlackOceanSkillsApi.resetCooldown(player);                        // 모든 쿨타임 초기화
 * </pre>
 */
public final class BlackOceanSkillsApi {
    private BlackOceanSkillsApi() {}

    /** @return 시작했으면 true (죽었거나 다른 스킬 모으는 중, 바닥 조준 실패면 false) */
    public static boolean cast(ServerPlayer player, Skill skill, boolean ignoreCooldown) {
        if (!ignoreCooldown && SkillServer.remainingTicks(player, skill) > 0) return false;
        return SkillServer.cast(player, skill, !ignoreCooldown);
    }

    /** 그 사람 능력의 slot(0~4)번 스킬. 능력이 없으면 false */
    public static boolean castSlot(ServerPlayer player, int slot, boolean ignoreCooldown) {
        Element e = PlayerElements.get(player);
        if (e == null || slot < 0 || slot >= Skill.SLOTS) return false;
        return cast(player, Skill.of(e, slot), ignoreCooldown);
    }

    public static boolean castFireFist(ServerPlayer player, boolean ignoreCooldown) {
        return cast(player, Skill.FIRE_FIST, ignoreCooldown);
    }

    @Nullable
    public static Element getElement(ServerPlayer player) {
        return PlayerElements.get(player);
    }

    /** null = 능력 없음 */
    public static void setElement(ServerPlayer player, @Nullable Element element) {
        PlayerElements.set(player, element);
    }

    public static void resetCooldown(ServerPlayer player) {
        SkillServer.resetCooldown(player, null);
    }

    public static void resetCooldown(ServerPlayer player, Skill skill) {
        SkillServer.resetCooldown(player, skill);
    }

    public static int cooldownTicks(ServerPlayer player) {
        return SkillServer.remainingTicks(player, Skill.FIRE_FIST);
    }

    public static int cooldownTicks(ServerPlayer player, Skill skill) {
        return SkillServer.remainingTicks(player, skill);
    }
}
