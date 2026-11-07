# Blackocean skills — 2.3 visual direction brief (shared by all workers)

Mod: NeoForge 1.21.1 "blackocean_skills", Java 21. 6 elements × 5 skills (keys 1–5, 5 = ultimate). Server-authoritative hits; client renders all VFX itself (custom meshes, animated texture sheets, shader `fire_flow` with per-element ramp, camera-facing sprites, ribbons, decals, block-chunk debris). This client-side rendering is the "custom model / animated texture / particle" toolbox; we deliberately do NOT use server display entities (network cost, no shader control).

## The user's requirements (verbatim intent)
1. Shape & visual design: each skill has a distinctive silhouette and focal form recognizable in a still frame; clear hierarchy primary form > supporting trails > impact > lingering details; elements differ by shape, motion, texture and dissipation — not recolors; identity through motion (fire curls and rises, ice grows and shatters, lightning branches abruptly); consistent visual language (palettes, detail level, density).
2. Depth & layering: combine models, animated textures, particles by role; primary form stays readable while supporting layers show energy flow, speed, depth, trails; must hold up from front, side and rear (no angle-dependent collapse/flatness — avoid pure billboards for elongated forms: use 3D meshes, crossed planes, axis-aligned ribbons); contrast bright cores vs darker edges, large vs small, dense vs open. Rendering support: vanilla core shaders only — NO real bloom, dynamic lighting or screen distortion (Iris shaderpacks may or may not exist; don't depend on them). Use alternatives: layered additive soft halos (fake bloom), ground "light pool" decals and vertex-brightened nearby surfaces (fake light), wobbling translucent heat/frost haze sprites (fake distortion).
3. Impact & audio: impact visuals and sounds on the exact damage tick; light vs heavy attacks differ by anticipation, speed change, impact shape and audio weight, not only size; limit audio buildup and flashing when skills overlap (use FxManager.playAt — it is now rate-limited by FxLimits; ScreenFx flashes must go through FxLimits.allowFlash).
4. Combat readability & accuracy: attack direction, affected area and danger timing easy to read; visible boundaries == real hit detection; decorative aftermath clearly distinct from active damage areas; check caster AND opponent views; don't obscure characters/targets; friend vs foe distinguished by outline/markers not just color.

## Element style guide (keep consistent)
- FIRE: curling, rising tongues; licks upward, dissipates into rising smoke and floating embers. Core white-yellow → orange → deep red edge, dark smoke contrast. Heavy = fist/sun; light = bullets.
- LIGHTNING: abrupt, jagged branching; instant on/off with 1–2 frame flicker and a short burn-in afterimage; dissipates by flickering out + small sparks. White core → cyan → deep blue edge.
- WIND: crescent and spiral arcs, thin translucent streak lines, carried leaves/dust give it visibility; dissipates by stretching and thinning out. Pale mint/white, open and airy (low density).
- WATER: heavy liquid volume (sheets, tubes, wave walls) with white foam rims and droplets under gravity; dissipates by falling and splashing, wet ground ripples. Deep blue body, white foam.
- ICE: angular faceted crystals that GROW in stepped bursts, sharp glints; dissipates by cracking then shattering into falling shards and frost mist. Dark teal interior, cyan-white edges.
- THUNDER: heavy, slow, weighty wind-up; massive geometric hammer and rune shapes, ground-shaking shock walls; dissipates into dust and residual crawling gold sparks. Gold core, violet edges, dark storm contrast.
Hierarchy budget per skill: primary form = highest contrast & brightness; supporting trails ≈ 40–60 % brightness; impact = brief peak (≤ 4 ticks); lingering = low alpha, NO edge lines.

## Shared APIs (in client/fx)
- `SkillFx.dangerZone(base, R, fill, element, caster, alpha, time)`, `SkillFx.dangerCone(origin, dir, range, halfDeg, fill, element, caster, alpha, time)`, `SkillFx.dangerLane(start, dir, length, halfWidth, fill, element, caster, alpha, time)` — use these for EVERY active damage area / telegraph (exact hit size). They style friend/foe automatically via `Hostility.of(caster)` (SELF / ALLY / ENEMY). Draw each frame while active.
- `Hostility.of(entity)`, `FxLimits.allowSound`, `FxLimits.allowFlash`, `FxManager.playAt` (limited), `ChargeFx.aimDir()/flatDir()`, `AimHand`, `IceThunderAim`, `DebrisFx`, `CameraShake`, `ScreenFx`, `DamageNumbers`, `CasterPose`.

## Build / branch rules
Git worktree of GitHub `summim/blackocean-skills`, base branch `wip-2.3`. No local Gradle (offline). Compile via CI: commit, `git push -u origin <your-branch>`, `gh workflow run Build -R summim/blackocean-skills --ref <your-branch>`, run id `gh run list -R summim/blackocean-skills -b <your-branch> -L 1`, `gh run watch <id> -R summim/blackocean-skills --exit-status`, failures `gh run view <id> -R summim/blackocean-skills --log-failed`. Must finish green. Commit messages end with:
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TuuiqgEkrMLT82zKpwd7DH
Edit ONLY files you own; other workers edit others in parallel. Never change existing method signatures others use — only add. You cannot run a game client: verify geometry from all angles by reasoning about the mesh/billboard math, and say honestly what still needs in-game viewing.
