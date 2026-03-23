import {computed, reactive, ref, type ComputedRef, type Ref} from 'vue';
import {message} from 'ant-design-vue';
import {getApi, postApi} from '../../../api/client';
import type {
  MemoryEntryPageVO,
  MemoryEntrySaveReq,
  MemoryEntryVO,
  MemoryHistoryPageVO,
  MemoryHistoryVO,
  MemoryScope,
} from '../../../types';
import type {StudioRuntime} from './useStudioRuntime';

type MemoryNode = 'entries' | 'history-sql';
type SelectOption<T extends string | number> = { label: string; value: T };

export interface MemoryModule {
  memoryActiveNode: ComputedRef<MemoryNode>;
  memoryLoading: Ref<boolean>;
  memorySaving: Ref<boolean>;
  memoryActionLoading: Ref<boolean>;
  memoryKeyword: Ref<string>;
  memoryFilterConnectionId: Ref<number>;
  memoryFilterDatabaseName: Ref<string>;
  memoryConnectionOptions: ComputedRef<SelectOption<number>[]>;
  memoryDatabaseOptions: ComputedRef<SelectOption<string>[]>;
  memoryEntryTotal: Ref<number>;
  memoryHistoryTotal: Ref<number>;
  memoryEntryItems: Ref<MemoryEntryVO[]>;
  memoryHistoryItems: Ref<MemoryHistoryVO[]>;
  selectedMemoryHistory: ComputedRef<MemoryHistoryVO | null>;
  memoryEntryForm: MemoryEntrySaveReq;
  memoryScopeOptions: Array<{ label: string; value: MemoryScope }>;
  openMemoryNode: (node: MemoryNode) => Promise<void>;
  closeMemoryTab: (tabKey: string) => void;
  handleMemoryFilterConnectionChange: () => Promise<void>;
  handleMemoryFilterDatabaseChange: () => Promise<void>;
  loadMemoryData: () => Promise<void>;
  resetMemoryEntryForm: () => void;
  selectMemoryEntry: (item: MemoryEntryVO) => void;
  selectMemoryHistory: (item: MemoryHistoryVO) => void;
  saveMemoryEntry: () => Promise<void>;
  removeMemoryEntry: () => Promise<void>;
  removeMemoryHistory: () => Promise<void>;
  promoteMemoryHistory: () => Promise<void>;
  memoryScopeLabel: (scope?: MemoryScope) => string;
}

