# 主题：multi-version-packaging

## 记录

### 2026-03-10 11:23:32

## 本次目标
- 实现三版本打包能力：最小化包（仅在线）、中等包（含 ONNX runtime 不含模型）、全量包（含模型并预置默认模型配置）。
- 同步落地后端包型能力、前端包型开关、发布脚本与验证流程。

## 关键改动
- 后端包型与路由：
  - 新增配置文件：`application-minimal.yml`、`application-medium.yml`、`application-full.yml`。
  - `RagConfigServiceImpl` 增加 `rag.embedding.model-dir` 默认注入与 `sqlcopilot.rag.local-onnx-enabled` 归一化逻辑；最小包强制 provider 归一化为在线。
  - 新增标记接口：`LocalRagEmbeddingService`、`LocalRagRerankService`。
  - `RagEmbeddingRouterServiceImpl` / `RagRerankRouterServiceImpl` 改为可选注入本地 ONNX 服务，最小包不再硬依赖本地实现。
  - `OnnxBgeM3EmbeddingServiceImpl` / `OnnxLocalRerankServiceImpl` 增加 `@ConditionalOnClass` + `@ConditionalOnProperty(sqlcopilot.rag.local-onnx-enabled=true)`。
- 后端构建：
  - `apps/server/pom.xml` 新增 `pack-minimal` / `pack-medium` / `pack-full` / `native` profiles。
  - 接入 `org.graalvm.buildtools:native-maven-plugin`。
  - `pack-minimal` 对 Spring Boot repackage 增加 ONNX runtime 依赖排除。
- 前端包型开关：
  - 新增 `apps/desktop/src/config/packageVariant.ts`，支持 `VITE_PACKAGE_VARIANT=minimal|medium|full`。
  - `useStudioRuntime.ts` 增加包型能力导出：`ragLocalOnnxEnabled`、`ragProviderTypeOptions`，并在保存/回填 RAG 配置时进行 minimal 包型兜底归一化。
  - `StudioShell.vue` 的 RAG 运行模式下拉改为动态 options；minimal 包型去掉本地 ONNX 入口和目录选择分支。
- 打包脚本与发布目录：
  - `apps/desktop/package.json` 新增 `build:minimal|medium|full`、`dist:minimal|medium|full`；构建产物命名与输出目录按包型区分到 `release/{variant}/desktop`。
  - 根 `package.json` 新增 `package:variants`。
  - 新增 `scripts/package-variants.sh`，统一输出 `release/{variant}/backend` 与 `release/{variant}/desktop`；支持无 `native-image` 时自动降级到 `clean package`，并支持 `SQLCOPILOT_ELECTRON_DIST` 指向本地 Electron 缓存目录。
- 文档更新：
  - README 增补三包型构建命令、Native 命令、环境变量与模型路径说明。

## 验证结果
- 后端构建：
  - `mvn -f apps/server/pom.xml clean package -DskipTests` 通过。
  - `mvn -f apps/server/pom.xml -Ppack-minimal|pack-medium|pack-full clean package -DskipTests` 全部通过。
  - `pack-minimal` 产物检查：`jar tf ... | rg onnxruntime` 无命中（已排除 ONNX runtime）。
  - `pack-medium` 产物检查：命中 `BOOT-INF/lib/onnxruntime-1.19.2.jar`。
- Native 编译：
  - `mvn -f apps/server/pom.xml -Pnative,pack-minimal clean native:compile -DskipTests` 失败，原因：当前 `JAVA_HOME` 非 GraalVM，缺少 `native-image`。
- 前端验证：
  - `npm run -w @sqlcopilot/desktop type-check` 通过。
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 桌面打包：
  - 通过本地 Electron 缓存路径（`--config.electronDist=/Users/zhouyu/Library/Caches/electron/3823f8325b6228c0ad28ae147ee67c92fe61e3948351d195d7f8b92a19bbe1dd`）完成：
    - `release/minimal/desktop/SQL-Copilot-minimal-0.1.0-mac-arm64.dmg`
    - `release/medium/desktop/SQL-Copilot-medium-0.1.0-mac-arm64.dmg`
    - `release/full/desktop/SQL-Copilot-full-0.1.0-mac-arm64.dmg`
