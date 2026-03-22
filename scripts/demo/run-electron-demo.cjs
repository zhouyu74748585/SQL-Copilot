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
  relativeFromRoot,
  sleep,
} = require('../acceptance/lib/common.cjs');

const args = parseArgs(process.argv.slice(2));
const outputDir = path.resolve(args['output-dir'] || path.join(ROOT, 'output', 'playwright', 'mysql-demo', 'latest'));
const screenshotsDir = path.join(outputDir, 'screenshots');
const stepsFile = path.join(outputDir, 'steps.json');
const summaryFile = path.join(outputDir, 'summary.md');
const resultFile = path.join(outputDir, 'demo-results.json');
const ELECTRON_BINARY = require(require.resolve('electron', { paths: [DESKTOP_DIR] }));

const DEFAULT_STEP_DURATION_MS = 1300;
const ACTION_SETTLE_MS = 450;
const STEP_SETTLE_MS = 1500;
const OMITTED_FEATURES = [
  'Redis/KV 独立流程：不属于 MySQL 主线演示。',
  '删除/清空/重命名/复制等高风险维护动作：不适合正常流程 GIF。',
  'AI Repair：本次主流程聚焦 Auto 对话的 NL2SQL、趋势图、解释与分析。',
  '查询结果 CSV 导出：当前会出现数据库上下文提示干扰主流程，暂不纳入主演示。',
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

function sanitizeFilePart(value) {
  return String(value || '')
    .trim()
    .replace(/[\\/:*?"<>|]/g, '_')
    .replace(/\s+/g, '_')
    .slice(0, 80);
}

async function nodeApiGet(requestPath) {
  const response = await fetch(`${BACKEND_URL}${requestPath}`);
  const json = await response.json();
  if (!response.ok || json.code !== 0) {
    throw new Error(json.message || `HTTP ${response.status}`);
  }
  return json.data;
}

async function waitForShellReady(window) {
  const startedAt = Date.now();
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
        || null;
      const text = document.body?.innerText || '';
      return Boolean(shell) && (text.includes('对象浏览') || text.includes('Object Browser'));
    }).catch(() => false);
    if (ready) {
      return;
    }
    await sleep(300);
  }
  throw new Error('Electron shell was not ready within 60 seconds.');
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
  await window.waitForTimeout(4500);
  await waitForShellReady(window);
  return { electronApp, window };
}

async function captureScreenshot(window, fileName) {
  const screenshotPath = path.join(screenshotsDir, fileName);
  await window.screenshot({ path: screenshotPath, fullPage: true });
  return relativeFromRoot(screenshotPath);
}

async function waitVisible(locator, timeout = 10_000) {
  await locator.first().waitFor({ state: 'visible', timeout });
}

async function tryClick(locator, timeout = 1200) {
  try {
    await locator.first().waitFor({ state: 'visible', timeout });
    await locator.first().click();
    return true;
  } catch (_) {
    return false;
  }
}

async function clickByText(window, selectors, text, options = {}) {
  for (const selector of selectors) {
    const locator = window.locator(selector).filter({ hasText: text }).first();
    try {
      await locator.waitFor({ state: 'visible', timeout: options.timeout || 1500 });
      if (options.button) {
        await locator.click({ button: options.button });
      } else if (options.dblclick) {
        await locator.dblclick();
      } else {
        await locator.click();
      }
      return true;
    } catch (_) {
      // try next selector
    }
  }
  return false;
}

async function clickContextMenuAction(window, actionKey, actionLabels = []) {
  const menu = window.locator('[data-testid="studio-context-menu"], .context-menu').first();
  await waitVisible(menu, 5000);
  const direct = menu.locator(`[data-testid="studio-context-menu-item-${actionKey}"]`).first();
  if (await direct.isVisible().catch(() => false)) {
    await direct.click();
    await sleep(ACTION_SETTLE_MS);
    return;
  }
  const labels = Array.isArray(actionLabels) ? actionLabels : [actionLabels];
  for (const label of labels.filter(Boolean)) {
    const action = menu.locator('[data-testid^="studio-context-menu-item-"], .context-menu-item').filter({ hasText: label }).first();
    if (await action.isVisible().catch(() => false)) {
      await action.click();
      await sleep(ACTION_SETTLE_MS);
      return;
    }
  }
  throw new Error(`Context menu action was not found: ${actionKey}`);
}

async function openBrowserTab(window) {
  const byTestId = window.locator('[data-testid="studio-browser-tab"]');
  if (await tryClick(byTestId, 500)) {
    return;
  }
  const ok = await clickByText(window, ['.workspace-tab', 'button'], '对象浏览');
  if (!ok) {
    throw new Error('Failed to switch to 对象浏览 tab.');
  }
}

