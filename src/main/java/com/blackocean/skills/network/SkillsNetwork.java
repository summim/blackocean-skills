package com.blackocean.skills.network;

import com.blackocean.skills.client.ClientPacketHandler;
import com.blackocean.skills.fire.FireFistServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class SkillsNetwork {
    private SkillsNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");

        r.playToServer(CastRequestPayload.TYPE, CastRequestPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        FireFistServer.tryCast(sp);
                    }
                }));

        // 아래 두 처리기는 클라이언트에서만 실행된다.
        // 람다 안에서만 ClientPacketHandler 를 부르기 때문에, 서버에서는 그 클래스가 아예 불려오지 않는다.
        r.playToClient(CastStartPayload.TYPE, CastStartPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> ClientPacketHandler.onCastStart(payload)));
        r.playToClient(FireFistImpactPayload.TYPE, FireFistImpactPayload.CODEC, (payload, ctx) ->
                ctx.enqueueWork(() -> ClientPacketHandler.onImpact(payload)));
    }
}