export function useMemoryModule(runtime: StudioRuntime): MemoryModule {
  const memoryLoading = ref(false);
  const memorySaving = ref(false);
  const memoryActionLoading = ref(false);
  const memoryKeyword = ref('');
  const memoryFilterConnectionId = ref(0);
  const memoryFilterDatabaseName = ref('');
  const memoryEntryTotal = ref(0);
  const memoryHistoryTotal = ref(0);
  const memoryEntryItems = ref<MemoryEntryVO[]>([]);
  const memoryHistoryItems = ref<MemoryHistoryVO[]>([]);
  const selectedMemoryHistoryId = ref(0);

  const memoryScopeOptions = [
    {label: '数据库级', value: 'DATABASE' as const},
    {label: '连接级', value: 'CONNECTION' as const},
  ];

  const memoryEntryForm = reactive<MemoryEntrySaveReq>({
    scope: 'CONNECTION',
    connectionId: undefined,
    databaseName: '',
    title: '',
    summary: '',
  });

  const memoryActiveNode = computed<MemoryNode>(() => runtime.activeMemoryTab.value?.node ?? 'entries');
  const memoryConnectionOptions = computed(() => runtime.connectionSelectOptions.value);

  function normalizeConnectionId(value?: number | string | null) {
    const next = Number(value || 0);
    return Number.isFinite(next) && next > 0 ? next : 0;
  }

  function normalizeDatabaseName(value?: string) {
    return (value || '').trim();
  }

  function buildDatabaseOptions(connectionId: number, fallbackDatabaseName = '') {
    if (!connectionId) {
      return [] as SelectOption<string>[];
    }
    const connection = runtime.connections.value.find((item) => item.id === connectionId);
    const cached = connection ? runtime.visibleDatabasesForConnection(connection) : [];
    const fallback = normalizeDatabaseName(fallbackDatabaseName);
    const merged = Array.from(new Set([
      ...cached,
      ...((fallback && !cached.includes(fallback)) ? [fallback] : []),
    ].filter(Boolean)));
    return merged.map((item) => ({label: item, value: item}));
  }

  const memoryDatabaseOptions = computed<SelectOption<string>[]>(() => (
    buildDatabaseOptions(memoryFilterConnectionId.value, memoryFilterDatabaseName.value)
  ));

  const selectedMemoryHistory = computed(() => (
    memoryHistoryItems.value.find((item) => item.historyId === selectedMemoryHistoryId.value) ?? null
  ));

  function resetMemoryEntryForm() {
    memoryEntryForm.id = undefined;
    memoryEntryForm.scope = 'CONNECTION';
    memoryEntryForm.connectionId = memoryFilterConnectionId.value || undefined;
    memoryEntryForm.databaseName = '';
    memoryEntryForm.title = '';
    memoryEntryForm.summary = '';
  }

  function memoryScopeLabel(scope?: MemoryScope) {
    if (scope === 'DATABASE') {
      return '数据库级';
    }
    return '连接级';
  }

  function ensureMemoryTab(node: MemoryNode) {
    const existing = runtime.memoryTabs.value.find((item) => item.node === node);
    if (existing) {
      existing.updatedAt = Date.now();
      return existing;
    }
    const tab = {
      key: `memory-${node}`,
      node,
      title: node === 'entries' ? '长期记忆' : '历史 SQL 记忆',
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    runtime.memoryTabs.value = [...runtime.memoryTabs.value, tab];
    return tab;
  }

  function closeMemoryTab(tabKey: string) {
    const index = runtime.memoryTabs.value.findIndex((item) => item.key === tabKey);
    if (index < 0) {
      return;
    }
    const tabs = [...runtime.memoryTabs.value];
    tabs.splice(index, 1);
    runtime.memoryTabs.value = tabs;
    if (runtime.activeWorkbenchTab.value === tabKey) {
      runtime.activeWorkbenchTab.value = tabs[index]?.key || tabs[index - 1]?.key || runtime.browserTabKey;
    }
  }

  function buildMemoryQuery() {
    const params = new URLSearchParams();
    if (memoryFilterConnectionId.value) {
      params.set('connectionId', String(memoryFilterConnectionId.value));
    }
    if (memoryFilterDatabaseName.value) {
      params.set('databaseName', memoryFilterDatabaseName.value);
    }
    if (memoryKeyword.value.trim()) {
      params.set('keyword', memoryKeyword.value.trim());
    }
    params.set('pageNo', '1');
    params.set('pageSize', '100');
    return `?${params.toString()}`;
  }

  async function loadMemoryData() {
    memoryLoading.value = true;
    try {
      const query = buildMemoryQuery();
      if (memoryActiveNode.value === 'entries') {
        const page = await getApi<MemoryEntryPageVO>(`/api/memory/entry/page${query}`);
        memoryEntryItems.value = page.items || [];
        memoryEntryTotal.value = page.total || 0;
        if (memoryEntryForm.id) {
          const current = memoryEntryItems.value.find((item) => item.id === memoryEntryForm.id);
          if (!current) {
            resetMemoryEntryForm();
          }
        }
      } else {
        const page = await getApi<MemoryHistoryPageVO>(`/api/memory/history/page${query}`);
        memoryHistoryItems.value = page.items || [];
        memoryHistoryTotal.value = page.total || 0;
        if (!memoryHistoryItems.value.some((item) => item.historyId === selectedMemoryHistoryId.value)) {
          selectedMemoryHistoryId.value = memoryHistoryItems.value[0]?.historyId || 0;
        }
      }
    } finally {
      memoryLoading.value = false;
    }
  }

  async function openMemoryNode(node: MemoryNode) {
    const tab = ensureMemoryTab(node);
    runtime.activeWorkbenchTab.value = tab.key;
    await loadMemoryData();
    if (node === 'entries' && !memoryEntryForm.title) {
      resetMemoryEntryForm();
    }
  }

  async function handleMemoryFilterConnectionChange() {
    const connectionId = normalizeConnectionId(memoryFilterConnectionId.value);
    memoryFilterConnectionId.value = connectionId;
    if (!connectionId) {
      memoryFilterDatabaseName.value = '';
      await loadMemoryData();
      return;
    }
    await runtime.prepareConnectionTreeData(connectionId);
    const options = buildDatabaseOptions(connectionId, memoryFilterDatabaseName.value);
    if (!options.some((item) => item.value === memoryFilterDatabaseName.value)) {
      memoryFilterDatabaseName.value = '';
    }
    await loadMemoryData();
  }

  async function handleMemoryFilterDatabaseChange() {
    const options = buildDatabaseOptions(memoryFilterConnectionId.value, memoryFilterDatabaseName.value);
    if (!options.some((item) => item.value === memoryFilterDatabaseName.value)) {
      memoryFilterDatabaseName.value = '';
    }
    await loadMemoryData();
  }

  function selectMemoryEntry(item: MemoryEntryVO) {
    memoryEntryForm.id = item.id;
    memoryEntryForm.scope = item.scope;
    memoryEntryForm.connectionId = item.connectionId;
    memoryEntryForm.databaseName = item.databaseName || '';
    memoryEntryForm.title = item.title;
    memoryEntryForm.summary = item.summary;
  }

  function selectMemoryHistory(item: MemoryHistoryVO) {
    selectedMemoryHistoryId.value = item.historyId;
  }

  async function saveMemoryEntry() {
    memorySaving.value = true;
    try {
      const payload: MemoryEntrySaveReq = {
        id: memoryEntryForm.id,
        scope: memoryEntryForm.scope,
        connectionId: normalizeConnectionId(memoryEntryForm.connectionId),
        databaseName: memoryEntryForm.scope === 'DATABASE' ? normalizeDatabaseName(memoryEntryForm.databaseName) : '',
        title: memoryEntryForm.title.trim(),
        summary: memoryEntryForm.summary.trim(),
      };
      const saved = await postApi<MemoryEntryVO>('/api/memory/entry/save', payload);
      message.success(memoryEntryForm.id ? '长期记忆已更新' : '长期记忆已创建');
      await openMemoryNode('entries');
      selectMemoryEntry(saved);
    } finally {
      memorySaving.value = false;
    }
  }

  async function removeMemoryEntry() {
    if (!memoryEntryForm.id) {
      return;
    }
    memoryActionLoading.value = true;
    try {
      await postApi<boolean>('/api/memory/entry/remove', {id: memoryEntryForm.id});
      message.success('长期记忆已删除');
      resetMemoryEntryForm();
      await loadMemoryData();
    } finally {
      memoryActionLoading.value = false;
    }
  }

  async function removeMemoryHistory() {
    if (!selectedMemoryHistory.value) {
      return;
    }
    memoryActionLoading.value = true;
    try {
      await postApi<boolean>('/api/memory/history/remove', {historyId: selectedMemoryHistory.value.historyId});
      message.success('历史 SQL 记忆已删除');
      await loadMemoryData();
    } finally {
      memoryActionLoading.value = false;
    }
  }

  async function promoteMemoryHistory() {
    if (!selectedMemoryHistory.value) {
      return;
    }
    memoryActionLoading.value = true;
    try {
      const saved = await postApi<MemoryEntryVO>('/api/memory/history/promote', {
        historyIds: [selectedMemoryHistory.value.historyId],
      });
      message.success('已提升为长期记忆');
      await openMemoryNode('entries');
      selectMemoryEntry(saved);
    } finally {
      memoryActionLoading.value = false;
    }
  }

  return {
    memoryActiveNode,
    memoryLoading,
    memorySaving,
    memoryActionLoading,
    memoryKeyword,
    memoryFilterConnectionId,
    memoryFilterDatabaseName,
    memoryConnectionOptions,
    memoryDatabaseOptions,
    memoryEntryTotal,
    memoryHistoryTotal,
    memoryEntryItems,
    memoryHistoryItems,
    selectedMemoryHistory,
    memoryEntryForm,
    memoryScopeOptions,
    openMemoryNode,
    closeMemoryTab,
    handleMemoryFilterConnectionChange,
    handleMemoryFilterDatabaseChange,
    loadMemoryData,
    resetMemoryEntryForm,
    selectMemoryEntry,
    selectMemoryHistory,
    saveMemoryEntry,
    removeMemoryEntry,
    removeMemoryHistory,
    promoteMemoryHistory,
    memoryScopeLabel,
  };
}
