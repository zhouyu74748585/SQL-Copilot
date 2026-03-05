# 2026-03-05 ONNX GPU 模式验证与修复记录

## 背景
- 现象：ONNX Runtime 在 Windows 下尝试启用 CUDA Provider 时出现 `LoadLibrary failed with error 126`。
- 目标：保持 GPU 依赖策略不变，定位原因并提供可验证的 GPU/CPU 运行态判断方式。

## 本次修改

### 1. ONNX Provider 容错与诊断优化
文件：`apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/OnnxBgeM3EmbeddingServiceImpl.java`

- 保留 `execution-provider=CUDA` 的强制测试能力（显式 CUDA 不改为 CPU）。
- 在 `AUTO` 模式下增加 Windows CUDA 运行库可用性预检查：
  - 检查 `PATH` 中是否存在 `nvcuda/cudart/cublas/cublasLt/cudnn` 相关 DLL。
  - 缺失时跳过 CUDA 尝试，避免反复触发 `error 126`。
- 日志优化：
  - `AUTO` 下不可用 Provider 记录为 info（降低误报噪音）。
  - 显式配置 Provider 失败仍保留 warn。

### 2. 新增运行时 Provider 查询接口（便于 GPU 测试）
文件：`apps/server/src/main/java/com/sqlcopilot/studio/controller/RagVectorizeController.java`

- 新增接口：`GET /api/rag/vectorize/runtime-provider`
- 返回：`{"provider":"CPU|CUDA|..."}`，直接读取 `RagEmbeddingService.getRuntimeProvider()`。
- 用于快速确认当前实例是否真正跑在 GPU Provider 上。

## 验证过程

### 后端 clean + 启动验证
- 执行：`mvn clean package -DskipTests`（成功）
- 启动参数：`--rag.embedding.execution-provider=CUDA`
- 健康检查：`/api/health` 返回成功。
- Provider 检查：`/api/rag/vectorize/runtime-provider` 返回 `CPU`。

### 前端 clean + preview 验证
- 执行：`npm run -w @sqlcopilot/desktop build`（成功）
- 执行 preview 并检测端口：`6044` 监听成功（`FRONTEND_PREVIEW_UP`）。

## 结论
- 在当前机器上，强制 `CUDA` 配置下运行时仍回退为 `CPU`，说明 GPU Provider 依赖未满足。
- 环境检查结果显示：
  - 存在：`nvcuda`, `cudart`, `cublas`, `cublasLt`
  - 缺失：`cudnn64_*.dll`
- `error 126` 的直接原因与 cuDNN 运行库缺失一致。

## 后续建议
- 安装与当前 ONNX Runtime GPU 版本匹配的 cuDNN，并将 DLL 目录加入 `PATH` 后重启终端/IDE。
- 通过 `GET /api/rag/vectorize/runtime-provider` 复测，期望返回 `CUDA`。

## 2026-03-05 23:50 Follow-up

- Updated CUDA runtime detection in `OnnxBgeM3EmbeddingServiceImpl`:
  - Search DLLs from `PATH`, `CUDA_PATH*` / `CUDNN_PATH*`, and `C:\Program Files\NVIDIA\CUDNN\...`.
  - Strip quoted path entries before directory probing.
  - Preload `cudart/cublas/cublasLt/cudnn` DLLs on Windows before enabling CUDA provider.
  - Improve AUTO-mode skip logs with per-library check details.
- Validation result:
  - Backend clean package passed.
  - Backend startup log shows `configured=AUTO, selected=CUDA`.
  - Frontend clean build + preview passed.

## 2026-03-06 00:05 Follow-up (cudnnCreate crash)

- Reproduced issue on `AUTO` with ORT warning + native error:
  - `VerifyEachNodeIsAssignedToAnEp`
  - `Invalid handle. Cannot load symbol cudnnCreate`
- Root cause:
  - CUDA was selected while `cudnn64_9.dll` was not visible from process `PATH`.
  - This can lead to native loader failure and JVM process exit during session initialization.
- Fix applied:
  - In AUTO mode, CUDA is now enabled only when required CUDA/cuDNN DLLs are visible on `PATH`.
  - Keep explicit diagnostics in logs (`nvcuda/cudart/cublas/cublasLt/cudnn` booleans + PATH sample).
  - Keep explicit `CUDA` mode for forced testing; AUTO prioritizes stability.
- Validation:
  - Backend clean package + startup passed.
  - `GET /api/rag/vectorize/runtime-provider` returns `CPU` and process stays alive (no native crash).
  - Frontend clean build + preview passed.
