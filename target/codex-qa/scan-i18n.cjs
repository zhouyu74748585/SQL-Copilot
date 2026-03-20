const fs = require('fs');
const path = require('path');
const { _electron: electron } = require('playwright');

const ROOT = 'D:/Ideaprojects/SQL-Copilot';
const DESKTOP_DIR = path.join(ROOT, 'apps/desktop');
const DEMO_DB = 'D:/Ideaprojects/SQL-Copilot/target/codex-qa/sql-copilot-demo.sqlite';
const ELECTRON_BINARY = require(require.resolve('electron', { paths: [DESKTOP_DIR] }));
const LAUNCH_ENV = {
  ...process.env,
  ELECTRON_RENDERER_URL: 'http://127.0.0.1:8888',
  SQLCOPILOT_BACKEND_URL: 'http://127.0.0.1:18080',
  SQLCOPILOT_DATA_DIR: ROOT,
  NO_PROXY: 'localhost,127.0.0.1',
};

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
  DEMO_DB,
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

function hasCjk(text) {
  return /[\u4e00-\u9fff]/.test(text);
}

function shouldIgnore(text) {
  if (!text) return true;
  const normalized = text.trim();
  if (!normalized) return true;
  if (IGNORE_EXACT.has(normalized)) return true;
  if (/^\d+[\d\s:\-/.]*$/.test(normalized)) return true;
  if (/^[A-Za-z0-9_./:\- ()]+$/.test(normalized) && !hasCjk(normalized)) return true;
  return IGNORE_CONTAINS.some((part) => normalized.includes(part));
}

async function launchWindow() {
  const electronApp = await electron.launch({
    executablePath: ELECTRON_BINARY,
    args: ['electron/main.cjs'],
    cwd: DESKTOP_DIR,
    env: LAUNCH_ENV,
  });
  const window = await electronApp.firstWindow({ timeout: 60000 });
  await window.waitForLoadState('domcontentloaded');
  await window.waitForTimeout(4000);
  return { electronApp, window };
}

async function resetShell(window) {
  await window.evaluate(async ({ demoDb }) => {
    const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
    shell.setLocale('en-US');
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
    shell.erSnapshotSaveModalOpen = false;
    shell.groupModalOpen = false;
    shell.redisKeyModalOpen = false;
    shell.renameTableModalOpen = false;
    shell.tablePasteModalOpen = false;
    shell.tableCopyTaskModalOpen = false;
    shell.namespaceModalOpen = false;
    shell.createModalOpen = false;
    shell.activeWorkbenchTab = shell.browserTabKey;
    shell.browserDetailCollapsed = false;
    shell.workflow.connectionId = 5;
    shell.activeDatabaseMap[5] = demoDb;
    shell.currentObjectType = 'tables';
    await new Promise((resolve) => setTimeout(resolve, 300));
  }, { demoDb: DEMO_DB });
  await window.waitForTimeout(900);
}

async function snapshotStrings(window) {
  const raw = await window.evaluate(() => {
    const pieces = [];
    const push = (value, source) => {
      if (!value) return;
      const text = String(value).replace(/\s+/g, ' ').trim();
      if (!text) return;
      pieces.push({ text, source });
    };

    push(document.body ? document.body.innerText : '', 'body');

    document.querySelectorAll('button, [role="button"], .ant-btn, .workspace-tab, .tool-item, .knowledge-nav-item, .history-menu-item, .er-relation-context-action').forEach((node) => {
      push(node.innerText || node.textContent || '', 'button');
    });

    document.querySelectorAll('[title], [aria-label], input[placeholder], textarea[placeholder]').forEach((node) => {
      push(node.getAttribute('title'), 'title');
      push(node.getAttribute('aria-label'), 'aria');
      push(node.getAttribute('placeholder'), 'placeholder');
    });

    return pieces;
  });

  const hits = [];
  const seen = new Set();
  for (const item of raw) {
    const lines = String(item.text).split(/\n+/).map((line) => line.trim()).filter(Boolean);
    for (const line of lines) {
      if (!hasCjk(line) || shouldIgnore(line)) continue;
      const key = `${item.source}::${line}`;
      if (seen.has(key)) continue;
      seen.add(key);
      hits.push({ source: item.source, text: line });
    }
  }
  return hits;
}

async function collectScene(name, setup) {
  const { electronApp, window } = await launchWindow();
  try {
    await resetShell(window);
    await setup(window);
    await window.waitForTimeout(1600);
    const hits = await snapshotStrings(window);
    return { name, hits };
  } finally {
    await electronApp.close().catch(() => {});
  }
}

