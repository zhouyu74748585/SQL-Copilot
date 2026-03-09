# 主题：table-data-browser-tab-editing

## 记录

### 2026-03-09 15:22:32

## 本次目标
- 在对象浏览中将“表双击”改为打开数据浏览页签。
- 在表右键菜单新增“查询SQL”，保留原双击的“新建查询页签+默认SQL”行为。
- 新增数据浏览页签，支持分页查询、基础过滤、行详情表单、行增删改、提交与撤销。

## 关键改动
- 前端对象浏览交互
  - `apps/desktop/src/modules/studio/composables/useConnectionBrowserModule.ts`
    - 右键动作从单一 `queryData` 拆分为：
      - `querySql`：打开新查询页签，默认 SQL 为 `SELECT * FROM <table> LIMIT 100`。
      - `browseData`：打开数据浏览页签。
    - 表对象双击改为打开数据浏览页签；非表对象仍保持打开查询页签。
  - `apps/desktop/src/modules/studio/components/StudioShell.vue`
    - 右键菜单新增“查询SQL”，原“查询数据”调整为“数据浏览”。

- 前端数据浏览页签
  - 新增 `apps/desktop/src/modules/studio/composables/useTableDataModule.ts`
    - 负责数据浏览页签状态与行为：
      - 打开/关闭页签、分页加载、过滤应用、分页切换。
      - 行草稿状态（`clean/new/updated`）与删除缓存。
      - 新增/删除行、表格与右侧表单联动编辑。
      - 提交变更与撤销重载。
      - 脏数据拦截：有未提交改动时禁止切页/改过滤/切连接库。
  - `apps/desktop/src/modules/studio/components/StudioShell.vue`
    - 顶部页签新增数据浏览 tab 渲染。
    - 新增 `activeTableDataTab` 渲染分支：
      - 顶部过滤条件栏（字段+操作符+值，AND）。
      - 中间可编辑表格。
      - 右侧行详情表单。
      - 底部左侧新增/删除按钮，右侧分页+分页大小。
      - 提交/撤销按钮。
  - `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
    - 新增 `TableDataWorkspaceTab` 结构与 `tableDataTabs/activeTableDataTab` 状态。
    - 工作台 tab 管理纳入数据浏览 tab（激活兜底、连接删除联动清理等）。
    - 新增 `databaseOptionsForTableDataTab`。
  - `apps/desktop/src/modules/studio/composables/useStudioController.ts`
    - 注册并暴露 `useTableDataModule`。
  - `apps/desktop/src/modules/studio/composables/useUiShellModule.ts`
    - 右侧拖拽宽度逻辑支持数据浏览页签。
  - `apps/desktop/src/types/index.ts`
    - 新增 `TableDataPageReq/VO`、`TableDataCommitReq/VO`、`TableDataFilterOperator` 类型。
  - 样式
    - 新增 `apps/desktop/src/modules/studio/styles/table-data.css`。
    - `apps/desktop/src/style.css` 引入该样式。

- 后端数据浏览接口
  - 新增 `apps/server/src/main/java/com/sqlcopilot/studio/controller/TableDataController.java`
    - `POST /api/schema/table/data/page`
    - `POST /api/schema/table/data/commit`
  - 新增 DTO/VO（均为显式对象，字段含中文注释）
    - `TableDataPageReq` / `TableDataPageVO`
    - `TableDataCommitReq` / `TableDataCommitVO`
  - 新增服务与实现
    - `TableDataService`
    - `TableDataServiceImpl`
    - 关键规则：
      - 只读连接禁止提交。
      - 无主键表只允许浏览，提交直接拒绝。
      - 过滤操作符支持：`EQ/NE/GT/GTE/LT/LTE/LIKE/IS_NULL/IS_NOT_NULL`。
      - 提交采用单事务全成全败，执行顺序：删除 -> 更新 -> 新增。
      - 更新/删除严格按主键定位，受影响行数不为 1 时回滚并失败。
  - 新增集中 SQL 仓储
    - `apps/server/src/main/java/com/sqlcopilot/studio/repository/TableDataJdbcRepository.java`
    - 统一封装分页、过滤、count、insert/update/delete 动态 SQL。
    - 按数据库类型适配分页：
      - MySQL/PostgreSQL/SQLite：`LIMIT/OFFSET`
      - SQLServer/Oracle：`OFFSET ... FETCH NEXT`

## 验证结果
- 前端类型检查通过
  - `npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 Maven 打包通过
  - `mvn -f apps/server/pom.xml clean package`
- 启动验证（clean）通过
  - 后端：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18081'`
  - 健康检查：`curl http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
  - 前端预览：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
  - 预览连通性：`curl -I http://127.0.0.1:4173` 返回 `HTTP/1.1 200 OK`

## 说明
- 本次环境初始缺少前端依赖，先执行 `npm install` 后再进行前端校验。
- 已按“有未提交改动时禁止切页/改过滤，需先提交或撤销”的规则落地前端行为。


### 2026-03-09 15:24:05

