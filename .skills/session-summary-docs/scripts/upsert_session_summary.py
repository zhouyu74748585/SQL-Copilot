#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import difflib
import pathlib
import re
import sys
from typing import Optional


FILENAME_PATTERN = re.compile(r"^(\d{14})-(.+)\.md$")


def normalize_topic(text: str) -> str:
    lowered = text.strip().lower()
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", lowered)


def sanitize_for_filename(topic: str) -> str:
    cleaned = topic.strip()
    cleaned = cleaned.replace("/", "-").replace("\\", "-")
    cleaned = re.sub(r"\s+", "-", cleaned)
    cleaned = re.sub(r"-{2,}", "-", cleaned)
    cleaned = cleaned.strip("-")
    return cleaned or "session-summary"


def stem_token(token: str) -> str:
    if len(token) > 5 and token.endswith("ing"):
        return token[:-3]
    if len(token) > 4 and token.endswith("es"):
        return token[:-2]
    if len(token) > 3 and token.endswith("s"):
        return token[:-1]
    if len(token) > 4 and token.endswith("ed"):
        return token[:-2]
    return token


def tokenize_topic(topic: str) -> list[str]:
    raw = re.split(r"[^0-9a-z\u4e00-\u9fff]+", topic.strip().lower())
    return [stem_token(part) for part in raw if part]


def score_similarity(new_topic: str, existing_topic: str) -> float:
    a = normalize_topic(new_topic)
    b = normalize_topic(existing_topic)
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    if a in b or b in a:
        return 0.9
    seq_score = difflib.SequenceMatcher(a=a, b=b).ratio()

    tokens_a = tokenize_topic(new_topic)
    tokens_b = tokenize_topic(existing_topic)
    if not tokens_a or not tokens_b:
        return seq_score
    set_a = set(tokens_a)
    set_b = set(tokens_b)
    intersection = len(set_a & set_b)
    union = len(set_a | set_b)
    jaccard = intersection / union if union else 0.0
    cover = intersection / min(len(set_a), len(set_b))

    if intersection >= 2 and cover >= 0.66:
        return max(seq_score, 0.82)
    return max(seq_score, jaccard)


def find_similar_file(docs_dir: pathlib.Path, topic: str) -> Optional[pathlib.Path]:
    best_path: Optional[pathlib.Path] = None
    best_score = 0.0
    for path in sorted(docs_dir.glob("*.md")):
        matched = FILENAME_PATTERN.match(path.name)
        if not matched:
            continue
        existing_topic = matched.group(2)
        score = score_similarity(topic, existing_topic)
        if score > best_score:
            best_score = score
            best_path = path
    if best_path is not None and best_score >= 0.72:
        return best_path
    return None


def read_content(content_file: Optional[str]) -> str:
    if content_file:
        data = pathlib.Path(content_file).read_text(encoding="utf-8")
    else:
        data = sys.stdin.read()
    text = data.strip()
    if not text:
        raise ValueError("summary content is empty")
    return text


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Create or append session summary in docs directory."
    )
    parser.add_argument("--topic", required=True, help="Summary topic suffix")
    parser.add_argument("--docs-dir", default="docs", help="Target docs directory")
    parser.add_argument(
        "--content-file",
        default=None,
        help="Optional markdown file as summary source. Default: stdin",
    )
    args = parser.parse_args()

    topic = args.topic.strip()
    if not topic:
        raise ValueError("topic must not be empty")

    content = read_content(args.content_file)
    docs_dir = pathlib.Path(args.docs_dir).resolve()
    docs_dir.mkdir(parents=True, exist_ok=True)

    now = dt.datetime.now()
    stamp = now.strftime("%Y%m%d%H%M%S")
    pretty_time = now.strftime("%Y-%m-%d %H:%M:%S")

    target = find_similar_file(docs_dir, topic)
    if target is None:
        filename_topic = sanitize_for_filename(topic)
        target = docs_dir / f"{stamp}-{filename_topic}.md"
        body = (
            f"# 主题：{topic}\n\n"
            f"## 记录\n\n"
            f"### {pretty_time}\n\n"
            f"{content}\n"
        )
        target.write_text(body, encoding="utf-8")
        status = "created"
    else:
        append_block = f"\n\n### {pretty_time}\n\n{content}\n"
        with target.open("a", encoding="utf-8") as f:
            f.write(append_block)
        status = "appended"

    print(f"status={status} path={target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
