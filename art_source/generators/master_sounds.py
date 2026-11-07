"""모든 스킬 효과음 마스터링 + sounds.json 생성 (소리의 단일 기준표).

사용법: python3 master_sounds.py [프로젝트 루트]
  원본: art_source/sound_raw/<경로>.ogg   (make_v5_sounds.py 는 여기에 바로 씀. 예전 생성기(make_sounds / make_v4_sounds /
        make_ice_assets / make_thunder_assets)는 assets 에 바로 쓰므로, 다시 돌렸다면 그 ogg 를 sound_raw 로 옮긴 뒤 이 스크립트를 돌린다)
  결과: src/main/resources/assets/blackocean_skills/sounds/skill/<경로>.ogg  (폴더를 지우고 새로 씀)
        src/main/resources/assets/blackocean_skills/sounds.json

체인 (audiolib.master): DC 제거 → 35Hz 하이패스 → 고역 다듬기(방송용) → 앞뒤 무음 정리·꼬리 페이드
        → 목표 "최대 순간 음량"(K-가중 400ms, LUFS) → 4배 오버샘플 피크 리미터 -1dBFS → 모노 48kHz vorbis q4

음량 등급 (게임 안에서 소리 크기는 파일이 정한다: Minecraft 는 재생 볼륨을 1 로 잘라 버리고,
1 보다 큰 값은 들리는 거리만 늘린다 → 거리는 SkillSounds 의 반경으로, 크기는 여기서 정함)
"""
import json
import os
import shutil
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from audiolib import SR, load, save_ogg, master, pitch_shift, shelf, momentary_max, true_peak_db, band_ratio  # noqa: E402

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
RAW = ROOT + '/art_source/sound_raw/'
ASSETS = ROOT + '/src/main/resources/assets/blackocean_skills/'
OUT = ASSETS + 'sounds/skill/'

# ---- 음량 등급 (최대 순간 음량, LUFS) ----
CHARGE = -20.0      # 모으기 (작은 스킬)
ULT_CHARGE = -15.0  # 궁극기 모으기·솟구침
LOOP = -19.0        # 따라다니는 반복음
TAIL = -20.0        # 잔불·잔향 꼬리
RAPID = -18.0       # 아주 자주 나는 소리 (연사·파편·여진 낙뢰)
CAST = -16.0        # 발사·시전
HIT = -15.0         # 작은 적중
IMPACT = -13.0      # 스킬 폭발·광역
BIG = -12.0         # 큰 광역 / 궁극기 중간 단계
ULT = -10.0         # 궁극기 최종 타격
STINGER = -14.0     # 궁극기 공용 알림


def V(raw, pitch=1.0, tilt=0.0):
    return dict(raw=raw, pitch=pitch, tilt=tilt)


