package com.blackocean.skills;

import com.blackocean.skills.network.SkillEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 스킬 동작 (서버 판정). 연출은 SkillEvents 신호로 클라이언트가 그린다.
 * 즉시 끝나는 스킬은 정적 메서드, 시간이 걸리는 스킬은 Effect (매 틱 SkillServer 가 부름).
 */
final class ServerEffects {
    private static final Vec3 UP = new Vec3(0, 1, 0);

    private ServerEffects() {}

    // =====================================================================
    // 즉시 스킬
    // =====================================================================

    /** 화염 고리: 사방으로 불의 파도 */
    static void flameRing(ServerLevel level, ServerPlayer p, Skill s) {
        double radius = Math.max(0.5, s.radius());
        Vec3 c = p.position().add(0, 0.9, 0);
        for (LivingEntity e : SkillTargets.inCylinder(level, p, p.position(), radius, 2.0, 4.0, c)) {
            Vec3 flat = e.position().subtract(p.position()).multiply(1, 0, 1);
            double f = 1.0 - 0.5 * Math.min(1.0, flat.length() / radius);
            SkillTargets.hurt(level, e, p, Element.FIRE, (float) (s.damage() * f));
            SkillTargets.knock(e, flat.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : flat.normalize(), s.speed() * f, 0.35 + 0.25 * f);
        }
        level.playSound(null, c.x, c.y, c.z, SkillsRegistry.snd("flame_ring.burst"), SoundSource.PLAYERS, 4.0f, 1.0f);
        Vec3 feet = SkillAim.groundBelow(level, p, p.position());
        SkillEvents.send(level, s, SkillEvents.BURST, p, feet, Vec3.ZERO, (float) radius, 0, 18, -1);
    }

