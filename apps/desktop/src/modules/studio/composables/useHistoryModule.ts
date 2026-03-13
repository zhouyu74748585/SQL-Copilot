import {message, Modal} from 'ant-design-vue';
import {getApi, postApi} from '../../../api/client';
import type {
  QueryHistorySessionPageVO,
  QueryHistoryVO,
} from '../../../types';
import type {StudioRuntime} from './useStudioRuntime';

type QueryTab = StudioRuntime['queryTabs']['value'][number];
type QueryMessage = QueryTab['chatMessages'][number];
type HistorySession = StudioRuntime['historySessionItems']['value'][number];

export interface HistoryModule {
  handleHistoryMenuClick: () => void;
  historyItemDisplayTitle: (item: HistorySession) => string;
  isHistoryItemActive: (item: HistorySession) => boolean;
  startHistoryTitleEdit: (item: HistorySession) => void;
  removeHistorySession: (item: HistorySession) => Promise<void>;
  commitHistoryTitleEdit: (item: HistorySession) => void;
  normalizeHistoryAssistantPayload: (sqlTextRaw: string, assistantContentRaw: string) => {
    sqlText: string;
    assistantContent: string;
  };
  buildHistoryChatMessages: (connectionId: number, sessionId: string, rows: QueryHistoryVO[]) => QueryMessage[];
  buildHistoryTabFromRows: (connectionId: number, sessionId: string, rows: QueryHistoryVO[]) => QueryTab;
  loadHistorySessionPage: (reset: boolean) => Promise<void>;
  applyHistoryKeywordSearch: () => void;
  handleHistoryMenuScroll: (event: Event) => void;
  openHistorySession: (item: HistorySession) => Promise<void>;
}

