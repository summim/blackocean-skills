package com.blackocean.skills.network;

import com.blackocean.skills.SkillsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * 서버 → 클라이언트: 스킬이 실제로 일어난 순간 (서버가 피해를 준 바로 그 틱).
 * 화면 연출은 전부 이 신호 하나로 받는다. 각 칸의 뜻은 type 마다 다르다 (SkillEvents 참고).
 */
public record SkillEventPayload(int skill, int type, int casterId,
                                double x, double y, double z,
                                float ax, float ay, float az,
                                float size, float size2, int duration, int target) implements CustomPacketPayload {
    public static final Type<SkillEventPayload> TYPE = new Type<>(SkillsMod.id("skill_event"));
    public static final StreamCodec<FriendlyByteBuf, SkillEventPayload> CODEC = StreamCodec.<FriendlyByteBuf, SkillEventPayload>of(
            (buf, p) -> {
                buf.writeVarInt(p.skill() + 1);
                buf.writeVarInt(p.type());
                buf.writeVarInt(p.casterId() + 1);
                buf.writeDouble(p.x());
                buf.writeDouble(p.y());
                buf.writeDouble(p.z());
                buf.writeFloat(p.ax());
                buf.writeFloat(p.ay());
                buf.writeFloat(p.az());
                buf.writeFloat(p.size());
                buf.writeFloat(p.size2());
                buf.writeVarInt(p.duration());
                buf.writeVarInt(p.target() + 1);
            },
            buf -> new SkillEventPayload(buf.readVarInt() - 1, buf.readVarInt(), buf.readVarInt() - 1,
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readVarInt() - 1));

    public Vec3 pos() {
        return new Vec3(x, y, z);
    }

    public Vec3 vec() {
        return new Vec3(ax, ay, az);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
