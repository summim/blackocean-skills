package com.blackocean.skills.client;

import com.blackocean.skills.Skill;
import com.blackocean.skills.SkillServer;
import com.blackocean.skills.SkillsMod;
import com.blackocean.skills.SkillsRegistry;
import com.blackocean.skills.client.fx.AimPreview;
import com.blackocean.skills.client.fx.CameraShake;
import com.blackocean.skills.client.fx.FxManager;
import com.blackocean.skills.client.fx.FxShaders;
import com.blackocean.skills.client.fx.ObjMesh;
import com.blackocean.skills.client.fx.ScreenFx;
import com.blackocean.skills.entity.SkillProjectile;
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

import java.util.Arrays;
import java.util.Locale;

/** 클라이언트 전용 시작점. 서버(전용 서버)에서는 이 클래스가 아예 불려오지 않는다. */
@Mod(value = SkillsMod.MODID, dist = Dist.CLIENT)
public final class SkillsClient {
    /** 스킬 키 5개 (기본 1~5, 5번 = 궁극기). 어떤 스킬인지는 내 능력에 따라 정해진다. */
    public static final KeyMapping[] SKILL_KEYS = new KeyMapping[Skill.SLOTS];

    static {
        int[] defaults = {GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_5};
        for (int i = 0; i < SKILL_KEYS.length; i++) {
            SKILL_KEYS[i] = new KeyMapping("key.blackocean_skills.skill" + (i + 1), KeyConflictContext.IN_GAME,
                    InputConstants.Type.KEYSYM, defaults[i], "key.categories.blackocean_skills");
        }
    }

    /** 같은 키 연타 방지 (키마다 따로) */
    private static final long[] nextLocalCast = new long[Skill.SLOTS];
    /** 지난 틱에 눌려 있었는지 (누름·뗌 순간을 알아내기 위해) */
    private static final boolean[] wasDown = new boolean[Skill.SLOTS];
    /** 조준 중 우클릭으로 취소함 → 이번에 키를 떼도 발동하지 않음 */
    private static boolean aimCancelled;

