---
name: table-structure-edit-feature
overview: 在对象浏览页增加表结构编辑功能：操作按钮支持右键菜单，包含"新建表"和"编辑表"选项，点击后跳转至新页签打开表结构编辑界面
design:
  styleKeywords:
    - Professional
    - Clean
    - Functional
    - Navicat-style
  fontSystem:
    fontFamily: Roboto, PingFang-SC
    heading:
      size: 18px
      weight: 500
    subheading:
      size: 14px
      weight: 500
    body:
      size: 13px
      weight: 400
  colorSystem:
    primary:
      - "#1890FF"
      - "#40A9FF"
    background:
      - "#FFFFFF"
      - "#F5F5F5"
    text:
      - "#333333"
      - "#666666"
    functional:
      - "#52C41A"
      - "#FF4D4F"
todos:
  - id: add-table-editor-tab-types
    content: 在App.vue中添加TableEditorWorkspaceTab类型定义和相关状态管理
    status: completed
  - id: add-new-table-button
    content: 在对象浏览页工具栏添加"新建表"按钮
    status: completed
    dependencies:
      - add-table-editor-tab-types
  - id: add-edit-table-menu
    content: 在右键菜单添加"编辑表"选项(针对tables对象)
    status: completed
    dependencies:
      - add-table-editor-tab-types
  - id: create-table-editor-component
    content: 创建TableEditor.vue表结构编辑组件
    status: completed
    dependencies:
      - add-table-editor-tab-types
  - id: implement-table-editor-logic
    content: 实现表结构编辑页签的打开、切换、关闭逻辑
    status: completed
    dependencies:
      - create-table-editor-component
  - id: add-backend-table-create-api
    content: 在后端SchemaController添加创建表接口
    status: completed
  - id: add-backend-table-alter-api
    content: 在后端SchemaController添加修改表结构接口
    status: completed
  - id: verify-startup
    content: 启动验证后端和前端确保功能正常运行
    status: completed
    dependencies:
      - add-new-table-button
      - add-edit-table-menu
      - implement-table-editor-logic
      - add-backend-table-create-api
      - add-backend-table-alter-api
---

## 用户需求

在对象浏览页增加表结构编辑功能，操作按钮需支持右键菜单。菜单选项包括：新建表、编辑表。点击新建或编辑时，跳转至新的页签以打开表结构编辑界面。界面设计和交互逻辑需参考Navicat的实现风格。

## 核心功能

1. 对象浏览页工具栏添加"新建表"按钮入口
2. 右键菜单针对表对象添加"编辑表"选项
3. 点击新建或编辑时打开新的工作区页签
4. 表结构编辑界面参考Navicat设计风格，支持字段管理、索引管理、主键设置等
5. 后端提供CREATE TABLE和ALTER TABLE执行接口

## 技术栈

- 前端: Vue3 + TypeScript + Ant Design Vue
- 后端: Spring Boot + Java
- 打包工具: Vite (前端)

## 实现方案

### 前端实现

1. **对象浏览页工具栏增强**: 在现有"智能ER图"按钮旁添加"新建表"按钮
2. **右键菜单扩展**: 在targetType为'object'且objectType为'tables'时添加"编辑表"菜单项
3. **表结构编辑页签**: 创建TableEditorWorkspaceTab类型管理编辑页签，类似ErWorkspaceTab结构
4. **TableEditor.vue组件**: 新建表结构编辑器组件，包含:

- 表名和表备注输入
- 字段列表编辑（增删改）
- 主键设置
- 索引管理
- SQL预览和执行按钮

### 后端实现

1. **创建表接口**: POST /api/schema/table/create
2. **修改表接口**: POST /api/schema/table/alter
3. 利用现有SQL执行服务执行生成的DDL语句

### 实现细节

- 参照现有erTabs实现模式管理表编辑页签
- 参照现有右键菜单和triggerContextAction实现菜单动作
- 表结构数据使用现有TableDetailVO格式
- 编辑模式时通过tableDetail接口获取现有结构

## 设计风格

采用Navicat风格的表结构编辑器界面，简洁专业，功能区清晰划分。

## 页面规划

- **表结构编辑页签**: 新建工作区页签类型，显示"表结构编辑 - {表名}"
- **编辑区域**: 
- 顶部: 表名、表备注、基本信息
- 中部: 字段表格（列名、类型、长度、可空、主键、自增、默认值、备注）
- 底部: 索引管理、SQL预览、执行按钮

## UI设计

- 使用Ant Design Vue的表格、按钮、表单组件
- 字段编辑采用行内编辑模式
- 工具栏使用小型按钮(ghost类型)
- 整体配色与现有Navicat风格一致（深色侧边栏，浅色内容区）