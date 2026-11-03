package com.blackocean.skills;

import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/**
 * Sable 배(서브레벨) 좌표 보정.
 * <p>
 * Sable 은 배의 블록을 실제로는 아주 먼 "플롯" 좌표(약 2천만 칸 밖)에 보관한다.
 * 그래서 {@code level.clip} 이 배 블록에 맞으면 맞은 위치가 플롯 좌표로 돌아온다.
 * 그 좌표를 그대로 쓰면 조준선 길이가 수천만 칸이 되거나(렌더링 폭주), 폭발이 엉뚱한 곳에서 터진다.
 * 여기서 맞은 위치를 실제 세계 좌표로 되돌린다. Sable 이 없으면 아무것도 바꾸지 않는다.
 */
public final class SubLevels {
    private static final Object COMPANION;
    private static final Method PROJECT;

    static {
        Object inst = null;
        Method m = null;
        try {
            Class<?> c = Class.forName("dev.ryanhcode.sable.companion.SableCompanion", true, SubLevels.class.getClassLoader());
            inst = c.getField("INSTANCE").get(null);
            m = c.getMethod("projectOutOfSubLevel", Level.class, Vec3.class);
        } catch (Throwable ignored) {
            // Sable 없음 (개발 환경 등) → 보정 없이 동작
        }
        COMPANION = inst;
        PROJECT = m;
    }

    private SubLevels() {}

    /** 배 플롯 좌표면 실제 세계 좌표로 바꾼다. 일반 좌표는 그대로. */
    public static Vec3 toWorld(Level level, Vec3 v) {
        if (PROJECT == null) return v;
        try {
            Object r = PROJECT.invoke(COMPANION, level, v);
            return r instanceof Vec3 w ? w : v;
        } catch (Throwable t) {
            return v;
        }
    }

    /**
     * {@code level.clip} 과 같지만, 맞은 위치와 맞은 면 방향을 실제 세계 기준으로 돌려준다.
     * (blockPos 는 원래 값 그대로 — 블록 조회용)
     */
    public static BlockHitResult clip(Level level, ClipContext ctx) {
        BlockHitResult hit = level.clip(ctx);
        if (PROJECT == null || hit.getType() == HitResult.Type.MISS) return hit;
        Vec3 loc = hit.getLocation();
        Vec3 world = toWorld(level, loc);
        if (world == loc || world.distanceToSqr(loc) < 1.0E-6) return hit;

        // 면 방향도 배의 회전에 맞춰 돌린다
        Direction face = hit.getDirection();
        Vec3 n = Vec3.atLowerCornerOf(face.getNormal());
        Vec3 wn = toWorld(level, loc.add(n)).subtract(world);
        Direction worldFace = wn.lengthSqr() > 1.0E-6
                ? Direction.getNearest(wn.x, wn.y, wn.z)
                : face;
        return new BlockHitResult(world, worldFace, hit.getBlockPos(), hit.isInside());
    }
}
