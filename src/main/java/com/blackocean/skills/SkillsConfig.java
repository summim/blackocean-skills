package com.blackocean.skills;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 서버 설정: config/blackocean_skills-common.toml
 * 방송 중 밸런스를 바꿀 때 코드를 다시 빌드할 필요 없이 이 파일 숫자만 바꾸면 된다.
 */
public final class SkillsConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue CHARGE_TICKS;
    public static final ModConfigSpec.DoubleValue SPEED;
    public static final ModConfigSpec.DoubleValue RANGE;
    public static final ModConfigSpec.DoubleValue HIT_RADIUS;
    public static final ModConfigSpec.DoubleValue DIRECT_DAMAGE;
    public static final ModConfigSpec.DoubleValue SPLASH_DAMAGE;
    public static final ModConfigSpec.DoubleValue SPLASH_RADIUS;
    public static final ModConfigSpec.DoubleValue KNOCKBACK;
    public static final ModConfigSpec.BooleanValue ALLOW_BLOCK_DAMAGE;
    public static final ModConfigSpec.BooleanValue ALLOW_FIRE;
    public static final ModConfigSpec.BooleanValue PVP;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("화염 주먹 (기본 키 R)").push("fire_fist");
        ENABLED = b.comment("false 면 아무도 화염 주먹을 쓸 수 없음 (명령어·API 강제 발사는 가능)").define("enabled", true);
        COOLDOWN_TICKS = b.comment("재사용 대기시간 (틱, 20틱 = 1초)").defineInRange("cooldownTicks", 120, 0, 72000);
        CHARGE_TICKS = b.comment("준비 동작 길이 (틱). 이 시간이 지나면 주먹이 발사됨").defineInRange("chargeTicks", 8, 0, 40);
        SPEED = b.comment("비행 속도 (블록/틱). 1.6 = 초당 32블록. 최대 3.9 (마인크래프트가 보낼 수 있는 속도 한계)").defineInRange("speed", 1.6, 0.2, 3.9);
        RANGE = b.comment("최대 사거리 (블록). 여기까지 아무것도 안 맞으면 공중에서 흩어짐").defineInRange("range", 28.0, 4.0, 128.0);
        HIT_RADIUS = b.comment("주먹 판정 반지름 (블록). 클수록 맞히기 쉬움").defineInRange("hitRadius", 0.9, 0.1, 3.0);
        DIRECT_DAMAGE = b.comment("직격 피해 (하트 반 칸 = 1)").defineInRange("directDamage", 12.0, 0.0, 200.0);
        SPLASH_DAMAGE = b.comment("폭발 중심 범위 피해 (멀수록 줄어듦)").defineInRange("splashDamage", 6.0, 0.0, 200.0);
        SPLASH_RADIUS = b.comment("폭발 범위 반지름 (블록)").defineInRange("splashRadius", 3.5, 0.0, 16.0);
        KNOCKBACK = b.comment("밀쳐내는 세기").defineInRange("knockback", 0.9, 0.0, 5.0);
        ALLOW_BLOCK_DAMAGE = b.comment("true 면 지형을 부숨 (기본 false)").define("allowBlockDamage", false);
        ALLOW_FIRE = b.comment("true 면 맞은 대상과 지형에 불을 붙임 (기본 false)").define("allowFire", false);
        PVP = b.comment("false 면 플레이어끼리는 피해 없음").define("pvp", true);
        b.pop();
        SPEC = b.build();
    }

    private SkillsConfig() {}
}
