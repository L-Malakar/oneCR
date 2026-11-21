# Contributing to oneCR

Thanks for your interest! oneCR is a milestone-driven project — see [docs/ROADMAP.md](docs/ROADMAP.md) for what is being built and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the pieces fit.

## Development setup

1. Android Studio Ladybug or newer, JDK 17+, Android SDK 35.
2. `git clone https://github.com/L-Malakar/oneCR.git`
3. Open the project in Android Studio, or build from the command line:
   - Windows: `build.bat --debug`
   - Linux/macOS: `./gradlew :app:assembleDebug`
4. Version bumps happen in **one place only**: `gradle.properties` (`VERSION_NAME`, `VERSION_CODE`) and `gradle/libs.versions.toml` for dependencies.

## Ground rules

- **Architecture boundaries are fixed** by the module graph (see ARCHITECTURE.md). UI code stays in `:app` / `:feature:*`; privileged code stays in `:privilege:*` and is minimal; audio/encoding stays in `:service:recorder`; AI stays behind the interfaces in `:ai:*`.
- **No network code** outside the Model Manager (`:data:files`). This is a hard privacy rule.
- **No new permissions** without documenting them in PRIVACY.md with a concrete reason.
- Kotlin official code style (`.editorconfig` is enforced by IDEs), 4-space indent, 120-col soft limit.
- Prefer stable APIs; if you must use an experimental API, isolate it behind a small wrapper.

## Commit messages

Conventional-commits style, e.g. `feat(recorder): ...`, `fix(build): ...`, `docs: ...`, `chore(deps): ...`.

## Pull requests

1. Fork / branch from `main`.
2. Keep PRs focused on one milestone step or one concern.
3. CI must pass (`assembleDebug`, `assembleRelease`, `test`).
4. Describe device testing you performed (model, Android version, what you verified) — audio behavior is device-specific, so this matters.

## Reporting bugs

Open an issue using the bug template and include: device model, Android version, whether you used embedded-ADB or Shizuku mode, and a logcat capture around the failure (`adb logcat -s oneCR`).

## License

By contributing you agree that your contributions are licensed under the MIT license of this repository.
