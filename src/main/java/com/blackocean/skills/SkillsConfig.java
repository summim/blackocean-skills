package com.blackocean.skills;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 서버 설정: config/blackocean_skills-common.toml
 * 방송 중 밸런스를 바꿀 때 코드를 다시 빌드할 필요 없이 이 파일 숫자만 바꾸면 된다.
 * [fire_fist] 칸의 이름은 1.1 과 같아서, 이미 바꿔 둔 서버 설정이 그대로 유지된다.
 */
public final class SkillsConfig {
    public static final ModConfigSpec SPEC;

    // ---- 1. 화염 주먹 (+ 모든 스킬 공통: pvp, allowFire, allowBlockDamage) ----
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

    // ---- 2. 불꽃 탄환 ----
    public static final ModConfigSpec.BooleanValue BULLETS_ENABLED;
    public static final ModConfigSpec.IntValue BULLETS_COOLDOWN;
    public static final ModConfigSpec.IntValue BULLETS_CHARGE;
    public static final ModConfigSpec.IntValue BULLETS_COUNT;
    public static final ModConfigSpec.IntValue BULLETS_INTERVAL;
    public static final ModConfigSpec.DoubleValue BULLETS_SPEED;
    public static final ModConfigSpec.DoubleValue BULLETS_RANGE;
    public static final ModConfigSpec.DoubleValue BULLETS_DAMAGE;
    public static final ModConfigSpec.DoubleValue BULLETS_SPLASH_DAMAGE;
    public static final ModConfigSpec.DoubleValue BULLETS_SPLASH_RADIUS;
    public static final ModConfigSpec.DoubleValue BULLETS_SPREAD;

    // ---- 3. 불기둥 ----
    public static final ModConfigSpec.BooleanValue PILLAR_ENABLED;
    public static final ModConfigSpec.IntValue PILLAR_COOLDOWN;
    public static final ModConfigSpec.IntValue PILLAR_CHARGE;
    public static final ModConfigSpec.DoubleValue PILLAR_RANGE;
    public static final ModConfigSpec.DoubleValue PILLAR_RADIUS;
    public static final ModConfigSpec.DoubleValue PILLAR_HEIGHT;
    public static final ModConfigSpec.DoubleValue PILLAR_DAMAGE;
    public static final ModConfigSpec.DoubleValue PILLAR_BURN_DAMAGE;
    public static final ModConfigSpec.IntValue PILLAR_DURATION;
    public static final ModConfigSpec.DoubleValue PILLAR_LAUNCH;

    // ---- 4. 화염 고리 ----
    public static final ModConfigSpec.BooleanValue RING_ENABLED;
    public static final ModConfigSpec.IntValue RING_COOLDOWN;
    public static final ModConfigSpec.IntValue RING_CHARGE;
    public static final ModConfigSpec.DoubleValue RING_RADIUS;
    public static final ModConfigSpec.DoubleValue RING_DAMAGE;
    public static final ModConfigSpec.DoubleValue RING_KNOCKBACK;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("1. 화염 주먹 (기본 키 1). 맨 아래 pvp·allowFire·allowBlockDamage 는 스킬 4개 모두에 적용").push("fire_fist");
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
        ALLOW_BLOCK_DAMAGE = b.comment("[모든 스킬] true 면 지형을 부숨 (기본 false)").define("allowBlockDamage", false);
        ALLOW_FIRE = b.comment("[모든 스킬] true 면 맞은 대상과 지형에 불을 붙임 (기본 false)").define("allowFire", false);
        PVP = b.comment("[모든 스킬] false 면 플레이어끼리는 피해 없음").define("pvp", true);
        b.pop();

