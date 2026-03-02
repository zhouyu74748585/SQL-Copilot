# Qdrant binaries for Electron packaging

Place platform-specific Qdrant executables in the following folders before running `npm run dist`:

- `resources/qdrant/darwin-arm64/qdrant`
- `resources/qdrant/darwin-x64/qdrant`
- `resources/qdrant/linux-arm64/qdrant`
- `resources/qdrant/linux-x64/qdrant`
- `resources/qdrant/win32-arm64/qdrant.exe`
- `resources/qdrant/win32-x64/qdrant.exe`

You can use `npm run download:qdrant` to download the binary for the current platform.
