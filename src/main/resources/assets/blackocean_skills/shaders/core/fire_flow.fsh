#version 150

// 흐르는 능력 셰이더 (불·번개·바람·물 공용, 색 단계표만 다름)
// - 노이즈 텍스처를 크기·속도가 다른 두 겹으로 흘려 일렁임을 만든다.
// - 정점 색 알파 = 열기 (1 = 흰빛 중심, 0 = 붉은 가장자리).
// - 열기를 색 단계표로 바꾸고, 애니풍으로 띠를 살짝 나눠 외곽선이 읽히게 한다.
// - FireParams: x = 흐르는 속도, y = 갉아 먹기(0 꽉 참 → 1 다 탐), z = 밝기 배율, w = 흰빛 타오름(0~1, 폭발 핵·섬광)
// 더하기 섞기(ONE, ONE)로 그리므로 색에 알파를 미리 곱해서 내보낸다.

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec4 ColorModulator;
uniform float GameTime;
uniform vec4 FireParams;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// 색 단계표 (능력마다 다른 그림: fire/lightning/wind/water_ramp.png, 왼쪽 = 가장 뜨거운 색)
vec3 ramp(float t) {
    return texture(Sampler1, vec2(1.0 - clamp(t, 0.0, 1.0), 0.5)).rgb;
}

// 부드러운 계단: 연속값을 4단계 띠로 나누되 경계는 살짝 흐리게
float cel(float v) {
    float s = 0.0;
    s += smoothstep(0.16, 0.20, v);
    s += smoothstep(0.40, 0.44, v);
    s += smoothstep(0.64, 0.68, v);
    s += smoothstep(0.84, 0.88, v);
    return mix(v, s * 0.25, 0.55);
}

void main() {
    float time = GameTime * 1200.0;   // GameTime 은 하루(24000틱)를 0~1 로 나눈 값 → 초 단위로 변환
    float speed = FireParams.x;
    float erosion = FireParams.y;
    float intensity = FireParams.z;

    vec2 uv = texCoord0;
    float n1 = texture(Sampler0, fract(uv * vec2(1.0, 1.0) + vec2(0.0, time * speed * 0.55))).r;
    float n2 = texture(Sampler0, fract(uv * vec2(2.1, 1.7) + vec2(0.37, time * speed * 0.95))).r;
    float n = n1 * 0.62 + n2 * 0.38;

    float heat = vertexColor.a;
    float v = heat * (0.45 + 1.05 * n) * intensity;

    // 외곽 갉아 먹기: 열기가 낮은 곳(가장자리)부터 노이즈 모양대로 구멍이 난다
    float edge = n * 0.75 + heat * 0.45;
    float mask = smoothstep(erosion, erosion + 0.18, edge);

    float c = cel(clamp(v, 0.0, 1.0));
    vec3 col = ramp(c) * vertexColor.rgb;
    // 흰빛 타오름: 뜨거운 곳일수록 하얗게 (HDR 이 없으니 색을 흰색 쪽으로 밀어 눈부심을 흉내)
    float hot = clamp(FireParams.w * (v - 0.35) * 1.6, 0.0, 1.0);
    col = mix(col, vec3(1.0, 0.98, 0.94), hot);
    float a = mask * clamp(v * 1.35, 0.0, 1.0);
    if (a < 0.003) {
        discard;
    }
    fragColor = vec4(col * a, a) * ColorModulator;
}
