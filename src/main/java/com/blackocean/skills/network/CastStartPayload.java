package com.blackocean.skills.network;

import com.blackocean.skills.SkillsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 서버 → 클라이언트: "이 플레이어가 스킬 준비를 시작했다" (준비 연출용).
 * 땅 조준 스킬은 (x, y, z) 에 반지름 size 의 경고 원을 그린다. yaw/pitch = 고정된 시전 방향 (시전자 몸이 그쪽을 봄).
 */
public record CastStartPayload(int casterId, int skill, int chargeTicks, double x, double y, double z, float size, float yaw, float pitch) implements CustomPacketPayload {
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
                buf.writeFloat(p.yaw());
                buf.writeFloat(p.pitch());
            },
            buf -> new CastStartPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
