package com.blackocean.skills;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 서버(판정)와 클라이언트(조준 표시)가 똑같이 쓰는 조준 계산.
 * 같은 함수를 쓰므로 조준 표시가 실제 판정과 어긋나지 않는다. 블록 검사는 모두 SubLevels.clip (배 좌표 보정).
 */
public final class SkillAim {
    private static final Vec3 UP = new Vec3(0, 1, 0);

    private SkillAim() {}

    /** 손 위치 (side: + 오른쪽, - 왼쪽, lift: 위로). 벽 앞에서 쓰면 벽 앞으로 당긴다. */
    public static Vec3 handPos(Level level, Player p, Vec3 eye, Vec3 look, double side, double lift) {
        Vec3 right = look.cross(UP);
        right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 start = eye.add(look.scale(lift > 1.0 ? 0.6 : 1.6)).add(right.scale(side)).add(0, lift > 1.0 ? lift : -0.3, 0);
        BlockHitResult wall = SubLevels.clip(level, new ClipContext(eye, start, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (wall.getType() != HitResult.Type.MISS) {
            start = wall.getLocation().subtract(start.subtract(eye).normalize().scale(0.15));
        }
        return start;
    }

    /** 조준점(화면 가운데)이 가리키는 곳 (블록 또는 생물, 없으면 사거리 끝) */
    public static Vec3 aimPoint(Level level, Player p, Vec3 eye, Vec3 look, double range) {
        Vec3 far = eye.add(look.scale(range));
        BlockHitResult bh = SubLevels.clip(level, new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 target = bh.getType() == HitResult.Type.MISS ? far : bh.getLocation();
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(level, p, eye, target,
                p.getBoundingBox().expandTowards(target.subtract(eye)).inflate(1.0),
                e -> e.isPickable() && !e.isSpectator() && !SkillTargets.isSameRide(p, e));
        return eh != null ? eh.getLocation() : target;
    }

    /** 손에서 나가지만 조준점으로 날아가도록 방향을 맞춘다. 조준점이 너무 가깝거나 뒤면 바라보는 방향. */
    public static Vec3 aimDirection(Level level, Player p, Vec3 eye, Vec3 look, Vec3 start, double range) {
        Vec3 d = aimPoint(level, p, eye, look, range).subtract(start);
        if (d.lengthSqr() < 4.0 || d.normalize().dot(look) < 0.5) return look;
        return d.normalize();
    }

    /** 바닥 조준: 조준점이 가리키는 바닥. 허공이면 그 아래 10칸까지 바닥을 찾는다. 없으면 null. */
    @Nullable
    public static Vec3 groundTarget(Level level, Player p, Vec3 eye, Vec3 look, double range) {
        Vec3 far = eye.add(look.scale(range));
        BlockHitResult bh = SubLevels.clip(level, new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        Vec3 from;
        if (bh.getType() != HitResult.Type.MISS) {
            if (bh.getDirection() == Direction.UP) return bh.getLocation();
            from = bh.getLocation().add(Vec3.atLowerCornerOf(bh.getDirection().getNormal()).scale(0.3));
        } else {
            from = far;
        }
        BlockHitResult down = SubLevels.clip(level, new ClipContext(from, from.add(0, -10, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        return down.getType() == HitResult.Type.MISS ? null : down.getLocation();
    }

    /** 발밑 바닥 (공중이면 최대 6칸 아래, 없으면 발 위치) */
    public static Vec3 groundBelow(Level level, Entity e, Vec3 feet) {
        Vec3 from = feet.add(0, 0.1, 0);
        BlockHitResult down = SubLevels.clip(level, new ClipContext(from, from.add(0, -6, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, e));
        return down.getType() == HitResult.Type.MISS ? feet : down.getLocation();
    }

    /** 땅과 나란한 바라보는 방향 (돌진·해일) */
    public static Vec3 flatLook(Vec3 look, float yRotDeg) {
        Vec3 f = new Vec3(look.x, 0, look.z);
        if (f.lengthSqr() < 1.0E-3) {
            double r = Math.toRadians(yRotDeg);
            f = new Vec3(-Math.sin(r), 0, Math.cos(r));
        }
        return f.normalize();
    }

    /**
     * 순간이동 도착 지점 (발 위치). 벽에 막히면 벽 앞, 몸이 끼는 자리면 뒤로 물러나거나 한 칸 위를 찾는다.
     * 갈 곳이 없으면 null.
     */
    @Nullable
    public static Vec3 blinkDestination(Level level, Player p, Vec3 feet, Vec3 eye, Vec3 look, double range) {
        BlockHitResult bh = SubLevels.clip(level, new ClipContext(eye, eye.add(look.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double dist = bh.getType() == HitResult.Type.MISS ? range : Math.max(0.0, eye.distanceTo(bh.getLocation()) - 0.7);
        AABB box = p.getBoundingBox();
        for (double d = dist; d >= 1.5; d -= 0.5) {
            Vec3 c = feet.add(look.scale(d));
            for (double up : new double[]{0.0, 0.6, 1.2, -0.6}) {
                Vec3 at = c.add(0, up, 0);
                if (level.noCollision(p, box.move(at.subtract(feet)))) return at;
            }
        }
        return null;
    }
}
