package com.blackocean.skills.network;

import com.blackocean.skills.SkillsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 서버 → 클라이언트: "이 플레이어가 스킬 준비를 시작했다" (준비 연출용).
 * 불기둥은 (x, y, z) 에 반지름 size 의 경고 원을 그린다. 다른 스킬은 좌표·크기를 쓰지 않는다.
 */
public record CastStartPayload(int casterId, int skill, int chargeTicks, double x, double y, double z, float size) implements CustomPacketPayload {
    public static final Type<CastStartPayload> TYPE = new Type<>(SkillsMod.id("skill_start"));
    public static final StreamCodec<FriendlyByteBuf, CastStartPayload> CODEC = StreamCodec.<FriendlyByteBuf, CastStartPayload>of(
            (buf, p) -> {
                buf.writeVarInt(p.casterId());
                buf.writeVarInt(p.skill());
                buf.writeVarInt(p.chargeTicks());
                buf.writeDouble(p.x());
                buf.writeDouble(p.y());
                buf.writeDouble(p.z());
                buf.writeFloat(p.size());
            },
            buf -> new CastStartPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
