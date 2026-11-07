package com.blackocean.skills.entity;

import com.blackocean.skills.Element;
import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillServer;
import com.blackocean.skills.SkillTargets;
import com.blackocean.skills.SkillsConfig;
import com.blackocean.skills.SkillsHooks;
import com.blackocean.skills.SkillSounds;
import com.blackocean.skills.SkillsRegistry;
import com.blackocean.skills.SubLevels;
import com.blackocean.skills.network.SkillEvents;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 모든 투사체 스킬 (판정 전용). 어떤 스킬인지는 생성 신호에 실려 클라이언트로 간다 → 클라이언트가 모양을 그림.
 * <p>
 * 서버: 매 틱 "이전 위치 → 다음 위치" 선 전체를 검사 (빠른 투사체가 얇은 벽·적을 뚫지 않게).
 * 맞으면 같은 틱에 피해를 주고, 정확한 좌표를 담은 IMPACT 신호를 보낸 뒤 사라진다.
 * 진공 참격(WIND_BLADE)은 적을 꿰뚫고 계속 날아간다 (적마다 한 번).
 */
public class SkillProjectile extends Projectile implements IEntityWithComplexSpawn {
    private Skill skill = Skill.FIRE_FIST;
    private int life = 20;
    private final Set<Integer> pierced = new HashSet<>();
    /** 타격 직전 소리(무거운 바람·궁극기 차오름)를 이미 틀었음 — 소리 전용, 판정과 무관 */
    private boolean cued;

