# Build the APK

The GitHub Actions workflow installs the Android SDK/NDK, cross-compiles
SentencePiece and NeMo-Speech.cpp for arm64-v8a, builds the Android app,
and uploads `app-debug.apk`.

Open **Actions → Build Android APK → Run workflow**.

The resulting APK performs inference locally. GitHub is only used to compile it.
