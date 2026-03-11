import {message} from 'ant-design-vue';
import {nextTick, reactive} from 'vue';
import type {StudioRuntime} from './useStudioRuntime';

type QueryTab = StudioRuntime['queryTabs']['value'][number];
type QueryMessage = QueryTab['chatMessages'][number];

type QueryPromptAssistMode = 'table' | 'column';

interface QueryPromptAssistItem {
  key: string;
  kind: QueryPromptAssistMode;
  label: string;
  tableName: string;
  columnName?: string;
  meta?: string;
  description?: string;
}

interface QueryPromptAssistContext {
  mode: QueryPromptAssistMode;
  keyword: string;
  tableName: string;
  anchorStart: number;
  anchorEnd: number;
}

interface QueryPromptAssistState {
  visible: boolean;
  loading: boolean;
  tabKey: string;
  mode: QueryPromptAssistMode;
  keyword: string;
  tableName: string;
  anchorStart: number;
  anchorEnd: number;
  activeIndex: number;
  emptyText: string;
  items: QueryPromptAssistItem[];
}

export interface QueryModule {
  queryPromptAssist: QueryPromptAssistState;
  handleChatComposerInput: (event: Event, tab: QueryTab) => void;
  handleChatComposerCursorChange: (event: Event, tab: QueryTab) => void;
  handleChatComposerKeydown: (event: KeyboardEvent, tab: QueryTab) => void;
  closeQueryPromptAssist: () => void;
  setQueryPromptAssistActive: (index: number) => void;
  applyPromptAssistOption: (tab: QueryTab, option: QueryPromptAssistItem) => Promise<void>;
  appendSqlToEditor: (tab: QueryTab, sqlText: string) => void;
  appendSelectedSqlToPrompt: (tab: QueryTab) => void;
  terminateAiExecutionForTab: (tab: QueryTab) => void;
  terminateSqlExecutionForTab: (tab: QueryTab) => void;
  retryUserMessage: (tab: QueryTab, userMessage: QueryMessage) => Promise<void>;
}

