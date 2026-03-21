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


### 2026-03-10 14:51:52

## 追加记录（完整应用按包型打包命令）

### 本次目标
- 输出并固化“完整应用（前后端一起）按包型打包”的命令，避免仅前端或仅后端的单独命令。

### 关键改动
- `scripts/package-variants.sh` 新增包型参数能力：
  - 支持命令参数：`bash scripts/package-variants.sh minimal|medium|full`
  - 支持环境变量：`SQLCOPILOT_VARIANTS=minimal,full`
  - 增加包型合法性校验，非法值直接失败并提示。
- 根 `package.json` 新增快捷命令：
  - `package:app:minimal`
  - `package:app:medium`
  - `package:app:full`
- `README.md` 构建章节重构为“完整应用打包”导向：
  - JVM 后端完整应用打包（全量/单包型）
  - Native 后端完整应用打包（全量/单包型）
  - 明确 `npm run package:variants -- <variant>` 的参数用法。

### 验证结果
- 脚本语法检查通过：`bash -n scripts/package-variants.sh`
- 包型校验生效：非法包型会报错并退出（`Invalid variant: ...`）
- npm scripts 已注册：`package:app:minimal|medium|full`

### 说明
- 本次仅完成命令与脚本能力补齐，未执行新的完整打包编译。


### 2026-03-10 14:59:39

## 追加记录（Native IllegalAccessError 修复）

### 问题现象
- Native 编译报错：
  - `IllegalAccessError: ... OnnxBgeM3EmbeddingServiceImpl$SessionInitResult ... cannot access class ai.onnxruntime.OrtSession ...`
- 触发点为 `record` 组件类型包含 `OrtSession`，GraalVM 构建期在记录组件反射读取时命中模块导出限制。

### 修复内容
- 文件：`apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/OnnxBgeM3EmbeddingServiceImpl.java`
- 将：
  - `private record SessionInitResult(OrtSession session, String selectedProvider)`
- 调整为：
  - 普通 `private static final class SessionInitResult`
  - 保留 `session()` / `selectedProvider()` 访问方法，调用方无需改动。

### 验证
- 执行：`mvn -f apps/server/pom.xml -Ppack-minimal -DskipTests compile`
- 结果：`BUILD SUCCESS`

### 后续建议
- 在相同 GraalVM 环境下重试：
  - `mvn -f apps/server/pom.xml -Pnative,pack-minimal clean native:compile -DskipTests`


### 2026-03-10 17:43:43

## 2026-03-10 会话补充（full 打包失败修复）

### 本次目标
- 修复 `npm run package:app:full` 产物在 mac-arm64 启动白屏（后端 native 启动失败）问题。
- 产出可用 `full` 桌面制品，并确认内置 backend 可健康启动。

### 关键改动
- 新增并扩展 MyBatis native hints：
  - 文件：`apps/server/src/main/java/com/sqlcopilot/studio/config/MyBatisNativeHints.java`
  - 增加 MyBatis 日志/SPI/Javassist/Scripting/SqlSessionFactoryBean/MapperFactoryBean 等反射注册。
  - 为 10 个 mapper 接口注册 JDK Proxy hints，解决 native 运行期 `Proxy class ... not found`。
  - 补充 `ArrayList/HashMap/LinkedHashMap` 构造器反射 hints，修复 MyBatis 结果集处理时集合实例化失败。
- 调整 ONNX Embedding 服务的 CoreML flags 处理：
  - 文件：`apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/OnnxBgeM3EmbeddingServiceImpl.java`
  - 移除对 `CoreMLFlags` 的强类型硬依赖，改为运行时反射可选解析；类缺失时回退 `addCoreML()`。
  - 避免 full/native 启动时因 `CoreMLFlags` 缺失导致 Bean 初始化失败。

### 验证结果
- 后端 native 编译（clean）：
  - `mvn -f apps/server/pom.xml -Pnative,pack-full clean native:compile -DskipTests -Dsqlcopilot.native.image.jvm.xmx=6g -Dsqlcopilot.native.image.threads=4`
  - 结果：成功。
- 后端启动验证（full）：
  - `apps/server/target/sql-copilot-server --spring.profiles.active=full`
  - `GET http://127.0.0.1:18080/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 完整 full 打包：
  - `npm run package:app:full`
  - 结果：成功，生成 DMG 与 app 目录。
- 前端预览验证：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173`
  - 结果：可访问首页 HTML。
- 制品内置 backend 验证：
  - `release/full/desktop/mac-arm64/SQL Copilot.app/Contents/Resources/backend/sql-copilot-server --spring.profiles.active=full`
  - 健康检查返回成功。

### 产物
- `release/full/desktop/SQL-Copilot-full-0.1.0-mac-arm64.dmg`
- `release/full/desktop/mac-arm64/SQL Copilot.app`


### 2026-03-10 17:51:01

## 2026-03-10 会话补充（制品瘦身：移除无用 jar）

### 本次目标
- 打包制品中不再携带无用后端 `jar`，减少桌面安装包体积。

### 关键改动
- 调整后端资源拷贝逻辑：
  - 文件：`scripts/package-variants.sh`
  - `prepare_backend_runtime` 在存在 native 可执行（`sql-copilot-server` 或 `sql-copilot-server.exe`）时，仅拷贝 native 可执行，不再拷贝 `apps/server/target/*.jar`。
  - 仅在无 native 可执行时，才拷贝 `jar` 作为回退运行形态。

### 验证结果
- 脚本语法校验：`bash -n scripts/package-variants.sh` 通过。
- 完整 full 打包：`npm run package:app:full` 成功。
- 产物检查：
  - `release/full/desktop/mac-arm64/SQL Copilot.app/Contents/Resources/backend` 下仅包含 native + 配置 + models，无 `*.jar`。
- 启动验证：
  - 打包内置 backend 启动健康检查通过：`/api/health` 返回 `ok`。
  - 前端 `preview` 验证通过。


### 2026-03-10 18:01:32

## 2026-03-10 会话补充（Electron 白屏：file:// 资源路径修复）

### 本次问题
- 打包后的桌面应用白屏，开发者控制台报错：
  - `GET file:///assets/*.css|*.js net::ERR_FILE_NOT_FOUND`

### 根因
- `apps/desktop/dist/index.html` 中静态资源引用为绝对路径 `/assets/...`。
- Electron 生产环境通过 `file://.../dist/index.html` 加载页面，绝对路径会被解析成磁盘根目录 `file:///assets/...`，导致找不到资源。

### 关键改动
- 文件：`apps/desktop/vite.config.ts`
- 改为按命令设置 `base`：
  - build：`'./'`
  - dev：`'/'`
- 使构建产物 `index.html` 引用变为 `./assets/...`，兼容 Electron `file://` 场景。

### 验证结果
- 前端验证：
  - `npm run -w @sqlcopilot/desktop type-check` 通过
  - `npm run -w @sqlcopilot/desktop build:full` 通过
  - `apps/desktop/dist/index.html` 中资源路径已变为 `./assets/...`
