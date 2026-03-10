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
