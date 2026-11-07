package com.blackocean.skills.client;

import com.blackocean.skills.Element;
import com.blackocean.skills.Skill;
import org.jetbrains.annotations.Nullable;

/** 내 능력 (서버가 알려 줌). null = 능력 없음 / 아직 모름 */
public final class ClientElement {
    @Nullable
    private static Element element;

    private ClientElement() {}

    @Nullable
    public static Element get() {
        return element;
    }

    static void set(@Nullable Element e) {
        element = e;
    }

    /** 내 능력의 slot(0~4)번 스킬. 능력이 없으면 null */
    @Nullable
    public static Skill skill(int slot) {
        return element == null || slot < 0 || slot >= Skill.SLOTS ? null : Skill.of(element, slot);
    }
}