- 完整打包验证：
  - `npm run package:app:full` 成功
  - 从 `release/full/desktop/mac-arm64/SQL Copilot.app/Contents/Resources/app.asar` 抽取 `dist/index.html`，确认最终制品内也是 `./assets/...`


### 2026-03-10 18:23:50

## 本次目标
- 修复 full/native 运行时 `AiConfigMapper.findById` 报错：`No constructor found in AiProviderConfigEntity matching [...]`。
- 重新验证 full 完整应用打包链路（backend native + desktop），确认最终制品可启动且 API 正常。

## 关键改动
- 文件：`apps/server/src/main/java/com/sqlcopilot/studio/config/MyBatisNativeHints.java`
- 新增 mapper 实体反射注册：
  - `AiProviderConfigEntity`
  - `AuditLogEntity`
  - `ConnectionEntity`
  - `ErGraphSnapshotEntity`
  - `KnowledgeExampleSqlEntity`
  - `KnowledgeTermEntity`
  - `QueryHistoryEntity`
  - `RagEmbeddingConfigEntity`
  - `RagVectorizeStatusEntity`
  - `SavedQueryEntity`
- 新增 `registerEntityType(...)`，统一注册实体在 native 下需要的构造器/方法/字段反射可见性，避免 MyBatis 在结果映射时因无法反射默认构造器而退化到构造器签名匹配并失败。

## 验证结果
- 后端 Maven 打包（clean）通过：
  - `mvn -f apps/server/pom.xml -Ppack-full -DskipTests clean package`
- 后端 native 编译（clean）通过：
  - `JAVA_HOME=/Users/zhouyu/Library/Java/JavaVirtualMachines/graalvm-jdk-17.0.12/Contents/Home`
  - `mvn -f apps/server/pom.xml -Pnative,pack-full clean native:compile -DskipTests -Dsqlcopilot.native.image.jvm.xmx=6g -Dsqlcopilot.native.image.threads=4`
- native 启动与接口回归通过：
  - `apps/server/target/sql-copilot-server --spring.profiles.active=full`
  - `GET /api/health` -> 200
  - `GET /api/ai/config/get` -> 200（不再出现 constructor 异常）
