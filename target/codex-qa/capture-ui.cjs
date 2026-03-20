const path = require('path');
const { _electron: electron } = require('playwright');

const ROOT = 'D:/Ideaprojects/SQL-Copilot';
const DESKTOP_DIR = path.join(ROOT, 'apps/desktop');
const DEMO_DB = 'D:/Ideaprojects/SQL-Copilot/target/codex-qa/sql-copilot-demo.sqlite';
const SCREEN_DIR = path.join(ROOT, 'docs/img');
const ELECTRON_BINARY = require(require.resolve('electron', { paths: [DESKTOP_DIR] }));
const LAUNCH_ENV = {
  ...process.env,
  ELECTRON_RENDERER_URL: 'http://127.0.0.1:8888',
  SQLCOPILOT_BACKEND_URL: 'http://127.0.0.1:18080',
  SQLCOPILOT_DATA_DIR: ROOT,
  NO_PROXY: 'localhost,127.0.0.1',
};

async function launchWindow() {
  const electronApp = await electron.launch({
    executablePath: ELECTRON_BINARY,
    args: ['electron/main.cjs'],
    cwd: DESKTOP_DIR,
    env: LAUNCH_ENV,
  });
  const window = await electronApp.firstWindow({ timeout: 60000 });
  await window.waitForLoadState('domcontentloaded');
  await window.waitForTimeout(4500);
  return { electronApp, window };
}

async function resetWorkspace(window) {
  await window.evaluate(async ({ demoDb }) => {
    const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
    shell.setLocale('zh-CN');
    if (shell.isDarkTheme) shell.toggleTheme();
    shell.queryTabs.splice(0);
    shell.erTabs.splice(0);
    shell.tableEditorTabs.splice(0);
    shell.tableDataTabs.splice(0);
    shell.objectDefinitionEditorTabs.splice(0);
    shell.knowledgeTabs.splice(0);
    shell.aiConfigModalOpen = false;
    shell.vectorizeOverviewModalOpen = false;
    shell.saveQueryModalOpen = false;
    shell.saveQueryAsExampleModalOpen = false;
    shell.historyKeywordInput = '';
    shell.erSnapshotKeywordInput = '';
    shell.activeWorkbenchTab = shell.browserTabKey;
    shell.browserDetailCollapsed = false;
    shell.workflow.connectionId = 5;
    shell.activeDatabaseMap[5] = demoDb;
    shell.currentObjectType = 'tables';
    await new Promise((resolve) => setTimeout(resolve, 500));
  }, { demoDb: DEMO_DB });
  await window.waitForTimeout(1200);
}

async function ensureGroupExpanded(window) {
  const items = await window.locator('.connection-tree .tree-title-text').count();
  if (items < 3) {
    await window.locator('.connection-tree .ant-tree-switcher').nth(1).click();
    await window.waitForTimeout(1200);
  }
}

async function captureObjectBrowser(window) {
  await resetWorkspace(window);
  await ensureGroupExpanded(window);
  await window.evaluate(async ({ demoDb }) => {
    const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
    shell.activeWorkbenchTab = shell.browserTabKey;
    shell.workflow.connectionId = 5;
    shell.activeDatabaseMap[5] = demoDb;
    shell.currentObjectType = 'tables';
    await shell.refreshCurrentPageObjects({ force: true });
    await shell.selectObject(5, demoDb, 'tables', 'customers');
  }, { demoDb: DEMO_DB });
  await window.waitForTimeout(2500);
  await window.screenshot({ path: path.join(SCREEN_DIR, 'object-browser.png') });
}

async function captureQueryWorkbench(window) {
  await resetWorkspace(window);
  await window.evaluate(async ({ demoDb }) => {
    const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
    shell.workflow.connectionId = 5;
    shell.activeDatabaseMap[5] = demoDb;
    shell.openAiQueryTab('');
    await new Promise((resolve) => setTimeout(resolve, 700));
    shell.activeQueryTab.connectionId = 5;
    shell.activeQueryTab.databaseName = demoDb;
    shell.activeQueryTab.title = 'Order Overview';
    shell.activeQueryTab.sqlText = 'SELECT order_no, customer_name, status, order_date, total_amount FROM v_order_summary ORDER BY order_date DESC;';
    await shell.executeSqlForTab(shell.activeQueryTab);
  }, { demoDb: DEMO_DB });
  await window.waitForTimeout(3500);
  await window.screenshot({ path: path.join(SCREEN_DIR, 'query-workbench.png') });
}

async function captureHistoryMenu(window) {
  await resetWorkspace(window);
  await window.evaluate(async ({ demoDb }) => {
    const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
    shell.workflow.connectionId = 5;
    shell.activeDatabaseMap[5] = demoDb;
    shell.openAiQueryTab('');
    await new Promise((resolve) => setTimeout(resolve, 700));
    shell.activeQueryTab.connectionId = 5;
    shell.activeQueryTab.databaseName = demoDb;
    shell.activeQueryTab.title = 'Order Overview';
  }, { demoDb: DEMO_DB });
  await window.waitForTimeout(2000);
  await window.locator('.top-action-btn').nth(0).click();
  await window.waitForTimeout(1500);
  await window.screenshot({ path: path.join(SCREEN_DIR, 'history-snapshots.png') });
}

