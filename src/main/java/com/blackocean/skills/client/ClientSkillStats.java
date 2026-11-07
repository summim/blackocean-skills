package com.blackocean.skills.client;

import com.blackocean.skills.Skill;
import com.blackocean.skills.network.SkillStatsPayload;
import org.jetbrains.annotations.Nullable;

/**
 * 조준 표시에 쓰는 사거리·반경 등. 접속하면 서버가 실제 값을 보내 주고,
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

    private static boolean has(Skill s) {
        return server != null && s.ordinal() < server.range().length;
    }

    public static float range(Skill s) {
        return has(s) ? server.range()[s.ordinal()] : (float) s.range();
    }

    public static float radius(Skill s) {
        return has(s) ? server.radius()[s.ordinal()] : (float) s.radius();
    }

    public static int durationTicks(Skill s) {
        return has(s) ? Math.round(server.duration()[s.ordinal()]) : s.durationTicks();
    }

    public static float speed(Skill s) {
        return has(s) ? server.speed()[s.ordinal()] : (float) s.speed();
    }

    /** 모으는 시간 (서버 값은 시전 시작 신호로 다시 맞춰짐) */
    public static int chargeTicks(Skill s) {
        return s.chargeTicks();
    }
}
