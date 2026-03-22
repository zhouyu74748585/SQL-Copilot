import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const CATEGORY_RULES = [
  {
    name: '打包与发布',
    keywords: ['release', 'package', 'packaging', 'build', 'workflow', 'artifact', 'electron', 'graalvm', 'native', '发布', '打包', '构建'],
  },
  {
    name: '导出与结果处理',
    keywords: ['export', 'download', 'csv', 'excel', 'json', 'result', '导出', '下载', '结果'],
  },
  {
    name: '数据库与连接能力',
    keywords: ['mysql', 'oracle', 'postgres', 'sqlserver', 'sqlite', 'jdbc', 'schema', 'connection', 'database', 'sql', 'explain', '连接', '数据库', '表', '视图', '函数'],
  },
  {
    name: 'AI 与检索',
    keywords: ['ai', 'rag', 'vector', 'embedding', 'rerank', 'token', 'llm', 'onnx', 'agent', 'query chat', '向量', '模型', '检索', '记忆'],
  },
  {
    name: '界面与交互',
    keywords: ['ui', 'ux', 'layout', 'icon', 'theme', 'tab', 'dialog', 'titlebar', 'overlay', 'chart', 'canvas', 'editor', 'browser', 'pane', 'toolbar', 'tooltip', '前端', '界面', '图标', '交互', '布局', '主题'],
  },
  {
    name: '文档与工程',
    keywords: ['readme', 'doc', 'docs', 'license', 'prd', 'ci', '说明', '文档', '架构'],
  },
];

function parseArgs(argv) {
  const options = {
    output: '',
    previousTag: '',
    currentTag: '',
    currentSha: '',
  };

  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (token === '--output') {
      options.output = argv[index + 1] || '';
      index += 1;
      continue;
    }
    if (token === '--previous-tag') {
      options.previousTag = argv[index + 1] || '';
      index += 1;
      continue;
    }
    if (token === '--current-tag') {
      options.currentTag = argv[index + 1] || '';
      index += 1;
      continue;
    }
    if (token === '--current-sha') {
      options.currentSha = argv[index + 1] || '';
      index += 1;
    }
  }

  if (!options.output) {
    throw new Error('Missing required --output');
  }
  if (!options.currentSha) {
    throw new Error('Missing required --current-sha');
  }
  return options;
}

function runGit(args, { allowFailure = false } = {}) {
  const result = spawnSync('git', args, {
    encoding: 'utf8',
    stdio: 'pipe',
  });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0 && !allowFailure) {
    const detail = [result.stdout, result.stderr].filter(Boolean).join('\n').trim();
    throw new Error(detail || `git ${args.join(' ')} failed with exit code ${result.status}`);
  }
  return (result.stdout || '').trim();
}

function refExists(refName) {
  if (!refName) {
    return false;
  }
  const result = spawnSync('git', ['rev-parse', '--verify', '--quiet', refName], {
    encoding: 'utf8',
    stdio: 'pipe',
  });
  return result.status === 0;
}

function sanitizeSubject(subject) {
  return subject
    .replace(/^(feat|fix|docs|style|refactor|test|build|ci|chore|perf|revert)(\([^)]+\))?:\s*/i, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function classifySubject(subject) {
  const lower = subject.toLowerCase();
  const matched = CATEGORY_RULES.find((rule) => rule.keywords.some((keyword) => lower.includes(keyword.toLowerCase())));
  return matched?.name || '其他改进';
}

function collectCommits(previousTag, currentSha) {
  const rangeArgs = ['log', '--no-merges', '--format=%H%x1f%s%x1e'];
  if (previousTag && refExists(previousTag)) {
    rangeArgs.push(`${previousTag}..${currentSha}`);
  } else {
    rangeArgs.push(currentSha);
  }

  const output = runGit(rangeArgs);
  return output
    .split('\x1e')
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const [hash, rawSubject] = entry.split('\x1f');
      return {
        hash,
        subject: sanitizeSubject(rawSubject || ''),
      };
    })
    .filter((commit) => commit.subject && !/^merge\b/i.test(commit.subject));
}

function buildSummarySections(commits) {
  const grouped = new Map();

  for (const commit of commits) {
    const category = classifySubject(commit.subject);
    if (!grouped.has(category)) {
      grouped.set(category, []);
    }
    const entries = grouped.get(category);
    if (!entries.includes(commit.subject)) {
      entries.push(commit.subject);
    }
  }

  return [...grouped.entries()]
    .sort((left, right) => right[1].length - left[1].length)
    .slice(0, 5);
}

function buildCompareLink(previousTag, currentRef) {
  const serverUrl = process.env.GITHUB_SERVER_URL || '';
  const repository = process.env.GITHUB_REPOSITORY || '';
  if (!serverUrl || !repository || !previousTag || !currentRef) {
    return '';
  }
  return `${serverUrl}/${repository}/compare/${previousTag}...${currentRef}`;
}

function buildReleaseNotes({ previousTag, currentTag, currentSha, commits }) {
  const currentRef = currentTag || currentSha;
  const shortSha = currentSha.slice(0, 7);
  const sections = buildSummarySections(commits);
  const compareLink = buildCompareLink(previousTag, currentRef);
  const lines = ['# SQL Copilot Release Notes', ''];

  lines.push('## 主要内容');
  if (sections.length === 0) {
    lines.push('- 本次发布没有检测到上个 Release 之后的新增提交，主要用于重新打包并发布当前 Commit 的构建产物。');
  } else {
    for (const [category, subjects] of sections) {
      lines.push(`- ${category}：${subjects.slice(0, 3).join('；')}`);
    }
    const summarizedCount = sections.reduce((count, [, subjects]) => count + Math.min(subjects.length, 3), 0);
    const remaining = commits.length - summarizedCount;
    if (remaining > 0) {
      lines.push(`- 补充说明：另外包含 ${remaining} 个提交的细节修复与工程调整。`);
    }
  }

  lines.push('', '## 提交范围');
  if (previousTag) {
    lines.push(`- 上个 Release：\`${previousTag}\``);
  } else {
    lines.push('- 上个 Release：无，按当前仓库历史生成首次发布摘要。');
  }
  lines.push(`- 当前 Tag：\`${currentTag || '未创建标签'}\``);
  lines.push(`- 当前 Commit：\`${shortSha}\``);
  lines.push(`- 纳入提交：${commits.length} 个`);

  if (compareLink) {
    lines.push(`- 完整对比：[${previousTag}...${currentRef}](${compareLink})`);
  }

  return `${lines.join('\n')}\n`;
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const currentSha = runGit(['rev-parse', options.currentSha]);
  const commits = collectCommits(options.previousTag, currentSha);
  const content = buildReleaseNotes({
    previousTag: options.previousTag,
    currentTag: options.currentTag,
    currentSha,
    commits,
  });

  fs.mkdirSync(path.dirname(options.output), { recursive: true });
  fs.writeFileSync(options.output, content, 'utf8');
}

main();
