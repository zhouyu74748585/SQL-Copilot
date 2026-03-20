const path = require('path');
const { _electron: electron } = require('playwright');
const {
  ROOT,
  DESKTOP_DIR,
  BACKEND_URL,
  PREVIEW_URL,
  parseArgs,
  ensureDir,
  writeJson,
  writeText,
  isBlockedError,
  relativeFromRoot,
  sleep,
} = require('./lib/common.cjs');

const args = parseArgs(process.argv.slice(2));
const round = Number.parseInt(args.round || '1', 10);
const outputDir = path.resolve(args['output-dir'] || path.join(ROOT, 'output', 'playwright', `round-${round}`));
const screenshotsDir = path.join(outputDir, 'screenshots');
const ELECTRON_BINARY = require(require.resolve('electron', { paths: [DESKTOP_DIR] }));

const IGNORE_EXACT = new Set([
  '测试',
  '本地redis',
  '新建连接',
  '本地SqlServer',
  'Local SQLite Demo',
  'Order Overview',
  'AOV',
  'Global',
  'test',
]);

const IGNORE_CONTAINS = [
  'session-demo-sqlite-001',
  'SO-202603',
  'Blue Ocean Tech',
  'Data Warehouse Lab',
  'Operations Center',
  'Hangzhou',
  'Shanghai',
  'Chengdu',
  'OpenAI · gpt-5.4',
  'OpenAI API',
  'Local SQLite Demo',
  'Order Overview',
  'v_order_summary',
  'v_recent_orders',
  'customers',
  'orders',
  'order_items',
  'order_no',
  'customer_name',
  'total_amount',
  'customer_id',
  'order_date',
  'status',
  'SQLITE',
  'REDIS',
  'SQLSERVER',
  'MYSQL',
  'DEV',
  'Global',
  'AOV',
  'City sales in last 30 days',
  'Aggregate city-level sales for the last 30 days.',
];

function getLaunchEnv() {
  return {
    ...process.env,
    ELECTRON_RENDERER_URL: PREVIEW_URL,
    SQLCOPILOT_BACKEND_URL: BACKEND_URL,
    SQLCOPILOT_DATA_DIR: ROOT,
    NO_PROXY: 'localhost,127.0.0.1',
  };
}

function issueKeyForScenario(scenarioId) {
  return `acceptance:${scenarioId}`;
}

function makeResult({ module, scenario, priority, status, summary, evidence = [], issueKey = '', fixedInRound = null }) {
  return {
    round,
    module,
    scenario,
    priority,
    status,
    summary,
    evidence,
    issueKey,
    fixedInRound,
  };
}

function hasCjk(text) {
  return /[\u4e00-\u9fff]/.test(text);
}

function shouldIgnoreI18nHit(text) {
  if (!text) {
    return true;
  }
  const normalized = text.trim();
  if (!normalized) {
    return true;
  }
  if (IGNORE_EXACT.has(normalized)) {
    return true;
  }
  if (/^\d+[\d\s:/.-]*$/.test(normalized)) {
    return true;
  }
  if (/^[A-Za-z0-9_./:\- ()]+$/.test(normalized) && !hasCjk(normalized)) {
    return true;
  }
  return IGNORE_CONTAINS.some((part) => normalized.includes(part));
}

function resolveShellInPage() {
  const root = document.querySelector('#app');
  const studioRoot = document.querySelector('.studio-root');
  return root?.__vue_app__?._instance?.subTree?.component?.setupState
    || root?.__vue_app__?._instance?.proxy
    || root?.__vue_app__?._container?._vnode?.component?.setupState
    || root?.__vue_app__?._container?._vnode?.component?.proxy
    || studioRoot?.__vueParentComponent?.setupState
    || studioRoot?.__vueParentComponent?.proxy
    || studioRoot?.__vueParentComponent?.ctx
    || root?.__vueParentComponent?.setupState
    || root?.__vueParentComponent?.proxy
    || root?.firstElementChild?.__vueParentComponent?.setupState
    || root?.firstElementChild?.__vueParentComponent?.proxy
    || null;
}

async function launchWindow() {
  const electronApp = await electron.launch({
    executablePath: ELECTRON_BINARY,
    args: [DESKTOP_DIR],
    cwd: DESKTOP_DIR,
    env: getLaunchEnv(),
  });
  const window = await electronApp.firstWindow({ timeout: 120_000 });
  await window.waitForLoadState('domcontentloaded');
  await window.waitForTimeout(4_500);
  await waitForShellReady(window);
  await window.evaluate(() => {
    window.__QA_getShell = () => {
      const root = document.querySelector('#app');
      const studioRoot = document.querySelector('.studio-root');
      return root?.__vue_app__?._instance?.subTree?.component?.setupState
        || root?.__vue_app__?._instance?.proxy
        || root?.__vue_app__?._container?._vnode?.component?.setupState
        || root?.__vue_app__?._container?._vnode?.component?.proxy
        || studioRoot?.__vueParentComponent?.setupState
        || studioRoot?.__vueParentComponent?.proxy
        || studioRoot?.__vueParentComponent?.ctx
        || root?.__vueParentComponent?.setupState
        || root?.__vueParentComponent?.proxy
        || root?.firstElementChild?.__vueParentComponent?.setupState
        || root?.firstElementChild?.__vueParentComponent?.proxy
        || null;
    };
  });
  return { electronApp, window };
}

