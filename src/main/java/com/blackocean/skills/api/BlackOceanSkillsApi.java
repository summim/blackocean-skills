package com.blackocean.skills.api;

import com.blackocean.skills.fire.FireFistServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 다른 모드(별풍선 연동 등)에서 부르는 창구. 반드시 서버 메인 스레드에서 호출.
 * <pre>
 * BlackOceanSkillsApi.castFireFist(player, true);   // 쿨타임 무시하고 화염 주먹 발사 (보상)
 * BlackOceanSkillsApi.resetCooldown(player);         // 쿨타임 즉시 초기화
 * int left = BlackOceanSkillsApi.cooldownTicks(player); // 남은 쿨타임 (틱)
 * </pre>
 */
public final class BlackOceanSkillsApi {
    private BlackOceanSkillsApi() {}

    /**
     * @param ignoreCooldown true 면 쿨타임을 확인하지도, 새로 걸지도 않는다.
     * @return 발사 준비를 시작했으면 true (죽었거나 이미 준비 중이면 false)
     */
    public static boolean castFireFist(ServerPlayer player, boolean ignoreCooldown) {
        if (!ignoreCooldown && FireFistServer.remainingTicks(player) > 0) return false;
        return FireFistServer.cast(player, !ignoreCooldown);
    }

    public static void resetCooldown(ServerPlayer player) {
        FireFistServer.resetCooldown(player);
    }

    public static int cooldownTicks(ServerPlayer player) {
        return FireFistServer.remainingTicks(player);
    }
}