# 이벤트 → (등급, [원본 변형...], 반복음, 최대 길이(초), sounds.json 변형별 (볼륨, 음정))
# 변형이 하나면 그대로, 여럿이면 <이름>_1.. 로 저장.
EVENTS = {
    # ---------------- 화염
    'fire_fist.charge': (CHARGE, [V('fire_fist/charge')]),
    'fire_fist.launch': (CAST, [V('fire_fist/launch')]),
    'fire_fist.travel': (LOOP, [V('fire_fist/travel')], dict(loop=True)),
    'fire_fist.impact': (IMPACT, [V('fire_fist/impact')]),
    'fire_fist.crackle': (TAIL, [V('fire_fist/crackle')]),
    'fire_bullets.charge': (CHARGE, [V('fire_bullets/charge')]),
    'fire_bullets.shot': (RAPID, [V('fire_bullets/shot'), V('fire_bullets/shot', 0.93, 1.0), V('fire_bullets/shot', 1.07, -1.0)]),
    'fire_bullets.impact': (RAPID, [V('fire_bullets/impact'), V('fire_bullets/impact', 0.92, 1.0), V('fire_bullets/impact', 1.08, -1.0)]),
    'fire_pillar.charge': (CHARGE, [V('fire_pillar/charge')]),
    'fire_pillar.warn': (CHARGE, [V('fire_pillar/warn')]),
    'fire_pillar.erupt': (IMPACT, [V('fire_pillar/erupt')]),
    'flame_ring.charge': (CHARGE, [V('flame_ring/charge')]),
    'flame_ring.burst': (IMPACT, [V('flame_ring/burst')]),
    'fire.sun_charge': (ULT_CHARGE, [V('fire/sun_charge')]),
    'fire.sun_launch': (BIG, [V('fire/sun_launch')]),
    'fire.sun_impact': (ULT, [V('fire/sun_impact')]),
    'fire.sun_burn': (TAIL, [V('fire/sun_burn')]),
    # ---------------- 번개
    'lightning.charge': (CHARGE, [V('lightning/charge')]),
    'lightning.zap': (HIT, [V('lightning/zap_1'), V('lightning/zap_2'), V('lightning/zap_3')]),
    'lightning.thunder': (BIG, [V('lightning/thunder')]),
    'lightning.strike': (IMPACT, [V('lightning/strike_1'), V('lightning/strike_2'), V('lightning/strike_3')]),
    'lightning.blink': (CAST, [V('lightning/blink')]),
    'lightning.blink_arrive': (CAST, [V('lightning/blink_arrive')]),
    'lightning.discharge': (IMPACT, [V('lightning/discharge')]),
    'lightning.storm_charge': (ULT_CHARGE, [V('lightning/storm_charge')]),
    'lightning.storm_end': (ULT, [V('lightning/storm_end')]),
    # ---------------- 바람
    'wind.charge': (CHARGE, [V('wind/charge')]),
    'wind.blade': (CAST, [V('wind/blade'), V('wind/blade', 0.94, 0.8)]),
    'wind.blade_hit': (HIT, [V('wind/blade_hit_1'), V('wind/blade_hit_2')]),
    'wind.dash': (CAST, [V('wind/dash')]),
    'wind.tornado': (CAST, [V('wind/tornado')], dict(loop=True)),
    'wind.tornado_end': (IMPACT, [V('wind/tornado_end')]),
    'wind.burst': (IMPACT, [V('wind/burst')]),
    'wind.tempest_charge': (ULT_CHARGE, [V('wind/tempest_charge')]),
    # ---------------- 물
    'water.charge': (CHARGE, [V('water/charge')]),
    'water.shot': (CAST, [V('water/shot'), V('water/shot', 1.07, -0.8)]),
    'water.splash': (HIT, [V('water/splash'), V('water/splash', 0.93, 1.0), V('water/splash', 1.06, -1.0)]),
    'water.jet': (LOOP + 1, [V('water/jet')], dict(maxlen=1.8, fade=0.3)),
    'water.prison': (CAST, [V('water/prison')]),
    'water.burst': (IMPACT, [V('water/burst')]),
    'water.wave': (BIG, [V('water/wave')]),
    'water.dragon_charge': (ULT_CHARGE, [V('water/dragon_charge')]),
    'water.dragon': (BIG, [V('water/dragon')]),
    'water.dragon_impact': (ULT, [V('water/dragon_impact')]),
    # ---------------- 얼음
    'ice.charge': (CHARGE, [V('ice/charge')]),
    'ice.shot': (CAST, [V('ice/shot'), V('ice/shot', 0.95, 0.8)]),
    'ice.shards': (CAST, [V('ice/shards')]),
    'ice.shatter': (HIT, [V('ice/shatter'), V('ice/shatter', 0.92, 1.2), V('ice/shatter', 1.06, -0.5)]),
    'ice.spikes': (RAPID, [V('ice/spikes'), V('ice/spikes', 0.92, 1.0), V('ice/spikes', 1.08, -1.0)]),
    'ice.nova': (IMPACT, [V('ice/nova')]),
    'ice.age_charge': (ULT_CHARGE, [V('ice/age_charge')]),
    'ice.glacier': (ULT, [V('ice/glacier')]),
    'ice.glacier_shatter': (ULT + 1, [V('ice/glacier_shatter')]),
    # ---------------- 천둥
    'thunder.charge': (CHARGE + 1, [V('thunder/charge')]),
    'thunder.hammer': (CAST, [V('thunder/hammer')]),
    'thunder.hammer_impact': (IMPACT, [V('thunder/hammer_impact')]),
    'thunder.leap': (CAST, [V('thunder/leap')]),
    'thunder.clap': (IMPACT, [V('thunder/clap')]),
    'thunder.slam': (IMPACT, [V('thunder/slam')]),
    'thunder.roar': (CAST, [V('thunder/roar')]),
    'thunder.roar_burst': (IMPACT, [V('thunder/roar_burst')]),
    'thunder.rise': (ULT_CHARGE, [V('thunder/rise')]),
    'thunder.aftershock': (BIG - 1, [V('thunder/aftershock_1'), V('thunder/aftershock_2')]),
    'thunder.judgment': (ULT, [V('thunder/judgment')]),
    # ---------------- 공용
    'common.ultimate': (STINGER, [V('common/ultimate')]),
    'common.transform': (IMPACT, [V('common/transform')]),
}

