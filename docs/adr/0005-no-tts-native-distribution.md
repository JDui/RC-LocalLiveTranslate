# ADR-0005: Build an Android JNI without TTS

Status: implemented build / packaging path, Android execution pending (2026-10-09).

## Context

The official sherpa-onnx v1.13.8 Android distribution enables TTS by default. Its fixed CMake configuration includes piper-phonemize and espeak-ng. Not calling these APIs does not demonstrate that their code is absent from a prebuilt JNI. The application needs no speech synthesis and should not redistribute unused components with extra license obligations.

## Decision

Build sherpa JNI from commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`, using SDK NDK `29.0.14206865` and CMake `3.22.1`. Set TTS and offline speaker diarization OFF. Keep VAD, recognizer, and speaker embedding JNI. Set Android API 28, ARM64, 16 KB maximum ELF page size, and the same static C++ strategy as the official JNI. Retain Foxlet's separately provided C++ shared runtime.

Pinned upstream CMake uses checksum-verified dependency archives, including ONNX Runtime 1.28.2. Preserve upstream license / notice files, complete KissFFT BSD terms, collected public-source copyright lines, NDK notices, and a generated SOURCE inventory beside the app's offline license assets. Eigen 5.0.1 MPL-covered source is unchanged and its fixed upstream source URL is retained. Sherpa's Android sentencepiece adaptation is performed by its pinned CMake source. These source links and this repository's build script describe the corresponding source acquisition and build path.

Prepare source directories additively, not by resetting sparse checkout during compilation. Acquire an exclusive preparation lock. Complete compilation and license collection before replacing SDK libraries; write SOURCE completion metadata after the copies finish. The Gradle helper requires that metadata. CI installs the same toolchain, prepares native dependencies, builds app / tests, and checks static alignment.

## Verification Boundary

Build flags, compile success, exported JNI symbols, APK asset / library inspection, and ELF / ZIP alignment are static evidence only. Android JNI loading and real model behavior must still be verified on an ARM64 device. Removing TTS does not establish a complete legal opinion about every application dependency or model weight. No weights are bundled or publicly distributed by this preparation path.
