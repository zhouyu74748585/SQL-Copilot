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


### 2026-03-09 22:10:31

## 追加优化：数据浏览大分页切页/切页签卡顿
- 目标
  - 缓解数据浏览页在单页约 1000 行时，翻页或切换页签导致的明显卡顿。

## 改动
- `apps/desktop/src/modules/studio/components/TableDataVirtualGrid.vue`
  - 新增独立的数据浏览虚拟滚动表格组件。
  - 使用固定行高 + 可视区裁剪 + overscan，只渲染当前视窗附近的行，避免整页 1000 行同时挂载。
  - 保留横向滚动、行选中、双击单元格编辑、日期/时间编辑器切换等现有交互。
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 数据浏览中心表格从原 `a-table` 整表渲染切换为 `TableDataVirtualGrid`。
  - 保持现有分页、提交、撤销、详情表单等周边行为不变。
- `apps/desktop/src/modules/studio/styles/table-data.css`
  - 新增虚拟表格头部、行层、空状态、选中态和滚动容器样式。
  - 移除仅适用于旧 `a-table` 数据浏览表格的样式依赖。

## 说明
- 本次没有重新启用 `a-table` 的内建虚拟能力，而是采用独立虚拟表格组件，原因是此前该路径在当前复杂单元格编辑场景下出现过“接口已返回但表格持续 loading”的不稳定问题。
- 当前方案把虚拟滚动控制收敛在本地组件内，降低了整表重挂载带来的切页和切页签成本，同时避开了之前的不稳定组合。

## 验证结果
- `npm run -w @sqlcopilot/desktop type-check` 通过。
- `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 clean 启动验证通过：
  - `mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081`
  - `http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端预览验证通过：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
  - `http://127.0.0.1:4173` 返回 `HTTP 200`


### 2026-03-09 23:04:49

## 追加微调：漏斗图标跟随表名，底部工具栏垂直居中
- 目标
  - 将筛选漏斗图标移动到表名后面，放在同一标题组内。
  - 让底部工具栏在底栏高度内保持垂直居中。

## 改动
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 标题栏拆成“左侧标题组 + 右侧详情收起按钮”。
  - 漏斗图标从右侧 action 区移动到表名后面。
- `apps/desktop/src/modules/studio/styles/table-data.css`
  - 新增 `table-data-title-main` 标题组样式。
  - 将底部工具栏改为固定最小高度并使用对称内边距，保证按钮组在底栏区域内垂直居中。

## 验证结果
- `npm run -w @sqlcopilot/desktop type-check` 通过。
- `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 clean 启动验证通过：
  - `mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081`
  - `http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端预览验证通过：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
  - `http://127.0.0.1:4173` 返回 `HTTP 200`


### 2026-03-09 23:00:47

## 追加微调：收起图标、底部工具栏留白、详情注释省略展示
- 目标
  - 提升数据浏览页右侧详情收起按钮的图标语义。
  - 让底部工具栏与底边界保持更舒适的留白，并缩小图标尺寸。
  - 让右侧数据详情中的列注释保持单行显示，超长时省略并支持 hover 查看完整内容。

## 改动
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 将数据详情收起/展开图标从左右箭头改为 `menu-fold/menu-unfold`。
  - 右侧详情标签改为“列名 + 注释 tooltip”结构。
- `apps/desktop/src/modules/studio/styles/table-data.css`
  - 调整底部工具栏的上下内边距与按钮高度。
  - 缩小底部工具栏图标尺寸。
  - 为详情标签新增单行省略样式，注释超长时直接截断并保持不换行。

## 验证结果
- `npm run -w @sqlcopilot/desktop type-check` 通过。
- `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 clean 启动验证通过：
  - `mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081`
  - `http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端预览验证通过：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
  - `http://127.0.0.1:4173` 返回 `HTTP 200`


### 2026-03-09 22:57:18

## 追加优化：数据浏览右侧详情可收起
- 目标
  - 允许收起数据浏览页右侧“数据详情”面板。
  - 收起后让中间表格区域占满中间与右侧空间。

