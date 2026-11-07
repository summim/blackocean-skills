package com.blackocean.skills.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 각자 PC 설정: config/blackocean_skills-client.toml */
public final class SkillsClientConfig {
    /** 연출 품질 (입자·파편 수 배율) */
    public enum FxQuality {
        LOW(0.5f, 0.35f),
        MEDIUM(0.75f, 0.65f),
        HIGH(1.0f, 1.0f);

        /** 불꽃·불씨·연기 입자 배율 */
        public final float particles;
        /** 지형 파편 조각 배율 */
        public final float debris;

        FxQuality(float particles, float debris) {
            this.particles = particles;
            this.debris = debris;
        }
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue PARTICLE_AMOUNT;
    public static final ModConfigSpec.BooleanValue AFTERIMAGES;
    public static final ModConfigSpec.BooleanValue CAMERA_SHAKE;
    public static final ModConfigSpec.BooleanValue COOLDOWN_HUD;
    public static final ModConfigSpec.BooleanValue BLOCK_HOTBAR_KEYS;
    public static final ModConfigSpec.BooleanValue HOLD_TO_AIM;
    /** 지형 파편 연출 (튀는 블록 조각·금 간 땅). 화면에만 보이고 실제 블록은 그대로. */
    public static final ModConfigSpec.BooleanValue DEBRIS;
    /** 연출 품질 low / medium / high */
    public static final ModConfigSpec.EnumValue<FxQuality> FX_QUALITY;
    /** 화면 흔들림 세기 배율 */
    public static final ModConfigSpec.DoubleValue SHAKE_STRENGTH;
    /** 궁극기 화면 연출 (위아래 검은 띠·속도선·배너) */
    public static final ModConfigSpec.BooleanValue ULT_CINEMATIC;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("fx");
        PARTICLE_AMOUNT = b.comment("불꽃·불씨·연기 양 (1.0 = 기본, 컴퓨터가 느리면 0.5)").defineInRange("particleAmount", 1.0, 0.0, 2.0);
        AFTERIMAGES = b.comment("화염 주먹 잔상 그리기").define("afterimages", true);
        CAMERA_SHAKE = b.comment("가까운 폭발에서 화면 흔들림").define("cameraShake", true);
        COOLDOWN_HUD = b.comment("핫바 오른쪽에 스킬 칸 5개(아이콘·키 번호·쿨타임, 5번 = 궁극기) 표시").define("cooldownHud", true);
        BLOCK_HOTBAR_KEYS = b.comment("스킬 키가 핫바 키(1~5)와 같을 때 스킬만 쓰고 핫바 칸은 안 바꿈. false 면 둘 다 동작")
                .define("skillKeysOverHotbar", true);
        HOLD_TO_AIM = b.comment("true: 스킬 키를 꾹 누르면 방향·사거리·범위가 보이고, 떼는 순간 발동 (톡 치면 바로 발동, 누른 채 우클릭하면 취소).",
                        "false: 누르는 순간 바로 발동 (조준 표시 없음)")
                .define("holdToAim", true);
        DEBRIS = b.comment("큰 폭발에서 땅의 블록 조각이 튀고 금 간 자국이 남는 연출 (화면에만 보임, 실제 블록은 안 부서짐)")
                .define("debris", true);
        FX_QUALITY = b.comment("연출 품질: LOW / MEDIUM / HIGH (입자·파편 수). 사람이 많아 느리면 MEDIUM 이나 LOW")
                .defineEnum("fxQuality", FxQuality.HIGH);
        SHAKE_STRENGTH = b.comment("화면 흔들림 세기 (1.0 = 기본, 0.5 = 약하게)").defineInRange("shakeStrength", 1.0, 0.0, 2.0);
        ULT_CINEMATIC = b.comment("궁극기 시전 때 영화 같은 화면 연출 (위아래 검은 띠·속도선·배너, 1.5초 이내)")
                .define("ultimateCinematic", true);
        b.pop();
        SPEC = b.build();
    }

    private SkillsClientConfig() {}

    /** 지금 품질 (설정을 아직 못 읽었으면 HIGH) */
    public static FxQuality quality() {
        try {
            return FX_QUALITY.get();
        } catch (IllegalStateException e) {
            return FxQuality.HIGH;
        }
    }
}