# 변형이 여럿인 이벤트: sounds.json 에서도 음정·볼륨을 조금씩 흔듦 (같은 파일이 연달아 나와도 덜 기계적)
JSON_JITTER = [(1.0, 1.0), (0.92, 0.97), (0.96, 1.03)]


def render(spec, target, opts):
    x = load(RAW + spec['raw'] + '.ogg')
    loop = opts.get('loop', False)
    if spec['pitch'] != 1.0:
        x = pitch_shift(x, spec['pitch'])
    if spec['tilt']:
        x = shelf(x, 1500, spec['tilt'], high=False)  # +: 저역 강조(어둡게), -: 밝게
    if 'maxlen' in opts:
        n = int(opts['maxlen'] * SR)
        x = x[:n].copy()
        nf = int(opts.get('fade', 0.2) * SR)
        x[-nf:] *= np.cos(np.linspace(0, np.pi / 2, nf)) ** 2
    if loop:
        # 반복음: 필터 상태가 이음매를 깨지 않도록 세 번 이어 붙여 처리하고 가운데만 씀
        n = len(x)
        y = master(np.concatenate([x, x, x]), target, loop=True)
        return y[n:2 * n]
    return master(x, target)


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    sounds_json = {}
    total = 0
    print(f"{'event':26s} {'file':34s} {'dur':>5s} {'Mmax':>6s} {'tpk':>5s} {'2.5-8k':>6s}")
    for name, entry in EVENTS.items():
        target, specs = entry[0], entry[1]
        opts = entry[2] if len(entry) > 2 else {}
        base = specs[0]['raw'].rsplit('_', 1)[0] if len(specs) > 1 and specs[0]['raw'][-2] == '_' else specs[0]['raw']
        rels = [base] if len(specs) == 1 else [f'{base}_{i + 1}' for i in range(len(specs))]
        entries = []
        for i, (spec, rel) in enumerate(zip(specs, rels)):
            y = render(spec, target, opts)
            path = OUT + rel + '.ogg'
            save_ogg(path, y)
            dec = load(path)
            total += os.path.getsize(path)
            print(f'{name:26s} {rel:34s} {len(dec) / SR:5.2f} {momentary_max(dec):6.1f} {true_peak_db(dec):5.1f} {band_ratio(dec, 2500, 8000):6.3f}')
            e = {'name': f'blackocean_skills:skill/{rel}'}
            if len(specs) > 1:
                vol, pit = JSON_JITTER[i % len(JSON_JITTER)]
                if vol != 1.0: e['volume'] = vol
                if pit != 1.0: e['pitch'] = pit
            entries.append(e)
        sounds_json[name] = {'category': 'player', 'subtitle': f'subtitles.blackocean_skills.{name}', 'sounds': entries}
    with open(ASSETS + 'sounds.json', 'w', encoding='utf-8') as f:
        json.dump(sounds_json, f, indent=2, ensure_ascii=False)
        f.write('\n')
    print(f'{len(sounds_json)} events, total {total / 1024 / 1024:.2f} MB')


if __name__ == '__main__':
    main()