export function useHistoryModule(runtime: StudioRuntime): HistoryModule {
  function handleHistoryMenuClick() {
    if (!runtime.canOpenHistory.value) {
      return;
    }
    const connectionId = runtime.currentHistoryConnectionId.value;
    if (!connectionId) {
      return;
    }
    if (runtime.historySessionConnectionId.value !== connectionId) {
      runtime.historyKeywordInput.value = '';
      runtime.historyKeyword.value = '';
    }
    runtime.historySessionConnectionId.value = connectionId;
    runtime.cancelHistoryTitleEdit();
    void runtime.runSafely(async () => {
      await loadHistorySessionPage(true);
    });
  }

  function historyItemDisplayTitle(item: HistorySession) {
    const custom = (runtime.sessionTitleOverrides.value[runtime.sessionTitleOverrideKey(item)] ?? '').trim();
    if (custom) {
      return custom;
    }
    const source = (item.title ?? '').trim();
    return runtime.buildSessionDefaultTitle(source);
  }

  function isHistoryItemActive(item: HistorySession) {
    const tab = runtime.activeQueryTab.value;
    if (!tab) {
      return false;
    }
    return tab.connectionId === item.connectionId && tab.sessionId === item.sessionId;
  }

  function startHistoryTitleEdit(item: HistorySession) {
    runtime.editingHistoryTabKey.value = runtime.historyItemKey(item);
    runtime.editingHistoryTitle.value = historyItemDisplayTitle(item);
  }

  async function removeHistorySession(item: HistorySession) {
    const targetKey = runtime.historyItemKey(item);
    if (runtime.historySessionLoadingKey.value === targetKey) {
      return;
    }
    const confirmed = await new Promise<boolean>((resolve) => {
      Modal.confirm({
        title: '删除会话历史',
        content: `确定删除会话“${historyItemDisplayTitle(item)}”的全部历史记录吗？`,
        okText: '删除',
        okButtonProps: {danger: true},
        cancelText: '取消',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      });
    });
    if (!confirmed) {
      return;
    }

    runtime.historySessionLoadingKey.value = targetKey;
    try {
      await postApi<boolean>('/api/editor/history/session/remove', {
        connectionId: item.connectionId,
        sessionId: item.sessionId,
      });
      const overrideKey = runtime.sessionTitleOverrideKey(item);
      if (runtime.sessionTitleOverrides.value[overrideKey]) {
        const next = {...runtime.sessionTitleOverrides.value};
        delete next[overrideKey];
        runtime.sessionTitleOverrides.value = next;
        runtime.persistSessionTitleOverrides();
      }
      if (runtime.editingHistoryTabKey.value === targetKey) {
        runtime.cancelHistoryTitleEdit();
      }
      runtime.historySessionItems.value = runtime.historySessionItems.value.filter(
        (entry) => runtime.historyItemKey(entry) !== targetKey,
      );
      runtime.queryTabs.value = runtime.queryTabs.value.filter(
        (tab) => !(tab.connectionId === item.connectionId && tab.sessionId === item.sessionId),
      );
      runtime.ensureActiveWorkbenchTab();
      message.success('会话已删除');
      if (!runtime.historySessionItems.value.length) {
        runtime.historySessionPageNo.value = 1;
        runtime.historySessionHasMore.value = true;
        await loadHistorySessionPage(true);
      }
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(msg);
    } finally {
      runtime.historySessionLoadingKey.value = '';
    }
  }

  function commitHistoryTitleEdit(item: HistorySession) {
    const key = runtime.historyItemKey(item);
    if (runtime.editingHistoryTabKey.value !== key) {
      return;
    }
    const overrideKey = runtime.sessionTitleOverrideKey(item);
    const renamed = runtime.editingHistoryTitle.value.trim();
    const next = {...runtime.sessionTitleOverrides.value};
    if (renamed) {
      next[overrideKey] = renamed;
    } else {
      delete next[overrideKey];
    }
    runtime.sessionTitleOverrides.value = next;
    runtime.persistSessionTitleOverrides();
    const tab = runtime.findQueryTabBySession(item.connectionId, item.sessionId);
    if (tab) {
      if (renamed) {
        tab.title = renamed;
      } else {
        runtime.applySessionTitle(tab);
      }
    }
    runtime.editingHistoryTabKey.value = '';
    runtime.editingHistoryTitle.value = '';
  }

  function normalizeHistoryAssistantPayload(sqlTextRaw: string, assistantContentRaw: string) {
    const sqlText = sqlTextRaw.trim();
    const assistantContent = assistantContentRaw.trim();
    if (sqlText && assistantContent && sqlText === assistantContent) {
      if (runtime.looksLikeSqlText(sqlText)) {
        return {
          sqlText,
          assistantContent: '',
        };
      }
      return {
        sqlText: '',
        assistantContent,
      };
    }
    return {
      sqlText,
      assistantContent,
    };
  }

  function buildHistoryChatMessages(connectionId: number, sessionId: string, rows: QueryHistoryVO[]) {
    const ordered = [...rows].sort((a, b) => (a.createdAt ?? 0) - (b.createdAt ?? 0));
    const messages: QueryMessage[] = [];
    ordered.forEach((item, index) => {
      const promptText = (item.promptText ?? '').trim();
      const normalizedPayload = normalizeHistoryAssistantPayload(item.sqlText ?? '', item.assistantContent ?? '');
      const sqlText = normalizedPayload.sqlText;
      const actionType = runtime.normalizeHistoryActionType(item.actionType);
      const ts = item.createdAt ?? Date.now() + index;
      if (promptText) {
        messages.push({
          id: `chat-history-user-${connectionId}-${encodeURIComponent(sessionId)}-${item.id ?? index}`,
          role: 'user',
          content: promptText,
          actionType,
          createdAt: ts,
        });
      }
      const assistantContent = normalizedPayload.assistantContent;
      const hasAssistantPayload = !!assistantContent || !!sqlText || !!item.chartConfig || !!item.chartImageCacheKey;
      if (hasAssistantPayload) {
        const thinkingContent = runtime.extractThinkingContentFromTrace(item.trace);
        messages.push({
          id: `chat-history-assistant-${connectionId}-${encodeURIComponent(sessionId)}-${item.id ?? index}`,
          role: 'assistant',
          content: assistantContent,
          streaming: false,
          finalized: true,
          thinkingContent: thinkingContent || undefined,
          liveOutput: '',
          sqlText: sqlText || undefined,
          actionType,
          chartConfig: item.chartConfig ?? undefined,
          chartConfigSummary: assistantContent || undefined,
          chartImageCacheKey: (item.chartImageCacheKey || '').trim() || undefined,
          trace: item.trace ?? undefined,
          traceExpanded: false,
          createdAt: ts + 1,
        });
      }
    });
    return messages;
  }

  function buildHistoryTabFromRows(connectionId: number, sessionId: string, rows: QueryHistoryVO[]) {
    const messages = buildHistoryChatMessages(connectionId, sessionId, rows);
    const ordered = [...rows].sort((a, b) => (a.createdAt ?? 0) - (b.createdAt ?? 0));
    const last = ordered[ordered.length - 1];
    const first = ordered[0];
    const latestMemoryFlag = [...ordered].reverse().find((item) => item.memoryEnabled != null)?.memoryEnabled;
    const latestTokenEstimate = [...ordered].reverse().find((item) => item.tokenEstimate != null)?.tokenEstimate;
    const models = runtime.aiModelOptions.value.map((item) => String(item.value)).filter((item) => !!item);
    const tab: QueryTab = {
      key: `query-history-${connectionId}-${encodeURIComponent(sessionId)}`,
      title: '未命名会话',
      connectionId,
      databaseName: (last?.databaseName || '').trim() || runtime.getActiveDatabaseName(connectionId),
      savedQueryEditMode: false,
      sessionId,
      prompt: '',
      sqlText: '',
      riskAckToken: '',
      riskInfo: null,
      executeResult: null,
      explainResult: null,
      selectedAiModel: models[0] ?? '',
      autoMode: true,
      autoExecute: false,
      aiGenerating: false,
      sqlExecuting: false,
      selectedSqlText: '',
      chatMessages: messages,
      resultTableRows: [],
      resultTableColumns: [],
      lastExecuteFailed: false,
      lastExecuteErrorMessage: '',
      lastFailedSqlText: '',
      resultViewMode: 'table',
      manualChartConfig: runtime.emptyManualChartConfig(),
      activeChartConfig: null,
      chartImageDataUrl: '',
      chartImageCacheKey: '',
      chartReadonly: false,
      createdAt: first?.createdAt ?? Date.now(),
      updatedAt: last?.createdAt ?? Date.now(),
      conversationMemoryEnabled: latestMemoryFlag ?? true,
      sqlMemoryEnabled: true,
      detailOutputOverride: null,
      lastTokenEstimate: Number(latestTokenEstimate ?? 0),
    };
    runtime.applySessionTitle(tab);
    return tab;
  }

  async function loadHistorySessionPage(reset: boolean) {
    if (!runtime.historySessionConnectionId.value) {
      return;
    }
    if (reset) {
      if (runtime.historyReloading.value) {
        return;
      }
      runtime.historyReloading.value = true;
    } else {
      if (runtime.historyLoadingMore.value || runtime.historyReloading.value || !runtime.historySessionHasMore.value) {
        return;
      }
      runtime.historyLoadingMore.value = true;
    }
    try {
      const requestPageNo = reset ? 1 : runtime.historySessionPageNo.value;
      const params = new URLSearchParams({
        connectionId: `${runtime.historySessionConnectionId.value}`,
        pageNo: `${requestPageNo}`,
        pageSize: `${runtime.historySessionPageSize}`,
      });
      if (runtime.historyKeyword.value) {
        params.set('keyword', runtime.historyKeyword.value);
      }
      const page = await getApi<QueryHistorySessionPageVO>(`/api/editor/history/session/page?${params.toString()}`);
      const pageItems = page.items ?? [];
      if (reset) {
        runtime.historySessionItems.value = pageItems;
      } else if (pageItems.length) {
        const merged = [...runtime.historySessionItems.value];
        const indexMap = new Map<string, number>();
        merged.forEach((entry, idx) => {
          indexMap.set(runtime.historyItemKey(entry), idx);
        });
        pageItems.forEach((entry) => {
          const entryKey = runtime.historyItemKey(entry);
          const existed = indexMap.get(entryKey);
          if (existed === undefined) {
            indexMap.set(entryKey, merged.length);
            merged.push(entry);
          } else {
            merged[existed] = entry;
          }
        });
        runtime.historySessionItems.value = merged;
      }
      runtime.historySessionPageNo.value = (page.pageNo ?? requestPageNo) + 1;
      runtime.historySessionHasMore.value = !!page.hasMore;
    } finally {
      if (reset) {
        runtime.historyReloading.value = false;
      } else {
        runtime.historyLoadingMore.value = false;
      }
    }
  }

  function applyHistoryKeywordSearch() {
    runtime.historyKeyword.value = runtime.historyKeywordInput.value.trim();
    runtime.historySessionPageNo.value = 1;
    runtime.historySessionHasMore.value = true;
    runtime.historySessionItems.value = [];
    runtime.cancelHistoryTitleEdit();
    void runtime.runSafely(async () => {
      await loadHistorySessionPage(true);
    });
  }

  function handleHistoryMenuScroll(event: Event) {
    if (runtime.historyLoadingMore.value || runtime.historyReloading.value || !runtime.historySessionHasMore.value) {
      return;
    }
    const target = event.target as HTMLElement | null;
    if (!target) {
      return;
    }
    if (target.scrollTop + target.clientHeight < target.scrollHeight - 36) {
      return;
    }
    void runtime.runSafely(async () => {
      await loadHistorySessionPage(false);
    });
  }

  async function openHistorySession(item: HistorySession) {
    const loadingKey = runtime.historyItemKey(item);
    if (runtime.historySessionLoadingKey.value === loadingKey) {
      return;
    }
    runtime.historySessionLoadingKey.value = loadingKey;
    try {
      const params = new URLSearchParams({
        connectionId: `${item.connectionId}`,
        sessionId: item.sessionId,
        limit: '5000',
      });
      const rows = await getApi<QueryHistoryVO[]>(`/api/editor/history/session/detail?${params.toString()}`);
      if (!rows.length) {
        message.info('该会话暂无可展示历史');
        return;
      }
      const customTitle = (runtime.sessionTitleOverrides.value[runtime.sessionTitleOverrideKey(item)] ?? '').trim();
      const fallbackTitle = historyItemDisplayTitle(item);
      let tab = runtime.findQueryTabBySession(item.connectionId, item.sessionId);
      if (tab) {
        const loaded = buildHistoryTabFromRows(item.connectionId, item.sessionId, rows);
        tab.chatMessages = loaded.chatMessages;
        tab.sqlText = '';
        tab.selectedSqlText = '';
        tab.executeResult = null;
        tab.explainResult = null;
        tab.riskInfo = null;
        tab.riskAckToken = '';
        tab.prompt = '';
        tab.resultViewMode = 'table';
        tab.manualChartConfig = runtime.emptyManualChartConfig();
        tab.activeChartConfig = null;
        tab.chartImageDataUrl = '';
        tab.chartImageCacheKey = '';
        tab.chartReadonly = true;
        tab.createdAt = item.createdAt ?? loaded.createdAt;
        tab.updatedAt = item.updatedAt ?? loaded.updatedAt;
        tab.databaseName = tab.databaseName || loaded.databaseName;
        tab.title = customTitle || fallbackTitle;
      } else {
        tab = buildHistoryTabFromRows(item.connectionId, item.sessionId, rows);
        tab.prompt = '';
        tab.selectedSqlText = '';
        tab.title = customTitle || fallbackTitle;
        tab.resultViewMode = 'table';
        tab.chartReadonly = true;
        tab.createdAt = item.createdAt ?? tab.createdAt;
        tab.updatedAt = item.updatedAt ?? tab.updatedAt;
        runtime.queryTabs.value = [...runtime.queryTabs.value, tab];
      }
      runtime.activeWorkbenchTab.value = tab.key;
      await runtime.prepareConnectionTreeData(tab.connectionId);
      tab.databaseName = tab.databaseName || runtime.getActiveDatabaseName(tab.connectionId);
      await runtime.warmupTableSuggestions(tab);
      await runtime.hydrateHistoryChartImages(tab);
    } finally {
      runtime.historySessionLoadingKey.value = '';
    }
  }

  return {
    handleHistoryMenuClick,
    historyItemDisplayTitle,
    isHistoryItemActive,
    startHistoryTitleEdit,
    removeHistorySession,
    commitHistoryTitleEdit,
    normalizeHistoryAssistantPayload,
    buildHistoryChatMessages,
    buildHistoryTabFromRows,
    loadHistorySessionPage,
    applyHistoryKeywordSearch,
    handleHistoryMenuScroll,
    openHistorySession,
  };
}
