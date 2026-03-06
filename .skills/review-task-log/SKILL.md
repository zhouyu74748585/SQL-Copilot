---
name: review-task-log
description: 任务完成后的代码变更记录技能。用于在每次任务收尾时检查工作区是否存在代码文件变更；仅当代码文件发生变化时，才在 docs/review 下创建 reviewTodo-{yyyyMMddHHmmss}.md，记录需求描述与变动文件及行号。
---

# review-task-log

## 执行目标

在任务完成阶段自动生成评审记录，沉淀本次需求和代码变更。仅在存在代码文件变更时创建记录文件。

## 执行时机

- 完成用户请求的实现或修改后
- 发送最终回复前
- `git status --short` 有代码文件变更输出时

## 执行步骤

1. 提炼本次需求描述（优先使用用户原始表述，必要时补充一句中文摘要）。
2. 执行 `git status --short --untracked-files=all` 获取变更文件。
3. 若无变更：
   - 跳过文件创建。
   - 在最终回复中说明“无文件变更，未生成 review 记录”。
4. 若仅有非代码文件变更（如 `*.md`、`*.txt`）：
   - 跳过文件创建。
   - 在最终回复中说明“仅非代码文件变更，未生成 review 记录”。
5. 若有代码文件变更：
   - 创建目录：`mkdir -p docs/review`
   - 生成文件名：`reviewTodo-$(date +%Y%m%d%H%M%S).md`
   - 写入需求描述与代码文件变动清单（每个文件附带行号）。
6. 在最终回复中返回生成文件的绝对路径。

## 推荐命令

```bash
$CODEX_HOME/skills/review-task-log/scripts/create_review_todo.sh "需求描述"
```

## 文件模板

```markdown
# 任务信息

## 需求描述
{需求描述}

## 变动文件
- [状态] 路径 (行号: 12-18,26)
```

## 注意事项

- 所有内容使用中文。
- 仅记录代码文件（默认扩展名：`java/kt/py/js/ts/vue/go/rs/c/cpp/cs/php/rb/swift/sh/sql` 等）。
- 变动文件保留状态标记（如 `M`、`A`、`D`、`R`、`??`）并附带行号。
- 一次任务只生成一条记录，避免重复创建。
