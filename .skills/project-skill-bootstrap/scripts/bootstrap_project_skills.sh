#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="${1:-$(pwd)}"
GLOBAL_SKILLS_DIR="${2:-/Users/zhouyu/.codex/skills}"
LOCAL_SKILLS_DIR="${PROJECT_ROOT}/.skills"
AGENTS_FILE="${PROJECT_ROOT}/AGENTS.md"
MARKER_CN='### 技能引导规则（由 project-skill-bootstrap 维护）'
MARKER_EN='### Skill Bootstrap Rules (managed by project-skill-bootstrap)'

if [[ ! -d "${GLOBAL_SKILLS_DIR}" ]]; then
  echo "全局技能目录不存在: ${GLOBAL_SKILLS_DIR}" >&2
  exit 1
fi

mkdir -p "${LOCAL_SKILLS_DIR}"
rsync -a --delete \
  --exclude '.DS_Store' \
  --exclude '.system/skill-creator/***' \
  --exclude '.system/skill-installer/***' \
  "${GLOBAL_SKILLS_DIR}/" "${LOCAL_SKILLS_DIR}/"

# 防止历史残留目录在目标工程中保留。
rm -rf "${LOCAL_SKILLS_DIR}/.system/skill-creator" "${LOCAL_SKILLS_DIR}/.system/skill-installer"

if [[ ! -f "${AGENTS_FILE}" ]]; then
  : > "${AGENTS_FILE}"
fi

if ! grep -Fq "${MARKER_CN}" "${AGENTS_FILE}" && ! grep -Fq "${MARKER_EN}" "${AGENTS_FILE}"; then
  cat >> "${AGENTS_FILE}" <<'RULES'

### 技能引导规则（由 project-skill-bootstrap 维护）
- 优先使用`<project-root>/.skills`中的技能。
- 若新工程缺少`.skills`，则创建并从`/Users/zhouyu/.codex/skills/`复制技能（排除`.system/skill-creator`与`.system/skill-installer`）。
- 若用户显式指定技能（如`$skill-name`）或任务命中技能领域，必须读取对应`.skills/<skill>/SKILL.md`。
- 文本编码默认使用UTF-8。
- 除非用户明确要求，否则不兼容旧数据。
- 生成或维护`AGENTS.md`时默认使用中文。
- 与用户交互时默认使用中文回复，除非用户明确要求其他语言。
RULES
fi

echo "初始化完成"
echo "项目根目录: ${PROJECT_ROOT}"
echo "本地技能目录: ${LOCAL_SKILLS_DIR}"
echo "AGENTS 文件: ${AGENTS_FILE}"
