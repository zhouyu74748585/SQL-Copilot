# 主题：group-create-connection-default-selection

## 记录

### 2026-03-19 01:55:00

## 本轮变更
- 修正连接树中“分组”节点右键新建连接的行为。
- 当用户在某个分组上右键选择“新建连接”时，连接创建弹窗默认选中当前分组，而不是回退到首个分组或空值。

## 涉及文件
- `apps/desktop/src/modules/studio/composables/useConnectionBrowserModule.ts`

## 验证
- 待执行前端 type-check / build 以及 clean 后启动验证。
