package com.blackocean.skills;

import com.blackocean.skills.fire.FireBulletProjectile;
import com.blackocean.skills.fire.FireFistProjectile;
import com.blackocean.skills.network.CastStartPayload;
import com.blackocean.skills.network.CooldownSyncPayload;
import com.blackocean.skills.network.SkillFxPayload;
import com.blackocean.skills.network.SkillStatsPayload;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 서버 쪽 스킬 관리 (4종 공통): 쿨타임 확인 → "시전 시작" 신호 → 준비 시간 뒤 발동.
 * <pre>
 *  1 화염 주먹 : 큰 투사체 1발
 *  2 불꽃 탄환 : 작은 투사체 여러 발 (양손 번갈아)
 *  3 불기둥   : 조준한 바닥에 경고 → 솟구치며 피해 + 띄움, 잠시 남아 계속 태움
 *  4 화염 고리 : 몸 주위로 모았다가 사방으로 불의 파도 (피해 + 밀쳐냄)
 * </pre>
 */
public final class SkillServer {
    private static final int N = Skill.VALUES.length;
    private static final Map<UUID, long[]> READY_AT = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static final List<Pillar> PILLARS = new ArrayList<>();
    /** 예약된 입력 (플레이어마다 1개, 마지막에 누른 것). 쓸 수 있게 되는 순간 바로 발동. */
    private static final Map<UUID, Buffered> BUFFERED = new HashMap<>();

    /**
     * 입력 예약 허용 시간 (틱). 쿨타임이 이만큼 이하로 남았거나 다른 스킬을 모으는 중이면
     * 누른 키를 버리지 않고 기억했다가 바로 발동한다. (서버가 조금 느려 화면보다 늦게 끝나도 씹히지 않게)
     */
    public static final int BUFFER_TICKS = 10;

    private record Buffered(Skill skill, long expireAt) {}

    /**
     * @param cooldown 이 시전으로 걸린 쿨타임. 발사 전에 취소되면 되돌려 준다.
     * @param shot     불꽃 탄환: 몇 번째 발 (0 = 준비 중)
     * @param target   불기둥: 솟을 자리
     */
    private record Pending(UUID player, Skill skill, long fireAt, int cooldown, int shot, @Nullable Vec3 target) {}

    private static final class Pillar {
        final ServerLevel level;
        final UUID owner;
        final Vec3 base;
        final double radius, height;
        final long end;
        long nextBurn;

        Pillar(ServerLevel level, UUID owner, Vec3 base, double radius, double height, long now, int duration) {
            this.level = level;
            this.owner = owner;
            this.base = base;
            this.radius = radius;
            this.height = height;
            this.end = now + duration;
            this.nextBurn = now + 10;
        }
    }

    private SkillServer() {}

    // =====================================================================
    // 시전
    // =====================================================================