- 启动验证：
  - 后端 minimal profile 启动成功；`/api/health` 返回 `ok`；`/api/rag/config/get` 返回在线 provider；`/api/rag/vectorize/runtime-provider` 返回 `ONLINE_OPENAI_COMPAT`。
  - 后端 full profile 启动成功；`/api/rag/config/get` 返回 `LOCAL_ONNX` + 默认模型目录 + `ragRerankEnabled=true`；`/api/rag/vectorize/runtime-provider` 返回本地 ONNX provider。
  - 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort` 启动成功，HTTP 200。

## 遗留项
- 若要在该环境直接完成 Native 编译，需要将 `JAVA_HOME` 切换到 GraalVM 17 并确保 `native-image` 可用。


### 2026-03-10 11:48:31

## 追加记录（仅 desktop 打包）

### 本次目标
- 根据最新要求，调整多版本打包流程：不再生成 backend 发布物，仅产出 desktop 三包。

### 关键改动
- 修改 `scripts/package-variants.sh`：
  - 删除 backend native/package 构建逻辑。
  - 删除 backend 发布物复制与 `run.sh` 生成逻辑。
  - 保留 `minimal/medium/full` 三包型的 `desktop` type-check + build + dist。
  - 脚本输出提示改为 `release/{minimal,medium,full}/desktop`。
  - 增加对历史 `release/<variant>/backend` 目录的清理，避免旧产物残留误判。
- 更新 `README.md` 打包说明：
  - 将“一键产出三种包（桌面+后端）”改为“一键产出三种桌面包（仅 desktop）”。

### 验证结果
- 一键脚本验证通过（使用本地 electron 缓存目录）：
  - `SQLCOPILOT_ELECTRON_DIST=/Users/zhouyu/Library/Caches/electron/3823f8325b6228c0ad28ae147ee67c92fe61e3948351d195d7f8b92a19bbe1dd npm run package:variants`
  - 日志仅出现 desktop 的 type-check/build/dist，不再出现 backend 构建命令。
- 产物目录检查：
  - `release/minimal/desktop`
  - `release/medium/desktop`
  - `release/full/desktop`
- 启动验证：
  - 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort`，HTTP 200。
  - 后端健康：`java -jar ... --spring.profiles.active=medium` + `GET /api/health` 返回 `ok`。


### 2026-03-10 11:48:57

## 补充记录
- README 打包段已进一步收敛：后端 native 命令改为“可选单独验证”说明，避免与“仅 desktop 打包”语义冲突。


### 2026-03-10 11:54:46

## 追加记录（最终制品仅 backend）

### 本次目标
- 按最新口径调整：最终发布产物仅保留 backend；desktop 仅在需要时作为可选步骤执行。

### 关键改动
- 修改 `scripts/package-variants.sh`：
  - 默认行为：仅构建并输出 `release/{minimal,medium,full}/backend`。
  - 保留三包型差异化后端构建流程（`pack-minimal|pack-medium|pack-full`，并在有 native-image 时走 native 编译）。
  - `full` 包继续复制 `apps/server/models` 到 `release/full/backend/models`。
  - 新增可选开关：`SQLCOPILOT_INCLUDE_DESKTOP=1` 时，才附加执行 desktop 的三包型打包。
  - 默认模式下清理 `release/<variant>/desktop`，避免旧产物误判。
- 更新 `README.md`：
  - `npm run package:variants` 说明改为“默认仅 backend”。
  - 补充 `SQLCOPILOT_INCLUDE_DESKTOP=1 npm run package:variants` 用法。
  - 新增环境变量说明：`SQLCOPILOT_INCLUDE_DESKTOP`（默认 `0`）。

### 验证结果
- `npm run package:variants` 执行通过：日志仅出现 backend 构建，不出现 desktop 打包步骤。
- 产物目录验证：
  - `release/minimal/backend`
  - `release/medium/backend`
  - `release/full/backend`（含 `models`）
