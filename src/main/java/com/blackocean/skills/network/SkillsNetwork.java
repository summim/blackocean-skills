package com.blackocean.skills.network;

import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillServer;
import com.blackocean.skills.client.ClientPacketHandler;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class SkillsNetwork {
    private SkillsNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("4"); // 신호 모양이 바뀌면 숫자를 올림 (옛 버전 접속기는 접속 거부)

        r.playToServer(CastRequestPayload.TYPE, CastRequestPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> {
                    Skill skill = Skill.byIndex(payload.skill());
                    if (skill != null && ctx.player() instanceof ServerPlayer sp) {
                        SkillServer.tryCast(sp, skill);
                    }
                }));

        // 아래 처리기는 클라이언트에서만 실행된다.
        // 람다 안에서만 ClientPacketHandler 를 부르기 때문에, 서버에서는 그 클래스가 아예 불려오지 않는다.
        r.playToClient(CastStartPayload.TYPE, CastStartPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> ClientPacketHandler.onCastStart(payload)));
        r.playToClient(FireFistImpactPayload.TYPE, FireFistImpactPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> ClientPacketHandler.onImpact(payload)));
        r.playToClient(CooldownSyncPayload.TYPE, CooldownSyncPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> ClientPacketHandler.onCooldown(payload)));
        r.playToClient(SkillFxPayload.TYPE, SkillFxPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> ClientPacketHandler.onSkillFx(payload)));
        r.playToClient(SkillStatsPayload.TYPE, SkillStatsPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> ClientPacketHandler.onStats(payload)));
    }
}
