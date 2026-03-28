import type {Ref} from 'vue';
import {postApi} from '../../../../api/client';
import type {
  AiTraceVO,
  ChartConfigVO,
  PromptBudgetVO,
  QueryHistorySessionVO,
} from '../../../../types';
import {sessionTitleOverridesStorageKey} from './constants';
import type {
  QueryChatMessage,
  QueryActionType,
  QueryWorkspaceTab,
} from './types';

interface HistoryRuntimeHelperContext {
  conversationMemoryEnabledForTab: (tab: QueryWorkspaceTab | null | undefined) => boolean;
  connections: Ref<Array<{id: number; name?: string}>>;
  editingHistoryTabKey: Ref<string>;
  editingHistoryTitle: Ref<string>;
  estimateTextTokens: (text: string) => number;
  getActiveDatabaseName: (connectionId: number) => string;
  queryTabs: Ref<QueryWorkspaceTab[]>;
  sessionTitleOverrides: Ref<Record<string, string>>;
}

interface SaveConversationHistoryOptions {
  actionType?: QueryActionType;
  assistantContent?: string;
  databaseName?: string;
  chartConfig?: ChartConfigVO | null;
  chartImageCacheKey?: string;
  historyType?: 'CHAT' | 'EXECUTE';
  executionMs?: number;
  success?: boolean;
  structuredContextJson?: string;
  trace?: AiTraceVO;
  traceJson?: string;
  tokenEstimate?: number;
  turnContentTokens?: number;
  requestPromptTokens?: number;
  requestCompletionTokens?: number;
  requestTotalTokens?: number;
  tokenEstimateSource?: string;
  tokenEstimateVersion?: number;
  tokenEstimateScope?: string;
  promptBudget?: PromptBudgetVO | null;
  memoryEnabled?: boolean;
}