async function captureDataBrowser(window) {
  await resetWorkspace(window);
  await window.evaluate(async ({ demoDb }) => {
    const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
    await shell.openTableDataTabByObject({
      objectName: 'orders',
      objectType: 'tables',
      rowEstimate: 0,
      tableSize: '-',
      description: '-',
      vectorizeStatus: 'DONE',
    }, {
      connectionId: 5,
      databaseName: demoDb,
    });
  }, { demoDb: DEMO_DB });
  await window.waitForTimeout(3500);
  await window.screenshot({ path: path.join(SCREEN_DIR, 'data-browser.png') });
}

async function captureTableEditor(window) {
  await resetWorkspace(window);
  await window.evaluate(async ({ demoDb }) => {
    const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
    await shell.openEditTableEditor(5, demoDb, 'orders');
  }, { demoDb: DEMO_DB });
  await window.waitForTimeout(3000);
  const firstRow = window.locator('.columns-table .column-name-cell').first();
  if (await firstRow.count()) {
    await firstRow.click();
    await window.waitForTimeout(500);
  }
  await window.screenshot({ path: path.join(SCREEN_DIR, 'table-editor.png') });
}

async function captureObjectDefinitionEditor(window) {
  await resetWorkspace(window);
  await window.evaluate(async ({ demoDb }) => {
    const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
    await shell.openNewObjectDefinitionEditor(5, demoDb, 'views');
    await new Promise((resolve) => setTimeout(resolve, 400));
    shell.handleObjectDefinitionSqlChange(shell.activeObjectDefinitionEditorTab, 'CREATE VIEW v_recent_orders AS\nSELECT order_no, customer_name, total_amount\nFROM v_order_summary\nWHERE order_date >= date(\'now\', \'-7 day\');');
  }, { demoDb: DEMO_DB });
  await window.waitForTimeout(2500);
  await window.screenshot({ path: path.join(SCREEN_DIR, 'object-definition-editor.png') });
}

async function captureErDiagram(window) {
  await resetWorkspace(window);
  await window.evaluate(async ({ demoDb }) => {
    const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
    await shell.openErSnapshot({
      id: 2,
      connectionId: 5,
      databaseName: demoDb,
      snapshotName: 'Order Domain Snapshot',
      tableCount: 3,
      modelName: 'local-demo',
      createdAt: Date.now(),
      updatedAt: Date.now(),
    });
  }, { demoDb: DEMO_DB });
  await window.waitForTimeout(3500);
  await window.screenshot({ path: path.join(SCREEN_DIR, 'er-diagram.png') });
}

async function captureKnowledgeCenter(window) {
  await resetWorkspace(window);
  await window.evaluate(async () => {
    const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
    await shell.openKnowledgeNode('example-sql');
    await new Promise((resolve) => setTimeout(resolve, 700));
    if (shell.knowledgeExampleItems.length) {
      shell.selectKnowledgeExample(shell.knowledgeExampleItems[0]);
    }
  });
  await window.waitForTimeout(2500);
  await window.screenshot({ path: path.join(SCREEN_DIR, 'knowledge-center.png') });
}

async function captureRedisBrowser(window) {
  await resetWorkspace(window);
  await ensureGroupExpanded(window);
  await window.evaluate(() => {
    const target = Array.from(document.querySelectorAll('.connection-tree .tree-title-text')).find((node) => (node.textContent || '').includes('redis'));
    target?.closest('.ant-tree-node-content-wrapper')?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
  await window.waitForTimeout(3000);
  await window.screenshot({ path: path.join(SCREEN_DIR, 'redis-browser.png') });
}

async function captureSettings(window) {
  await resetWorkspace(window);
  await window.evaluate(() => {
    const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
    shell.openAiConfigModal();
    shell.aiConfigActiveTab = 'general';
  });
  await window.waitForTimeout(1800);
  await window.screenshot({ path: path.join(SCREEN_DIR, 'settings-appearance.png') });
}

async function runCapture(name, fn) {
  const { electronApp, window } = await launchWindow();
  try {
    await fn(window);
    console.log(`captured:${name}`);
  } finally {
    await electronApp.close().catch(() => {});
  }
}

(async () => {
  await runCapture('object-browser', captureObjectBrowser);
  await runCapture('query-workbench', captureQueryWorkbench);
  await runCapture('history-snapshots', captureHistoryMenu);
  await runCapture('data-browser', captureDataBrowser);
  await runCapture('table-editor', captureTableEditor);
  await runCapture('object-definition-editor', captureObjectDefinitionEditor);
  await runCapture('er-diagram', captureErDiagram);
  await runCapture('knowledge-center', captureKnowledgeCenter);
  await runCapture('redis-browser', captureRedisBrowser);
  await runCapture('settings-appearance', captureSettings);
})();