## 追加验证（收尾复验）
- 后端在修正过滤字段大小写映射后再次通过：
  - `mvn -f apps/server/pom.xml clean package`
  - `mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18081'`
  - `curl http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端构建与类型检查结果保持通过，无需额外修复。


### 2026-03-09 15:41:43

## 追加修复：数据浏览页签布局错位（显示四块）
- 问题现象
  - 切到数据浏览页签时，主区布局异常，视觉上出现“四块”错位。
- 根因
  - `workbench-table-data` 缺少与 `workbench-query/workbench-table-editor` 对应的网格行列与分区定位规则，导致 `table-data` 分支中的中心区、右侧详情区、分割条走了自动排版。
- 修复内容
  - 更新 `apps/desktop/src/modules/studio/styles/shell.css`：
    - 新增 `.workbench.workbench-table-data` 网格定义（含 `grid-template-rows: auto minmax(0, 1fr)`）。
    - 新增 `.workbench.workbench-table-data .pane-left/.pane-splitter-left` 行跨越规则。
    - 新增 `.table-data-center-pane/.table-data-pane-splitter/.table-data-detail-pane` 的列行定位规则。
    - 在 `@media (max-width: 1400px)` 与 `@media (max-width: 1200px)` 中补充 `workbench-table-data` 响应式规则。
- 验证
  - `npm run -w @sqlcopilot/desktop type-check` 通过。
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort` 启动成功，`curl -I http://127.0.0.1:4173` 返回 `HTTP/1.1 200 OK`。
  - 追加后端收尾验证：
    - `mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18081'` 启动成功。
    - `curl http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。


### 2026-03-09 16:01:49

### 2026-03-09 16:00:00

## 追加修复：数据浏览底栏固定、图标化操作与无总数分页
- 目标
  - 底部操作栏固定在数据浏览页签底部。
  - 新增/删除使用 `+/-`，提交/撤销使用 `勾/叉`，过滤应用使用漏斗图标。
  - 数据列超宽时支持横向滚动，表格呈现方式与查询结果一致（直接表格）。
  - 提升查询结果表与数据浏览表的显示密度（字号/行高/内边距）。
  - 分页改为“不查总数，仅判断是否还有下一页”，分页大小默认 1000 且可输入。

## 关键改动
- 前端
  - `apps/desktop/src/modules/studio/components/StudioShell.vue`
    - 修正数据浏览分页方法绑定：替换旧 `handleTableDataPageChange`，改为 `prevTableDataPage`、`nextTableDataPage`、`updateTableDataPageSize`。
    - 新增 `tableDataScrollX` 绑定，数据浏览表按列宽计算横向滚动。
    - 底部按钮维持图标化：`+/-/✓/✗/漏斗`。
    - 分页改为“上一页/下一页 + 可输入每页条数”。
  - `apps/desktop/src/modules/studio/styles/table-data.css`
    - 数据浏览中心区改为 `flex` 纵向布局，`table-data-grid-wrap` 占满可用高度。
    - 底部栏使用 `margin-top: auto` + 顶部分割线，固定贴底。
    - 增加数据浏览表横向滚动样式（`ant-table-body` x 方向滚动）。
    - 新增查询结果表与数据浏览表统一紧凑样式：字体 11px、行高压缩、单元格 padding 缩小。
- 后端
  - `apps/server/src/main/java/com/sqlcopilot/studio/dto/schema/TableDataPageVO.java`
    - 分页响应由 `total` 改为 `hasNext`。
  - `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/TableDataServiceImpl.java`
    - 默认分页大小调整为 1000。
    - 分页查询采用 `pageSize + 1` 取数判断 `hasNext`，不再查询总数。
  - `apps/server/src/main/java/com/sqlcopilot/studio/repository/TableDataJdbcRepository.java`
    - 分页 SQL 使用 fetchSize（`pageSize+1`）返回数据，配合服务层裁剪判断下一页。
  - `apps/desktop/src/types/index.ts`、`apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`、`apps/desktop/src/modules/studio/composables/useTableDataModule.ts`
    - 前端类型与状态由 `total` 对齐到 `hasNextPage`。

## 验证结果
- 前端类型检查通过
  - `npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 前端预览通过
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
  - `curl -I http://127.0.0.1:4173` 返回 `HTTP/1.1 200 OK`
- 后端 Maven 打包通过
  - `mvn -f apps/server/pom.xml clean package`
- 后端 clean 启动与健康检查通过
  - `mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18081'`
  - `curl http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`


### 2026-03-09 17:46:15

### 2026-03-09 17:46:00

## 追加修复：顶部漏斗折叠筛选/排序、表格可编辑与性能优化
- 目标
  - 漏斗按钮上移到顶部，筛选默认折叠，展开后支持筛选规则与排序规则。
  - 底部按钮改为无边框图标按钮，新增刷新按钮（仅图标）。
  - 提升 1000 行数据渲染性能，降低卡顿。
  - 表单详情字段名追加字段注释。
  - 表格支持直接编辑（除主键外）；时间类型字段使用日期/时间选择器。

