package com.blackocean.skills.network;

import com.blackocean.skills.SkillsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 클라이언트 → 서버: "화염 주먹 쓰겠다" (내용 없음) */
public record CastRequestPayload() implements CustomPacketPayload {
    public static final Type<CastRequestPayload> TYPE = new Type<>(SkillsMod.id("cast_fire_fist"));
    public static final CastRequestPayload INSTANCE = new CastRequestPayload();
    public static final StreamCodec<FriendlyByteBuf, CastRequestPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
