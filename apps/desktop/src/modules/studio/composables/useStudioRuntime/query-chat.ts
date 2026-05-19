import type {Ref} from 'vue';
import {nextTick} from 'vue';
import type {
  AiTraceStageVO,
  AiTraceVO,
  ChartConfigVO,
} from '../../../../types';
import {dedupeChartMessageContent} from './ai-interaction';
import {cloneChartConfig} from './charts';
import type {QueryChatMessage, QueryWorkspaceTab} from './types';

interface QueryChatHelperContext {
  applySessionTitle: (tab: QueryWorkspaceTab) => void;
  getActiveQueryTab: () => QueryWorkspaceTab | null;
  queryChatMessageElementMap: Map<string, HTMLElement>;
  queryChatScrollRef: Ref<HTMLElement | null>;
  touchQueryTab: (tab: QueryWorkspaceTab) => void;
}

function waitForStreamingPaint() {
  if (typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function') {
    return new Promise<void>((resolve) => {
      window.requestAnimationFrame(() => {
        window.setTimeout(resolve, 0);
      });
    });
  }
  return new Promise<void>((resolve) => {
    setTimeout(resolve, 0);
  });
}

function isQueryChatMessage(value: unknown): value is QueryChatMessage {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const candidate = value as Partial<QueryChatMessage>;
  return (candidate.role === 'user' || candidate.role === 'assistant') && typeof candidate.id === 'string';
}

function hasRenderableAssistantPayload(messageItem: QueryChatMessage | undefined) {
  if (!messageItem) {
    return false;
  }
  return !!(
    (messageItem.content || '').trim()
    || (messageItem.liveOutput || '').trim()
    || (messageItem.sqlText || '').trim()
    || messageItem.chartConfig
    || (messageItem.chartImageDataUrl || '').trim()
    || messageItem.executionPreview
  );
}

function extractThinkingContentFromTrace(trace?: AiTraceVO | null) {
  if (!trace?.stages?.length) {
    return '';
  }
  const values = trace.stages
    .map((stage) => stage.llmCall?.thinkingContent || '')
    .filter((item) => !!item.trim());
  return values.length ? values[values.length - 1] : '';
}

function resolveStreamingLlmStageMeta(actionType?: QueryChatMessage['actionType']) {
  if (actionType === 'explain' || actionType === 'auto_explain') {
    return {stageCode: 'llm_explain_sql', stageLabel: 'explain_sql'};
  }
  if (actionType === 'analyze' || actionType === 'auto_analyze') {
    return {stageCode: 'llm_analyze_sql', stageLabel: 'analyze_sql'};
  }
  if (actionType === 'chart_auto_plan' || actionType === 'auto_chart_auto_plan') {
    return {stageCode: 'llm_generate_chart', stageLabel: '图表生成'};
  }
  if (actionType === 'repair') {
    return {stageCode: 'llm_repair_sql', stageLabel: 'repair_sql'};
  }
  return {stageCode: 'llm_generate_sql', stageLabel: 'SQL生成'};
}

