# BlackOcean Skills (블랙오션 스킬) — 4계통 능력자 스킬 20종

**화염 · 번개 · 바람 · 물** 능력자. 능력마다 숫자키 **1~5** 스킬, **5번은 궁극기**.
애니풍 흐르는 에너지 셰이더 · 3D 메시 · 진짜처럼 갈라지는 번개 · 회오리 · 물의 용 · 화면 섬광/궁극기 배너로 그립니다.
NeoForge 1.21.1 전용. **추가로 설치할 모드가 없습니다** (이 jar 하나로 서버 판정 + 화면 연출).

## 1. 빌드하는 법

1. **JDK 21** 필요. 이 폴더의 **`빌드하기.bat`** 더블클릭 → `build\libs\blackocean_skills-2.0.0.jar`.
2. jar 를 **서버 `mods`** 와 **접속기 모드팩(`blackocean-pack`)** 둘 다에 넣습니다.

> **자동 빌드**: GitHub `summim/blackocean-skills` 의 main 에 push 하면 깃허브가 jar 를 만들고,
> 실제 서버를 한 번 켜서 모드가 오류 없이 올라오는지까지 시험합니다. 완성된 jar 는 `dist` 브랜치에 저장됩니다.

---

## 2. 능력 얻기

| 방법 | 설명 |
|---|---|
| **능력의 열매** (화염·번개·바람·물) | 먹으면 그 능력을 얻음 (기둥·고리·화면 번쩍 연출). 크리에이티브 음식 탭 / `/give @p blackocean_skills:lightning_fruit` |
| `/skills element <대상> <fire\|lightning\|wind\|water\|none\|default>` | OP 가 능력 지정 (`default` = 서버 기본값으로 되돌림) |
| `/skills element <대상>` | 지금 능력 확인 |
| 서버 기본값 | 아무것도 안 정한 사람은 `defaultElement` (기본 **fire** → 예전처럼 모두 화염 스킬) |

능력은 죽어도 유지되고 저장됩니다. `fruitSwap = false` 면 이미 능력이 있는 사람은 다른 열매를 먹어도 안 바뀝니다.
능력이 없으면(none) 1~5 키는 그냥 핫바 키로 동작합니다.

---

## 3. 스킬 20종

| 키 | 🔥 화염 | ⚡ 번개 | 🌪 바람 | 🌊 물 |
|---|---|---|---|---|
| **1** | 화염 주먹 — 거대한 3D 주먹, 폭발 | 뇌격 — 즉시 번개, 주변 적 3명까지 연쇄 | 진공 참격 — 적을 꿰뚫는 초승달 칼날 | 수탄 — 물 구슬, 물보라 + 느려짐 |
| **2** | 불꽃 탄환 — 양손 5연사 | 섬광 이동 — 12칸 순간이동, 출발·도착·경로에 번개 | 질풍 돌진 — 12칸 돌진, 부딪힌 적 띄움 | 수류 분사 — 조준하는 대로 1.6초 물줄기 |
| **3** | 불기둥 — 바닥에서 솟구침 | 낙뢰 — 조준한 곳에 낙뢰 3연속 | 회오리 — 빨아들여 띄운 뒤 날려 보냄 | 물의 감옥 — 가두고 끌어당긴 뒤 터짐 |
| **4** | 화염 고리 — 사방으로 불의 파도 | 방전 — 주변 모든 적에게 번개 + 감전 | 폭풍 방벽 — 날려 보내고 **적 투사체 제거** | 해일 — 앞으로 밀려가는 파도 벽 |
| **5 궁극기** | **작열의 태양** — 머리 위 태양을 던져 대폭발 + 불바다 | **뇌신의 심판** — 뇌운이 하늘을 덮고 낙뢰를 퍼붓다 마지막 거대 번개 | **천공의 대폭풍** — 높이 22칸 거대 회오리 | **해룡 강림** — 물의 용이 솟구쳐 내리꽂힘 + 소용돌이 |

**궁극기**: 쿨타임 60~70초. 쓰면 근처 사람 화면에 **큰 제목 배너 + 능력 색 섬광**, 궁극기 소리. 스킬 칸 5번은 금테.
번개 궁극기 중엔 근처 하늘이 어두워집니다.

### 조작 (1.3 과 같음)
- **꾹 누르면 조준 표시** (날아갈 길·사거리·범위·도착 지점), **떼면 발동**, 누른 채 **우클릭 = 취소**. 톡 치면 바로 발동.
- **입력 예약**: 쿨타임 0.5초 이내·다른 스킬을 모으는 중에 누른 키는 기억했다가 되는 순간 발동 (스킬 칸 노랗게 깜빡임).
- 1~5 를 누르면 스킬만 나가고 핫바 칸은 안 바뀝니다 (`skillKeysOverHotbar = false` 로 끌 수 있음).
- 같은 배·탈것에 탄 사람은 어떤 스킬에도 맞지 않습니다 (선원 오사 방지).
- 지형은 부수지 않고 불도 붙이지 않습니다 (설정으로 켤 수 있음, 화염 계열만).

---

