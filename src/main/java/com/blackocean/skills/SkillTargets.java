package com.blackocean.skills;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 모든 스킬이 함께 쓰는 "누구를 맞힐 수 있나" 규칙과 피해·상태 도우미 (서버). */
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

    /** 원 안(가로 거리)·높이 범위 안에서 맞힐 수 있는 생물. 시야 검사는 seeFrom 이 있을 때만. */
    public static List<LivingEntity> inCylinder(ServerLevel level, @Nullable Entity owner, Vec3 base, double r, double below, double above,
                                                @Nullable Vec3 seeFrom) {
        AABB box = new AABB(base.x - r - 1, base.y - below, base.z - r - 1, base.x + r + 1, base.y + above, base.z + r + 1);
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, x -> canHit(owner, x))) {
            double dx = e.getX() - base.x, dz = e.getZ() - base.z;
            double reach = r + e.getBbWidth() * 0.5;
            if (dx * dx + dz * dz > reach * reach) continue;
            if (seeFrom != null && owner != null && !canSee(level, owner, seeFrom, e)) continue;
            out.add(e);
        }
        return out;
    }

    /** 공 모양 범위 안의 생물과 거리 비율 (0 = 중심, 1 = 가장자리) */
    public static List<LivingEntity> inSphere(ServerLevel level, @Nullable Entity owner, Vec3 c, double r, boolean needSight) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r + 1), x -> canHit(owner, x))) {
            double d = Math.max(0.0, e.getBoundingBox().getCenter().distanceTo(c) - e.getBbWidth() * 0.5);
            if (d > r) continue;
            if (needSight && owner != null && !canSee(level, owner, c, e)) continue;
            out.add(e);
        }
        return out;
    }

    public static double edgeFraction(LivingEntity e, Vec3 c, double r) {
        double d = Math.max(0.0, e.getBoundingBox().getCenter().distanceTo(c) - e.getBbWidth() * 0.5);
        return r <= 0 ? 0 : Math.min(1.0, d / r);
    }

    /** 능력별 피해 종류 (사망 메시지에 시전자 이름이 나오게) */
    public static DamageSource source(ServerLevel level, @Nullable Entity direct, @Nullable Entity owner, Element element) {
        return switch (element) {
            case FIRE -> level.damageSources().explosion(direct != null ? direct : owner, owner);
            case LIGHTNING -> new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(DamageTypes.LIGHTNING_BOLT), direct != null ? direct : owner, owner);
            default -> owner instanceof Player pl ? level.damageSources().playerAttack(pl)
                    : owner instanceof LivingEntity le ? level.damageSources().mobAttack(le) : level.damageSources().generic();
        };
    }

    public static void hurt(ServerLevel level, LivingEntity e, @Nullable Entity owner, Element element, float dmg) {
        if (dmg <= 0) return;
        e.hurt(source(level, null, owner, element), dmg);
        if (element == Element.FIRE && SkillsConfig.ALLOW_FIRE.get()) e.igniteForSeconds(3.0f);
    }

    /** 밀쳐내기 (서버). 플레이어에게도 바로 전달되도록 hurtMarked 를 켠다. */
    public static void knock(Entity e, Vec3 dir, double strength, double up) {
        if (strength <= 0 && up <= 0) return;
        e.push(dir.x * strength, up, dir.z * strength);
        e.hurtMarked = true;
    }

    /** 속도를 직접 정함 (끌어당기기·회오리) */
    public static void setVelocity(Entity e, Vec3 v) {
        e.setDeltaMovement(v);
        e.hurtMarked = true;
    }

    /** 느려짐 (amp 0 = I) */
    public static void slow(LivingEntity e, int ticks, int amp) {
        if (ticks > 0) e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, amp, false, true, true));
    }

    /** 감전·속박: 거의 못 움직임 */
    public static void stun(LivingEntity e, int ticks) {
        if (ticks <= 0) return;
        e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 5, false, true, true));
        e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 1, false, false, true));
    }
}
