#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "用法: $0 \"需求描述\"" >&2
  exit 1
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "当前目录不是 Git 仓库，无法生成 review 记录。" >&2
  exit 1
fi

requirement_desc="$1"
repo_root="$(git rev-parse --show-toplevel)"
changes="$(git -C "${repo_root}" status --short --untracked-files=all)"

if [[ -z "${changes}" ]]; then
  echo "未检测到文件变更，跳过生成 review 记录。"
  exit 0
fi

is_code_file() {
  local file_path="$1"
  case "${file_path}" in
    *.java|*.kt|*.kts|*.groovy|*.scala|*.py|*.js|*.jsx|*.mjs|*.cjs|*.ts|*.tsx|*.vue|*.go|*.rs|*.c|*.cc|*.cpp|*.cxx|*.h|*.hpp|*.hh|*.cs|*.php|*.rb|*.swift|*.m|*.mm|*.sh|*.bash|*.zsh|*.ps1|*.sql)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

mkdir -p "${repo_root}/docs/review"
timestamp="$(date +%Y%m%d%H%M%S)"
output_file="${repo_root}/docs/review/reviewTodo-${timestamp}.md"

extract_new_ranges() {
  awk '
    /^@@ / {
      line = $0
      sub(/^@@ -/, "", line)
      split(line, parts, " ")
      new_part = parts[2]
      sub(/^\+/, "", new_part)
      split(new_part, nums, ",")
      start = nums[1] + 0
      count = (nums[2] == "" ? 1 : nums[2] + 0)
      if (count > 0) {
        end = start + count - 1
        item = (count == 1 ? start : start "-" end)
        if (out != "") out = out ","
        out = out item
      }
    }
    END { print out }
  '
}

extract_old_ranges() {
  awk '
    /^@@ / {
      line = $0
      sub(/^@@ -/, "", line)
      split(line, parts, " ")
      old_part = parts[1]
      split(old_part, nums, ",")
      start = nums[1] + 0
      count = (nums[2] == "" ? 1 : nums[2] + 0)
      if (count > 0) {
        end = start + count - 1
        item = (count == 1 ? start : start "-" end)
        if (out != "") out = out ","
        out = out item
      }
    }
    END { print out }
  '
}

merge_ranges() {
  local first="${1:-}"
  local second="${2:-}"
  printf "%s,%s\n" "${first}" "${second}" | awk -F',' '
    {
      for (i = 1; i <= NF; i++) {
        gsub(/^[ \t]+|[ \t]+$/, "", $i)
        if ($i != "" && !seen[$i]++) {
          if (out != "") out = out ","
          out = out $i
        }
      }
    }
    END { print out }
  '
}

build_line_desc() {
  local file_path="$1"
  local status="$2"
  local new_ranges=""
  local old_ranges=""

  if [[ "${status}" == "??" ]]; then
    local abs_path="${repo_root}/${file_path}"
    if [[ -f "${abs_path}" ]]; then
      local line_count
      line_count="$(wc -l < "${abs_path}" | tr -d ' ')"
      if [[ "${line_count}" -le 1 ]]; then
        echo "1"
      else
        echo "1-${line_count}"
      fi
    else
      echo "无可定位行号"
    fi
    return
  fi

  local unstaged_new
  local staged_new
  local unstaged_old
  local staged_old

  unstaged_new="$(git -C "${repo_root}" diff --unified=0 --no-color -- "${file_path}" | extract_new_ranges)"
  staged_new="$(git -C "${repo_root}" diff --cached --unified=0 --no-color -- "${file_path}" | extract_new_ranges)"
  new_ranges="$(merge_ranges "${unstaged_new}" "${staged_new}")"

  unstaged_old="$(git -C "${repo_root}" diff --unified=0 --no-color -- "${file_path}" | extract_old_ranges)"
  staged_old="$(git -C "${repo_root}" diff --cached --unified=0 --no-color -- "${file_path}" | extract_old_ranges)"
  old_ranges="$(merge_ranges "${unstaged_old}" "${staged_old}")"

  if [[ -n "${new_ranges}" && -n "${old_ranges}" ]]; then
    echo "新行 ${new_ranges}; 旧行 ${old_ranges}"
  elif [[ -n "${new_ranges}" ]]; then
    echo "${new_ranges}"
  elif [[ -n "${old_ranges}" ]]; then
    echo "旧行 ${old_ranges}"
  else
    echo "无可定位行号"
  fi
}

collect_code_changes() {
  while IFS= read -r line; do
    [[ -z "${line}" ]] && continue
    status="$(echo "${line:0:2}" | tr -d ' ')"
    path="${line:3}"
    file_path="${path}"
    if [[ -z "${status}" ]]; then
      status="?"
    fi
    if [[ "${status}" == R* && "${path}" == *" -> "* ]]; then
      file_path="${path##* -> }"
    fi
    if is_code_file "${file_path}"; then
      printf '%s\n' "${line}"
    fi
  done <<< "${changes}"
}

code_changes="$(collect_code_changes)"
if [[ -z "${code_changes}" ]]; then
  echo "仅检测到非代码文件变更，跳过生成 review 记录。"
  exit 0
fi

{
  echo "# 任务信息"
  echo
  echo "## 需求描述"
  echo "${requirement_desc}"
  echo
  echo "## 变动文件"
  while IFS= read -r line; do
    [[ -z "${line}" ]] && continue
    status="$(echo "${line:0:2}" | tr -d ' ')"
    path="${line:3}"
    file_path="${path}"
    if [[ -z "${status}" ]]; then
      status="?"
    fi
    if [[ "${status}" == R* && "${path}" == *" -> "* ]]; then
      file_path="${path##* -> }"
    fi
    line_desc="$(build_line_desc "${file_path}" "${status}")"
    echo "- [${status}] ${path} (行号: ${line_desc})"
  done <<< "${code_changes}"
} > "${output_file}"

echo "${output_file}"
