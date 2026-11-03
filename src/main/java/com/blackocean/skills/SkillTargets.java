package com.blackocean.skills;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** 모든 스킬이 함께 쓰는 "누구를 맞힐 수 있나" 규칙과 도우미. */
public final class SkillTargets {
    private SkillTargets() {}

    /** 시전자 본인, 같은 배·탈것의 선원, (pvp 꺼짐·같은 팀) 플레이어는 맞지 않는다. */
    public static boolean canHit(@Nullable Entity owner, Entity e) {
        if (!(e instanceof LivingEntity) || !e.isAlive() || e.isSpectator()) return false;
        if (e == owner) return false;
        if (isSameRide(owner, e)) return false;
        if (owner instanceof Player op && e instanceof Player tp) {
            if (!SkillsConfig.PVP.get() || !op.canHarmPlayer(tp)) return false;
        }
        return true;
    }

    /** 같은 배·탈것에 탄 사이인지 (내가 탄 것, 나를 태운 것, 같이 탄 사람). */
    public static boolean isSameRide(@Nullable Entity owner, Entity e) {
        if (owner == null) return false;
        return e == owner.getVehicle() || owner.isPassengerOfSameVehicle(e) || e.hasPassenger(owner) || owner.hasPassenger(e);
    }

    /** from 에서 대상의 머리·몸통·발 중 하나라도 보이면 true (낮은 턱 뒤에 발만 가려도 맞게). */
    public static boolean canSee(Level level, Entity viewer, Vec3 from, LivingEntity e) {
        AABB b = e.getBoundingBox();
        double cx = (b.minX + b.maxX) * 0.5, cz = (b.minZ + b.maxZ) * 0.5;
        return clear(level, viewer, from, new Vec3(cx, b.minY + b.getYsize() * 0.5, cz))
                || clear(level, viewer, from, new Vec3(cx, b.maxY - 0.1, cz))
                || clear(level, viewer, from, new Vec3(cx, b.minY + 0.1, cz));
    }

    private static boolean clear(Level level, Entity viewer, Vec3 a, Vec3 b) {
        return SubLevels.clip(level, new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer)).getType() == HitResult.Type.MISS;
    }

    /** 밀쳐내기 (서버). 플레이어에게도 바로 전달되도록 hurtMarked 를 켠다. */
    public static void knock(Entity e, Vec3 dir, double strength, double up) {
        if (strength <= 0 && up <= 0) return;
        e.push(dir.x * strength, up, dir.z * strength);
        e.hurtMarked = true;
    }
}
