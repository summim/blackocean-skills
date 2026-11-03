package com.blackocean.skills.fire;

import com.blackocean.skills.SubLevels;
import com.blackocean.skills.SkillTargets;
import com.blackocean.skills.SkillsConfig;
import com.blackocean.skills.SkillsRegistry;
import com.blackocean.skills.network.FireFistImpactPayload;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 화염 주먹 투사체 (판정 전용).
 * <p>
 * 서버: 매 틱 "이전 위치 → 다음 위치" 선 전체를 검사해서 블록·생물과 부딪히는지 확인한다.
 * 빠른 투사체가 얇은 벽이나 적을 뚫고 지나가지 않게 하기 위해서다.
 * 맞으면 같은 틱에 피해를 주고, 정확한 좌표를 담은 적중 신호를 보낸 뒤 사라진다.
 * <p>
 * 클라이언트: 스스로 움직이지 않고 서버가 매 틱 보내는 위치를 따른다. 모양은 FxManager 가 그린다.
 */
public class FireFistProjectile extends Projectile {
    protected int life = 18;

    public FireFistProjectile(EntityType<? extends FireFistProjectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static FireFistProjectile create(ServerLevel level, ServerPlayer owner, Vec3 pos, Vec3 dir) {
        return setup(new FireFistProjectile(SkillsRegistry.FIRE_FIST.get(), level), owner, pos, dir);
    }

    /** 위치·방향·속도·수명을 맞춘다 (불꽃 탄환도 같은 방식). */
    public static <T extends FireFistProjectile> T setup(T p, ServerPlayer owner, Vec3 pos, Vec3 dir) {
        p.setOwner(owner);
        p.setPos(pos.x, pos.y, pos.z);
        double speed = p.speed();
        p.setDeltaMovement(dir.scale(speed));
        p.life = Mth.ceil(p.range() / speed);
        float yRot = (float) (Mth.atan2(dir.x, dir.z) * Mth.RAD_TO_DEG);
        float xRot = (float) (Mth.atan2(dir.y, dir.horizontalDistance()) * Mth.RAD_TO_DEG);
        p.setYRot(yRot);
        p.setXRot(xRot);
        p.yRotO = yRot;
        p.xRotO = xRot;
        return p;
    }

    // ---- 종류별 수치 (불꽃 탄환은 FireBulletProjectile 에서 바꿈) ----
    protected double speed() { return SkillsConfig.SPEED.get(); }
    protected double range() { return SkillsConfig.RANGE.get(); }
    protected double hitRadius() { return SkillsConfig.HIT_RADIUS.get(); }
    protected double directDamage() { return SkillsConfig.DIRECT_DAMAGE.get(); }
    protected double splashDamage() { return SkillsConfig.SPLASH_DAMAGE.get(); }
    protected double splashRadius() { return SkillsConfig.SPLASH_RADIUS.get(); }
    protected double knockback() { return SkillsConfig.KNOCKBACK.get(); }
    protected float terrainPower() { return 2.0f; }
    protected SoundEvent impactSound() { return SkillsRegistry.FIRE_FIST_IMPACT.get(); }
    protected float impactVolume() { return 4.0f; }
    /** 적중 신호에 실리는 종류 (0 = 화염 주먹, 1 = 불꽃 탄환) */
    public int fxKind() { return 0; }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        Vec3 from = position();
        Vec3 to = from.add(getDeltaMovement());
        if (level().isClientSide) {
            // 클라이언트도 같은 직선으로 미리 움직여 끊김 없이 보이게 함 (판정은 서버만)
            setPos(to.x, to.y, to.z);
            return;
        }

        Hit hit = findHit(from, to);
        if (hit != null) {
            impact(hit.pos(), hit.normal(), hit.entity(), false);
            return;
        }
        if (tickCount >= life) {
            impact(to, new Vec3(0, 1, 0), null, true);
            return;
        }
        setPos(to.x, to.y, to.z);
    }

    private record Hit(Vec3 pos, Vec3 normal, @Nullable Entity entity) {}

