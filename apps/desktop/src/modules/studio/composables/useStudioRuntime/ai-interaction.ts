import {message} from 'ant-design-vue';
import type {
  AiAutoQueryVO,
  AiGenerateChartVO,
  AiGenerateSqlVO,
  AiIntentType,
  AiRepairVO,
  AiTextResponseVO,
  AiTraceVO,
  ChartConfigVO,
  SqlExecuteVO,
} from '../../../../types';
import {
  buildExecutionPreview,
  cloneChartConfig,
  latestSuccessfulStatementResult,
  supportsGroupedSeriesChart,
} from './charts';
import type {
  AiActionType,
  QueryChatMessage,
  QueryActionType,
  QueryWorkspaceTab,
  RetryRequestMeta,
} from './types';

interface RetryInvokeOptions {
  userMessage: QueryChatMessage;
  promptText: string;
  finalPrompt: string;
  actionSqlSnippet?: string;
}

export function enrichPromptWithSchemaReferences(promptText: string) {
  const rawPrompt = promptText.trim();
  if (!rawPrompt) {
    return rawPrompt;
  }
  const explicitTables = new Set<string>();
  const explicitColumns = new Set<string>();
  const normalizedPrompt = rawPrompt.replace(
    /(^|[\s(,，;；])@([^\s@.(),，;；]+)(?:\.([^\s@.(),，;；]+))?/g,
    (_match, prefix: string, tableName: string, columnName?: string) => {
      const normalizedTableName = (tableName || '').trim();
      const normalizedColumnName = (columnName || '').trim();
      if (!normalizedTableName) {
        return `${prefix}@${tableName || ''}`;
      }
      explicitTables.add(normalizedTableName);
      if (normalizedColumnName) {
        explicitColumns.add(`${normalizedTableName}.${normalizedColumnName}`);
        return `${prefix}${normalizedTableName}.${normalizedColumnName}`;
      }
      return `${prefix}${normalizedTableName}`;
    },
  );
  if (!explicitTables.size && !explicitColumns.size) {
    return rawPrompt;
  }
  const hints: string[] = [];
  if (explicitTables.size) {
    hints.push(`用户显式指定的当前库表: ${Array.from(explicitTables).join(', ')}`);
  }
  if (explicitColumns.size) {
    hints.push(`用户显式指定的字段: ${Array.from(explicitColumns).join(', ')}`);
  }
  return [
    normalizedPrompt.trim(),
    '',
    '[当前库显式引用]',
    ...hints,
  ].join('\n');
}

export function buildChartPrompt(promptText: string) {
  return [
    'Please generate a chart plan.',
    'Requirements:',
    '1) Return executable SQL and structured chart config.',
    '2) 配置需包含图表类型、字段映射、排序建议；',
    '3) Use only the current database context.',
    `用户需求：${promptText}`,
  ].join('\n');
}

export function chartTypeLabel(chartType?: string) {
  const normalized = (chartType || '').toUpperCase();
  if (normalized === 'BAR') {
    return 'Bar';
  }
  if (normalized === 'PIE') {
    return '饼图';
  }
  if (normalized === 'SCATTER') {
    return 'Scatter';
  }
  if (normalized === 'TREND') {
    return 'Trend';
  }
  return 'Line';
}

export function autoActionTypeByIntent(intentType: AiIntentType): QueryActionType {
  if (intentType === 'EXPLAIN_SQL') {
    return 'auto_explain';
  }
  if (intentType === 'ANALYZE_SQL') {
    return 'auto_analyze';
  }
  if (intentType === 'GENERATE_CHART') {
    return 'auto_chart_auto_plan';
  }
  return 'auto_generate';
}

export function chartSummaryText(config?: ChartConfigVO | null) {
  if (!config) {
    return 'No usable chart config returned. Please configure manually.';
  }
  const type = chartTypeLabel(config.chartType);
  if ((config.chartType || '').toUpperCase() === 'PIE') {
    return `${type} · Category: ${config.categoryField || '-'} · Value: ${config.valueField || '-'}`;
  }
  if (supportsGroupedSeriesChart(config.chartType) && config.seriesField) {
    const y = config.yFields?.[0] || '-';
    return `${type} · X: ${config.xField || '-'} · Y: ${y} · Series: ${config.seriesField || '-'}`;
  }
  const y = (config.yFields || []).join(', ') || '-';
  return `${type} · X: ${config.xField || '-'} · Y: ${y}`;
}

export function dedupeChartMessageContent(
  content?: string,
  chartConfig?: ChartConfigVO | null,
  chartConfigSummary?: string,
) {
  const normalizedContent = (content || '').trim();
  if (!normalizedContent) {
    return '';
  }
  if (!chartConfig) {
    return normalizedContent;
  }
  const normalizedSummary = (chartConfigSummary || '').trim() || chartSummaryText(chartConfig).trim();
  if (normalizedSummary && normalizedContent === normalizedSummary) {
    return '';
  }
  return normalizedContent;
}

export function isChartConfigRenderable(config: ChartConfigVO | null | undefined, rows: Array<Record<string, string | null>>) {
  if (!config) {
    return false;
  }
  const fields = rows.length ? Object.keys(rows[0]) : [];
  const hasField = (field?: string) => !!field && fields.includes(field);
  const chartType = (config.chartType || '').toUpperCase();
  if (chartType === 'PIE') {
    return hasField(config.categoryField) && hasField(config.valueField);
  }
  if (chartType === 'SCATTER') {
    return hasField(config.xField) && !!config.yFields?.[0] && hasField(config.yFields[0]);
  }
  if (supportsGroupedSeriesChart(chartType) && config.seriesField) {
    return hasField(config.xField)
      && !!config.yFields?.[0]
      && config.yFields.length === 1
      && hasField(config.yFields[0])
      && hasField(config.seriesField);
  }
  return hasField(config.xField) && !!config.yFields?.length && config.yFields.every((field) => hasField(field));
}

interface GenerateChartFromMessageOptions {
  appendRenderMessage?: boolean;
  silentSuccess?: boolean;
}

interface AiInteractionHelperContext {
  appendAssistantSqlMessage: (
    tab: QueryWorkspaceTab,
    sqlText: string,
    actionType: QueryChatMessage['actionType'],
    content?: string,
    chartConfig?: ChartConfigVO | null,
    chartConfigSummary?: string,
    chartImageCacheKey?: string,
    traceOrTargetMessage?: AiTraceVO | QueryChatMessage,
    targetMessage?: QueryChatMessage,
  ) => QueryChatMessage;
  appendAssistantTextMessage: (
    tab: QueryWorkspaceTab,
    content: string,
    actionType: QueryChatMessage['actionType'],
    traceOrTargetMessage?: AiTraceVO | QueryChatMessage,
    targetMessage?: QueryChatMessage,
  ) => QueryChatMessage;
  appendAssistantThinkingMessage: (
    tab: QueryWorkspaceTab,
    actionType: QueryChatMessage['actionType'],
    thinkingEnabled?: boolean,
  ) => QueryChatMessage;
  appendUserChatMessage: (
    tab: QueryWorkspaceTab,
    promptText: string,
    actionType: QueryChatMessage['actionType'],
  ) => QueryChatMessage;
  applyResponseTokenSnapshot: (
    tab: QueryWorkspaceTab,
    result: {
      totalTokens?: number;
      requestPromptTokens?: number;
      requestCompletionTokens?: number;
      requestTotalTokens?: number;
      turnContentTokens?: number;
      promptBudget?: any;
    },
  ) => void;
  applyStreamTraceSnapshot: (messageItem: QueryChatMessage, trace?: AiTraceVO) => void;
  cacheChartImageWithRetry: (
    tab: QueryWorkspaceTab,
    suggestedFileName: string,
  ) => Promise<{imageDataUrl: string; cacheKey: string; cacheErrorMessage?: string}>;
  clearUserRetryState: (userMessage: QueryChatMessage) => void;
  conversationMemoryEnabledForTab: (tab: QueryWorkspaceTab | null | undefined) => boolean;
  detailOutputEnabledForTab: (tab: QueryWorkspaceTab | null | undefined) => boolean;
  getThinkingEnabled: () => boolean;
  enrichPromptWithSchemaReferences: (promptText: string) => string;
  ensureAssistantStreamingState: (
    tab: QueryWorkspaceTab,
    messageItem: QueryChatMessage,
    actionType?: QueryChatMessage['actionType'],
  ) => void;
  executeSqlForTab: (
    tab: QueryWorkspaceTab,
    sqlOverride?: string,
    options?: {silentSuccess?: boolean},
  ) => Promise<boolean>;
  flushStreamingQueryTab: (tab: QueryWorkspaceTab) => Promise<void>;
  getErrorMessage: (error: unknown) => string;
  getActiveChartRows: () => Array<Record<string, string | null>>;
  isAiRequestAbortedMessage: (rawMessage: string) => boolean;
  isTimeoutErrorMessage: (rawMessage: string) => boolean;
  looksLikeExecutableQueryText: (text: string, dbType: string) => boolean;
  markUserMessageRetryable: (tab: QueryWorkspaceTab, userMessage: QueryChatMessage, retryMeta: RetryRequestMeta) => void;
  materializeAssistantErrorMessage: (
    tab: QueryWorkspaceTab,
    targetMessage: QueryChatMessage | undefined,
    actionType: QueryChatMessage['actionType'],
    errorMessage: string,
  ) => void;
  mergePromptWithSqlSnippet: (promptText: string, selectedSqlText?: string) => string;
  normalizeChartCacheErrorMessage: (rawMessage: string) => string;
  postAiStreamWithTimeout: (
    tab: QueryWorkspaceTab,
    path: string,
    payload: unknown,
    onEvent: (event: {eventType: string; [key: string]: any}) => void | Promise<void>,
  ) => Promise<void>;
  queryTabDbType: (tab: QueryWorkspaceTab) => string;
  removeQueryChatMessage: (tab: QueryWorkspaceTab, messageItem: QueryChatMessage) => void;
  resolveSelectedSqlSnippet: (tab: QueryWorkspaceTab, sqlOverride?: string) => string;
  saveConversationHistory: (
    tab: QueryWorkspaceTab,
    promptText: string,
    sqlText: string,
    options?: any,
  ) => Promise<void>;
  saveConversationHistoryOnce: (
    tab: QueryWorkspaceTab,
    userMessage: QueryChatMessage,
    promptText: string,
    sqlText: string,
    options?: any,
  ) => Promise<void>;
  setupManualChartConfigByResult: (tab: QueryWorkspaceTab) => void;
  timeoutRetryErrorMessage: (rawMessage: string) => string;
  touchQueryTab: (tab: QueryWorkspaceTab) => void;
  upsertStreamingTraceLlmDelta: (
    messageItem: QueryChatMessage,
    actionType: QueryChatMessage['actionType'] | undefined,
    channel: 'thinking' | 'output',
    value: string,
  ) => void;
  upsertStreamingTraceStage: (messageItem: QueryChatMessage, stage: any) => void;
}

export function createAiInteractionHelpers(ctx: AiInteractionHelperContext) {
  async function generateSqlForTab(
    tab: QueryWorkspaceTab,
    actionType: AiActionType = 'generate',
    retryOptions?: RetryInvokeOptions,
  ) {
    if (tab.aiGenerating) {
      return;
    }
    const rawPrompt = retryOptions?.promptText ?? tab.prompt.trim();
    const actionSqlSnippet = retryOptions?.actionSqlSnippet ?? (actionType === 'generate'
      ? ''
      : ctx.resolveSelectedSqlSnippet(tab));
    if (actionType === 'generate' && !rawPrompt.trim()) {
      message.info('Please enter a natural language request first.');
      return;
    }
    if (actionType !== 'generate' && !rawPrompt.trim() && !actionSqlSnippet) {
      message.info('请先输入说明，或在右侧编辑器中选择 SQL');
      return;
    }
    const promptText = rawPrompt.trim() || (actionType === 'explain'
      ? 'Please explain this SQL.'
      : 'Please analyze whether this SQL is reasonable.');
    const normalizedPromptText = ctx.enrichPromptWithSchemaReferences(promptText);
    const userMessage = retryOptions?.userMessage ?? ctx.appendUserChatMessage(tab, promptText, actionType);
    const thinkingMessage = ctx.appendAssistantThinkingMessage(tab, actionType, ctx.getThinkingEnabled());
    if (!retryOptions) {
      tab.prompt = '';
    }
    const finalPrompt = retryOptions?.finalPrompt ?? (actionType === 'generate'
      ? normalizedPromptText
      : ctx.mergePromptWithSqlSnippet(normalizedPromptText, actionSqlSnippet));
    const retryMeta: RetryRequestMeta = {
      kind: 'ai_action',
      actionType,
      promptText,
      finalPrompt,
      actionSqlSnippet,
    };
    tab.aiGenerating = true;
    try {
      if (actionType === 'generate') {
        const streamState = {generated: null as AiGenerateSqlVO | null};
        await ctx.postAiStreamWithTimeout(tab, '/api/ai/query/generate/stream', {
          connectionId: tab.connectionId,
          sessionId: tab.sessionId,
          prompt: finalPrompt,
          databaseName: tab.databaseName || undefined,
          modelId: tab.selectedAiModel || undefined,
          memoryEnabled: ctx.conversationMemoryEnabledForTab(tab),
          detailOutputEnabled: ctx.detailOutputEnabledForTab(tab),
          thinkingEnabled: ctx.getThinkingEnabled(),
        }, (event) => {
          if (event.eventType === 'stage.updated' && event.stage) {
            ctx.ensureAssistantStreamingState(tab, thinkingMessage, actionType);
            ctx.upsertStreamingTraceStage(thinkingMessage, event.stage);
            return ctx.flushStreamingQueryTab(tab);
          }
          if (event.eventType === 'llm.thinking.delta' && thinkingMessage.thinkingEnabled) {
            ctx.ensureAssistantStreamingState(tab, thinkingMessage, actionType);
            thinkingMessage.thinkingContent = event.delta?.accumulatedText || thinkingMessage.thinkingContent || '';
            ctx.upsertStreamingTraceLlmDelta(thinkingMessage, actionType, 'thinking', thinkingMessage.thinkingContent || '');
            return ctx.flushStreamingQueryTab(tab);
          }
          if (event.eventType === 'llm.output.delta') {
            ctx.ensureAssistantStreamingState(tab, thinkingMessage, actionType);
            thinkingMessage.liveOutput = event.delta?.accumulatedText || thinkingMessage.liveOutput || '';
            thinkingMessage.content = thinkingMessage.liveOutput || thinkingMessage.content;
            ctx.upsertStreamingTraceLlmDelta(thinkingMessage, actionType, 'output', thinkingMessage.liveOutput || '');
            return ctx.flushStreamingQueryTab(tab);
          }
          if (event.eventType === 'trace.snapshot' && event.trace) {
            ctx.applyStreamTraceSnapshot(thinkingMessage, event.trace);
            return ctx.flushStreamingQueryTab(tab);
          }
          if (event.eventType === 'result.final') {
            streamState.generated = event.finalResult?.generateSql || null;
            return;
          }
          if (event.eventType === 'error') {
            throw new Error(event.error?.message || 'AI 流式请求失败');
          }
        });
        const generated = streamState.generated;
        if (!generated) {
          throw new Error('流式响应未返回最终结果');
        }
        ctx.applyResponseTokenSnapshot(tab, generated);
        const generatedText = (generated.sqlText || '').trim();
        if (ctx.looksLikeExecutableQueryText(generatedText, ctx.queryTabDbType(tab))) {
          ctx.appendAssistantSqlMessage(tab, generatedText, actionType, '', undefined, undefined, undefined, generated.trace, thinkingMessage);
          await ctx.saveConversationHistoryOnce(tab, userMessage, promptText, generatedText, {
            trace: generated.trace,
          });
          message.success('SQL generated.');
        } else {
          ctx.appendAssistantTextMessage(tab, generatedText || '未返回可执行 SQL', actionType, generated.trace, thinkingMessage);
          await ctx.saveConversationHistoryOnce(tab, userMessage, promptText, '', {
            actionType,
            assistantContent: generatedText || '未返回可执行 SQL',
            databaseName: tab.databaseName,
            trace: generated.trace,
          });
          message.warning('未生成可执行 SQL，已返回说明内容');
        }
        if (generated.reasoning) {
          message.info(generated.reasoning);
        }
        ctx.clearUserRetryState(userMessage);
        return;
      }

      const endpoint = actionType === 'explain' ? '/api/ai/query/explain' : '/api/ai/query/analyze';
      const streamState = {result: null as AiTextResponseVO | null};
      await ctx.postAiStreamWithTimeout(tab, `${endpoint}/stream`, {
        connectionId: tab.connectionId,
        sessionId: tab.sessionId,
        prompt: finalPrompt,
        databaseName: tab.databaseName || undefined,
        modelId: tab.selectedAiModel || undefined,
        memoryEnabled: ctx.conversationMemoryEnabledForTab(tab),
        detailOutputEnabled: ctx.detailOutputEnabledForTab(tab),
        thinkingEnabled: ctx.getThinkingEnabled(),
      }, (event) => {
        if (event.eventType === 'stage.updated' && event.stage) {
          ctx.ensureAssistantStreamingState(tab, thinkingMessage, actionType);
          ctx.upsertStreamingTraceStage(thinkingMessage, event.stage);
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'llm.thinking.delta' && thinkingMessage.thinkingEnabled) {
          ctx.ensureAssistantStreamingState(tab, thinkingMessage, actionType);
          thinkingMessage.thinkingContent = event.delta?.accumulatedText || thinkingMessage.thinkingContent || '';
          ctx.upsertStreamingTraceLlmDelta(thinkingMessage, actionType, 'thinking', thinkingMessage.thinkingContent || '');
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'llm.output.delta') {
          ctx.ensureAssistantStreamingState(tab, thinkingMessage, actionType);
          thinkingMessage.liveOutput = event.delta?.accumulatedText || thinkingMessage.liveOutput || '';
          thinkingMessage.content = thinkingMessage.liveOutput || thinkingMessage.content;
          ctx.upsertStreamingTraceLlmDelta(thinkingMessage, actionType, 'output', thinkingMessage.liveOutput || '');
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'trace.snapshot' && event.trace) {
          ctx.applyStreamTraceSnapshot(thinkingMessage, event.trace);
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'result.final') {
          streamState.result = event.finalResult?.textResponse || null;
          return;
        }
        if (event.eventType === 'error') {
          throw new Error(event.error?.message || 'AI 流式请求失败');
        }
      });
      const result = streamState.result;
      if (!result) {
        throw new Error('流式响应未返回最终结果');
      }
      ctx.applyResponseTokenSnapshot(tab, result);
      const content = result.content || 'No content returned.';
      ctx.appendAssistantTextMessage(tab, content, actionType, result.trace, thinkingMessage);
      await ctx.saveConversationHistoryOnce(tab, userMessage, promptText, actionSqlSnippet || '', {
        actionType,
        assistantContent: content,
        databaseName: tab.databaseName,
        trace: result.trace,
      });
      if (result.reasoning) {
        message.info(result.reasoning);
      }
      message.success(actionType === 'explain' ? 'SQL explanation generated.' : 'SQL analysis generated.');
      ctx.clearUserRetryState(userMessage);
    } catch (error) {
      const msg = ctx.getErrorMessage(error);
      if (ctx.isAiRequestAbortedMessage(msg)) {
        thinkingMessage.pending = false;
        thinkingMessage.streaming = false;
        thinkingMessage.aborted = true;
        if (!thinkingMessage.content && !thinkingMessage.liveOutput && !thinkingMessage.thinkingContent) {
          ctx.removeQueryChatMessage(tab, thinkingMessage);
        }
        ctx.clearUserRetryState(userMessage);
        message.info('Conversation was stopped.');
        return;
      }
      ctx.materializeAssistantErrorMessage(tab, thinkingMessage, actionType, msg);
      if (ctx.isTimeoutErrorMessage(msg)) {
        ctx.markUserMessageRetryable(tab, userMessage, retryMeta);
        message.error(ctx.timeoutRetryErrorMessage(msg));
      } else {
        ctx.clearUserRetryState(userMessage);
        message.error(msg);
      }
    } finally {
      tab.aiGenerating = false;
      ctx.touchQueryTab(tab);
    }
  }

  async function generateChartPlanForTab(tab: QueryWorkspaceTab, retryOptions?: RetryInvokeOptions) {
    if (tab.aiGenerating) {
      return;
    }
    const rawPrompt = retryOptions?.promptText ?? tab.prompt.trim();
    if (!rawPrompt) {
      message.info('Please enter chart requirements first.');
      return;
    }
    const normalizedPromptText = ctx.enrichPromptWithSchemaReferences(rawPrompt);
    const finalPrompt = retryOptions?.finalPrompt ?? buildChartPrompt(normalizedPromptText);
    const userMessage = retryOptions?.userMessage ?? ctx.appendUserChatMessage(tab, rawPrompt, 'chart_auto_plan');
    const thinkingMessage = ctx.appendAssistantThinkingMessage(tab, 'chart_auto_plan', ctx.getThinkingEnabled());
    if (!retryOptions) {
      tab.prompt = '';
    }
    const retryMeta: RetryRequestMeta = {
      kind: 'chart_plan',
      promptText: rawPrompt,
      finalPrompt,
    };
    tab.aiGenerating = true;
    try {
      const streamState = {generated: null as AiGenerateChartVO | null};
      await ctx.postAiStreamWithTimeout(tab, '/api/ai/query/generate-chart/stream', {
        connectionId: tab.connectionId,
        sessionId: tab.sessionId,
        prompt: finalPrompt,
        databaseName: tab.databaseName || undefined,
        modelId: tab.selectedAiModel || undefined,
        memoryEnabled: ctx.conversationMemoryEnabledForTab(tab),
        detailOutputEnabled: ctx.detailOutputEnabledForTab(tab),
        thinkingEnabled: ctx.getThinkingEnabled(),
      }, (event) => {
        if (event.eventType === 'stage.updated' && event.stage) {
          ctx.ensureAssistantStreamingState(tab, thinkingMessage, 'chart_auto_plan');
          ctx.upsertStreamingTraceStage(thinkingMessage, event.stage);
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'llm.thinking.delta' && thinkingMessage.thinkingEnabled) {
          ctx.ensureAssistantStreamingState(tab, thinkingMessage, 'chart_auto_plan');
          thinkingMessage.thinkingContent = event.delta?.accumulatedText || thinkingMessage.thinkingContent || '';
          ctx.upsertStreamingTraceLlmDelta(thinkingMessage, 'chart_auto_plan', 'thinking', thinkingMessage.thinkingContent || '');
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'llm.output.delta') {
          ctx.ensureAssistantStreamingState(tab, thinkingMessage, 'chart_auto_plan');
          thinkingMessage.liveOutput = event.delta?.accumulatedText || thinkingMessage.liveOutput || '';
          thinkingMessage.content = thinkingMessage.liveOutput || thinkingMessage.content;
          ctx.upsertStreamingTraceLlmDelta(thinkingMessage, 'chart_auto_plan', 'output', thinkingMessage.liveOutput || '');
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'trace.snapshot' && event.trace) {
          ctx.applyStreamTraceSnapshot(thinkingMessage, event.trace);
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'result.final') {
          streamState.generated = event.finalResult?.generateChart || null;
          return;
        }
        if (event.eventType === 'error') {
          throw new Error(event.error?.message || 'AI 流式请求失败');
        }
      });
      const generated = streamState.generated;
      if (!generated) {
        throw new Error('流式响应未返回最终结果');
      }
      ctx.applyResponseTokenSnapshot(tab, generated);
      const sqlText = (generated.sqlText || '').trim();
      const config = generated.chartConfig ? cloneChartConfig(generated.chartConfig) : null;
      const summary = (generated.configSummary || '').trim() || chartSummaryText(config);
      if (!sqlText) {
        ctx.appendAssistantTextMessage(tab, summary || 'No chart plan returned.', 'chart_auto_plan', generated.trace, thinkingMessage);
        message.warning('未生成可执行 SQL');
        return;
      }
      const plannedMessage = ctx.appendAssistantSqlMessage(
        tab,
        sqlText,
        'chart_auto_plan',
        summary,
        config,
        summary,
        undefined,
        generated.trace,
        thinkingMessage,
      );
      await ctx.saveConversationHistoryOnce(tab, userMessage, rawPrompt, sqlText, {
        actionType: 'chart_auto_plan',
        assistantContent: summary,
        chartConfig: config,
        databaseName: tab.databaseName,
        trace: generated.trace,
      });
      if (generated.reasoning) {
        message.info(generated.reasoning);
      }
      const generatedChart = await generateChartFromMessage(tab, plannedMessage, {
        appendRenderMessage: false,
        silentSuccess: true,
      });
      if (!generatedChart) {
        message.warning('Chart plan generated, but auto execution failed. Please click Generate Chart manually.');
        return;
      }
      message.success('AI 图表方案已执行并生成图表');
      ctx.clearUserRetryState(userMessage);
    } catch (error) {
      const msg = ctx.getErrorMessage(error);
      if (ctx.isAiRequestAbortedMessage(msg)) {
        thinkingMessage.pending = false;
        thinkingMessage.streaming = false;
        thinkingMessage.aborted = true;
        if (!thinkingMessage.content && !thinkingMessage.liveOutput && !thinkingMessage.thinkingContent) {
          ctx.removeQueryChatMessage(tab, thinkingMessage);
        }
        ctx.clearUserRetryState(userMessage);
        message.info('Conversation was stopped.');
        return;
      }
      ctx.materializeAssistantErrorMessage(tab, thinkingMessage, 'chart_auto_plan', msg);
      if (ctx.isTimeoutErrorMessage(msg)) {
        ctx.markUserMessageRetryable(tab, userMessage, retryMeta);
        message.error(ctx.timeoutRetryErrorMessage(msg));
      } else {
        ctx.clearUserRetryState(userMessage);
        message.error(msg);
      }
    } finally {
      tab.aiGenerating = false;
      ctx.touchQueryTab(tab);
    }
  }

  async function generateChartFromMessage(
    tab: QueryWorkspaceTab,
    item: QueryChatMessage,
    options?: GenerateChartFromMessageOptions,
  ) {
    const sqlText = (item.sqlText || '').trim();
    if (!sqlText) {
      message.warning('Current message does not contain SQL. Unable to generate chart.');
      return;
    }
    const success = await ctx.executeSqlForTab(tab, sqlText, {silentSuccess: true});
    if (!success) {
      return;
    }
    if (latestSuccessfulStatementResult(tab)?.executeResult) {
      item.executionPreview = buildExecutionPreview(latestSuccessfulStatementResult(tab)?.executeResult as SqlExecuteVO);
    }

    const rows = ctx.getActiveChartRows();
    let config = item.chartConfig ? cloneChartConfig(item.chartConfig) : null;
    if (!isChartConfigRenderable(config, rows)) {
      ctx.setupManualChartConfigByResult(tab);
      config = cloneChartConfig(tab.manualChartConfig);
      message.warning('AI chart config does not match result fields. Switched to manual default config.');
    } else {
      tab.manualChartConfig = cloneChartConfig(config);
    }
    tab.activeChartConfig = config;
    tab.resultViewMode = 'chart';
    tab.chartReadonly = false;
    ctx.touchQueryTab(tab);

    const cached = await ctx.cacheChartImageWithRetry(tab, `chart-auto-${Date.now()}`);
    const imageDataUrl = cached.imageDataUrl;
    if (!imageDataUrl) {
      message.warning('图表渲染完成，但图片导出失败');
      return false;
    }
    tab.chartImageDataUrl = imageDataUrl;
    item.chartImageDataUrl = imageDataUrl;
    const cacheKey = cached.cacheKey || '';
    tab.chartImageCacheKey = cacheKey;
    item.chartImageCacheKey = cacheKey;
    ctx.touchQueryTab(tab);
    if (cached.cacheErrorMessage) {
      message.warning(ctx.normalizeChartCacheErrorMessage(cached.cacheErrorMessage));
    }
    if (options?.appendRenderMessage !== false) {
      const renderMessage = ctx.appendAssistantSqlMessage(
        tab,
        sqlText,
        'chart_auto_render',
        'Chart generated.',
        config,
        chartSummaryText(config),
        cacheKey,
      );
      renderMessage.chartImageDataUrl = imageDataUrl;
    }
    await ctx.saveConversationHistory(tab, '生成图表', sqlText, {
      actionType: 'chart_auto_render',
      assistantContent: chartSummaryText(config),
      chartConfig: config,
      chartImageCacheKey: cacheKey,
      databaseName: tab.databaseName,
    });
    if (!options?.silentSuccess) {
      message.success('Chart generated.');
    }
    return true;
  }

  async function generateManualChartForTab(tab: QueryWorkspaceTab) {
    if (!tab.executeResult?.rows?.length) {
      message.info('当前没有可用于制图的查询结果');
      return;
    }
    const config = cloneChartConfig(tab.manualChartConfig);
    const rows = ctx.getActiveChartRows();
    if (!isChartConfigRenderable(config, rows)) {
      message.warning('图表字段配置不完整，请先选择有效字段');
      return;
    }
    tab.activeChartConfig = config;
    tab.resultViewMode = 'chart';
    tab.chartReadonly = false;
    ctx.touchQueryTab(tab);

    const cached = await ctx.cacheChartImageWithRetry(tab, `chart-manual-${Date.now()}`);
    const imageDataUrl = cached.imageDataUrl;
    if (!imageDataUrl) {
      message.warning('图表渲染完成，但图片导出失败');
      return;
    }
    tab.chartImageDataUrl = imageDataUrl;
    tab.chartImageCacheKey = cached.cacheKey || '';
    ctx.touchQueryTab(tab);
    if (cached.cacheErrorMessage) {
      message.warning(ctx.normalizeChartCacheErrorMessage(cached.cacheErrorMessage));
    }
    message.success('Manual chart generated.');
  }

  async function editChartFromHistory(tab: QueryWorkspaceTab, item: QueryChatMessage) {
    await generateChartFromMessage(tab, item);
  }

  async function sendAutoForTab(tab: QueryWorkspaceTab, retryOptions?: RetryInvokeOptions) {
    if (tab.aiGenerating) {
      return;
    }
    const rawPrompt = retryOptions?.promptText ?? tab.prompt.trim();
    if (!rawPrompt) {
      message.info('Please enter a natural language request first.');
      return;
    }
    const sqlSnippet = retryOptions?.actionSqlSnippet ?? ctx.resolveSelectedSqlSnippet(tab);
    const normalizedPromptText = ctx.enrichPromptWithSchemaReferences(rawPrompt);
    const finalPrompt = retryOptions?.finalPrompt ?? ctx.mergePromptWithSqlSnippet(normalizedPromptText, sqlSnippet);
    const userMessage = retryOptions?.userMessage ?? ctx.appendUserChatMessage(tab, rawPrompt, 'auto_generate');
    const thinkingMessage = ctx.appendAssistantThinkingMessage(tab, 'auto_generate', false);
    if (!retryOptions) {
      tab.prompt = '';
    }
    const retryMeta: RetryRequestMeta = {
      kind: 'auto',
      promptText: rawPrompt,
      finalPrompt,
      actionSqlSnippet: sqlSnippet,
    };
    tab.aiGenerating = true;
    try {
      const streamState = {result: null as AiAutoQueryVO | null};
      // 意图识别固定关闭思考模式，提升响应速度
      await ctx.postAiStreamWithTimeout(tab, '/api/ai/query/auto/stream', {
        connectionId: tab.connectionId,
        sessionId: tab.sessionId,
        prompt: finalPrompt,
        databaseName: tab.databaseName || undefined,
        modelId: tab.selectedAiModel || undefined,
        memoryEnabled: ctx.conversationMemoryEnabledForTab(tab),
        detailOutputEnabled: ctx.detailOutputEnabledForTab(tab),
        thinkingEnabled: false,
      }, (event) => {
        if (event.eventType === 'intent.resolved' && event.intent?.intentType) {
          thinkingMessage.actionType = autoActionTypeByIntent(event.intent.intentType as AiIntentType);
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'stage.updated' && event.stage) {
          ctx.ensureAssistantStreamingState(tab, thinkingMessage, thinkingMessage.actionType);
          ctx.upsertStreamingTraceStage(thinkingMessage, event.stage);
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'llm.thinking.delta' && thinkingMessage.thinkingEnabled) {
          ctx.ensureAssistantStreamingState(tab, thinkingMessage, thinkingMessage.actionType);
          thinkingMessage.thinkingContent = event.delta?.accumulatedText || thinkingMessage.thinkingContent || '';
          ctx.upsertStreamingTraceLlmDelta(thinkingMessage, thinkingMessage.actionType, 'thinking', thinkingMessage.thinkingContent || '');
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'llm.output.delta') {
          ctx.ensureAssistantStreamingState(tab, thinkingMessage, thinkingMessage.actionType);
          thinkingMessage.liveOutput = event.delta?.accumulatedText || thinkingMessage.liveOutput || '';
          thinkingMessage.content = thinkingMessage.liveOutput || thinkingMessage.content;
          ctx.upsertStreamingTraceLlmDelta(thinkingMessage, thinkingMessage.actionType, 'output', thinkingMessage.liveOutput || '');
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'trace.snapshot' && event.trace) {
          ctx.applyStreamTraceSnapshot(thinkingMessage, event.trace);
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'result.final' && event.finalResult?.autoQuery) {
          streamState.result = event.finalResult.autoQuery;
          return;
        }
        if (event.eventType === 'error') {
          throw new Error(event.error?.message || 'AI 流式请求失败');
        }
      });
      const result = streamState.result;
      if (!result) {
        throw new Error('流式响应未返回最终结果');
      }
      const latestTokenEstimate = result.totalTokens;
      if (latestTokenEstimate != null) {
        ctx.applyResponseTokenSnapshot(tab, result);
      }
      const actionType = autoActionTypeByIntent(result.intentType);
      if (result.intentType === 'GENERATE_SQL') {
        const sqlText = (result.sqlText || '').trim();
        if (ctx.looksLikeExecutableQueryText(sqlText, ctx.queryTabDbType(tab))) {
          const assistantMessage = ctx.appendAssistantSqlMessage(
            tab,
            sqlText,
            actionType,
            '',
            undefined,
            undefined,
            undefined,
            result.trace,
            thinkingMessage,
          );
          await ctx.saveConversationHistoryOnce(tab, userMessage, rawPrompt, sqlText, {
            actionType,
            databaseName: tab.databaseName,
            trace: result.trace,
          });
          if (tab.autoExecute) {
            const executed = await ctx.executeSqlForTab(tab, sqlText, {silentSuccess: true});
            if (!executed) {
              message.warning('SQL generated, but auto execution failed.');
            } else if (latestSuccessfulStatementResult(tab)?.executeResult) {
              assistantMessage.executionPreview = buildExecutionPreview(latestSuccessfulStatementResult(tab)?.executeResult as SqlExecuteVO);
              ctx.touchQueryTab(tab);
            }
          }
        } else {
          const contentText = sqlText || '未返回可执行 SQL';
          ctx.appendAssistantTextMessage(tab, contentText, actionType, result.trace, thinkingMessage);
          await ctx.saveConversationHistoryOnce(tab, userMessage, rawPrompt, '', {
            actionType,
            assistantContent: contentText,
            databaseName: tab.databaseName,
            trace: result.trace,
          });
        }
      } else if (result.intentType === 'GENERATE_CHART') {
        const sqlText = (result.sqlText || '').trim();
        const config = result.chartConfig ? cloneChartConfig(result.chartConfig) : null;
        const summary = (result.configSummary || '').trim() || chartSummaryText(config);
        if (!sqlText) {
          ctx.appendAssistantTextMessage(tab, summary || 'No chart plan returned.', actionType, result.trace, thinkingMessage);
          await ctx.saveConversationHistoryOnce(tab, userMessage, rawPrompt, '', {
            actionType,
            assistantContent: summary || 'No chart plan returned.',
            chartConfig: config,
            databaseName: tab.databaseName,
            trace: result.trace,
          });
        } else {
          const plannedMessage = ctx.appendAssistantSqlMessage(
            tab,
            sqlText,
            actionType,
            summary,
            config,
            summary,
            undefined,
            result.trace,
            thinkingMessage,
          );
          await ctx.saveConversationHistoryOnce(tab, userMessage, rawPrompt, sqlText, {
            actionType,
            assistantContent: summary,
            chartConfig: config,
            databaseName: tab.databaseName,
            trace: result.trace,
          });
          const generatedChart = await generateChartFromMessage(tab, plannedMessage, {
            appendRenderMessage: false,
            silentSuccess: true,
          });
          if (!generatedChart) {
            message.warning('Chart plan generated, but auto execution failed. Please click Generate Chart manually.');
          }
        }
      } else if (result.intentType === 'EXPLAIN_SQL' || result.intentType === 'ANALYZE_SQL') {
        const content = (result.content || '').trim() || 'No content returned.';
        ctx.appendAssistantTextMessage(tab, content, actionType, result.trace, thinkingMessage);
        await ctx.saveConversationHistoryOnce(tab, userMessage, rawPrompt, sqlSnippet || '', {
          actionType,
          assistantContent: content,
          databaseName: tab.databaseName,
          trace: result.trace,
        });
      } else {
        throw new Error('未识别的 Auto 意图类型');
      }

      if (result.fallbackUsed) {
        message.warning('Auto mode fell back. Please check returned content.');
      }
      ctx.clearUserRetryState(userMessage);
    } catch (error) {
      const msg = ctx.getErrorMessage(error);
      if (ctx.isAiRequestAbortedMessage(msg)) {
        thinkingMessage.pending = false;
        thinkingMessage.streaming = false;
        thinkingMessage.aborted = true;
        if (!thinkingMessage.content && !thinkingMessage.liveOutput && !thinkingMessage.thinkingContent) {
          ctx.removeQueryChatMessage(tab, thinkingMessage);
        }
        ctx.clearUserRetryState(userMessage);
        message.info('Conversation was stopped.');
        return;
      }
      ctx.materializeAssistantErrorMessage(tab, thinkingMessage, thinkingMessage.actionType, msg);
      if (ctx.isTimeoutErrorMessage(msg)) {
        ctx.markUserMessageRetryable(tab, userMessage, retryMeta);
        message.error(ctx.timeoutRetryErrorMessage(msg));
      } else {
        ctx.clearUserRetryState(userMessage);
        message.error(msg);
      }
    } finally {
      tab.aiGenerating = false;
      ctx.touchQueryTab(tab);
    }
  }

  async function repairSqlForTab(tab: QueryWorkspaceTab) {
    if (!tab.lastExecuteFailed) {
      message.info('最近一次 SQL 执行未失败，无需修复');
      return;
    }
    const failedSql = tab.lastFailedSqlText.trim();
    const errorMessage = tab.lastExecuteErrorMessage.trim();
    if (!failedSql || !errorMessage) {
      message.error('缺少失败 SQL 或错误信息，无法执行修复');
      return;
    }
    if (tab.aiGenerating) {
      return;
    }
    let thinkingMessage: QueryChatMessage | undefined;
    tab.aiGenerating = true;
    try {
      const promptText = `请修复以下 SQL 执行错误。\n错误信息：${errorMessage}\n\nSQL:\n${failedSql}`;
      const userMessage = ctx.appendUserChatMessage(tab, promptText, 'repair');
      thinkingMessage = ctx.appendAssistantThinkingMessage(tab, 'repair', ctx.getThinkingEnabled());
      const streamState = {repaired: null as AiRepairVO | null};
      await ctx.postAiStreamWithTimeout(tab, '/api/ai/query/repair/stream', {
        connectionId: tab.connectionId,
        sessionId: tab.sessionId,
        sqlText: failedSql,
        errorMessage,
        databaseName: tab.databaseName || undefined,
        modelId: tab.selectedAiModel || undefined,
        detailOutputEnabled: ctx.detailOutputEnabledForTab(tab),
        thinkingEnabled: ctx.getThinkingEnabled(),
      }, (event) => {
        if (!thinkingMessage) {
          return;
        }
        if (event.eventType === 'stage.updated' && event.stage) {
          ctx.ensureAssistantStreamingState(tab, thinkingMessage, 'repair');
          ctx.upsertStreamingTraceStage(thinkingMessage, event.stage);
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'llm.thinking.delta' && thinkingMessage.thinkingEnabled) {
          ctx.ensureAssistantStreamingState(tab, thinkingMessage, 'repair');
          thinkingMessage.thinkingContent = event.delta?.accumulatedText || thinkingMessage.thinkingContent || '';
          ctx.upsertStreamingTraceLlmDelta(thinkingMessage, 'repair', 'thinking', thinkingMessage.thinkingContent || '');
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'llm.output.delta') {
          ctx.ensureAssistantStreamingState(tab, thinkingMessage, 'repair');
          thinkingMessage.liveOutput = event.delta?.accumulatedText || thinkingMessage.liveOutput || '';
          thinkingMessage.content = thinkingMessage.liveOutput || thinkingMessage.content;
          ctx.upsertStreamingTraceLlmDelta(thinkingMessage, 'repair', 'output', thinkingMessage.liveOutput || '');
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'trace.snapshot' && event.trace) {
          ctx.applyStreamTraceSnapshot(thinkingMessage, event.trace);
          return ctx.flushStreamingQueryTab(tab);
        }
        if (event.eventType === 'result.final') {
          streamState.repaired = event.finalResult?.repair || null;
          return;
        }
        if (event.eventType === 'error') {
          throw new Error(event.error?.message || 'AI 流式请求失败');
        }
      });
      const repaired = streamState.repaired;
      if (!repaired) {
        throw new Error('流式响应未返回最终结果');
      }
      const repairedSql = (repaired.repairedSql || failedSql || '').trim();
      const assistantContent = (repaired.errorExplanation || repaired.repairNote || '已尝试修复 SQL').trim();
      ctx.appendAssistantSqlMessage(
        tab,
        repairedSql,
        'repair',
        assistantContent,
        undefined,
        undefined,
        undefined,
        repaired.trace,
        thinkingMessage,
      );
      await ctx.saveConversationHistoryOnce(tab, userMessage, promptText, repairedSql, {
        actionType: 'repair',
        assistantContent,
        databaseName: tab.databaseName,
        trace: repaired.trace,
      });
      tab.lastExecuteFailed = false;
      tab.lastExecuteErrorMessage = '';
      tab.lastFailedSqlText = '';
      ctx.touchQueryTab(tab);
      message.success(repaired.repairNote || 'Repair suggestion generated.');
    } catch (error) {
      const errMsg = ctx.getErrorMessage(error);
      if (ctx.isAiRequestAbortedMessage(errMsg) && thinkingMessage) {
        thinkingMessage.pending = false;
        thinkingMessage.streaming = false;
        thinkingMessage.aborted = true;
        if (!thinkingMessage.content && !thinkingMessage.liveOutput && !thinkingMessage.thinkingContent) {
          ctx.removeQueryChatMessage(tab, thinkingMessage);
        }
        message.info('Conversation was stopped.');
        return;
      }
      ctx.materializeAssistantErrorMessage(tab, thinkingMessage, 'repair', errMsg);
      message.error(errMsg);
    } finally {
      tab.aiGenerating = false;
    }
  }

  return {
    autoActionTypeByIntent,
    editChartFromHistory,
    generateChartFromMessage,
    generateChartPlanForTab,
    generateManualChartForTab,
    generateSqlForTab,
    repairSqlForTab,
    sendAutoForTab,
  };
}
