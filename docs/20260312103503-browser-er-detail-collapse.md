# 主题：browser-er-detail-collapse

## 记录

### 2026-03-12 10:35:03

## 本次目标
- 为对象浏览页的“对象详情”增加与数据浏览页一致的折叠/展开能力。
- 为智能 ER 图页的“ER 图信息”增加与数据浏览页一致的折叠/展开能力。

## 关键改动
- 在运行态新增 `browserDetailCollapsed`，并为 `ErWorkspaceTab` 增加 `detailCollapsed` 字段，保证对象浏览页和每个 ER 页签都能独立记住折叠状态。
- 在对象浏览页和智能 ER 图页的标题栏增加折叠按钮，沿用数据浏览页的 `MenuFoldOutlined/MenuUnfoldOutlined` 交互，并在折叠时隐藏右侧详情面板与分隔条。
- 在 `shell.css` 中补充浏览页与 ER 页的 collapsed 布局类，使中间主区域在折叠后扩展到右侧，同时补齐小屏场景下 `workbench-er` 的自适应布局。
- 在 ER 快照恢复和 ER 选表创建页签时统一初始化 `detailCollapsed`，避免旧页签或快照恢复后出现未定义状态。

## 验证结果
- `npm run type-check` 通过。
- `npm run build` 通过。
- `mvn -f apps/server/pom.xml clean spring-boot:run "-Dspring-boot.run.arguments=--server.port=18082"` 启动成功。
- `curl http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4174` 启动成功。
- `curl -I http://127.0.0.1:4174` 返回 `HTTP/1.1 200 OK`。

## 遗留项
- 本次只复用了数据浏览页的折叠交互和布局扩展逻辑，没有新增折叠状态持久化；关闭应用后仍按当前运行态默认值恢复。
