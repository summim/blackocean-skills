package com.blackocean.skills.client;

import com.blackocean.skills.client.fx.FxManager;
import com.blackocean.skills.network.CastStartPayload;
import com.blackocean.skills.network.FireFistImpactPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** 서버에서 온 신호를 화면 연출로 넘긴다. (클라이언트에서만 불림) */
public final class ClientPacketHandler {
    private ClientPacketHandler() {}

    public static void onCastStart(CastStartPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity caster = mc.level.getEntity(p.casterId());
        if (caster != null) {
            FxManager.onCastStart(caster, p.chargeTicks());
        }
    }

    public static void onImpact(FireFistImpactPayload p) {
        if (Minecraft.getInstance().level == null) return;
        FxManager.onImpact(p);
    }
}
