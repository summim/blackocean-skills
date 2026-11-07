package com.blackocean.skills;

import com.blackocean.skills.network.ElementSyncPayload;
import com.blackocean.skills.network.SkillEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/** 플레이어 능력 읽기·쓰기 (서버). 저장은 플레이어 데이터(attachment)에 되고 죽어도 유지된다. */
public final class PlayerElements {
    private PlayerElements() {}

    /** 이 플레이어의 능력. 없으면 null. 아직 안 정했으면 서버 기본값(defaultElement). */
    @Nullable
    public static Element get(ServerPlayer p) {
        String s = p.getData(SkillsRegistry.ELEMENT);
        if (s.isEmpty()) s = SkillsConfig.DEFAULT_ELEMENT.get();
        return Element.byId(s);
    }

    /** 명시적으로 정해 둔 능력이 있는지 ("" 이 아니면 true, none 포함) */
    public static boolean hasChosen(ServerPlayer p) {
        return !p.getData(SkillsRegistry.ELEMENT).isEmpty();
    }

    /** @param e null 이면 "능력 없음" */
    public static void set(ServerPlayer p, @Nullable Element e) {
        p.setData(SkillsRegistry.ELEMENT, e == null ? "none" : e.id);
        SkillServer.cancelAll(p);
        sync(p);
    }

    /** 설정을 지워 서버 기본값으로 */
    public static void clear(ServerPlayer p) {
        p.setData(SkillsRegistry.ELEMENT, "");
        SkillServer.cancelAll(p);
        sync(p);
    }

    public static void sync(ServerPlayer p) {
        Element e = get(p);
        PacketDistributor.sendToPlayer(p, new ElementSyncPayload(e == null ? -1 : e.ordinal()));
    }

    /** 열매를 먹음. @return 능력을 받았으면 true (false 면 열매를 돌려줌) */
    public static boolean eatFruit(ServerPlayer p, Element e) {
        Element now = get(p);
        if (now == e && hasChosen(p)) {
            p.displayClientMessage(Component.translatable("msg.blackocean_skills.fruit_same", Component.translatable(e.translationKey())), true);
            return false;
        }
        if (hasChosen(p) && now != null && !SkillsConfig.FRUIT_SWAP.get()) {
            p.displayClientMessage(Component.translatable("msg.blackocean_skills.fruit_locked", Component.translatable(now.translationKey())), true);
            return false;
        }
        set(p, e);
        p.displayClientMessage(Component.translatable("msg.blackocean_skills.fruit_gain", Component.translatable(e.translationKey())), false);
        SkillSounds.play(p.serverLevel(), p.position(), "common.transform", SkillSounds.R_IMPACT, 1.0f);
        SkillEvents.send(p.serverLevel(), null, SkillEvents.TRANSFORM, p, p.position(), Vec3.ZERO, 0, 0, 0, e.ordinal(), 64.0);
        return true;
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) sync(p);
    }

    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) sync(p);
    }

    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) sync(p);
    }
}
