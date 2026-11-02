package com.blackocean.skills.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 각자 PC 설정: config/blackocean_skills-client.toml */
public final class SkillsClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue PARTICLE_AMOUNT;
    public static final ModConfigSpec.BooleanValue AFTERIMAGES;
    public static final ModConfigSpec.BooleanValue CAMERA_SHAKE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("fx");
        PARTICLE_AMOUNT = b.comment("불꽃·불씨·연기 양 (1.0 = 기본, 컴퓨터가 느리면 0.5)").defineInRange("particleAmount", 1.0, 0.0, 2.0);
        AFTERIMAGES = b.comment("화염 주먹 잔상 그리기").define("afterimages", true);
        CAMERA_SHAKE = b.comment("가까운 폭발에서 화면 흔들림").define("cameraShake", true);
        b.pop();
        SPEC = b.build();
    }

    private SkillsClientConfig() {}
}