async function selectConnectionAndDatabase(window, connectionName, databaseName, connectionId = null, keepTreeFilter = false) {
  await openBrowserTab(window);
  const searchInput = window.locator('input[placeholder="搜索连接"], input[placeholder="Search Connections"]').first();
  if (await searchInput.isVisible().catch(() => false)) {
    await searchInput.fill('');
    await sleep(300);
  }

  const preferredConnectionNode = connectionId == null
    ? null
    : window.locator(`[data-testid="studio-tree-node-connection-${connectionId}"]`).first();
  const connectionLabels = [connectionName];
  if (connectionName === '新建连接') {
    connectionLabels.push('New Connection');
  }
  const clickedConn = preferredConnectionNode && await tryClick(preferredConnectionNode, 1200)
    ? true
    : await clickByText(
      window,
      [
        '.ant-tree-node-content-wrapper',
        '.ant-tree-title',
        '.tree-node-title',
      ],
      connectionLabels[0],
    );
  let resolvedClickedConn = clickedConn;
  if (!resolvedClickedConn) {
    for (const label of connectionLabels.slice(1)) {
      resolvedClickedConn = await clickByText(
        window,
        [
          '.ant-tree-node-content-wrapper',
          '.ant-tree-title',
          '.tree-node-title',
        ],
        label,
      );
      if (resolvedClickedConn) {
        break;
      }
    }
  }
  if (!resolvedClickedConn) {
    const switchers = window.locator('.ant-tree-switcher').filter({ has: window.locator('.ant-tree-switcher-icon') });
    const count = await switchers.count().catch(() => 0);
    for (let i = 0; i < count; i += 1) {
      await switchers.nth(i).click().catch(() => {});
    }
  }
  let clickedConnAfterExpand = resolvedClickedConn;
  if (!clickedConnAfterExpand) {
    for (const label of connectionLabels) {
      clickedConnAfterExpand = await clickByText(
        window,
        [
          '.ant-tree-node-content-wrapper',
          '.ant-tree-title',
          '.tree-node-title',
        ],
        label,
        { timeout: 2500 },
      );
      if (clickedConnAfterExpand) {
        break;
      }
    }
  }
  if (!clickedConnAfterExpand) {
    throw new Error(`Failed to select connection: ${connectionName}`);
  }

  const dbNode = window.locator('.ant-tree-node-content-wrapper, .ant-tree-title, .tree-node-title').filter({ hasText: databaseName }).first();
  if (!(await dbNode.isVisible().catch(() => false))) {
    const connNode = window.locator('.ant-tree-node').filter({ hasText: connectionName }).first();
    const switcher = connNode.locator('.ant-tree-switcher').first();
    if (!(await tryClick(switcher, 1000))) {
      await connNode.dblclick().catch(() => {});
    }
  }

  const clickedDb = await clickByText(
    window,
    [
      `[data-testid="studio-tree-node-database-${sanitizeFilePart(databaseName)}"]`,
      '.ant-tree-node-content-wrapper',
      '.ant-tree-title',
      '.tree-node-title',
    ],
    databaseName,
    { timeout: 5000 },
  );
  if (!clickedDb) {
    throw new Error(`Failed to select database: ${databaseName}`);
  }
  if (await searchInput.isVisible().catch(() => false) && !keepTreeFilter) {
    await searchInput.fill('');
  }
  await sleep(900);
}

async function switchToObjectCategory(window, categoryLabel) {
  const objectSearch = window.locator('.center-toolbar-right input').first();
  if (await objectSearch.isVisible().catch(() => false)) {
    await objectSearch.fill('');
    await sleep(500);
  }
  const candidates = [categoryLabel];
  if (categoryLabel === '表') {
    candidates.push('Table');
  } else if (categoryLabel === '视图') {
    candidates.push('View');
  } else if (categoryLabel === '函数') {
    candidates.push('Function');
  } else if (categoryLabel === '查询') {
    candidates.push('Query');
  }
  let clicked = false;
  for (const label of candidates) {
    clicked = await clickByText(
      window,
      ['.ant-tree-node-content-wrapper', '.ant-tree-title', '.tree-node-title'],
      label,
      { timeout: 3000 },
    );
    if (clicked) {
      break;
    }
  }
  if (!clicked) {
    throw new Error(`Failed to switch object category: ${categoryLabel}`);
  }
  await sleep(350);
}

async function openDatabaseCategory(window, databaseName, categoryLabel) {
  const databaseNode = window.locator(`[data-testid="studio-tree-node-database-${sanitizeFilePart(databaseName)}"]`).first();
  if (await databaseNode.isVisible().catch(() => false)) {
    await databaseNode.click().catch(() => {});
    await sleep(700);
  }
  await switchToObjectCategory(window, categoryLabel);
}

function objectRowLocator(window, objectName, objectType = 'tables') {
  const exact = window.locator(`[data-testid="studio-object-row-${objectType}-${sanitizeFilePart(objectName)}"]`).first();
  const fallback = window.locator(
    '.object-list-table .table-name-cell:visible, .object-grid .object-card:visible',
  ).filter({ hasText: objectName }).first();
  return {
    exact,
    fallback,
  };
}

async function selectObjectRow(window, objectName, objectType = 'tables') {
  let { exact, fallback } = objectRowLocator(window, objectName, objectType);
  let row = await exact.isVisible().catch(() => false) ? exact : fallback;
  if (!(await row.isVisible().catch(() => false))) {
    const objectSearch = window.locator('.center-toolbar-right input').first();
    if (await objectSearch.isVisible().catch(() => false)) {
      await objectSearch.fill(objectName);
      await sleep(1200);
      ({ exact, fallback } = objectRowLocator(window, objectName, objectType));
      row = await exact.isVisible().catch(() => false) ? exact : fallback;
    }
  }
  await waitVisible(row, 10_000);
  await row.click();
  await sleep(ACTION_SETTLE_MS);
  return row;
}

async function openQueryFromObjectContext(window, tableName) {
  const row = await selectObjectRow(window, tableName);
  await row.click({ button: 'right' });
  await clickContextMenuAction(window, 'querySql', ['SQL查询', 'SQL Query']);
  await waitVisible(window.locator('.query-editor-pane'));
  await sleep(900);
}

async function readActiveSql(window) {
  return window.evaluate(() => {
    const root = document.querySelector('#app');
    const studioRoot = document.querySelector('.studio-root');
    const shell = root?.__vue_app__?._instance?.subTree?.component?.setupState
      || root?.__vue_app__?._instance?.proxy
      || root?.__vue_app__?._container?._vnode?.component?.setupState
      || root?.__vue_app__?._container?._vnode?.component?.proxy
      || studioRoot?.__vueParentComponent?.setupState
      || studioRoot?.__vueParentComponent?.proxy
      || studioRoot?.__vueParentComponent?.ctx
      || null;
    const shellSql = shell?.activeQueryTab?.sqlText
      || shell?.workflow?.sqlText
      || shell?.queryModule?.activeQueryTab?.value?.sqlText
      || '';
    if (String(shellSql || '').trim()) {
      return String(shellSql || '');
    }
    const monacoText = Array.from(document.querySelectorAll('.query-editor-group .view-lines'))
      .map((node) => node.textContent || '')
      .join('\n')
      .trim();
    return monacoText;
  });
}

async function waitQueryResultReady(window, timeoutMs = 30_000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const footer = await window.locator('.query-result-footer').first().textContent().catch(() => '');
    const loading = await window.locator('.query-result-panel .ant-spin-spinning').count().catch(() => 0);
    if (footer && loading === 0) {
      return;
    }
    await sleep(300);
  }
  throw new Error('Timed out waiting for query result.');
}

