# BYDMate — voice work: PR A status + English-voice roadmap

Personal working notes (on the fork `wajidp/BYDMate`) to resume later.
Date: 2026-10-04 · Base: upstream `main` fast-forwarded to `6170b341`.

Two things are captured here:
1. **PR A** ("play audio" automation action) — done and raised; how to resume it.
2. **English voice assistant** — research + a phased roadmap for the long-term project.

---

## Part 1 — PR A: play-audio automation action (DONE, in review)

**What it is:** a new automation action **"Play sound" (`play_audio`)** that plays a
user-picked sound file. Complements the existing "Speak text" (`speak`) action —
`speak` synthesizes text, `play_audio` plays an existing file verbatim.

**Status:** raised as **PR #287** → https://github.com/AndyShaman/BYDMate/pull/287
- Branch: `feat/play-audio-action`, pushed to fork remote `fork`
  (`https://github.com/wajidp/BYDMate.git`), PR opened against `AndyShaman/BYDMate:main`.
- All local CI gates green before push: `:app:detekt`, `:app:lintDebug`,
  `:app:testDebugUnitTest` (full suite).

**What it touched:**
- `data/automation/ActionDispatcher.kt` — `"play_audio"` branch; a short-lived,
  ducking `MediaPlayer` behind the `playAudioFile` test seam (seam takes the URI as a
  `String` so dispatch is testable without Android's `Uri.parse`). `AUDIOFOCUS_GAIN_
  TRANSIENT_MAY_DUCK`; focus released + player released on completion/error. Pure
  companion helper `playAudioUri(payload)`.
- `data/local/entity/RuleEntity.kt` — kind doc comment.
- `data/automation/RuleDraftValidator.kt` — new `PlayAudioUriEmpty`.
- `agent/AgentTools.kt`, `ui/automation/RuleText.kt`, `ui/automation/RuleImportSummary.kt`
  — wired the new kind (exhaustive `when`s needed the branch).
- `ui/automation/AutomationViewModel.kt` — `newPlayAudioAction` + `playAudioUri()` /
  `playAudioName()` / `withPlayAudio()` + error-string mapping.
- `ui/automation/AutomationScreen.kt` — `PlayAudioActionControls` using SAF
  `ACTION_OPEN_DOCUMENT` (`audio/*`) + `takePersistableUriPermission` (persisted read
  grant), picker tile, and a `queryDisplayName` helper.
- Strings in all six locales (ru/en/zh/pt/pl/be).
- Tests: `RuleDraftValidatorPlayAudioTest`, `ActionDispatcherPlayAudioTest`.
- `CHANGELOG.md` under `## [Unreleased]` (Russian).

**To resume PR A** (address review, add commits):
```bash
git fetch fork && git checkout feat/play-audio-action
# ...edit...
./gradlew :app:detekt :app:lintDebug :app:testDebugUnitTest   # JAVA_HOME below
git push fork feat/play-audio-action                           # updates the PR
```

**Known follow-up ideas:** bundled default chimes (raw resources) as an alternative to
a user-picked URI; a volume field in the payload.

---

## Part 2 — English voice assistant (RESEARCH / long-term)

### 2.1 The reframe
"English TTS" is really **two projects**, because the whole offline pipeline is
Russian-locked, not just the voice. A voice only speaks English if something upstream
produces English text, and today nothing does.
- **English *output*** (speak English text in an English voice) — small, shippable.
- **English *conversation*** (talk to it in English, get English back) — large, gated
  on a new ASR model.

### 2.2 Current pipeline
```
mic ─► AudioCapture ─► silero VAD ─► GigaAM v3 ASR ─► NluParser ──fast path──► dispatch
                        (lang-free)   (RU nemo-ctc)   (RU dictionary)  │
                                                                       └─unrecognized─►
   AgentOrchestrator ─► LlmAgentBackend (online LLM) ─► reply text ─► TtsRouter
                                                         ├─ offline SherpaTtsEngine (RU voices)
                                                         └─ online  MiniMax / OpenRouter (lang-free)
```
Key files: `voice/AudioCapture.kt`, `voice/GigaAmAsrEngine.kt` +
`voice/GigaAmModelManager.kt`, `voice/NluParser.kt` + `voice/VoiceDictionary.kt` +
`VoiceDictionaryLoader.kt`, `agent/AgentOrchestrator.kt` + `agent/LlmAgentBackend.kt` +
`agent/AgentTools.kt` + `agent/AgentCommandCatalog.kt` + `voice/AgentPersona.kt`,
`voice/TtsVoiceCatalog.kt` + `voice/SherpaTtsEngine.kt` + `voice/TtsModelManager.kt` +
`voice/online/TtsRouter.kt`. Locale signal already exists:
`LocalePreferences.getLanguage()` + per-app locale (`BYDMateApp.kt`, `MainActivity.kt`).

### 2.3 Language-lock audit

| Layer | How it's locked | Data-driven? | English effort |
|---|---|---|---|
| AudioCapture / VAD | none | — | **none** |
| **ASR** | GigaAM v3 **Russian** nemo-ctc | model-driven | **High** — new model + language-aware engine |
| **NLU dictionary** | RU phrases + RU morphology templates | yes (resource) | **High (content)** — English dictionary |
| Agent system prompt | hardcoded `отвечай по-русски` (`AgentOrchestrator.kt:388`) | no | **Low** — conditional on language |
| Agent personas | RU strings (`AgentPersona.kt`) | no | **Low–Med** — translate |
| Agent tool/catalog/error text | RU labels/errors | partly | **Med** — translate user-facing bits |
| Canned NLU replies / `VoiceCommandLabels` | RU strings | partly | **Med** — translate |
| **TTS offline voices** | catalog lists only `ru_RU` | **yes** (`TtsModelManager` generic) | **Low** — add `en_US`/`en_GB` Piper |
| **TTS online** | none | — | **none** |

Takeaway: **TTS + online-agent reply language are cheap. ASR is the wall. Offline
English NLU is large content but no new architecture.**

### 2.4 Options per layer
- **ASR** (same sherpa-onnx transport minimizes change):
  - *NeMo English CTC* — closest to current `nemo-ctc` engine, smallest diff. **Recommended start.**
  - *Streaming Zipformer English* — true streaming, small, different wiring.
  - *Whisper tiny.en/base.en* — robust, heavier/higher latency, VAD-segmented.
  - Design: keep single-language models, select by the language setting (mirrors
    `GigaAmModelManager` downloading one model) → engine becomes "language-aware" via a
    model id, not a rewrite. VAD unchanged.
- **NLU:** English dictionary is content, not architecture. Can be deferred — English
  mode could route everything to the online LLM agent instead.
- **Agent:** make the system prompt reply in English when selected (LLM does English
  natively); translate personas + user-facing tool/catalog/error strings. Tool schemas
  stay. Gives English replies with **no new model**.
- **TTS:** add `en_US`/`en_GB` Piper archives to `TtsVoiceCatalog` (sherpa-onnx
  `tts-models` has `vits-piper-en_US-*-medium`; same `urlFor` pattern, PIPER engine,
  espeak-ng does English). Tag voices with a language; filter the Settings list.
  `RuStressMarker` is RU-only — skip for English voices. Online already works.

### 2.5 Phased roadmap (each phase shippable)
- **Phase 0 — scaffolding (small):** one "voice language" setting (or reuse
  `LocalePreferences`), threaded to TTS voice filter + ASR model id + agent prompt
  variant. No behavior change.
- **Phase 1 — English *output* (small, = the original "English TTS"):** English Piper +
  online English voices; agent replies in English when selected. → English
  `Speak text`/`agent_query` actions and the online assistant speak correct English.
  *Talking to it is still Russian.*
- **Phase 2 — English *speech-in* (large, the real project):** English ASR model
  (recommend NeMo English CTC), language-keyed ASR manager/engine, translate agent
  strings. → Full English conversation via the online agent.
- **Phase 3 — offline English commands (large, optional):** author English
  `VoiceDictionary`; translate canned replies. Skippable if Phase 2 is enough.

Order: **0 → 1** can land now. **2** is the long-term project. **3** optional.

### 2.6 Risks / open questions
- ASR accuracy + latency on the head unit (arm64, modest CPU) — **bench on-device
  before committing** to a model; GigaAM was chosen for a reason.
- Download size/UX — English ASR + TTS add hundreds of MB of optional downloads; reuse
  the staging + atomic-rename pattern.
- Mixed-language use — single language setting is simplest; auto-detect out of scope.
- "Multilingual" is the same machinery with more data — design language-keying
  generically, ship English first.
- `VoiceDictionaryLoader` format assumes Russian inflection — check before authoring EN.

### 2.7 Recommendation
1. **Ship Phase 0+1 as "PR B"** — bounded, independently useful (English `speak`
   actions stop sounding broken; online English users get English answers).
2. **Track Phase 2 (English ASR)** as the long-term project; first task is an on-device
   NeMo-English-CTC benchmark spike.
3. **Defer Phase 3** until there's demand.

---

## Appendix — local toolchain (this machine)
Installed during PR A so the Gradle gates can run locally:
- JDK 17: `openjdk@17` (Homebrew, keg-only) at `/opt/homebrew/opt/openjdk@17`.
- Android SDK 34: `/opt/homebrew/share/android-commandlinetools`
  (`platforms;android-34`, `build-tools;34.0.0`, `platform-tools`).
- `local.properties` → `sdk.dir=/opt/homebrew/share/android-commandlinetools` (gitignored).

Run gradle with:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:detekt :app:lintDebug :app:testDebugUnitTest
```

Fork remotes:
- `origin` = upstream `AndyShaman/BYDMate` (no push access).
- `fork`   = `wajidp/BYDMate` (push here; open PRs against upstream from this fork).
