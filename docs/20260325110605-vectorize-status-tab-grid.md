# 主题：vectorize-status-tab-grid

## 记录

### 2026-03-25 11:06:05

## 本次目标
- 修复库级向量化状态误投射到所有表的问题。
- 为顶部工作台 tab 增加右键菜单关闭能力。
- 为查询结果页和数据浏览页共用表格增加列宽拖拽。
- 为数据浏览页字段头增加快捷排序菜单，并与现有排序面板同步。

## 关键改动
- 后端 `SchemaOverviewVO.TableSummaryVO` 新增逐表向量状态字段：`vectorizeStatus`、`vectorizeMessage`、`vectorizeUpdatedAt`。
- 后端 `SchemaServiceImpl` 改为按当前库真实向量数据批量计算逐表状态，并对历史 `SUCCESS + 0 向量` 脏状态做自愈。
- 后端 `RagVectorizeQueueServiceImpl` 将数据库级状态与单表任务状态拆开：
  - 单表手动/队列向量化不再把整库状态写成 `SUCCESS`。
  - 空库整库向量化完成后回写 `NOT_VECTORIZED`，避免伪成功。
- 前端对象浏览表格改为优先消费后端返回的逐表向量状态；视图统一显示“不参与向量化”。
- 前端顶部工作台 tab 新增右键菜单：`关闭左侧`、`关闭右侧`、`关闭其他`，`browser` 作为锚点不参与关闭。
- 共用表格组件 `TableDataVirtualGrid` 新增：
  - 列宽拖拽手柄（80px~640px）
  - 数据浏览字段头快捷排序菜单：`正序` / `倒序` / `移除排序`
- 查询结果页按 statement 维度维护列宽；数据浏览页按 tab 维度维护列宽，并在分页/重载后保留同名列宽。

## 验证结果
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端 clean 构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 Maven clean 打包通过：`mvn -f apps/server/pom.xml clean package -DskipTests -Dfile.encoding=UTF-8`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18130' '-Dfile.encoding=UTF-8'`
- 健康检查通过：`curl http://127.0.0.1:18130/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 成功：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6072 --strictPort`
- 前端连通性通过：`curl -I http://127.0.0.1:6072` 返回 `HTTP/1.1 200 OK`

## 规范自检
- 已按 `backend-api-design` 检查：
  - 未新增 PUT/DELETE 接口；
  - 响应扩展继续使用显式 DTO/VO；
  - SQL 仍集中在既有 Repository/Mapper/Qdrant 客户端，不在 Service 中散写动态 SQL；
  - 新增关键逻辑补充了中文注释。
