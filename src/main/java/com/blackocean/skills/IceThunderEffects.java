package com.blackocean.skills;

import com.blackocean.skills.entity.SkillProjectile;
import com.blackocean.skills.network.SkillEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 얼음·천둥 능력의 서버 판정. 연출은 SkillEvents 신호로 클라이언트(IceFx·ThunderFx)가 그린다.
 */
final class IceThunderEffects {
    private IceThunderEffects() {}

    // =====================================================================
    // 공통 도우미
    // =====================================================================

    /** 얼리고, 주변 사람들 화면에 "얼음에 갇힌" 연출 */
    static void freeze(ServerLevel level, LivingEntity e, int ticks) {
        if (ticks <= 0) return;
        SkillTargets.freeze(e, ticks);
        SkillEvents.send(level, null, SkillEvents.FREEZE, null, e.position(), Vec3.ZERO, e.getBbWidth(), e.getBbHeight(), ticks, e.getId(), 96.0);
    }

    /** 기절시키고 머리 위에 전기 고리 연출 */
    static void stun(ServerLevel level, LivingEntity e, int ticks) {
        if (ticks <= 0) return;
        SkillTargets.stun(e, ticks);
        SkillEvents.send(level, null, SkillEvents.STUN, null, e.position(), Vec3.ZERO, e.getBbWidth(), e.getBbHeight(), ticks, e.getId(), 96.0);
    }

    /** 설정으로 켰을 때만 실제 지형 파괴 (블록만, 생물 피해 없음) */
    static void terrain(ServerLevel level, Vec3 at, float power) {
        if (!SkillsConfig.ALLOW_BLOCK_DAMAGE.get()) return;
        level.explode(null, null, SkillProjectile.TERRAIN_ONLY, at.x, at.y, at.z, power, false,
                Level.ExplosionInteraction.MOB, ParticleTypes.SMOKE, ParticleTypes.SMOKE, SkillProjectile.SILENT);
    }

    /** @param radius 들리는 반경 (블록, SkillSounds.R_*) */
    static void sound(ServerLevel level, Vec3 at, String name, float radius, float pitch) {
        SkillSounds.play(level, at, name, radius, pitch);
    }

    private static Vec3 out(Vec3 from, LivingEntity e) {
        Vec3 flat = e.position().subtract(from).multiply(1, 0, 1);
        return flat.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : flat.normalize();
    }