async function waitForShellReady(window) {
  const startedAt = Date.now();
  let lastSnapshot = null;
  while (Date.now() - startedAt < 60_000) {
    const ready = await window.evaluate(() => {
      const root = document.querySelector('#app');
      const studioRoot = document.querySelector('.studio-root');
      const shell = root?.__vue_app__?._instance?.subTree?.component?.setupState
        || root?.__vue_app__?._instance?.proxy
        || root?.__vue_app__?._container?._vnode?.component?.setupState
        || root?.__vue_app__?._container?._vnode?.component?.proxy
        || studioRoot?.__vueParentComponent?.setupState
        || studioRoot?.__vueParentComponent?.proxy
        || studioRoot?.__vueParentComponent?.ctx
        || root?.__vueParentComponent?.setupState
        || root?.__vueParentComponent?.proxy
        || root?.firstElementChild?.__vueParentComponent?.setupState
        || root?.firstElementChild?.__vueParentComponent?.proxy
        || null;
      return {
        ready: Boolean(shell),
        href: window.location.href,
        title: document.title,
        hasRoot: Boolean(root),
        hasVue: Boolean(root && root.__vue_app__),
        hasInstance: Boolean(root && root.__vue_app__ && root.__vue_app__._instance),
        hasContainerVNode: Boolean(root?.__vue_app__?._container?._vnode),
        hasContainerComponent: Boolean(root?.__vue_app__?._container?._vnode?.component),
        hasSubTree: Boolean(root && root.__vue_app__ && root.__vue_app__._instance && root.__vue_app__._instance.subTree),
        hasSubTreeComponent: Boolean(root && root.__vue_app__ && root.__vue_app__._instance && root.__vue_app__._instance.subTree && root.__vue_app__._instance.subTree.component),
        subTreeType: root?.__vue_app__?._instance?.subTree?.type?.name || root?.__vue_app__?._instance?.subTree?.type || '',
        childCount: Array.isArray(root?.__vue_app__?._instance?.subTree?.children) ? root.__vue_app__._instance.subTree.children.length : 0,
        firstChildHasComponent: Boolean(root?.__vue_app__?._instance?.subTree?.children?.[0]?.component),
        firstChildType: root?.__vue_app__?._instance?.subTree?.children?.[0]?.type?.name || root?.__vue_app__?._instance?.subTree?.children?.[0]?.type || '',
        bodySample: (document.body?.innerText || '').slice(0, 200),
      };
    }).catch(() => ({
      ready: false,
      href: '',
      title: '',
      hasRoot: false,
      hasVue: false,
      bodySample: '',
    }));
    lastSnapshot = ready;
    if (ready) {
      if (ready.ready) {
        return;
      }
    }
    await sleep(300);
  }
  throw new Error(`Electron shell was not ready within 60 seconds. Snapshot=${JSON.stringify(lastSnapshot)}`);
}

async function captureScreenshot(window, fileName) {
  const targetPath = path.join(screenshotsDir, fileName);
  await window.screenshot({ path: targetPath, fullPage: true });
  return relativeFromRoot(targetPath);
}

async function resolveRuntimeContext(window) {
  const connections = await nodeApiGet('/api/connection/list');
  const byDbType = (dbType) => connections.find((item) => item.dbType === dbType) || null;
  const sqliteConnection = connections.find((item) => item.name === 'Local SQLite Demo') || byDbType('SQLITE');
  return {
    sqliteConnectionId: sqliteConnection?.id || 0,
    sqliteDatabaseName: sqliteConnection?.databaseName || '',
    redisConnectionId: byDbType('REDIS')?.id || 0,
    mysqlConnectionId: byDbType('MYSQL')?.id || 0,
    sqlServerConnectionId: byDbType('SQLSERVER')?.id || 0,
  };
}

async function resetWorkspace(window, context, options = {}) {
  const locale = options.locale || 'zh-CN';
  const dark = options.dark === true;
  await window.evaluate(({ locale, dark }) => {
    window.localStorage.setItem('sqlcopilot.ui.locale', locale);
    window.localStorage.setItem('sqlcopilot.ui-theme.v1', dark ? 'dark' : 'light');
  }, {
    locale,
    dark,
  });
  await window.reload({ waitUntil: 'domcontentloaded' });
  await window.waitForTimeout(1500);
}

async function snapshotStrings(window) {
  const pieces = await window.evaluate(() => {
    const results = [];
    const push = (value, source) => {
      if (!value) {
        return;
      }
      const text = String(value).replace(/\s+/g, ' ').trim();
      if (!text) {
        return;
      }
      results.push({ text, source });
    };

    push(document.body?.innerText || '', 'body');
    document.querySelectorAll('button, [role="button"], .ant-btn, .workspace-tab, .tool-item').forEach((node) => {
      push(node.innerText || node.textContent || '', 'button');
    });
    document.querySelectorAll('[title], [aria-label], input[placeholder], textarea[placeholder]').forEach((node) => {
      push(node.getAttribute('title'), 'title');
      push(node.getAttribute('aria-label'), 'aria');
      push(node.getAttribute('placeholder'), 'placeholder');
    });
    return results;
  });

  const hits = [];
  const seen = new Set();
  pieces.forEach((item) => {
    String(item.text).split(/\n+/).map((line) => line.trim()).filter(Boolean).forEach((line) => {
      if (!hasCjk(line) || shouldIgnoreI18nHit(line)) {
        return;
      }
      const key = `${item.source}:${line}`;
      if (seen.has(key)) {
        return;
      }
      seen.add(key);
      hits.push({ source: item.source, text: line });
    });
  });
  return hits;
}

