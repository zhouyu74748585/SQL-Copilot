import type {Ref} from 'vue';
import {getApi, postApi} from '../../../../api/client';
import {message} from 'ant-design-vue';
import type {
  SavedQuerySaveReq,
  SavedQueryVO,
} from '../../../../types';
import {browserTabKey} from './constants';
import {emptyManualChartConfig} from './charts';
import {buildObjectQuerySql} from './utils';
import type {
  ObjectRow,
  QueryWorkspaceTab,
  RequestAbortReason,
} from './types';

interface QueryWorkbenchHelperContext {
  activeDatabaseMap: Ref<Record<number, string>>;
  activeWorkbenchTab: Ref<string>;
  aiRequestAbortControllerMap: Map<string, AbortController>;
  aiRequestAbortReasonMap: Map<string, RequestAbortReason>;
  applySessionTitle: (tab: QueryWorkspaceTab) => void;
  browserNavMode: Ref<'connections' | 'knowledge'>;
  ensureConnection: () => void;
  executeSqlForTab: (tab: QueryWorkspaceTab, sqlOverride?: string, options?: {silentSuccess?: boolean}) => Promise<boolean>;
  getActiveDatabaseName: (connectionId: number) => string;
  getConversationMemoryEnabled: () => boolean;
  getKvQueryTemplate: () => string;
  getModelValues: () => string[];
  isKvConnectionId: (connectionId: number) => boolean;
  knowledgeTabs: Ref<Array<{key: string}>>;
  memoryTabs: Ref<Array<{key: string}>>;
  objectDefinitionEditorTabs: Ref<Array<{key: string}>>;
  persistSessionTitleOverrides: () => void;
  prepareConnectionTreeData: (connectionId: number) => Promise<void>;
  queryDbTypeByConnectionId: (connectionId: number) => string;
  queryTabs: Ref<QueryWorkspaceTab[]>;
  runSafely: (task: () => Promise<void>) => Promise<void>;
  saveQueryModalOpen: Ref<boolean>;
  saveQuerySubmitting: Ref<boolean>;
  saveQueryTitle: Ref<string>;
  savedQueryCache: Ref<Record<string, SavedQueryVO[]>>;
  sessionTitleOverrideKey: (tab: {connectionId: number; sessionId: string}) => string;
  sessionTitleOverrides: Ref<Record<string, string>>;
  sqlExecutionAbortControllerMap: Map<string, AbortController>;
  sqlExecutionAbortReasonMap: Map<string, RequestAbortReason>;
  tableDataTabs: Ref<Array<{key: string}>>;
  tableEditorTabs: Ref<Array<{key: string}>>;
  warmupTableSuggestions: (tab: QueryWorkspaceTab | null) => Promise<void>;
  workflow: {
    connectionId: number;
    prompt: string;
    sqlText: string;
  };
  erTabs: Ref<Array<{key: string}>>;
}

interface CreateQueryTabOptions {
  title?: string;
  connectionId?: number;
  databaseName?: string;
  prompt?: string;
  sqlText?: string;
  savedQueryId?: number;
  savedQueryEditMode?: boolean;
  detailOutputOverride?: boolean | null;
}