    /** 땅 높이를 따라감: 위 3칸에서 아래 6칸까지 */
    static Vec3 groundAt(ServerLevel level, LivingEntity e, Vec3 p) {
        Vec3 from = p.add(0, 3.0, 0);
        BlockHitResult down = SubLevels.clip(level, new ClipContext(from, from.add(0, -6.5, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, e));
        return down.getType() == HitResult.Type.MISS ? p : down.getLocation();
    }

    // =====================================================================
    // 즉시 스킬
    // =====================================================================

    /** 절대 냉기: 주변을 한순간에 얼림 */
    static void frostNova(ServerLevel level, ServerPlayer p, Skill s) {
        Vec3 c = p.position().add(0, 1.0, 0);
        double r = Math.max(0.5, s.radius());
        for (LivingEntity e : SkillTargets.inSphere(level, p, c, r, true)) {
            SkillTargets.hurt(level, e, p, Element.ICE, s.damage());
            freeze(level, e, s.durationTicks());
        }
        sound(level, c, "ice.nova", SkillSounds.R_IMPACT, 1.0f);
        SkillEvents.send(level, s, SkillEvents.NOVA, p, SkillAim.groundBelow(level, p, p.position()), Vec3.ZERO, (float) r, 0, s.durationTicks(), -1);
    }

    /** 뇌명 일격: 앞쪽 부채꼴 충격파 */
    static void thunderclap(ServerLevel level, ServerPlayer p, Skill s) {
        Vec3 origin = SkillAim.groundBelow(level, p, p.position());
        Vec3 dir = SkillAim.flatLook(p.getLookAngle(), p.getYRot());
        // 연출(망치를 내려침)이 먼저 시작되고, 망치가 땅에 닿는 2틱 뒤에 피해·소리
        SkillEvents.send(level, s, SkillEvents.CLAP, p, origin, dir, (float) s.range(), (float) s.radius(), 14, -1);
        SkillServer.addEffect(new Later(level, p, s, 2, o -> {
            for (LivingEntity e : SkillTargets.inCone(level, o, origin, dir, s.range(), s.radius())) {
                double f = Math.min(1.0, e.position().distanceTo(origin) / Math.max(1.0, s.range()));
                SkillTargets.hurt(level, e, o, Element.THUNDER, (float) (s.damage() * (1.0 - 0.3 * f)));
                stun(level, e, s.durationTicks());
                SkillTargets.knock(e, out(origin, e), s.speed() * (1.0 - 0.4 * f), 0.45);
            }
            sound(level, origin, "thunder.clap", SkillSounds.R_IMPACT, 0.9f);
            sound(level, origin, "thunder.slam", SkillSounds.R_CAST, 1.1f);
        }));
    }

    /** 몇 틱 뒤에 한 번 실행 (시전자가 사라지면 취소) */
    static final class Later extends ServerEffects.Effect {
        final int at;
        final java.util.function.Consumer<ServerPlayer> run;

        Later(ServerLevel level, ServerPlayer p, Skill s, int at, java.util.function.Consumer<ServerPlayer> run) {
            super(level, p, s);
            this.at = at;
            this.run = run;
        }

        @Override
        boolean step(ServerPlayer o) {
            if (!o.isAlive()) return true;
            if (age < at) return false;
            run.accept(o);
            return true;
        }
    }

    // =====================================================================
    // 지속 스킬
    // =====================================================================

    /** 빙창 행진: 땅을 따라 앞으로 솟는 얼음 가시 */
    static final class Spikes extends ServerEffects.Effect {
        final Vec3 start, dir, side;
        final double width, range, speed;
        final int ticks;
        final Set<Integer> hit = new HashSet<>();

        Spikes(ServerLevel level, ServerPlayer p, Skill s) {
            super(level, p, s);
            this.start = SkillAim.groundBelow(level, p, p.position());
            this.dir = SkillAim.flatLook(p.getLookAngle(), p.getYRot());
            this.side = new Vec3(-dir.z, 0, dir.x);
            this.width = Math.max(0.5, s.radius());
            Vec3 from = start.add(0, 1.0, 0), to = from.add(dir.scale(s.range()));
            BlockHitResult wall = SubLevels.clip(level, new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            this.range = wall.getType() == HitResult.Type.MISS ? s.range() : Math.max(1.5, wall.getLocation().distanceTo(from));
            this.speed = Math.max(0.3, s.speed());
            this.ticks = (int) Math.ceil(range / speed);
            sound(start, "ice.spikes", SkillSounds.R_CAST, 1.0f);
            SkillEvents.send(level, s, SkillEvents.SPIKES, p, start, dir, (float) width, (float) range, ticks, -1);
        }

        @Override
        boolean step(ServerPlayer o) {
            if (age > ticks) return true;
            double d = Math.min(range, age * speed);
            Vec3 front = groundAt(level, o, start.add(dir.scale(d)));
            if (age % 4 == 0) sound(front, "ice.spikes", SkillSounds.R_CHARGE, 0.92f + level.random.nextFloat() * 0.2f); // 변형 3개 + 4틱 간격 (예전 3틱은 겹쳐서 지글거림), 반복음이라 반경은 작게
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                    new net.minecraft.world.phys.AABB(front, front).inflate(width + 2, 3, width + 2), x -> SkillTargets.canHit(o, x))) {
                Vec3 rel = e.position().subtract(start);
                double along = rel.dot(dir), lat = Math.abs(rel.dot(side));
                if (along < d - speed - 1.0 || along > d + 0.8 || lat > width + e.getBbWidth() * 0.5) continue;
                if (hit.contains(e.getId())) continue;
                // 길 옆 벽 너머는 맞지 않음 (길 가운데 땅 위에서 보이는 대상만)
                Vec3 lane = groundAt(level, o, start.add(dir.scale(Math.max(0.0, Math.min(range, along))))).add(0, 1.0, 0);
                if (!SkillTargets.canSee(level, o, lane, e)) continue;
                hit.add(e.getId());
                SkillTargets.hurt(level, e, o, Element.ICE, skill.damage(), true);
                SkillSounds.playLimited(level, e.getBoundingBox().getCenter(), "ice.shard_hit", SkillSounds.R_HIT, 0.85f + level.random.nextFloat() * 0.1f, 2, 2.5);
                // 얼림이 위로 가는 속도를 없애므로 얼린 다음에 띄움
                freeze(level, e, skill.durationTicks());
                SkillTargets.setVelocity(e, new Vec3(0, 0.75, 0).add(dir.scale(0.15)));
            }
            return false;
        }
    }