async function run() {
  const scenes = [];

  scenes.push(await collectScene('browser-tables', async (window) => {
    await window.evaluate(async ({ demoDb }) => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      await shell.refreshCurrentPageObjects({ force: true });
      await shell.selectObject(5, demoDb, 'tables', 'customers');
    }, { demoDb: DEMO_DB });
  }));

  scenes.push(await collectScene('browser-views', async (window) => {
    await window.evaluate(async ({ demoDb }) => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.currentObjectType = 'views';
      await shell.refreshCurrentPageObjects({ force: true });
    }, { demoDb: DEMO_DB });
  }));

  scenes.push(await collectScene('browser-queries', async (window) => {
    await window.evaluate(async ({ demoDb }) => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.currentObjectType = 'queries';
      await shell.refreshCurrentPageObjects({ force: true });
    }, { demoDb: DEMO_DB });
  }));

  scenes.push(await collectScene('query-workbench', async (window) => {
    await window.evaluate(async ({ demoDb }) => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.openAiQueryTab('');
      await new Promise((resolve) => setTimeout(resolve, 700));
      shell.activeQueryTab.connectionId = 5;
      shell.activeQueryTab.databaseName = demoDb;
      shell.activeQueryTab.title = 'Order Overview';
      shell.activeQueryTab.sqlText = 'SELECT order_no, customer_name, status, order_date, total_amount FROM v_order_summary ORDER BY order_date DESC;';
      await shell.executeSqlForTab(shell.activeQueryTab);
    }, { demoDb: DEMO_DB });
  }));

  scenes.push(await collectScene('table-data', async (window) => {
    await window.evaluate(async ({ demoDb }) => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      await shell.openTableDataTabByObject({ objectName: 'orders', objectType: 'tables', rowEstimate: 0, tableSize: '-', description: '-', vectorizeStatus: 'DONE' }, { connectionId: 5, databaseName: demoDb });
    }, { demoDb: DEMO_DB });
  }));

  scenes.push(await collectScene('table-editor', async (window) => {
    await window.evaluate(async ({ demoDb }) => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      await shell.openEditTableEditor(5, demoDb, 'orders');
    }, { demoDb: DEMO_DB });
  }));

  scenes.push(await collectScene('object-definition', async (window) => {
    await window.evaluate(async ({ demoDb }) => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      await shell.openNewObjectDefinitionEditor(5, demoDb, 'views');
      await new Promise((resolve) => setTimeout(resolve, 400));
      shell.handleObjectDefinitionSqlChange(shell.activeObjectDefinitionEditorTab, 'CREATE VIEW v_recent_orders AS\nSELECT order_no, customer_name, total_amount\nFROM v_order_summary\nWHERE order_date >= date(\'now\', \'-7 day\');');
    }, { demoDb: DEMO_DB });
  }));

  scenes.push(await collectScene('er-generated', async (window) => {
    await window.evaluate(async ({ demoDb }) => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      await shell.openErTableSelectModal();
      await new Promise((resolve) => setTimeout(resolve, 500));
      shell.erSelectTableValues.splice(0, shell.erSelectTableValues.length, 'customers', 'orders', 'order_items');
      await shell.confirmErTableSelection();
    }, { demoDb: DEMO_DB });
  }));

  scenes.push(await collectScene('history-dropdown', async (window) => {
    await window.locator('.top-action-btn').nth(0).click();
  }));

  scenes.push(await collectScene('er-snapshot-dropdown', async (window) => {
    await window.locator('.top-action-btn').nth(1).click();
  }));

  scenes.push(await collectScene('knowledge-example', async (window) => {
    await window.evaluate(async () => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      await shell.openKnowledgeNode('example-sql');
      await new Promise((resolve) => setTimeout(resolve, 700));
      if (shell.knowledgeExampleItems.length) shell.selectKnowledgeExample(shell.knowledgeExampleItems[0]);
    });
  }));

  scenes.push(await collectScene('knowledge-term', async (window) => {
    await window.evaluate(async () => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      await shell.openKnowledgeNode('terms');
      await new Promise((resolve) => setTimeout(resolve, 700));
      if (shell.knowledgeTermItems.length) shell.selectKnowledgeTerm(shell.knowledgeTermItems[0]);
    });
  }));

  scenes.push(await collectScene('settings-general', async (window) => {
    await window.evaluate(() => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.openAiConfigModal();
      shell.aiConfigActiveTab = 'general';
    });
  }));

  scenes.push(await collectScene('settings-model', async (window) => {
    await window.evaluate(() => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.openAiConfigModal();
      shell.aiConfigActiveTab = 'model';
    });
  }));

  scenes.push(await collectScene('settings-embedding', async (window) => {
    await window.evaluate(() => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.openAiConfigModal();
      shell.aiConfigActiveTab = 'embedding';
    });
  }));

  scenes.push(await collectScene('connection-modal-create', async (window) => {
    await window.evaluate(() => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.openCreateModal();
    });
  }));

  scenes.push(await collectScene('group-modal-create', async (window) => {
    await window.evaluate(() => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.openCreateGroupModal();
    });
  }));

  scenes.push(await collectScene('save-query-modal', async (window) => {
    await window.evaluate(async ({ demoDb }) => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.openAiQueryTab('');
      await new Promise((resolve) => setTimeout(resolve, 700));
      shell.activeQueryTab.connectionId = 5;
      shell.activeQueryTab.databaseName = demoDb;
      shell.activeQueryTab.sqlText = 'select 1';
      shell.openSaveQueryModal(shell.activeQueryTab);
    }, { demoDb: DEMO_DB });
  }));

  scenes.push(await collectScene('save-example-modal', async (window) => {
    await window.evaluate(async ({ demoDb }) => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.openAiQueryTab('');
      await new Promise((resolve) => setTimeout(resolve, 700));
      shell.activeQueryTab.connectionId = 5;
      shell.activeQueryTab.databaseName = demoDb;
      shell.activeQueryTab.sqlText = 'select 1';
      shell.openSaveQueryAsExampleModal(shell.activeQueryTab);
    }, { demoDb: DEMO_DB });
  }));

  scenes.push(await collectScene('er-snapshot-save-modal', async (window) => {
    await window.evaluate(async ({ demoDb }) => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      await shell.openErTableSelectModal();
      await new Promise((resolve) => setTimeout(resolve, 500));
      shell.erSelectTableValues.splice(0, shell.erSelectTableValues.length, 'customers', 'orders', 'order_items');
      await shell.confirmErTableSelection();
      await new Promise((resolve) => setTimeout(resolve, 600));
      shell.openErSnapshotSaveModal();
    }, { demoDb: DEMO_DB });
  }));

  scenes.push(await collectScene('redis-browser', async (window) => {
    await window.evaluate(() => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.activeWorkbenchTab = shell.browserTabKey;
      shell.workflow.connectionId = 4;
      shell.activeDatabaseMap[4] = '0';
      shell.currentObjectType = 'tables';
    });
    await window.waitForTimeout(1500);
  }));

  scenes.push(await collectScene('redis-key-modal', async (window) => {
    await window.evaluate(() => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.openCreateRedisKeyModal();
    });
  }));

  scenes.push(await collectScene('rename-table-modal', async (window) => {
    await window.evaluate(({ demoDb }) => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.renameTableModalOpen = true;
      shell.renameTableForm.connectionId = 5;
      shell.renameTableForm.databaseName = demoDb;
      shell.renameTableForm.sourceTableName = 'orders';
      shell.renameTableForm.targetTableName = 'orders_archive';
    }, { demoDb: DEMO_DB });
  }));

  scenes.push(await collectScene('table-copy-modal', async (window) => {
    await window.evaluate(({ demoDb }) => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.tablePasteModalOpen = true;
      shell.tablePasteForm.sourceConnectionId = 5;
      shell.tablePasteForm.sourceDatabaseName = demoDb;
      shell.tablePasteForm.sourceTableName = 'orders';
      shell.tablePasteForm.sourceDbType = 'SQLITE';
      shell.tablePasteForm.targetConnectionId = 5;
      shell.tablePasteForm.targetDatabaseName = demoDb;
      shell.tablePasteForm.targetTableName = 'orders_copy';
      shell.tablePasteForm.copyData = true;
    }, { demoDb: DEMO_DB });
  }));

  scenes.push(await collectScene('vectorize-overview-modal', async (window) => {
    await window.evaluate(() => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.vectorizeOverviewModalOpen = true;
      shell.vectorizeOverviewData = {
        totalTables: 3,
        completedTables: 3,
        runningTables: 0,
        failedTables: 0,
        pendingTables: 0,
        totalChunks: 12,
        lastFullVectorizeProvider: 'LOCAL_ONNX',
        lastFullVectorizeAt: Date.now(),
        tableItems: [
          { tableName: 'customers', status: 'DONE', chunkCount: 3, updatedAt: Date.now(), message: '' },
          { tableName: 'orders', status: 'DONE', chunkCount: 5, updatedAt: Date.now(), message: '' },
        ],
      };
    });
  }));

  scenes.push(await collectScene('namespace-modal', async (window) => {
    await window.evaluate(() => {
      const shell = document.querySelector('#app').__vue_app__._instance.subTree.component.setupState;
      shell.namespaceModalOpen = true;
      shell.namespaceForm.mode = 'create';
      shell.namespaceForm.connectionId = 2;
      shell.namespaceForm.databaseName = 'demo';
      shell.namespaceForm.namespaceLabel = 'Schema';
      shell.namespaceForm.targetNamespaceName = 'analytics';
    });
  }));

  const summary = scenes.map((scene) => ({ name: scene.name, count: scene.hits.length, hits: scene.hits }));
  fs.writeFileSync(path.join(ROOT, 'target/codex-qa/i18n-scan-results.json'), JSON.stringify(summary, null, 2), 'utf8');
  console.log(JSON.stringify(summary, null, 2));
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
