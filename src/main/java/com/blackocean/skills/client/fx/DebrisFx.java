package com.blackocean.skills.client.fx;

import com.blackocean.skills.Element;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 지형 파괴 연출 (화면에만 보임 — 실제 블록은 그대로).
 * 맞은 자리 주변 땅의 실제 블록 모양·색으로 된 조각들이 튀어 오르고, 굴러 떨어지고, 사라진다.
 * 금 간 땅 무늬·흙먼지도 함께. (임시 뼈대 — 연출 담당이 채움)
 */
public final class DebrisFx {
    private DebrisFx() {}

    /**
     * 사방으로 터지는 파편.
     * @param ground 바닥 위 중심
     * @param radius 파편이 튀어나오는 땅의 반지름
     * @param count  조각 수 (설정·거리에 따라 줄어듦)
     * @param power  튀는 세기 (0.3 약함 ~ 1.5 궁극기)
     */
    public static void burst(Vec3 ground, float radius, int count, float power) {}

    /** 한쪽 방향(부채꼴·직선)으로 몰려 튀는 파편. dir = 수평 방향 */
    public static void directed(Vec3 ground, Vec3 dir, float range, float halfAngleDeg, int count, float power) {}

    /** 금 간 땅 + 패인 자국 무늬 (el 색으로 틈이 빛남, null = 빛 없음) */
    public static void crater(Vec3 ground, float radius, int lifeTicks, @Nullable Element el) {}

    static void tick() {}

    static void render(float pt, float time) {}

    static boolean isEmpty() { return true; }

    static void clear() {}
}