    /** 뇌격: 즉시 번개 + 연쇄 */
    static void chainBolt(ServerLevel level, ServerPlayer p, Skill s) {
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        Vec3 start = SkillAim.handPos(level, p, eye, look, 0.3, 0);
        Vec3 dir = SkillAim.aimDirection(level, p, eye, look, start, s.range());
        Vec3 end = start.add(dir.scale(s.range()));
        BlockHitResult bh = SubLevels.clip(level, new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (bh.getType() != HitResult.Type.MISS) end = bh.getLocation();

        LivingEntity first = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(start, end).inflate(s.hit + 1), x -> SkillTargets.canHit(p, x))) {
            Optional<Vec3> hit = e.getBoundingBox().inflate(s.hit).clip(start, end);
            if (hit.isPresent() && hit.get().distanceToSqr(start) < best) {
                best = hit.get().distanceToSqr(start);
                first = e;
            }
        }
        if (first == null) {
            SkillEvents.send(level, s, SkillEvents.BOLT, p, start, end, 1.2f, 0, 0, -1);
            level.playSound(null, end.x, end.y, end.z, SkillsRegistry.snd("lightning.zap"), SoundSource.PLAYERS, 2.0f, 1.0f);
            return;
        }
        Set<LivingEntity> done = new HashSet<>();
        LivingEntity cur = first;
        Vec3 from = start;
        float dmg = s.damage();
        int jumps = (int) Math.round(s.speed());
        for (int i = 0; i <= jumps && cur != null; i++) {
            Vec3 to = cur.getBoundingBox().getCenter();
            SkillEvents.send(level, s, SkillEvents.BOLT, p, from, to, i == 0 ? 1.2f : 0.8f, 0, 0, cur.getId());
            SkillTargets.hurt(level, cur, p, Element.LIGHTNING, dmg);
            SkillTargets.stun(cur, 10);
            level.playSound(null, to.x, to.y, to.z, SkillsRegistry.snd("lightning.zap"), SoundSource.PLAYERS, 2.0f, 0.9f + i * 0.1f);
            done.add(cur);
            from = to;
            dmg *= 0.75f;
            LivingEntity next = null;
            double nd = s.radius() * s.radius();
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, cur.getBoundingBox().inflate(s.radius()), x -> SkillTargets.canHit(p, x))) {
                if (done.contains(e)) continue;
                double d = e.distanceToSqr(cur);
                if (d < nd && SkillTargets.canSee(level, cur, to, e)) {
                    nd = d;
                    next = e;
                }
            }
            cur = next;
        }
    }

    /** 섬광 이동: 순간이동 + 출발·도착·지나간 길에 번개 */
    static void blink(ServerLevel level, ServerPlayer p, Skill s) {
        Vec3 origin = p.position();
        Vec3 dest = SkillAim.blinkDestination(level, p, origin, p.getEyePosition(), p.getLookAngle(), s.range());
        if (dest == null) {
            p.displayClientMessage(Component.translatable("msg.blackocean_skills.no_space"), true);
            SkillServer.resetCooldown(p, s);
            return;
        }
        if (p.isPassenger()) p.stopRiding();
        p.connection.teleport(dest.x, dest.y, dest.z, p.getYRot(), p.getXRot());
        p.fallDistance = 0;
        Vec3 a = origin.add(0, 0.9, 0), b = dest.add(0, 0.9, 0);
        double r = s.radius();
        Set<LivingEntity> hit = new HashSet<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(a, b).inflate(r + 1), x -> SkillTargets.canHit(p, x))) {
            AABB box = e.getBoundingBox();
            boolean near = box.getCenter().distanceTo(a) <= r + 0.5 || box.getCenter().distanceTo(b) <= r + 0.5
                    || box.inflate(1.2).clip(a, b).isPresent();
            if (near && hit.add(e)) {
                SkillTargets.hurt(level, e, p, Element.LIGHTNING, s.damage());
                SkillTargets.stun(e, 15);
            }
        }
        level.playSound(null, a.x, a.y, a.z, SkillsRegistry.snd("lightning.blink"), SoundSource.PLAYERS, 2.5f, 1.0f);
        level.playSound(null, b.x, b.y, b.z, SkillsRegistry.snd("lightning.zap"), SoundSource.PLAYERS, 2.5f, 0.8f);
        SkillEvents.send(level, s, SkillEvents.BLINK, p, a, b, (float) r, 0, 0, -1);
    }

    /** 방전: 주변 모든 적에게 번개 */
    static void discharge(ServerLevel level, ServerPlayer p, Skill s) {
        Vec3 c = p.position().add(0, 1.0, 0);
        int n = 0;
        for (LivingEntity e : SkillTargets.inSphere(level, p, c, s.radius(), true)) {
            SkillTargets.hurt(level, e, p, Element.LIGHTNING, s.damage());
            SkillTargets.stun(e, s.durationTicks());
            if (n++ < 16) SkillEvents.send(level, s, SkillEvents.BOLT, p, c, e.getBoundingBox().getCenter(), 0.9f, 0, 0, e.getId());
        }
        level.playSound(null, c.x, c.y, c.z, SkillsRegistry.snd("lightning.discharge"), SoundSource.PLAYERS, 4.0f, 1.0f);
        SkillEvents.send(level, s, SkillEvents.BURST, p, SkillAim.groundBelow(level, p, p.position()), Vec3.ZERO, (float) s.radius(), 0, 14, -1);
    }

    /** 폭풍 방벽: 강한 돌풍 + 주변의 적 투사체 제거 */
    static void gust(ServerLevel level, ServerPlayer p, Skill s) {
        Vec3 c = p.position().add(0, 1.0, 0);
        double r = s.radius();
        for (LivingEntity e : SkillTargets.inSphere(level, p, c, r, true)) {
            double f = 1.0 - 0.5 * SkillTargets.edgeFraction(e, c, r);
            SkillTargets.hurt(level, e, p, Element.WIND, (float) (s.damage() * f));
            Vec3 flat = e.position().subtract(p.position()).multiply(1, 0, 1);
            SkillTargets.knock(e, flat.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : flat.normalize(), s.speed() * f, 0.55);
        }
        for (Projectile pr : level.getEntitiesOfClass(Projectile.class, new AABB(c, c).inflate(r), x -> x.getOwner() != p && !(x instanceof ThrownTrident) && !(x instanceof FishingHook)
                && (x.getOwner() == null || SkillTargets.canHit(p, x.getOwner())))) {
            pr.discard();
        }
        level.playSound(null, c.x, c.y, c.z, SkillsRegistry.snd("wind.burst"), SoundSource.PLAYERS, 4.0f, 1.0f);
        SkillEvents.send(level, s, SkillEvents.BURST, p, SkillAim.groundBelow(level, p, p.position()), Vec3.ZERO, (float) r, 0, 16, -1);
    }

    // =====================================================================
    // 지속 스킬
    // =====================================================================

    abstract static class Effect {
        final ServerLevel level;
        final UUID ownerId;
        final int ownerEntity;
        final Skill skill;
        int age;

        Effect(ServerLevel level, ServerPlayer owner, Skill skill) {
            this.level = level;
            this.ownerId = owner.getUUID();
            this.ownerEntity = owner.getId();
            this.skill = skill;
        }

        /** @return true 면 끝 */
        boolean tick(MinecraftServer server) {
            ServerPlayer o = server.getPlayerList().getPlayer(ownerId);
            // 나갔거나, 다른 차원으로 갔거나, 죽고 다시 태어남(새 몸) → 끝
            if (o == null || o.level() != level || o.getId() != ownerEntity) {
                abort();
                return true;
            }
            age++;
            return step(o);
        }

        abstract boolean step(ServerPlayer owner);

        /** 시전자가 사라져 중간에 끝날 때 */
        void abort() {}

        void sound(Vec3 at, String name, float vol, float pitch) {
            level.playSound(null, at.x, at.y, at.z, SkillsRegistry.snd(name), SoundSource.PLAYERS, vol, pitch);
        }
    }

    /** 불기둥: 솟구치며 피해 + 띄움, 남아서 계속 태움 */
    static final class Pillar extends Effect {
        static final double HEIGHT = 9.0;
        final Vec3 base;
        final int duration;

        Pillar(ServerLevel level, ServerPlayer p, Skill s, Vec3 base) {
            super(level, p, s);
            this.base = base;
            this.duration = s.durationTicks();
            double r = s.radius();
            for (LivingEntity e : SkillTargets.inCylinder(level, p, base, r, 0.5, HEIGHT, base.add(0, 1, 0))) {
                SkillTargets.hurt(level, e, p, Element.FIRE, s.damage(), true);
                Vec3 out = e.position().subtract(base).multiply(1, 0, 1);
                SkillTargets.knock(e, out.lengthSqr() < 1.0E-4 ? Vec3.ZERO : out.normalize(), 0.25, 1.0);
            }
            sound(base, "fire_pillar.erupt", 4.0f, 1.0f);
            SkillEvents.send(level, s, SkillEvents.ERUPT, p, base, Vec3.ZERO, (float) r, (float) HEIGHT, duration, -1);
        }

        @Override
        boolean step(ServerPlayer o) {
            if (age >= duration) return true;
            if (age % 10 == 0) {
                for (LivingEntity e : SkillTargets.inCylinder(level, o, base, skill.radius() * 0.85, 0.5, HEIGHT, base.add(0, 1, 0))) {
                    SkillTargets.hurt(level, e, o, Element.FIRE, skill.damage2(), true);
                    SkillTargets.knock(e, Vec3.ZERO, 0, 0.18);
                }
            }
            return false;
        }
    }

    /** 작열의 태양이 남긴 불바다 */
    static final class Field extends Effect {
        final Vec3 base;
        final float radius;
        final int duration;

        Field(ServerLevel level, ServerPlayer p, Skill s, Vec3 base, float radius) {
            super(level, p, s);
            this.base = base;
            this.radius = radius;
            this.duration = s.durationTicks();
            SkillEvents.send(level, s, SkillEvents.FIELD, p, base, Vec3.ZERO, radius, 0, duration, -1);
        }

        @Override
        boolean step(ServerPlayer o) {
            if (age >= duration) return true;
            if (age % 10 == 0) {
                for (LivingEntity e : SkillTargets.inCylinder(level, o, base, radius, 1.0, 3.0, base.add(0, 1, 0))) {
                    SkillTargets.hurt(level, e, o, Element.FIRE, skill.damage2(), true);
                }
            }
            return false;
        }
    }

    /** 낙뢰: 조준한 곳에 번개가 연달아 */
    static final class Strikes extends Effect {
        final Vec3 center;
        final int count, interval;
        int fired;

        Strikes(ServerLevel level, ServerPlayer p, Skill s, Vec3 center) {
            super(level, p, s);
            this.center = center;
            this.count = Math.max(1, (int) Math.round(s.speed()));
            this.interval = Math.max(2, Math.max(count, s.durationTicks()) / count);
            strike(p);
        }

        void strike(ServerPlayer o) {
            double r = skill.radius();
            Vec3 at = center;
            if (fired > 0) {
                double a = o.getRandom().nextDouble() * Math.PI * 2, d = Math.sqrt(o.getRandom().nextDouble()) * r * 0.7;
                at = SkillAim.groundBelow(level, o, center.add(Math.cos(a) * d, 2.0, Math.sin(a) * d));
            }
            double hr = Math.max(1.5, r * 0.8);
            for (LivingEntity e : SkillTargets.inCylinder(level, o, at, hr, 1.0, 5.0, at.add(0, 1, 0))) {
                SkillTargets.hurt(level, e, o, Element.LIGHTNING, skill.damage(), true);
                SkillTargets.stun(e, 15);
            }
            sound(at, "lightning.thunder", 3.0f, 1.25f);
            SkillEvents.send(level, skill, SkillEvents.STRIKE, o, at, Vec3.ZERO, (float) hr, 0, 0, -1);
            fired++;
        }

        @Override
        boolean step(ServerPlayer o) {
            if (fired >= count) return true;
            if (age % interval == 0) strike(o);
            return fired >= count;
        }
    }

    /** [궁] 뇌신의 심판: 낙뢰를 퍼붓고 마지막에 거대한 번개 */
    static final class Storm extends Effect {
        final Vec3 center;
        final int duration;

        Storm(ServerLevel level, ServerPlayer p, Skill s, Vec3 center) {
            super(level, p, s);
            this.center = center;
            this.duration = Math.max(20, s.durationTicks());
            sound(center, "lightning.thunder", 6.0f, 0.6f);
            SkillEvents.send(level, s, SkillEvents.STORM, p, center, Vec3.ZERO, (float) s.radius(), 0, duration, -1);
        }

        @Override
        boolean step(ServerPlayer o) {
            double r = skill.radius();
            if (age < duration && age % 4 == 0) {
                List<LivingEntity> in = SkillTargets.inCylinder(level, o, center, r, 2.0, 14.0, null);
                Vec3 at;
                if (!in.isEmpty() && o.getRandom().nextFloat() < 0.7f) {
                    at = in.get(o.getRandom().nextInt(in.size())).position();
                } else {
                    double a = o.getRandom().nextDouble() * Math.PI * 2, d = Math.sqrt(o.getRandom().nextDouble()) * r;
                    at = center.add(Math.cos(a) * d, 3.0, Math.sin(a) * d);
                }
                at = SkillAim.groundBelow(level, o, at.add(0, 0.5, 0));
                for (LivingEntity e : SkillTargets.inCylinder(level, o, at, 2.2, 1.0, 5.0, at.add(0, 1, 0))) {
                    SkillTargets.hurt(level, e, o, Element.LIGHTNING, skill.damage2(), true);
                    SkillTargets.stun(e, 10);
                }
                if (age % 8 == 0) sound(at, "lightning.thunder", 3.5f, 1.1f + o.getRandom().nextFloat() * 0.3f);
                else sound(at, "lightning.zap", 3.0f, 0.8f);
                SkillEvents.send(level, skill, SkillEvents.STRIKE, o, at, Vec3.ZERO, 2.2f, 0, 0, -1);
            }
            if (age == duration) {
                double big = Math.max(3.0, r * 0.45);
                for (LivingEntity e : SkillTargets.inCylinder(level, o, center, big, 2.0, 12.0, null)) {
                    SkillTargets.hurt(level, e, o, Element.LIGHTNING, skill.damage(), true);
                    SkillTargets.stun(e, 30);
                    SkillTargets.knock(e, Vec3.ZERO, 0, 0.9);
                }
                sound(center, "lightning.thunder", 10.0f, 0.55f);
                SkillEvents.send(level, skill, SkillEvents.STRIKE, o, center, Vec3.ZERO, (float) big, 1f, 0, -1);
                return true;
            }
            return false;
        }
    }

    /** 질풍 돌진 */
    static final class Dash extends Effect {
        final Vec3 dir;
        final int ticks;
        final double perTick;
        final Set<Integer> hit = new HashSet<>();

        Dash(ServerLevel level, ServerPlayer p, Skill s) {
            super(level, p, s);
            this.dir = SkillAim.flatLook(p.getLookAngle(), p.getYRot());
            this.ticks = Math.max(2, s.durationTicks());
            this.perTick = s.range() / ticks;
            if (p.isPassenger()) p.stopRiding();
            sound(p.position(), "wind.dash", 2.5f, 1.0f);
            SkillEvents.send(level, s, SkillEvents.DASH, p, p.position(), dir, (float) s.radius(), (float) s.range(), ticks, -1);
            push(p);
        }

        void push(ServerPlayer o) {
            SkillTargets.setVelocity(o, dir.scale(perTick).add(0, 0.04, 0));
            o.fallDistance = 0;
        }

        @Override
        boolean step(ServerPlayer o) {
            if (!o.isAlive()) return true;
            o.fallDistance = 0;
            if (age < ticks) {
                push(o);
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, o.getBoundingBox().inflate(skill.radius()), x -> SkillTargets.canHit(o, x))) {
                    if (!hit.add(e.getId())) continue;
                    SkillTargets.hurt(level, e, o, Element.WIND, skill.damage(), true);
                    SkillTargets.knock(e, dir, 0.5, 0.8);
                }
            } else if (age == ticks) {
                SkillTargets.setVelocity(o, dir.scale(0.3).add(0, 0.05, 0));
            }
            return age > ticks + 30; // 30틱 동안 낙하 피해 없음
        }
    }

    /** 회오리 / [궁] 천공의 대폭풍 */
    static final class Tornado extends Effect {
        final Vec3 center;
        final boolean big;
        final double r, h;
        final int duration;

        Tornado(ServerLevel level, ServerPlayer p, Skill s, Vec3 center) {
            super(level, p, s);
            this.center = center;
            this.big = s.ultimate();
            this.r = s.radius();
            this.h = big ? 22.0 : 7.0;
            this.duration = Math.max(10, s.durationTicks());
            sound(center, "wind.tornado", big ? 6.0f : 3.0f, big ? 0.7f : 1.0f);
            SkillEvents.send(level, s, SkillEvents.TORNADO, p, center, Vec3.ZERO, (float) r, (float) h, duration, -1);
        }

        @Override
        void abort() {
            double pr = r * (big ? 2.3 : 1.6);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(pr, 0, pr).expandTowards(0, h + 2, 0))) {
                e.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 70, 0, false, false, true));
            }
        }

        @Override
        boolean step(ServerPlayer o) {
            if (age % 60 == 0 && age < duration - 20) sound(center, "wind.tornado", big ? 6.0f : 3.0f, big ? 0.7f : 1.0f);
            double pullR = r * (big ? 2.3 : 1.6);
            List<LivingEntity> in = SkillTargets.inCylinder(level, o, center, pullR, 1.0, h, center.add(0, 1, 0));
            if (age >= duration) {
                for (LivingEntity e : in) {
                    Vec3 flat = e.position().subtract(center).multiply(1, 0, 1);
                    SkillTargets.hurt(level, e, o, Element.WIND, skill.damage(), true);
                    SkillTargets.setVelocity(e, (flat.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : flat.normalize()).scale(big ? 2.0 : 1.4).add(0, 0.45, 0));
                    e.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 70, 0, false, false, true));
                }
                sound(center, "wind.burst", big ? 6.0f : 3.0f, 0.8f);
                return true;
            }
            boolean hurtTick = age % 10 == 0;
            for (LivingEntity e : in) {
                Vec3 toC = center.subtract(e.position()).multiply(1, 0, 1);
                double d = toC.length();
                Vec3 n = d < 1.0E-3 ? Vec3.ZERO : toC.scale(1.0 / d);
                Vec3 tangent = new Vec3(-n.z, 0, n.x);
                double pull = Math.min(big ? 0.32 : 0.22, d * 0.12);
                double vy = e.getDeltaMovement().y;
                if (d <= r + 0.5) {
                    vy = e.getY() < center.y + h * 0.7 ? Math.max(vy, big ? 0.32 : 0.22) : Math.min(vy, 0.04);
                    e.fallDistance = 0;
                    if (hurtTick) SkillTargets.hurt(level, e, o, Element.WIND, skill.damage2(), true);
                }
                SkillTargets.setVelocity(e, n.scale(pull).add(tangent.scale(big ? 0.35 : 0.25)).add(0, vy, 0));
            }
            return false;
        }
    }

    /** 수류 분사: 조준하는 방향으로 계속 물줄기 */
    static final class Jet extends Effect {
        final int ticks;
        final Map<Integer, Integer> nextHit = new HashMap<>();

        Jet(ServerLevel level, ServerPlayer p, Skill s) {
            super(level, p, s);
            this.ticks = Math.max(4, s.durationTicks());
            sound(p.position(), "water.jet", 2.5f, 1.0f);
            SkillEvents.send(level, s, SkillEvents.JET, p, p.position(), Vec3.ZERO, (float) s.range(), (float) s.radius(), ticks, -1);
        }

        @Override
        boolean step(ServerPlayer o) {
            if (!o.isAlive() || age > ticks) return true;
            Vec3 eye = o.getEyePosition(), look = o.getLookAngle();
            Vec3 start = SkillAim.handPos(level, o, eye, look, 0.3, 0);
            Vec3 dir = SkillAim.aimDirection(level, o, eye, look, start, skill.range());
            Vec3 end = start.add(dir.scale(skill.range()));
            BlockHitResult bh = SubLevels.clip(level, new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, o));
            if (bh.getType() != HitResult.Type.MISS) end = bh.getLocation();
            double rad = skill.radius();
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(start, end).inflate(rad + 1), x -> SkillTargets.canHit(o, x))) {
                if (e.getBoundingBox().inflate(rad).clip(start, end).isEmpty() && !e.getBoundingBox().inflate(rad).contains(start)) continue;
                int id = e.getId();
                if (nextHit.getOrDefault(id, 0) <= age) {
                    nextHit.put(id, age + 5);
                    SkillTargets.hurt(level, e, o, Element.WATER, skill.damage(), true);
                    SkillTargets.slow(e, 20, 1);
                }
                Vec3 v = e.getDeltaMovement();
                SkillTargets.setVelocity(e, new Vec3(dir.x * skill.speed(), Math.max(v.y, Math.min(0.12, dir.y * skill.speed() + 0.05)), dir.z * skill.speed()));
            }
            return false;
        }
    }

    /** 물의 감옥: 끌어당겨 가두고 마지막에 터짐 */
    static final class Prison extends Effect {
        final Vec3 center;
        final int duration;

        Prison(ServerLevel level, ServerPlayer p, Skill s, Vec3 center) {
            super(level, p, s);
            this.center = center;
            this.duration = Math.max(10, s.durationTicks());
            sound(center, "water.splash", 3.0f, 0.8f);
            SkillEvents.send(level, s, SkillEvents.PRISON, p, center, Vec3.ZERO, (float) s.radius(), 0, duration, -1);
        }

        @Override
        boolean step(ServerPlayer o) {
            double r = skill.radius();
            Vec3 core = center.add(0, 1.2, 0);
            List<LivingEntity> in = SkillTargets.inCylinder(level, o, center, r * 1.4, 1.0, 4.5, center.add(0, 1, 0));
            if (age >= duration) {
                for (LivingEntity e : in) {
                    Vec3 out = e.position().subtract(center).multiply(1, 0, 1);
                    SkillTargets.hurt(level, e, o, Element.WATER, skill.damage(), true);
                    SkillTargets.knock(e, out.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : out.normalize(), 0.6, 0.7);
                }
                sound(center, "water.splash", 4.0f, 0.7f);
                return true;
            }
            for (LivingEntity e : in) {
                Vec3 to = core.subtract(e.position());
                Vec3 v = to.scale(0.25);
                if (v.length() > 0.6) v = v.normalize().scale(0.6);
                SkillTargets.setVelocity(e, v);
                e.fallDistance = 0;
                SkillTargets.slow(e, 10, 3);
                if (age % 10 == 0) SkillTargets.hurt(level, e, o, Element.WATER, skill.damage2(), true);
            }
            return false;
        }
    }

    /** 해일: 앞으로 밀려가는 파도 벽 */
    static final class Wave extends Effect {
        final Vec3 start, dir, side;
        final double width, range, speed;
        final int ticks;
        final Set<Integer> hit = new HashSet<>();

        Wave(ServerLevel level, ServerPlayer p, Skill s) {
            super(level, p, s);
            this.start = SkillAim.groundBelow(level, p, p.position());
            this.dir = SkillAim.flatLook(p.getLookAngle(), p.getYRot());
            this.side = new Vec3(-dir.z, 0, dir.x);
            this.width = s.radius();
            Vec3 from = start.add(0, 1.0, 0), to = from.add(dir.scale(s.range()));
            BlockHitResult wall = SubLevels.clip(level, new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            this.range = wall.getType() == HitResult.Type.MISS ? s.range() : Math.max(1.0, wall.getLocation().distanceTo(from));
            this.speed = Math.max(0.2, s.speed());
            this.ticks = (int) Math.ceil(range / speed);
            sound(start, "water.wave", 4.0f, 1.0f);
            SkillEvents.send(level, s, SkillEvents.WAVE, p, start, dir, (float) width, (float) range, ticks, -1);
        }

        @Override
        boolean step(ServerPlayer o) {
            if (age > ticks) return true;
            double d = age * speed;
            Vec3 front = start.add(dir.scale(d));
            AABB box = new AABB(front, front).inflate(width + 2, 0, width + 2).expandTowards(0, 4.5, 0).expandTowards(0, -1.5, 0);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, x -> SkillTargets.canHit(o, x))) {
                Vec3 rel = e.position().subtract(start);
                double along = rel.dot(dir), lat = Math.abs(rel.dot(side));
                if (along < d - 2.0 || along > d + 0.8 || lat > width + e.getBbWidth() * 0.5) continue;
                if (hit.add(e.getId())) {
                    SkillTargets.hurt(level, e, o, Element.WATER, skill.damage(), true);
                    SkillTargets.slow(e, 30, 1);
                }
                SkillTargets.setVelocity(e, dir.scale(speed * 1.15).add(0, e.onGround() ? 0.3 : Math.max(0.05, e.getDeltaMovement().y), 0));
            }
            return false;
        }
    }

    /** [궁] 해룡 강림: 솟아올라 내리꽂힘 + 소용돌이 */
    static final class Dragon extends Effect {
        static final int FLIGHT = 26;
        final Vec3 target;
        final int linger;

        Dragon(ServerLevel level, ServerPlayer p, Skill s, Vec3 target) {
            super(level, p, s);
            this.target = target;
            this.linger = s.durationTicks();
            sound(p.position(), "water.dragon", 6.0f, 1.0f);
            SkillEvents.send(level, s, SkillEvents.DRAGON, p, p.position().add(0, 1, 0), target, (float) s.radius(), linger, FLIGHT, -1);
        }

        @Override
        boolean step(ServerPlayer o) {
            double r = skill.radius();
            if (age == FLIGHT) {
                Vec3 c = target.add(0, 1.0, 0);
                for (LivingEntity e : SkillTargets.inCylinder(level, o, target, r, 2.0, 6.0, target.add(0, 1, 0))) {
                    double f = SkillTargets.edgeFraction(e, c, r);
                    SkillTargets.hurt(level, e, o, Element.WATER, (float) (skill.damage() * (1.0 - 0.5 * f)), true);
                    Vec3 out = e.position().subtract(target).multiply(1, 0, 1);
                    SkillTargets.knock(e, out.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : out.normalize(), 0.8, 1.1);
                    SkillTargets.slow(e, 80, 2);
                }
                sound(target, "water.splash", 8.0f, 0.6f);
                sound(target, "water.wave", 6.0f, 0.8f);
            } else if (age > FLIGHT && age % 10 == 0) {
                for (LivingEntity e : SkillTargets.inCylinder(level, o, target, r, 1.0, 4.0, target.add(0, 1, 0))) {
                    Vec3 to = target.subtract(e.position()).multiply(1, 0, 1);
                    if (to.lengthSqr() > 1.0E-3) SkillTargets.knock(e, to.normalize(), 0.25, 0);
                    SkillTargets.hurt(level, e, o, Element.WATER, skill.damage2(), true);
                }
            }
            return age >= FLIGHT + linger;
        }
    }
}