async function nodeApiGet(requestPath) {
  const response = await fetch(`${BACKEND_URL}${requestPath}`);
  const json = await response.json();
  if (!response.ok || json.code !== 0) {
    throw new Error(json.message || `HTTP ${response.status}`);
  }
  return json.data;
}

async function nodeApiPost(requestPath, payload) {
  const response = await fetch(`${BACKEND_URL}${requestPath}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const json = await response.json();
  if (!response.ok || json.code !== 0) {
    throw new Error(json.message || `HTTP ${response.status}`);
  }
  return json.data;
}

function nextSessionId(name) {
  return `qa-${name}-${round}-${Date.now()}`;
}

async function executeSql(connectionId, databaseName, sqlText, options = {}) {
  return nodeApiPost('/api/sql/execute', {
    connectionId,
    sessionId: options.sessionId || nextSessionId('execute'),
    sqlText,
    databaseName,
    maxRows: options.maxRows ?? 200,
    memoryEnabled: false,
    operatorName: 'acceptance-bot',
  });
}

function firstExecutableStatement(sqlText) {
  let normalized = String(sqlText || '').trim();
  const fenced = normalized.match(/```(?:sql)?\s*([\s\S]*?)```/i);
  if (fenced && fenced[1]) {
    normalized = fenced[1].trim();
  }
  normalized = normalized.replace(/^sql\s*[:：]\s*/i, '').trim();
  const keywordStart = normalized.match(/\b(select|with|insert|update|delete)\b[\s\S]*$/i);
  if (keywordStart) {
    normalized = keywordStart[0].trim();
  }
  return normalized
    .split(';')
    .map((item) => item.trim())
    .find((item) => !!item) || '';
}

async function loadTablePage(connectionId, databaseName, tableName, options = {}) {
  return nodeApiPost('/api/schema/table/data/page', {
    connectionId,
    databaseName,
    tableName,
    objectType: options.objectType || 'tables',
    pageNo: options.pageNo || 1,
    pageSize: options.pageSize || 100,
    filters: options.filters || [],
    sorts: options.sorts || [],
  });
}

function blockedOrFail(message, moduleName, scenarioId, priority, evidence) {
  const status = isBlockedError(message) ? 'BLOCKED' : 'FAIL';
  return makeResult({
    module: moduleName,
    scenario: scenarioId,
    priority,
    status,
    summary: message,
    evidence,
    issueKey: status === 'FAIL' ? issueKeyForScenario(scenarioId) : '',
  });
}

async function runScenario(window, context, def) {
  const evidence = [];
  try {
    await resetWorkspace(window, context, def.resetOptions);
    const payload = await def.run(window, context);
    if (payload.screenshotName) {
      evidence.push(await captureScreenshot(window, payload.screenshotName));
    }
    if (Array.isArray(payload.extraEvidence)) {
      evidence.push(...payload.extraEvidence);
    }
    return makeResult({
      module: def.module,
      scenario: def.id,
      priority: def.priority,
      status: payload.status || 'PASS',
      summary: payload.summary,
      evidence,
      issueKey: payload.status === 'FAIL' ? issueKeyForScenario(def.id) : '',
    });
  } catch (error) {
    evidence.push(await captureScreenshot(window, `${def.id}-failed.png`).catch(() => ''));
    return blockedOrFail(
      error instanceof Error ? error.message : String(error),
      def.module,
      def.id,
      def.priority,
      evidence.filter(Boolean),
    );
  }
}

async function scenarioStartup(window) {
  const info = await window.evaluate(() => {
    return {
      locale: window.localStorage.getItem('sqlcopilot.ui.locale') || 'zh-CN',
      theme: window.localStorage.getItem('sqlcopilot.ui-theme.v1') || 'light',
      bodySample: (document.body?.innerText || '').slice(0, 120),
    };
  });
  if (!info.bodySample.includes('对象浏览')) {
    throw new Error('Electron 主窗口已打开，但未渲染出工作台主体。');
  }
  return {
    summary: `Electron 主窗口已加载，当前 locale=${info.locale}，theme=${info.theme}。`,
    screenshotName: 'startup-electron-shell.png',
  };
}

async function scenarioSqliteBrowser(window, context) {
  const tableNames = await nodeApiGet(`/api/schema/objectNames?connectionId=${context.sqliteConnectionId}&databaseName=${encodeURIComponent(context.sqliteDatabaseName)}&objectType=tables`);
  const viewNames = await nodeApiGet(`/api/schema/objectNames?connectionId=${context.sqliteConnectionId}&databaseName=${encodeURIComponent(context.sqliteDatabaseName)}&objectType=views`);
  const queryTitles = await nodeApiGet(`/api/editor/saved-query/list?connectionId=${context.sqliteConnectionId}&databaseName=${encodeURIComponent(context.sqliteDatabaseName)}`);
  const detail = await nodeApiGet(`/api/schema/tableDetail?connectionId=${context.sqliteConnectionId}&databaseName=${encodeURIComponent(context.sqliteDatabaseName)}&tableName=customers`);

  if (!tableNames.includes('customers') || !tableNames.includes('orders')) {
    throw new Error(`SQLite 对象浏览缺少预期表：${tableNames.join(', ')}`);
  }
  if (!viewNames.includes('v_order_summary')) {
    throw new Error(`SQLite 对象浏览缺少预期视图：${viewNames.join(', ')}`);
  }
  if (!queryTitles.length) {
    throw new Error('SQLite 保存查询列表为空，未能满足浏览验收基线。');
  }
  if ((detail.columns || []).length < 4) {
    throw new Error(`customers 详情字段数异常：${(detail.columns || []).length}`);
  }
  return {
    summary: `SQLite 对象浏览通过：表 ${tableNames.length} 个，视图 ${viewNames.length} 个，保存查询 ${queryTitles.length} 个。`,
    screenshotName: 'sqlite-browser-detail.png',
  };
}

async function scenarioSqliteQuery(window, context) {
  const queryTitle = `QA Acceptance Round ${round} Saved Query ${Date.now()}`;
  const sqlText = 'SELECT order_no, customer_name, status, order_date, total_amount FROM v_order_summary ORDER BY order_date DESC;';
  const executeResult = await executeSql(context.sqliteConnectionId, context.sqliteDatabaseName, sqlText, {
    sessionId: nextSessionId('saved-query'),
  });
  const saved = await nodeApiPost('/api/editor/saved-query/save', {
    connectionId: context.sqliteConnectionId,
    databaseName: context.sqliteDatabaseName,
    title: queryTitle,
    sqlText,
  });
  const queryTitles = await nodeApiGet(`/api/editor/saved-query/list?connectionId=${context.sqliteConnectionId}&databaseName=${encodeURIComponent(context.sqliteDatabaseName)}`);

  if (!saved?.id) {
    throw new Error('保存查询未返回有效 ID。');
  }
  if ((executeResult.rows || []).length < 1) {
    throw new Error('执行查询后未返回结果行。');
  }
  if (!queryTitles.some((item) => item.title === queryTitle && item.sqlText === sqlText)) {
    throw new Error(`保存查询未出现在对象浏览列表中：${queryTitle}`);
  }
  return {
    summary: `查询工作台通过：执行返回 ${(executeResult.rows || []).length} 行，保存查询并成功持久化。`,
    screenshotName: 'sqlite-query-save-reopen.png',
  };
}

function classifyAiPayload(result, successSummary, blockedLabel) {
  if (result.generatedSql && result.rowCount >= 1) {
    return {
      status: 'PASS',
      summary: successSummary(result),
    };
  }
  if (isBlockedError(result.errorText || result.assistantContent || result.generatedSql)) {
    return {
      status: 'BLOCKED',
      summary: `${blockedLabel}：${result.errorText || result.assistantContent || '环境未返回有效结果。'}`,
    };
  }
  return {
    status: 'FAIL',
    summary: result.errorText || result.assistantContent || '未生成有效结果。',
  };
}

async function scenarioAiGenerate(window, context) {
  const result = await nodeApiPost('/api/ai/query/generate', {
    connectionId: context.sqliteConnectionId,
    sessionId: nextSessionId('ai-generate'),
    prompt: '基于订单汇总视图，列出最近 3 条订单的订单号、客户名、订单日期和总金额，按订单日期倒序排序。',
    databaseName: context.sqliteDatabaseName,
  });
  const generatedSql = firstExecutableStatement(result.sqlText || '');
  let executedRows = 0;
  if (generatedSql) {
    const executeResult = await executeSql(context.sqliteConnectionId, context.sqliteDatabaseName, generatedSql, {
      sessionId: nextSessionId('ai-generated-sql'),
    });
    executedRows = (executeResult.rows || []).length;
  }

  const classified = classifyAiPayload(
    {
      generatedSql,
      assistantContent: result.reasoning || '',
      errorText: '',
      rowCount: executedRows,
      tokenEstimate: result.totalTokens || 0,
    },
    (payload) => `AI 生成 SQL 并成功执行，返回 ${payload.rowCount} 行，累计 Token ${payload.tokenEstimate}。`,
    'AI 生成链路被环境阻塞',
  );
  return {
    status: classified.status,
    summary: classified.summary,
    screenshotName: `sqlite-ai-generate-${classified.status.toLowerCase()}.png`,
  };
}

async function scenarioAiExplain(window, context) {
  const sqlText = 'SELECT order_no, customer_name, status FROM v_order_summary ORDER BY order_date DESC LIMIT 3;';
  const result = await nodeApiPost('/api/ai/query/explain', {
    connectionId: context.sqliteConnectionId,
    sessionId: nextSessionId('ai-explain'),
    prompt: `请解释这段 SQL 的作用：\n${sqlText}`,
    databaseName: context.sqliteDatabaseName,
  });

  if (result.content) {
    return {
      summary: `AI Explain 成功返回说明，内容长度 ${result.content.length}。`,
      screenshotName: 'sqlite-ai-explain-pass.png',
    };
  }
  if (isBlockedError(result.errorText)) {
    return {
      status: 'BLOCKED',
      summary: `AI Explain 被环境阻塞：${result.errorText}`,
      screenshotName: 'sqlite-ai-explain-blocked.png',
    };
  }
  throw new Error(result.errorText || 'AI Explain 未返回内容。');
}

async function scenarioAiAnalyze(window, context) {
  const sqlText = 'SELECT order_no, customer_name, total_amount FROM v_order_summary ORDER BY order_date DESC LIMIT 3;';
  const result = await nodeApiPost('/api/ai/query/analyze', {
    connectionId: context.sqliteConnectionId,
    sessionId: nextSessionId('ai-analyze'),
    prompt: `请分析这段 SQL 是否合理，并指出潜在问题：\n${sqlText}`,
    databaseName: context.sqliteDatabaseName,
  });

  if (result.content) {
    return {
      summary: `AI Analyze 成功返回分析，内容长度 ${result.content.length}。`,
      screenshotName: 'sqlite-ai-analyze-pass.png',
    };
  }
  if (isBlockedError(result.errorText)) {
    return {
      status: 'BLOCKED',
      summary: `AI Analyze 被环境阻塞：${result.errorText}`,
      screenshotName: 'sqlite-ai-analyze-blocked.png',
    };
  }
  throw new Error(result.errorText || 'AI Analyze 未返回内容。');
}

async function scenarioAiRepair(window, context) {
  let failureMessage = '';
  try {
    await executeSql(context.sqliteConnectionId, context.sqliteDatabaseName, 'SELECT missing_column FROM orders LIMIT 1;', {
      sessionId: nextSessionId('ai-repair-bad-sql'),
    });
  } catch (error) {
    failureMessage = error.message;
  }
  const result = await nodeApiPost('/api/ai/query/repair', {
    connectionId: context.sqliteConnectionId,
    sessionId: nextSessionId('ai-repair'),
    sqlText: 'SELECT missing_column FROM orders LIMIT 1;',
    errorMessage: failureMessage || 'no such column: missing_column',
    databaseName: context.sqliteDatabaseName,
  });
  const repairedSql = (result.repairedSql || '').trim();
  let executedRows = 0;
  if (repairedSql) {
    const executeResult = await executeSql(context.sqliteConnectionId, context.sqliteDatabaseName, repairedSql, {
      sessionId: nextSessionId('ai-repaired-sql'),
    });
    executedRows = (executeResult.rows || []).length;
  }

  if (!failureMessage) {
    throw new Error('未能先复现失败 SQL，Repair 场景前置条件不足。');
  }
  if (repairedSql && executedRows >= 1) {
    return {
      summary: `AI Repair 成功生成修复 SQL 并执行，返回 ${executedRows} 行。`,
      screenshotName: 'sqlite-ai-repair-pass.png',
    };
  }
  const combined = failureMessage || result.errorExplanation || repairedSql;
  if (isBlockedError(combined)) {
    return {
      status: 'BLOCKED',
      summary: `AI Repair 被环境阻塞：${combined}`,
      screenshotName: 'sqlite-ai-repair-blocked.png',
    };
  }
  throw new Error(combined || 'AI Repair 未返回可执行修复结果。');
}

async function scenarioTableData(window, context) {
  const firstPage = await loadTablePage(context.sqliteConnectionId, context.sqliteDatabaseName, 'customers', {
    sorts: [{
      columnName: 'id',
      direction: 'ASC',
    }],
  });
  const firstRow = firstPage.rows?.[0];
  if (!firstRow) {
    throw new Error('customers 数据页未加载首行数据。');
  }
  const rowMap = Object.fromEntries((firstRow.cells || []).map((item) => [item.columnName, item.cellValue]));
  const originalCity = rowMap.city;
  const updatedCity = `${originalCity}-QA`;
  await nodeApiPost('/api/schema/table/data/commit', {
    connectionId: context.sqliteConnectionId,
    databaseName: context.sqliteDatabaseName,
    tableName: 'customers',
    objectType: 'tables',
    inserts: [],
    updates: [{
      primaryKeyValues: [{
        columnName: 'id',
        cellValue: rowMap.id,
      }],
      cells: [{
        columnName: 'city',
        cellValue: updatedCity,
      }],
    }],
    deletes: [],
  });
  const updatedPage = await loadTablePage(context.sqliteConnectionId, context.sqliteDatabaseName, 'customers');
  const updatedRow = updatedPage.rows.find((item) => item.cells.some((cell) => cell.columnName === 'id' && cell.cellValue === rowMap.id));
  const updatedRowMap = Object.fromEntries((updatedRow?.cells || []).map((item) => [item.columnName, item.cellValue]));
  if (updatedRowMap.city !== updatedCity) {
    throw new Error('提交表数据变更后，未读到更新结果。');
  }
  await nodeApiPost('/api/schema/table/data/commit', {
    connectionId: context.sqliteConnectionId,
    databaseName: context.sqliteDatabaseName,
    tableName: 'customers',
    objectType: 'tables',
    inserts: [],
    updates: [{
      primaryKeyValues: [{
        columnName: 'id',
        cellValue: rowMap.id,
      }],
      cells: [{
        columnName: 'city',
        cellValue: originalCity,
      }],
    }],
    deletes: [],
  });
  const restoredPage = await loadTablePage(context.sqliteConnectionId, context.sqliteDatabaseName, 'customers');
  const restoredRow = restoredPage.rows.find((item) => item.cells.some((cell) => cell.columnName === 'id' && cell.cellValue === rowMap.id));
  const restoredRowMap = Object.fromEntries((restoredRow?.cells || []).map((item) => [item.columnName, item.cellValue]));

  if (restoredRowMap.city !== originalCity) {
    throw new Error('回滚表数据失败。');
  }
  return {
    summary: `表数据页支持筛选、排序、编辑和回滚，当前页 ${firstPage.pageNo}，展示 ${(firstPage.rows || []).length} 行。`,
    screenshotName: 'sqlite-table-data-edit.png',
  };
}

async function scenarioTableEditor(window, context) {
  const detail = await nodeApiGet(`/api/schema/tableDetail?connectionId=${context.sqliteConnectionId}&databaseName=${encodeURIComponent(context.sqliteDatabaseName)}&tableName=orders`);
  if ((detail.columns || []).length < 5) {
    throw new Error(`表结构编辑页字段数异常：${(detail.columns || []).length}`);
  }
  return {
    summary: `表结构编辑页加载成功，表 orders 字段 ${(detail.columns || []).length} 个，索引 ${(detail.indexes || []).length} 个。`,
    screenshotName: 'sqlite-table-editor-view.png',
  };
}

async function scenarioObjectDefinition(window, context) {
  const detail = await nodeApiGet(`/api/schema/object/definition?connectionId=${context.sqliteConnectionId}&databaseName=${encodeURIComponent(context.sqliteDatabaseName)}&objectType=views&objectName=${encodeURIComponent('v_order_summary')}`);

  if ((detail.definitionSql || '').length < 40) {
    throw new Error('对象定义编辑器未接收到预期 SQL 文本。');
  }
  return {
    summary: `对象定义编辑器接口返回成功，视图定义 SQL 长度 ${(detail.definitionSql || '').length}。`,
    screenshotName: 'sqlite-object-definition-editor.png',
  };
}

async function scenarioErSnapshot(window, context) {
  const snapshotName = `QA Acceptance Snapshot R${round}`;
  const graph = await nodeApiPost('/api/schema/er/graph', {
    connectionId: context.sqliteConnectionId,
    databaseName: context.sqliteDatabaseName,
    tableNames: ['customers', 'orders', 'order_items'],
    includeAiInference: true,
    aiConfidenceThreshold: 0.6,
  });
  if ((graph.tables || []).length < 3) {
    throw new Error('ER 图未成功生成节点。');
  }
  await nodeApiPost('/api/editor/er/snapshot/save', {
    connectionId: context.sqliteConnectionId,
    databaseName: context.sqliteDatabaseName,
    snapshotName,
    selectedTableNames: ['customers', 'orders', 'order_items'],
    modelName: '',
    layoutMode: 'GRID',
    aiConfidenceThreshold: 0.6,
    includeAiInference: true,
    graph,
  });
  const page = await nodeApiGet(`/api/editor/er/snapshot/page?connectionId=${context.sqliteConnectionId}&pageNo=1&pageSize=20&keyword=${encodeURIComponent(snapshotName)}`);
  const saved = (page.items || []).find((item) => item.snapshotName === snapshotName);
  if (!saved) {
    throw new Error('ER 快照保存后未出现在快照列表。');
  }
  const reopened = await nodeApiGet(`/api/editor/er/snapshot/detail?id=${saved.id}`);

  if ((reopened.graph?.tables || reopened.graph?.nodes || []).length < 3 && (reopened.selectedTableNames || []).length < 3) {
    throw new Error('ER 快照重开后节点不足。');
  }
  return {
    summary: `ER 图生成并保存快照成功，快照 ID ${saved.id}，选表 ${(reopened.selectedTableNames || []).length} 张。`,
    screenshotName: 'sqlite-er-snapshot-reopen.png',
  };
}

async function scenarioHistoryKnowledge(window, context) {
  const historyPage = await nodeApiGet(`/api/editor/history/session/page?connectionId=${context.sqliteConnectionId}&pageNo=1&pageSize=10`);
  const firstSession = historyPage.items?.[0];
  const historyMessages = firstSession
    ? await nodeApiGet(`/api/editor/history/session/detail?connectionId=${context.sqliteConnectionId}&sessionId=${encodeURIComponent(firstSession.sessionId)}&limit=20`)
    : [];
  const examples = await nodeApiGet('/api/knowledge/example/list?scope=GLOBAL&connectionId=0&databaseName=');
  const terms = await nodeApiGet('/api/knowledge/term/list?scope=GLOBAL&connectionId=0&databaseName=');

  if (!(historyPage.items || []).length) {
    throw new Error('历史会话列表为空，无法完成历史恢复验收。');
  }
  if (!historyMessages.length) {
    throw new Error('打开历史会话后未恢复聊天记录。');
  }
  if (!examples.length || !terms.length) {
    throw new Error(`知识中心数据不足：样例 ${examples.length}，术语 ${terms.length}`);
  }
  return {
    summary: `历史与知识中心通过：历史会话 ${(historyPage.items || []).length} 条，样例 ${examples.length} 条，术语 ${terms.length} 条。`,
    screenshotName: 'sqlite-history-knowledge.png',
  };
}

async function scenarioI18n(window, context) {
  const sceneHits = [];
  const runSubscene = async (name, locale, dark) => {
    await resetWorkspace(window, context, { locale, dark });
    await window.waitForTimeout(1200);
    const bodyText = await window.evaluate(() => document.body?.innerText || '');
    const hits = await snapshotStrings(window);
    sceneHits.push({ name, hits, bodyText });
  };

  await runSubscene('main-light', 'en-US', false);
  await runSubscene('main-dark', 'en-US', true);

  const unexpected = sceneHits.filter((item) => item.hits.length > 0);
  if (unexpected.length) {
    const details = unexpected.map((item) => `${item.name}: ${item.hits.map((hit) => hit.text).join(' | ')}`).join(' ; ');
    return {
      status: 'FAIL',
      summary: `英文/主题高频场景仍有中文残留：${details}`,
      screenshotName: 'ui-i18n-theme-fail.png',
    };
  }

  const untranslated = sceneHits.filter((item) => !/Object Browser|History|Settings/i.test(item.bodyText));
  if (untranslated.length) {
    return {
      status: 'FAIL',
      summary: `英文场景未命中预期关键文案：${untranslated.map((item) => item.name).join(', ')}`,
      screenshotName: 'ui-i18n-theme-fail.png',
    };
  }

  return {
    summary: '中英切换与浅深色高频场景未发现明显中文残留或主题异常。',
    screenshotName: 'ui-i18n-theme-pass.png',
  };
}

async function scenarioRedis(window, context) {
  if (!context.redisConnectionId) {
    return {
      status: 'BLOCKED',
      summary: '未找到 Redis 连接配置。',
      screenshotName: 'redis-smoke-blocked.png',
    };
  }

  try {
    await nodeApiPost('/api/connection/test', { connectionId: context.redisConnectionId });
  } catch (error) {
    return {
      status: 'BLOCKED',
      summary: `Redis 连接测试失败：${error.message}`,
      screenshotName: 'redis-smoke-blocked.png',
    };
  }

  const keyName = `qa:acceptance:round-${round}:value`;
  await nodeApiPost('/api/kv/redis/key/create', {
    connectionId: context.redisConnectionId,
    databaseName: '0',
    keyName,
    valueType: 'string',
    ttlSeconds: 300,
    stringValue: 'round-1',
    entries: [],
  });
  await nodeApiPost('/api/kv/redis/key/update', {
    connectionId: context.redisConnectionId,
    databaseName: '0',
    keyName,
    valueType: 'string',
    ttlSeconds: 300,
    stringValue: 'round-1-updated',
    entries: [],
  });
  const detail = await nodeApiGet(`/api/kv/object/detail?connectionId=${context.redisConnectionId}&databaseName=0&objectName=${encodeURIComponent(keyName)}`);
  const deleteResult = await nodeApiPost('/api/kv/redis/key/delete', {
    connectionId: context.redisConnectionId,
    databaseName: '0',
    targetType: 'KEY',
    targetValue: keyName,
  });

  const browser = await nodeApiGet(`/api/kv/redis/browser?connectionId=${context.redisConnectionId}&databaseName=0&parentPath=&keyword=&cursor=0&pageSize=20`);

  if (detail.valueType !== 'string') {
    throw new Error(`Redis 新增键类型异常：${detail.valueType}`);
  }
  if (deleteResult.deletedCount < 1) {
    throw new Error('Redis 删除新增键未成功。');
  }
  if (!(browser.items || []).length) {
    throw new Error('Redis 浏览树未返回任何节点。');
  }
  return {
    summary: `Redis 烟测通过：树表节点 ${(browser.items || []).length} 条，新增/更新/删除 qa:acceptance:* 键成功。`,
    screenshotName: 'redis-smoke-pass.png',
  };
}

async function resolveBrowseContext(connectionId, dbType) {
  const databases = await nodeApiGet(`/api/schema/databases?connectionId=${connectionId}`);
  const items = Array.isArray(databases) ? databases : [];
  if (!items.length) {
    throw new Error('未返回任何数据库。');
  }
  for (const item of items) {
    const databaseName = String(item.databaseName || '').trim();
    if (!databaseName) {
      continue;
    }
    let browseContext = databaseName;
    if (dbType === 'SQLSERVER') {
      const namespaces = await nodeApiGet(`/api/schema/namespaces?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}`);
      const namespaceName = String(namespaces?.[0]?.namespaceName || '').trim();
      browseContext = namespaceName ? `${databaseName}::${namespaceName}` : databaseName;
    }
    const objectNames = await nodeApiGet(`/api/schema/objectNames?connectionId=${connectionId}&databaseName=${encodeURIComponent(browseContext)}&objectType=tables`);
    if ((objectNames || []).length) {
      return {
        browseContext,
        objectNames,
      };
    }
  }
  return {
    browseContext: dbType === 'SQLSERVER'
      ? `${String(items[0].databaseName || '').trim()}::${String((await nodeApiGet(`/api/schema/namespaces?connectionId=${connectionId}&databaseName=${encodeURIComponent(String(items[0].databaseName || '').trim())}`))?.[0]?.namespaceName || '').trim()}`
      : String(items[0].databaseName || '').trim(),
    objectNames: [],
  };
}

async function runRelationalSmoke(window, context, options) {
  if (!options.connectionId) {
    return {
      status: 'BLOCKED',
      summary: `未找到 ${options.label} 连接配置。`,
      screenshotName: `${options.id}-blocked.png`,
    };
  }

  try {
    await nodeApiPost('/api/connection/test', { connectionId: options.connectionId });
  } catch (error) {
    return {
      status: 'BLOCKED',
      summary: `${options.label} 连接测试失败：${error.message}`,
      screenshotName: `${options.id}-blocked.png`,
    };
  }

  const browse = await resolveBrowseContext(options.connectionId, options.dbType);
  const browseContext = browse.browseContext;
  const objectNames = browse.objectNames;
  if (!objectNames.length) {
    throw new Error(`${options.label} 烟测失败：对象浏览未返回任何表。`);
  }
  return {
    status: 'BLOCKED',
    summary: `${options.label} 已通过连接与对象浏览烟测（数据库上下文 ${browseContext}，表 ${objectNames.length} 个），但默认 SQL 模板的 Electron UI 路径仍需后续补做自动化校验。`,
    screenshotName: `${options.id}-blocked.png`,
  };
}

async function main() {
  ensureDir(outputDir);
  ensureDir(screenshotsDir);
  const { electronApp, window } = await launchWindow();
  const results = [];
  try {
    const context = await resolveRuntimeContext(window);
    const shellDebug = await window.evaluate(() => {
      const shell = window.__QA_getShell();
      const internal = shell?.$ || shell?._ || null;
      return {
        type: typeof shell,
        keys: Object.keys(shell || {}).slice(0, 200),
        ownNames: shell ? Object.getOwnPropertyNames(shell).slice(0, 50) : [],
        dollarKeys: internal ? Object.keys(internal).slice(0, 50) : [],
        setupStateKeys: internal?.setupState ? Object.keys(internal.setupState).slice(0, 200) : [],
        ctxKeys: internal?.ctx ? Object.keys(internal.ctx).slice(0, 200) : [],
        hasWorkflow: Boolean(shell?.workflow),
        hasConnections: Boolean(shell?.connections),
        hasConnectionBrowserModule: Boolean(shell?.connectionBrowserModule),
        hasQueryModule: Boolean(shell?.queryModule),
        hasErModule: Boolean(shell?.erModule),
        hasHistoryModule: Boolean(shell?.historyModule),
        hasKnowledgeModule: Boolean(shell?.knowledgeModule),
        hasUiShellModule: Boolean(shell?.uiShellModule),
      };
    });
    writeJson(path.join(outputDir, 'shell-debug.json'), shellDebug);
    const scenarios = [
      { id: 'startup-electron-shell', module: 'startup', priority: 'P0', run: scenarioStartup },
      { id: 'sqlite-browser-detail', module: 'sqlite', priority: 'P0', run: scenarioSqliteBrowser },
      { id: 'sqlite-query-save-reopen', module: 'sqlite', priority: 'P0', run: scenarioSqliteQuery },
      { id: 'sqlite-ai-generate-execute', module: 'sqlite-ai', priority: 'P0', run: scenarioAiGenerate },
      { id: 'sqlite-ai-explain', module: 'sqlite-ai', priority: 'P0', run: scenarioAiExplain },
      { id: 'sqlite-ai-analyze', module: 'sqlite-ai', priority: 'P0', run: scenarioAiAnalyze },
      { id: 'sqlite-ai-repair', module: 'sqlite-ai', priority: 'P0', run: scenarioAiRepair },
      { id: 'sqlite-table-data-edit', module: 'sqlite', priority: 'P0', run: scenarioTableData },
      { id: 'sqlite-table-editor-view', module: 'sqlite', priority: 'P0', run: scenarioTableEditor },
      { id: 'sqlite-object-definition-editor', module: 'sqlite', priority: 'P0', run: scenarioObjectDefinition },
      { id: 'sqlite-er-snapshot-reopen', module: 'sqlite', priority: 'P0', run: scenarioErSnapshot },
      { id: 'sqlite-history-knowledge', module: 'sqlite', priority: 'P0', run: scenarioHistoryKnowledge },
      { id: 'ui-i18n-theme', module: 'ui', priority: 'P0', run: scenarioI18n },
      { id: 'redis-smoke', module: 'redis', priority: 'P1', run: scenarioRedis },
      {
        id: 'mysql-smoke',
        module: 'mysql',
        priority: 'P1',
        run: (currentWindow, currentContext) => runRelationalSmoke(currentWindow, currentContext, {
          id: 'mysql-smoke',
          label: 'MySQL',
          connectionId: currentContext.mysqlConnectionId,
          dbType: 'MYSQL',
          expectedSqlFragment: 'LIMIT 100',
        }),
      },
      {
        id: 'sqlserver-smoke',
        module: 'sqlserver',
        priority: 'P1',
        run: (currentWindow, currentContext) => runRelationalSmoke(currentWindow, currentContext, {
          id: 'sqlserver-smoke',
          label: 'SQL Server',
          connectionId: currentContext.sqlServerConnectionId,
          dbType: 'SQLSERVER',
          expectedSqlFragment: 'TOP 100',
        }),
      },
    ];

    for (const scenario of scenarios) {
      const result = await runScenario(window, context, scenario);
      results.push(result);
      console.log(`[acceptance] ${scenario.id}: ${result.status} - ${result.summary}`);
    }
  } finally {
    await electronApp.close().catch(() => {});
  }

  const summaryLines = [
    '# Electron Acceptance Summary',
    '',
    ...results.map((item) => `- [${item.status}] ${item.priority} ${item.module}/${item.scenario}: ${item.summary}`),
    '',
  ];
  writeJson(path.join(outputDir, 'electron-results.json'), results);
  writeText(path.join(outputDir, 'electron-summary.md'), `${summaryLines.join('\n')}\n`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