## 改动
- `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts`
  - 为 `TableDataWorkspaceTab` 增加 `detailCollapsed` 状态。
- `apps/desktop/src/modules/studio/composables/useTableDataModule.ts`
  - 新增 `toggleTableDataDetailCollapsed`，用于切换数据详情面板显隐。
  - 新打开的数据浏览页签默认保持详情展开。
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 数据浏览标题栏新增“展开/收起数据详情”按钮。
  - 收起时隐藏右侧详情面板与分隔条。
- `apps/desktop/src/modules/studio/styles/shell.css`
  - 新增收起态布局规则：数据浏览中间区域跨到第 5 列，占满原中间区与右侧详情区。

## 验证结果
- `npm run -w @sqlcopilot/desktop type-check` 通过。
- `npm run -w @sqlcopilot/desktop build -- --emptyOutDir` 通过。
- 后端 clean 启动验证通过：
  - `mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081`
  - `http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端预览验证通过：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4173 --strictPort`
  - `http://127.0.0.1:4173` 返回 `HTTP 200`


### 2026-03-10 09:58:27

## 2026-03-10 数据浏览页首屏加载卡住修复

### 本次目标
- 修复“数据浏览页首次打开一直转圈，切换页签后才刷新表格”的问题。

### 关键改动
- 文件：`apps/desktop/src/modules/studio/composables/useTableDataModule.ts`
- 调整 `touchTableDataTab(tab)`：在更新 `updatedAt` 后，额外将 `runtime.tableDataTabs` 对应索引项做一次浅拷贝回写。
- 目的：确保无论调用链中传入的是响应式代理还是原始对象，`loading/rows/error` 的变更都能稳定触发 Vue 视图更新，避免首屏卡在 loading。

### 验证结果
- 前端构建：`npm run build`（根目录）通过。
- 前端类型检查：`npm run type-check`（根目录）通过。
- 后端启动验证：
  - `apps/server` 执行 `mvn clean` 通过。
  - `mvn spring-boot:run` 在默认 `18080` 端口受环境中已有进程占用（PortInUseException）。
  - 使用 `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18081` 启动成功，并正常停止。
- 前端预览验证：
  - `apps/desktop` 启动 `npm run preview -- --host 127.0.0.1 --port 4173 --strictPort` 成功。
  - `curl -I http://127.0.0.1:4173/` 返回 `HTTP/1.1 200 OK`。

### 备注
- 工作区存在用户既有修改：`apps/server/src/main/resources/application.yml`，本次未改动该文件。


### 2026-03-11 11:48:53

## 追加记录（数据浏览页返回后持续 loading 修复，2026-03-11 11:48）

### 本次目标
- 修复数据浏览页在后端已返回分页数据后，前端仍持续转圈且不渲染结果的问题。

### 根因分析
- `apps/desktop/src/modules/studio/composables/useTableDataModule.ts` 中的 `touchTableDataTab` 会把 `tableDataTabs` 中当前项替换成一个新对象。
- 但 `loadTableDataPage(tab)` 的异步请求在此之后仍持续修改旧的 `tab` 引用。
- 原实现回写时使用的是数组中的旧快照 `tabs[index]`，导致请求完成后 `loading=false`、`rows`、`columns` 等最新状态没有同步回真实渲染源，页面因此停留在“后端已返回但前端仍 loading”的状态。

### 关键改动
- 更新 `apps/desktop/src/modules/studio/composables/useTableDataModule.ts`
  - 修正 `touchTableDataTab(tab)` 的同步逻辑。
  - 从“基于 `tabs[index]` 旧快照回写”改为“基于当前传入的 `tab` 全量回写”。
  - 保证数据浏览分页请求异步期间对 `tab.loading`、`tab.rows`、`tab.columns`、`tab.errorMessage` 等字段的修改，最终都会同步到 `runtime.tableDataTabs` 的真实渲染对象。

### 验证结果
- 前端类型检查通过：
  - `npm run type-check`
- 前端构建通过：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 Maven clean package 通过：
  - `mvn -f apps/server/pom.xml clean package -DskipTests`
