package com.blackocean.skills.network;

import com.blackocean.skills.SkillServer;
import com.blackocean.skills.client.ClientPacketHandler;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class SkillsNetwork {
    private SkillsNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("7"); // 신호 모양이 바뀌면 숫자를 올림 (옛 버전 접속기는 접속 거부)

        r.playToServer(CastRequestPayload.TYPE, CastRequestPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SkillServer.tryCast(sp, payload.slot(), payload.yaw(), payload.pitch());
                }));

        // 아래 처리기는 클라이언트에서만 실행된다 (람다 안에서만 ClientPacketHandler 를 부름 → 서버에서는 불려오지 않음)
        r.playToClient(CastStartPayload.TYPE, CastStartPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> ClientPacketHandler.onCastStart(payload)));
        r.playToClient(CooldownSyncPayload.TYPE, CooldownSyncPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> ClientPacketHandler.onCooldown(payload)));
        r.playToClient(SkillEventPayload.TYPE, SkillEventPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> ClientPacketHandler.onEvent(payload)));
        r.playToClient(SkillStatsPayload.TYPE, SkillStatsPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> ClientPacketHandler.onStats(payload)));
        r.playToClient(ElementSyncPayload.TYPE, ElementSyncPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> ClientPacketHandler.onElement(payload)));
    }
}