export function createQueryChatHelpers(ctx: QueryChatHelperContext) {
  function toggleMessageTraceExpanded(tab: QueryWorkspaceTab, messageId: string) {
    const target = tab.chatMessages.find((item) => item.id === messageId);
    if (!target || !target.trace) {
      return;
    }
    target.traceExpanded = target.traceExpanded !== true;
    ctx.touchQueryTab(tab);
  }

  function toggleMessageThinkingExpanded(tab: QueryWorkspaceTab, messageId: string) {
    const target = tab.chatMessages.find((item) => item.id === messageId);
    if (!target) {
      return;
    }
    target.thinkingExpanded = target.thinkingExpanded !== true;
    ctx.touchQueryTab(tab);
  }

  function flushStreamingQueryTab(tab: QueryWorkspaceTab) {
    ctx.touchQueryTab(tab);
    return nextTick().then(() => waitForStreamingPaint());
  }

  function bindQueryChatMessageRef(messageId: string, element: unknown) {
    if (element instanceof HTMLElement) {
      ctx.queryChatMessageElementMap.set(messageId, element);
      return;
    }
    ctx.queryChatMessageElementMap.delete(messageId);
  }

  function scrollToQueryChatMessage(tab: QueryWorkspaceTab, messageId: string) {
    const tabKey = tab.key;
    void nextTick().then(() => {
      if (ctx.getActiveQueryTab()?.key !== tabKey) {
        return;
      }
      const container = ctx.queryChatScrollRef.value;
      if (!container) {
        return;
      }
      const target = ctx.queryChatMessageElementMap.get(messageId);
      if (!target) {
        container.scrollTop = container.scrollHeight;
        return;
      }
      target.scrollIntoView({block: 'end'});
    });
  }

  function appendUserChatMessage(tab: QueryWorkspaceTab, promptText: string, actionType: QueryChatMessage['actionType']) {
    const now = Date.now();
    const messageItem: QueryChatMessage = {
      id: `chat-user-${now}-${Math.random().toString(16).slice(2, 8)}`,
      role: 'user',
      content: promptText,
      actionType,
      retryable: false,
      retryLoading: false,
      historySaved: false,
      createdAt: now,
    };
    tab.chatMessages.push(messageItem);
    ctx.applySessionTitle(tab);
    ctx.touchQueryTab(tab);
    scrollToQueryChatMessage(tab, messageItem.id);
    return messageItem;
  }

  function appendAssistantThinkingMessage(tab: QueryWorkspaceTab, actionType: QueryChatMessage['actionType'], thinkingEnabled = false) {
    const now = Date.now();
    const messageItem: QueryChatMessage = {
      id: `chat-assistant-thinking-${now}-${Math.random().toString(16).slice(2, 8)}`,
      role: 'assistant',
      content: '思考中...',
      pending: true,
      streaming: true,
      finalized: false,
      thinkingContent: '',
      thinkingEnabled,
      liveOutput: '',
      actionType,
      createdAt: now,
    };
    tab.chatMessages.push(messageItem);
    ctx.touchQueryTab(tab);
    scrollToQueryChatMessage(tab, messageItem.id);
    return messageItem;
  }

  function removeQueryChatMessage(tab: QueryWorkspaceTab, targetMessage: QueryChatMessage | undefined) {
    if (!targetMessage) {
      return;
    }
    const index = tab.chatMessages.findIndex((item) => item.id === targetMessage.id);
    if (index < 0) {
      return;
    }
    tab.chatMessages.splice(index, 1);
    ctx.touchQueryTab(tab);
  }

  function materializeAssistantErrorMessage(
    tab: QueryWorkspaceTab,
    targetMessage: QueryChatMessage | undefined,
    actionType: QueryChatMessage['actionType'],
    errorMessage: string,
  ) {
    if (!targetMessage) {
      return;
    }
    if (hasRenderableAssistantPayload(targetMessage) && !targetMessage.pending && !targetMessage.streaming) {
      return;
    }
    appendAssistantTextMessage(tab, errorMessage, actionType, targetMessage.trace, targetMessage);
  }

  function prepareAssistantMessage(
    messageItem: QueryChatMessage,
    actionType: QueryChatMessage['actionType'],
    createdAt: number,
  ) {
    messageItem.role = 'assistant';
    messageItem.actionType = actionType;
    messageItem.pending = false;
    messageItem.streaming = false;
    messageItem.finalized = true;
    if (messageItem.thinkingEnabled) {
      messageItem.thinkingContent = extractThinkingContentFromTrace(messageItem.trace) || messageItem.thinkingContent || '';
      if (messageItem.thinkingContent) {
        messageItem.thinkingExpanded = true;
      }
    } else {
      messageItem.thinkingContent = '';
      messageItem.thinkingExpanded = undefined;
    }
    messageItem.liveOutput = '';
    messageItem.aborted = false;
    messageItem.content = '';
    messageItem.sqlText = undefined;
    messageItem.chartConfig = undefined;
    messageItem.chartConfigSummary = undefined;
    messageItem.chartImageCacheKey = undefined;
    messageItem.chartImageDataUrl = undefined;
    messageItem.executionPreview = undefined;
    messageItem.retryable = undefined;
    messageItem.retryLoading = undefined;
    messageItem.retryMeta = undefined;
    messageItem.historySaved = undefined;
    messageItem.trace = undefined;
    messageItem.traceExpanded = undefined;
    messageItem.createdAt = createdAt;
  }

  function ensureAssistantStreamingState(
    tab: QueryWorkspaceTab,
    messageItem: QueryChatMessage,
    actionType?: QueryChatMessage['actionType'],
  ) {
    messageItem.role = 'assistant';
    if (actionType) {
      messageItem.actionType = actionType;
    }
    messageItem.pending = false;
    messageItem.streaming = true;
    messageItem.finalized = false;
    if (typeof messageItem.thinkingContent !== 'string') {
      messageItem.thinkingContent = '';
    }
    if (typeof messageItem.liveOutput !== 'string') {
      messageItem.liveOutput = '';
    }
    ctx.touchQueryTab(tab);
    scrollToQueryChatMessage(tab, messageItem.id);
  }

  function upsertStreamingTraceStage(messageItem: QueryChatMessage, stage: AiTraceStageVO) {
    const trace = messageItem.trace || {stageCount: 0, totalDurationMs: 0, stages: []};
    const stages = [...(trace.stages || [])];
    const index = stages.findIndex((item) => item.stageCode === stage.stageCode);
    if (index >= 0) {
      stages[index] = stage;
    } else {
      stages.push(stage);
    }
    messageItem.trace = {
      ...trace,
      stages,
      stageCount: stages.length,
    };
    messageItem.traceExpanded = true;
  }

  function upsertStreamingTraceLlmDelta(
    messageItem: QueryChatMessage,
    actionType: QueryChatMessage['actionType'] | undefined,
    channel: 'thinking' | 'output',
    accumulatedText: string,
  ) {
    if (!accumulatedText) {
      return;
    }
    const trace = messageItem.trace || {stageCount: 0, totalDurationMs: 0, stages: []};
    const stages = [...(trace.stages || [])];
    const stageMeta = resolveStreamingLlmStageMeta(actionType);
    const stageIndex = stages.findIndex((item) => item.stageCode === stageMeta.stageCode);
    const currentStage = stageIndex >= 0 ? stages[stageIndex] : undefined;
    const llmCall = {
      ...(currentStage?.llmCall || {}),
      streaming: true,
    };
    if (channel === 'thinking') {
      llmCall.thinkingContent = accumulatedText;
    } else {
      llmCall.fullOutput = accumulatedText;
    }
    const nextStage: AiTraceStageVO = {
      stageCode: currentStage?.stageCode || stageMeta.stageCode,
      stageLabel: currentStage?.stageLabel || stageMeta.stageLabel,
      stageType: currentStage?.stageType || 'llm',
      status: currentStage?.status || 'running',
      durationMs: currentStage?.durationMs || 0,
      inputFields: currentStage?.inputFields || [],
      outputFields: currentStage?.outputFields || [],
      llmCall,
    };
    if (stageIndex >= 0) {
      stages[stageIndex] = nextStage;
    } else {
      stages.push(nextStage);
    }
    messageItem.trace = {
      ...trace,
      stages,
      stageCount: stages.length,
    };
    messageItem.traceExpanded = true;
  }

  function applyStreamTraceSnapshot(messageItem: QueryChatMessage, trace?: AiTraceVO) {
    if (!trace) {
      return;
    }
    messageItem.trace = trace;
    messageItem.traceExpanded = true;
    if (messageItem.thinkingEnabled) {
      const thinkingContent = extractThinkingContentFromTrace(trace);
      if (thinkingContent) {
        messageItem.thinkingContent = thinkingContent;
        messageItem.thinkingExpanded = true;
      }
    }
  }

  function finalizeStreamingMessage(messageItem: QueryChatMessage) {
    messageItem.pending = false;
    messageItem.streaming = false;
    messageItem.finalized = true;
    messageItem.liveOutput = '';
  }

  function appendAssistantSqlMessage(
    tab: QueryWorkspaceTab,
    sqlText: string,
    actionType: QueryChatMessage['actionType'],
    content = '',
    chartConfig?: ChartConfigVO | null,
    chartConfigSummary?: string,
    chartImageCacheKey?: string,
    traceOrTargetMessage?: AiTraceVO | QueryChatMessage,
    targetMessage?: QueryChatMessage,
  ) {
    const now = Date.now();
    const resolvedTrace = isQueryChatMessage(traceOrTargetMessage) ? undefined : traceOrTargetMessage;
    const resolvedTargetMessage = isQueryChatMessage(traceOrTargetMessage) ? traceOrTargetMessage : targetMessage;
    const messageItem: QueryChatMessage = resolvedTargetMessage ?? {
      id: `chat-assistant-${now}-${Math.random().toString(16).slice(2, 8)}`,
      role: 'assistant',
      content: '',
      actionType,
      createdAt: now,
    };
    prepareAssistantMessage(messageItem, actionType, now);
    messageItem.sqlText = sqlText;
    messageItem.chartConfig = chartConfig ? cloneChartConfig(chartConfig) : undefined;
    messageItem.chartConfigSummary = (chartConfigSummary || '').trim() || undefined;
    messageItem.content = dedupeChartMessageContent(
      content,
      messageItem.chartConfig,
      messageItem.chartConfigSummary,
    );
    messageItem.chartImageCacheKey = (chartImageCacheKey || '').trim() || undefined;
    messageItem.trace = resolvedTrace;
    messageItem.traceExpanded = false;
    if (!resolvedTargetMessage) {
      tab.chatMessages.push(messageItem);
    }
    ctx.touchQueryTab(tab);
    scrollToQueryChatMessage(tab, messageItem.id);
    return messageItem;
  }

  function appendAssistantTextMessage(
    tab: QueryWorkspaceTab,
    content: string,
    actionType: QueryChatMessage['actionType'],
    traceOrTargetMessage?: AiTraceVO | QueryChatMessage,
    targetMessage?: QueryChatMessage,
  ) {
    const now = Date.now();
    const resolvedTrace = isQueryChatMessage(traceOrTargetMessage) ? undefined : traceOrTargetMessage;
    const resolvedTargetMessage = isQueryChatMessage(traceOrTargetMessage) ? traceOrTargetMessage : targetMessage;
    const messageItem: QueryChatMessage = resolvedTargetMessage ?? {
      id: `chat-assistant-${now}-${Math.random().toString(16).slice(2, 8)}`,
      role: 'assistant',
      content: '',
      actionType,
      createdAt: now,
    };
    prepareAssistantMessage(messageItem, actionType, now);
    messageItem.content = content.trim();
    messageItem.trace = resolvedTrace;
    messageItem.traceExpanded = false;
    if (!resolvedTargetMessage) {
      tab.chatMessages.push(messageItem);
    }
    ctx.touchQueryTab(tab);
    scrollToQueryChatMessage(tab, messageItem.id);
    return messageItem;
  }

  return {
    toggleMessageTraceExpanded,
    toggleMessageThinkingExpanded,
    flushStreamingQueryTab,
    bindQueryChatMessageRef,
    scrollToQueryChatMessage,
    appendUserChatMessage,
    appendAssistantThinkingMessage,
    removeQueryChatMessage,
    materializeAssistantErrorMessage,
    prepareAssistantMessage,
    extractThinkingContentFromTrace,
    ensureAssistantStreamingState,
    upsertStreamingTraceStage,
    upsertStreamingTraceLlmDelta,
    applyStreamTraceSnapshot,
    finalizeStreamingMessage,
    appendAssistantSqlMessage,
    appendAssistantTextMessage,
  };
}