    /** [궁] 빙하기 강림: 빙산 충돌 → 얼음 지대 → 산산조각 */
    static final class Glacier extends ServerEffects.Effect {
        final Vec3 center;
        final double r;
        final int duration;

        Glacier(ServerLevel level, ServerPlayer p, Skill s, Vec3 center) {
            super(level, p, s);
            this.center = center;
            this.r = Math.max(1.0, s.radius());
            this.duration = Math.max(20, s.durationTicks());
            burst(p, 1.0, 40);
            sound(center, "ice.glacier", SkillSounds.R_ULT, 1.0f);
            terrain(level, center, 3.5f);
            SkillEvents.send(level, s, SkillEvents.GLACIER, p, center, Vec3.ZERO, (float) r, 0, duration, -1);
        }

        void burst(ServerPlayer o, double mult, int freezeTicks) {
            for (LivingEntity e : SkillTargets.inCylinder(level, o, center, r, 2.0, 8.0, center.add(0, 1, 0))) {
                double f = SkillTargets.edgeFraction(e, center, r);
                SkillTargets.hurt(level, e, o, Element.ICE, (float) (skill.damage() * mult * (1.0 - 0.5 * f)), true);
                freeze(level, e, freezeTicks); // 얼린 다음에 밀침 (얼림이 위로 가는 속도를 없앰)
                SkillTargets.knock(e, out(center, e), 1.0 * (1 - 0.5 * f), 0.6);
            }
        }

        @Override
        boolean step(ServerPlayer o) {
            // 산산조각 직전 차오름 (끝 = 마지막 폭발 틱)
            if (age == Math.max(1, duration - SkillSounds.SWELL_TICKS)) sound(center, "common.ult_swell", SkillSounds.R_ULT_CHARGE, 1.0f);
            if (age >= duration) {
                burst(o, 0.6, 30);
                sound(center, "ice.glacier_shatter", SkillSounds.R_ULT, 1.0f);
                SkillEvents.send(level, skill, SkillEvents.GLACIER, o, center, Vec3.ZERO, (float) r, 1, 0, -1);
                return true;
            }
            if (age % 10 == 0) {
                List<LivingEntity> in = SkillTargets.inCylinder(level, o, center, r, 1.0, 4.0, center.add(0, 1, 0));
                for (LivingEntity e : in) {
                    SkillTargets.hurt(level, e, o, Element.ICE, skill.damage2(), true);
                    SkillTargets.slow(e, 30, 3);
                    e.setTicksFrozen(Math.min(139, e.getTicksFrozen() + 30));
                }
                if (!in.isEmpty()) SkillSounds.tick(level, center.add(0, 1, 0), "ice.tick", 1.0f, 8, r + 2);
            }
            return false;
        }

        @Override
        void abort() {
            // 시전자가 사라져도 빙산은 조용히 녹음 (클라이언트 연출은 정해진 시간 뒤 스스로 사라짐)
        }
    }

    /** 천둥 도약: 포물선으로 뛰어 내려찍기 */
    static final class Leap extends ServerEffects.Effect {
        final Vec3 start, dest;
        final int ticks;
        final double arc;
        boolean landed;

        Leap(ServerLevel level, ServerPlayer p, Skill s, Vec3 target) {
            super(level, p, s);
            if (p.isPassenger()) p.stopRiding();
            this.start = p.position();
            Vec3 flat = target.subtract(start).multiply(1, 0, 1);
            double max = s.range();
            this.dest = flat.length() > max ? start.add(flat.normalize().scale(max)).add(0, target.y - start.y, 0) : target;
            this.ticks = Math.max(6, s.durationTicks());
            this.arc = 2.5 + flat.length() * 0.25;
            sound(start, "thunder.leap", SkillSounds.R_CAST, 1.0f);
            SkillEvents.send(level, s, SkillEvents.LEAP, p, start, dest, (float) s.radius(), 0, ticks, -1);
            step(p);
        }

        @Override
        boolean controlsOwner() {
            return !landed;
        }

