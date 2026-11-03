package com.blackocean.skills.network;

import com.blackocean.skills.SkillsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 서버 → 클라이언트 (접속할 때): 조준 표시에 쓰는 서버 설정값.
 * 서버 설정을 바꿔도 시청자 화면의 사거리·범위 표시가 정확히 맞도록 서버 값을 보내 준다.
 */
public record SkillStatsPayload(float fistRange, float fistHitRadius, float fistSplash,
                                float bulletsRange, float bulletsSplash, float bulletsSpread,
                                float pillarRange, float pillarRadius, float pillarHeight,
                                float ringRadius) implements CustomPacketPayload {
    public static final Type<SkillStatsPayload> TYPE = new Type<>(SkillsMod.id("skill_stats"));
    public static final StreamCodec<FriendlyByteBuf, SkillStatsPayload> CODEC = StreamCodec.<FriendlyByteBuf, SkillStatsPayload>of(
            (buf, p) -> {
                buf.writeFloat(p.fistRange());
                buf.writeFloat(p.fistHitRadius());
                buf.writeFloat(p.fistSplash());
                buf.writeFloat(p.bulletsRange());
                buf.writeFloat(p.bulletsSplash());
                buf.writeFloat(p.bulletsSpread());
                buf.writeFloat(p.pillarRange());
                buf.writeFloat(p.pillarRadius());
                buf.writeFloat(p.pillarHeight());
                buf.writeFloat(p.ringRadius());
            },
            buf -> new SkillStatsPayload(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
