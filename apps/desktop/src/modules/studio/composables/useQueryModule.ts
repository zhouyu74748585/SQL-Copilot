import {message} from 'ant-design-vue';
import type {StudioRuntime} from './useStudioRuntime';

type QueryTab = StudioRuntime['queryTabs']['value'][number];
type QueryMessage = QueryTab['chatMessages'][number];

export interface QueryModule {
  handleChatComposerKeydown: (event: KeyboardEvent, tab: QueryTab) => void;
  appendSqlToEditor: (tab: QueryTab, sqlText: string) => void;
  appendSelectedSqlToPrompt: (tab: QueryTab) => void;
  terminateAiExecutionForTab: (tab: QueryTab) => void;
  terminateSqlExecutionForTab: (tab: QueryTab) => void;
  retryUserMessage: (tab: QueryTab, userMessage: QueryMessage) => Promise<void>;
}

export function useQueryModule(runtime: StudioRuntime): QueryModule {
  function appendSqlToEditor(tab: QueryTab, sqlText: string) {
    const value = sqlText.trim();
    if (!value) {
      return;
    }
    tab.sqlText = tab.sqlText.trim() ? `${tab.sqlText.trim()}\n\n${value}` : value;
    tab.selectedSqlText = '';
    runtime.hideSqlSelectionPopover();
    runtime.touchQueryTab(tab);
    runtime.activeWorkbenchTab.value = tab.key;
  }

  function appendSelectedSqlToPrompt(tab: QueryTab) {
    const value = tab.selectedSqlText.trim();
    if (!value) {
      message.info('请先在右侧 SQL 编辑器中选择一段 SQL');
      return;
    }
    tab.prompt = tab.prompt.trim() ? `${tab.prompt.trim()}\n${value}` : value;
    runtime.touchQueryTab(tab);
    runtime.hideSqlSelectionPopover();
  }

  function terminateAiExecutionForTab(tab: QueryTab) {
    const controller = runtime.aiRequestAbortControllerMap.get(tab.key);
    if (!controller) {
      return;
    }
    runtime.aiRequestAbortReasonMap.set(tab.key, 'manual');
    controller.abort();
  }

  function terminateSqlExecutionForTab(tab: QueryTab) {
    const controller = runtime.sqlExecutionAbortControllerMap.get(tab.key);
    if (!controller) {
      return;
    }
    runtime.sqlExecutionAbortReasonMap.set(tab.key, 'manual');
    controller.abort();
  }

  function handleChatComposerKeydown(event: KeyboardEvent, tab: QueryTab) {
    if (!tab.autoMode) {
      return;
    }
    if (event.isComposing) {
      return;
    }
    if (event.key !== 'Enter') {
      return;
    }
    if (event.shiftKey) {
      return;
    }
    event.preventDefault();
    if (tab.aiGenerating) {
      return;
    }
    void runtime.sendAutoForTab(tab);
  }

  async function retryUserMessage(tab: QueryTab, userMessage: QueryMessage) {
    const retryMeta = userMessage.retryMeta;
    if (!retryMeta || tab.aiGenerating) {
      return;
    }
    userMessage.retryLoading = true;
    runtime.touchQueryTab(tab);
    try {
      const retryOptions = {
        userMessage,
        promptText: retryMeta.promptText,
        finalPrompt: retryMeta.finalPrompt,
        actionSqlSnippet: retryMeta.actionSqlSnippet || '',
      };
      if (retryMeta.kind === 'ai_action') {
        await runtime.generateSqlForTab(tab, retryMeta.actionType || 'generate', retryOptions);
        return;
      }
      if (retryMeta.kind === 'auto') {
        await runtime.sendAutoForTab(tab, retryOptions);
        return;
      }
      await runtime.generateChartPlanForTab(tab, retryOptions);
    } finally {
      userMessage.retryLoading = false;
      runtime.touchQueryTab(tab);
    }
  }

  return {
    handleChatComposerKeydown,
    appendSqlToEditor,
    appendSelectedSqlToPrompt,
    terminateAiExecutionForTab,
    terminateSqlExecutionForTab,
    retryUserMessage,
  };
}