        b.comment("2. 불꽃 탄환 (기본 키 2) — 양손에서 작은 화염탄을 연달아 쏨").push("fire_bullets");
        BULLETS_ENABLED = b.define("enabled", true);
        BULLETS_COOLDOWN = b.comment("재사용 대기시간 (틱)").defineInRange("cooldownTicks", 80, 0, 72000);
        BULLETS_CHARGE = b.comment("준비 동작 길이 (틱)").defineInRange("chargeTicks", 4, 0, 40);
        BULLETS_COUNT = b.comment("한 번에 쏘는 탄환 수").defineInRange("count", 5, 1, 20);
        BULLETS_INTERVAL = b.comment("탄환 사이 간격 (틱)").defineInRange("intervalTicks", 2, 1, 20);
        BULLETS_SPEED = b.comment("비행 속도 (블록/틱, 최대 3.9)").defineInRange("speed", 2.6, 0.2, 3.9);
        BULLETS_RANGE = b.comment("최대 사거리 (블록)").defineInRange("range", 34.0, 4.0, 128.0);
        BULLETS_DAMAGE = b.comment("한 발 직격 피해").defineInRange("damage", 4.0, 0.0, 200.0);
        BULLETS_SPLASH_DAMAGE = b.comment("한 발 폭발 범위 피해").defineInRange("splashDamage", 2.0, 0.0, 200.0);
        BULLETS_SPLASH_RADIUS = b.comment("한 발 폭발 범위 (블록)").defineInRange("splashRadius", 1.6, 0.0, 8.0);
        BULLETS_SPREAD = b.comment("탄 퍼짐 (도)").defineInRange("spreadDegrees", 2.5, 0.0, 30.0);
        b.pop();

        b.comment("3. 불기둥 (기본 키 3) — 조준한 바닥에 경고 원이 생긴 뒤 불기둥이 솟아오름").push("fire_pillar");
        PILLAR_ENABLED = b.define("enabled", true);
        PILLAR_COOLDOWN = b.comment("재사용 대기시간 (틱)").defineInRange("cooldownTicks", 200, 0, 72000);
        PILLAR_CHARGE = b.comment("경고 원이 보이는 시간 (틱). 이 시간 동안 피할 수 있음").defineInRange("chargeTicks", 14, 0, 60);
        PILLAR_RANGE = b.comment("조준 가능한 최대 거리 (블록)").defineInRange("range", 26.0, 4.0, 64.0);
        PILLAR_RADIUS = b.comment("불기둥 반지름 (블록)").defineInRange("radius", 2.2, 0.5, 8.0);
        PILLAR_HEIGHT = b.comment("불기둥 높이 (블록)").defineInRange("height", 9.0, 2.0, 32.0);
        PILLAR_DAMAGE = b.comment("솟구칠 때 피해").defineInRange("damage", 10.0, 0.0, 200.0);
        PILLAR_BURN_DAMAGE = b.comment("기둥 안에 계속 있으면 0.5초마다 받는 피해").defineInRange("burnDamage", 2.0, 0.0, 200.0);
        PILLAR_DURATION = b.comment("기둥이 남아 있는 시간 (틱)").defineInRange("durationTicks", 26, 1, 200);
        PILLAR_LAUNCH = b.comment("위로 띄우는 세기").defineInRange("launch", 1.0, 0.0, 4.0);
        b.pop();

        b.comment("4. 화염 고리 (기본 키 4) — 몸 주위로 불 고리가 돌다가 사방으로 불의 파도가 터짐").push("flame_ring");
        RING_ENABLED = b.define("enabled", true);
        RING_COOLDOWN = b.comment("재사용 대기시간 (틱)").defineInRange("cooldownTicks", 260, 0, 72000);
        RING_CHARGE = b.comment("고리가 도는 시간 (틱)").defineInRange("chargeTicks", 16, 0, 60);
        RING_RADIUS = b.comment("불의 파도가 닿는 반지름 (블록)").defineInRange("radius", 7.0, 1.0, 24.0);
        RING_DAMAGE = b.comment("중심 피해 (멀수록 줄어듦, 가장자리에서 절반)").defineInRange("damage", 9.0, 0.0, 200.0);
        RING_KNOCKBACK = b.comment("바깥으로 밀쳐내는 세기").defineInRange("knockback", 1.6, 0.0, 6.0);
        b.pop();

        SPEC = b.build();
    }

    private SkillsConfig() {}
}
