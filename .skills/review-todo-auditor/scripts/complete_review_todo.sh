#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "用法: $0 --result-file <审查结果md文件> [--todo-file <reviewTodo文件>]" >&2
  exit 1
}

result_file=""
todo_file=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --result-file)
      [[ $# -lt 2 ]] && usage
      result_file="$2"
      shift 2
      ;;
    --todo-file)
      [[ $# -lt 2 ]] && usage
      todo_file="$2"
      shift 2
      ;;
    *)
      usage
      ;;
  esac
done

[[ -z "${result_file}" ]] && usage
if [[ ! -f "${result_file}" ]]; then
  echo "审查结果文件不存在: ${result_file}" >&2
  exit 2
fi
if [[ ! -s "${result_file}" ]]; then
  echo "审查结果文件为空: ${result_file}" >&2
  exit 3
fi

if [[ -z "${todo_file}" ]]; then
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  todo_file="$(${script_dir}/find_latest_review_todo.sh)"
fi

if [[ ! -f "${todo_file}" ]]; then
  echo "待审文件不存在: ${todo_file}" >&2
  exit 4
fi

todo_name="$(basename "${todo_file}")"
if [[ "${todo_name}" != reviewTodo-*.md ]]; then
  echo "待审文件命名不合法（必须是 reviewTodo-*.md）: ${todo_name}" >&2
  exit 5
fi

review_dir="$(dirname "${todo_file}")"
timestamp="$(date +%Y%m%d%H%M%S)"
done_file="${review_dir}/reviewDone-${timestamp}.md"

{
  echo
  echo "## Review结果"
  echo
  cat "${result_file}"
} >> "${todo_file}"

mv "${todo_file}" "${done_file}"
echo "${done_file}"
