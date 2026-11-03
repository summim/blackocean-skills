# BlackOcean Skills (블랙오션 스킬) — 이글이글 화염 스킬 4종

숫자키 **1 화염 주먹 · 2 불꽃 탄환 · 3 불기둥 · 4 화염 고리**. 애니풍 화염 셰이더·3D 메시·궤적·충격파·그을음 자국으로 그리는 능력 스킬입니다.
NeoForge 1.21.1 전용. **추가로 설치할 모드가 없습니다** (이 jar 하나로 서버 판정 + 화면 연출 모두 동작).

![미리보기](art_source/fx_preview.png)
> 위 그림은 모드의 셰이더 수식을 그대로 옮겨 그린 **근사 미리보기**입니다. 실제 게임 화면은 조명·배경에 따라 다릅니다.

---

## 1. 빌드하는 법

1. **JDK 21** 이 필요합니다 (돛 애드온 때 설치했다면 그대로 사용).
2. 이 폴더의 **`빌드하기.bat`** 을 더블클릭합니다. 처음엔 5~10분 걸립니다.
3. 끝나면 `build\libs\blackocean_skills-1.3.0.jar` 가 생깁니다.
4. 이 jar 를 **서버 `mods` 폴더**와 **접속기 모드팩(`blackocean-pack`)** 둘 다에 넣습니다.
   모드팩은 jar 를 넣고 `node tools/build-manifest.js` → push 하면 시청자 접속기가 자동으로 받습니다.

> **자동 빌드**: GitHub `summim/blackocean-skills` 에 push 하면 깃허브가 jar 를 만들고,
> 실제 서버를 한 번 켜서 모드가 오류 없이 올라오는지까지 시험합니다. 완성된 jar 는 `dist` 브랜치에 저장됩니다.

---

## 2. 게임 안에서 — 스킬 4종

| 키 | 스킬 | 하는 일 | 쿨타임 |
|---|---|---|---|
| **1** | 화염 주먹 | 0.4초 모았다가 거대한 3D 주먹 발사. 직격 12 + 폭발 범위 6. 맞은 자리에서 작은 불기둥이 솟고 바닥에 그을음 | 6초 |
| **2** | 불꽃 탄환 | 양손 번갈아 작은 화염탄 5발 연사. 한 발에 직격 4 + 범위 2 | 4초 |
| **3** | 불기둥 | 조준한 바닥에 경고 원(0.7초) → 거대한 불기둥이 솟아 피해 10 + 위로 띄움, 1.3초 동안 남아 계속 태움 | 10초 |
| **4** | 화염 고리 | 몸 주위로 불 고리 3개가 0.8초 돌다가 사방 7칸으로 불의 파도. 피해 최대 9 + 크게 밀쳐냄 | 13초 |

- **꾹 눌러 조준**: 스킬 키를 누르고 있으면 날아갈 길·사거리·범위가 바닥에 보이고, **떼는 순간 발동**. 누른 채 **우클릭하면 취소**. 톡 치면 바로 발동.
  - 1·2: 손에서 앞으로 흐르는 화살표 길 (폭 = 판정 폭, 벽·적에서 멈춤) + 떨어지는 곳의 폭발 범위 원
  - 3: 불기둥이 솟을 바닥 원 + 기둥 높이 원 + 발밑 최대 사거리 원 (바닥이 없으면 빨간 원)
  - 4: 발밑의 불의 파도 범위 원
  - 쿨타임이 많이 남았으면 빨갛게 보이고, 조준점 아래에 남은 초가 나옴. 사거리·범위는 접속할 때 서버 설정값을 받아서 그림.
- **입력 예약**: 쿨타임이 0.5초 이내로 남았거나 다른 스킬을 모으는 중에 누른 키는 버리지 않고 기억했다가 **되는 순간 바로 발동** (스킬 칸이 노랗게 깜빡임). 마지막에 누른 스킬 하나만 기억.
- **키 바꾸기**: 설정 → 조작 → "블랙오션 스킬". 1.1 의 R 키 설정은 무시하고 모두 1~4 로 시작합니다.
- **핫바와 겹침**: 1~4 를 누르면 스킬만 나가고 핫바 칸은 안 바뀝니다 (마우스 휠로는 그대로 바뀜).
  둘 다 되게 하려면 `blackocean_skills-client.toml` 의 `skillKeysOverHotbar = false`.