    @Nullable
    private Hit findHit(Vec3 from, Vec3 to) {
        Level level = level();
        Hit best = null;
        double bestDist = Double.MAX_VALUE;

        BlockHitResult bh = SubLevels.clip(level, new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (bh.getType() != HitResult.Type.MISS) {
            bestDist = from.distanceToSqr(bh.getLocation());
            Direction d = bh.getDirection();
            best = new Hit(bh.getLocation(), new Vec3(d.getStepX(), d.getStepY(), d.getStepZ()), null);
        }

        double r = hitRadius();
        AABB sweep = new AABB(from, to).inflate(r + 2.0);
        for (Entity e : level.getEntities(this, sweep, this::canHitTarget)) {
            AABB box = e.getBoundingBox().inflate(r);
            Vec3 p;
            if (box.contains(from)) {
                p = from;
            } else {
                Optional<Vec3> clip = box.clip(from, to);
                if (clip.isEmpty()) continue;
                p = clip.get();
            }
            double dist = from.distanceToSqr(p);
            if (dist < bestDist) {
                bestDist = dist;
                Vec3 back = from.subtract(to);
                best = new Hit(p, back.lengthSqr() > 1.0E-6 ? back.normalize() : new Vec3(0, 1, 0), e);
            }
        }
        return best;
    }

    private boolean canHitTarget(Entity e) {
        // 시전자·같은 배의 선원·pvp 규칙 (모든 스킬 공통)
        return SkillTargets.canHit(getOwner(), e);
    }

    private void impact(Vec3 pos, Vec3 normal, @Nullable Entity direct, boolean fizzle) {
        ServerLevel level = (ServerLevel) level();
        Entity owner = getOwner();
        LivingEntity ownerLiving = owner instanceof LivingEntity le ? le : null;
        boolean fire = SkillsConfig.ALLOW_FIRE.get();

        // 맞은 면에서 조금 띄운 점. 정확히 면 위의 점에서 시야 검사를 하면
        // 바로 그 블록에 막힌 것으로 나와 범위 피해가 전혀 안 들어가는 문제가 있었다.
        Vec3 center = pos.add(normal.scale(0.25));

        if (!fizzle) {
            Vec3 flight = getDeltaMovement().lengthSqr() > 1.0E-6 ? getDeltaMovement().normalize() : new Vec3(0, 0, 1);
            if (direct != null) {
                direct.hurt(damageSources().mobProjectile(this, ownerLiving), (float) directDamage());
                knock(direct, flight, 1.0);
                if (fire) direct.igniteForSeconds(4.0f);
            }

            double radius = splashRadius();
            double splash = splashDamage();
            if (radius > 0 && splash > 0) {
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(radius), this::canHitTarget)) {
                    if (e == direct) continue;
                    Vec3 c = e.getBoundingBox().getCenter();
                    double dist = Math.max(0.0, c.distanceTo(center) - e.getBbWidth() * 0.5);
                    if (dist > radius || !SkillTargets.canSee(level, this, center, e)) continue;
                    double f = 1.0 - dist / radius;
                    e.hurt(damageSources().explosion(this, owner), (float) (splash * f));
                    Vec3 push = c.subtract(center);
                    knock(e, push.lengthSqr() < 1.0E-4 ? new Vec3(0, 1, 0) : push.normalize(), f);
                    if (fire) e.igniteForSeconds(2.0f);
                }
            }

            if (SkillsConfig.ALLOW_BLOCK_DAMAGE.get() || fire) {
                // 지형 파괴·불 붙이기만 바닐라 폭발에 맡긴다.
                // 생물 피해·밀쳐내기는 위에서 이미 했으므로 꺼 둔다 (예전엔 시전자까지 폭발 피해를 받고 대상은 이중 피해).
                // 바닐라 폭발 연기·소리도 꺼서 화염 주먹 연출과 겹치지 않게 한다.
                level.explode(this, null, TERRAIN_ONLY, center.x, center.y, center.z, terrainPower(), fire,
                        SkillsConfig.ALLOW_BLOCK_DAMAGE.get() ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE,
                        ParticleTypes.SMOKE, ParticleTypes.SMOKE, SILENT);
            }
            level.playSound(null, pos.x, pos.y, pos.z, impactSound(), SoundSource.PLAYERS,
                    impactVolume(), 0.95f + random.nextFloat() * 0.1f);
        }

        PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, 128.0,
                new FireFistImpactPayload(getId(), pos.x, pos.y, pos.z,
                        (float) normal.x, (float) normal.y, (float) normal.z,
                        direct == null ? -1 : direct.getId(), fizzle, fxKind()));
        discard();
    }

    /** 지형에만 영향을 주는 폭발 계산기 (생물 피해 0, 밀쳐내기 0). 불기둥·화염 고리도 같이 씀. */
    public static final ExplosionDamageCalculator TERRAIN_ONLY = new ExplosionDamageCalculator() {
        @Override
        public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
            return false;
        }

        @Override
        public float getKnockbackMultiplier(Entity entity) {
            return 0f;
        }
    };

    public static final Holder<SoundEvent> SILENT = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.EMPTY);

    private void knock(Entity e, Vec3 dir, double factor) {
        double s = knockback() * factor;
        if (s <= 0) return;
        e.push(dir.x * s, 0.25 * s + 0.1, dir.z * s);
        e.hurtMarked = true;
    }
}