    /** 클라이언트가 1~4 키를 눌러 보낸 요청. 메인 스레드에서 호출된다. */
    public static void tryCast(ServerPlayer p, Skill skill) {
        if (!skill.enabled()) {
            p.displayClientMessage(Component.translatable("msg.blackocean_skills.disabled", Component.translatable(skill.translationKey())), true);
            PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(skill.ordinal(), 0, 0, true));
            return;
        }
        int left = remainingTicks(p, skill);
        boolean charging = isCharging(p.getUUID());
        if ((left > 0 && left <= BUFFER_TICKS) || (charging && left <= BUFFER_TICKS)) {
            // 곧 쓸 수 있음 → 예약 (마지막에 누른 스킬이 이김)
            long now = p.server.getTickCount();
            BUFFERED.put(p.getUUID(), new Buffered(skill, now + Math.max(left, 0) + BUFFER_TICKS + 20));
            return;
        }
        if (left > 0) {
            p.displayClientMessage(Component.translatable("msg.blackocean_skills.cooldown",
                    Component.translatable(skill.translationKey()), String.format("%.1f", left / 20.0)), true);
            PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(skill.ordinal(), left, skill.cooldownTicks(), true));
            return;
        }
        if (!cast(p, skill, true)) {
            PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(skill.ordinal(), 0, 0, true));
        }
    }

    /**
     * 실제 시전. 명령어·API 에서도 부른다.
     * @param useCooldown false 면 쿨타임을 확인하지도, 걸지도 않음 (별풍선 보상 등)
     * @return 시전을 시작했으면 true
     */
    public static boolean cast(ServerPlayer p, Skill skill, boolean useCooldown) {
        if (!p.isAlive() || p.isSpectator() || isCharging(p.getUUID())) return false;

        Vec3 target = null;
        if (skill == Skill.FIRE_PILLAR) {
            target = pillarTarget(p);
            if (target == null) {
                p.displayClientMessage(Component.translatable("msg.blackocean_skills.no_target"), true);
                return false;
            }
        }

        long now = p.server.getTickCount();
        int charge = skill.chargeTicks();
        int cooldown = useCooldown ? skill.cooldownTicks() : 0;
        if (useCooldown) {
            readyAt(p.getUUID())[skill.ordinal()] = now + cooldown;
        }
        PENDING.add(new Pending(p.getUUID(), skill, now + charge, cooldown, 0, target));

        Vec3 at = target != null ? target : p.position();
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new CastStartPayload(p.getId(), skill.ordinal(), charge, at.x, at.y, at.z,
                skill == Skill.FIRE_PILLAR ? SkillsConfig.PILLAR_RADIUS.get().floatValue() : 0f));
        if (useCooldown) {
            PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(skill.ordinal(), cooldown, cooldown, false));
        }

        ServerLevel level = p.serverLevel();
        switch (skill) {
            case FIRE_PILLAR -> level.playSound(null, at.x, at.y, at.z, SkillsRegistry.FIRE_PILLAR_WARN.get(), SoundSource.PLAYERS, 2.0f, 1.0f);
            case FLAME_RING -> level.playSound(null, p.getX(), p.getY(), p.getZ(), SkillsRegistry.FLAME_RING_CHARGE.get(), SoundSource.PLAYERS, 2.0f, 1.0f);
            default -> { }
        }
        return true;
    }

    public static int remainingTicks(ServerPlayer p, Skill skill) {
        long[] r = READY_AT.get(p.getUUID());
        if (r == null) return 0;
        long left = r[skill.ordinal()] - p.server.getTickCount();
        return left > 0 ? (int) left : 0;
    }

    public static void resetCooldown(ServerPlayer p, @Nullable Skill skill) {
        long[] r = READY_AT.get(p.getUUID());
        if (r != null) {
            if (skill == null) java.util.Arrays.fill(r, 0L);
            else r[skill.ordinal()] = 0L;
        }
        PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(skill == null ? -1 : skill.ordinal(), 0, 0, false));
    }

    private static long[] readyAt(UUID id) {
        return READY_AT.computeIfAbsent(id, k -> new long[N]);
    }

    /** 준비 동작 중(아직 발동 전)이면 다른 스킬을 겹쳐 쓸 수 없다. 탄환 연사 중에는 다른 스킬 가능. */
    private static boolean isCharging(UUID id) {
        for (Pending x : PENDING) {
            if (x.player().equals(id) && x.shot() == 0) return true;
        }
        return false;
    }

    // =====================================================================
    // 매 틱
    // =====================================================================

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();

        if (!PENDING.isEmpty()) {
            List<Pending> next = new ArrayList<>();
            Iterator<Pending> it = PENDING.iterator();
            while (it.hasNext()) {
                Pending x = it.next();
                if (now < x.fireAt()) continue;
                it.remove();
                ServerPlayer p = server.getPlayerList().getPlayer(x.player());
                if (p == null || !p.isAlive() || p.isSpectator()) {
                    if (x.shot() == 0 && x.cooldown() > 0) {
                        // 준비 중에 죽음 등으로 발동 못 함 → 쿨타임 돌려줌
                        long[] r = READY_AT.get(x.player());
                        if (r != null) r[x.skill().ordinal()] = 0L;
                        if (p != null) resetCooldown(p, x.skill());
                    }
                    continue;
                }
                switch (x.skill()) {
                    case FIRE_FIST -> launchFist(p);
                    case FIRE_BULLETS -> {
                        launchBullet(p, x.shot());
                        if (x.shot() + 1 < SkillsConfig.BULLETS_COUNT.get()) {
                            next.add(new Pending(x.player(), x.skill(), now + SkillsConfig.BULLETS_INTERVAL.get(), 0, x.shot() + 1, null));
                        }
                    }
                    case FIRE_PILLAR -> eruptPillar(p, x.target());
                    case FLAME_RING -> burstRing(p);
                }
            }
            PENDING.addAll(next);
        }

        if (!BUFFERED.isEmpty()) tickBuffered(server, now);
        if (!PILLARS.isEmpty()) tickPillars(server, now);
    }

    private static void tickBuffered(MinecraftServer server, long now) {
        Iterator<Map.Entry<UUID, Buffered>> it = BUFFERED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Buffered> e = it.next();
            Buffered b = e.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null || now > b.expireAt()) {
                it.remove();
                if (p != null) PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(b.skill().ordinal(),
                        remainingTicks(p, b.skill()), b.skill().cooldownTicks(), true));
                continue;
            }
            if (isCharging(p.getUUID()) || remainingTicks(p, b.skill()) > 0) continue;
            it.remove();
            if (!b.skill().enabled() || !cast(p, b.skill(), true)) {
                PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(b.skill().ordinal(), 0, 0, true));
            }
        }
    }

    /** 접속할 때 조준 표시용 서버 설정값을 보내 준다. */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) sendStats(p);
    }

    public static void sendStats(ServerPlayer p) {
        PacketDistributor.sendToPlayer(p, new SkillStatsPayload(
                SkillsConfig.RANGE.get().floatValue(), SkillsConfig.HIT_RADIUS.get().floatValue(), SkillsConfig.SPLASH_RADIUS.get().floatValue(),
                SkillsConfig.BULLETS_RANGE.get().floatValue(), SkillsConfig.BULLETS_SPLASH_RADIUS.get().floatValue(), SkillsConfig.BULLETS_SPREAD.get().floatValue(),
                SkillsConfig.PILLAR_RANGE.get().floatValue(), SkillsConfig.PILLAR_RADIUS.get().floatValue(), SkillsConfig.PILLAR_HEIGHT.get().floatValue(),
                SkillsConfig.RING_RADIUS.get().floatValue()));
    }

    // =====================================================================
    // 1. 화염 주먹 / 2. 불꽃 탄환
    // =====================================================================

    private static void launchFist(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        Vec3 look = p.getLookAngle();
        Vec3 start = handPos(level, p, look, 0.3);
        Vec3 dir = aimDirection(level, p, p.getEyePosition(), look, start, SkillsConfig.RANGE.get());
        level.addFreshEntity(FireFistProjectile.create(level, p, start, dir));
        level.playSound(null, start.x, start.y, start.z, SkillsRegistry.FIRE_FIST_LAUNCH.get(), SoundSource.PLAYERS, 2.5f, 1.0f);
    }

    private static void launchBullet(ServerPlayer p, int shot) {
        ServerLevel level = p.serverLevel();
        Vec3 look = p.getLookAngle();
        double side = (shot % 2 == 0) ? 0.38 : -0.38; // 오른손, 왼손 번갈아
        Vec3 start = handPos(level, p, look, side);
        Vec3 dir = aimDirection(level, p, p.getEyePosition(), look, start, SkillsConfig.BULLETS_RANGE.get());
        double spread = Math.toRadians(SkillsConfig.BULLETS_SPREAD.get());
        if (spread > 0) {
            dir = dir.xRot((float) ((p.getRandom().nextDouble() - 0.5) * 2 * spread))
                    .yRot((float) ((p.getRandom().nextDouble() - 0.5) * 2 * spread)).normalize();
        }
        level.addFreshEntity(FireBulletProjectile.createBullet(level, p, start, dir));
        level.playSound(null, start.x, start.y, start.z, SkillsRegistry.FIRE_BULLETS_SHOT.get(), SoundSource.PLAYERS,
                1.6f, 0.92f + p.getRandom().nextFloat() * 0.16f);
    }

    /** 손 위치 (side: + 오른쪽, - 왼쪽). 벽 앞에서 쏘면 벽 안에서 생기지 않게 벽 앞으로 당긴다. */
    private static Vec3 handPos(ServerLevel level, ServerPlayer p, Vec3 look, double side) {
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 eye = p.getEyePosition();
        Vec3 start = eye.add(look.scale(1.6)).add(right.scale(side)).add(0, -0.3, 0);
        BlockHitResult wall = SubLevels.clip(level, new ClipContext(eye, start, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (wall.getType() != HitResult.Type.MISS) {
            start = wall.getLocation().subtract(look.scale(0.15));
        }
        return start;
    }

    /** 투사체는 손 옆에서 나가지만, 화면 가운데(조준점)가 가리키는 곳으로 날아가게 방향을 맞춘다. */
    private static Vec3 aimDirection(ServerLevel level, ServerPlayer p, Vec3 eye, Vec3 look, Vec3 start, double range) {
        Vec3 far = eye.add(look.scale(range));
        BlockHitResult bh = SubLevels.clip(level, new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 target = bh.getType() == HitResult.Type.MISS ? far : bh.getLocation();
        if (target.distanceToSqr(eye) > (range + 4.0) * (range + 4.0)) target = far; // 안전장치

        EntityHitResult eh = ProjectileUtil.getEntityHitResult(level, p, eye, target,
                p.getBoundingBox().expandTowards(target.subtract(eye)).inflate(1.0),
                e -> e.isPickable() && !e.isSpectator() && !SkillTargets.isSameRide(p, e));
        if (eh != null) {
            target = eh.getLocation();
        }
        Vec3 d = target.subtract(start);
        // 조준점이 너무 가까우면(벽에 붙어 쏨) 그냥 바라보는 방향
        if (d.lengthSqr() < 4.0 || d.normalize().dot(look) < 0.5) return look;
        return d.normalize();
    }

    // =====================================================================
    // 3. 불기둥
    // =====================================================================

    /** 조준점이 가리키는 바닥. 허공을 보면 그 지점에서 아래로 최대 10블록까지 바닥을 찾는다. 없으면 null. */
    @Nullable
    private static Vec3 pillarTarget(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        Vec3 eye = p.getEyePosition();
        Vec3 far = eye.add(p.getLookAngle().scale(SkillsConfig.PILLAR_RANGE.get()));
        BlockHitResult bh = SubLevels.clip(level, new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        Vec3 from;
        if (bh.getType() != HitResult.Type.MISS) {
            if (bh.getDirection() == Direction.UP) return bh.getLocation();
            from = bh.getLocation().add(Vec3.atLowerCornerOf(bh.getDirection().getNormal()).scale(0.3));
        } else {
            from = far;
        }
        BlockHitResult down = SubLevels.clip(level, new ClipContext(from, from.add(0, -10, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        if (down.getType() == HitResult.Type.MISS) return null;
        return down.getLocation();
    }

    private static void eruptPillar(ServerPlayer p, @Nullable Vec3 base) {
        if (base == null) return;
        ServerLevel level = p.serverLevel();
        double r = SkillsConfig.PILLAR_RADIUS.get();
        double h = SkillsConfig.PILLAR_HEIGHT.get();
        int duration = SkillsConfig.PILLAR_DURATION.get();
        double launch = SkillsConfig.PILLAR_LAUNCH.get();
        float dmg = SkillsConfig.PILLAR_DAMAGE.get().floatValue();

        for (LivingEntity e : inPillar(level, p, base, r, h)) {
            e.hurt(level.damageSources().explosion(p, p), dmg);
            Vec3 out = e.position().subtract(base).multiply(1, 0, 1);
            Vec3 dir = out.lengthSqr() < 1.0E-4 ? Vec3.ZERO : out.normalize();
            SkillTargets.knock(e, dir, 0.25, launch);
            if (SkillsConfig.ALLOW_FIRE.get()) e.igniteForSeconds(4.0f);
        }
        terrain(level, p, base.add(0, 0.3, 0), 2.5f);
        level.playSound(null, base.x, base.y, base.z, SkillsRegistry.FIRE_PILLAR_ERUPT.get(), SoundSource.PLAYERS, 4.0f, 1.0f);
        PILLARS.add(new Pillar(level, p.getUUID(), base, r, h, level.getServer().getTickCount(), duration));
        PacketDistributor.sendToPlayersNear(level, null, base.x, base.y, base.z, 160.0,
                new SkillFxPayload(SkillFxPayload.PILLAR_ERUPT, p.getId(), base.x, base.y, base.z, (float) r, (float) h, duration));
    }

    private static void tickPillars(MinecraftServer server, long now) {
        Iterator<Pillar> it = PILLARS.iterator();
        while (it.hasNext()) {
            Pillar x = it.next();
            if (now >= x.end) {
                it.remove();
                continue;
            }
            if (now < x.nextBurn) continue;
            x.nextBurn = now + 10; // 0.5초마다 (생물이 맞은 뒤 무적 시간과 같음)
            ServerPlayer owner = server.getPlayerList().getPlayer(x.owner);
            float dmg = SkillsConfig.PILLAR_BURN_DAMAGE.get().floatValue();
            if (dmg <= 0) continue;
            for (LivingEntity e : inPillar(x.level, owner, x.base, x.radius * 0.85, x.height)) {
                e.hurt(owner != null ? x.level.damageSources().explosion(owner, owner) : x.level.damageSources().inFire(), dmg);
                SkillTargets.knock(e, Vec3.ZERO, 0, 0.18); // 기둥 안에서는 계속 떠오름
            }
        }
    }

    private static List<LivingEntity> inPillar(ServerLevel level, @Nullable Entity owner, Vec3 base, double r, double h) {
        AABB box = new AABB(base.x - r, base.y - 0.5, base.z - r, base.x + r, base.y + h, base.z + r);
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> SkillTargets.canHit(owner, e))) {
            double dx = e.getX() - base.x, dz = e.getZ() - base.z;
            double reach = r + e.getBbWidth() * 0.5;
            if (dx * dx + dz * dz <= reach * reach) out.add(e);
        }
        return out;
    }

    // =====================================================================
    // 4. 화염 고리
    // =====================================================================

    private static void burstRing(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        double radius = SkillsConfig.RING_RADIUS.get();
        float dmg = SkillsConfig.RING_DAMAGE.get().floatValue();
        double kb = SkillsConfig.RING_KNOCKBACK.get();
        Vec3 c = p.position().add(0, 0.9, 0);

        AABB box = new AABB(c, c).inflate(radius, 3.0, radius);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> SkillTargets.canHit(p, e))) {
            Vec3 ec = e.getBoundingBox().getCenter();
            Vec3 flat = ec.subtract(c).multiply(1, 0, 1);
            double dist = Math.max(0.0, flat.length() - e.getBbWidth() * 0.5);
            if (dist > radius || !SkillTargets.canSee(level, p, c, e)) continue;
            double f = 1.0 - 0.5 * (dist / radius); // 가장자리에서 절반
            e.hurt(level.damageSources().explosion(p, p), (float) (dmg * f));
            Vec3 dir = flat.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : flat.normalize();
            SkillTargets.knock(e, dir, kb * f, 0.35 + 0.25 * f);
            if (SkillsConfig.ALLOW_FIRE.get()) e.igniteForSeconds(3.0f);
        }
        terrain(level, p, p.position().add(0, 0.2, 0), 3.0f);
        level.playSound(null, c.x, c.y, c.z, SkillsRegistry.FLAME_RING_BURST.get(), SoundSource.PLAYERS, 4.0f, 1.0f);
        Vec3 feet = groundBelow(level, p);
        PacketDistributor.sendToPlayersNear(level, null, feet.x, feet.y, feet.z, 160.0,
                new SkillFxPayload(SkillFxPayload.RING_BURST, p.getId(), feet.x, feet.y, feet.z, (float) radius, 0f, 18));
    }

    /** 발밑 바닥 (공중에서 쓰면 최대 6블록 아래, 없으면 발 위치). 파도가 바닥을 따라 퍼지게. */
    private static Vec3 groundBelow(ServerLevel level, ServerPlayer p) {
        Vec3 feet = p.position().add(0, 0.1, 0);
        BlockHitResult down = SubLevels.clip(level, new ClipContext(feet, feet.add(0, -6, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        return down.getType() == HitResult.Type.MISS ? p.position() : down.getLocation();
    }

    // =====================================================================
    // 공통
    // =====================================================================

    /** 설정으로 켰을 때만 지형 파괴·불 붙이기 (생물 피해는 위에서 이미 줬으므로 0). */
    private static void terrain(ServerLevel level, ServerPlayer p, Vec3 at, float power) {
        boolean fire = SkillsConfig.ALLOW_FIRE.get();
        boolean blocks = SkillsConfig.ALLOW_BLOCK_DAMAGE.get();
        if (!fire && !blocks) return;
        level.explode(p, null, FireFistProjectile.TERRAIN_ONLY, at.x, at.y, at.z, power, fire,
                blocks ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE,
                ParticleTypes.SMOKE, ParticleTypes.SMOKE, FireFistProjectile.SILENT);
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        PENDING.removeIf(x -> x.player().equals(id));
        BUFFERED.remove(id);
    }

    public static void clear() {
        READY_AT.clear();
        PENDING.clear();
        PILLARS.clear();
        BUFFERED.clear();
    }

}