async function clickQueryHeaderButton(window, index) {
  const buttons = window.locator('.query-editor-header-actions .sql-action-icon-btn');
  await buttons.nth(index).click();
}

async function setSqlEditorText(window, sqlText) {
  const editor = window.locator('.query-editor-group .monaco-editor').first();
  await editor.click();
  await window.keyboard.press('Control+A');
  await window.keyboard.type(sqlText, { delay: 1 });
}

async function ensureManualMode(window) {
  const modePill = window.locator('.query-chat-mode-pill').first();
  const modeText = (await modePill.textContent().catch(() => '') || '').trim();
  if (!modeText.includes('Auto')) {
    return;
  }
  await window.locator('.query-chat-settings-trigger').first().click();
  const autoSwitch = window.locator('.query-chat-settings-panel .query-chat-settings-item').first().locator('.ant-switch').first();
  const checked = await autoSwitch.getAttribute('aria-checked').catch(() => 'false');
  if (checked === 'true') {
    await autoSwitch.click();
  }
  await window.keyboard.press('Escape').catch(() => {});
  await sleep(300);
}

async function ensureAutoModeAndExecute(window) {
  if (!(await window.locator('.query-chat-settings-trigger').first().isVisible().catch(() => false))) {
    return;
  }
  await window.locator('.query-chat-settings-trigger').first().click();
  const settingsPanel = window.locator('.query-chat-settings-panel').first();
  await waitVisible(settingsPanel, 5000);

  const autoModeSwitch = settingsPanel.locator('.query-chat-settings-item').filter({ hasText: 'Auto 模式' }).first().locator('.ant-switch').first();
  const autoModeChecked = await autoModeSwitch.getAttribute('aria-checked').catch(() => 'false');
  if (autoModeChecked !== 'true') {
    await autoModeSwitch.click();
    await sleep(ACTION_SETTLE_MS);
  }

  const autoExecuteItem = settingsPanel.locator('.query-chat-settings-item').filter({ hasText: '自动执行' }).first();
  if (await autoExecuteItem.isVisible().catch(() => false)) {
    const autoExecuteSwitch = autoExecuteItem.locator('.ant-switch').first();
    const autoExecuteChecked = await autoExecuteSwitch.getAttribute('aria-checked').catch(() => 'false');
    if (autoExecuteChecked !== 'true') {
      await autoExecuteSwitch.click();
      await sleep(ACTION_SETTLE_MS);
    }
  }

  await window.keyboard.press('Escape').catch(() => {});
  await sleep(500);
}

async function waitForAssistantTurnCompleted(window, previousAssistantCount, timeoutMs = 120_000) {
  const started = Date.now();
  let stableAt = 0;
  while (Date.now() - started < timeoutMs) {
    const assistantMessages = window.locator('.query-chat-message.is-assistant');
    const count = await assistantMessages.count().catch(() => 0);
    const lastAssistant = assistantMessages.last();
    const headText = count > previousAssistantCount
      ? String(await lastAssistant.locator('.query-chat-assistant-head').textContent().catch(() => '') || '')
      : '';
    const textContent = count > previousAssistantCount
      ? String(await lastAssistant.locator('.query-chat-text').textContent().catch(() => '') || '')
      : '';
    const hasSql = count > previousAssistantCount
      ? await lastAssistant.locator('.query-chat-sql').count().catch(() => 0)
      : 0;
    const hasChart = count > previousAssistantCount
      ? await lastAssistant.locator('.query-chat-chart-image').count().catch(() => 0)
      : 0;
    const hasExecution = count > previousAssistantCount
      ? await lastAssistant.locator('.query-chat-execution-summary').count().catch(() => 0)
      : 0;
    const hasThinking = count > previousAssistantCount
      ? await lastAssistant.locator('.query-chat-thinking-icon').count().catch(() => 0)
      : 0;
    if (
      count > previousAssistantCount
      && !headText.includes('流式中')
      && hasThinking === 0
      && (
        hasSql > 0
        || hasChart > 0
        || hasExecution > 0
        || textContent.trim().length > 0
      )
    ) {
      if (!stableAt) {
        stableAt = Date.now();
      }
      if (Date.now() - stableAt >= 1200) {
        return {
          assistantCount: count,
          headText,
          textContent,
          hasSql,
          hasChart,
          hasExecution,
        };
      }
    } else {
      stableAt = 0;
    }
    await sleep(450);
  }
  throw new Error('Timed out waiting for assistant turn to complete.');
}

async function getAssistantCount(window) {
  return window.locator('.query-chat-message.is-assistant').count().catch(() => 0);
}

