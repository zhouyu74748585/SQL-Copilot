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