        Vec3 at(double t) {
            double k = Math.min(1.0, t);
            return start.lerp(dest, k).add(0, 4 * arc * k * (1 - k), 0);
        }

        @Override
        boolean step(ServerPlayer o) {
            if (!o.isAlive()) return true;
            o.fallDistance = 0;
            // 내려찍기 직전 바람 (끝 = 착지 틱)
            if (!landed && age == Math.max(0, ticks - SkillSounds.WHOOSH_TICKS)) sound(dest, "common.heavy_whoosh", SkillSounds.R_CAST, 1.0f);
            if (!landed) {
                if (age < ticks) {
                    // 경로의 기울기(속도) + 어긋난 만큼 조금만 보정 → 핑이 높아도 출렁이지 않음
                    Vec3 v = at((age + 1.0) / ticks).subtract(at((double) age / ticks));
                    Vec3 err = at((double) age / ticks).subtract(o.position());
                    if (err.lengthSqr() < 9.0) v = v.add(err.scale(0.25));
                    SkillTargets.setVelocity(o, v);
                    return false;
                }
                landed = true;
                Vec3 c = SkillAim.groundBelow(level, o, o.position());
                double r = Math.max(0.5, skill.radius());
                for (LivingEntity e : SkillTargets.inCylinder(level, o, c, r, 1.5, 3.5, c.add(0, 1, 0))) {
                    SkillTargets.hurt(level, e, o, Element.THUNDER, skill.damage(), true);
                    SkillTargets.setVelocity(e, out(c, e).scale(0.35).add(0, 0.7, 0));
                    stun(level, e, 12);
                }
                SkillTargets.setVelocity(o, Vec3.ZERO);
                sound(c, "thunder.slam", SkillSounds.R_IMPACT, 1.0f);
                sound(c, "thunder.clap", SkillSounds.R_CAST, 1.2f);
                SkillEvents.send(level, skill, SkillEvents.LEAP, o, c, c, (float) r, 1, 0, -1);
            }
            return age > ticks + 20; // 착지 후 1초 동안 낙하 피해 없음
        }
    }

    /** 천둥 포효: 끌어당긴 뒤 폭발 */
    static final class Roar extends ServerEffects.Effect {
        static final int PULL = 12;

        Roar(ServerLevel level, ServerPlayer p, Skill s) {
            super(level, p, s);
            sound(p.position(), "thunder.roar", SkillSounds.R_IMPACT, 1.0f);
            SkillEvents.send(level, s, SkillEvents.ROAR, p, SkillAim.groundBelow(level, p, p.position()), Vec3.ZERO, (float) s.radius(), 0, PULL, -1);
        }

        @Override
        boolean step(ServerPlayer o) {
            if (!o.isAlive()) return true;
            Vec3 c = o.position();
            double r = Math.max(1.0, skill.radius());
            List<LivingEntity> in = SkillTargets.inSphere(level, o, c.add(0, 1, 0), r, true);
            if (age < PULL) {
                for (LivingEntity e : in) {
                    Vec3 to = c.subtract(e.position());
                    double d = to.length();
                    if (d < 1.6) continue;
                    Vec3 v = to.scale(Math.min(0.55, d * 0.12) / d);
                    SkillTargets.setVelocity(e, new Vec3(v.x, Math.max(e.getDeltaMovement().y, 0.05), v.z));
                }
                return false;
            }
            double br = r * 0.6;
            for (LivingEntity e : SkillTargets.inSphere(level, o, c.add(0, 1, 0), br, true)) {
                SkillTargets.hurt(level, e, o, Element.THUNDER, skill.damage(), true);
                stun(level, e, skill.durationTicks());
                SkillTargets.knock(e, out(c, e), 0.5, 0.55);
            }
            sound(c, "thunder.roar_burst", SkillSounds.R_IMPACT, 1.0f);
            SkillEvents.send(level, skill, SkillEvents.ROAR, o, SkillAim.groundBelow(level, o, c), Vec3.ZERO, (float) br, 1, 0, -1);
            return true;
        }
    }

    /** [궁] 뇌신 강림: 솟구침 → 내려찍기 → 여진 3번 */
    static final class Judgment extends ServerEffects.Effect {
        static final int SLAM = 6, AFTER_GAP = 10, AFTERSHOCKS = 3;
        final Vec3 start, target;
        final int rise;
        final double r;
        Vec3 slamFrom;
        int impactAge = -1;

