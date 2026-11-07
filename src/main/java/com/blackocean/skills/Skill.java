package com.blackocean.skills;

import org.jetbrains.annotations.Nullable;

/**
 * 스킬 30종 = 능력 6종 × 키 5개 (5번 = 궁극기).
 * 순서는 신호 번호이므로 바꾸면 신호 버전(SkillsNetwork)을 올려야 한다.
 * 숫자 기본값은 SkillsConfig 가 설정 파일 기본값으로 쓴다 (서버에서 바꿀 수 있음).
 */
public enum Skill {
    //            능력               키  이름                   조준                 모양                  판정  쿨  모음  피해  피해2  사거리 반경 지속  속도
    FIRE_FIST(Element.FIRE, 0, "fire_fist", Aim.PROJECTILE, Shape.FIST, 0.9f, 6, 0.4, 12, 6, 28, 3.5, 0, 1.6,
            "damage=직격, damage2=폭발 범위 피해, radius=폭발 반지름, speed=비행 속도(블록/틱, 최대 3.9)"),
    FIRE_BULLETS(Element.FIRE, 1, "fire_bullets", Aim.PROJECTILE, Shape.BULLET, 0.45f, 4, 0.2, 4, 2, 34, 1.6, 0, 2.6,
            "5발 연사. damage=한 발 직격, damage2=한 발 범위 피해, radius=한 발 폭발 반지름"),
    FIRE_PILLAR(Element.FIRE, 2, "fire_pillar", Aim.GROUND, Shape.NONE, 0, 10, 0.7, 10, 2, 26, 2.2, 1.3, 0,
            "damage=솟구칠 때, damage2=기둥 안에서 0.5초마다, radius=기둥 반지름, duration=기둥 유지(초)"),
    FLAME_RING(Element.FIRE, 3, "flame_ring", Aim.SELF, Shape.NONE, 0, 13, 0.8, 9, 0, 0, 7, 0, 1.6,
            "damage=중심 피해(가장자리 절반), radius=불의 파도 반지름, speed=밀쳐내기"),
    FIRE_SUN(Element.FIRE, 4, "fire_sun", Aim.PROJECTILE, Shape.SUN, 2.4f, 60, 1.5, 26, 3, 40, 9, 4, 0.9,
            "[궁극기] 머리 위에 태양을 만들어 던짐. damage=폭발(가장자리 절반), damage2=불바다 0.5초마다, radius=폭발 반지름, duration=불바다 유지(초)"),

    LIGHTNING_BOLT(Element.LIGHTNING, 0, "lightning_bolt", Aim.LINE, Shape.NONE, 0.6f, 3.5, 0.25, 8, 0, 32, 6, 0, 3,
            "즉시 번개 + 연쇄. damage=첫 대상(다음 대상마다 75%), radius=연쇄가 튀는 거리, speed=추가 연쇄 수"),
    LIGHTNING_BLINK(Element.LIGHTNING, 1, "lightning_blink", Aim.BLINK, Shape.NONE, 0, 7, 0.1, 6, 0, 12, 2.5, 0, 0,
            "조준 방향으로 순간이동. damage=출발·도착 지점과 지나간 길의 적, range=최대 거리, radius=번개 범위"),
    LIGHTNING_STRIKE(Element.LIGHTNING, 2, "lightning_strike", Aim.GROUND, Shape.NONE, 0, 9, 0.6, 9, 0, 28, 3, 0.6, 3,
            "조준한 곳에 낙뢰 연속. damage=한 번, radius=범위, duration=모든 낙뢰가 떨어지는 시간(초), speed=낙뢰 수"),
    LIGHTNING_DISCHARGE(Element.LIGHTNING, 3, "lightning_discharge", Aim.SELF, Shape.NONE, 0, 12, 0.5, 7, 0, 0, 7, 1.5, 0,
            "주변 모든 적에게 번개. damage=피해, radius=범위, duration=감전(느려짐) 시간(초)"),
    LIGHTNING_STORM(Element.LIGHTNING, 4, "lightning_storm", Aim.GROUND, Shape.NONE, 0, 70, 1.2, 24, 7, 30, 11, 3.5, 0,
            "[궁극기] 뇌운을 불러 낙뢰를 퍼붓고 마지막에 거대한 번개. damage=마지막 번개, damage2=낙뢰 한 번, radius=폭풍 반지름, duration=폭풍 시간(초)"),

