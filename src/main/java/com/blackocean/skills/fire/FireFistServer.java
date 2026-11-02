package com.blackocean.skills.fire;

import com.blackocean.skills.SkillsConfig;
import com.blackocean.skills.SkillsRegistry;
import com.blackocean.skills.network.CastStartPayload;
import com.blackocean.skills.network.CooldownSyncPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 서버 쪽 시전 관리: 쿨타임 확인 → "시전 시작" 신호 → 준비 시간 뒤 투사체 발사.
 */
public final class FireFistServer {
    private static final Map<UUID, Long> READY_AT = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();

    /** @param cooldown 이 시전으로 걸린 쿨타임. 발사 전에 취소되면 되돌려 준다. */
    private record Pending(UUID player, long fireAt, int cooldown) {}

    private FireFistServer() {}

    /** 클라이언트가 R 키를 눌러 보낸 요청. 메인 스레드에서 호출된다. */
    public static void tryCast(ServerPlayer p) {
        if (!SkillsConfig.ENABLED.get()) {
            p.displayClientMessage(Component.translatable("msg.blackocean_skills.disabled"), true);
            PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(0, 0, true));
            return;
        }
        int left = remainingTicks(p);
        if (left > 0) {
            p.displayClientMessage(Component.translatable("msg.blackocean_skills.cooldown", String.format("%.1f", left / 20.0)), true);
            PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(left, SkillsConfig.COOLDOWN_TICKS.get(), true));
            return;
        }
        cast(p, true);
    }

    /**
     * 실제 시전. 명령어·API 에서도 부른다.
     * @param useCooldown false 면 쿨타임을 확인하지도, 걸지도 않음 (별풍선 보상 등)
     * @return 시전을 시작했으면 true
     */
    public static boolean cast(ServerPlayer p, boolean useCooldown) {
        if (!p.isAlive() || p.isSpectator() || isPending(p.getUUID())) return false;
        long now = p.server.getTickCount();
        int charge = SkillsConfig.CHARGE_TICKS.get();
        int cooldown = useCooldown ? SkillsConfig.COOLDOWN_TICKS.get() : 0;
        if (useCooldown) {
            READY_AT.put(p.getUUID(), now + cooldown);
        }
        PENDING.add(new Pending(p.getUUID(), now + charge, cooldown));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new CastStartPayload(p.getId(), charge));
        if (useCooldown) {
            PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(cooldown, cooldown, false));
        }
        return true;
    }

    public static int remainingTicks(ServerPlayer p) {
        Long ready = READY_AT.get(p.getUUID());
        if (ready == null) return 0;
        long left = ready - p.server.getTickCount();
        return left > 0 ? (int) left : 0;
    }

    public static void resetCooldown(ServerPlayer p) {
        READY_AT.remove(p.getUUID());
        PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(0, 0, false));
    }

    private static boolean isPending(UUID id) {
        for (Pending x : PENDING) {
            if (x.player().equals(id)) return true;
        }
        return false;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending x = it.next();
            if (now < x.fireAt()) continue;
            it.remove();
            ServerPlayer p = server.getPlayerList().getPlayer(x.player());
            if (p != null && p.isAlive() && !p.isSpectator()) {
                launch(p);
            } else if (x.cooldown() > 0) {
                // 준비 중에 죽음 등으로 발사 못 함 → 쿨타임 돌려줌
                READY_AT.remove(x.player());
                if (p != null) resetCooldown(p);
            }
        }
    }

    private static void launch(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        Vec3 look = p.getLookAngle();
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 eye = p.getEyePosition();
        Vec3 start = eye.add(look.scale(1.6)).add(right.scale(0.3)).add(0, -0.3, 0);

        // 벽 바로 앞에서 쏘면 주먹이 벽 안에서 생겨 뚫고 나가지 않도록 벽 앞으로 당긴다
        BlockHitResult wall = level.clip(new ClipContext(eye, start, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (wall.getType() != HitResult.Type.MISS) {
            start = wall.getLocation().subtract(look.scale(0.15));
        }

        // 주먹은 손 옆에서 나가지만, 화면 가운데(조준점)가 가리키는 곳으로 날아가게 방향을 맞춘다
        Vec3 dir = aimDirection(level, p, eye, look, start);

        level.addFreshEntity(FireFistProjectile.create(level, p, start, dir));
        level.playSound(null, start.x, start.y, start.z, SkillsRegistry.FIRE_FIST_LAUNCH.get(), SoundSource.PLAYERS, 2.5f, 1.0f);
    }

    private static Vec3 aimDirection(ServerLevel level, ServerPlayer p, Vec3 eye, Vec3 look, Vec3 start) {
        double range = SkillsConfig.RANGE.get();
        Vec3 far = eye.add(look.scale(range));
        BlockHitResult bh = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 target = bh.getType() == HitResult.Type.MISS ? far : bh.getLocation();

        EntityHitResult eh = ProjectileUtil.getEntityHitResult(level, p, eye, target,
                p.getBoundingBox().expandTowards(target.subtract(eye)).inflate(1.0),
                e -> e.isPickable() && !e.isSpectator() && !isSameRide(p, e));
        if (eh != null) {
            target = eh.getLocation();
        }
        Vec3 d = target.subtract(start);
        // 조준점이 너무 가까우면(벽에 붙어 쏨) 그냥 바라보는 방향
        if (d.lengthSqr() < 4.0 || d.normalize().dot(look) < 0.5) return look;
        return d.normalize();
    }

    /** 같은 배·탈것에 탄 사이인지 (내가 탄 것, 나를 태운 것, 같이 탄 사람). */
    static boolean isSameRide(Entity owner, Entity e) {
        if (owner == null) return false;
        return e == owner.getVehicle() || owner.isPassengerOfSameVehicle(e) || e.hasPassenger(owner) || owner.hasPassenger(e);
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        PENDING.removeIf(x -> x.player().equals(id));
    }

    public static void clear() {
        READY_AT.clear();
        PENDING.clear();
    }
}
