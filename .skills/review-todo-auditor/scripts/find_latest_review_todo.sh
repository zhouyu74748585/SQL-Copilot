#!/usr/bin/env bash
set -euo pipefail

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  repo_root="$(git rev-parse --show-toplevel)"
else
  repo_root="$(pwd)"
fi

review_dir="${repo_root}/docs/review"
if [[ ! -d "${review_dir}" ]]; then
  echo "未找到 review 目录: ${review_dir}" >&2
  exit 2
fi

shopt -s nullglob
files=("${review_dir}"/reviewTodo-*.md)
shopt -u nullglob

if [[ ${#files[@]} -eq 0 ]]; then
  echo "未找到待审文件（reviewTodo-*.md）。" >&2
  exit 3
fi

latest_file="$(ls -1t "${files[@]}" | head -n 1)"
echo "${latest_file}"