- **스킬 칸**: 핫바 오른쪽에 4칸 (아이콘·키 번호·쿨타임). 준비되면 주황 테두리가 반짝, 쿨타임 중에 누르면 빨간 테두리.
- **조준**: 투사체는 손 옆에서 나가지만 **조준점이 가리키는 곳**으로 날아감. 불기둥은 허공을 보면 그 아래 10칸까지 바닥을 찾음.
- **같은 배**: 같은 배·탈것에 탄 사람은 어떤 스킬에도 맞지 않음 (선원 오사 방지).
- **지형**: **부수지 않고 불도 붙이지 않음** (설정으로 켤 수 있음).
- 한 스킬을 **모으는 중**에 다른 스킬 키를 누르면 예약되어, 모으기가 끝나자마자 이어서 나감 (탄환 연사 중에는 바로 가능).

### 사운드
- 화염 주먹: `charge` → `launch` → `travel`(따라다님) → `impact` → `crackle`
- 불꽃 탄환: `shot`(한 발마다) → `impact`
- 불기둥: `warn`(땅이 달아오름) → `erupt`
- 화염 고리: `charge`(소용돌이) → `burst`
전부 모노라서 거리에 따라 작아집니다.

---

## 3. 설정 파일

**서버** `config/blackocean_skills-common.toml` — 칸 4개. `[fire_fist]` 이름은 1.1 과 같아서 이미 바꾼 값은 그대로 유지됩니다.

| 칸 | 주요 항목 (기본값) |
|---|---|
| `[fire_fist]` | enabled, cooldownTicks 120, chargeTicks 8, speed 1.6, range 28, directDamage 12, splashDamage 6, splashRadius 3.5, knockback 0.9 |
| `[fire_fist]` 공통 | **pvp** true, **allowFire** false, **allowBlockDamage** false — 스킬 4개 모두에 적용 |
| `[fire_bullets]` | enabled, cooldownTicks 80, chargeTicks 4, count 5, intervalTicks 2, speed 2.6, range 34, damage 4, splashDamage 2, splashRadius 1.6, spreadDegrees 2.5 |
| `[fire_pillar]` | enabled, cooldownTicks 200, chargeTicks 14, range 26, radius 2.2, height 9, damage 10, burnDamage 2, durationTicks 26, launch 1.0 |
| `[flame_ring]` | enabled, cooldownTicks 260, chargeTicks 16, radius 7, damage 9, knockback 1.6 |

**각자 PC** `config/blackocean_skills-client.toml` 의 `[fx]`
- `particleAmount` (1.0): 불꽃·불씨·연기 양. 느리면 0.5
- `afterimages` (true): 화염 주먹 잔상
- `cameraShake` (true): 화면 흔들림
- `cooldownHud` (true): 핫바 오른쪽 스킬 칸
- `skillKeysOverHotbar` (true): 스킬 키가 핫바 키와 같을 때 핫바 칸 이동을 막음
- `holdToAim` (true): 꾹 눌러 조준·떼면 발동. false 면 누르는 순간 발동 (조준 표시 없음)

### 명령어 (OP)
| 명령어 | 뜻 |
|---|---|
| `/skills reset` · `/skills reset <대상>` | 모든 스킬 쿨타임 초기화 |
| `/skills cast` · `/skills cast <대상>` | 쿨타임 무시하고 화염 주먹 (1.1 과 같음) |
| `/skills cast <스킬> [대상]` | 쿨타임 무시하고 그 스킬. 스킬: `fire_fist` `fire_bullets` `fire_pillar` `flame_ring` (예: `/skills cast fire_pillar @a`) |