const TABLE_REFERENCE_PATTERN = /(^|[\s(,，;；])@([^\s@.(),，;；]*)$/;
const COLUMN_REFERENCE_PATTERN = /(^|[\s(,，;；])@([^\s@.(),，;；]+)\.([^\s@.(),，;；]*)$/;

export function useQueryModule(runtime: StudioRuntime): QueryModule {
  const queryPromptAssist = reactive<QueryPromptAssistState>({
    visible: false,
    loading: false,
    tabKey: '',
    mode: 'table',
    keyword: '',
    tableName: '',
    anchorStart: 0,
    anchorEnd: 0,
    activeIndex: 0,
    emptyText: '',
    items: [],
  });

  let promptAssistRequestId = 0;

  function closeQueryPromptAssist() {
    queryPromptAssist.visible = false;
    queryPromptAssist.loading = false;
    queryPromptAssist.tabKey = '';
    queryPromptAssist.keyword = '';
    queryPromptAssist.tableName = '';
    queryPromptAssist.anchorStart = 0;
    queryPromptAssist.anchorEnd = 0;
    queryPromptAssist.activeIndex = 0;
    queryPromptAssist.emptyText = '';
    queryPromptAssist.items = [];
  }

  function setQueryPromptAssistActive(index: number) {
    if (!queryPromptAssist.items.length) {
      queryPromptAssist.activeIndex = 0;
      return;
    }
    queryPromptAssist.activeIndex = Math.min(
      queryPromptAssist.items.length - 1,
      Math.max(0, index),
    );
  }

  function moveQueryPromptAssist(step: number) {
    if (!queryPromptAssist.items.length) {
      return;
    }
    const nextIndex = (queryPromptAssist.activeIndex + step + queryPromptAssist.items.length)
      % queryPromptAssist.items.length;
    queryPromptAssist.activeIndex = nextIndex;
  }

  function resolvePromptAssistContext(tab: QueryTab, event: Event | null): QueryPromptAssistContext | null {
    const target = event?.target;
    if (!(target instanceof HTMLTextAreaElement)) {
      return null;
    }
    const promptText = tab.prompt || '';
    const caret = target.selectionStart ?? promptText.length;
    const prefix = promptText.slice(0, caret);
    const columnMatch = prefix.match(COLUMN_REFERENCE_PATTERN);
    if (columnMatch) {
      const leading = columnMatch[1] || '';
      return {
        mode: 'column',
        keyword: (columnMatch[3] || '').trim(),
        tableName: (columnMatch[2] || '').trim(),
        anchorStart: prefix.length - columnMatch[0].length + leading.length,
        anchorEnd: caret,
      };
    }
    const tableMatch = prefix.match(TABLE_REFERENCE_PATTERN);
    if (tableMatch) {
      const leading = tableMatch[1] || '';
      return {
        mode: 'table',
        keyword: (tableMatch[2] || '').trim(),
        tableName: '',
        anchorStart: prefix.length - tableMatch[0].length + leading.length,
        anchorEnd: caret,
      };
    }
    return null;
  }

  function sortPromptAssistItems(items: QueryPromptAssistItem[], keyword: string) {
    const loweredKeyword = keyword.trim().toLowerCase();
    return [...items].sort((left, right) => {
      const leftLabel = left.label.toLowerCase();
      const rightLabel = right.label.toLowerCase();
      const leftStartsWith = loweredKeyword ? leftLabel.startsWith(loweredKeyword) : true;
      const rightStartsWith = loweredKeyword ? rightLabel.startsWith(loweredKeyword) : true;
      if (leftStartsWith !== rightStartsWith) {
        return leftStartsWith ? -1 : 1;
      }
      return leftLabel.localeCompare(rightLabel);
    });
  }

  function buildTablePromptAssistItems(tableNames: string[], keyword: string) {
    const loweredKeyword = keyword.trim().toLowerCase();
    const uniqueNames = Array.from(new Set(tableNames.map((item) => item.trim()).filter((item) => !!item)));
    const matched = loweredKeyword
      ? uniqueNames.filter((item) => item.toLowerCase().includes(loweredKeyword))
      : uniqueNames;
    return sortPromptAssistItems(
      matched.map((tableName) => ({
        key: `table:${tableName}`,
        kind: 'table' as const,
        label: tableName,
        tableName,
        meta: '表',
      })),
      loweredKeyword,
    ).slice(0, 120);
  }

  function buildColumnPromptAssistItems(
    tableName: string,
    columns: Array<{ columnName: string; dataType?: string; columnComment?: string }>,
    keyword: string,
  ) {
    const loweredKeyword = keyword.trim().toLowerCase();
    const matched = loweredKeyword
      ? columns.filter((item) => (item.columnName || '').toLowerCase().includes(loweredKeyword))
      : columns;
    const items: QueryPromptAssistItem[] = [];
    matched.forEach((item) => {
      const columnName = (item.columnName || '').trim();
      if (!columnName) {
        return;
      }
      items.push({
        key: `column:${tableName}.${columnName}`,
        kind: 'column',
        label: columnName,
        tableName,
        columnName,
        meta: item.dataType?.trim() || '字段',
        description: item.columnComment?.trim() || '',
      });
    });
    return sortPromptAssistItems(items, loweredKeyword).slice(0, 120);
  }

  async function refreshQueryPromptAssist(tab: QueryTab, event: Event | null) {
    const context = resolvePromptAssistContext(tab, event);
    if (!context) {
      closeQueryPromptAssist();
      return;
    }
    const databaseName = runtime.resolveQueryDatabaseName(tab);
    if (!tab.connectionId || !databaseName) {
      closeQueryPromptAssist();
      return;
    }
    const requestId = ++promptAssistRequestId;
    queryPromptAssist.visible = true;
    queryPromptAssist.loading = true;
    queryPromptAssist.tabKey = tab.key;
    queryPromptAssist.mode = context.mode;
    queryPromptAssist.keyword = context.keyword;
    queryPromptAssist.tableName = context.tableName;
    queryPromptAssist.anchorStart = context.anchorStart;
    queryPromptAssist.anchorEnd = context.anchorEnd;
    queryPromptAssist.emptyText = context.mode === 'table' ? '当前库没有可用表' : `表 ${context.tableName} 没有可用字段`;
    try {
      const items = context.mode === 'table'
        ? buildTablePromptAssistItems(
          await runtime.ensureTableNamesLoaded(tab.connectionId, databaseName),
          context.keyword,
        )
        : buildColumnPromptAssistItems(
          context.tableName,
          (await runtime.ensureQueryTableDetailLoaded(tab.connectionId, databaseName, context.tableName))?.columns ?? [],
          context.keyword,
        );
      if (requestId !== promptAssistRequestId) {
        return;
      }
      queryPromptAssist.items = items;
      queryPromptAssist.activeIndex = items.length ? Math.min(queryPromptAssist.activeIndex, items.length - 1) : 0;
      queryPromptAssist.emptyText = items.length
        ? ''
        : (context.mode === 'table'
          ? `未匹配到表 ${context.keyword ? `"${context.keyword}"` : ''}`.trim()
          : `表 ${context.tableName} 未匹配到字段 ${context.keyword ? `"${context.keyword}"` : ''}`.trim());
    } finally {
      if (requestId === promptAssistRequestId) {
        queryPromptAssist.loading = false;
      }
    }
  }

  async function applyPromptAssistOption(tab: QueryTab, option: QueryPromptAssistItem) {
    if (!queryPromptAssist.visible || queryPromptAssist.tabKey !== tab.key) {
      return;
    }
    const replacement = option.kind === 'column'
      ? `@${option.tableName}.${option.columnName || ''}`
      : `@${option.tableName}`;
    const before = tab.prompt.slice(0, queryPromptAssist.anchorStart);
    const after = tab.prompt.slice(queryPromptAssist.anchorEnd);
    tab.prompt = `${before}${replacement}${after}`;
    runtime.touchQueryTab(tab);
    closeQueryPromptAssist();
    await nextTick();
  }

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

  function handleChatComposerInput(event: Event, tab: QueryTab) {
    void refreshQueryPromptAssist(tab, event);
  }

  function handleChatComposerCursorChange(event: Event, tab: QueryTab) {
    void refreshQueryPromptAssist(tab, event);
  }

  function handleChatComposerKeydown(event: KeyboardEvent, tab: QueryTab) {
    if (event.isComposing) {
      return;
    }
    const assistVisible = queryPromptAssist.visible && queryPromptAssist.tabKey === tab.key;
    if (assistVisible && event.key === 'Escape') {
      event.preventDefault();
      closeQueryPromptAssist();
      return;
    }
    if (assistVisible && queryPromptAssist.items.length) {
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        moveQueryPromptAssist(1);
        return;
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault();
        moveQueryPromptAssist(-1);
        return;
      }
      if ((event.key === 'Enter' && !event.shiftKey) || event.key === 'Tab') {
        event.preventDefault();
        const target = queryPromptAssist.items[queryPromptAssist.activeIndex];
        if (target) {
          void applyPromptAssistOption(tab, target);
        }
        return;
      }
    }
    if (!tab.autoMode) {
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
    queryPromptAssist,
    handleChatComposerInput,
    handleChatComposerCursorChange,
    handleChatComposerKeydown,
    closeQueryPromptAssist,
    setQueryPromptAssistActive,
    applyPromptAssistOption,
    appendSqlToEditor,
    appendSelectedSqlToPrompt,
    terminateAiExecutionForTab,
    terminateSqlExecutionForTab,
    retryUserMessage,
  };
}
