---
name: project-skill-bootstrap
description: Bootstrap project-local skill setup for new repositories. Use when a project needs a `.skills` directory, when installed skills must be copied into the repo (excluding `.system/skill-creator` and `.system/skill-installer`), or when `AGENTS.md` must include local skill rules, UTF-8 defaults, no legacy-data compatibility by default, AGENTS content in Chinese, and Chinese-first user replies.
---

# Project Skill Bootstrap

## 目标
初始化仓库，使后续智能体能够稳定发现并使用项目内技能，并按中文规则运行。

## 工作流
1. 解析项目根目录。
2. 检查 `<project-root>/.skills` 是否存在。
3. 若不存在，创建 `.skills` 并从 `/Users/zhouyu/.codex/skills/` 复制技能，排除 `.system/skill-creator` 与 `.system/skill-installer`。
4. 确保 `AGENTS.md` 存在，并包含技能使用与默认约束规则。
5. 输出创建/更新结果。

## AGENTS.md 必备约束
1. 优先使用 `<project-root>/.skills`。
2. 新工程缺少 `.skills` 时自动创建并复制技能（排除 `.system/skill-creator` 与 `.system/skill-installer`）。
3. 用户显式指定技能（如 `$skill-name`）或任务命中技能领域时，必须读取对应 `SKILL.md`。
4. 文本编码默认 UTF-8。
5. 除非用户明确要求，否则不兼容旧数据。
6. 生成或维护 `AGENTS.md` 时默认使用中文。
7. 与用户交互时默认使用中文回复，除非用户明确要求其他语言。

## 脚本
执行 `scripts/bootstrap_project_skills.sh` 自动完成初始化：

```bash
scripts/bootstrap_project_skills.sh /path/to/project
```
