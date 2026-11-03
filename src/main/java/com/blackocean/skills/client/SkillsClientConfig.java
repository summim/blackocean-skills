package com.blackocean.skills.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 각자 PC 설정: config/blackocean_skills-client.toml */
public final class SkillsClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue PARTICLE_AMOUNT;
    public static final ModConfigSpec.BooleanValue AFTERIMAGES;
    public static final ModConfigSpec.BooleanValue CAMERA_SHAKE;
    public static final ModConfigSpec.BooleanValue COOLDOWN_HUD;
    public static final ModConfigSpec.BooleanValue BLOCK_HOTBAR_KEYS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("fx");
        PARTICLE_AMOUNT = b.comment("불꽃·불씨·연기 양 (1.0 = 기본, 컴퓨터가 느리면 0.5)").defineInRange("particleAmount", 1.0, 0.0, 2.0);
        AFTERIMAGES = b.comment("화염 주먹 잔상 그리기").define("afterimages", true);
        CAMERA_SHAKE = b.comment("가까운 폭발에서 화면 흔들림").define("cameraShake", true);
        COOLDOWN_HUD = b.comment("핫바 오른쪽에 스킬 칸 4개(아이콘·키 번호·쿨타임) 표시").define("cooldownHud", true);
        BLOCK_HOTBAR_KEYS = b.comment("스킬 키가 핫바 키(1~4)와 같을 때 스킬만 쓰고 핫바 칸은 안 바꿈. false 면 둘 다 동작")
                .define("skillKeysOverHotbar", true);
        b.pop();
        SPEC = b.build();
    }

    private SkillsClientConfig() {}
}
