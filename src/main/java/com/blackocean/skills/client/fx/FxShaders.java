package com.blackocean.skills.client.fx;

import com.blackocean.skills.SkillsMod;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;

/**
 * 흐르는 화염 셰이더 등록.
 * 파일: assets/blackocean_skills/shaders/core/fire_flow.json (.vsh / .fsh)
 * 불러오기에 실패해도 게임은 멈추지 않고, 연출만 단순한 텍스처로 대체된다.
 */
public final class FxShaders {
    private static final Logger LOG = LogUtils.getLogger();
    static ShaderInstance fireFlow;

    private FxShaders() {}

    public static void onRegisterShaders(RegisterShadersEvent event) {
        fireFlow = null; // 다시 불러오기(F3+T) 실패 시 이미 닫힌 셰이더를 쓰지 않도록
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(), SkillsMod.id("fire_flow"), DefaultVertexFormat.POSITION_TEX_COLOR),
                    s -> fireFlow = s);
        } catch (Exception e) {
            fireFlow = null;
            LOG.error("[BlackOcean Skills] 화염 셰이더를 불러오지 못했습니다. 단순 연출로 대체합니다.", e);
        }
    }
}
