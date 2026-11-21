# third_party — pinned native dependencies

This directory will hold the **pinned git submodules** of the two native inference engines used by oneCR:

| Directory | Project | Added at | Purpose |
|---|---|---|---|
| `whisper.cpp/` | https://github.com/ggml-org/whisper.cpp | M5 | on-device speech-to-text (GGML models, 16 kHz float input) |
| `llama.cpp/` | https://github.com/ggml-org/llama.cpp | M5 | on-device summarization (GGUF models) |

## Policy

- Both engines are added as **git submodules pinned to a specific commit**, never vendored copies, so every build is reproducible and security bumps are one-line diffs.
- The build system (CMake via Gradle's external native build) compiles only the small subset of sources each engine needs, for `arm64-v8a` (plus `x86_64` in debug builds for emulator smoke tests).
- Upstream licenses (both MIT) are compatible with oneCR's MIT license; attribution lives in README and in the in-app licenses screen.
- Until M5, this directory is intentionally empty except for this file.
