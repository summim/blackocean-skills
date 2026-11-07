package com.blackocean.skills;

import com.blackocean.skills.entity.SkillProjectile;
import com.blackocean.skills.network.CastStartPayload;
import com.blackocean.skills.network.CooldownSyncPayload;
import com.blackocean.skills.network.SkillEvents;
import com.blackocean.skills.network.SkillStatsPayload;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 서버 쪽 스킬 관리 (20종 공통): 쿨타임 확인 → "시전 시작" 신호 → 모으는 시간 뒤 발동 → 지속 효과 매 틱 처리.
 * 실제 스킬 동작은 ServerEffects 에 있다.
 */
public final class SkillServer {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int N = Skill.VALUES.length;

    /**
     * 입력 예약 허용 시간 (틱). 쿨타임이 이만큼 이하로 남았거나 다른 스킬을 모으는 중이면
     * 누른 키를 버리지 않고 기억했다가 되는 순간 바로 발동한다.
     */
    public static final int BUFFER_TICKS = 10;

    private static final Map<UUID, long[]> READY_AT = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static final Map<UUID, Buffered> BUFFERED = new HashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();
    private static final List<ServerEffects.Effect> EFFECTS = new ArrayList<>();

    /**
     * @param cooldown 이 시전으로 걸린 쿨타임 (발동 전에 취소되면 돌려줌)
     * @param shot     불꽃 탄환: 몇 번째 발 (0 = 모으는 중)
     * @param target   바닥 조준 스킬의 자리
     */
    private record Pending(UUID player, int entity, Skill skill, long fireAt, int cooldown, int shot, @Nullable Vec3 target, ResourceKey<Level> dim) {}

    private record Buffered(Skill skill, long expireAt) {}

    private SkillServer() {}

    // =====================================================================
    // 시전
    // =====================================================================