function firstExecutableStatement(sqlText) {
  return String(sqlText || '')
    .replace(/```(?:sql)?/gi, '')
    .split(';')
    .map((item) => item.trim())
    .find(Boolean) || '';
}

async function openErSnapshotSaveModal(window) {
  const saveModal = window.locator('.ant-modal').filter({
    has: window.locator('.ant-modal-title').filter({ hasText: /保存 ER 图快照|Save ER Snapshot/i }),
  }).first();
  const saveButton = window.locator('.er-toolbar .ant-space').first().locator('.sql-action-icon-btn').nth(2);
  await saveButton.scrollIntoViewIfNeeded().catch(() => {});
  if (!(await tryClick(saveButton, 1200))) {
    await saveButton.click({ force: true }).catch(() => {});
  }
  await sleep(800);
  if (await saveModal.isVisible().catch(() => false)) {
    return saveModal;
  }
  await saveButton.dispatchEvent('click').catch(() => {});
  await sleep(800);
  if (await saveModal.isVisible().catch(() => false)) {
    return saveModal;
  }
  await saveButton.evaluate((node) => {
    node.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  }).catch(() => {});
  await waitVisible(saveModal, 10_000);
  return saveModal;
}

async function waitAssistantSqlCount(window, minCount, timeoutMs = 90_000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const count = await window.locator('.query-chat-message.is-assistant .query-chat-sql').count();
    if (count >= minCount) {
      return count;
    }
    await sleep(350);
  }
  throw new Error('Timed out waiting for AI generated SQL.');
}

async function readLatestAssistantSql(window) {
  const lastSql = await window.locator('.query-chat-message.is-assistant .query-chat-sql').last().textContent().catch(() => '');
  return String(lastSql || '').replace(/\s+/g, ' ').trim();
}

async function composePromptWithTableMention(window, tableName, questionText) {
  const promptBox = window.locator('[data-testid="studio-query-chat-prompt"], .query-chat-composer textarea').first();
  await promptBox.fill('');
  await promptBox.type(`@${tableName}`, { delay: 20 });
  const assistItem = window.locator('.query-chat-prompt-assist-item').filter({ hasText: tableName }).first();
  if (await assistItem.isVisible().catch(() => false)) {
    await assistItem.click();
    await sleep(200);
  }
  const currentText = await promptBox.inputValue().catch(() => '');
  if (!currentText.includes(tableName)) {
    await promptBox.fill(`@${tableName}`);
  }
  await promptBox.type(` ${questionText}`, { delay: 20 });
  return promptBox;
}

async function waitAssistantMessageCount(window, minCount, timeoutMs = 90_000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const count = await window.locator('.query-chat-message.is-assistant').count();
    if (count >= minCount) {
      return count;
    }
    await sleep(350);
  }
  throw new Error('Timed out waiting for assistant message.');
}

async function clickHeaderButtonUntilModal(window, modalTitle, candidateIndexes) {
  for (const index of candidateIndexes) {
    await clickQueryHeaderButton(window, index).catch(() => {});
    const modal = window.locator('.ant-modal').filter({ has: window.locator('.ant-modal-title', { hasText: modalTitle }) }).first();
    if (await modal.isVisible().catch(() => false)) {
      return modal;
    }
    await sleep(250);
  }
  throw new Error(`Failed to open modal: ${modalTitle}`);
}

async function setAntSelectByText(window, containerLocator, optionText, selectIndex = 0) {
  const select = containerLocator.locator('.ant-select').nth(selectIndex);
  await select.click({ force: true });
  await sleep(250);
  const dropdown = window.locator('.ant-select-dropdown:visible').last();
  const option = dropdown.locator('.ant-select-item-option').filter({ hasText: optionText }).first();
  if (await option.count()) {
    await option.click({ force: true }).catch(async () => {
      await option.evaluate((node) => node.click());
    });
    await sleep(ACTION_SETTLE_MS);
    return;
  }
  const first = dropdown.locator('.ant-select-item-option').first();
  await first.click({ force: true }).catch(async () => {
    await first.evaluate((node) => node.click());
  });
  await sleep(ACTION_SETTLE_MS);
}

async function probeContext() {
  const connections = await nodeApiGet('/api/connection/list');
  const mysqlConnection = connections.find((item) => item.id === 3 && item.dbType === 'MYSQL')
    || connections.find((item) => item.dbType === 'MYSQL');
  if (!mysqlConnection) {
    throw new Error('No MYSQL connection was found.');
  }

  const dbs = await nodeApiGet(`/api/schema/databases?connectionId=${mysqlConnection.id}`);
  const dbNames = (dbs || []).map((item) => String(item.databaseName || '').trim()).filter(Boolean);
  if (!dbNames.length) {
    throw new Error('MySQL connection has no visible databases.');
  }

  const databaseName = dbNames.includes('mdm') ? 'mdm' : dbNames[0];
  const tableNames = await nodeApiGet(`/api/schema/objectNames?connectionId=${mysqlConnection.id}&databaseName=${encodeURIComponent(databaseName)}&objectType=tables`);
  if (!tableNames.length) {
    throw new Error(`No tables in MySQL database: ${databaseName}`);
  }

  const primaryTable = ['distribution_batch_replay_task', 'connection_data_source', 'business_domain']
    .find((item) => tableNames.includes(item)) || tableNames[0];
  const aiTable = tableNames.includes('distribution_callback_log') ? 'distribution_callback_log' : primaryTable;

  const detail = await nodeApiGet(
    `/api/schema/tableDetail?connectionId=${mysqlConnection.id}&databaseName=${encodeURIComponent(databaseName)}&tableName=${encodeURIComponent(primaryTable)}`,
  );
  const columnNames = (detail?.columns || []).map((item) => String(item.columnName || '').trim()).filter(Boolean);
  if (!columnNames.length) {
    throw new Error(`No columns found for table: ${primaryTable}`);
  }

  const preferredColumns = ['id', 'type', 'window_from', 'window_to'].filter((item) => columnNames.includes(item));
  const aiColumns = preferredColumns.length >= 4 ? preferredColumns.slice(0, 4) : columnNames.slice(0, 4);
  const sortColumn = aiColumns.includes('id') ? 'id' : aiColumns[0];
  const aiPrompt = '今年的下发数据量是多少，按月分组';

  const erPreferred = ['connection_api_client', 'connection_broker', 'connection_data_source'];
  const erTables = erPreferred.filter((item) => tableNames.includes(item));
  if (erTables.length < 3) {
    for (const tableName of tableNames) {
      if (!erTables.includes(tableName)) {
        erTables.push(tableName);
      }
      if (erTables.length >= 3) {
        break;
      }
    }
  }

  const sysViews = await nodeApiGet(
    `/api/schema/objectNames?connectionId=${mysqlConnection.id}&databaseName=${encodeURIComponent('sys')}&objectType=views`,
  ).catch(() => []);
  const objectDefinitionDatabase = sysViews.length ? 'sys' : databaseName;
  const objectDefinitionView = objectDefinitionDatabase === 'sys'
    ? (sysViews.includes('host_summary') ? 'host_summary' : (sysViews[0] || ''))
    : '';

  return {
    connectionId: mysqlConnection.id,
    connectionName: mysqlConnection.name,
    databaseName,
    tableNames,
    primaryTable,
    aiTable,
    fallbackTableUsed: primaryTable !== 'distribution_batch_replay_task',
    aiColumns,
    aiPrompt,
    sortColumn,
    erTables: erTables.slice(0, 3),
    objectDefinitionDatabase,
    objectDefinitionView,
  };
}

async function main() {
  ensureDir(outputDir);
  ensureDir(screenshotsDir);
  const { electronApp, window } = await launchWindow();
  const steps = [];
  let stepNo = 0;
  const runtime = await probeContext();
  let snapshotName = '';
  let aiRetryPromptUsed = false;

  async function step(id, title, caption, action, options = {}) {
    try {
      await action();
      await sleep(options.settleMs || STEP_SETTLE_MS);
      const seq = String(++stepNo).padStart(2, '0');
      const screenshot = await captureScreenshot(window, `${seq}-${id}.png`);
      steps.push({
        id,
        title,
        caption,
        screenshot,
        durationMs: options.durationMs || DEFAULT_STEP_DURATION_MS,
        includeInGif: options.includeInGif !== false,
      });
    } catch (error) {
      const seq = String(stepNo + 1).padStart(2, '0');
      await captureScreenshot(window, `${seq}-${id}-failed.png`).catch(() => {});
      throw error;
    }
  }

  try {
    await step(
      'startup-home',
      '启动首页',
      'Electron 主窗口加载完成，进入对象浏览初始页。',
      async () => {
        await openBrowserTab(window);
      },
      { durationMs: 1500 },
    );

    await step(
      'mysql-select-mdm',
      '选择 MySQL 数据库',
      `选择连接 ${runtime.connectionName}(ID=${runtime.connectionId})，切换到数据库 ${runtime.databaseName}。`,
      async () => {
        await selectConnectionAndDatabase(window, runtime.connectionName, runtime.databaseName, runtime.connectionId);
      },
    );

    await step(
      'mysql-select-table',
      '定位主表示例',
      `在对象列表中定位表 ${runtime.primaryTable}。`,
      async () => {
        await selectConnectionAndDatabase(window, runtime.connectionName, runtime.databaseName, runtime.connectionId);
        await selectObjectRow(window, runtime.primaryTable);
      },
    );

    await step(
      'mysql-open-query-default',
      '打开默认 SQL',
      `右键 ${runtime.primaryTable} 执行 SQL查询，确认默认 SQL 模板。`,
      async () => {
        await openQueryFromObjectContext(window, runtime.primaryTable);
        const sqlText = (await readActiveSql(window)).replace(/\s+/g, ' ').trim().toUpperCase();
        if (!sqlText.includes('LIMIT 100')) {
          throw new Error(`Expected LIMIT 100 in default SQL, got: ${sqlText.slice(0, 120)}`);
        }
      },
    );

    await step(
      'mysql-execute-default-sql',
      '执行默认 SQL',
      '执行默认 SQL 并展示结果表格。',
      async () => {
        const executeClicked = await tryClick(window.locator('[data-testid="studio-query-editor-execute"]').first(), 800);
        if (!executeClicked) {
          await clickQueryHeaderButton(window, 1);
        }
        await waitQueryResultReady(window);
      },
    );

    await step(
      'ai-auto-nl2sql',
      'AI Auto 生成 SQL',
      `全程使用 Auto 模式。第一轮直接提问：${runtime.aiPrompt}；若未命中 ${runtime.aiTable}，第二轮再用 @ 自动补全补充表名。`,
      async () => {
        await selectConnectionAndDatabase(window, runtime.connectionName, runtime.databaseName, runtime.connectionId);
        await selectObjectRow(window, runtime.aiTable);
        await openQueryFromObjectContext(window, runtime.aiTable);
        await ensureAutoModeAndExecute(window);
        const promptBox = window.locator('[data-testid="studio-query-chat-prompt"], .query-chat-composer textarea').first();
        await promptBox.fill(runtime.aiPrompt);
        const existing = await getAssistantCount(window);
        const sent = await tryClick(window.locator('.query-chat-composer-actions .sql-action-icon-btn').first(), 1200);
        if (!sent) {
          throw new Error('Failed to send Auto prompt.');
        }
        await waitForAssistantTurnCompleted(window, existing);
        const firstSql = (await readLatestAssistantSql(window)).toLowerCase();
        if (!firstSql.includes(runtime.aiTable.toLowerCase())) {
          aiRetryPromptUsed = true;
          await composePromptWithTableMention(window, runtime.aiTable, runtime.aiPrompt);
          const retryCount = await getAssistantCount(window);
          const retried = await tryClick(window.locator('.query-chat-composer-actions .sql-action-icon-btn').first(), 1200);
          if (!retried) {
            throw new Error('Failed to send retry Auto prompt.');
          }
          await waitForAssistantTurnCompleted(window, retryCount);
        }
      },
      { durationMs: 1800 },
    );

    await step(
      'ai-auto-trend-chart',
      'AI Auto 生成趋势图',
      '在同一 Auto 对话中继续输入“生成趋势图”，等待完整输出。',
      async () => {
        const promptBox = window.locator('[data-testid="studio-query-chat-prompt"], .query-chat-composer textarea').first();
        await promptBox.fill('生成趋势图');
        const existing = await getAssistantCount(window);
        const sent = await tryClick(window.locator('.query-chat-composer-actions .sql-action-icon-btn').first(), 1200);
        if (!sent) {
          throw new Error('Failed to send Auto chart prompt.');
        }
        await waitForAssistantTurnCompleted(window, existing);
      },
    );

    await step(
      'ai-auto-explain-sql',
      'AI Auto 解释 SQL',
      '先确保编辑器中只有一条 SQL，再输入“解释sql”，等待完整输出。',
      async () => {
        const sqlText = firstExecutableStatement(await readLatestAssistantSql(window));
        if (sqlText) {
          await setSqlEditorText(window, sqlText);
        }
        const promptBox = window.locator('[data-testid="studio-query-chat-prompt"], .query-chat-composer textarea').first();
        await promptBox.fill('解释sql');
        const existing = await getAssistantCount(window);
        const sent = await tryClick(window.locator('.query-chat-composer-actions .sql-action-icon-btn').first(), 1200);
        if (!sent) {
          throw new Error('Failed to send Auto explain prompt.');
        }
        await waitForAssistantTurnCompleted(window, existing);
      },
      { durationMs: 1700 },
    );

    await step(
      'ai-auto-analyze-sql',
      'AI Auto 分析 SQL',
      '先确保编辑器中只有一条 SQL，再输入“分析sql”，等待完整输出。',
      async () => {
        const sqlText = firstExecutableStatement(await readLatestAssistantSql(window));
        if (sqlText) {
          await setSqlEditorText(window, sqlText);
        }
        const promptBox = window.locator('[data-testid="studio-query-chat-prompt"], .query-chat-composer textarea').first();
        await promptBox.fill('分析sql');
        const existing = await getAssistantCount(window);
        const sent = await tryClick(window.locator('.query-chat-composer-actions .sql-action-icon-btn').first(), 1200);
        if (!sent) {
          throw new Error('Failed to send Auto analyze prompt.');
        }
        await waitForAssistantTurnCompleted(window, existing);
      },
      { durationMs: 1700 },
    );

    await step(
      'save-query-and-example',
      '保存查询与样例 SQL',
      '依次执行“保存查询”“保存为样例 SQL”，并检查知识中心入口。',
      async () => {
        const saveClicked = await tryClick(window.locator('[data-testid="studio-query-save"]').first(), 800);
        const saveModal = saveClicked
          ? window.locator('.ant-modal').filter({ has: window.locator('[data-testid="studio-save-query-modal"]') }).first()
          : await clickHeaderButtonUntilModal(window, '保存查询', [4, 5, 6]);
        await waitVisible(saveModal, 10_000);
        const queryName = `mysql-demo-${Date.now()}`;
        const saveTitleInput = saveModal.locator('[data-testid="studio-save-query-title"], input').first();
        await saveTitleInput.fill(queryName);
        await saveModal.locator('.ant-modal-footer .ant-btn-primary').first().click();
        await sleep(500);

        const exampleClicked = await tryClick(window.locator('[data-testid="studio-query-save-example"]').first(), 800);
        const exampleModal = exampleClicked
          ? window.locator('.ant-modal').filter({ has: window.locator('[data-testid="studio-save-example-modal"]') }).first()
          : await clickHeaderButtonUntilModal(window, '保存为样例 SQL', [5, 6, 7]);
        await waitVisible(exampleModal, 10_000);
        const exampleInput = exampleModal.locator('[data-testid="studio-save-example-description"], textarea').first();
        await exampleInput.fill('MySQL demo example from qa:demo:mysql');
        await exampleModal.locator('.ant-modal-footer .ant-btn-primary').first().click();
        await sleep(600);
      },
    );

    await step(
      'knowledge-example-list',
      '知识中心样例 SQL',
      '切换到知识中心的样例 SQL 视图。',
      async () => {
        await openBrowserTab(window);
        const clicked = await tryClick(window.locator('[data-testid="studio-knowledge-example-sql"]').first(), 1200)
          || await clickByText(window, ['.knowledge-nav-item', 'button'], '样例SQL');
        if (!clicked) {
          throw new Error('Failed to open 知识中心 / 样例SQL.');
        }
        await sleep(500);
      },
    );

    await step(
      'history-restore-session',
      '恢复 AI 会话历史',
      '打开会话历史并恢复最新会话。',
      async () => {
        const historyBtn = window.locator('[data-testid="studio-top-history-button"]').first();
        if (!(await tryClick(historyBtn, 800))) {
          const clicked = await clickByText(window, ['.top-action-btn', 'button'], '历史');
          if (!clicked) {
            throw new Error('Failed to open 历史 menu.');
          }
        }
        const item = window.locator('.history-menu-panel .history-menu-item').first();
        await waitVisible(item, 8000);
        await item.click();
        await sleep(500);
      },
      { durationMs: 1500 },
    );

    await step(
      'table-data-filter-sort',
      '表数据筛选与排序',
      '双击表打开数据浏览，设置排序与非空筛选并应用。',
      async () => {
        await selectConnectionAndDatabase(window, runtime.connectionName, runtime.databaseName, runtime.connectionId);
        const row = await selectObjectRow(window, runtime.primaryTable);
        await row.dblclick();
        await waitVisible(window.locator('.table-data-center-pane'), 10_000);
        await window.locator('.table-data-title-main .table-data-icon-btn').first().click();
        await sleep(300);

        const filterBlock = window.locator('.table-data-filter-block').first();
        const sortBlock = window.locator('.table-data-filter-block').nth(1);
        await sortBlock.locator('.table-data-rule-add-btn').first().click();
        const sortItem = sortBlock.locator('.table-data-filter-item').last();
        await setAntSelectByText(window, sortItem, runtime.sortColumn, 0);
        await setAntSelectByText(window, sortItem, '降序', 1);

        await filterBlock.locator('.table-data-rule-add-btn').first().click();
        const filterItem = filterBlock.locator('.table-data-filter-item').last();
        await setAntSelectByText(window, filterItem, runtime.sortColumn, 0);
        await setAntSelectByText(window, filterItem, '非空', 1);

        await window.locator('.table-data-filter-actions .ant-btn-primary').first().click();
        await sleep(900);
      },
      { durationMs: 1700 },
    );

    await step(
      'table-structure-preview',
      '表结构与 SQL 预览',
      '从对象列表进入“编辑表结构”，仅展示结构与 SQL 预览。',
      async () => {
        await selectConnectionAndDatabase(window, runtime.connectionName, runtime.databaseName, runtime.connectionId);
        const row = await selectObjectRow(window, runtime.primaryTable);
        await row.click({ button: 'right' });
        await clickContextMenuAction(window, 'editTable', ['编辑表结构', 'Edit Table Structure']);
        await waitVisible(window.locator('.table-editor-preview-pane .detail-code-head').filter({ hasText: 'SQL 预览' }), 10_000);
      },
    );

    await step(
      'er-generate-snapshot-export',
      'ER 生成、快照与导出',
      `打开 ER 选表，优先使用 ${runtime.erTables.join('、')}，完成快照保存、重开与导出。`,
      async () => {
        await selectConnectionAndDatabase(window, runtime.connectionName, runtime.databaseName, runtime.connectionId);
        const erButton = window.locator('[data-testid="studio-browser-toolbar-open-er"]').first();
        if (!(await tryClick(erButton, 800))) {
          await window.locator('.center-toolbar-left .toolbar-icon-btn').nth(2).click();
        }
        const erModal = window.locator('.ant-modal').filter({
          has: window.locator('[data-testid="studio-er-select-modal"], .ant-modal-title:has-text("选择ER图目标表")'),
        }).first();
        await waitVisible(erModal, 10_000);

        const checkedValues = new Set();
        for (const tableName of runtime.erTables) {
          const box = erModal.locator(`[data-testid="studio-er-select-option-${sanitizeFilePart(tableName)}"], .ant-checkbox-wrapper`).filter({ hasText: tableName }).first();
          if (await box.count()) {
            await box.click();
            checkedValues.add(tableName);
          }
        }
        if (checkedValues.size < 3) {
          const fallbackBoxes = erModal.locator('.ant-checkbox-wrapper');
          const total = await fallbackBoxes.count();
          for (let i = 0; i < total && checkedValues.size < 3; i += 1) {
            await fallbackBoxes.nth(i).click();
            checkedValues.add(`fallback-${i + 1}`);
          }
        }
        await erModal.locator('.ant-modal-footer .ant-btn-primary').first().click();
        await waitVisible(window.locator('.er-diagram-pane'), 30_000);
        await sleep(800);

        const erActions = window.locator('.er-toolbar .ant-space').first().locator('.sql-action-icon-btn');
        const saveModal = await openErSnapshotSaveModal(window);
        snapshotName = `mysql-demo-snapshot-${Date.now()}`;
        await saveModal.locator('input').first().fill(snapshotName);
        await saveModal.locator('.ant-modal-footer .ant-btn-primary').first().click();
        await sleep(600);

        const topErButton = window.locator('[data-testid="studio-top-er-snapshot-button"]').first();
        if (!(await tryClick(topErButton, 800))) {
          const clicked = await clickByText(window, ['.top-action-btn', 'button'], 'ER图');
          if (!clicked) {
            throw new Error('Failed to open ER图 snapshot menu.');
          }
        }
        const snapshotItem = window.locator('.er-snapshot-menu-panel .history-menu-item').filter({ hasText: snapshotName }).first();
        await waitVisible(snapshotItem, 8000);
        await snapshotItem.click();
        await sleep(600);

        await erActions.nth(3).click();
        await sleep(500);
      },
      { durationMs: 1800 },
    );

    await step(
      'object-definition-view',
      '视图定义编辑',
      `切到 ${runtime.objectDefinitionDatabase} 库并打开视图定义编辑器（${runtime.objectDefinitionView || '首个视图'}）。`,
      async () => {
        await selectConnectionAndDatabase(window, runtime.connectionName, runtime.objectDefinitionDatabase, runtime.connectionId, true);
        await window.evaluate(async ({ connectionId, databaseName }) => {
          const root = document.querySelector('#app');
          const studioRoot = document.querySelector('.studio-root');
          const candidates = [
            root?.__vue_app__?._instance?.subTree?.component?.setupState,
            root?.__vue_app__?._instance?.proxy,
            root?.__vue_app__?._instance?.subTree?.component?.props?.controller,
            root?.__vue_app__?._container?._vnode?.component?.setupState,
            root?.__vue_app__?._container?._vnode?.component?.proxy,
            root?.__vue_app__?._container?._vnode?.component?.props?.controller,
            studioRoot?.__vueParentComponent?.setupState,
            studioRoot?.__vueParentComponent?.proxy,
            studioRoot?.__vueParentComponent?.ctx,
            studioRoot?.__vueParentComponent?.props?.controller,
          ].filter(Boolean);
          const shell = candidates.find((item) =>
            item?.workflow
            && item?.activeDatabaseMap
            && typeof item?.refreshCurrentPageObjects === 'function');
          if (!shell) {
            return;
          }
          shell.workflow.connectionId = connectionId;
          if (shell.activeDatabaseMap?.value) {
            shell.activeDatabaseMap.value = {
              ...shell.activeDatabaseMap.value,
              [connectionId]: databaseName,
            };
          } else if (shell.activeDatabaseMap) {
            shell.activeDatabaseMap = {
              ...shell.activeDatabaseMap,
              [connectionId]: databaseName,
            };
          }
          if (shell.currentObjectType?.value) {
            shell.currentObjectType.value = 'views';
          } else if (shell.currentObjectType) {
            shell.currentObjectType = 'views';
          }
          await shell.refreshCurrentPageObjects({ force: true });
        }, {
          connectionId: runtime.connectionId,
          databaseName: runtime.objectDefinitionDatabase,
        }).catch(() => {});
        await sleep(1500);
        await openDatabaseCategory(window, runtime.objectDefinitionDatabase, '视图');
        const targetView = runtime.objectDefinitionView;
        if (!targetView) {
          throw new Error('No view available for object definition demo.');
        }
        const objectSearch = window.locator('.center-toolbar-right input').first();
        if (await objectSearch.isVisible().catch(() => false)) {
          await objectSearch.fill(targetView);
          await sleep(1500);
        }
        const row = objectRowLocator(window, targetView, 'views');
        const candidate = await row.exact.isVisible().catch(() => false) ? row.exact : row.fallback;
        if (await candidate.isVisible().catch(() => false)) {
          await candidate.click({ button: 'right' });
          await clickContextMenuAction(window, 'editDefinition', ['编辑视图定义', 'Edit View Definition']);
          await waitVisible(window.locator('.object-definition-pane'), 12_000);
          return;
        }
        const newViewButton = window.locator('.center-toolbar-left .toolbar-icon-btn').first();
        if (await tryClick(newViewButton, 1200)) {
          await waitVisible(window.locator('.object-definition-pane'), 12_000);
          return;
        }
        await window.evaluate(async ({ connectionId, databaseName, objectName }) => {
          const root = document.querySelector('#app');
          const studioRoot = document.querySelector('.studio-root');
          const candidates = [
            root?.__vue_app__?._instance?.subTree?.component?.setupState,
            root?.__vue_app__?._instance?.proxy,
            root?.__vue_app__?._instance?.subTree?.component?.props?.controller,
            root?.__vue_app__?._container?._vnode?.component?.setupState,
            root?.__vue_app__?._container?._vnode?.component?.proxy,
            root?.__vue_app__?._container?._vnode?.component?.props?.controller,
            studioRoot?.__vueParentComponent?.setupState,
            studioRoot?.__vueParentComponent?.proxy,
            studioRoot?.__vueParentComponent?.ctx,
            studioRoot?.__vueParentComponent?.props?.controller,
          ].filter(Boolean);
          const target = candidates.find((item) =>
            typeof item?.openObjectDefinitionEditor === 'function'
            || typeof item?.objectDefinitionEditorModule?.openObjectDefinitionEditor === 'function');
          const openMethod = typeof target?.openObjectDefinitionEditor === 'function'
            ? target.openObjectDefinitionEditor.bind(target)
            : target?.objectDefinitionEditorModule?.openObjectDefinitionEditor?.bind(target.objectDefinitionEditorModule);
          if (typeof openMethod !== 'function') {
            throw new Error('openObjectDefinitionEditor is unavailable');
          }
          await openMethod(connectionId, databaseName, 'views', objectName);
        }, {
          connectionId: runtime.connectionId,
          databaseName: runtime.objectDefinitionDatabase,
          objectName: targetView,
        });
        await waitVisible(window.locator('.object-definition-pane'), 12_000);
      },
    );

    await step(
      'settings-i18n-theme',
      '设置中英文与主题切换',
      '打开设置切换英文与深色，再恢复中文和浅色。',
      async () => {
        const settingsButton = window.locator('[data-testid="studio-top-settings-button"]').first();
        if (!(await tryClick(settingsButton, 800))) {
          const clicked = await clickByText(window, ['.top-action-btn', 'button'], '设置');
          if (!clicked) {
            throw new Error('Failed to open 设置 modal.');
          }
        }
        const settingsModal = window.locator('.ant-modal').filter({
          has: window.locator('.ant-modal-title').filter({ hasText: /设置|Settings/i }),
        }).first();
        await waitVisible(settingsModal, 10_000);
        const appearanceTab = settingsModal.locator('.ant-tabs-tab, .ant-tabs-tab-btn').filter({ hasText: /界面设置|Appearance/i }).first();
        if (await appearanceTab.isVisible().catch(() => false)) {
          await appearanceTab.click();
        }
        await sleep(600);

        const hasLocaleTestId = (await settingsModal.locator('[data-testid="studio-settings-locale-select"]').count()) > 0;
        const localeItem = hasLocaleTestId
          ? settingsModal.locator('[data-testid="studio-settings-locale-select"]').first().locator('xpath=ancestor::*[contains(@class,"ant-form-item")]').first()
          : settingsModal.locator('.ant-form-item').filter({ hasText: /界面语言|Display Language/i }).first();
        await setAntSelectByText(window, localeItem, 'English');

        const themeSwitch = (await settingsModal.locator('[data-testid="studio-settings-theme-switch"]').count())
          ? settingsModal.locator('[data-testid="studio-settings-theme-switch"]').first()
          : (await settingsModal.locator('.ant-form-item').filter({ hasText: /深色模式|Dark Mode/i }).count())
            ? settingsModal.locator('.ant-form-item').filter({ hasText: /深色模式|Dark Mode/i }).first().locator('.ant-switch').first()
            : settingsModal.locator('.ant-switch').first();
        await themeSwitch.click();
        await sleep(400);

        await setAntSelectByText(window, localeItem, '中文');
        await themeSwitch.click();
        await sleep(300);

        const closeButton = settingsModal.locator('.ant-modal-footer .ant-btn').filter({ hasText: /取消|Cancel/i }).first();
        if (!(await tryClick(closeButton, 1200))) {
          await window.keyboard.press('Escape').catch(() => {});
        }
      },
      { durationMs: 1700 },
    );
  } finally {
    await electronApp.close().catch(() => {});
  }

  writeJson(stepsFile, steps);
  writeJson(resultFile, {
    generatedAt: new Date().toISOString(),
    outputDir: relativeFromRoot(outputDir),
    connectionId: runtime.connectionId,
    connectionName: runtime.connectionName,
    databaseName: runtime.databaseName,
    aiTable: runtime.aiTable,
    aiPrompt: runtime.aiPrompt,
    aiRetryPromptUsed,
    primaryTable: runtime.primaryTable,
    fallbackTableUsed: runtime.fallbackTableUsed,
    omittedFeatures: OMITTED_FEATURES,
    snapshotName,
    steps,
  });

  const summary = [
    '# MySQL Demo Summary',
    '',
    `- 连接: ${runtime.connectionName} (ID=${runtime.connectionId})`,
    `- 主数据库: ${runtime.databaseName}`,
    `- 主表示例: ${runtime.primaryTable}`,
    `- AI 对话目标表: ${runtime.aiTable}`,
    `- 是否回退主表: ${runtime.fallbackTableUsed ? '是' : '否'}`,
    `- AI Prompt: ${runtime.aiPrompt}`,
    `- 是否追加第二轮提示: ${aiRetryPromptUsed ? '是（使用 @distribution_callback_log 自动补全后再次提问）' : '否'}`,
    `- ER 快照名称: ${snapshotName || '-'}`,
    '',
    '## 未纳入主 GIF 的功能',
    ...OMITTED_FEATURES.map((item) => `- ${item}`),
    '',
    '## 产物',
    `- steps.json: ${relativeFromRoot(stepsFile)}`,
    `- screenshots: ${relativeFromRoot(screenshotsDir)}`,
    `- demo-results.json: ${relativeFromRoot(resultFile)}`,
    '',
  ].join('\n');
  writeText(summaryFile, `${summary}\n`);
  process.stdout.write(`[mysql-demo] steps generated: ${steps.length}\n`);
}

main().catch((error) => {
  process.stderr.write(`${error?.stack || error}\n`);
  process.exit(1);
});