    public SkillsClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SkillsClientConfig.SPEC);

        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            for (KeyMapping k : SKILL_KEYS) e.register(k);
        });
        modBus.addListener(RegisterShadersEvent.class, FxShaders::onRegisterShaders);
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class,
                e -> e.registerEntityRenderer(SkillsRegistry.SKILL_PROJECTILE.get(), NoopRenderer::new));
        modBus.addListener(RegisterClientReloadListenersEvent.class,
                e -> e.registerReloadListener((ResourceManagerReloadListener) rm -> {
                    ObjMesh.clearCache();
                    SkillBarHud.invalidateCaches(); // 아이콘 유무·번역 글자 (언어 변경도 여기로 옴)
                }));
        modBus.addListener(RegisterGuiLayersEvent.class, e -> {
            ScreenFx.register(e);
            SkillBarHud.register(e);
        });

        // Pre: 바닐라가 핫바 키(1~5)를 처리하기 전에 스킬 키를 먼저 가져간다
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre.class, SkillsClient::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, SkillsClient::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, FxManager::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(com.blackocean.skills.client.fx.CasterPose::onRenderLiving);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, com.blackocean.skills.client.fx.DamageNumbers::onRender);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeCameraAngles.class, CameraShake::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeFogColor.class, ScreenFx::onFogColor);
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
            if (e.getLevel().isClientSide() && e.getEntity() instanceof SkillProjectile p) FxManager.onProjectileJoin(p);
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            FxManager.clear();
            SkillBarHud.clear();
            ClientSkillStats.clear();
            ClientElement.set(null);
            AimPreview.stop();
            ScreenFx.clear();
        });
    }

    private static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // 창(인벤토리·채팅 등)이 열리면 조준 취소. 창이 열리며 키가 풀려도 발동하지 않게.
        if (mc.screen != null || !mc.player.isAlive() || mc.player.isSpectator()) {
            if (AimPreview.aiming() != null) AimPreview.stop();
            aimCancelled = false;
            Arrays.fill(wasDown, false);
            for (KeyMapping key : SKILL_KEYS) while (key.consumeClick()) { }
            return;
        }

        boolean holdToAim = SkillsClientConfig.HOLD_TO_AIM.get();
        for (int i = 0; i < SKILL_KEYS.length; i++) {
            KeyMapping key = SKILL_KEYS[i];
            int clicks = 0;
            while (key.consumeClick()) clicks++;
            boolean down = key.isDown();

            // 스킬 키가 핫바 키와 같은 키면, 이번에 눌린 핫바 칸 이동은 취소 (스킬만 나감). 능력이 없으면 핫바 그대로.
            if ((clicks > 0 || down) && SkillsClientConfig.BLOCK_HOTBAR_KEYS.get() && ClientElement.get() != null) {
                for (KeyMapping hot : mc.options.keyHotbarSlots) {
                    if (hot.same(key)) {
                        while (hot.consumeClick()) { }
                    }
                }
            }
            Skill skill = ClientElement.skill(i);

            if (skill == null) { // 능력 없음 → 키는 핫바용 그대로
                wasDown[i] = down;
                continue;
            }
            if (!holdToAim) {
                if (clicks > 0) tryCast(mc, i);
                wasDown[i] = down;
                continue;
            }

            boolean pressed = !wasDown[i] && (down || clicks > 0);
            boolean released = wasDown[i] && !down;
            if (pressed && down) {
                AimPreview.start(skill); // 누르기 시작 → 조준 표시 (나중에 누른 키가 이김)
                aimCancelled = false;
            } else if (pressed) {
                tryCast(mc, i); // 한 틱 안에 누르고 뗌 (아주 빠른 톡) → 바로 발동
            }
            if (released && AimPreview.aiming() == skill) {
                // 떼는 순간 화면에 보이던 조준 그대로 발동 (보이던 표시를 계산한 시선, 안 보였으면 지금 시선)
                float[] shown = AimPreview.shownAim(skill);
                AimPreview.stop();
                if (!aimCancelled) {
                    if (shown != null) tryCast(mc, i, shown[0], shown[1]);
                    else tryCast(mc, i);
                }
                aimCancelled = false;
            }
            wasDown[i] = down;
        }

        // 조준 중 우클릭 → 취소 (이번 우클릭은 아이템 사용에 쓰지 않음)
        if (AimPreview.aiming() != null) {
            boolean cancel = false;
            while (mc.options.keyUse.consumeClick()) cancel = true;
            if (cancel) {
                AimPreview.stop();
                aimCancelled = true;
                // 누르고 있는 우클릭으로 바닐라가 같은 틱에 아이템을 쓰지 않게 (열매 먹기·양손 아이템·블록 놓기가 취소와 함께 나가던 문제)
                mc.options.keyUse.setDown(false);
            }
        }
    }

    private static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (!mc.isPaused()) {
            try {
                FxManager.tick(mc);
                ScreenFx.tick();
            } catch (RuntimeException ex) {
                // 연출 오류로 게임이 튕기지 않게: 기록하고 연출을 모두 비움 (판정은 서버라 게임에는 영향 없음)
                FxManager.fxError("tick", ex);
                FxManager.clear();
                ScreenFx.clear();
            }
            SkillBarHud.tick();
        }
    }

    private static void tryCast(Minecraft mc, int slot) {
        if (mc.player == null) return;
        tryCast(mc, slot, mc.player.getYRot(), mc.player.getXRot());
    }

    /** yaw/pitch = 발동 방향 (조준 표시가 보이던 시선). 서버가 이 방향으로 고정해서 쏨 */
    private static void tryCast(Minecraft mc, int slot, float yaw, float pitch) {
        if (mc.player == null || mc.level == null || mc.player.isSpectator() || !mc.player.isAlive()) return;
        Skill skill = ClientElement.skill(slot);
        if (skill == null) {
            mc.player.displayClientMessage(Component.translatable("msg.blackocean_skills.no_element"), true);
            return;
        }
        long now = mc.level.getGameTime();
        if (now < nextLocalCast[slot]) return;
        nextLocalCast[slot] = now + 3;

        int left = SkillBarHud.remaining(skill);
        if (left > SkillServer.BUFFER_TICKS) {
            // 쿨타임이 넉넉히 남음 → 서버에 묻지 않고 바로 안내
            mc.player.displayClientMessage(Component.translatable("msg.blackocean_skills.cooldown",
                    Component.translatable(skill.translationKey()), String.format(Locale.ROOT, "%.1f", left / 20.0)), true);
            SkillBarHud.flashDenied(skill);
            return;
        }
        if (left > 0 || FxManager.isCharging(mc.player)) {
            // 쿨타임이 거의 끝났거나 다른 스킬을 모으는 중 → 서버가 예약해 두었다가 되는 순간 바로 발동
            // (예약 발동은 서버가 그 순간의 시선으로 쏨 → 미리 준비 연출을 시작하지 않고 서버 신호를 기다림)
            SkillBarHud.flashQueued(skill);
            PacketDistributor.sendToServer(new CastRequestPayload(slot, yaw, pitch));
            return;
        }
        // 떼는 즉시 내 화면에서 준비 연출 시작 (서버 응답을 기다리지 않음).
        // 준비 연출·바닥 자리 예측도 보낼 방향과 같은 시선으로 계산 (잠깐 시선을 바꿨다가 바로 되돌림, 같은 틱 안이라 화면엔 영향 없음)
        net.minecraft.client.player.LocalPlayer p = mc.player;
        float oy = p.getYRot(), ox = p.getXRot();
        boolean swap = oy != yaw || ox != pitch;
        if (swap) {
            p.setYRot(yaw);
            p.setXRot(pitch);
        }
        try {
            FxManager.startPredictedCharge(p, skill);
        } catch (RuntimeException ex) {
            FxManager.fxError("predicted_charge", ex); // 연출 오류로 게임이 튕기거나 시전 요청이 안 나가지 않게
        } finally {
            if (swap) {
                p.setYRot(oy);
                p.setXRot(ox);
            }
        }
        SkillBarHud.flashPressed(skill);
        PacketDistributor.sendToServer(new CastRequestPayload(slot, yaw, pitch));
    }
}
