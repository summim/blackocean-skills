package com.blackocean.skills.network;

import com.blackocean.skills.SkillsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 서버 → 클라이언트: 범위 스킬이 터진 순간 (서버가 피해를 준 바로 그 틱).
 *
 * @param kind   PILLAR_ERUPT(불기둥 분출) 또는 RING_BURST(화염 고리 폭발)
 * @param size   불기둥: 반지름, 화염 고리: 파도가 닿는 반지름
 * @param height 불기둥 높이 (화염 고리는 0)
 * @param duration 연출 길이 (틱)
 */
public record SkillFxPayload(int kind, int casterId, double x, double y, double z, float size, float height, int duration)
        implements CustomPacketPayload {
    public static final int PILLAR_ERUPT = 0;
    public static final int RING_BURST = 1;

    public static final Type<SkillFxPayload> TYPE = new Type<>(SkillsMod.id("skill_fx"));
    public static final StreamCodec<FriendlyByteBuf, SkillFxPayload> CODEC = StreamCodec.<FriendlyByteBuf, SkillFxPayload>of(
            (buf, p) -> {
                buf.writeVarInt(p.kind());
                buf.writeVarInt(p.casterId());
                buf.writeDouble(p.x());
                buf.writeDouble(p.y());
                buf.writeDouble(p.z());
                buf.writeFloat(p.size());
                buf.writeFloat(p.height());
                buf.writeVarInt(p.duration());
            },
            buf -> new SkillFxPayload(buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