### 별풍선 연동용 (개발자용)
```java
BlackOceanSkillsApi.cast(player, Skill.FIRE_PILLAR, true);     // 쿨타임 무시하고 불기둥
BlackOceanSkillsApi.castFireFist(player, true);                 // 1.1 과 같음
BlackOceanSkillsApi.resetCooldown(player);                       // 모든 스킬 쿨타임 초기화
int left = BlackOceanSkillsApi.cooldownTicks(player, Skill.FLAME_RING); // 남은 쿨타임 (틱)
```

---

## 4. 어떻게 동작하나 (판정과 화면이 어긋나지 않는 이유)

1. 스킬 키를 누르면 **내 화면에서 바로** 준비 연출 시작 + 서버에 요청 (불기둥은 서버가 조준점을 정한 뒤 경고 원 표시).
2. 서버가 쿨타임 확인 후 주변 사람에게 "시전 시작" 신호 → 다른 사람 화면에도 준비 연출.
3. 0.4초 뒤 서버가 투사체를 만듦. 매 틱 "이전 위치 → 다음 위치" 선 전체로 충돌 검사 (얇은 벽·적을 뚫지 않음).
4. 맞은 **그 틱에** 피해를 주고, 정확한 좌표·맞은 면 방향을 담아 "적중" 신호.
5. 클라이언트는 주먹을 그 좌표로 옮기고 거기서 폭발을 시작.

쿨타임 중에는 내 화면이 이미 알고 있어서 준비 연출을 아예 시작하지 않습니다.
그래도 서버가 거절하면(설정으로 꺼 둠 등) 거절 신호를 받는 즉시 준비 연출을 지웁니다.

### 성능
- 한 화면의 연출 입자는 최대 2,500개. 여럿이 동시에 쏴도 프레임이 무너지지 않게 막습니다.
- 멀리서 터지는 화염 주먹은 입자를 줄여 그립니다 (32블록 밖 60%, 64블록 밖 30%, 128블록 밖 15%).
- 64블록 밖 주먹은 잔상을 생략합니다.

---

## 5. 연출 구성 (전부 이 모드가 직접 그림)

| 단계 | 그리는 것 | 방법 |
|---|---|---|
| 본체 | 손가락·엄지가 보이는 매끈한 주먹 (삼각형 2,000개) | `models/fx/fist_core.obj` + 흐르는 화염 셰이더 |
| 겉불꽃 | 뒤로 뻗는 불꽃 혀 5갈래, 윤곽이 밝음, 바깥이 계속 갉아 먹힘 | `fist_shell.obj` + 셰이더 |
| 궤적 | 주먹 뒤로 휘는 불꽃 띠 (가운데 밝고 가장자리로 갈수록 붉음) | 카메라를 향하는 띠 + 셰이더 |
| 잔상 | 최근 3틱 위치에 흐려지며 타 버리는 주먹 | 같은 메시, 갉아 먹기 증가 |
| 후광 | 주먹·손 뒤의 부드러운 빛 번짐 | `flash_glow.png` 판 |
| 폭발 | 울퉁불퉁 꿈틀거리며 부풀었다가 노이즈 모양으로 타서 사라지는 불덩어리 | 코드로 만든 구 + 셰이더 |
| 충격파 | 바닥(벽에 맞으면 벽면)을 따라 퍼지는 링 2개 | `shock_ring.png` |
| 나선 띠 | 화염 주먹 뒤로 꼬이며 따라오는 불꽃 띠 2가닥 | 궤적 점을 따라 도는 띠 + 셰이더 |
| 불기둥 | 꼬이며 솟는 2겹 원통(바깥 붉은 기둥 + 안쪽 흰 심지), 바닥 불꽃 고리, 소용돌이치는 불꽃 | 코드로 만든 원통 + 셰이더 |
| 불의 파도 | 바닥을 따라 퍼지는 불의 벽 2겹 + 타는 바닥 띠 | 넓어지는 원통 + 셰이더 |
| 경고 원 | 불기둥 자리 바닥에서 빛나며 점점 타오르는 원 | 링·빛 판 + 셰이더 고리 |
| 그을음 자국 | 폭발 자리 바닥·벽에 7~10초 남는 검게 탄 자국 | `scorch.png` (반투명) |
| 스킬 칸 | 아이콘 4개 | `textures/gui/skills/*.png` (64×64) |
| 불꽃·불씨·연기 | 애니풍 단계 음영 불꽃 64프레임, 떠오르는 불씨, 퍼지는 연기 | 스프라이트 시트 |