export function createHistoryRuntimeHelpers(ctx: HistoryRuntimeHelperContext) {
  function sessionRefKey(connectionId: number, sessionId: string) {
    return `${connectionId}::${sessionId}`;
  }

  function sessionTitleOverrideKey(sessionRef: { connectionId: number; sessionId: string }) {
    return sessionRefKey(sessionRef.connectionId, sessionRef.sessionId);
  }

  function historyItemKey(item: QueryHistorySessionVO) {
    return sessionRefKey(item.connectionId, item.sessionId);
  }

  function findQueryTabBySession(connectionId: number, sessionId: string) {
    return ctx.queryTabs.value.find((tab) => tab.connectionId === connectionId && tab.sessionId === sessionId) ?? null;
  }

  function queryTabConnectionNameById(connectionId?: number) {
    if (!connectionId) {
      return '';
    }
    return ctx.connections.value.find((item) => item.id === connectionId)?.name ?? '';
  }

  function normalizeTitleSource(text: string) {
    return text.replace(/\s+/g, ' ').trim();
  }

  function buildSessionDefaultTitle(text: string) {
    const normalized = normalizeTitleSource(text);
    if (!normalized) {
      return '未命名会话';
    }
    const splitIndex = normalized.search(/[。！？\n]/);
    const firstSentence = (splitIndex >= 0 ? normalized.slice(0, splitIndex) : normalized).trim() || normalized;
    return firstSentence.length > 20 ? `${firstSentence.slice(0, 20)}...` : firstSentence;
  }

  function firstPromptForTitle(tab: QueryWorkspaceTab) {
    const firstUser = tab.chatMessages.find((item) => item.role === 'user' && item.content.trim());
    if (firstUser) {
      return firstUser.content;
    }
    return tab.prompt;
  }

  function buildNewQueryPlaceholderTitle(tab: QueryWorkspaceTab) {
    const connectionName = (queryTabConnectionNameById(tab.connectionId) || '').trim() || '未命名连接';
    const databaseName = (tab.databaseName || ctx.getActiveDatabaseName(tab.connectionId) || '').trim() || '未指定库';
    return `${connectionName} / ${databaseName} · 新的查询`;
  }

  function applySessionTitle(tab: QueryWorkspaceTab) {
    const custom = (ctx.sessionTitleOverrides.value[sessionTitleOverrideKey(tab)] ?? '').trim();
    if (custom) {
      tab.title = custom;
      return;
    }
    const firstPrompt = firstPromptForTitle(tab).trim();
    if (firstPrompt) {
      tab.title = buildSessionDefaultTitle(firstPrompt);
      return;
    }
    tab.title = buildNewQueryPlaceholderTitle(tab);
  }

  function loadSessionTitleOverrides() {
    if (typeof window === 'undefined') {
      return;
    }
    try {
      const raw = window.localStorage.getItem(sessionTitleOverridesStorageKey);
      if (!raw) {
        return;
      }
      const parsed = JSON.parse(raw) as Record<string, string>;
      if (!parsed || typeof parsed !== 'object') {
        return;
      }
      const next: Record<string, string> = {};
      Object.entries(parsed).forEach(([key, value]) => {
        if (typeof value !== 'string') {
          return;
        }
        const normalized = value.trim();
        if (normalized) {
          next[key] = normalized;
        }
      });
      ctx.sessionTitleOverrides.value = next;
    } catch {
      ctx.sessionTitleOverrides.value = {};
    }
  }

  function persistSessionTitleOverrides() {
    if (typeof window === 'undefined') {
      return;
    }
    try {
      window.localStorage.setItem(sessionTitleOverridesStorageKey, JSON.stringify(ctx.sessionTitleOverrides.value));
    } catch {
      // 忽略本地存储异常，避免阻塞主流程。
    }
  }

  function cancelHistoryTitleEdit() {
    ctx.editingHistoryTabKey.value = '';
    ctx.editingHistoryTitle.value = '';
  }

  function assistantActionLabel(actionType: QueryActionType) {
    if (actionType === 'auto_generate') {
      return 'Auto · 生成 SQL';
    }
    if (actionType === 'auto_explain') {
      return 'Auto · 解释 SQL';
    }
    if (actionType === 'auto_analyze') {
      return 'Auto · 分析 SQL';
    }
    if (actionType === 'auto_chart_auto_plan') {
      return 'Auto · 图表方案';
    }
    if (actionType === 'explain') {
      return '解释 SQL';
    }
    if (actionType === 'analyze') {
      return '分析 SQL';
    }
    if (actionType === 'repair') {
      return '修复 SQL';
    }
    if (actionType === 'chart_auto_plan') {
      return '图表方案';
    }
    if (actionType === 'chart_manual_render') {
      return '手动制图';
    }
    if (actionType === 'chart_auto_render') {
      return '自动制图';
    }
    return '生成 SQL';
  }

  function normalizeHistoryActionType(actionType?: string): QueryActionType {
    const normalized = (actionType || '').trim().toLowerCase();
    if (normalized === 'auto_generate') {
      return 'auto_generate';
    }
    if (normalized === 'auto_explain') {
      return 'auto_explain';
    }
    if (normalized === 'auto_analyze') {
      return 'auto_analyze';
    }
    if (normalized === 'auto_chart_auto_plan') {
      return 'auto_chart_auto_plan';
    }
    if (normalized === 'explain') {
      return 'explain';
    }
    if (normalized === 'analyze') {
      return 'analyze';
    }
    if (normalized === 'repair') {
      return 'repair';
    }
    if (normalized === 'chart_auto_plan') {
      return 'chart_auto_plan';
    }
    if (normalized === 'chart_manual_render') {
      return 'chart_manual_render';
    }
    if (normalized === 'chart_auto_render') {
      return 'chart_auto_render';
    }
    return 'generate';
  }

  function userBubbleClass(actionType: QueryActionType) {
    if (actionType === 'auto_explain' || actionType === 'explain') {
      return 'is-explain';
    }
    if (actionType === 'auto_analyze' || actionType === 'analyze') {
      return 'is-analyze';
    }
    if (actionType === 'repair') {
      return 'is-repair';
    }
    return 'is-generate';
  }

  function resolveHistoryMemoryEnabled(tab: QueryWorkspaceTab, options?: SaveConversationHistoryOptions) {
    if (options?.memoryEnabled != null) {
      return options.memoryEnabled;
    }
    if (options?.historyType === 'EXECUTE') {
      return tab.sqlMemoryEnabled;
    }
    return ctx.conversationMemoryEnabledForTab(tab);
  }

  async function saveConversationHistory(
    tab: QueryWorkspaceTab,
    promptText: string,
    sqlText: string,
    options?: SaveConversationHistoryOptions,
  ) {
    try {
      const memoryEnabled = resolveHistoryMemoryEnabled(tab, options);
      await postApi<boolean>('/api/editor/history/save', {
        connectionId: tab.connectionId,
        sessionId: tab.sessionId,
        promptText,
        sqlText,
        historyType: options?.historyType || 'CHAT',
        actionType: options?.actionType || 'generate',
        assistantContent: options?.assistantContent || '',
        databaseName: options?.databaseName || tab.databaseName || '',
        chartConfigJson: options?.chartConfig ? JSON.stringify(options.chartConfig) : '',
        chartImageCacheKey: options?.chartImageCacheKey || '',
        structuredContextJson: options?.structuredContextJson || '',
        traceJson: options?.traceJson || (options?.trace ? JSON.stringify(options.trace) : ''),
        trace: options?.trace,
        tokenEstimate: options?.tokenEstimate,
        turnContentTokens: options?.turnContentTokens,
        requestPromptTokens: options?.requestPromptTokens,
        requestCompletionTokens: options?.requestCompletionTokens,
        requestTotalTokens: options?.requestTotalTokens,
        tokenEstimateSource: options?.tokenEstimateSource,
        tokenEstimateVersion: options?.tokenEstimateVersion,
        tokenEstimateScope: options?.tokenEstimateScope,
        promptBudgetJson: options?.promptBudget ? JSON.stringify(options.promptBudget) : '',
        promptBudget: options?.promptBudget ?? undefined,
        memoryEnabled,
        executionMs: options?.executionMs,
        success: options?.success ?? true,
      });
    } catch {
      // 关键操作：会话历史持久化失败不阻塞主流程。
    }
  }

  function buildStructuredContextForTab(tab: QueryWorkspaceTab, windowSize = 12) {
    const start = Math.max(0, tab.chatMessages.length - windowSize);
    const rows = tab.chatMessages.slice(start).map((item) => ({
      role: item.role,
      actionType: item.actionType,
      content: (item.content || '').slice(0, 500),
      sqlText: (item.sqlText || '').slice(0, 500),
      createdAt: item.createdAt,
    }));
    return JSON.stringify(rows);
  }

  async function saveConversationHistoryOnce(
    tab: QueryWorkspaceTab,
    userMessage: QueryChatMessage,
    promptText: string,
    sqlText: string,
    options?: SaveConversationHistoryOptions,
  ) {
    if (userMessage.historySaved) {
      return;
    }
    const mergedOptions: SaveConversationHistoryOptions = {
      ...options,
      tokenEstimate: options?.tokenEstimate ?? tab.lastRequestTotalTokens ?? tab.lastTokenEstimate ?? ctx.estimateTextTokens(`${promptText || ''}\n${sqlText || ''}`),
      turnContentTokens: options?.turnContentTokens ?? tab.lastTurnContentTokens,
      requestPromptTokens: options?.requestPromptTokens ?? tab.lastRequestPromptTokens,
      requestCompletionTokens: options?.requestCompletionTokens ?? tab.lastRequestCompletionTokens,
      requestTotalTokens: options?.requestTotalTokens ?? tab.lastRequestTotalTokens,
      tokenEstimateSource: options?.tokenEstimateSource ?? (tab.lastRequestTotalTokens > 0 ? 'provider_usage' : 'backend_estimator'),
      tokenEstimateVersion: options?.tokenEstimateVersion ?? 2,
      tokenEstimateScope: options?.tokenEstimateScope ?? (tab.lastRequestTotalTokens > 0 ? 'REQUEST_TOTAL' : 'TURN_CONTENT'),
      promptBudget: options?.promptBudget ?? tab.lastPromptBudget ?? null,
      memoryEnabled: resolveHistoryMemoryEnabled(tab, options),
      structuredContextJson: options?.structuredContextJson ?? buildStructuredContextForTab(tab),
      traceJson: options?.traceJson ?? (options?.trace ? JSON.stringify(options.trace) : ''),
    };
    await saveConversationHistory(tab, promptText, sqlText, mergedOptions);
    userMessage.historySaved = true;
  }

  function applyPromptBudgetSnapshot(tab: QueryWorkspaceTab, budget: PromptBudgetVO | null | undefined) {
    tab.lastPromptBudget = budget ? JSON.parse(JSON.stringify(budget)) : null;
  }

  function applyResponseTokenSnapshot(
    tab: QueryWorkspaceTab,
    result: {
      totalTokens?: number;
      requestPromptTokens?: number;
      requestCompletionTokens?: number;
      requestTotalTokens?: number;
      turnContentTokens?: number;
      promptBudget?: PromptBudgetVO;
    } | null | undefined,
  ) {
    if (!tab || !result) {
      return;
    }
    const totalTokens = Number(result.requestTotalTokens ?? result.totalTokens ?? 0);
    tab.lastTokenEstimate = totalTokens;
    tab.lastRequestPromptTokens = Number(result.requestPromptTokens ?? 0);
    tab.lastRequestCompletionTokens = Number(result.requestCompletionTokens ?? 0);
    tab.lastRequestTotalTokens = totalTokens;
    tab.lastTurnContentTokens = Number(result.turnContentTokens ?? 0);
    applyPromptBudgetSnapshot(tab, result.promptBudget);
  }

  return {
    normalizeTitleSource,
    firstPromptForTitle,
    buildNewQueryPlaceholderTitle,
    sessionTitleOverrideKey,
    historyItemKey,
    findQueryTabBySession,
    queryTabConnectionNameById,
    buildSessionDefaultTitle,
    applySessionTitle,
    loadSessionTitleOverrides,
    persistSessionTitleOverrides,
    cancelHistoryTitleEdit,
    assistantActionLabel,
    normalizeHistoryActionType,
    userBubbleClass,
    saveConversationHistory,
    buildStructuredContextForTab,
    saveConversationHistoryOnce,
    applyResponseTokenSnapshot,
  };
}