    public SkillProjectile(EntityType<? extends SkillProjectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static SkillProjectile create(ServerLevel level, ServerPlayer owner, Skill skill, Vec3 pos, Vec3 dir) {
        SkillProjectile p = new SkillProjectile(SkillsRegistry.SKILL_PROJECTILE.get(), level);
        p.skill = skill;
        p.setOwner(owner);
        p.setPos(pos.x, pos.y, pos.z);
        double speed = Mth.clamp(skill.speed(), 0.2, 3.9);
        p.setDeltaMovement(dir.scale(speed));
        p.life = Mth.ceil(Math.max(2.0, skill.range()) / speed);
        float yRot = (float) (Mth.atan2(dir.x, dir.z) * Mth.RAD_TO_DEG);
        float xRot = (float) (Mth.atan2(dir.y, dir.horizontalDistance()) * Mth.RAD_TO_DEG);
        p.setYRot(yRot);
        p.setXRot(xRot);
        p.yRotO = yRot;
        p.xRotO = xRot;
        return p;
    }

    public Skill getSkill() {
        return skill;
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(skill.ordinal());
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        Skill s = Skill.byIndex(buf.readVarInt());
        if (s != null) skill = s;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 160 * 160;
    }

    @Override
    public void tick() {
        super.tick();
        Vec3 from = position();
        Vec3 to = from.add(getDeltaMovement());
        if (level().isClientSide) {
            setPos(to.x, to.y, to.z); // 클라이언트도 같은 직선으로 움직여 부드럽게 (판정은 서버만)
            return;
        }
        ServerLevel level = (ServerLevel) level();
        // 시전자가 나감(또는 다른 차원) → 주인 없는 투사체는 PvP·팀 규칙을 확인할 수 없으므로 조용히 사라짐
        if (getOwner() == null) {
            discard();
            return;
        }

        // 블록
        BlockHitResult bh = SubLevels.clip(level, new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        Vec3 end = bh.getType() == HitResult.Type.MISS ? to : bh.getLocation();

        // 생물 (선 위에서 가까운 순서). 첫 틱은 시전자 눈에서부터 검사 → 코앞(눈과 손 사이)의 적을 지나치지 않음
        Vec3 sweepFrom = from;
        if (tickCount <= 1 && getOwner() instanceof LivingEntity ol && ol.level() == level) {
            Vec3 eye = ol.getEyePosition();
            if (eye.distanceToSqr(from) < 9.0) sweepFrom = eye;
        }
        List<Hit> hits = entityHits(level, sweepFrom, end);
        boolean pierce = skill.shape == Skill.Shape.BLADE;
        for (Hit h : hits) {
            if (pierce) {
                pierceHit(level, h);
            } else {
                Vec3 back = from.subtract(to);
                impact(level, h.pos, back.lengthSqr() > 1.0E-6 ? back.normalize() : new Vec3(0, 1, 0), h.entity, false);
                return;
            }
        }
        if (bh.getType() != HitResult.Type.MISS) {
            Direction d = bh.getDirection();
            impact(level, bh.getLocation(), new Vec3(d.getStepX(), d.getStepY(), d.getStepZ()), null, false);
            return;
        }
        if (tickCount >= life) {
            impact(level, to, new Vec3(0, 1, 0), null, true);
            return;
        }
        if (!cued) cue(level, to);
        setPos(to.x, to.y, to.z);
    }

    /**
     * 소리 전용: 무거운 투사체가 곧(바람 길이 안에) 맞을 것 같으면 그 자리에서 미리 바람·차오름 소리를 틂 →
     * 소리의 끝이 타격 순간에 겹침. 화염 주먹·망치 = 무거운 바람(6틱), 작열의 태양 = 궁극기 차오름(16틱).
     * 앞으로 갈 길만 미리 검사하고 판정·움직임은 바꾸지 않음.
     */
    private void cue(ServerLevel level, Vec3 at) {
        int n = switch (skill) {
            case FIRE_FIST, THUNDER_HAMMER -> SkillSounds.WHOOSH_TICKS;
            case FIRE_SUN -> SkillSounds.SWELL_TICKS;
            default -> 0;
        };
        if (n <= 0) {
            cued = true;
            return;
        }
        Vec3 ahead = at.add(getDeltaMovement().scale(n));
        Vec3 hitAt = null;
        if (skill == Skill.FIRE_SUN && tickCount + n >= life) hitAt = at.add(getDeltaMovement().scale(Math.max(0, life - tickCount)));
        BlockHitResult bh = SubLevels.clip(level, new ClipContext(at, ahead, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (bh.getType() != HitResult.Type.MISS && (hitAt == null || bh.getLocation().distanceToSqr(at) < hitAt.distanceToSqr(at))) hitAt = bh.getLocation();
        List<Hit> hs = entityHits(level, at, hitAt != null ? hitAt : ahead);
        if (!hs.isEmpty()) hitAt = hs.get(0).pos;
        if (hitAt == null) return;
        cued = true;
        if (skill == Skill.FIRE_SUN) SkillSounds.play(level, hitAt, "common.ult_swell", SkillSounds.R_ULT_CHARGE, 1.0f);
        else SkillSounds.play(level, hitAt, "common.heavy_whoosh", SkillSounds.R_CAST, skill == Skill.THUNDER_HAMMER ? 0.95f : 1.05f);
    }

    private record Hit(Vec3 pos, LivingEntity entity, double dist) {}

    private List<Hit> entityHits(ServerLevel level, Vec3 from, Vec3 to) {
        List<Hit> out = new ArrayList<>();
        double r = skill.hit;
        AABB sweep = new AABB(from, to).inflate(r + 2.0);
        Entity owner = getOwner();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, sweep, x -> SkillTargets.canHit(owner, x))) {
            if (pierced.contains(e.getId())) continue;
            AABB box = e.getBoundingBox().inflate(r);
            Vec3 p;
            if (box.contains(from)) {
                p = from;
            } else {
                Optional<Vec3> clip = box.clip(from, to);
                if (clip.isEmpty()) continue;
                p = clip.get();
            }
            out.add(new Hit(p, e, from.distanceToSqr(p)));
        }
        out.sort((a, b) -> Double.compare(a.dist, b.dist));
        return out;
    }

    private void pierceHit(ServerLevel level, Hit h) {
        pierced.add(h.entity.getId());
        Entity owner = getOwner();
        SkillTargets.hurt(level, h.entity, owner, skill.element, skill.damage(), true);
        Vec3 flight = getDeltaMovement().normalize();
        SkillTargets.knock(h.entity, flight, 0.7, 0.25);
        SkillSounds.play(level, h.pos, "wind.blade_hit", SkillSounds.R_HIT, 0.95f + random.nextFloat() * 0.1f);
        SkillEvents.send(level, skill, SkillEvents.PIERCE, owner, h.pos, flight, 1f, 0f, getId(), h.entity.getId());
    }

    private void impact(ServerLevel level, Vec3 pos, Vec3 normal, @Nullable LivingEntity direct, boolean fizzle) {
        Entity owner = getOwner();
        LivingEntity ownerLiving = owner instanceof LivingEntity le ? le : null;
        Vec3 center = pos.add(normal.scale(0.25)); // 면에서 조금 띄움 (정확히 면 위면 시야 검사가 막힘)
        Vec3 flight = getDeltaMovement().lengthSqr() > 1.0E-6 ? getDeltaMovement().normalize() : new Vec3(0, 0, 1);
        // 부채꼴 스킬(얼음 파편)은 radius 가 각도(도)라서 폭발 반지름으로 쓰면 안 됨
        float radius = skill.aim == Skill.Aim.CONE ? 0f : (float) skill.radius();

        if (!fizzle || skill == Skill.FIRE_SUN) {
            switch (skill) {
                case FIRE_SUN -> {
                    // 거대한 폭발 (가장자리 절반) + 불바다
                    for (LivingEntity e : SkillTargets.inSphere(level, owner, center, radius, true)) {
                        double f = SkillTargets.edgeFraction(e, center, radius);
                        SkillTargets.hurt(level, e, owner, Element.FIRE, (float) (skill.damage() * (1.0 - 0.5 * f)));
                        Vec3 push = e.getBoundingBox().getCenter().subtract(center);
                        SkillTargets.knock(e, push.lengthSqr() < 1.0E-4 ? new Vec3(0, 1, 0) : push.normalize(), 1.6 * (1 - 0.5 * f), 0.7);
                    }
                    terrain(level, center, 4.0f);
                    SkillSounds.play(level, pos, "fire.sun_impact", SkillSounds.R_ULT, 1.0f);
                    SkillServer.addFireField(level, owner, pos, radius * 0.7f, skill);
                }
                case WIND_BLADE -> SkillSounds.play(level, pos, "wind.blade_hit", SkillSounds.R_HIT, 0.8f);
                default -> {
                    if (direct != null) {
                        SkillTargets.hurt(level, direct, owner, skill.element, skill.damage(), rapid());
                        SkillTargets.knock(direct, flight, skill == Skill.FIRE_FIST ? 0.9 : skill == Skill.THUNDER_HAMMER ? 1.2 : 0.4,
                                skill == Skill.THUNDER_HAMMER ? 0.5 : 0.25);
                        if (skill.element == Element.WATER) SkillTargets.slow(direct, skill.durationTicks(), 1);
                        if (skill == Skill.ICE_SPEAR) SkillsHooks.freeze(level, direct, skill.durationTicks());
                        if (skill == Skill.ICE_SHARDS) SkillTargets.slow(direct, skill.durationTicks(), 2);
                        if (skill == Skill.THUNDER_HAMMER) SkillsHooks.stun(level, direct, skill.durationTicks());
                        if (skill.element == Element.FIRE && SkillsConfig.ALLOW_FIRE.get()) direct.igniteForSeconds(4.0f);
                    }
                    float splash = skill.damage2();
                    if (radius > 0 && splash > 0) {
                        for (LivingEntity e : SkillTargets.inSphere(level, owner, center, radius, true)) {
                            if (e == direct) continue;
                            double f = 1.0 - SkillTargets.edgeFraction(e, center, radius);
                            SkillTargets.hurt(level, e, owner, skill.element, (float) (splash * f), rapid());
                            Vec3 push = e.getBoundingBox().getCenter().subtract(center);
                            boolean heavy = skill == Skill.THUNDER_HAMMER;
                            SkillTargets.knock(e, push.lengthSqr() < 1.0E-4 ? new Vec3(0, 1, 0) : push.normalize(), (heavy ? 1.1 : 0.6) * f, (heavy ? 0.5 : 0.2) * f);
                            if (skill.element == Element.WATER) SkillTargets.slow(e, skill.durationTicks() / 2, 0);
                            if (skill.element == Element.ICE) SkillTargets.slow(e, 30, 2);
                            if (heavy) SkillsHooks.stun(level, e, skill.durationTicks() / 2);
                        }
                    }
                    if (skill == Skill.FIRE_FIST) terrain(level, center, 2.0f);
                    if (skill == Skill.THUNDER_HAMMER) SkillsHooks.terrain(level, center, 2.5f);
                    String snd = switch (skill) {
                        case FIRE_BULLETS -> "fire_bullets.impact";
                        case WATER_BULLET -> "water.splash";
                        case ICE_SPEAR -> "ice.shatter";
                        case ICE_SHARDS -> "ice.shard_hit"; // 가벼운 공격: 짧고 밝은 '틱' (창의 무거운 깨짐과 구분)
                        case THUNDER_HAMMER -> "thunder.hammer_impact";
                        default -> "fire_fist.impact";
                    };
                    float pitch = 0.95f + random.nextFloat() * 0.1f;
                    switch (skill) {
                        // 파편 7발·연사 5발: 같은 자리에서 한꺼번에 터지는 소리는 하나로 (겹치면 지글거리는 소음)
                        case ICE_SHARDS -> SkillSounds.playLimited(level, pos, snd, SkillSounds.R_RAPID, pitch, 3, 3.0);
                        case FIRE_BULLETS -> SkillSounds.playLimited(level, pos, snd, SkillSounds.R_RAPID, pitch, 2, 2.0);
                        case FIRE_FIST, THUNDER_HAMMER -> SkillSounds.play(level, pos, snd, SkillSounds.R_BIG, pitch);
                        case ICE_SPEAR -> SkillSounds.play(level, pos, snd, SkillSounds.R_IMPACT, pitch - 0.08f);
                        default -> SkillSounds.play(level, pos, snd, SkillSounds.R_HIT, pitch);
                    }
                }
            }
        }
        SkillEvents.send(level, skill, SkillEvents.IMPACT, owner, pos, normal, radius, fizzle ? 1f : 0f, getId(), direct == null ? -1 : direct.getId());
        discard();
    }

    /** 짧은 간격으로 여러 번 맞는 투사체 (연사·파편): 맞은 뒤 무적 시간 무시 */
    private boolean rapid() {
        return skill == Skill.FIRE_BULLETS || skill == Skill.ICE_SHARDS;
    }

    /** 설정으로 켰을 때만 지형 파괴·불 붙이기 (화염 계열) */
    private void terrain(ServerLevel level, Vec3 at, float power) {
        boolean fire = SkillsConfig.ALLOW_FIRE.get();
        boolean blocks = SkillsConfig.ALLOW_BLOCK_DAMAGE.get();
        if (!fire && !blocks) return;
        level.explode(this, null, TERRAIN_ONLY, at.x, at.y, at.z, power, fire,
                blocks ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE,
                ParticleTypes.SMOKE, ParticleTypes.SMOKE, SILENT);
    }

    /** 지형에만 영향을 주는 폭발 계산기 (생물 피해 0, 밀쳐내기 0). */
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
}
