# Offline slicing integration — engineering status

## Current state
The Android app loads and renders STL, but it does **not** slice meshes into printable toolpaths. The "Prepare G-code" button deliberately refuses to generate output. Do not publish this APK as a working slicer.

## Selected engine
UltiMaker CuraEngine: https://github.com/Ultimaker/CuraEngine
- C++ slicer, licensed AGPL-3.0-or-later; distribute corresponding source and comply with license.
- Recent engine builds use CMake/Conan and native dependencies including Clipper, Boost, RapidJSON, range-v3, fmt, spdlog, and optionally libArcus/Protobuf.
- No verified Android ARM64 binary or integration is currently committed here. A Linux desktop executable cannot simply be packaged as an Android library.

## Release acceptance criteria
1. Reproducible Android ARM64 build of a pinned, audited CuraEngine revision and dependencies.
2. JNI/native integration with background execution, progress, cancellation, and robust error reporting; no network permission.
3. Android document picker input STL and Storage Access Framework export of real generated G-code.
4. Tested Ender-3 V3 SE printer geometry, PETG material settings, extrusion and retraction, homing/start/end G-code.
5. Automated golden-file tests with a cube and overhang geometry verifying actual layered perimeters, infill, extrusion, travel and bounds; verify output with independent G-code parser.
6. Successful GitHub Actions Android APK build and on-device ARM64 smoke test.
7. Source attribution and AGPL license compliance.

## Safety
Do not execute unvalidated G-code on a printer. Do not merge the draft PR until all criteria pass.