- 前端验证通过：
  - `npm run -w @sqlcopilot/desktop type-check`
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`，HTTP 200
- full 完整应用打包回归：
  - 显式 GraalVM 环境执行：`npm run package:app:full`
  - 日志确认为 `backend native build`（非 jar fallback）
  - 产物：`release/full/desktop/SQL-Copilot-full-0.1.0-mac-arm64.dmg`
  - 制品内 backend 目录仅含 `sql-copilot-server`（无 `*.jar`）
  - 直接启动制品内 backend：
    - `release/full/desktop/mac-arm64/SQL Copilot.app/Contents/Resources/backend/sql-copilot-server --spring.profiles.active=full`
    - `GET /api/health` 与 `GET /api/ai/config/get` 均 200

## 说明
- 若直接执行 `npm run package:app:full` 未设置 GraalVM 环境，脚本会 fallback 到 jar 打包；需要先设置 `JAVA_HOME`/`PATH` 才会走 native。


### 2026-03-10 18:37:50

## 本次目标
- 修复 full/native 制品运行时报错：`未配置数据库驱动映射: MYSQL，请检查 jdbc-drivers.yml`。
- 验证修复后完整 full 包（native backend + desktop）在制品内可复现通过。

## 关键改动
- 文件：`apps/server/src/main/java/com/sqlcopilot/studio/config/MyBatisNativeHints.java`
- 在 `registerHints(...)` 中新增 native 资源注册：
  - `jdbc-drivers.yml`
  - `drivers/**`
- 目的：保证 `JdbcDriverResolver` 与 `IsolatedJdbcConnectionManager` 在 native 运行期通过 `ClassPathResource` 能读取驱动映射与驱动包资源。

## 验证结果
- 后端 native clean 编译通过：
  - `mvn -f apps/server/pom.xml -Pnative,pack-full clean native:compile -DskipTests -Dsqlcopilot.native.image.jvm.xmx=6g -Dsqlcopilot.native.image.threads=4`
- native 启动验证通过：
  - `apps/server/target/sql-copilot-server --spring.profiles.active=full`
  - `GET /api/health` -> 200
- 驱动映射回归：
  - `POST /api/connection/databases/preview`（`dbType=MYSQL`）
  - 结果不再出现 `未配置数据库驱动映射`，返回已进入驱动初始化阶段的错误（环境未连通时为初始化/连接失败），说明映射资源已生效。
- full 完整应用打包通过（native backend）：
  - `JAVA_HOME=...graalvm... npm run package:app:full`
  - 生成：`release/full/desktop/SQL-Copilot-full-0.1.0-mac-arm64.dmg`
- 制品内 backend 回归：
  - 启动 `release/full/desktop/mac-arm64/SQL Copilot.app/Contents/Resources/backend/sql-copilot-server --spring.profiles.active=full`
  - `GET /api/health` -> 200
  - `POST /api/connection/databases/preview`（MYSQL）不再报“未配置数据库驱动映射”。
- 前端预览验证：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
  - `HTTP/1.1 200 OK`

## 说明
- 本次修复的是“驱动映射资源缺失”问题；若目标数据库不可达，接口仍会返回连接/驱动初始化相关错误，这是网络或运行环境层面的独立问题。


### 2026-03-10 23:52:00

## 追加记录（打包策略切换到 Java + jlink）

### 本次目标
- 按最新要求调整完整应用打包策略：不再要求 backend native build，统一改为 `Spring Boot jar + jdeps/jlink runtime`。
- 补齐支持 Windows 与 macOS/Linux 的打包入口脚本，并保证 Electron 内置 backend 优先使用随包 JRE 启动。

### 关键改动
- 新增 `scripts/package-variants.mjs`
  - 接管根 `package:variants` 打包入口。
  - 每个 variant 统一执行 `mvn -Ppack-<variant> clean package -DskipTests`。
  - 基于当前 `JAVA_HOME` / `java.home` 自动解析 `jar`、`jdeps`、`jlink` 工具。
  - 使用 `jdeps --print-module-deps` + 预置模块集合生成 jlink runtime，并支持通过 `SQLCOPILOT_JLINK_EXTRA_MODULES` 追加模块。
  - 将 `jar + jre + application*.yml + variant + run.sh/run.cmd` 组装到 `apps/desktop/resources/backend`，可选导出到 `release/<variant>/backend`。
  - 适配 Windows 文件锁场景：jlink 临时目录独立为 `release/.jlink-temp`，backend 导出目录改为“保留根目录、清空子项”。
- 新增 `scripts/package-variants.ps1`
  - Windows PowerShell 可直接执行。
- 保留 `scripts/package-variants.sh`
  - 作为 macOS/Linux 包装脚本，内部转发到 `node scripts/package-variants.mjs`。
- 调整 `apps/desktop/electron/main.cjs`
  - backend 启动时优先识别 `resources/backend/jre/bin/java(.exe)`，不再依赖系统 Java。
  - `jar` 启动参数补充 `-Dfile.encoding=UTF-8`。
- 调整 `apps/server/src/main/java/com/sqlcopilot/studio/SqlCopilotApplication.java`
  - 去掉 `@MapperScan(factoryBean = AotMapperFactoryBean.class)`，恢复普通 JVM/jar 启动路径，避免 `AotMapperFactoryBean` 在非 native 运行时导致 Bean 装配失败。
- 新增 `apps/server/src/main/java/com/sqlcopilot/studio/support/driver/BundledJdbcDriverFactory.java`
  - 补齐内置 JDBC 驱动实例工厂，修复当前分支 `MyBatisNativeHints` / `IsolatedJdbcConnectionManager` 的编译断点。
- 更新 `README.md`
  - 打包说明改为 `jar + jlink`。
  - 增补 `scripts/package-variants.ps1` / `scripts/package-variants.sh` 用法与 `SQLCOPILOT_JLINK_EXTRA_MODULES` 说明。

### 验证结果
- Windows PowerShell 打包脚本验证通过：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\package-variants.ps1 medium`
  - 附加导出 backend：
    - `$env:SQLCOPILOT_EXPORT_BACKEND='1'`
    - `powershell -ExecutionPolicy Bypass -File .\scripts\package-variants.ps1 medium`
- 产物检查：
  - `release/medium/backend`
  - `release/medium/desktop/SQL-Copilot-medium-0.1.0-win-x64.exe`
  - `release/medium/desktop/win-unpacked/resources/backend/jre`
- 启动验证：
  - backend：
    - `release/medium/backend/run.cmd medium`
    - `GET http://127.0.0.1:18080/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
  - frontend preview：
    - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
    - 返回 `HTTP 200`

### 说明
- 本轮已完成 Windows 侧完整打包与启动验证。
- `scripts/package-variants.sh` / `run.sh` 已生成并适配 macOS/Linux，但当前会话环境为 Windows，未在本机执行 macOS 实包验证。


### 2026-03-10 21:24:07

## ?????Windows ????????

### ????
- ?? `npm run package:variants` ? Windows PowerShell ???? `bash scripts/package-variants.sh` ???????????
- ?? `D:\Ideaprojects\manhua-java\scripts\build-desktop.ps1` ?????????? Windows ????????? Visual Studio C++ ??????? GraalVM Native Image ?????

### ????
- ?????????? Node ???
  - `package.json` ? `package:variants` ? `bash scripts/package-variants.sh` ?? `node scripts/package-variants.mjs`?
  - ?? `scripts/package-variants.mjs`???? `minimal|medium|full` ?????backend ?????desktop ??????????
- Windows ?????????
  - ?? `scripts/package-variants.ps1`????? PowerShell ????
  - `scripts/package-variants.mjs` ?? `manhua-java` ???? `vswhere + VsDevCmd.bat` ???????? shell ????? `cl.exe` ????????????? VS C++ ????
  - ?? `cl.exe` ??????? `19.31+`??????????? `19.50.35724`?
  - ?? `SQLCOPILOT_STRICT_NATIVE=1` ????????? native ??????????? JVM jar ??????????? Windows ??????
- ????????
  - ?? `run.sh` ??? `BASE_DIR` ? Node ????????????
  - ? `scripts/package-variants.sh` ??????????????? Node ????????????
- ?????
  - README ??? Windows PowerShell ???????PowerShell ????????? native ????/???????

### ????
- Windows ?????
  - `npm run package:variants -- minimal` ?????
  - ??????????? `C:\Program Files\Microsoft Visual Studio\18\Community\Common7\Tools\VsDevCmd.bat`?
  - ?????? `Visual C++ compiler 19.50.35724` ?? GraalVM Native Image ???
  - ?????????`release/minimal/desktop/SQL-Copilot-minimal-0.1.0-win-x64.exe`?
- ?????
  - ??????? clean native ?????? `apps/server/target/sql-copilot-server.exe --spring.profiles.active=minimal --server.port=18080`?`GET http://127.0.0.1:18080/api/health` ?? `{"code":0,"message":"success","data":"ok"}`?
  - ????? `npm run -w @sqlcopilot/desktop build:minimal`?? `--emptyOutDir` clean ??????? `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`?HTTP ?? `200`?

### ??
- ????????`electron-builder` ???? `description/author missing`?? chunk ???????????? Windows ????????
- ????????????????????`.gitignore`?`apps/server/src/main/resources/application.yml`?


### 2026-03-10 22:18:40

## ?????Windows Native ????? app.asar ??????

### ????
- ? Windows ???? GraalVM Native Image ????????? `32` ???`16g` ????
- ?? `electron-builder` ? Windows ??? `win-unpacked/resources/app.asar` ????????????????
- ?? `apps/desktop/package.json` ??? `description` / `author` ???????

### ????
- `scripts/package-variants.mjs`
  - `resolveNativeImageXmx(...)` ? Windows ?????? `SQLCOPILOT_NATIVE_IMAGE_XMX` ????? `16g`?
  - `resolveNativeImageThreads(...)` ? Windows ?????? `SQLCOPILOT_NATIVE_IMAGE_THREADS` / `SQLCOPILOT_NATIVE_IMAGE_PARALLELISM` ????? `32`?
  - ???????????????? `release/.tmp/<variant>-desktop-attempt-<n>`??? `electron-builder` ????????? `release/<variant>/desktop/win-unpacked`?
  - ??????????? `release/<variant>/desktop`??? `win-unpacked` ??????????????? `win-unpacked-latest`?????????????
  - ?? Windows ?? `electron-builder` ?????
- `apps/desktop/package.json`
  - ?? `description` ? `author`??? electron-builder ?????????
- `README.md`
  - ?? Windows ?? `16g / 32` ????????????????

### ????
- `npm run package:variants -- minimal` ?????
- ?????Windows native ??????????? `xmx=16g, threads=32`?
- ?????`description` / `author missing` ???????
- ????????????? `release/minimal/desktop/win-unpacked/resources/app.asar` ???????????????????
  - `Unable to replace ...\win-unpacked, keeping new copy at ...\win-unpacked-latest`
- ???????????`release/minimal/desktop/SQL-Copilot-minimal-0.1.0-win-x64.exe`?
- ?????
  - ???`apps/server/target/sql-copilot-server.exe --spring.profiles.active=minimal --server.port=18080` ?????? `{"code":0,"message":"success","data":"ok"}`?
  - ???`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort` ?? `HTTP 200`?

### ??
- ??????????? `win-unpacked` ????? `win-unpacked-latest`??????????? unpacked ???????????????????


### 2026-03-10 22:35:55

## ????
- ?? Windows GraalVM native-image ??? minimal ???? ONNX Runtime ????????
- ?????? clean native ?????????????????

## ????
- ? `apps/server/pom.xml` ? `native-maven-plugin` `buildArgs` ??? `--initialize-at-run-time=ai.onnxruntime,ai.onnxruntime.providers`?? ONNX Runtime ??????????????? `OrtSession`?`OrtProvider`?`OrtEnvironment`?`OnnxRuntime` ??????????????

## ????
- ?? Maven native ?????`mvn -f apps/server/pom.xml -Pnative,pack-minimal clean native:compile -DskipTests -Dsqlcopilot.native.image.jvm.xmx=16g -Dsqlcopilot.native.image.threads=32`??? `apps/server/target/sql-copilot-server.exe`?
- ??????????? `apps/server/target/sql-copilot-server.exe --spring.profiles.active=minimal --server.port=18080`?`GET http://127.0.0.1:18080/api/health` ?? `{"code":0,"message":"success","data":"ok"}`?
- ???????`npm run -w @sqlcopilot/desktop type-check`?`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`?`npm run preview -- --host 127.0.0.1 --port 4173 --strictPort` ????????? HTTP 200?

## ???
- ?? native-image ???? reachability / reflection warning??????? minimal ?? native ?????????????????????????????????????????


### 2026-03-11 10:11:33

## 本次目标
- 去掉项目内与 GraalVM/native 打包直接相关的代码、依赖和运行分支，统一回到 JVM jar + jlink 运行时链路。

## 关键改动
- 后端构建：
  - `apps/server/pom.xml` 删除 `org.graalvm.buildtools:native-maven-plugin`、`native` profile，以及 `sqlcopilot.native.image.*` / `graalvm.buildtools.version` 等原生编译参数。
- 后端代码：
  - `SqlCopilotApplication` 去掉 `@ImportRuntimeHints(MyBatisNativeHints.class)`。
  - 删除仅服务于 AOT/native 的 `AotMapperFactoryBean`、`MyBatisAotConfig`、`MyBatisNativeHints` 三个配置类。
- Desktop 启动链路：
  - `apps/desktop/electron/main.cjs` 删除对 `sql-copilot-server(.exe)` native 可执行文件的探测与优先启动分支，保留 `run.sh` / `run.cmd` / jar 启动路径。
- 文档：
  - `README.md` 中后端技术栈改为 `JDK 17`，去掉 GraalVM/native 相关表述。

## 验证结果
- 后端 Maven clean 打包通过：
  - `mvn -f apps/server/pom.xml clean package -DskipTests`
- 前端类型检查与 clean build 通过：
  - `npm run -w @sqlcopilot/desktop type-check`
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 启动验证通过：
  - 后端：`java -Dfile.encoding=UTF-8 -jar apps/server/target/sql-copilot-server-0.1.0.jar --spring.profiles.active=medium --server.port=18081`
  - 健康检查：`GET http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
  - 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort`
  - HTTP 检查：`curl -I http://127.0.0.1:8888` 返回 `HTTP/1.1 200 OK`

## 遗留项
- 历史总结文档中仍保留此前 native 打包过程记录，仅作为历史信息，不再代表当前构建链路。


### 2026-03-11 11:40:13

## 追加记录（复核 native 代码去除并完成启动验证，2026-03-11 11:39）

### 本次目标
- 基于 `614c14e4`、`8e7cd5f6`、`9fbcfd66` 三个 commit 复核并清理 native 相关代码与逻辑残留。

### 关键改动
- 复核当前分支已包含后续提交 `172ea06 去掉native相关代码`，确认 native 主链路已切回 `Spring Boot jar + jlink runtime`。
- 进一步清理源码中残留的 native 专属表述：
  - `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/OnnxBgeM3EmbeddingServiceImpl.java`
  - 将注释中的 `native loader crashes` 调整为更准确的 `runtime loader crashes`，避免继续把运行时 CUDA/cuDNN 检查与 native 路径绑定。

### 验证结果
- 后端构建（clean）通过：
  - `mvn -f apps/server/pom.xml clean package -DskipTests`
- 前端类型检查通过：
  - `npm run type-check`
- 前端 clean build 通过：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端启动验证通过：
  - `java -Dfile.encoding=UTF-8 -jar apps/server/target/sql-copilot-server-0.1.0.jar --spring.profiles.active=medium --server.port=18081`
  - `GET http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端预览验证通过：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
  - `HEAD http://127.0.0.1:4173` 返回 `200 OK`

### 备注
- 默认端口 `18080` 在本机已被其他进程占用，因此本次后端启动验证改用 `18081`，不影响本次代码变更结论。

### 2026-03-14 11:18:00

## 本次目标
- 修复 Windows 下执行 `npm run package:variants` 时，`release/<variant>/desktop/win-unpacked/resources/app.asar` 被占用导致 `electron-builder` 清理输出目录失败的问题。
- 继续打通 Windows 下三种 variant 的完整桌面打包链路，并补齐后端启动、前端预览验收。

## 关键改动
- `scripts/package-variants.mjs`
  - 将目录清理统一收敛到 `removePathWithRetry`，对 Windows 的 `EBUSY` / `EPERM` / `ENOTEMPTY` 增加重试等待。
  - 新增 `releaseWindowsDirLocks`，在清理 `release/<variant>/desktop` 失败时，定向结束输出目录下残留的打包应用进程，避免误杀其他程序。
  - 在执行 `electron-builder` 前显式清理桌面输出目录，尽量把文件锁问题拦截在脚本层，而不是让 `electron-builder` 直接失败。
  - 新增 `prepareDesktopOutputDir` 兜底：若标准输出目录持续被 Windows 占用，则自动回退到时间戳目录 `release/<variant>/desktop-YYYYMMddHHmmss` 继续构建。
  - 针对 Windows `full` 版本新增目标切换：改为 `zip` 产物，规避 NSIS 在约 3 GB 安装包场景下 `failed creating mmap` 的限制；仍保留 `win-unpacked` 目录产物。

## 验证结果
- 锁文件回归验证通过：
  - 人工在 `release/medium/desktop/win-unpacked` 内放置并启动临时 `lock-holder` 进程后，执行 `npm run package:app:medium`。
  - 脚本能识别目录占用并在标准输出目录不可清理时自动回退到 `release/medium/desktop-20260314025416` 完成打包。
- 单 variant 打包验证通过：
  - `npm run package:app:medium`
  - `npm run package:app:full`
- 全量打包验证通过：
  - `npm run package:variants`
  - 结果：
    - `minimal` 输出到回退目录 `release/minimal/desktop-20260314030824`
    - `medium` 输出到回退目录 `release/medium/desktop-20260314031035`
    - `full` 输出到标准目录 `release/full/desktop`，并生成 `SQL-Copilot-full-0.1.0-win-x64.zip`
- 启动验收通过：
  - 后端 clean 启动：
    - `mvn -f apps/server/pom.xml clean package -DskipTests`
    - `java -Dfile.encoding=UTF-8 -jar apps/server/target/sql-copilot-server-0.1.0.jar --spring.profiles.active=medium --server.port=18080`
    - `GET http://127.0.0.1:18080/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
  - 前端 clean 预览：
    - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
    - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort`
    - `GET http://127.0.0.1:8888` 返回 `HTTP 200`

## 说明
- 由于 Windows 可能在打包完成后继续短暂占用旧输出目录，`minimal` / `medium` 本次最终产物位于时间戳回退目录；这是本次修复的预期行为，目的是优先保证打包成功而不是整次流程失败。
- `full` 版本在 Windows 下改为 `zip` 属于容量兜底策略；如果后续需要恢复安装器格式，需要先进一步拆分模型资源或重新评估 Windows 安装包方案。

### 2026-03-14 12:10:00

## 补充记录
- 针对 Windows `full` 变体偶发的 `rcedit-x64.exe` 报错 `Fatal error: Unable to commit changes`，在 `apps/desktop/package.json` 的 `build` 配置中显式补充：
  - `copyright: "Copyright (C) 2026 SQL Copilot"`
- 目的：
  - 覆盖 electron-builder 默认生成的 `Copyright © year ${author}`，避免向 `rcedit` 传递 `©` 字符造成版本资源写入不稳定。

## 验证结果
- `npm run package:app:full` 再次执行通过。
- `rcedit` 命令已改为：
  - `--set-version-string LegalCopyright 'Copyright (C) 2026 SQL Copilot'`
- 本次复测中未再出现 `Unable to commit changes` 重试日志。
- 产物输出到：
  - `release/full/desktop-20260314040557/SQL-Copilot-full-0.1.0-win-x64.zip`
- 启动验收通过：
  - 后端：
    - `mvn -f apps/server/pom.xml clean package -DskipTests`
    - `java -Dfile.encoding=UTF-8 -jar apps/server/target/sql-copilot-server-0.1.0.jar --spring.profiles.active=medium --server.port=18080`
    - `GET http://127.0.0.1:18080/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
  - 前端：
    - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
    - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort`
    - `GET http://127.0.0.1:8888` 返回 `HTTP 200`

### 2026-03-14 13:45:00

## 补充记录
- 修复桌面打包时误带入数据库文件，以及后端默认数据库路径写死为工程绝对路径的问题。

## 关键改动
- `apps/server/src/main/resources/application.yml`
  - 新增 `sqlcopilot.storage.data-dir: ${SQLCOPILOT_DATA_DIR:.}`。
  - `spring.datasource.url` 改为 `jdbc:sqlite:${sqlcopilot.storage.data-dir}/sql-copilot.db`，不再绑定开发机绝对路径。
- `apps/desktop/electron/main.cjs`
  - 新增 `resolveBackendDataDir()`，桌面端启动内置后端时自动将 `SQLCOPILOT_DATA_DIR` 指向 Electron `userData` 目录。
  - 启动前主动创建数据目录，确保首次安装启动时可以直接建库。
- `scripts/package-variants.mjs`
  - 生成的 `run.cmd` / `run.sh` 启动脚本改为动态设置 `SQLCOPILOT_DATA_DIR`。
  - Windows 默认落到 `%LOCALAPPDATA%\\SQL Copilot`，Linux/macOS 默认落到 `${XDG_DATA_HOME:-$HOME/.local/share}/sql-copilot`。
- `apps/desktop/package.json`
  - `build.files` 新增 `!**/*.db`。
  - `extraResources` 中 `resources/backend` 的过滤规则新增 `!**/*.db`，防止运行后产生的数据库再次被打包带入产物。

## 验证结果
- 打包验证通过：
  - `npm run package:app:full`
  - 产物目录 `release/full/desktop` 下未发现 `.db` 文件。
  - `release/full/desktop/win-unpacked/resources/backend/application.yml` 中数据库路径已为占位写法，不再包含开发机绝对路径。
  - `release/full/desktop/win-unpacked/resources/backend/run.cmd` 已改为动态设置 `SQLCOPILOT_DATA_DIR`。
- 后端启动验证通过：
  - 从独立工作目录 `target/backend-runtime-check` 启动：
    - `java -Dfile.encoding=UTF-8 -jar apps/server/target/sql-copilot-server-0.1.0.jar --spring.profiles.active=medium --server.port=18080`
  - `GET http://127.0.0.1:18080/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
  - 数据库文件实际创建于 `target/backend-runtime-check/sql-copilot.db`，说明默认路径已随运行位置动态变化。
- 前端预览验证通过：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort`
  - `GET http://127.0.0.1:8888` 返回 `HTTP 200`

### 2026-03-14 13:50:00

## 补充记录
- 按平台拆分 Electron 桌面端图标资源：
  - Windows 使用 `icon.ico`
  - macOS 使用 `icon.icns`
  - Linux 使用 `icon.png`

## 关键改动
- `apps/desktop/package.json`
  - 移除顶层通用 `build.icon`。
  - 改为：
    - `build.win.icon = "../../icon.ico"`
    - `build.mac.icon = "../../icon.icns"`
    - `build.linux.icon = "../../icon.png"`

## 验证结果
- Windows 打包验证通过：
  - `npm run package:app:full`
  - `electron-builder` 日志已显示：
    - `path resolved   path=D:\Ideaprojects\SQL-Copilot\icon.ico outputFormat=ico`
    - `rcedit ... --set-icon 'D:\Ideaprojects\SQL-Copilot\icon.ico'`
  - 说明当前 Windows 构建已直接使用 `icon.ico`，不再从 `png` 临时转换。
- 启动验收通过：
  - 后端：
    - `GET http://127.0.0.1:18080/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
  - 前端：
    - `GET http://127.0.0.1:8888` 返回 `HTTP 200`

### 2026-03-14 14:05:00

## 补充记录
- 新增 GitHub Actions 多平台打包工作流，用于在 GitHub 上按当前 runner 环境分别产出 Windows、macOS、Linux 制品。

## 关键改动
- 新增 `.github/workflows/package-variants.yml`
  - 触发方式：
    - `workflow_dispatch`
    - `push` 到 `main` / `master`
    - `push` 标签 `v*`
  - 矩阵环境：
    - `windows-latest`
    - `macos-latest`
    - `ubuntu-latest`
  - 统一步骤：
    - `actions/checkout@v4`
    - `actions/setup-node@v4`，Node 22，启用 npm cache
    - `actions/setup-java@v4`，Temurin JDK 17，启用 Maven cache
    - Linux 额外安装 `libarchive-tools`
    - 执行 `npm ci`
    - 执行 `npm run package:variants`
    - 上传 `release/**` 为 GitHub artifact
  - 默认设置：
    - `SQLCOPILOT_MAC_SIGN=0`，避免 macOS runner 因未签名而失败

## 验证结果
- 工作流文件已生成：
  - `.github/workflows/package-variants.yml`
- 本地联动验证通过：
  - 后端 clean 启动：
    - `mvn -f apps/server/pom.xml clean package -DskipTests`
    - `GET http://127.0.0.1:18080/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
  - 前端 clean 预览：
    - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
    - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort`
    - `GET http://127.0.0.1:8888` 返回 `HTTP 200`

## 说明
- 该工作流仍然遵循当前项目的“按运行环境产出对应平台制品”逻辑：
  - Windows runner 产出 Windows 包
  - macOS runner 产出 macOS 包
  - Linux runner 产出 Linux 包
- 不做单机跨平台交叉打包；三平台制品通过矩阵任务汇总为独立 artifact。

### 2026-03-14 17:00:00

## 补充记录
- 复核 Windows `full` 变体的制品类型问题：用户期望不要只生成 `zip`。

## 结论
- 当前 `full` 包体量约 3 GB，Windows 下传统安装类目标暂时不可行：
  - `nsis` 安装包失败：`makensis.exe` 报错 `failed creating mmap of ...nsis.7z`
  - `portable` 单文件 exe 同样失败：内部仍会走 NSIS 打包流程，并触发相同 `failed creating mmap`
- 因此现阶段 Windows `full` 只能稳定保留为 `zip` 制品；`minimal` / `medium` 仍可正常生成安装包。

## 关键改动
- `scripts/package-variants.mjs`
  - 保持 Windows `full` 目标为 `zip`
  - 保留 Windows `rcedit` 提交异常的重试逻辑，但在最后一次仍检测到 `Unable to commit changes` 时改为告警而非直接中断，避免 `zip` 已成功生成却被脚本误判失败。

## 验证结果
- `npm run package:app:full` 已再次验证通过。
- 打包日志确认：
  - Windows `full` 使用 `zip` 目标可成功输出制品。
  - Windows `portable` 目标会在 NSIS 阶段失败，错误为 `failed creating mmap of ...nsis.7z`。

### 2026-03-14 21:55:00

## 补充记录
- 按最新要求收敛打包模式：不再保留 `minimal` / `full`，仅保留 `medium` 这一种打包模式。
- `medium` 继续保留 ONNX 框架能力，但打包产物不包含模型文件，模型由用户自行下载并配置目录。

## 关键改动
- 根 `package.json`
  - 移除 `package:app:minimal` / `package:app:full`
  - 新增 `package:app` 作为 `medium` 的同义入口
- `apps/desktop/package.json`
  - 移除 `build:minimal` / `build:full`、`dist:minimal` / `dist:full`
  - 保留 `build:medium` / `dist:medium`
  - 产物命名去掉包型后缀，改为 `SQL-Copilot-${version}-${os}-${arch}.${ext}`
  - 输出目录固定为 `release/desktop`
- `apps/desktop/src/config/packageVariant.ts`
  - 包型常量固定为 `medium`
  - 前端始终提供 `LOCAL_ONNX` / `ONLINE_OPENAI_COMPAT` 两种 RAG 运行模式选项
- `scripts/package-variants.mjs`
  - 仅接受 `medium` 包型
  - 汇总输出提示改为 `release/medium/...`
  - 不再包含 `full` / `minimal` 的特殊分支
- `.github/workflows/package-variants.yml`
  - 工作流名称改为 `Package Medium`
  - CI 只执行 `npm run package:variants`
  - artifact 名称改为 `sql-copilot-${platform}-medium`

## 验证结果
- 打包验证通过：
  - `npm run package:variants`
  - 成功输出 `release/desktop-20260314134755`
  - 产物为 `SQL-Copilot-0.1.0-win-x64.exe`
- 启动验收通过：
  - 后端：
    - `GET http://127.0.0.1:18080/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
  - 前端：
    - `GET http://127.0.0.1:8888` 返回 `HTTP 200`

### 2026-03-14 22:10:00

## 补充记录
- 后端配置进一步收敛为单一配置文件：仅保留 `application.yml`，不再保留 `application-medium.yml`。

## 关键改动
- `apps/server/src/main/resources/application.yml`
  - 合并原 `application-medium.yml` 的有效默认值：
    - `rag.embedding.provider-type = ONLINE_OPENAI_COMPAT`
    - `rag.embedding.model-dir = ""`
    - `rag.rerank.enabled = false`
    - `rag.rerank.provider-type = ONLINE_OPENAI_COMPAT`
    - `rag.rerank.model-dir = ""`
  - 保留 `sqlcopilot.rag.local-onnx-enabled = true`，继续支持用户手动切换到本地 ONNX。
- 删除：
  - `apps/server/src/main/resources/application-medium.yml`
- `apps/server/pom.xml`
  - 删除 `pack-medium` profile
  - 不再保留 `sqlcopilot.packaging.variant` 属性
- `scripts/package-variants.mjs`
  - 后端打包改为直接执行 `mvn clean package -DskipTests`
  - 不再复制 `application-${variant}.yml`
  - 生成的 `run.cmd` / `run.sh` 默认不再附带 `--spring.profiles.active=medium`
- `apps/desktop/electron/main.cjs`
  - 内置 backend 启动默认 profile 改为空；仅当显式设置 `SQLCOPILOT_BACKEND_PROFILE` 时才传递 `spring.profiles.active`

## 验证结果
- 后端 clean 构建通过：
  - `mvn -f apps/server/pom.xml clean package -DskipTests`
- 后端启动验收通过：
  - `java -Dfile.encoding=UTF-8 -jar apps/server/target/sql-copilot-server-0.1.0.jar --server.port=18080`
  - `GET http://127.0.0.1:18080/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 clean build 与预览通过：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort`
  - `GET http://127.0.0.1:8888` 返回 `HTTP 200`

### 2026-03-14 22:15:00

## 补充记录
- 去掉后端代码中与 `sqlcopilot.rag.local-onnx-enabled` 相关的参数判断与条件装配，统一改为按 provider 配置和本地服务可用性判断。

## 关键改动
- `apps/server/src/main/resources/application.yml`
  - 删除 `sqlcopilot.rag.local-onnx-enabled`
- `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/RagConfigServiceImpl.java`
  - 删除 `localOnnxEnabled` 配置注入
  - `normalizeProviderType(...)` 不再依赖开关，直接接受 `LOCAL_ONNX` / `ONLINE_OPENAI_COMPAT`
- `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/RagEmbeddingRouterServiceImpl.java`
  - 删除 `localOnnxEnabled` 配置注入
  - provider 归一化仅依赖配置值和 `localRagEmbeddingService` 是否存在
- `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/RagRerankRouterServiceImpl.java`
  - 删除 `localOnnxEnabled` 配置注入
  - provider 归一化仅依赖配置值和 `localRagRerankService` 是否存在
- `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/OnnxBgeM3EmbeddingServiceImpl.java`
  - 删除 `@ConditionalOnProperty(sqlcopilot.rag.local-onnx-enabled=true)`
- `apps/server/src/main/java/com/sqlcopilot/studio/service/rag/impl/OnnxLocalRerankServiceImpl.java`
  - 删除 `@ConditionalOnProperty(sqlcopilot.rag.local-onnx-enabled=true)`

## 验证结果
- 后端 clean 构建通过：
  - `mvn -f apps/server/pom.xml clean package -DskipTests`
- 后端启动验收通过：
  - `java -Dfile.encoding=UTF-8 -jar apps/server/target/sql-copilot-server-0.1.0.jar --server.port=18080`
  - `GET http://127.0.0.1:18080/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 clean build 与预览通过：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort`
  - `GET http://127.0.0.1:8888` 返回 `HTTP 200`

### 2026-03-14 22:30:34

## 补充记录
- 按最新要求继续收敛打包入口与 README 命令说明，去掉多余的模式参数和重复打包命令，只保留一个对外打包入口。

## 关键改动
- `package.json`
  - 删除 `package:variants` 与 `package:app:medium`，仅保留 `package:app`。
- `apps/desktop/package.json`
  - 删除 `build:medium` / `dist:medium`，统一由 `build` / `dist` 承担单一 medium 打包流程。
- `scripts/package-variants.mjs`
  - desktop 构建步骤改为调用统一的 `npm run -w @sqlcopilot/desktop build`，避免继续依赖包型后缀命令。
- `README.md`
  - 删除多余的 `:medium` 后缀命令、重复的一键打包入口和模式说明表。
  - 桌面打包命令收敛为 `npm run -w @sqlcopilot/desktop dist`。
  - 一键打包命令收敛为 `npm run package:app`。
- `.github/workflows/package-variants.yml`
  - CI 打包命令同步切换为 `npm run package:app`。

## 验证结果
- 前端校验通过：
  - `npm run type-check`
  - `npm run build`
- 前端预览通过：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort`
  - `GET http://127.0.0.1:8888` 返回 `HTTP 200`
- 后端 clean 启动通过：
  - `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18080"`
  - `GET http://127.0.0.1:18080/api/health` 返回包含 `ok` 的成功响应

### 2026-03-14 22:52:03

## 补充记录
- 继续收敛单模式打包实现，去掉当前代码与工作流中残留的 `medium` 包型字面量和 variant 骨架，避免“实际上只有一种模式，但实现仍按变体处理”的混乱。

## 关键改动
- `apps/desktop/package.json`
  - `build` 改为直接执行 `vite build --emptyOutDir`。
  - `dist` 改为直接执行 `electron-builder`，不再注入 `VITE_PACKAGE_VARIANT` / `SQLCOPILOT_PACKAGE_VARIANT`。
- `apps/desktop/src/config/packageVariant.ts`
  - 删除 `SqlCopilotPackageVariant`、默认 `medium` 常量和 `minimalPackage`。
  - 保留真正仍在使用的 RAG provider 选项与归一化逻辑，并显式导出 `ragLocalOnnxEnabled = true`。
- `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 删除 `packageVariant` 返回值和对包型常量的依赖，直接使用固定的本地 ONNX 可用状态。
- `scripts/package-variants.mjs`
  - 删除 `DEFAULT_VARIANTS`、`normalizeVariant`、`parseVariants` 和 `for variant of variants` 循环。
  - 脚本改为单次执行 `backend -> jlink -> desktop`。
  - 输出目录统一为 `release/desktop` 与可选的 `release/backend`。
  - 若仍传入旧的 variant 参数，脚本会直接报错提示不再支持。
- `.github/workflows/package-variants.yml`
  - 工作流名称由 `Package Medium` 改为 `Package App`。
  - artifact 名称去掉 `-medium` 后缀。
- `README.md`
  - 脚本描述改为“单包打包脚本”，不再出现 `medium` 模式表述。

## 验证结果
- 当前实现代码检索结果：
  - 在 `package.json`、`README.md`、`apps/desktop`、`scripts`、`.github` 中，已无与打包单模式相关的 `medium`、`VITE_PACKAGE_VARIANT`、`SQLCOPILOT_PACKAGE_VARIANT` 残留。
  - 剩余命中仅为业务样式类 `risk-level-medium`，与打包模式无关。
- 前端校验通过：
  - `npm run type-check`
  - `npm run build`
- 打包脚本实跑通过：
  - `npm run package:app`
  - 成功输出到 `release/desktop`
- 启动验收通过：
  - 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 8888 --strictPort`
  - `GET http://127.0.0.1:8888` 返回 `HTTP 200`
  - 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18080"`
  - `GET http://127.0.0.1:18080/api/health` 返回包含 `ok` 的成功响应


### 2026-03-20 17:28:23

## ????
- ??????????????????????????
- ??? Docker Desktop / Windows ???????????????`win-x64`?`mac-arm64`?`mac-x64`?`linux-x64`?
- ?????????????????????? Qdrant ???

## ????
- ?? `scripts/package-variants.mjs`?
  - ?? `--targets=` ?????????????????
  - ??????????`mac arm -> mac-arm64`?`mac x86 -> mac-x64`?`linux x86 -> linux-x64`?
  - ????????? `release/desktop/<target>`????????
- ??? `package.json`?
  - `package:app` ????????????
  - ?? `package:app:host`???????????????
- ?? `apps/desktop/scripts/download-qdrant.mjs`?
  - ??????? Qdrant ?????????
  - ?????????????????????
- ?? `apps/desktop/package.json`?
  - ?? `@electron/packager` ?????? Windows ????? macOS / Linux ??????
- ??????????
  - `win-x64` ???? `electron-builder` ??????
  - macOS ???? `electron-packager` ???????? zip?
  - Linux ???? `electron-packager` ???????? tar.gz?
  - ???`electron-builder` ??? Windows ????? macOS ???Linux AppImage ??? Windows ???? `mksquashfs`????????
- ????????
  - ???????? `jdeps + jlink`?
  - ?????????????? JDK 17 ??????????????????????
- README ????????????

## ????
- ?????????
  - `mvn -f apps/server/pom.xml clean package -DskipTests`
- ????????????
  - `npm run -w @sqlcopilot/desktop type-check`
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- ?????????
  - ? `mvn.cmd -f apps/server/pom.xml clean spring-boot:run` ????`GET http://127.0.0.1:18080/api/health` ?? `200`?
- ?????????
  - `npx vite preview --host 127.0.0.1 --port 4173 --strictPort`
  - `GET http://127.0.0.1:4173/` ?? `200`?
- ???????????
  - `release/desktop/win-x64/SQL-Copilot-0.1.0-win-x64.exe`
  - `release/desktop/mac-arm64/SQL-Copilot-0.1.0-mac-arm64.zip`
  - `release/desktop/mac-x64/SQL-Copilot-0.1.0-mac-x64.zip`
  - `release/desktop/linux-x64/SQL-Copilot-0.1.0-linux-x64.tar.gz`

## ??
- `release/desktop/win-unpacked` ?? Windows ??????????????????????
- ???????? `apps/desktop/resources/qdrant/darwin-x64/qdrant` ? `apps/desktop/resources/qdrant/linux-x64/qdrant`???????????


### 2026-03-21 00:47:25

## ????
- ?? GitHub Actions ??????????? GitHub ?????????????
- ????? runner ????????????????? runner ??????????

## ????
- ?? `.github/workflows/package-variants.yml`?
  - ?? `workflow_dispatch`???? `push` ? `main` / `master` ? `v*` ?????????
  - ???????????????????????
    - `windows-latest -> win-x64`
    - `macos-14 -> mac-arm64`
    - `macos-15-intel -> mac-x64`
    - `ubuntu-latest -> linux-x64`
  - ?????? `npm run package:app` ?? `node scripts/package-variants.mjs --targets=${{ matrix.target }}`????? job ?????????
  - ????????? `release/desktop/${{ matrix.target }}/**`?artifact ???? `sql-copilot-${{ matrix.target}`?
- ?????????? GitHub ?? runner?
  - Windows ?? Windows?
  - macOS arm / x64 ????? mac runner ????
  - Linux ?? Linux?
  - ??????? JDK ??????????????????

## ????
- ?? clean ???????
  - `mvn.cmd -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18081"`
  - `GET http://127.0.0.1:18081/api/health` ?? `200`?
- ?? clean build ? preview ?????
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
  - `npx vite preview --host 127.0.0.1 --port 4174 --strictPort`
  - `GET http://127.0.0.1:4174/` ?? `200`?

## ??
- ????? GitHub ??????????????
- ???????GitHub ???? job ??????????????????????????????


### 2026-03-21 10:18:47

## ????
- ?? GitHub Actions ? macOS runner ??? `Package target artifact` ??????
- ??????????????????????? clean ?????

## ????
- ?? `scripts/package-variants.mjs`?`macOS` ? `Linux` ????? `electron-packager`????? runner ????????? `electron-builder`?
- ?? `Windows` ????? `electron-builder`??????? `win-x64` ?????
- ???????? `SQLCOPILOT_DESKTOP_RELEASE_DIR`??????????????????????????????????? clean ??????????? `release/desktop`?

## ????
- mac ?????`$env:SQLCOPILOT_DESKTOP_RELEASE_DIR='release\\desktop-validation'; node scripts/package-variants.mjs --targets=mac-arm64,mac-x64` ???
- ???
  - `release/desktop-validation/mac-arm64/SQL-Copliot-0.1.0-mac-arm64.zip`
  - `release/desktop-validation/mac-x64/SQL-Copliot-0.1.0-mac-x64.zip`
- ?? clean ???`mvn.cmd -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081`?`GET /api/health` ?? `{"code":0,"message":"success","data":"ok"}`?
- ?? clean ???`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` ??`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4190 --strictPort` ?? `HTTP 200`?

## ??
- ?????????? `release/desktop/mac-arm64` ????? `app.asar` ? Windows ????? clean ???????? `SQLCOPILOT_DESKTOP_RELEASE_DIR` ???? clean ???????
- ????? preview ?? `4174` ??????????????? `4190` ??????


### 2026-03-21 11:48:51

## ????
- ????? SQL ??????? backend????? MySQL/SQL Server ???? `Handler dispatch failed: java.lang.NoClassDefFoundError: com/sqlcopilot/studio/util/SqlClassifier` ????
- ??????????????? 18080 ?????????????? backend?

## ????
- `apps/desktop/electron/main.cjs`
  - ????? backend ???? `resolvedBackendBaseUrl`????????? renderer ????? backend ???
  - ?? `resolveManagedBackendBaseUrl()`?????????? `18080`??????????????????????????????
  - ???? `SQLCOPILOT_BACKEND_URL` ??????????????? backend ?????????
  - ???? backend ??? `SERVER_PORT`??? run.cmd / run.sh / bundled JRE ???????????
- `apps/desktop/electron/preload.cjs`
  - ?? `backendBaseUrl` / `getBackendBaseUrl()` ? renderer??????????? `18080`?
- `apps/desktop/src/api/client.ts`
  - API ?????????????????? backend ?????????????? `http://localhost:18080`?

## ????
- ???? `spring-boot:run` ?? `/api/sql/execute` ????? `SqlClassifier` ???
- ????????????????? `127.0.0.1:18080` ??????????????????????? 18080 ??????? backend??????? + ???????
- ? backend ????? SQL ?????? `SqlClassifier`??? MySQL / SQL Server ???? `/api/sql/execute` ??? `NoClassDefFoundError`?Redis ?????? KV ??????????????

## ????
- Windows ?????`$env:SQLCOPILOT_DESKTOP_RELEASE_DIR='release\\desktop-runtime-validation'; node scripts/package-variants.mjs --targets=win-x64` ???
- ????? `http://127.0.0.1:18080/api/health` ?? `200` ??????? `release/desktop-runtime-validation/win-x64/win-unpacked/SQL Copliot.exe`???????? backend ??? `18081`?
- ???????????? backend?
  - `GET http://127.0.0.1:18081/api/health` ?? `{"code":0,"message":"success","data":"ok"}`?
  - `POST /api/connection/list` ?????????
  - `POST /api/sql/execute` ????????????? `SqlClassifier` ???
- clean ?????
  - ???`mvn.cmd -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18084`?`GET /api/health` ?? `ok`?
  - ???`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` ??`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4191 --strictPort` ?? `HTTP 200`?

## ??
- ?????????? `SqlClassifier` ???????????????????? backend ????
- ???????? `SQLCOPILOT_BACKEND_URL`?????????????????????????????????? backend?


### 2026-03-21 11:56:49

## ????
- ?? GitHub Actions ? macOS job ?? `npm run -w @sqlcopilot/desktop build` ???????? `Package target artifact` ??????
- ?????????????????? Vite ?????????????????? Unix signal?

## ????
- `apps/desktop/vite.config.ts`
  - ?? `manualChunks`?? `monaco-editor`?`ant-design-vue`?`echarts`?`vue`?`sql-formatter` ????????? chunk????? bundle ???? minify ???
  - ?? `SQLCOPILOT_VITE_SAFE_BUILD=1` ????????? JS/CSS minify????????????? macOS ???????????
- `scripts/package-variants.mjs`
  - `formatCommandFailure(...)` ???? `status` ????? `Signal SIG...`?????? `Exit code unknown`?
  - ???????? `NODE_OPTIONS=--max-old-space-size=8192`??? GitHub mac runner ????????
  - ? `darwin` ?????????????????????????????????????????

## ????
- ???????
  - `npm run -w @sqlcopilot/desktop type-check` ???
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` ???
  - `SQLCOPILOT_VITE_SAFE_BUILD=1 npm run -w @sqlcopilot/desktop build -- --emptyOutDir` ???
- mac ???????
  - `$env:SQLCOPILOT_DESKTOP_RELEASE_DIR='release\\desktop-mac-ci-validation'; node scripts/package-variants.mjs --targets=mac-arm64` ???
  - ???`release/desktop-mac-ci-validation/mac-arm64/SQL-Copliot-0.1.0-mac-arm64.zip`
- clean ?????
  - ???`mvn.cmd -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18085`?`GET /api/health` ?? `ok`?
  - ???`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` ??`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4192 --strictPort` ?? `HTTP 200`?

## ??
- ??????? GitHub workflow?mac job ??????????????signal ??? safe build ???
- ?? GitHub mac runner ??????????????? `SIGKILL`?`SIGABRT` ??????????????????
