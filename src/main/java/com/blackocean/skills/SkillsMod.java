package com.blackocean.skills;

import com.blackocean.skills.SkillServer;
import com.blackocean.skills.network.SkillsNetwork;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 블랙오션 스킬 모드 시작점 (서버·클라이언트 공통).
 * 화면 연출 코드는 client 패키지의 SkillsClient 에만 있고, 서버에서는 절대 불러오지 않는다.
 */
@Mod(SkillsMod.MODID)
public final class SkillsMod {
    public static final String MODID = "blackocean_skills";

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public SkillsMod(IEventBus modBus, ModContainer container) {
        SkillsRegistry.ENTITIES.register(modBus);
        SkillsRegistry.SOUNDS.register(modBus);
        container.registerConfig(ModConfig.Type.COMMON, SkillsConfig.SPEC);

        modBus.addListener(RegisterPayloadHandlersEvent.class, SkillsNetwork::register);

        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, SkillServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, SkillServer::onLogout);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> SkillServer.clear());
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, SkillsCommands::register);
    }
}