## 关键改动
- 前端交互与渲染
  - `apps/desktop/src/modules/studio/components/StudioShell.vue`
    - 数据浏览顶部新增漏斗切换按钮，筛选/排序面板默认隐藏。
    - 面板内新增“筛选规则 + 排序方式 + 应用按钮”布局。
    - 表格改为“默认文本 + 双击单元格进入编辑态”，避免全量 input 渲染。
    - 编辑器按列类型自动切换：`a-input` / `a-date-picker` / `a-time-picker`。
    - 底部操作按钮全部改为无边框图标风格，新增刷新图标按钮。
    - 分页按钮改为左右箭头图标。
    - 右侧表单详情标签追加列注释（`列名（注释）`）。
  - `apps/desktop/src/modules/studio/styles/table-data.css`
    - 新增顶部筛选折叠区、排序区、图标按钮与单元格编辑态样式。
    - 保持底部栏贴底并统一紧凑密度样式。
- 前端数据模块
  - `apps/desktop/src/modules/studio/composables/useTableDataModule.ts`
    - 新增 `tableDataSortDirectionOptions`、`toggleTableDataFilterPanel`、`add/removeTableDataSort`。
    - 新增单元格编辑状态管理：`start/stop/isTableDataCellEditing`。
    - 新增列编辑器类型判断 `tableDataColumnEditorType`。
    - 新增排序请求构建 `buildPageSorts`，分页请求携带 `sorts`。
    - 渲染性能优化：为显示行/列增加缓存与版本号（避免每次渲染全量重建 1000 行映射）。
  - `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
    - `TableDataWorkspaceTab` 增加筛选面板状态、排序草稿、编辑单元格状态、行列缓存版本等字段。
- 前端类型
  - `apps/desktop/src/types/index.ts`
    - `TableDataPageReq` 增加 `sorts[]`。
    - `TableDataPageVO.columns[]` 增加 `columnComment`。
- 后端接口
  - `apps/server/src/main/java/com/sqlcopilot/studio/dto/schema/TableDataPageReq.java`
    - 新增 `SortItem` 与 `sorts` 请求字段。
  - `apps/server/src/main/java/com/sqlcopilot/studio/dto/schema/TableDataPageVO.java`
    - 列信息新增 `columnComment`。
  - `apps/server/src/main/java/com/sqlcopilot/studio/service/impl/TableDataServiceImpl.java`
    - 新增排序白名单与方向校验（ASC/DESC）。
    - 查询阶段对排序字段做列映射归一。
    - 返回列信息时填充 `columnComment`。
  - `apps/server/src/main/java/com/sqlcopilot/studio/repository/TableDataJdbcRepository.java`
    - `queryPage` 支持接收排序条件列表。
    - `ORDER BY` 优先使用前端排序条件；无排序时回退主键/默认列排序。

## 验证结果
- 前端类型检查通过
  - `npm run -w @sqlcopilot/desktop type-check`
- 前端构建通过
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 前端预览通过
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
  - `curl -I http://127.0.0.1:4173` 返回 `HTTP/1.1 200 OK`
- 后端 Maven 打包通过
  - `mvn -f apps/server/pom.xml clean package`
- 后端 clean 启动与健康检查通过
  - `mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18081'`
  - `curl http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`


### 2026-03-09 17:55:56

### 2026-03-09 18:02:00

## 热修复：接口已返回但数据表格持续 loading
- 现象
  - 数据浏览接口网络层已返回，但页面表格区域持续转圈。

## 原因分析（高概率）
- 数据浏览表格在复杂单元格插槽 + 虚拟滚动组合下存在渲染阶段不稳定风险，导致 `loading=false` 后视图未正常落地。
- 同时此前在 `tableDataDisplayRows/Columns` 中写入响应式状态缓存，属于渲染期副作用，存在触发额外更新/不稳定的可能。

## 修复内容
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 移除数据浏览表格 `:virtual="true"`，先保证渲染稳定。
- `apps/desktop/src/modules/studio/composables/useTableDataModule.ts`
  - 将行/列缓存改为模块内 `Map` 非响应式缓存（按 `tab.key + version` 命中）。
  - 不再在渲染函数中修改 tab 响应式字段，消除渲染副作用。
  - 在关闭 tab 时清理对应缓存。

## 验证结果
- `npm run -w @sqlcopilot/desktop type-check` 通过。
- `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。


### 2026-03-09 18:00:16

### 2026-03-09 18:10:00

## 热修复2：持续转圈的并发请求保护
- 改动
  - `useTableDataModule.ts` 增加每个数据页签的分页请求序列号与 `AbortController`：
    - 新请求发起时自动中断旧请求。
    - 仅允许“最后一次请求”落地状态，避免并发覆盖导致 `loading` 不收敛。
    - 增加 20s 超时自动中断，防止挂起请求导致长期 loading。
  - 关闭数据页签时同步中断请求并清理序列状态。
  - `StudioShell.vue` 调整 `a-spin`：仅在“正在加载且当前无数据行”时展示遮罩，避免已返回数据仍被遮罩。

## 验证
- `npm run -w @sqlcopilot/desktop type-check` 通过。
- `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