- 后端启动验证通过：
  - `java -Dfile.encoding=UTF-8 -jar apps/server/target/sql-copilot-server-0.1.0.jar --spring.profiles.active=medium --server.port=18082`
  - `GET http://127.0.0.1:18082/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端预览验证通过：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4174 --strictPort`
  - `HEAD http://127.0.0.1:4174` 返回 `200 OK`

### 备注
- 本次问题属于前端状态同步缺陷，后端接口无需修改。


### 2026-03-11 12:24:15

## 追加记录（数据浏览刷新/换页改为先清空再 loading，2026-03-11 12:24）

### 本次目标
- 调整数据浏览页在刷新、换页、筛选排序重载时的交互：请求发出后立即清空旧结果并显示加载动画，而不是保留旧数据直到新结果返回后再切换。

### 关键改动
- 更新 `apps/desktop/src/modules/studio/composables/useTableDataModule.ts`
  - 在 `loadTableDataPage(tab)` 请求开始阶段，先清空当前页旧行数据与选中/编辑状态：
    - `tab.rows = []`
    - `tab.deletedRows = []`
    - `tab.selectedRowKey = ''`
    - `tab.editingCellKey = ''`
    - `tab.dirty = false`
    - `tab.hasNextPage = false`
  - 同步递增 `rowDataVersion` 并失效展示缓存，确保虚拟表格立即切换为空白态。
  - 保留列定义不清空，使加载过程中表头结构仍稳定，页面不会出现整块跳变。
- 结合现有 `a-spin` 条件，刷新/换页时会立即进入空白 loading 动画，待新数据返回后再渲染结果。

### 验证结果
- 前端类型检查通过：
  - `npm run type-check`
- 前端构建通过：
  - `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 Maven clean package 通过：
  - `mvn -f apps/server/pom.xml clean package -DskipTests`
- 后端启动验证通过：
  - `java -Dfile.encoding=UTF-8 -jar apps/server/target/sql-copilot-server-0.1.0.jar --spring.profiles.active=medium --server.port=18083`
  - `GET http://127.0.0.1:18083/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端预览验证通过：
  - `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 4175 --strictPort`
  - `HEAD http://127.0.0.1:4175` 返回 `200 OK`


### 2026-03-26 11:57:41

## 本次目标
- 为数据浏览页增加多选能力，支持批量删除当前页已勾选的数据行。

## 关键改动
- 数据浏览状态新增 `checkedRowKeys`，保留原有单行详情选择能力，同时支持复选框多选。
- `TableDataVirtualGrid` 新增首列复选框与表头全选能力，并增加 `rowSelectionEnabled` 开关，避免影响查询结果表格。
- 数据浏览底部删除按钮调整为“优先删除已勾选多行，否则回退删除当前选中行”，并展示已勾选数量。
- 批量删除仍复用原有提交协议：已存在行进入 `deletedRows`，新建未提交行直接从草稿列表移除。

## 验证结果
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端 clean 构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18131' '-Dfile.encoding=UTF-8'`
- 后端健康检查通过：`curl -s http://127.0.0.1:18131/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 成功：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6076 --strictPort`
- 前端连通性通过：`curl -I http://127.0.0.1:6076` 返回 `HTTP/1.1 200 OK`


### 2026-03-26 16:15:10

## 追加记录（数据浏览提交失败前端弹窗提示，2026-03-26 16:00）

### 本次目标
- 在数据浏览页提交数据变更失败时，提供明确的前端弹窗提示，避免失败仅停留在异常状态或页内文本。

### 关键改动
- 更新 `apps/desktop/src/modules/studio/composables/useTableDataModule.ts`
  - 为 `submitTableDataChanges(tab)` 增加 `catch` 分支。
  - 提交前先清空旧的 `errorMessage`，避免历史错误残留。
  - 提交失败时保留当前未提交变更，并将错误写回 `tab.errorMessage`。
  - 新增 `Modal.error` 弹窗，提示“提交数据变更失败”，并告知用户可修正后重试。
- 更新 `apps/desktop/src/i18n/messages.ts`
  - 为新增失败弹窗标题、说明文案和兜底错误文案补充中英双语映射。

