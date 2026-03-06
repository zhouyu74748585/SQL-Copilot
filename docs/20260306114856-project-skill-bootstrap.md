# 2026-03-06 工程技能目录与 AGENTS 规范落地记录

## 本次目标
- 在工程根目录补齐 `.skills` 技能目录。
- 统一使用 `AGENTS.md` 作为智能体提示文件（不使用 `AGENT.md`）。
- 在提示规则中补充技能发现与使用约束，并明确 UTF-8 与旧数据兼容策略。

## 实施内容
- 新建工程本地技能目录：`.skills/`。
- 将已安装技能从 `/Users/zhouyu/.codex/skills/` 同步到 `.skills/`（含 `.system` 与业务技能目录）。
- 更新 `AGENTS.md`，新增规则：
  - 若新工程缺失 `.skills`，需自动创建并复制已安装技能。
  - 优先使用项目内 `.skills`。
  - 用户显式指定技能或任务命中技能领域时，必须读取对应 `SKILL.md`。
  - 技能缺失/不可读时，需说明并降级执行。
- 依据用户反馈“生成文件是 AGENTS.md”，删除了临时创建的 `AGENT.md`，最终仅保留 `AGENTS.md`。

## 验证结果（clean 启动）
- 后端：
  - 执行 `mvn -f apps/server/pom.xml clean spring-boot:run -Dspring-boot.run.arguments=--server.port=18081`
  - `http://127.0.0.1:18081/api/health` 返回 `{"code":0,"message":"success","data":"ok"}`
- 前端：
  - 执行 `npm run -w @sqlcopilot/desktop type-check`
  - 执行 `npm run -w @sqlcopilot/desktop build -- --emptyOutDir`
  - 执行 `npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6045`
  - `http://127.0.0.1:6045` 可访问，页面标题为 `SQL Copilot`

## 备注
- 本次新增/修改文本文件均采用 UTF-8 编码。
- 默认策略保持“除非明确要求，不兼容旧数据”。

---

## 追加记录（2026-03-06 11:54:38）- 技能列表可见性修复

### 问题
- 用户反馈技能列表中看不到“新生成技能”。

### 原因
- 之前仅在项目内创建/同步了 `.skills`，未在全局技能库 `/Users/zhouyu/.codex/skills/` 下创建新的技能目录，因此不会出现在全局技能列表。

### 修复
- 新建全局技能：`/Users/zhouyu/.codex/skills/project-skill-bootstrap`。
- 使用 `init_skill.py` 初始化 `SKILL.md` 与 `agents/openai.yaml`。
- 完成技能内容编写并新增脚本：
  - `scripts/bootstrap_project_skills.sh`
- 将该技能同步到当前工程：
  - `.skills/project-skill-bootstrap`

### 验证
- 后端 clean 启动成功，健康检查通过：`/api/health` 返回 success。
- 前端 `type-check` + `build -- --emptyOutDir` + `preview` 成功，`http://127.0.0.1:6045` 可访问。

### 备注
- `quick_validate.py` 受环境依赖限制未执行成功：缺少 `PyYAML`（`ModuleNotFoundError: No module named 'yaml'`）。
- 已通过目录结构与文件内容人工校验确保技能完整可读。

---

## 追加记录（2026-03-06 12:20:33）- 同步时排除 skill-creator/skill-installer

### 需求
- 同步 skills 到项目 `.skills` 时，不包含 `skill-creator` 与 `skill-installer`。

### 本次改动
- 更新项目规则：`AGENTS.md` 第 8 条，明确复制时排除 `.system/skill-creator` 与 `.system/skill-installer`。
- 更新全局技能 `project-skill-bootstrap`：
  - `SKILL.md` 增加排除规则描述。
  - `scripts/bootstrap_project_skills.sh` 改为 rsync 排除并在同步后强制清理目标目录中的两个技能目录。
- 执行一次引导脚本对当前工程重同步，确认排除生效。

### 结果
- 当前工程目录 `/Users/zhouyu/IdeaProjects/SQL_Copilot/.skills/.system/` 下已不包含：
  - `skill-creator`
  - `skill-installer`

### 验证（clean 启动）
- 后端：`mvn -f apps/server/pom.xml clean spring-boot:run` 启动成功，`/api/health` 返回 success。
- 前端：`npm run -w @sqlcopilot/desktop type-check`、`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`、`npm run -w @sqlcopilot/desktop preview` 均成功，`http://127.0.0.1:6045` 可访问。

---

## 追加记录（2026-03-06 12:28:01）- AGENTS 中文化与中文回复约束

### 需求
- 增加约束：生成的 `AGENTS.md` 使用中文。
- 追加约束：与用户交互时使用中文回复。

### 本次改动
- 更新项目级 `AGENTS.md`：
  - 新增第 11 条：生成或维护 `AGENTS.md` 时默认使用中文。
  - 新增第 12 条：默认使用中文回复，除非用户明确要求其他语言。
  - 将“技能引导规则”块改为中文表述，并补充上述两条约束。
- 更新全局技能 `project-skill-bootstrap`：
  - `SKILL.md` 增加“AGENTS 中文化 + 中文回复优先”要求。
  - `scripts/bootstrap_project_skills.sh` 的规则块输出改为中文，并保留复制时排除 `skill-creator`/`skill-installer`。
- 同步更新到当前工程 `.skills/project-skill-bootstrap`。

### 验证（clean 启动）
- 后端：`mvn -f apps/server/pom.xml clean spring-boot:run` 启动成功，`/api/health` 返回 success。
- 前端：`npm run -w @sqlcopilot/desktop type-check`、`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`、`npm run -w @sqlcopilot/desktop preview` 均成功，`http://127.0.0.1:6045` 可访问。
