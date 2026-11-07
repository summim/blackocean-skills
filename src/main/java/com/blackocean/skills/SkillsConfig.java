package com.blackocean.skills;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 서버 설정: config/blackocean_skills-common.toml
 * [general] 전체 규칙 + 스킬 20개가 같은 모양의 칸으로 들어 있다 (시간은 모두 '초').
 * 방송 중에 바꿔도 서버가 자동으로 다시 읽는다 (사거리·범위는 접속자 화면에도 바로 반영).
 */
public final class SkillsConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue PVP;
    public static final ModConfigSpec.BooleanValue ALLOW_FIRE;
    public static final ModConfigSpec.BooleanValue ALLOW_BLOCK_DAMAGE;
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_ELEMENT;
    public static final ModConfigSpec.DoubleValue DAMAGE_MULT;
    public static final ModConfigSpec.DoubleValue COOLDOWN_MULT;
    public static final ModConfigSpec.BooleanValue FRUIT_SWAP;
    public static final ModConfigSpec.BooleanValue ULT_BANNER;

    /** 스킬 한 개의 설정 칸 */
    public static final class Entry {
        public final ModConfigSpec.BooleanValue enabled;
        public final ModConfigSpec.DoubleValue cooldown, charge, damage, damage2, range, radius, duration, speed;

        Entry(ModConfigSpec.Builder b, Skill s) {
            b.comment((s.ultimate() ? "[궁극기] " : "") + "키 " + (s.slot + 1) + " · " + s.element.id + " · " + s.notes).push(s.id);
            enabled = b.comment("false 면 아무도 못 씀 (명령어·API 강제 발동은 가능)").define("enabled", true);
            cooldown = b.comment("재사용 대기시간 (초)").defineInRange("cooldown", s.dCooldown, 0.0, 3600.0);
            charge = b.comment("모으는 시간 (초). 누른 뒤 이만큼 지나야 발동").defineInRange("charge", s.dCharge, 0.0, 5.0);
            damage = b.defineInRange("damage", s.dDamage, 0.0, 500.0);
            damage2 = b.defineInRange("damage2", s.dDamage2, 0.0, 500.0);
            range = b.defineInRange("range", s.dRange, 0.0, 96.0);
            radius = b.defineInRange("radius", s.dRadius, 0.0, 32.0);
            duration = b.comment("(초)").defineInRange("duration", s.dDuration, 0.0, 30.0);
            speed = b.defineInRange("speed", s.dSpeed, 0.0, 3.9);
            b.pop();
        }
    }

    public static final Entry[] ENTRIES = new Entry[Skill.VALUES.length];

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("모든 스킬 공통").push("general");
        PVP = b.comment("false 면 플레이어끼리는 피해 없음").define("pvp", true);
        ALLOW_FIRE = b.comment("true 면 화염 스킬이 지형·대상에 불을 붙임").define("allowFire", false);
        ALLOW_BLOCK_DAMAGE = b.comment("true 면 폭발 스킬이 지형을 부숨").define("allowBlockDamage", false);
        DEFAULT_ELEMENT = b.comment("능력을 정하지 않은 플레이어의 능력: fire, lightning, wind, water, none(스킬 없음)")
                .define("defaultElement", "fire", o -> o instanceof String s && (s.equals("none") || Element.byId(s) != null));
        DAMAGE_MULT = b.comment("모든 스킬 피해 배율").defineInRange("damageMultiplier", 1.0, 0.0, 10.0);
        COOLDOWN_MULT = b.comment("모든 스킬 쿨타임 배율").defineInRange("cooldownMultiplier", 1.0, 0.0, 10.0);
        FRUIT_SWAP = b.comment("true 면 이미 능력이 있어도 다른 열매를 먹으면 능력이 바뀜. false 면 먹어도 안 바뀜")
                .define("fruitSwap", true);
        ULT_BANNER = b.comment("궁극기를 쓰면 근처 사람 화면에 큰 제목 표시").define("ultimateBanner", true);
        b.pop();
        for (Skill s : Skill.VALUES) ENTRIES[s.ordinal()] = new Entry(b, s);
        SPEC = b.build();
    }

    private SkillsConfig() {}
}
