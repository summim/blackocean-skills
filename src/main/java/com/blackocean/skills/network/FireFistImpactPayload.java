package com.blackocean.skills.network;

import com.blackocean.skills.SkillsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 서버 → 클라이언트: 화염 주먹이 맞았다.
 * 서버가 피해를 준 바로 그 틱에 보내며, 정확한 좌표와 맞은 면의 방향(법선)을 담는다.
 * 클라이언트는 이 좌표에서 폭발을 시작하므로 판정과 화면이 어긋나지 않는다.
 *
 * @param fizzle true 면 아무것도 안 맞고 사거리 끝에서 흩어진 것 (작은 연출, 피해 없음)
 * @param kind   0 = 화염 주먹, 1 = 불꽃 탄환 (작은 폭발)
 */
public record FireFistImpactPayload(int projectileId, double x, double y, double z,
                                    float nx, float ny, float nz, int hitEntityId, boolean fizzle, int kind)
        implements CustomPacketPayload {
    public static final Type<FireFistImpactPayload> TYPE = new Type<>(SkillsMod.id("fire_fist_impact"));
    public static final StreamCodec<FriendlyByteBuf, FireFistImpactPayload> CODEC = StreamCodec.<FriendlyByteBuf, FireFistImpactPayload>of(
            (buf, p) -> {
                buf.writeVarInt(p.projectileId());
                buf.writeDouble(p.x());
                buf.writeDouble(p.y());
                buf.writeDouble(p.z());
                buf.writeFloat(p.nx());
                buf.writeFloat(p.ny());
                buf.writeFloat(p.nz());
                buf.writeVarInt(p.hitEntityId() + 1);
                buf.writeBoolean(p.fizzle());
                buf.writeVarInt(p.kind());
            },
            buf -> new FireFistImpactPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt() - 1, buf.readBoolean(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
