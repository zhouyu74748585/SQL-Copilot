import {postApi, postSseApi} from '../../../../api/client';
import type {AiStreamEventVO} from '../../../../types';
import {AI_REQUEST_ABORTED, aiRequestTimeoutMs} from './constants';
import type {
  QueryChatMessage,
  QueryWorkspaceTab,
  RequestAbortReason,
  RetryRequestMeta,
} from './types';

interface AiRequestHelperContext {
  aiRequestAbortControllerMap: Map<string, AbortController>;
  aiRequestAbortReasonMap: Map<string, RequestAbortReason>;
  queryEditorModeByDbType: (dbType: string) => string;
  touchQueryTab: (tab: QueryWorkspaceTab) => void;
}

export function createAiRequestHelpers(ctx: AiRequestHelperContext) {
  function timeoutRetryErrorMessage(rawMessage: string) {
    const normalized = rawMessage.trim();
    if (!normalized) {
      return '请求超时，请点击重试';
    }
    return normalized;
  }

  function isTimeoutErrorMessage(rawMessage: string) {
    const normalized = rawMessage.trim().toLowerCase();
    return normalized.includes('timeout')
      || normalized.includes('timed out')
      || normalized.includes('超时')
      || normalized.includes('http 504')
      || normalized.includes('http 408');
  }

  function getErrorMessage(error: unknown) {
    return error instanceof Error ? error.message : String(error);
  }

  function isAbortError(error: unknown) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      return true;
    }
    const normalized = getErrorMessage(error).trim().toLowerCase();
    return normalized.includes('abort');
  }

  function clearUserRetryState(userMessage: QueryChatMessage) {
    userMessage.retryable = false;
    userMessage.retryLoading = false;
    userMessage.retryMeta = undefined;
  }

  function markUserMessageRetryable(
    tab: QueryWorkspaceTab,
    userMessage: QueryChatMessage,
    retryMeta: RetryRequestMeta,
  ) {
    userMessage.retryable = true;
    userMessage.retryLoading = false;
    userMessage.retryMeta = retryMeta;
    ctx.touchQueryTab(tab);
  }

  function mergePromptWithSqlSnippet(promptText: string, selectedSqlText?: string) {
    const basePrompt = promptText.trim();
    const snippet = (selectedSqlText ?? '').trim();
    if (!snippet) {
      return basePrompt;
    }
    if (!basePrompt) {
      return snippet;
    }
    return [
      basePrompt,
      '',
      snippet,
    ].join('\n');
  }

  function isAiRequestAbortedMessage(rawMessage: string) {
    return rawMessage.trim() === AI_REQUEST_ABORTED;
  }

  async function postAiApiWithTimeout<T>(
    tab: QueryWorkspaceTab,
    path: string,
    payload: unknown,
    timeoutMs = aiRequestTimeoutMs,
  ) {
    const controller = new AbortController();
    ctx.aiRequestAbortControllerMap.set(tab.key, controller);
    ctx.aiRequestAbortReasonMap.delete(tab.key);
    const timeoutHandle = window.setTimeout(() => {
      ctx.aiRequestAbortReasonMap.set(tab.key, 'timeout');
      controller.abort();
    }, timeoutMs);
    try {
      return await postApi<T>(path, payload, {
        signal: controller.signal,
      });
    } catch (error) {
      if (isAbortError(error)) {
        const reason = ctx.aiRequestAbortReasonMap.get(tab.key);
        if (reason === 'timeout') {
          throw new Error(`请求超时（${Math.floor(timeoutMs / 1000)}s）`);
        }
        throw new Error(AI_REQUEST_ABORTED);
      }
      throw error;
    } finally {
      window.clearTimeout(timeoutHandle);
      if (ctx.aiRequestAbortControllerMap.get(tab.key) === controller) {
        ctx.aiRequestAbortControllerMap.delete(tab.key);
      }
      ctx.aiRequestAbortReasonMap.delete(tab.key);
    }
  }

  async function postAiStreamWithTimeout(
    tab: QueryWorkspaceTab,
    path: string,
    payload: unknown,
    onEvent: (event: AiStreamEventVO) => void,
    timeoutMs = aiRequestTimeoutMs,
  ) {
    const controller = new AbortController();
    ctx.aiRequestAbortControllerMap.set(tab.key, controller);
    ctx.aiRequestAbortReasonMap.delete(tab.key);
    const timeoutHandle = window.setTimeout(() => {
      ctx.aiRequestAbortReasonMap.set(tab.key, 'timeout');
      controller.abort();
    }, timeoutMs);
    try {
      await postSseApi<AiStreamEventVO>(path, payload, {
        signal: controller.signal,
        onEvent: ({data}) => onEvent(data),
      });
    } catch (error) {
      if (isAbortError(error)) {
        const reason = ctx.aiRequestAbortReasonMap.get(tab.key);
        if (reason === 'timeout') {
          throw new Error(`请求超时（${Math.floor(timeoutMs / 1000)}s）`);
        }
        throw new Error(AI_REQUEST_ABORTED);
      }
      throw error;
    } finally {
      window.clearTimeout(timeoutHandle);
      if (ctx.aiRequestAbortControllerMap.get(tab.key) === controller) {
        ctx.aiRequestAbortControllerMap.delete(tab.key);
      }
      ctx.aiRequestAbortReasonMap.delete(tab.key);
    }
  }

  function looksLikeExecutableQueryText(text: string, dbType: string) {
    const normalized = text.trim().toLowerCase();
    if (!normalized) {
      return false;
    }
    const mode = ctx.queryEditorModeByDbType(dbType);
    if (mode === 'json') {
      return normalized.startsWith('{') && normalized.includes('"collection"');
    }
    if (mode === 'redis') {
      return /^(get|type|ttl|exists|hgetall|lrange|smembers|zrange|keys|scan)\b/.test(normalized);
    }
    return /^(select|with|insert|update|delete|replace|create|alter|drop|truncate|merge|show|explain)\b/.test(normalized);
  }

  function looksLikeSqlText(text: string) {
    return looksLikeExecutableQueryText(text, 'MYSQL');
  }

  return {
    timeoutRetryErrorMessage,
    isTimeoutErrorMessage,
    getErrorMessage,
    isAbortError,
    clearUserRetryState,
    markUserMessageRetryable,
    mergePromptWithSqlSnippet,
    isAiRequestAbortedMessage,
    postAiApiWithTimeout,
    postAiStreamWithTimeout,
    looksLikeExecutableQueryText,
    looksLikeSqlText,
  };
}
