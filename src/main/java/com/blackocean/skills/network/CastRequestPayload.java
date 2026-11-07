package com.blackocean.skills.network;

import com.blackocean.skills.SkillsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 클라이언트 → 서버: "이 키(0~4)의 스킬을 쓰겠다". 어떤 스킬인지는 서버가 그 플레이어의 능력으로 정한다. */
/** yaw/pitch = 키를 뗀 순간의 시선 (조준 표시와 실제 방향을 맞추려고) */
public record CastRequestPayload(int slot, float yaw, float pitch) implements CustomPacketPayload {
    public static final Type<CastRequestPayload> TYPE = new Type<>(SkillsMod.id("cast_skill"));
    public static final StreamCodec<FriendlyByteBuf, CastRequestPayload> CODEC = StreamCodec.<FriendlyByteBuf, CastRequestPayload>of(
            (buf, p) -> {
                buf.writeVarInt(p.slot());
                buf.writeFloat(p.yaw());
                buf.writeFloat(p.pitch());
            },
            buf -> new CastRequestPayload(buf.readVarInt(), buf.readFloat(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
