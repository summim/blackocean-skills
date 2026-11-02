package com.blackocean.skills.network;

import com.blackocean.skills.SkillsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 서버 → 클라이언트: "이 플레이어가 시전을 시작했다" (준비 연출용) */
public record CastStartPayload(int casterId, int chargeTicks) implements CustomPacketPayload {
    public static final Type<CastStartPayload> TYPE = new Type<>(SkillsMod.id("fire_fist_start"));
    public static final StreamCodec<FriendlyByteBuf, CastStartPayload> CODEC = StreamCodec.<FriendlyByteBuf, CastStartPayload>of(
            (buf, p) -> {
                buf.writeVarInt(p.casterId());
                buf.writeVarInt(p.chargeTicks());
            },
            buf -> new CastStartPayload(buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