- 启动验证：
  - 后端：`release/medium/backend` 产物启动后 `GET /api/health` 返回 `ok`。
  - 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort` 返回 `HTTP/1.1 200 OK`。


### 2026-03-10 12:06:30

## 追加记录（默认 desktop 产物 + backend 中间构建）

### 本次目标
- 调整为：默认最终发布物只保留 desktop，但每个 variant 仍执行 backend 打包流程。

### 关键改动
- `scripts/package-variants.sh`
  - 默认值调整：`SQLCOPILOT_INCLUDE_DESKTOP=1`、`SQLCOPILOT_EXPORT_BACKEND=0`。
  - 三个 variant 始终先执行 backend 构建（native 可用走 native，否则 fallback `clean package`）。
  - 默认不导出 backend 发布目录（仅中间构建）；当 `SQLCOPILOT_EXPORT_BACKEND=1` 时才导出 `release/<variant>/backend`。
  - desktop 仍按 variant 正常导出到 `release/<variant>/desktop`。
- `README.md`
  - 明确 `npm run package:variants` 为“默认 desktop 产物，backend 作为中间构建执行”。
  - 新增开关说明：`SQLCOPILOT_EXPORT_BACKEND`。

### 验证结果
- 执行：
  - `SQLCOPILOT_ELECTRON_DIST=/Users/zhouyu/Library/Caches/electron/3823f8325b6228c0ad28ae147ee67c92fe61e3948351d195d7f8b92a19bbe1dd npm run package:variants`
- 日志验证：每个 variant 都出现 backend 构建日志，随后执行 desktop 打包。
- 最终产物目录：仅存在 `release/{minimal,medium,full}/desktop`，不存在 `release/*/backend`。
- 启动验证：
  - 后端健康检查：`/api/health` 返回 `ok`。
  - 前端预览：`HTTP/1.1 200 OK`。


### 2026-03-10 14:33:05

## 追加记录（Native Build 暂停）

### 本次目标
- 按用户要求切换到 GraalVM 17 并执行 backend native build；若缺少环境则补齐。

### 已完成
- 确认本机已有 GraalVM 17：
  - `JAVA_HOME=/Users/zhouyu/Library/Java/JavaVirtualMachines/graalvm-jdk-17.0.12/Contents/Home`
  - `native-image --version` 可用。
- `apps/server/pom.xml` 已补充 native 兼容参数：
  - `-H:+AllowDeprecatedBuilderClassesOnImageClasspath`
  - 目的：解决 Oracle GraalVM 17.0.12 下 onnxruntime 与 builder classpath 冲突导致的 native 编译失败。
- 已发起命令：
  - `mvn -f apps/server/pom.xml -Pnative,pack-minimal clean native:compile -DskipTests`
  - 进入 native image 生成阶段后，用户主动中断本轮执行。

### 当前状态
- native 编译进程已终止（无残留 `native-image` 进程）。
- 代码变更已保留，未回滚；后续可直接在同一 GraalVM 环境继续执行 `minimal -> medium -> full` 的 native 编译。

### 后续继续命令（待恢复时）
- `export JAVA_HOME=/Users/zhouyu/Library/Java/JavaVirtualMachines/graalvm-jdk-17.0.12/Contents/Home`
- `export PATH="$JAVA_HOME/bin:$PATH"`
- `mvn -f apps/server/pom.xml -Pnative,pack-minimal clean native:compile -DskipTests`
- `mvn -f apps/server/pom.xml -Pnative,pack-medium clean native:compile -DskipTests`
- `mvn -f apps/server/pom.xml -Pnative,pack-full clean native:compile -DskipTests`


### 2026-03-10 14:34:37

## 追加记录（README 增加 Native 打包命令）

### 本次目标
- 在 README 的“构建与打包”章节补充可直接执行的 Native 打包命令。

### 关键改动
- 在 `README.md` 的命令块内新增：
  - GraalVM 17 的 `JAVA_HOME` / `PATH` 设置示例。
  - 后端按包型 Native 编译命令：
    - `-Pnative,pack-minimal`
    - `-Pnative,pack-medium`
    - `-Pnative,pack-full`
  - 使用统一脚本 `npm run package:variants` 走 Native 的环境变量示例。

### 验证说明
- 本次仅文档补充，未触发新一轮编译执行。
