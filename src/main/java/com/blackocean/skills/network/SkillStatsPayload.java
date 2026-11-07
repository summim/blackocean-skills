package com.blackocean.skills.network;

import com.blackocean.skills.SkillsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 서버 → 클라이언트 (접속할 때, 설정을 고칠 때): 조준 표시에 쓰는 서버 설정값.
 * 스킬 번호 순서로 사거리·반경·지속 틱·속도.
 */
public record SkillStatsPayload(float[] range, float[] radius, float[] duration, float[] speed) implements CustomPacketPayload {
    public static final Type<SkillStatsPayload> TYPE = new Type<>(SkillsMod.id("skill_stats"));
    public static final StreamCodec<FriendlyByteBuf, SkillStatsPayload> CODEC = StreamCodec.<FriendlyByteBuf, SkillStatsPayload>of(
            (buf, p) -> {
                buf.writeVarInt(p.range().length);
                for (int i = 0; i < p.range().length; i++) {
                    buf.writeFloat(p.range()[i]);
                    buf.writeFloat(p.radius()[i]);
                    buf.writeFloat(p.duration()[i]);
                    buf.writeFloat(p.speed()[i]);
                }
            },
            buf -> {
                int n = Math.min(buf.readVarInt(), 256);
                float[] a = new float[n], b = new float[n], c = new float[n], d = new float[n];
                for (int i = 0; i < n; i++) {
                    a[i] = buf.readFloat();
                    b[i] = buf.readFloat();
                    c[i] = buf.readFloat();
                    d[i] = buf.readFloat();
                }
                return new SkillStatsPayload(a, b, c, d);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
