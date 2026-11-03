package com.blackocean.skills.api;

import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 다른 모드(별풍선 연동 등)에서 부르는 창구. 반드시 서버 메인 스레드에서 호출.
 * <pre>
 * BlackOceanSkillsApi.cast(player, Skill.FIRE_PILLAR, true);  // 쿨타임 무시하고 불기둥 (보상)
 * BlackOceanSkillsApi.castFireFist(player, true);              // 1.1 과 같음
 * BlackOceanSkillsApi.resetCooldown(player);                    // 모든 스킬 쿨타임 초기화
 * int left = BlackOceanSkillsApi.cooldownTicks(player, Skill.FLAME_RING); // 남은 쿨타임 (틱)
 * </pre>
 */
public final class BlackOceanSkillsApi {
    private BlackOceanSkillsApi() {}

    /**
     * @param ignoreCooldown true 면 쿨타임을 확인하지도, 새로 걸지도 않는다.
     * @return 발동 준비를 시작했으면 true (죽었거나 다른 스킬 준비 중, 불기둥 조준 실패면 false)
     */
    public static boolean cast(ServerPlayer player, Skill skill, boolean ignoreCooldown) {
        if (!ignoreCooldown && SkillServer.remainingTicks(player, skill) > 0) return false;
        return SkillServer.cast(player, skill, !ignoreCooldown);
    }

    public static boolean castFireFist(ServerPlayer player, boolean ignoreCooldown) {
        return cast(player, Skill.FIRE_FIST, ignoreCooldown);
    }

    /** 모든 스킬 쿨타임 초기화 */
    public static void resetCooldown(ServerPlayer player) {
        SkillServer.resetCooldown(player, null);
    }

    public static void resetCooldown(ServerPlayer player, Skill skill) {
        SkillServer.resetCooldown(player, skill);
    }

    /** 화염 주먹 남은 쿨타임 (1.1 과 같음) */
    public static int cooldownTicks(ServerPlayer player) {
        return SkillServer.remainingTicks(player, Skill.FIRE_FIST);
    }

    public static int cooldownTicks(ServerPlayer player, Skill skill) {
        return SkillServer.remainingTicks(player, skill);
    }
}
