package com.blackocean.skills;

import org.jetbrains.annotations.Nullable;

/**
 * 스킬 4종. 순서 = 기본 키 번호 (1, 2, 3, 4) = 신호에 실리는 번호.
 * 순서를 바꾸면 접속기와 서버 버전을 함께 올려야 한다.
 */
public enum Skill {
    FIRE_FIST("fire_fist"),
    FIRE_BULLETS("fire_bullets"),
    FIRE_PILLAR("fire_pillar"),
    FLAME_RING("flame_ring");

    public static final Skill[] VALUES = values();

    /** 설정·번역·아이콘 파일 이름에 쓰는 영문 이름 */
    public final String id;

    Skill(String id) {
        this.id = id;
    }

    @Nullable
    public static Skill byIndex(int i) {
        return i >= 0 && i < VALUES.length ? VALUES[i] : null;
    }

    @Nullable
    public static Skill byId(String id) {
        for (Skill s : VALUES) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    public boolean enabled() {
        return switch (this) {
            case FIRE_FIST -> SkillsConfig.ENABLED.get();
            case FIRE_BULLETS -> SkillsConfig.BULLETS_ENABLED.get();
            case FIRE_PILLAR -> SkillsConfig.PILLAR_ENABLED.get();
            case FLAME_RING -> SkillsConfig.RING_ENABLED.get();
        };
    }

    public int cooldownTicks() {
        return switch (this) {
            case FIRE_FIST -> SkillsConfig.COOLDOWN_TICKS.get();
            case FIRE_BULLETS -> SkillsConfig.BULLETS_COOLDOWN.get();
            case FIRE_PILLAR -> SkillsConfig.PILLAR_COOLDOWN.get();
            case FLAME_RING -> SkillsConfig.RING_COOLDOWN.get();
        };
    }

    public int chargeTicks() {
        return switch (this) {
            case FIRE_FIST -> SkillsConfig.CHARGE_TICKS.get();
            case FIRE_BULLETS -> SkillsConfig.BULLETS_CHARGE.get();
            case FIRE_PILLAR -> SkillsConfig.PILLAR_CHARGE.get();
            case FLAME_RING -> SkillsConfig.RING_CHARGE.get();
        };
    }

    public String translationKey() {
        return "skill.blackocean_skills." + id;
    }
}
