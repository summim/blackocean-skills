package com.blackocean.skills.client;

import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillsMod;
import com.blackocean.skills.SkillsRegistry;
import com.blackocean.skills.client.fx.CameraShake;
import com.blackocean.skills.client.fx.FxManager;
import com.blackocean.skills.client.fx.FxShaders;
import com.blackocean.skills.client.fx.ObjMesh;
import com.blackocean.skills.fire.FireFistProjectile;
import com.blackocean.skills.network.CastRequestPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.network.chat.Component;
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
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** 클라이언트 전용 시작점. 서버(전용 서버)에서는 이 클래스가 아예 불려오지 않는다. */
@Mod(value = SkillsMod.MODID, dist = Dist.CLIENT)
public final class SkillsClient {
    /**
     * 스킬 키 4개 (기본 1, 2, 3, 4). 이름을 1.1 의 "fire_fist"(R) 에서 바꿨기 때문에
     * 예전에 저장된 R 설정을 무시하고 모두 새 기본값 1~4 로 시작한다.
     */
    public static final KeyMapping[] SKILL_KEYS = new KeyMapping[Skill.VALUES.length];

    static {
        int[] defaults = {GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4};
        for (int i = 0; i < SKILL_KEYS.length; i++) {
            SKILL_KEYS[i] = new KeyMapping("key.blackocean_skills.skill" + (i + 1), KeyConflictContext.IN_GAME,
                    InputConstants.Type.KEYSYM, defaults[i], "key.categories.blackocean_skills");
        }
    }

    /** 연타 방지 (서버가 진짜 쿨타임을 판단하고, 이건 연출이 겹치지 않게 막는 용도) */
    private static long nextLocalCast;

    public SkillsClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SkillsClientConfig.SPEC);

        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            for (KeyMapping k : SKILL_KEYS) e.register(k);
        });
        modBus.addListener(RegisterShadersEvent.class, FxShaders::onRegisterShaders);
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, e -> {
            e.registerEntityRenderer(SkillsRegistry.FIRE_FIST.get(), NoopRenderer::new);
            e.registerEntityRenderer(SkillsRegistry.FIRE_BULLET.get(), NoopRenderer::new);
        });
        modBus.addListener(RegisterClientReloadListenersEvent.class,
                e -> e.registerReloadListener((ResourceManagerReloadListener) rm -> ObjMesh.clearCache()));
        modBus.addListener(RegisterGuiLayersEvent.class, SkillBarHud::register);

        // Pre: 바닐라가 핫바 키(1~4)를 처리하기 전에 스킬 키를 먼저 가져간다
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre.class, SkillsClient::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, SkillsClient::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, FxManager::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeCameraAngles.class, CameraShake::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
            if (e.getLevel().isClientSide() && e.getEntity() instanceof FireFistProjectile fist) FxManager.onFistJoin(fist);
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            FxManager.clear();
            SkillBarHud.clear();
        });
    }

    private static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        for (int i = 0; i < SKILL_KEYS.length; i++) {
            KeyMapping key = SKILL_KEYS[i];
            boolean pressed = false;
            while (key.consumeClick()) {
                pressed = true;
                tryCast(mc, Skill.VALUES[i]);
            }
            // 스킬 키가 핫바 키와 같은 키면, 이번에 눌린 핫바 칸 이동은 취소 (스킬만 나감)
            if (pressed && SkillsClientConfig.BLOCK_HOTBAR_KEYS.get() && !mc.player.isSpectator()) {
                for (KeyMapping hot : mc.options.keyHotbarSlots) {
                    if (hot.same(key)) {
                        while (hot.consumeClick()) { }
                    }
                }
            }
        }
    }

    private static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (!mc.isPaused()) {
            FxManager.tick(mc);
            SkillBarHud.tick();
        }
    }

    private static void tryCast(Minecraft mc, Skill skill) {
        if (mc.screen != null || mc.player.isSpectator() || !mc.player.isAlive()) return;
        long now = mc.level.getGameTime();
        if (now < nextLocalCast) return;
        nextLocalCast = now + 6;
        if (SkillBarHud.coolingDown(skill)) {
            // 쿨타임 중엔 서버에 물어보지 않고 바로 안내 (가짜 준비 연출·소리도 안 냄)
            mc.player.displayClientMessage(Component.translatable("msg.blackocean_skills.cooldown",
                    Component.translatable(skill.translationKey()),
                    String.format("%.1f", SkillBarHud.remaining(skill) / 20.0)), true);
            SkillBarHud.flashDenied(skill);
            return;
        }
        // 누르는 즉시 내 화면에서 준비 연출 시작 (서버 응답을 기다리지 않음)
        FxManager.startPredictedCharge(mc.player, skill);
        SkillBarHud.flashPressed(skill);
        PacketDistributor.sendToServer(new CastRequestPayload(skill.ordinal()));
    }
}