    WIND_BLADE(Element.WIND, 0, "wind_blade", Aim.PROJECTILE, Shape.BLADE, 1.4f, 4, 0.25, 8, 0, 30, 0, 0, 2.4,
            "적을 꿰뚫고 날아가는 바람 칼날. damage=맞은 적마다, speed=비행 속도"),
    WIND_DASH(Element.WIND, 1, "wind_dash", Aim.DASH, Shape.NONE, 0, 6, 0.1, 6, 0, 12, 1.8, 0.35, 0,
            "바라보는 방향으로 돌진. damage=부딪힌 적, range=돌진 거리, radius=부딪힘 범위, duration=돌진 시간(초)"),
    WIND_WHIRLWIND(Element.WIND, 2, "wind_whirlwind", Aim.GROUND, Shape.NONE, 0, 11, 0.5, 5, 2.5, 26, 3, 3, 0,
            "회오리로 빨아들여 띄움. damage=끝날 때 날려 보내며, damage2=0.5초마다, radius=회오리 반지름, duration=유지(초)"),
    WIND_BURST(Element.WIND, 3, "wind_burst", Aim.SELF, Shape.NONE, 0, 12, 0.4, 6, 0, 0, 7, 0, 2.4,
            "강한 돌풍으로 날려 보내고 주변의 적 투사체를 없앰. damage=피해, radius=범위, speed=밀쳐내기"),
    WIND_TEMPEST(Element.WIND, 4, "wind_tempest", Aim.GROUND, Shape.NONE, 0, 70, 1.2, 14, 3, 32, 7, 5, 0,
            "[궁극기] 거대한 폭풍 회오리. damage=끝날 때, damage2=0.5초마다, radius=회오리 반지름, duration=유지(초)"),

    WATER_BULLET(Element.WATER, 0, "water_bullet", Aim.PROJECTILE, Shape.ORB, 0.6f, 3.5, 0.25, 7, 4, 30, 2.5, 2, 1.8,
            "물 구슬. damage=직격, damage2=물보라 범위 피해, radius=물보라 반지름, duration=느려짐(초)"),
    WATER_JET(Element.WATER, 1, "water_jet", Aim.LINE, Shape.NONE, 0.9f, 9, 0.2, 2.5, 0, 18, 0.9, 1.6, 0.35,
            "조준하는 방향으로 계속 물줄기. damage=0.25초마다, range=길이, radius=굵기, duration=분사 시간(초), speed=밀어내기"),
    WATER_PRISON(Element.WATER, 2, "water_prison", Aim.GROUND, Shape.NONE, 0, 12, 0.5, 6, 1.5, 26, 3.5, 3, 0,
            "물의 감옥에 가둠(끌어당김·느려짐). damage=터질 때, damage2=0.5초마다, radius=감옥 반지름, duration=유지(초)"),
    WATER_WAVE(Element.WATER, 3, "water_wave", Aim.WAVE, Shape.NONE, 0, 11, 0.4, 8, 0, 16, 4, 0, 0.9,
            "앞으로 밀려가는 해일. damage=피해, range=파도가 가는 거리, radius=파도 폭의 절반, speed=파도 속도(블록/틱)"),
    WATER_DRAGON(Element.WATER, 4, "water_dragon", Aim.GROUND, Shape.NONE, 0, 70, 1.2, 22, 2, 32, 7, 3, 0,
            "[궁극기] 물의 용이 솟아올라 내리꽂힘. damage=충돌(가장자리 절반), damage2=소용돌이 0.5초마다, radius=충돌 반지름, duration=소용돌이 유지(초)"),

    ICE_SPEAR(Element.ICE, 0, "ice_spear", Aim.PROJECTILE, Shape.SPEAR, 0.7f, 4, 0.3, 9, 3, 32, 2.5, 1.5, 2.3,
            "얼음 창. damage=직격, damage2=부서지는 파편 범위 피해, radius=파편 반지름, duration=얼림(초)"),
    ICE_SHARDS(Element.ICE, 1, "ice_shards", Aim.CONE, Shape.SHARD, 0.5f, 6, 0.25, 3, 0, 22, 35, 1.0, 2.4,
            "부채꼴로 얼음 파편 7개. damage=파편 하나, range=사거리, radius=부채꼴 반각(도), duration=느려짐(초)"),
    ICE_SPIKES(Element.ICE, 2, "ice_spikes", Aim.WAVE, Shape.NONE, 0, 10, 0.5, 9, 0, 18, 1.6, 1.2, 1.2,
            "땅을 따라 앞으로 솟는 얼음 가시. damage=피해(띄움), range=거리, radius=폭의 절반, duration=얼림(초), speed=나아가는 속도(블록/틱)"),
    FROST_NOVA(Element.ICE, 3, "frost_nova", Aim.SELF, Shape.NONE, 0, 13, 0.5, 6, 0, 0, 6.5, 2.0, 0,
            "주변을 한순간에 얼림. damage=피해, radius=범위, duration=얼림(초)"),
    ICE_AGE(Element.ICE, 4, "ice_age", Aim.GROUND, Shape.NONE, 0, 70, 1.4, 22, 2, 30, 8, 3, 0,
            "[궁극기] 하늘에서 거대한 빙산이 떨어져 일대를 얼리고, 끝에 산산조각. damage=충돌·마지막 폭발(가장자리 절반), damage2=얼음 지대 0.5초마다, radius=반지름, duration=얼음 지대 유지(초)"),

