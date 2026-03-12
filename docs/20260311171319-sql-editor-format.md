# 主题：sql-editor-format

## 记录

### 2026-03-11 17:13:19

## 本次目标
- 在 SQL 编辑页增加 SQL 美化功能，支持更快整理生成或手写的查询语句。

## 关键改动
- 前端新增 `sql-formatter` 依赖，用于 SQL 语句格式化。
- 在 `useStudioRuntime.ts` 中增加 SQL 美化方法，按连接类型自动映射 MySQL、PostgreSQL、SQLite、SQL Server、Oracle 方言。
- SQL 编辑区工具栏新增“美化 SQL”按钮；有选区时仅美化选中 SQL，无选区时美化整个编辑器内容。
- 在 Monaco 编辑器中注册“美化 SQL”动作，支持通过 `Alt+Shift+F` 触发。

## 验证结果
- 前端构建通过：`npm run build`
- 前端类型检查通过：`npm run type-check`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173`
- 后端 clean 启动通过：`mvn clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081 -Dfile.encoding=UTF-8`
- 后端健康检查通过：`curl http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`

## 备注
- 默认端口 `18080` 被现有 Java 进程占用，本次未中断原进程，改用 `18081` 完成启动验证。


### 2026-03-11 17:22:28

## 本次目标
- 调整 SQL 编辑区头部布局，将美化按钮与原底部操作按钮合并到同一行，并优化按钮图标与 hover 提示。

## 关键改动
- 删除 SQL 编辑区顶部的“SQL 编辑与执行”标题文案。
- 将原底部操作按钮整体提升到标题栏，与“记忆理解”开关同处一行。
- 将 SQL 美化按钮图标替换为更接近魔法棒语义的 `HighlightOutlined`。
- 恢复 SQL 美化按钮的 hover 提示，保持“美化 SQL / 美化选中的SQL”动态文案。
- 调整标题栏样式为自适应高度，支持按钮换行，移除不再使用的底部 `editor-actions` 容器样式。

## 验证结果
- 前端构建通过：`npm run build`
- 前端类型检查通过：`npm run type-check`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173`
- 后端 clean 启动通过：`mvn clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081 -Dfile.encoding=UTF-8`
- 后端健康检查通过：`curl http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`

## 备注
- 默认端口 `18080` 仍被现有 Java 进程占用，本次继续使用 `18081` 完成启动验证。


### 2026-03-12 18:05:46

## 本次目标
- 为 SQL 编辑页补齐字段补全能力。
- 对象浏览中选中视图时，详情展示视图定义 SQL。
- 视图/函数定义编辑页补齐 SQL 补全、美化能力，并让编辑器区域占满容器。

## 关键改动
- `useStudioRuntime.ts` 为 Monaco SQL 补全新增字段候选：支持基于 `FROM/JOIN/UPDATE/INTO` 上下文解析表别名、表名与库表限定名，在 `别名.` / `表名.` 后加载字段元数据并提示列名，同时对已引用表提供非限定字段候选。
- SQL 格式化动作从查询页专属选择逻辑中拆出，保留 `Alt+Shift+F` 快捷键；对象定义编辑器也能直接触发美化。
- 对象浏览详情新增视图/函数定义 SQL 展示，复用 `/api/schema/object/definition` 接口并以高亮代码块呈现。
- `StudioShell.vue` 为对象定义编辑页新增“美化 SQL”按钮，并补充视图/函数详情卡片。
- `shell.css` 新增对象定义编辑器布局样式，确保无底部执行面板时编辑器区域仍可撑满剩余空间。

## 验证结果
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端 clean 构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4177 --strictPort`，`curl -I http://127.0.0.1:4177/` 返回 `HTTP/1.1 200 OK`
- 后端 clean 启动通过：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18090" "-Dfile.encoding=UTF-8"`
- 后端健康检查通过：`curl http://127.0.0.1:18090/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`

## 遗留项
- 当前字段补全以表别名/表名与常见 SQL 子句为主，复杂 CTE、子查询嵌套和极端方言语法仍可能需要后续增强。


### 2026-03-12 18:17:00

## 本次目标
- 为视图/函数对象页补齐新建入口，并增加“新建查询”按钮。
- 让对象详情中的视图/函数定义 SQL 自动美化后展示。
- 约束复制表快捷键：左侧树节点选中表或对象列表选中表时可触发；存在文本选区时回退到默认复制/粘贴。

## 关键改动
- `useStudioRuntime.ts` 新增 `canCreateView`、`canCreateFunction` 能力判断；对象详情中的定义 SQL 改为按当前数据库方言调用格式化器后再高亮展示。
- `StudioShell.vue` 在表、视图、函数、查询浏览页左上角补齐“新建查询”按钮；视图/函数页分别补齐“新建视图”“新建函数”按钮。
- `useTableCopyModule.ts` 在浏览态快捷键判断中新增文本选区检测，若用户已选择文本，则 `Cmd/Ctrl+C/V` 不再触发表复制/粘贴逻辑。

## 验证结果
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端 clean 构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4178 --strictPort`，`curl -I http://127.0.0.1:4178/` 返回 `HTTP/1.1 200 OK`
- 后端 clean 启动通过：`mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18091" "-Dfile.encoding=UTF-8"`
- 后端健康检查通过：`curl http://127.0.0.1:18091/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
