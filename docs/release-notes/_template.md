# Release notes template

One file per release: `v<version>.md` in this folder. The GitHub Actions release
job (`/.github/workflows/android.yml`) publishes the file verbatim as the release
body when you push a tag.

Structure to keep across releases:

1. **One-line summary** — what this release is (milestone / fix release).
2. **Highlights** — 3–6 bullets, user-facing language, bold key words.
3. **What works / what doesn't** — honest state of the build.
4. **Install / update notes** — floor, signature caveats, uninstall steps.
5. **SHA-256** — of the published APK (fill after building).
6. **Known issues** — anything you would open an issue about.

Style: short paragraphs, no marketing fluff, always state what is NOT in the
build yet. Users sideloading pre-releases need the truth, not a pitch.