        Judgment(ServerLevel level, ServerPlayer p, Skill s, Vec3 target) {
            super(level, p, s);
            if (p.isPassenger()) p.stopRiding();
            this.start = p.position();
            this.target = target;
            this.rise = Math.max(10, s.durationTicks());
            this.r = Math.max(1.0, s.radius());
            p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, rise + SLAM + 10, 2, false, false, true));
            sound(start, "thunder.rise", SkillSounds.R_ULT_CHARGE, 1.0f);
            SkillEvents.send(level, s, SkillEvents.JUDGMENT, p, start, target, (float) r, 0, rise, -1);
        }

        @Override
        boolean controlsOwner() {
            return impactAge < 0;
        }

        @Override
        boolean step(ServerPlayer o) {
            if (!o.isAlive()) return true;
            o.fallDistance = 0;
            if (age <= rise) {
                // 시전 지점 위 7칸으로 떠오름
                // 정해진 길(부드럽게 감속하며 7칸 위로)을 따라감 → 핑이 높아도 출렁이지 않음
                double k0 = ease((double) (age - 1) / rise), k1 = ease((double) age / rise);
                Vec3 v = new Vec3(0, 7.0 * (k1 - k0), 0);
                Vec3 err = start.add(0, 7.0 * k0, 0).subtract(o.position());
                if (err.lengthSqr() < 9.0) v = v.add(err.scale(0.2));
                SkillTargets.setVelocity(o, v);
                if (age == rise) {
                    slamFrom = o.position();
                    sound(o.position(), "thunder.hammer", SkillSounds.R_ULT_CHARGE, 0.6f);
                    sound(target, "common.heavy_whoosh", SkillSounds.R_IMPACT, 1.0f); // 내려꽂는 바람 (SLAM 6틱 = 바람 길이)
                }
                return false;
            }
            int t = age - rise;
            if (t <= SLAM) {
                Vec3 v = target.subtract(slamFrom).scale(1.0 / SLAM);
                Vec3 err = slamFrom.lerp(target, (double) (t - 1) / SLAM).subtract(o.position());
                if (err.lengthSqr() < 9.0) v = v.add(err.scale(0.25));
                SkillTargets.setVelocity(o, v);
                if (t == SLAM) impact(o);
                return false;
            }
            int since = age - impactAge;
            if (since > 0 && since % AFTER_GAP == 0) {
                int n = since / AFTER_GAP;
                if (n > AFTERSHOCKS) return true;
                double rr = r * (1.0 + 0.3 * n);
                for (LivingEntity e : SkillTargets.inCylinder(level, o, target, rr, 1.5, 4.0, target.add(0, 1, 0))) {
                    SkillTargets.hurt(level, e, o, Element.THUNDER, skill.damage2(), true);
                    SkillTargets.setVelocity(e, out(target, e).scale(0.3).add(0, 0.45, 0));
                }
                sound(target, "thunder.aftershock", SkillSounds.R_BIG, 0.95f + 0.05f * n);
                SkillEvents.send(level, skill, SkillEvents.JUDGMENT, o, target, target, (float) rr, 2, n, -1);
            }
            return false;
        }

        static double ease(double t) {
            t = Math.max(0, Math.min(1, t));
            return 1 - (1 - t) * (1 - t);
        }

        void impact(ServerPlayer o) {
            impactAge = age;
            SkillTargets.setVelocity(o, Vec3.ZERO);
            for (LivingEntity e : SkillTargets.inCylinder(level, o, target, r, 2.0, 8.0, target.add(0, 1, 0))) {
                double f = SkillTargets.edgeFraction(e, target, r);
                SkillTargets.hurt(level, e, o, Element.THUNDER, (float) (skill.damage() * (1.0 - 0.5 * f)), true);
                SkillTargets.setVelocity(e, out(target, e).scale(1.2 * (1 - 0.5 * f)).add(0, 0.9, 0));
                stun(level, e, 20);
            }
            sound(target, "thunder.judgment", SkillSounds.R_ULT, 1.0f);
            sound(target, "thunder.clap", SkillSounds.R_ULT_CHARGE, 0.6f);
            terrain(level, target, 4.0f);
            SkillEvents.send(level, skill, SkillEvents.JUDGMENT, o, target, target, (float) r, 1, 0, -1);
        }
    }
}
