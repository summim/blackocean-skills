package com.blackocean.skills.fire;

import com.blackocean.skills.SkillsConfig;
import com.blackocean.skills.SkillsRegistry;
import com.blackocean.skills.network.CastStartPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
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

    private record Pending(UUID player, long fireAt) {}

    private FireFistServer() {}

    /** 클라이언트가 R 키를 눌러 보낸 요청. 메인 스레드에서 호출된다. */
    public static void tryCast(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator()) return;
        long now = p.server.getTickCount();
        Long ready = READY_AT.get(p.getUUID());
        if (ready != null && now < ready) {
            String sec = String.format("%.1f", (ready - now) / 20.0);
            p.displayClientMessage(Component.translatable("msg.blackocean_skills.cooldown", sec), true);
            return;
        }
        for (Pending x : PENDING) {
            if (x.player().equals(p.getUUID())) return;
        }
        int charge = SkillsConfig.CHARGE_TICKS.get();
        READY_AT.put(p.getUUID(), now + SkillsConfig.COOLDOWN_TICKS.get());
        PENDING.add(new Pending(p.getUUID(), now + charge));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new CastStartPayload(p.getId(), charge));
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

        level.addFreshEntity(FireFistProjectile.create(level, p, start, look));
        level.playSound(null, start.x, start.y, start.z, SkillsRegistry.FIRE_FIST_LAUNCH.get(), SoundSource.PLAYERS, 2.5f, 1.0f);
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