    THUNDER_HAMMER(Element.THUNDER, 0, "thunder_hammer", Aim.PROJECTILE, Shape.HAMMER, 1.0f, 5, 0.35, 9, 5, 28, 3.5, 0.8, 1.5,
            "회전하며 날아가는 뇌신의 망치, 맞으면 천둥 폭발. damage=직격, damage2=폭발 범위, radius=폭발 반지름, duration=기절(초)"),
    THUNDER_LEAP(Element.THUNDER, 1, "thunder_leap", Aim.GROUND, Shape.NONE, 0, 8, 0.15, 8, 0, 16, 3.5, 0.7, 0,
            "조준한 곳으로 뛰어올라 내려찍음. damage=착지 피해(띄움), range=최대 거리, radius=착지 범위, duration=점프 시간(초)"),
    THUNDERCLAP(Element.THUNDER, 2, "thunderclap", Aim.CONE, Shape.NONE, 0, 9, 0.45, 10, 0, 10, 45, 1.0, 1.4,
            "앞쪽 부채꼴로 땅을 내려쳐 충격파. damage=피해, range=거리, radius=부채꼴 반각(도), duration=기절(초), speed=밀쳐내기"),
    THUNDER_ROAR(Element.THUNDER, 3, "thunder_roar", Aim.SELF, Shape.NONE, 0, 14, 0.4, 7, 0, 0, 8, 1.5, 0,
            "천둥의 포효로 주변의 적을 끌어당긴 뒤 터뜨림. damage=폭발, radius=범위, duration=기절(초)"),
    THUNDER_JUDGMENT(Element.THUNDER, 4, "thunder_judgment", Aim.GROUND, Shape.NONE, 0, 75, 0.6, 26, 8, 22, 10, 1.5, 0,
            "[궁극기] 하늘로 솟구쳐 거대한 뇌신의 망치로 내려찍음 + 여진 3번. damage=내려찍기(가장자리 절반), damage2=여진 한 번, radius=반지름, duration=공중에 떠 있는 시간(초)");

    /** 조준 표시 모양 */
    public enum Aim { PROJECTILE, LINE, GROUND, SELF, DASH, BLINK, WAVE, CONE }

    /** 투사체 모양 (투사체 스킬만) */
    public enum Shape { NONE, FIST, BULLET, SUN, BLADE, ORB, SPEAR, SHARD, HAMMER }

    public static final Skill[] VALUES = values();
    public static final int SLOTS = 5;

    public final Element element;
    /** 0~4 (키 1~5) */
    public final int slot;
    public final String id;
    public final Aim aim;
    public final Shape shape;
    /** 투사체 판정 반지름 (고정) */
    public final float hit;
    final double dCooldown, dCharge, dDamage, dDamage2, dRange, dRadius, dDuration, dSpeed;
    final String notes;

    Skill(Element element, int slot, String id, Aim aim, Shape shape, float hit, double cooldown, double charge,
          double damage, double damage2, double range, double radius, double duration, double speed, String notes) {
        this.element = element;
        this.slot = slot;
        this.id = id;
        this.aim = aim;
        this.shape = shape;
        this.hit = hit;
        this.dCooldown = cooldown;
        this.dCharge = charge;
        this.dDamage = damage;
        this.dDamage2 = damage2;
        this.dRange = range;
        this.dRadius = radius;
        this.dDuration = duration;
        this.dSpeed = speed;
        this.notes = notes;
    }

    @Nullable
    public static Skill byIndex(int i) {
        return i >= 0 && i < VALUES.length ? VALUES[i] : null;
    }

    @Nullable
    public static Skill byId(String id) {
        for (Skill s : VALUES) if (s.id.equals(id)) return s;
        return null;
    }

    /** 이 능력의 몇 번째 키 스킬 */
    public static Skill of(Element e, int slot) {
        for (Skill s : VALUES) if (s.element == e && s.slot == slot) return s;
        throw new IllegalArgumentException(e + " slot " + slot);
    }

    public boolean ultimate() {
        return slot == SLOTS - 1;
    }

    public String translationKey() {
        return "skill.blackocean_skills." + id;
    }

    // ---- 서버 설정값 (SkillsConfig) ----
    private SkillsConfig.Entry cfg() {
        return SkillsConfig.ENTRIES[ordinal()];
    }

    public boolean enabled() {
        return cfg().enabled.get();
    }

    public int cooldownTicks() {
        return (int) Math.round(cfg().cooldown.get() * 20.0 * SkillsConfig.COOLDOWN_MULT.get());
    }

    public int chargeTicks() {
        return (int) Math.round(cfg().charge.get() * 20.0);
    }

    public float damage() {
        return (float) (cfg().damage.get() * SkillsConfig.DAMAGE_MULT.get());
    }

    public float damage2() {
        return (float) (cfg().damage2.get() * SkillsConfig.DAMAGE_MULT.get());
    }

    public double range() {
        return cfg().range.get();
    }

    public double radius() {
        return cfg().radius.get();
    }

    public int durationTicks() {
        return (int) Math.round(cfg().duration.get() * 20.0);
    }

    public double speed() {
        return cfg().speed.get();
    }
}