**셰이더** `shaders/core/fire_flow.fsh`: 노이즈 2겹을 다른 속도로 흘려 일렁임을 만들고, "열기" 값을
흰빛 → 노랑 → 주황 → 빨강 색 단계표로 바꾼 뒤 4단계 띠로 살짝 나눠 **애니메이션풍 외곽선**이 읽히게 합니다.

### 할 수 없는 것 (이 모드만으로)
- **진짜 블룸**(화면 전체 빛 번짐), **열기 아지랑이**(화면 왜곡), **주변을 실제로 비추는 광원**은 넣지 않았습니다.
  후광 판으로 빛 번짐을 흉내 냈습니다. 진짜로 하려면 Photon2 나 Veil 같은 렌더링 모드가 필요합니다 (설계서 참고).
- **Iris 셰이더팩**을 켜면 연출이 안 보이거나 다르게 보일 수 있습니다.
- **Sable 배 블록**에 맞는지는 테스트가 필요합니다. 배 블록이 일반 월드 충돌 검사에 안 잡히면 주먹이 배를 통과합니다.

---

## 6. 폴더 구조

```
blackocean_skills/
├─ 빌드하기.bat / gradlew / build.gradle
├─ src/main/java/com/blackocean/skills/
│  ├─ SkillsMod.java, SkillsConfig.java, SkillsRegistry.java   시작점·설정·등록
│  ├─ fire/FireFistProjectile.java    투사체: 충돌 검사·피해·적중 신호
│  ├─ fire/FireFistServer.java        쿨타임·준비 시간·발사
│  ├─ network/                        신호 3종 (요청 / 시전 시작 / 적중)
│  └─ client/                         ← 클라이언트 전용
│     ├─ SkillsClient.java            R 키, 이벤트 연결
│     ├─ FireFistTravelSound.java     따라다니는 소리
│     └─ fx/FxManager.java            모든 연출 (준비·비행·적중·잔류)
│        fx/FxRender.java, FxShaders.java, ObjMesh.java, Sprite.java, CameraShake.java
├─ src/main/resources/assets/blackocean_skills/
│  ├─ shaders/core/fire_flow.json .vsh .fsh
│  ├─ models/fx/fist_core.obj, fist_shell.obj
│  ├─ textures/fx/  (노이즈 2, 색 단계표, 불꽃 시트, 궤적, 링, 섬광, 불꽃점, 불씨, 연기 시트, 아지랑이)
│  ├─ sounds/skill/fire_fist/*.ogg, sounds.json
│  └─ lang/ko_kr.json, en_us.json
└─ art_source/                        jar 에 안 들어가는 원본
   ├─ meshes_glb/       Blender·Photon 용 .glb (주먹, 고리, 충격파, 구, 불꽃 줄기)
   ├─ generators/       메시·텍스처·사운드를 다시 만드는 파이썬 스크립트, 미리보기 렌더러
   └─ fx_preview.png
```

### 에셋 다시 만들기 / 직접 바꾸기
- **주먹 모양**: Blender 에서 `art_source/meshes_glb/fist_core.glb` 를 열어 고친 뒤 **OBJ 로 내보내기**
  (+Z 가 앞, +Y 가 위, 1m = 1블록, 법선·UV 포함, 삼각형). `models/fx/fist_core.obj` 를 덮어쓰면 끝.
- **불꽃 그림**: `textures/fx/flame_sheet.png` 는 1024×1024, 8×8 칸(한 칸 128px), 검은 배경. 칸 순서는 왼쪽 위 → 오른쪽 아래.
- **소리**: 같은 이름의 `.ogg`(모노)로 바꾸면 됩니다.
- 스크립트: `python make_meshes.py 출력폴더`, `python make_textures.py 출력폴더`, `python make_sounds.py 출력폴더`
  (numpy, scipy, pillow, scikit-image, trimesh, ffmpeg 필요)