## 3-1. 설정 파일

**서버** `config/blackocean_skills-common.toml` (게임을 켠 채 고쳐도 자동 반영, 접속자 조준 표시까지 갱신)
- `[general]`: `pvp`, `allowFire`, `allowBlockDamage`, `defaultElement`(fire), `damageMultiplier`, `cooldownMultiplier`, `fruitSwap`, `ultimateBanner`
- 스킬마다 같은 모양의 칸 20개 (`[fire_fist]`, `[lightning_storm]` …): `enabled`, `cooldown`(초), `charge`(초), `damage`, `damage2`, `range`, `radius`, `duration`(초), `speed`
  - 각 칸 위의 설명에 그 스킬에서 숫자가 뜻하는 것(예: damage2 = 0.5초마다 피해)이 적혀 있습니다.
  - ⚠ 1.x 의 설정 이름과 달라서, 업데이트하면 스킬 숫자는 기본값으로 다시 시작합니다.

**각자 PC** `config/blackocean_skills-client.toml`: `particleAmount`(느리면 0.5), `afterimages`, `cameraShake`, `cooldownHud`, `skillKeysOverHotbar`, `holdToAim`

### 명령어 (OP)
| 명령어 | 뜻 |
|---|---|
| `/skills element <대상> <능력>` | 능력 지정 |
| `/skills reset [대상]` | 모든 쿨타임 초기화 |
| `/skills cast <스킬> [대상]` | 쿨타임 무시하고 발동 (능력과 상관없이). 예: `/skills cast lightning_storm @a` |

### 별풍선 연동용 (개발자용)
```java
BlackOceanSkillsApi.cast(player, Skill.LIGHTNING_STORM, true); // 쿨타임 무시하고 궁극기
BlackOceanSkillsApi.castSlot(player, 4, true);                 // 그 사람 능력의 5번
BlackOceanSkillsApi.setElement(player, Element.WATER);         // 능력 바꾸기
BlackOceanSkillsApi.resetCooldown(player);
```

---

## 4. 어떻게 동작하나 (판정과 화면이 어긋나지 않는 이유)

1. 스킬 키를 떼면 **내 화면에서 바로** 준비 연출 시작 + 서버에 요청 (바닥 조준 스킬도 서버와 같은 계산으로 자리를 미리 그림).
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
src/main/java/com/blackocean/skills/
├─ Element.java, Skill.java          능력 4종, 스킬 20종 표 (기본 수치·조준 모양)
├─ SkillsConfig.java                 설정 파일 (스킬마다 같은 칸)
├─ SkillServer.java                  쿨타임·입력 예약·모으기·발동 순서 (서버)
├─ ServerEffects.java                스킬별 실제 판정 (번개 연쇄, 회오리, 해일, 해룡 …)
├─ SkillAim.java                     조준 계산 (서버 판정과 조준 표시가 같이 씀)
├─ SkillTargets.java, SubLevels.java 맞힐 대상 규칙·피해·상태이상, 배(Sable) 좌표 보정
├─ PlayerElements.java, item/        플레이어 능력 저장, 능력의 열매
├─ entity/SkillProjectile.java       모든 투사체 (주먹·탄환·태양·칼날·물 구슬)
├─ network/                          신호: 시전 요청·시작·쿨타임·연출 이벤트·능력·설정값
└─ client/                           ← 클라이언트 전용
   ├─ SkillsClient.java              1~5 키, 꾹 눌러 조준, 핫바 키 막기
   ├─ SkillBarHud.java               스킬 칸 5개
   └─ fx/                            FxManager(공통), SkillFx(불기둥·화염 고리), FireFx(태양),
                                     LightningFx, WindFx, WaterFx, AimPreview, ScreenFx(섬광·배너·어두운 하늘)
src/main/resources/assets/blackocean_skills/
├─ shaders/core/fire_flow.*          흐르는 에너지 셰이더 (능력별 색 단계표 *_ramp.png)
├─ textures/fx/ gui/skills/ item/    이펙트 그림, 스킬 아이콘 20개, 열매 4개
└─ sounds/skill/                     fire_*/ lightning/ wind/ water/ common/
```

### 에셋 다시 만들기 / 직접 바꾸기
- **주먹 모양**: Blender 에서 `art_source/meshes_glb/fist_core.glb` 를 열어 고친 뒤 **OBJ 로 내보내기**
  (+Z 가 앞, +Y 가 위, 1m = 1블록, 법선·UV 포함, 삼각형). `models/fx/fist_core.obj` 를 덮어쓰면 끝.
- **불꽃 그림**: `textures/fx/flame_sheet.png` 는 1024×1024, 8×8 칸(한 칸 128px), 검은 배경. 칸 순서는 왼쪽 위 → 오른쪽 아래.
- **소리**: 같은 이름의 `.ogg`(모노)로 바꾸면 됩니다.
- 스크립트: `python make_meshes.py 출력폴더`, `python make_textures.py 출력폴더`, `python make_sounds.py 출력폴더`
  (numpy, scipy, pillow, scikit-image, trimesh, ffmpeg 필요)