export function createQueryWorkbenchHelpers(ctx: QueryWorkbenchHelperContext) {
  function openAiQueryTab(initialPrompt = '') {
    return createQueryTab({
      prompt: initialPrompt,
      sqlText: ctx.workflow.sqlText,
    });
  }

  function openQueryTabByObject(record: ObjectRow, autoExecute = false) {
    if (record.objectType !== 'tables' && record.objectType !== 'views') {
      return;
    }
    const connectionId = ctx.workflow.connectionId;
    const isKv = ctx.isKvConnectionId(connectionId);
    const sql = isKv
      ? ctx.getKvQueryTemplate()
      : buildObjectQuerySql(record.objectName, ctx.queryDbTypeByConnectionId(connectionId));
    const prompt = isKv ? `查询 ${record.objectName}` : `查询 ${record.objectName} 最近数据`;
    ctx.workflow.sqlText = sql;
    ctx.workflow.prompt = prompt;
    const tab = openAiQueryTab(prompt);
    if (!tab) {
      return;
    }
    tab.sqlText = sql;
    tab.prompt = '';
    tab.databaseName = ctx.getActiveDatabaseName(tab.connectionId);
    tab.updatedAt = Date.now();
    if (autoExecute) {
      void ctx.runSafely(async () => {
        await ctx.executeSqlForTab(tab, sql);
      });
    }
    return tab;
  }

  function createQueryTab(options?: CreateQueryTabOptions) {
    ctx.ensureConnection();
    const connectionId = options?.connectionId ?? ctx.workflow.connectionId;
    const now = Date.now();
    const databaseName = options?.databaseName ?? ctx.getActiveDatabaseName(connectionId);
    const models = ctx.getModelValues();
    const tab: QueryWorkspaceTab = {
      key: `query-${now}-${Math.round(Math.random() * 1000)}`,
      title: options?.title || '新的查询',
      connectionId,
      databaseName,
      savedQueryId: options?.savedQueryId,
      savedQueryEditMode: options?.savedQueryEditMode === true,
      sessionId: `session-${now}`,
      prompt: options?.prompt ?? '',
      sqlText: options?.sqlText ?? ctx.workflow.sqlText,
      riskAckToken: '',
      riskInfo: null,
      executeResult: null,
      explainResult: null,
      selectedAiModel: models[0] ?? '',
      autoMode: true,
      autoExecute: false,
      aiGenerating: false,
      sqlExecuting: false,
      executingStatementIndex: null,
      selectedSqlText: '',
      chatMessages: [],
      resultTableRows: [],
      resultTableColumns: [],
      lastExecuteFailed: false,
      lastExecuteErrorMessage: '',
      lastFailedSqlText: '',
      resultViewMode: 'table',
      manualChartConfig: emptyManualChartConfig(),
      activeChartConfig: null,
      chartImageDataUrl: '',
      chartImageCacheKey: '',
      chartReadonly: false,
      statementResults: [],
      activeStatementResultKey: '',
      createdAt: now,
      updatedAt: now,
      conversationMemoryEnabled: ctx.getConversationMemoryEnabled(),
      sqlMemoryEnabled: true,
      detailOutputOverride: options?.detailOutputOverride ?? null,
      lastTokenEstimate: 0,
      lastPromptBudget: null,
      lastRequestPromptTokens: 0,
      lastRequestCompletionTokens: 0,
      lastRequestTotalTokens: 0,
      lastTurnContentTokens: 0,
    };
    if (options?.title?.trim()) {
      ctx.sessionTitleOverrides.value = {
        ...ctx.sessionTitleOverrides.value,
        [ctx.sessionTitleOverrideKey(tab)]: options.title.trim(),
      };
      ctx.persistSessionTitleOverrides();
    }
    ctx.applySessionTitle(tab);
    ctx.queryTabs.value = [...ctx.queryTabs.value, tab];
    ctx.activeWorkbenchTab.value = tab.key;
    void ctx.runSafely(async () => {
      await ctx.prepareConnectionTreeData(tab.connectionId);
      tab.databaseName = tab.databaseName || ctx.getActiveDatabaseName(tab.connectionId);
      await ctx.warmupTableSuggestions(tab);
    });
    return tab;
  }

  function savedQueryCacheKey(connectionId: number, databaseName?: string) {
    return `${connectionId}|${databaseName || ''}`;
  }

  function savedQueriesByDatabase(connectionId: number, databaseName?: string) {
    return ctx.savedQueryCache.value[savedQueryCacheKey(connectionId, databaseName)] ?? [];
  }

  async function loadSavedQueries(connectionId: number, databaseName: string, options?: {syncCache?: boolean}) {
    const normalizedDatabaseName = databaseName || '';
    const list = await getApi<SavedQueryVO[]>(
      `/api/editor/saved-query/list?connectionId=${connectionId}&databaseName=${encodeURIComponent(normalizedDatabaseName)}`,
    );
    if (options?.syncCache !== false) {
      ctx.savedQueryCache.value = {
        ...ctx.savedQueryCache.value,
        [savedQueryCacheKey(connectionId, normalizedDatabaseName)]: list,
      };
    }
    return list;
  }

  function openSaveQueryModal(tab: QueryWorkspaceTab) {
    if (!tab.sqlText.trim()) {
      message.warning('请先输入要保存的 SQL');
      return;
    }
    ctx.saveQueryTitle.value = tab.savedQueryEditMode
      ? tab.title
      : (tab.title.includes('新的查询') ? '' : tab.title);
    ctx.saveQueryModalOpen.value = true;
  }

  async function saveCurrentQuery(tab: QueryWorkspaceTab) {
    const title = ctx.saveQueryTitle.value.trim();
    if (!title) {
      message.warning('保存查询名称不能为空');
      return null;
    }
    const sqlText = tab.sqlText.trim();
    if (!sqlText) {
      message.warning('请先输入要保存的 SQL');
      return null;
    }
    ctx.saveQuerySubmitting.value = true;
    try {
      const wasEditing = tab.savedQueryEditMode && !!tab.savedQueryId;
      const saved = wasEditing
        ? await postApi<SavedQueryVO>('/api/editor/saved-query/update', {
          id: tab.savedQueryId,
          connectionId: tab.connectionId,
          databaseName: tab.databaseName || '',
          title,
          sqlText,
        })
        : await postApi<SavedQueryVO>('/api/editor/saved-query/save', {
          connectionId: tab.connectionId,
          databaseName: tab.databaseName || '',
          title,
          sqlText,
        } satisfies SavedQuerySaveReq);
      ctx.sessionTitleOverrides.value = {
        ...ctx.sessionTitleOverrides.value,
        [ctx.sessionTitleOverrideKey(tab)]: title,
      };
      ctx.persistSessionTitleOverrides();
      tab.title = title;
      tab.savedQueryId = saved.id;
      tab.savedQueryEditMode = true;
      tab.databaseName = saved.databaseName || tab.databaseName;
      ctx.saveQueryModalOpen.value = false;
      await loadSavedQueries(tab.connectionId, tab.databaseName || '');
      message.success(wasEditing ? '查询已更新' : '查询已保存');
      return saved;
    } finally {
      ctx.saveQuerySubmitting.value = false;
    }
  }

  async function openSavedQueryTab(savedQuery: SavedQueryVO) {
    const normalizedDatabaseName = savedQuery.databaseName || '';
    const existingTab = ctx.queryTabs.value.find((item) => item.savedQueryId === savedQuery.id);
    if (existingTab) {
      ctx.activeWorkbenchTab.value = existingTab.key;
      existingTab.sqlText = savedQuery.sqlText;
      existingTab.title = savedQuery.title;
      existingTab.updatedAt = Date.now();
      return existingTab;
    }
    ctx.workflow.connectionId = savedQuery.connectionId;
    ctx.activeDatabaseMap.value = {
      ...ctx.activeDatabaseMap.value,
      [savedQuery.connectionId]: normalizedDatabaseName,
    };
    const tab = createQueryTab({
      title: savedQuery.title,
      connectionId: savedQuery.connectionId,
      databaseName: normalizedDatabaseName,
      prompt: '',
      sqlText: savedQuery.sqlText,
      savedQueryId: savedQuery.id,
      savedQueryEditMode: true,
    });
    await ctx.runSafely(async () => {
      await ctx.prepareConnectionTreeData(savedQuery.connectionId);
      await ctx.warmupTableSuggestions(tab);
    });
    ctx.browserNavMode.value = 'connections';
    return tab;
  }

  async function openSavedQueryTabByTitle(connectionId: number, databaseName: string, title: string) {
    const queries = savedQueriesByDatabase(connectionId, databaseName).length
      ? savedQueriesByDatabase(connectionId, databaseName)
      : await loadSavedQueries(connectionId, databaseName);
    const target = queries.find((item) => item.title === title);
    if (!target) {
      message.warning('未找到对应的保存查询');
      return null;
    }
    return openSavedQueryTab(target);
  }

  async function requestSqlExecutionInterrupt(tab: QueryWorkspaceTab | null | undefined) {
    if (!tab?.connectionId || !tab.sessionId) {
      return;
    }
    try {
      await postApi<{interrupted: boolean; message: string}>('/api/sql/interrupt', {
        connectionId: tab.connectionId,
        sessionId: tab.sessionId,
      });
    } catch {
      // 本地停止优先保证交互立即返回。
    }
  }

  function closeQueryTab(tabKey: string) {
    const index = ctx.queryTabs.value.findIndex((item) => item.key === tabKey);
    if (index < 0) {
      return;
    }
    const targetTab = ctx.queryTabs.value[index];
    const sqlController = ctx.sqlExecutionAbortControllerMap.get(tabKey);
    if (sqlController) {
      ctx.sqlExecutionAbortReasonMap.set(tabKey, 'manual');
      sqlController.abort();
      void requestSqlExecutionInterrupt(targetTab);
    }
    const aiController = ctx.aiRequestAbortControllerMap.get(tabKey);
    if (aiController) {
      ctx.aiRequestAbortReasonMap.set(tabKey, 'manual');
      aiController.abort();
    }
    const tabs = [...ctx.queryTabs.value];
    tabs.splice(index, 1);
    ctx.queryTabs.value = tabs;
    if (ctx.activeWorkbenchTab.value === tabKey) {
      ctx.activeWorkbenchTab.value = tabs[index]?.key || tabs[index - 1]?.key || browserTabKey;
      ensureActiveWorkbenchTab();
    }
  }

  function hasWorkbenchTab(tabKey: string) {
    if (tabKey === browserTabKey) {
      return true;
    }
    return ctx.queryTabs.value.some((item) => item.key === tabKey)
      || ctx.erTabs.value.some((item) => item.key === tabKey)
      || ctx.knowledgeTabs.value.some((item) => item.key === tabKey)
      || ctx.memoryTabs.value.some((item) => item.key === tabKey)
      || ctx.tableEditorTabs.value.some((item) => item.key === tabKey)
      || ctx.objectDefinitionEditorTabs.value.some((item) => item.key === tabKey)
      || ctx.tableDataTabs.value.some((item) => item.key === tabKey);
  }

  function ensureActiveWorkbenchTab() {
    if (hasWorkbenchTab(ctx.activeWorkbenchTab.value)) {
      return;
    }
    ctx.activeWorkbenchTab.value = ctx.queryTabs.value[0]?.key
      ?? ctx.erTabs.value[0]?.key
      ?? ctx.knowledgeTabs.value[0]?.key
      ?? ctx.memoryTabs.value[0]?.key
      ?? ctx.tableEditorTabs.value[0]?.key
      ?? ctx.objectDefinitionEditorTabs.value[0]?.key
      ?? ctx.tableDataTabs.value[0]?.key
      ?? browserTabKey;
  }

  return {
    openAiQueryTab,
    openQueryTabByObject,
    createQueryTab,
    savedQueryCacheKey,
    savedQueriesByDatabase,
    loadSavedQueries,
    openSaveQueryModal,
    saveCurrentQuery,
    openSavedQueryTab,
    openSavedQueryTabByTitle,
    closeQueryTab,
    requestSqlExecutionInterrupt,
    hasWorkbenchTab,
    ensureActiveWorkbenchTab,
  };
}
