package com.blackocean.skills.client;

import com.blackocean.skills.Element;
import com.blackocean.skills.Skill;
import com.blackocean.skills.client.fx.FxManager;
import com.blackocean.skills.network.CastStartPayload;
import com.blackocean.skills.network.CooldownSyncPayload;
import com.blackocean.skills.network.ElementSyncPayload;
import com.blackocean.skills.network.SkillEventPayload;
import com.blackocean.skills.network.SkillStatsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** 서버에서 온 신호를 화면 연출·스킬 칸으로 넘긴다. (클라이언트에서만 불림) */
public final class ClientPacketHandler {
    private ClientPacketHandler() {}

    public static void onCastStart(CastStartPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Skill skill = Skill.byIndex(p.skill());
        Entity caster = mc.level.getEntity(p.casterId());
        if (caster != null && skill != null) {
            if (caster == mc.player) SkillBarHud.castStarted(skill);
            com.blackocean.skills.client.fx.CasterPose.start(caster, skill, p.chargeTicks(), p.yaw(), p.pitch());
            // 신호 처리 중 오류가 나면 NeoForge 가 서버 접속을 끊으므로, 연출 오류는 여기서 막는다
            try {
                FxManager.onCastStart(caster, skill, p.chargeTicks(), new Vec3(p.x(), p.y(), p.z()), p.size());
            } catch (RuntimeException ex) {
                FxManager.fxError("cast_start", ex);
            }
        }
    }

    public static void onCooldown(CooldownSyncPayload p) {
        if (p.skill() < 0) {
            if (!p.denied()) SkillBarHud.resetAll();
        } else {
            Skill skill = Skill.byIndex(p.skill());
            if (skill == null) return;
            SkillBarHud.set(skill, p.remainingTicks(), p.totalTicks());
            if (p.denied()) SkillBarHud.flashDenied(skill);
        }
        if (p.denied()) FxManager.cancelPredictedCharge();
    }

    public static void onEvent(SkillEventPayload p) {
        if (Minecraft.getInstance().level == null) return;
        try {
            FxManager.onEvent(p);
        } catch (RuntimeException ex) {
            FxManager.fxError("event " + p.kind(), ex);
        }
    }

    public static void onStats(SkillStatsPayload p) {
        ClientSkillStats.set(p);
    }

    public static void onElement(ElementSyncPayload p) {
        Element before = ClientElement.get();
        Element now = Element.byIndex(p.element());
        ClientElement.set(now);
        if (before != now) com.blackocean.skills.client.fx.AimPreview.stop(); // 조준 중이던 스킬은 이제 내 것이 아님
    }
}
