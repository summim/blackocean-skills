package com.blackocean.skills;

import org.jetbrains.annotations.Nullable;

/** 능력 계통 6종. 플레이어마다 하나를 가진다 (열매를 먹거나 /skills element 명령어). */
public enum Element {
    FIRE("fire", 0xFF8A3A),
    LIGHTNING("lightning", 0x7FD8FF),
    WIND("wind", 0x8FF0C0),
    WATER("water", 0x4FA8FF),
    /** 얼음: 얼려서 묶는 제어형 */
    ICE("ice", 0xA8E8FF),
    /** 천둥: 묵직한 망치·충격파의 근접 파괴형 (번개 = 빠르고 정확, 천둥 = 무겁고 넓음) */
    THUNDER("thunder", 0xFFC83A);

    public static final Element[] VALUES = values();

    public final String id;
    /** 스킬 칸 테두리·배너 글자 색 (RGB) */
    public final int color;

    Element(String id, int color) {
        this.id = id;
        this.color = color;
    }

    @Nullable
    public static Element byId(String id) {
        for (Element e : VALUES) if (e.id.equalsIgnoreCase(id)) return e;
        return null;
    }

    @Nullable
    public static Element byIndex(int i) {
        return i >= 0 && i < VALUES.length ? VALUES[i] : null;
    }

    public String translationKey() {
        return "element.blackocean_skills." + id;
    }
}