### 验证结果
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端 clean 构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18132' '-Dfile.encoding=UTF-8'`
- 后端健康检查通过：`curl http://127.0.0.1:18132/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端 preview 成功：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6077 --strictPort`
- 前端连通性通过：`curl -I http://127.0.0.1:6077` 返回 `HTTP/1.1 200 OK`


### 2026-04-10 10:21:25

## 本次目标
- 在数据浏览页面的列标题下拉菜单中，除快捷排序外新增“添加到筛选”能力。

## 关键改动
- `apps/desktop/src/modules/studio/components/TableDataVirtualGrid.vue`
  - 表头下拉菜单新增“添加到筛选”入口。
  - 新增 `add-to-filter` 事件透传，并补充对应国际化文案。
- `apps/desktop/src/modules/studio/composables/useTableDataModule.ts`
  - 新增 `addTableDataFilterForColumn(tab, columnName)` 方法。
  - 点击列标题菜单后会自动展开筛选面板，并按当前列追加一条默认 `EQ` 的筛选草稿。
  - 对无效列名增加保护提示，避免异常状态写入。
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 将表头菜单新增事件接入页面级数据浏览模块。
- `apps/desktop/src/modules/studio/styles/table-data.css`
  - 为标题下拉菜单新增分割线样式，区分快捷排序与筛选操作。

## 验证结果
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端 clean 构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 前端预览通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6076 --strictPort`
- 前端连通性通过：`curl -I http://127.0.0.1:6076` 返回 `HTTP/1.1 200 OK`
- 后端 clean 启动通过：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18131' '-Dfile.encoding=UTF-8'`
- 后端健康检查通过：`curl -s http://127.0.0.1:18131/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`


### 2026-04-10 10:31:58

## 本次目标
- 为数据浏览页面增加当前页查找/替换能力。
- 支持 `Ctrl+F` / `Command+F` 快捷键从页面底部弹出查找栏，并可切换到替换模式。

## 关键改动
- `apps/desktop/src/modules/studio/composables/useStudioRuntime/types.ts`
  - 为数据浏览 Tab 增加查找/替换状态：查找栏显示、替换显示、关键词、替换词、匹配列表、当前匹配索引。
- `apps/desktop/src/modules/studio/composables/useTableDataModule.ts`
  - 新增当前页匹配计算、上一条/下一条定位、单个替换、全部替换等逻辑。
  - 查找范围限定为当前分页已加载的数据行。
  - 替换仅对可编辑且非主键列生效，替换后继续复用现有脏数据管理与提交流程。
- `apps/desktop/src/modules/studio/composables/useUiShellModule.ts`
  - 新增数据浏览页 `Ctrl+F` / `Command+F` 快捷键拦截，阻止浏览器默认查找并打开页内查找栏。
- `apps/desktop/src/modules/studio/composables/useStudioController.ts`
  - 将活动数据浏览 Tab 的查找栏打开动作接入全局快捷键模块。
- `apps/desktop/src/modules/studio/components/StudioShell.vue`
  - 在数据浏览页表格下方新增查找/替换面板。
  - 支持查找输入、匹配计数、上一条/下一条、显示/隐藏替换、替换当前、全部替换。
  - 新增输入框自动聚焦逻辑。
- `apps/desktop/src/modules/studio/components/TableDataVirtualGrid.vue`
  - 增加匹配单元格高亮与当前匹配高亮。
  - 当前匹配切换时自动滚动到对应数据行。
- `apps/desktop/src/modules/studio/styles/table-data.css`
  - 增加底部查找/替换栏与匹配高亮样式。

## 验证结果
- 前端类型检查通过：`npm run -w @sqlcopilot/desktop type-check`
- 前端 clean 构建通过：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
- 前端 preview 通过：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6076 --strictPort`
- 前端连通性通过：`curl -I http://127.0.0.1:6076` 返回 `HTTP/1.1 200 OK`
- 后端 clean 启动通过：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18131' '-Dfile.encoding=UTF-8'`
- 后端健康检查通过：`curl -s http://127.0.0.1:18131/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
