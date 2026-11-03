package com.blackocean.skills.network;

import com.blackocean.skills.SkillsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 클라이언트 → 서버: "이 스킬 쓰겠다" (skill = Skill 번호 0~3) */
public record CastRequestPayload(int skill) implements CustomPacketPayload {
    public static final Type<CastRequestPayload> TYPE = new Type<>(SkillsMod.id("cast_skill"));
    public static final StreamCodec<FriendlyByteBuf, CastRequestPayload> CODEC = StreamCodec.<FriendlyByteBuf, CastRequestPayload>of(
            (buf, p) -> buf.writeVarInt(p.skill()),
            buf -> new CastRequestPayload(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
