---
name: session-summary-docs
description: 在会话收尾时将任务总结写入当前工作区 docs 目录，使用 yyyyMMddHHmmss-{topic}.md 命名，并在已存在的同主题或相似主题文档中追加记录而非重复创建。用于任何需要沉淀开发阶段总结、修复记录、验证结果的任务结束场景。
---

# Session Summary Docs

在发送最终回复前，落盘本次会话总结到 `docs`，并保持“同主题单文档持续追加”。

## Workflow

1. 生成主题：
- 使用短主题（建议 2-6 个词），如 `graalvm-native-build`、`er-canvas-fix`。
- 主题仅用于文件名后缀，避免空格和路径分隔符。

2. 准备总结正文：
- 至少包含：本次目标、关键改动、验证结果、遗留项（可选）。
- 使用 UTF-8 编码写入。

3. 执行脚本写入/追加：

```bash
python3 "$CODEX_HOME/skills/session-summary-docs/scripts/upsert_session_summary.py" \
  --topic "er-canvas-fix" \
  --docs-dir "docs" <<'EOF'
## 本次目标
- 修复 ER 画布缩放与拖拽冲突

## 关键改动
- 调整缩放事件节流与拖拽边界判断

## 验证结果
- 后端 clean 启动成功
- 前端预览成功
EOF
```

4. 根据脚本结果确认：
- `created`：创建了新文档，文件名为 `yyyyMMddHHmmss-{topic}.md`。
- `appended`：命中同主题/相似主题文档并追加内容。

## Similarity Rule

脚本按以下顺序判定“同主题或相似主题”：

1. 主题规范化后完全相同。
2. 规范化后包含关系（A 包含 B 或 B 包含 A）。
3. 序列相似度阈值 `>= 0.72`，并取最高分文档。

## Script

- `scripts/upsert_session_summary.py`
- 输入总结正文（stdin 或 `--content-file`），输出 `status=<created|appended> path=<absolute_path>`。

## Note

- 默认写入 `docs` 根目录，不写入 `docs/review` 子目录。
- 在存在项目约束“无文件改动不记录”时，先检查 `git status --short` 再决定是否调用本脚本。