    /** 클라이언트가 1~5 키를 눌러 보낸 요청 (slot 0~4). 메인 스레드에서 호출된다. */
    public static void tryCast(ServerPlayer p, int slot) {
        if (slot < 0 || slot >= Skill.SLOTS) return;
        // 요청 폭주 방지: 한 사람당 한 틱에 한 번만
        long tick = p.server.getTickCount();
        Long last = LAST_REQUEST.put(p.getUUID(), tick);
        if (last != null && last == tick) return;
        // 기절·얼림 중엔 스킬 못 씀 (명령어·API 발동은 예외)
        net.minecraft.world.effect.MobEffectInstance slowFx = p.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN);
        if (slowFx != null && slowFx.getAmplifier() >= 5) {
            p.displayClientMessage(Component.translatable("msg.blackocean_skills.stunned"), true);
            PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(-1, 0, 0, true));
            return;
        }
        Element e = PlayerElements.get(p);
        if (e == null) {
            p.displayClientMessage(Component.translatable("msg.blackocean_skills.no_element"), true);
            PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(-1, 0, 0, true));
            return;
        }
        Skill skill = Skill.of(e, slot);
        if (!skill.enabled()) {
            p.displayClientMessage(Component.translatable("msg.blackocean_skills.disabled", Component.translatable(skill.translationKey())), true);
            deny(p, skill);
            return;
        }
        int left = remainingTicks(p, skill);
        Skill charging = chargingSkill(p.getUUID());
        if (charging == skill) return; // 모으는 중에 같은 키를 또 누름 → 무시 (예전엔 잠시 뒤 거절 표시가 떴음)
        boolean busy = charging != null || movingBlocks(p, skill);
        if (left <= BUFFER_TICKS && (left > 0 || busy)) {
            // 곧 쓸 수 있음 → 예약 (마지막에 누른 스킬이 이김)
            BUFFERED.put(p.getUUID(), new Buffered(skill, p.server.getTickCount() + Math.max(left, 0) + BUFFER_TICKS + 40));
            return;
        }
        if (left > 0) {
            p.displayClientMessage(Component.translatable("msg.blackocean_skills.cooldown",
                    Component.translatable(skill.translationKey()), String.format(Locale.ROOT, "%.1f", left / 20.0)), true);
            deny(p, skill);
            return;
        }
        if (!cast(p, skill, true)) deny(p, skill);
    }

    private static void deny(ServerPlayer p, Skill skill) {
        PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(skill.ordinal(), remainingTicks(p, skill), skill.cooldownTicks(), true));
    }

    /**
     * 실제 시전 (명령어·API 에서도 부름).
     * @param useCooldown false 면 쿨타임을 확인하지도, 걸지도 않음 (별풍선 보상 등)
     * @return 시작했으면 true
     */
    public static boolean cast(ServerPlayer p, Skill skill, boolean useCooldown) {
        if (!p.isAlive() || p.isSpectator() || p.isSleeping() || chargingSkill(p.getUUID()) != null) return false;
        if (movingBlocks(p, skill)) return false; // 돌진·도약 중에 또 이동 스킬 → 두 효과가 속도를 서로 덮어씀
        ServerLevel level = p.serverLevel();
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();

        Vec3 target = null;
        if (skill.aim == Skill.Aim.GROUND) {
            target = SkillAim.groundTarget(level, p, eye, look, skill.range());
            if (target == null && !useCooldown) target = SkillAim.groundBelow(level, p, p.position());
            if (target == null) {
                p.displayClientMessage(Component.translatable("msg.blackocean_skills.no_target"), true);
                return false;
            }
        } else if (skill.aim == Skill.Aim.BLINK && SkillAim.blinkDestination(level, p, p.position(), eye, look, skill.range()) == null) {
            p.displayClientMessage(Component.translatable("msg.blackocean_skills.no_space"), true);
            return false;
        }

        long now = p.server.getTickCount();
        int charge = skill.chargeTicks();
        int cooldown = useCooldown ? skill.cooldownTicks() : 0;
        if (useCooldown) readyAt(p.getUUID())[skill.ordinal()] = now + cooldown;
        PENDING.add(new Pending(p.getUUID(), p.getId(), skill, now + charge, cooldown, 0, target, level.dimension()));

        Vec3 at = target != null ? target : p.position();
        float size = skill.aim == Skill.Aim.GROUND ? (float) skill.radius() : 0f;
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new CastStartPayload(p.getId(), skill.ordinal(), charge, at.x, at.y, at.z, size));
        if (useCooldown) PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(skill.ordinal(), cooldown, cooldown, false));

        // 모으는 소리: 시전자 본인은 자기 화면에서 바로 틀므로 제외하고 주변에만
        ChargeSound cs = chargeSound(skill);
        level.playSound(p, p.getX(), p.getY(), p.getZ(), SkillsRegistry.snd(cs.name()), SoundSource.PLAYERS, cs.volume(), cs.pitch());
        if (skill == Skill.FIRE_PILLAR && target != null) {
            level.playSound(null, target.x, target.y, target.z, SkillsRegistry.snd("fire_pillar.warn"), SoundSource.PLAYERS, 2.0f, 1.0f);
        }
        if (skill.ultimate()) {
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SkillsRegistry.snd("common.ultimate"), SoundSource.PLAYERS, 5.0f, 1.0f);
            if (SkillsConfig.ULT_BANNER.get()) {
                SkillEvents.send(level, skill, SkillEvents.ULT_CAST, p, p.position(), Vec3.ZERO, 0, 0, charge, -1, 96.0);
            }
        }
        return true;
    }

    /** 모으는 소리 (서버·클라이언트 공통 표) */
    public record ChargeSound(String name, float volume, float pitch) {}

    public static ChargeSound chargeSound(Skill s) {
        return switch (s) {
            case FIRE_FIST, FIRE_PILLAR -> new ChargeSound("fire_fist.charge", 1.4f, 1.0f);
            case FIRE_BULLETS -> new ChargeSound("fire_fist.charge", 0.8f, 1.7f);
            case FLAME_RING -> new ChargeSound("flame_ring.charge", 2.0f, 1.0f);
            case FIRE_SUN -> new ChargeSound("fire.sun_charge", 3.0f, 1.0f);
            case LIGHTNING_STORM -> new ChargeSound("lightning.storm_charge", 3.0f, 1.0f);
            case WIND_TEMPEST -> new ChargeSound("wind.charge", 2.5f, 0.7f);
            case WATER_DRAGON -> new ChargeSound("water.charge", 2.5f, 0.7f);
            case ICE_AGE -> new ChargeSound("ice.charge", 3.0f, 0.6f);
            case THUNDER_JUDGMENT -> new ChargeSound("thunder.charge", 3.0f, 0.7f);
            default -> new ChargeSound(s.element.id + ".charge", 1.4f, s.slot == 1 ? 1.4f : 1.0f);
        };
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
            if (skill == null) Arrays.fill(r, 0L);
            else r[skill.ordinal()] = 0L;
        }
        PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(skill == null ? -1 : skill.ordinal(), 0, 0, false));
    }

    /** 능력이 바뀌거나 나갈 때: 모으는 중·예약된 시전 취소 */
    public static void cancelAll(ServerPlayer p) {
        UUID id = p.getUUID();
        PENDING.removeIf(x -> {
            if (!x.player().equals(id)) return false;
            if (x.shot() == 0 && x.cooldown() > 0) {
                long[] r = READY_AT.get(id);
                if (r != null) r[x.skill().ordinal()] = 0L;
                PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(x.skill().ordinal(), 0, 0, true));
            }
            return true;
        });
        Buffered b = BUFFERED.remove(id);
        if (b != null) deny(p, b.skill());
    }

    private static long[] readyAt(UUID id) {
        return READY_AT.computeIfAbsent(id, k -> new long[N]);
    }

    /** 시전자를 움직이는 스킬 (서로 겹치면 매 틱 속도를 서로 덮어써서 엉뚱한 곳으로 감) */
    private static boolean movesCaster(Skill s) {
        return s == Skill.WIND_DASH || s == Skill.LIGHTNING_BLINK || s == Skill.THUNDER_LEAP || s == Skill.THUNDER_JUDGMENT;
    }

    /** 이동 스킬인데 지금 다른 이동 효과가 몸을 움직이는 중이면 true */
    private static boolean movingBlocks(ServerPlayer p, Skill s) {
        if (!movesCaster(s)) return false;
        UUID id = p.getUUID();
        for (ServerEffects.Effect e : EFFECTS) {
            if (e.ownerId.equals(id) && e.controlsOwner()) return true;
        }
        return false;
    }

    /** 모으는 중(아직 발동 전)인 스킬. 탄환 연사 중(2발째부터)은 해당 없음. */
    @Nullable
    private static Skill chargingSkill(UUID id) {
        for (Pending x : PENDING) {
            if (x.player().equals(id) && x.shot() == 0) return x.skill();
        }
        return null;
    }

    static void addEffect(ServerEffects.Effect e) {
        EFFECTS.add(e);
    }

    /** 작열의 태양이 떨어진 자리의 불바다 (SkillProjectile 에서 부름) */
    public static void addFireField(ServerLevel level, @Nullable Entity owner, Vec3 pos, float radius, Skill skill) {
        if (!(owner instanceof ServerPlayer p)) return;
        Vec3 ground = SkillAim.groundBelow(level, p, pos);
        if (ground == pos) return; // 공중에서 터짐 → 불바다 없음
        EFFECTS.add(new ServerEffects.Field(level, p, skill, ground, radius));
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
                // 죽고 다시 태어난 경우(새 몸)도 취소
                boolean ok = p != null && p.isAlive() && !p.isSpectator() && p.getId() == x.entity() && p.level().dimension() == x.dim();
                if (!ok) {
                    if (x.shot() == 0 && x.cooldown() > 0) {
                        long[] r = READY_AT.get(x.player());
                        if (r != null) r[x.skill().ordinal()] = 0L;
                        if (p != null) resetCooldown(p, x.skill());
                    }
                    continue;
                }
                try {
                    if (x.skill() == Skill.FIRE_BULLETS) {
                        projectile(p, x.skill(), x.shot() % 2 == 0 ? 0.38 : -0.38, 0, 2.5);
                        if (x.shot() + 1 < 5) {
                            next.add(new Pending(x.player(), x.entity(), x.skill(), now + 2, 0, x.shot() + 1, null, x.dim()));
                        }
                    } else {
                        execute(p, x.skill(), x.target());
                    }
                } catch (Exception ex) {
                    LOG.error("[BlackOcean Skills] {} 발동 중 오류", x.skill().id, ex);
                }
            }
            PENDING.addAll(next);
        }

        if (!BUFFERED.isEmpty()) tickBuffered(server, now);

        if (!EFFECTS.isEmpty()) {
            EFFECTS.removeIf(ef -> {
                try {
                    return ef.tick(server);
                } catch (Exception ex) {
                    LOG.error("[BlackOcean Skills] 지속 효과 오류 — 효과를 끝냅니다", ex);
                    return true;
                }
            });
        }
    }

    private static void execute(ServerPlayer p, Skill skill, @Nullable Vec3 target) {
        ServerLevel level = p.serverLevel();
        switch (skill) {
            case FIRE_FIST, WIND_BLADE, WATER_BULLET -> projectile(p, skill, 0.3, 0, 0);
            case FIRE_SUN -> projectile(p, skill, 0, 2.6, 0);
            case FIRE_BULLETS -> projectile(p, skill, 0.38, 0, 2.5);
            case FLAME_RING -> ServerEffects.flameRing(level, p, skill);
            case LIGHTNING_BOLT -> ServerEffects.chainBolt(level, p, skill);
            case LIGHTNING_BLINK -> ServerEffects.blink(level, p, skill);
            case LIGHTNING_DISCHARGE -> ServerEffects.discharge(level, p, skill);
            case WIND_BURST -> ServerEffects.gust(level, p, skill);
            case WIND_DASH -> EFFECTS.add(new ServerEffects.Dash(level, p, skill));
            case WATER_JET -> EFFECTS.add(new ServerEffects.Jet(level, p, skill));
            case WATER_WAVE -> EFFECTS.add(new ServerEffects.Wave(level, p, skill));
            case ICE_SPEAR, THUNDER_HAMMER -> projectile(p, skill, 0.3, 0, 0);
            case ICE_SHARDS -> fan(p, skill, 7);
            case ICE_SPIKES -> EFFECTS.add(new IceThunderEffects.Spikes(level, p, skill));
            case FROST_NOVA -> IceThunderEffects.frostNova(level, p, skill);
            case THUNDERCLAP -> IceThunderEffects.thunderclap(level, p, skill);
            case THUNDER_ROAR -> EFFECTS.add(new IceThunderEffects.Roar(level, p, skill));
            default -> {
                if (target == null) return;
                switch (skill) {
                    case FIRE_PILLAR -> EFFECTS.add(new ServerEffects.Pillar(level, p, skill, target));
                    case LIGHTNING_STRIKE -> EFFECTS.add(new ServerEffects.Strikes(level, p, skill, target));
                    case LIGHTNING_STORM -> EFFECTS.add(new ServerEffects.Storm(level, p, skill, target));
                    case WIND_WHIRLWIND, WIND_TEMPEST -> EFFECTS.add(new ServerEffects.Tornado(level, p, skill, target));
                    case WATER_PRISON -> EFFECTS.add(new ServerEffects.Prison(level, p, skill, target));
                    case WATER_DRAGON -> EFFECTS.add(new ServerEffects.Dragon(level, p, skill, target));
                    case ICE_AGE -> EFFECTS.add(new IceThunderEffects.Glacier(level, p, skill, target));
                    case THUNDER_LEAP -> EFFECTS.add(new IceThunderEffects.Leap(level, p, skill, target));
                    case THUNDER_JUDGMENT -> EFFECTS.add(new IceThunderEffects.Judgment(level, p, skill, target));
                    default -> { }
                }
            }
        }
    }

    /** 부채꼴로 여러 발 (얼음 파편). radius = 반각(도) */
    private static void fan(ServerPlayer p, Skill skill, int count) {
        ServerLevel level = p.serverLevel();
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        Vec3 start = SkillAim.handPos(level, p, eye, look, 0, 0);
        Vec3 dir = SkillAim.aimDirection(level, p, eye, look, start, skill.range());
        double half = Math.toRadians(Math.min(80.0, Math.max(1.0, skill.radius())));
        for (int i = 0; i < count; i++) {
            double a = count == 1 ? 0 : -half + 2 * half * i / (count - 1);
            level.addFreshEntity(SkillProjectile.create(level, p, skill, start, dir.yRot((float) a).normalize()));
        }
        level.playSound(null, start.x, start.y, start.z, SkillsRegistry.snd("ice.shot"), SoundSource.PLAYERS, 2.4f, 1.3f);
    }

    /** 투사체 발사. side: 손 좌우, lift: 머리 위 높이(태양), spreadDeg: 탄 퍼짐 */
    private static void projectile(ServerPlayer p, Skill skill, double side, double lift, double spreadDeg) {
        ServerLevel level = p.serverLevel();
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        Vec3 start = SkillAim.handPos(level, p, eye, look, side, lift);
        Vec3 dir = SkillAim.aimDirection(level, p, eye, look, start, skill.range());
        if (spreadDeg > 0) {
            double s = Math.toRadians(spreadDeg);
            dir = dir.xRot((float) ((p.getRandom().nextDouble() - 0.5) * 2 * s))
                    .yRot((float) ((p.getRandom().nextDouble() - 0.5) * 2 * s)).normalize();
        }
        level.addFreshEntity(SkillProjectile.create(level, p, skill, start, dir));
        String snd = switch (skill) {
            case FIRE_BULLETS -> "fire_bullets.shot";
            case WIND_BLADE -> "wind.blade";
            case WATER_BULLET -> "water.shot";
            case ICE_SPEAR -> "ice.shot";
            case THUNDER_HAMMER -> "thunder.hammer";
            default -> "fire_fist.launch";
        };
        float pitch = skill == Skill.FIRE_SUN ? 0.55f : skill == Skill.FIRE_BULLETS ? 0.92f + p.getRandom().nextFloat() * 0.16f : 1.0f;
        level.playSound(null, start.x, start.y, start.z, SkillsRegistry.snd(snd), SoundSource.PLAYERS,
                skill == Skill.FIRE_SUN ? 5.0f : 2.2f, pitch);
    }

    private static void tickBuffered(MinecraftServer server, long now) {
        Iterator<Map.Entry<UUID, Buffered>> it = BUFFERED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Buffered> e = it.next();
            Buffered b = e.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            if (now > b.expireAt()) {
                it.remove();
                deny(p, b.skill());
                continue;
            }
            if (chargingSkill(p.getUUID()) != null || remainingTicks(p, b.skill()) > 0 || movingBlocks(p, b.skill())) continue;
            it.remove();
            Element el = PlayerElements.get(p);
            if (el != b.skill().element || !b.skill().enabled() || !cast(p, b.skill(), true)) deny(p, b.skill());
        }
    }

    // =====================================================================
    // 접속·설정
    // =====================================================================

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            sendStats(p);
            for (Skill s : Skill.VALUES) {
                int left = remainingTicks(p, s);
                if (left > 0) PacketDistributor.sendToPlayer(p, new CooldownSyncPayload(s.ordinal(), left, Math.max(left, s.cooldownTicks()), false));
            }
        }
    }

    /** 조준 표시용 서버 설정값 (사거리·반경·지속·속도) */
    public static void sendStats(ServerPlayer p) {
        float[] r = new float[N], rad = new float[N], dur = new float[N], spd = new float[N];
        for (Skill s : Skill.VALUES) {
            r[s.ordinal()] = (float) s.range();
            rad[s.ordinal()] = (float) s.radius();
            dur[s.ordinal()] = s.durationTicks();
            spd[s.ordinal()] = (float) s.speed();
        }
        PacketDistributor.sendToPlayer(p, new SkillStatsPayload(r, rad, dur, spd));
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        LAST_REQUEST.remove(id);
        PENDING.removeIf(x -> x.player().equals(id));
        BUFFERED.remove(id);
    }

    public static void clear() {
        READY_AT.clear();
        PENDING.clear();
        BUFFERED.clear();
        EFFECTS.clear();
    }
}
