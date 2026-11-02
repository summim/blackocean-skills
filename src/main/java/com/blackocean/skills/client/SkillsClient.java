package com.blackocean.skills.client;

import com.blackocean.skills.SkillsMod;
import com.blackocean.skills.SkillsRegistry;
import com.blackocean.skills.client.fx.CameraShake;
import com.blackocean.skills.client.fx.FxManager;
import com.blackocean.skills.client.fx.FxShaders;
import com.blackocean.skills.client.fx.ObjMesh;
import com.blackocean.skills.network.CastRequestPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** 클라이언트 전용 시작점. 서버(전용 서버)에서는 이 클래스가 아예 불려오지 않는다. */
@Mod(value = SkillsMod.MODID, dist = Dist.CLIENT)
public final class SkillsClient {
    public static final KeyMapping FIRE_FIST_KEY = new KeyMapping(
            "key.blackocean_skills.fire_fist", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.blackocean_skills");

    /** 연타 방지 (서버가 진짜 쿨타임을 판단하고, 이건 연출이 겹치지 않게 막는 용도) */
    private static long nextLocalCast;

    public SkillsClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SkillsClientConfig.SPEC);

        modBus.addListener(RegisterKeyMappingsEvent.class, e -> e.register(FIRE_FIST_KEY));
        modBus.addListener(RegisterShadersEvent.class, FxShaders::onRegisterShaders);
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class,
                e -> e.registerEntityRenderer(SkillsRegistry.FIRE_FIST.get(), NoopRenderer::new));
        modBus.addListener(RegisterClientReloadListenersEvent.class,
                e -> e.registerReloadListener((ResourceManagerReloadListener) rm -> ObjMesh.clearCache()));

        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, SkillsClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, FxManager::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeCameraAngles.class, CameraShake::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> FxManager.clear());
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        while (FIRE_FIST_KEY.consumeClick()) {
            tryCast(mc);
        }
        if (!mc.isPaused()) {
            FxManager.tick(mc);
        }
    }

    private static void tryCast(Minecraft mc) {
        if (mc.screen != null || mc.player.isSpectator() || !mc.player.isAlive()) return;
        long now = mc.level.getGameTime();
        if (now < nextLocalCast) return;
        nextLocalCast = now + 10;
        // 누르는 즉시 내 화면에서 준비 연출 시작 (서버 응답을 기다리지 않음)
        FxManager.startPredictedCharge(mc.player);
        PacketDistributor.sendToServer(CastRequestPayload.INSTANCE);
    }
}
