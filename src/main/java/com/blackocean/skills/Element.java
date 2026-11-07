package com.blackocean.skills;

import org.jetbrains.annotations.Nullable;

/** 능력 계통 4종. 플레이어마다 하나를 가진다 (열매를 먹거나 /skills element 명령어). */
public enum Element {
    FIRE("fire", 0xFF8A3A),
    LIGHTNING("lightning", 0x7FD8FF),
    WIND("wind", 0x8FF0C0),
    WATER("water", 0x4FA8FF);

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
