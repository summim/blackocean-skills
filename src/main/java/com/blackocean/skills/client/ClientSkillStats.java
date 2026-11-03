package com.blackocean.skills.client;

import com.blackocean.skills.SkillsConfig;
import com.blackocean.skills.network.SkillStatsPayload;
import org.jetbrains.annotations.Nullable;

/**
 * 조준 표시에 쓰는 사거리·범위. 접속하면 서버가 실제 값을 보내 주고,
 * 받기 전(또는 혼자 하기)에는 내 PC 의 설정 파일 값을 쓴다.
 */
public final class ClientSkillStats {
    @Nullable
    private static SkillStatsPayload server;

    private ClientSkillStats() {}

    static void set(SkillStatsPayload p) {
        server = p;
    }

    static void clear() {
        server = null;
    }

    public static float fistRange() { return server != null ? server.fistRange() : SkillsConfig.RANGE.get().floatValue(); }
    public static float fistHitRadius() { return server != null ? server.fistHitRadius() : SkillsConfig.HIT_RADIUS.get().floatValue(); }
    public static float fistSplash() { return server != null ? server.fistSplash() : SkillsConfig.SPLASH_RADIUS.get().floatValue(); }
    public static float bulletsRange() { return server != null ? server.bulletsRange() : SkillsConfig.BULLETS_RANGE.get().floatValue(); }
    public static float bulletsSplash() { return server != null ? server.bulletsSplash() : SkillsConfig.BULLETS_SPLASH_RADIUS.get().floatValue(); }
    public static float bulletsSpread() { return server != null ? server.bulletsSpread() : SkillsConfig.BULLETS_SPREAD.get().floatValue(); }
    public static float pillarRange() { return server != null ? server.pillarRange() : SkillsConfig.PILLAR_RANGE.get().floatValue(); }
    public static float pillarRadius() { return server != null ? server.pillarRadius() : SkillsConfig.PILLAR_RADIUS.get().floatValue(); }
    public static float pillarHeight() { return server != null ? server.pillarHeight() : SkillsConfig.PILLAR_HEIGHT.get().floatValue(); }
    public static float ringRadius() { return server != null ? server.ringRadius() : SkillsConfig.RING_RADIUS.get().floatValue(); }
}
