import {message} from 'ant-design-vue';
import {getApi, postApi} from '../../../api/client';
import type {TableDetailVO} from '../../../types';
import type {StudioRuntime} from './useStudioRuntime';

type TableEditorTab = StudioRuntime['tableEditorTabs']['value'][number];
type TableEditorDraft = NonNullable<TableEditorTab['draft']>;

type TableEditorChangePayload = {
  draft: TableEditorDraft;
  previewSql: string;
  canSave: boolean;
  dirty: boolean;
};

export interface TableEditorModule {
  closeTableEditorTab: (tabKey: string) => void;
  openNewTableEditor: () => Promise<void>;
  openEditTableEditor: (connectionId: number, databaseName: string, tableName: string) => Promise<void>;
  handleTableEditorChange: (tab: TableEditorTab, payload: TableEditorChangePayload) => void;
  handleTableEditorSave: (tab: TableEditorTab) => Promise<void>;
  handleTableEditorRefresh: () => void;
  handleTableEditorExecute: () => Promise<void>;
  confirmTruncateTable: () => Promise<void>;
  confirmDropTable: () => Promise<void>;
  refreshSchemaMetadata: (connectionId: number, databaseName: string) => Promise<void>;
}

export function useTableEditorModule(runtime: StudioRuntime): TableEditorModule {
  function closeTableEditorTab(tabKey: string) {
    const index = runtime.tableEditorTabs.value.findIndex((item) => item.key === tabKey);
    if (index < 0) {
      return;
    }
    const tabs = [...runtime.tableEditorTabs.value];
    tabs.splice(index, 1);
    runtime.tableEditorTabs.value = tabs;
    if (runtime.activeWorkbenchTab.value === tabKey) {
      runtime.activeWorkbenchTab.value = tabs[index]?.key || tabs[index - 1]?.key || runtime.browserTabKey;
      runtime.ensureActiveWorkbenchTab();
    }
  }

  async function openNewTableEditor() {
    const connectionId = runtime.workflow.connectionId;
    const databaseName = runtime.getActiveDatabaseName(connectionId);
    if (!connectionId || !databaseName) {
      message.error('请先选择连接和数据库');
      return;
    }
    const dbType = runtime.connections.value.find((item) => item.id === connectionId)?.dbType ?? 'MYSQL';
    const now = Date.now();
    const key = `table-editor-${now}`;
    const tab: TableEditorTab = {
      key,
      title: '新建表',
      connectionId,
      databaseName,
      tableName: '',
      dbType,
      mode: 'create',
      tableDetail: null,
      draft: null,
      baselineDraft: null,
      previewSql: '',
      canSave: false,
      dirty: false,
      loading: false,
      saved: false,
      createdAt: now,
      updatedAt: now,
    };
    runtime.tableEditorTabs.value = [...runtime.tableEditorTabs.value, tab];
    runtime.activeWorkbenchTab.value = key;
  }

  async function openEditTableEditor(connectionId: number, databaseName: string, tableName: string) {
    const existingTab = runtime.tableEditorTabs.value.find(
      (item) => item.connectionId === connectionId && item.databaseName === databaseName && item.tableName === tableName,
    );
    if (existingTab) {
      runtime.activeWorkbenchTab.value = existingTab.key;
      return;
    }
    const dbType = runtime.connections.value.find((item) => item.id === connectionId)?.dbType ?? 'MYSQL';
    const now = Date.now();
    const key = `table-editor-${now}`;
    const tab: TableEditorTab = {
      key,
      title: tableName,
      connectionId,
      databaseName,
      tableName,
      dbType,
      mode: 'edit',
      tableDetail: null,
      draft: null,
      baselineDraft: null,
      previewSql: '',
      canSave: false,
      dirty: false,
      loading: true,
      saved: false,
      createdAt: now,
      updatedAt: now,
    };
    runtime.tableEditorTabs.value = [...runtime.tableEditorTabs.value, tab];
    runtime.activeWorkbenchTab.value = key;
    try {
      const detail = await getApi<TableDetailVO>(
        `/api/schema/tableDetail?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}&tableName=${encodeURIComponent(tableName)}`,
      );
      const targetTab = runtime.tableEditorTabs.value.find((item) => item.key === key);
      if (targetTab) {
        targetTab.tableDetail = detail;
        targetTab.loading = false;
        targetTab.updatedAt = Date.now();
      }
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(`加载表结构失败: ${msg}`);
      closeTableEditorTab(key);
    }
  }

  function handleTableEditorChange(tab: TableEditorTab, payload: TableEditorChangePayload) {
    tab.draft = payload.draft;
    tab.previewSql = payload.previewSql;
    tab.canSave = payload.canSave;
    tab.dirty = payload.dirty;
    tab.updatedAt = Date.now();
  }

  async function handleTableEditorSave(tab: TableEditorTab) {
    tab.saved = true;
    tab.updatedAt = Date.now();
    message.success(tab.mode === 'create' ? '表创建成功' : '表结构更新成功');
    await refreshSchemaMetadata(tab.connectionId, tab.databaseName);
    await runtime.refreshCurrentObjects();
  }

  function handleTableEditorRefresh() {
    if (runtime.activeTableEditorTab.value) {
      runtime.activeTableEditorTab.value.updatedAt = Date.now();
    }
  }

  async function refreshSchemaMetadata(connectionId: number, databaseName: string) {
    try {
      await postApi('/api/schema/cache/refresh', {
        connectionId,
        databaseName,
      });
    } catch (e) {
      console.warn('刷新缓存失败:', e);
    }
  }

  async function confirmTruncateTable() {
    const tableName = runtime.truncateTableName.value;
    const dbName = runtime.contextMenu.databaseName;
    const connId = runtime.contextMenu.connectionId;
    runtime.truncateTableModalOpen.value = false;

    try {
      await postApi('/api/schema/table/truncate', {
        connectionId: connId,
        databaseName: dbName,
        tableName,
      });
      message.success('表数据已清空');
      await refreshSchemaMetadata(connId, dbName);
      await runtime.refreshCurrentObjects();
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(`清空失败: ${msg}`);
    }
  }

  async function confirmDropTable() {
    const tableName = runtime.dropTableName.value;
    const dbName = runtime.contextMenu.databaseName;
    const connId = runtime.contextMenu.connectionId;
    runtime.dropTableModalOpen.value = false;

    try {
      await postApi('/api/schema/table/drop', {
        connectionId: connId,
        databaseName: dbName,
        tableName,
      });
      message.success('表已删除');
      await refreshSchemaMetadata(connId, dbName);
      await runtime.refreshCurrentObjects();
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(`删除失败: ${msg}`);
    }
  }

  async function handleTableEditorExecute() {
    const tab = runtime.activeTableEditorTab.value;
    if (!tab || !tab.canSave || !tab.dirty) {
      if (!tab?.canSave) {
        message.warning('请先完善表结构定义');
      } else {
        message.info('未检测到结构变更，无需执行');
      }
      return;
    }
    const ddl = tab.previewSql?.trim();
    if (!ddl || ddl.startsWith('--')) {
      message.warning('SQL 预览为空，无法执行');
      return;
    }

    runtime.tableEditorSaving.value = true;
    try {
      await postApi(tab.mode === 'create' ? '/api/schema/table/create' : '/api/schema/table/alter', {
        connectionId: tab.connectionId,
        databaseName: tab.databaseName,
        tableName: tab.draft?.tableName || tab.tableName,
        tableComment: tab.draft?.tableComment || undefined,
        columns: tab.draft?.columns || [],
        indexes: tab.draft?.indexes || [],
        ddl,
      });
      const nextTableName = tab.draft?.tableName || tab.tableName;
      await refreshSchemaMetadata(tab.connectionId, tab.databaseName);
      try {
        await postApi('/api/rag/table/manual', {
          connectionId: tab.connectionId,
          databaseName: tab.databaseName,
          tableName: nextTableName,
        });
      } catch (e) {
        console.warn('向量化请求失败:', e);
      }
      message.success(tab.mode === 'create' ? '表创建成功' : '表结构更新成功');
      tab.saved = true;
      tab.dirty = false;
      tab.updatedAt = Date.now();
      await runtime.refreshCurrentObjects();
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(`执行失败: ${msg}`);
    } finally {
      runtime.tableEditorSaving.value = false;
    }
  }

  return {
    closeTableEditorTab,
    openNewTableEditor,
    openEditTableEditor,
    handleTableEditorChange,
    handleTableEditorSave,
    handleTableEditorRefresh,
    handleTableEditorExecute,
    confirmTruncateTable,
    confirmDropTable,
    refreshSchemaMetadata,
  };
}
