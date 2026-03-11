# 主题：query-chat-table-mention

## 记录

### 2026-03-11 23:28:00

## 本次目标
- 在对话窗口输入框中增加 `@` 选表能力，快速引用当前库表。
- 在已引用表后输入 `.` 时，继续弹出该表字段列表。
- 将引用面板改为浮层样式，显示在输入框上方，不再把输入框整体顶起。

## 关键改动
- 前端 `apps/desktop/src/modules/studio/composables/useQueryModule.ts`
  - 新增对话输入引用补全状态，识别 `@表名` 与 `@表名.字段` 两类上下文。
  - 复用现有 schema 概览与 `tableDetail` 接口，分别加载当前库表名和字段名。
  - 支持键盘上下选择、`Enter` / `Tab` 确认、`Esc` 关闭。
- 前端 `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 新增表详情缓存，避免字段补全重复请求。
  - 在发送 AI 对话请求前，将 `@table` / `@table.column` 归一化为显式 schema 引用说明，减少 `@` 语法对模型和检索链路的干扰。
- 前端 `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 对话输入框接入输入、光标移动与补全面板事件。
  - 将补全面板放入输入框容器，并改为绝对定位浮层。
- 样式 `apps/desktop/src/modules/studio/styles/shell.css`
  - 新增引用浮层样式、hover/active 状态和主题适配。
  - 浮层默认显示在输入框上方，避免影响 composer 原有布局。

## 验证结果
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端 clean 构建：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18100' '-Dfile.encoding=UTF-8'` 成功，`http://127.0.0.1:18100/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npx vite preview --host 127.0.0.1 --port 6055 --strictPort` 成功，`http://127.0.0.1:6055` 返回 `HTTP 200`。

## 备注
- 当前引用浮层固定在输入框上方，优先满足“不顶起输入框”的交互要求。
- 若后续需要进一步做成跟随光标的 mention/popover，可在现有输入上下文识别逻辑上继续演进。


### 2026-03-11 23:46:00

## 追加目标
- 修复对话输入浮层在键盘上下选择表/字段时，滚动条不跟随的问题。

## 追加改动
- 前端 `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 为引用浮层列表和候选项增加 DOM ref 绑定。
  - 监听 `queryPromptAssist.activeIndex` 对应的激活项变化，在浮层渲染完成后调用 `scrollIntoView({ block: 'nearest' })`。
  - 保持现有上下键切换逻辑不变，仅补足可视区域自动定位。

## 追加验证
- 前端类型检查：`npm run -w @sqlcopilot/desktop type-check` 通过。
- 前端 clean 构建：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 clean 启动：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18101' '-Dfile.encoding=UTF-8'` 成功，`http://127.0.0.1:18101/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端预览：`npx vite preview --host 127.0.0.1 --port 6056 --strictPort` 成功，`http://127.0.0.1:6056` 返回 `HTTP 200`。
