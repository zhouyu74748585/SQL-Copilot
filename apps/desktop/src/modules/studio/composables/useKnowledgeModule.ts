import {computed, reactive, ref, watch, type ComputedRef, type Ref} from 'vue';
import {message} from 'ant-design-vue';
import {getApi, postApi} from '../../../api/client';
import type {
  KnowledgeExampleSqlSaveReq,
  KnowledgeExampleSqlVO,
  KnowledgeScope,
  KnowledgeTermSaveReq,
  KnowledgeTermVO,
  KnowledgeVectorRebuildVO,
} from '../../../types';
import type {StudioRuntime} from './useStudioRuntime';

type KnowledgeNode = 'example-sql' | 'terms';
type QueryTab = StudioRuntime['queryTabs']['value'][number];
type SelectOption<T extends string | number> = { label: string; value: T };

interface SaveQueryAsExampleDraft {
  connectionId: number;
  databaseName: string;
  sqlText: string;
}

export interface KnowledgeModule {
  knowledgeActiveNode: ComputedRef<KnowledgeNode>;
  knowledgeLoading: Ref<boolean>;
  knowledgeSaving: Ref<boolean>;
  knowledgeRebuildLoading: Ref<boolean>;
  knowledgeKeyword: Ref<string>;
  knowledgeConnectionId: Ref<number>;
  knowledgeDatabaseName: Ref<string>;
  knowledgeConnectionOptions: ComputedRef<SelectOption<number>[]>;
  knowledgeDatabaseOptions: ComputedRef<SelectOption<string>[]>;
  knowledgeTermItems: Ref<KnowledgeTermVO[]>;
  knowledgeExampleItems: Ref<KnowledgeExampleSqlVO[]>;
  filteredKnowledgeTermItems: ComputedRef<KnowledgeTermVO[]>;
  filteredKnowledgeExampleItems: ComputedRef<KnowledgeExampleSqlVO[]>;
  knowledgeTermForm: KnowledgeTermSaveReq;
  knowledgeExampleForm: KnowledgeExampleSqlSaveReq;
  knowledgeScopeOptions: Array<{ label: string; value: KnowledgeScope }>;
  knowledgeContextText: ComputedRef<string>;
  saveQueryAsExampleModalOpen: Ref<boolean>;
  saveQueryAsExampleSubmitting: Ref<boolean>;
  saveQueryAsExampleDescription: Ref<string>;
  saveQueryAsExampleContextText: ComputedRef<string>;
  openKnowledgeNode: (node: KnowledgeNode) => Promise<void>;
  closeKnowledgeTab: (tabKey: string) => void;
  handleKnowledgeConnectionChange: () => Promise<void>;
  handleKnowledgeDatabaseChange: () => Promise<void>;
  resetKnowledgeTermForm: () => void;
  resetKnowledgeExampleForm: () => void;
  selectKnowledgeTerm: (item: KnowledgeTermVO) => void;
  selectKnowledgeExample: (item: KnowledgeExampleSqlVO) => void;
  saveKnowledgeTerm: () => Promise<void>;
  removeKnowledgeTerm: () => Promise<void>;
  saveKnowledgeExample: () => Promise<void>;
  removeKnowledgeExample: () => Promise<void>;
  openSaveQueryAsExampleModal: (tab: QueryTab) => void;
  confirmSaveQueryAsExample: () => Promise<void>;
  rebuildKnowledgeVectors: () => Promise<void>;
  loadKnowledgeData: () => Promise<void>;
  knowledgeScopeLabel: (scope?: KnowledgeScope) => string;
  knowledgeScopeColor: (scope?: KnowledgeScope) => string;
}

export function useKnowledgeModule(runtime: StudioRuntime): KnowledgeModule {
  const knowledgeLoading = ref(false);
  const knowledgeSaving = ref(false);
  const knowledgeRebuildLoading = ref(false);
  const knowledgeKeyword = ref('');
  const knowledgeConnectionId = ref(0);
  const knowledgeDatabaseName = ref('');
  const knowledgeTermItems = ref<KnowledgeTermVO[]>([]);
  const knowledgeExampleItems = ref<KnowledgeExampleSqlVO[]>([]);
  const saveQueryAsExampleModalOpen = ref(false);
  const saveQueryAsExampleSubmitting = ref(false);
  const saveQueryAsExampleDescription = ref('');
  const saveQueryAsExampleDraft = reactive<SaveQueryAsExampleDraft>({
    connectionId: 0,
    databaseName: '',
    sqlText: '',
  });

  const knowledgeScopeOptions = [
    {label: '数据库级', value: 'DATABASE' as const},
    {label: '连接级', value: 'CONNECTION' as const},
    {label: '全局', value: 'GLOBAL' as const},
  ];

  const knowledgeTermForm = reactive<KnowledgeTermSaveReq>({
    scope: 'DATABASE',
    term: '',
    description: '',
  });

  const knowledgeExampleForm = reactive<KnowledgeExampleSqlSaveReq>({
    scope: 'DATABASE',
    sqlText: '',
    description: '',
    termIds: [],
  });

  const knowledgeActiveNode = computed<KnowledgeNode>(() => runtime.activeKnowledgeTab.value?.node ?? 'example-sql');

  const knowledgeConnectionOptions = computed(() => runtime.connectionSelectOptions.value);

  const knowledgeDatabaseOptions = computed<SelectOption<string>[]>(() => {
    const connection = runtime.connections.value.find((item) => item.id === knowledgeConnectionId.value);
    const cached = connection ? runtime.visibleDatabasesForConnection(connection) : [];
    const fallback = knowledgeDatabaseName.value || (
      knowledgeConnectionId.value ? runtime.getActiveDatabaseName(knowledgeConnectionId.value) : ''
    );
    const merged = Array.from(new Set([
      ...cached,
      ...((fallback && !cached.includes(fallback)) ? [fallback] : []),
    ].filter((item) => !!item)));
    return merged.map((item) => ({label: item, value: item}));
  });

  const currentContext = computed(() => ({
    connectionId: knowledgeConnectionId.value,
    databaseName: knowledgeDatabaseName.value.trim(),
  }));

  const knowledgeContextText = computed(() => {
    const connectionName = runtime.connections.value.find((item) => item.id === currentContext.value.connectionId)?.name || '未选择连接';
    const databaseName = currentContext.value.databaseName || '未选择数据库';
    return `${connectionName} / ${databaseName}`;
  });

  const saveQueryAsExampleContextText = computed(() => {
    const connectionName = runtime.connections.value.find((item) => item.id === saveQueryAsExampleDraft.connectionId)?.name || '未选择连接';
    const databaseName = saveQueryAsExampleDraft.databaseName || '未选择数据库';
    return `${connectionName} / ${databaseName}`;
  });

  const filteredKnowledgeTermItems = computed(() => {
    const keyword = knowledgeKeyword.value.trim().toLowerCase();
    if (!keyword) {
      return knowledgeTermItems.value;
    }
    return knowledgeTermItems.value.filter((item) =>
      `${item.term} ${item.description || ''}`.toLowerCase().includes(keyword),
    );
  });

  const filteredKnowledgeExampleItems = computed(() => {
    const keyword = knowledgeKeyword.value.trim().toLowerCase();
    if (!keyword) {
      return knowledgeExampleItems.value;
    }
    return knowledgeExampleItems.value.filter((item) =>
      `${item.sqlText} ${item.description || ''}`.toLowerCase().includes(keyword),
    );
  });

  function scopeForContext(connectionId: number, databaseName: string): KnowledgeScope {
    if (connectionId && databaseName) {
      return 'DATABASE';
    }
    if (connectionId) {
      return 'CONNECTION';
    }
    return 'GLOBAL';
  }

  function preferredScope(): KnowledgeScope {
    return scopeForContext(currentContext.value.connectionId, currentContext.value.databaseName);
  }

  function touchKnowledgeTab(node: KnowledgeNode) {
    const currentTab = runtime.knowledgeTabs.value.find((item) => item.node === node);
    if (!currentTab) {
      return;
    }
    currentTab.updatedAt = Date.now();
  }

  function buildScopePayload<T extends { scope: KnowledgeScope; connectionId?: number; databaseName?: string }>(payload: T): T {
    const next = {...payload};
    if (next.scope === 'GLOBAL') {
      next.connectionId = undefined;
      next.databaseName = '';
      return next;
    }
    next.connectionId = currentContext.value.connectionId || undefined;
    next.databaseName = next.scope === 'DATABASE' ? currentContext.value.databaseName : '';
    return next;
  }

  function resolvePreferredContext() {
    const connectionId = runtime.activeQueryTab.value?.connectionId
      || runtime.workflow.connectionId
      || runtime.connections.value[0]?.id
      || 0;
    return {
      connectionId,
      databaseName: connectionId ? runtime.getActiveDatabaseName(connectionId) || '' : '',
    };
  }

  function resolveKnowledgeDatabaseName(connectionId: number, preferredDatabaseName = '') {
    if (!connectionId) {
      return '';
    }
    const preferred = preferredDatabaseName.trim();
    const availableValues = knowledgeDatabaseOptions.value.map((item) => item.value);
    if (!availableValues.length) {
      return preferred || runtime.getActiveDatabaseName(connectionId) || '';
    }
    if (preferred && availableValues.includes(preferred)) {
      return preferred;
    }
    const activeDatabaseName = runtime.getActiveDatabaseName(connectionId);
    if (activeDatabaseName && availableValues.includes(activeDatabaseName)) {
      return activeDatabaseName;
    }
    return availableValues[0] || '';
  }

  async function syncKnowledgeContextFromRuntime(force = false) {
    const preferred = resolvePreferredContext();
    if (!preferred.connectionId) {
      knowledgeConnectionId.value = 0;
      knowledgeDatabaseName.value = '';
      return;
    }
    if (
      force
      || !knowledgeConnectionId.value
      || !runtime.connections.value.some((item) => item.id === knowledgeConnectionId.value)
    ) {
      knowledgeConnectionId.value = preferred.connectionId;
    }
    await runtime.prepareConnectionTreeData(knowledgeConnectionId.value);
    knowledgeDatabaseName.value = resolveKnowledgeDatabaseName(
      knowledgeConnectionId.value,
      force ? preferred.databaseName : knowledgeDatabaseName.value || preferred.databaseName,
    );
  }

  function ensureKnowledgeTab(node: KnowledgeNode) {
    const existing = runtime.knowledgeTabs.value.find((item) => item.node === node);
    if (existing) {
      existing.updatedAt = Date.now();
      return existing;
    }
    const tab = {
      key: `knowledge-${node}`,
      node,
      title: node === 'terms' ? '术语管理' : '样例 SQL',
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    runtime.knowledgeTabs.value = [...runtime.knowledgeTabs.value, tab];
    return tab;
  }

  function resetKnowledgeTermForm() {
    knowledgeTermForm.id = undefined;
    knowledgeTermForm.scope = preferredScope();
    knowledgeTermForm.term = '';
    knowledgeTermForm.description = '';
    knowledgeTermForm.connectionId = currentContext.value.connectionId || undefined;
    knowledgeTermForm.databaseName = currentContext.value.databaseName;
  }

  function resetKnowledgeExampleForm() {
    knowledgeExampleForm.id = undefined;
    knowledgeExampleForm.scope = preferredScope();
    knowledgeExampleForm.sqlText = '';
    knowledgeExampleForm.description = '';
    knowledgeExampleForm.termIds = [];
    knowledgeExampleForm.connectionId = currentContext.value.connectionId || undefined;
    knowledgeExampleForm.databaseName = currentContext.value.databaseName;
  }

  async function loadKnowledgeData() {
    knowledgeLoading.value = true;
    try {
      const params = new URLSearchParams();
      if (currentContext.value.connectionId) {
        params.set('connectionId', String(currentContext.value.connectionId));
      }
      if (currentContext.value.databaseName) {
        params.set('databaseName', currentContext.value.databaseName);
      }
      const query = params.toString() ? `?${params.toString()}` : '';
      const [terms, examples] = await Promise.all([
        getApi<KnowledgeTermVO[]>(`/api/knowledge/term/list${query}`),
        getApi<KnowledgeExampleSqlVO[]>(`/api/knowledge/example/list${query}`),
      ]);
      knowledgeTermItems.value = terms;
      knowledgeExampleItems.value = examples;
    } finally {
      knowledgeLoading.value = false;
    }
  }

  async function openKnowledgeNode(node: KnowledgeNode) {
    const tab = ensureKnowledgeTab(node);
    runtime.activeWorkbenchTab.value = tab.key;
    await syncKnowledgeContextFromRuntime(!knowledgeConnectionId.value);
    await loadKnowledgeData();
    if (node === 'terms' && !knowledgeTermForm.term) {
      resetKnowledgeTermForm();
    }
    if (node === 'example-sql' && !knowledgeExampleForm.sqlText) {
      resetKnowledgeExampleForm();
    }
  }

  function closeKnowledgeTab(tabKey: string) {
    const index = runtime.knowledgeTabs.value.findIndex((item) => item.key === tabKey);
    if (index < 0) {
      return;
    }
    const tabs = [...runtime.knowledgeTabs.value];
    tabs.splice(index, 1);
    runtime.knowledgeTabs.value = tabs;
    if (runtime.activeWorkbenchTab.value === tabKey) {
      runtime.activeWorkbenchTab.value = tabs[index]?.key || tabs[index - 1]?.key || runtime.browserTabKey;
    }
  }

  async function handleKnowledgeConnectionChange() {
    if (!knowledgeConnectionId.value) {
      knowledgeDatabaseName.value = '';
      resetKnowledgeTermForm();
      resetKnowledgeExampleForm();
      await loadKnowledgeData();
      return;
    }
    await runtime.prepareConnectionTreeData(knowledgeConnectionId.value);
    knowledgeDatabaseName.value = resolveKnowledgeDatabaseName(knowledgeConnectionId.value);
    resetKnowledgeTermForm();
    resetKnowledgeExampleForm();
    await loadKnowledgeData();
  }

  async function handleKnowledgeDatabaseChange() {
    knowledgeDatabaseName.value = resolveKnowledgeDatabaseName(knowledgeConnectionId.value, knowledgeDatabaseName.value);
    resetKnowledgeTermForm();
    resetKnowledgeExampleForm();
    await loadKnowledgeData();
  }

  function selectKnowledgeTerm(item: KnowledgeTermVO) {
    knowledgeTermForm.id = item.id;
    knowledgeTermForm.scope = item.scope;
    knowledgeTermForm.term = item.term;
    knowledgeTermForm.description = item.description || '';
    knowledgeTermForm.connectionId = item.connectionId;
    knowledgeTermForm.databaseName = item.databaseName || '';
    touchKnowledgeTab('terms');
  }

  function selectKnowledgeExample(item: KnowledgeExampleSqlVO) {
    knowledgeExampleForm.id = item.id;
    knowledgeExampleForm.scope = item.scope;
    knowledgeExampleForm.sqlText = item.sqlText;
    knowledgeExampleForm.description = item.description || '';
    knowledgeExampleForm.termIds = [...(item.termIds || [])];
    knowledgeExampleForm.connectionId = item.connectionId;
    knowledgeExampleForm.databaseName = item.databaseName || '';
    touchKnowledgeTab('example-sql');
  }

  async function saveKnowledgeTerm() {
    if (!knowledgeTermForm.term.trim()) {
      message.warning('术语不能为空');
      return;
    }
    if (knowledgeTermForm.scope !== 'GLOBAL' && !currentContext.value.connectionId) {
      message.warning('当前作用域需要先选择连接');
      return;
    }
    if (knowledgeTermForm.scope === 'DATABASE' && !currentContext.value.databaseName) {
      message.warning('数据库级术语需要先选择数据库');
      return;
    }
    knowledgeSaving.value = true;
    try {
      const payload = buildScopePayload({...knowledgeTermForm});
      await postApi<KnowledgeTermVO>('/api/knowledge/term/save', payload);
      message.success(knowledgeTermForm.id ? '术语已更新' : '术语已保存');
      await loadKnowledgeData();
      resetKnowledgeTermForm();
      touchKnowledgeTab('terms');
    } finally {
      knowledgeSaving.value = false;
    }
  }

  async function removeKnowledgeTerm() {
    if (!knowledgeTermForm.id) {
      message.info('请先选择要删除的术语');
      return;
    }
    if (!window.confirm('确定删除该术语吗？关联样例中的术语引用也会同步移除。')) {
      return;
    }
    knowledgeSaving.value = true;
    try {
      await postApi<boolean>('/api/knowledge/term/remove', {id: knowledgeTermForm.id});
      message.success('术语已删除');
      await loadKnowledgeData();
      resetKnowledgeTermForm();
      touchKnowledgeTab('terms');
    } finally {
      knowledgeSaving.value = false;
    }
  }

  async function saveKnowledgeExample() {
    if (!knowledgeExampleForm.sqlText.trim()) {
      message.warning('样例 SQL 不能为空');
      return;
    }
    if (knowledgeExampleForm.scope !== 'GLOBAL' && !currentContext.value.connectionId) {
      message.warning('当前作用域需要先选择连接');
      return;
    }
    if (knowledgeExampleForm.scope === 'DATABASE' && !currentContext.value.databaseName) {
      message.warning('数据库级样例需要先选择数据库');
      return;
    }
    knowledgeSaving.value = true;
    try {
      const payload = buildScopePayload({...knowledgeExampleForm, termIds: [...knowledgeExampleForm.termIds]});
      await postApi<KnowledgeExampleSqlVO>('/api/knowledge/example/save', payload);
      message.success(knowledgeExampleForm.id ? '样例 SQL 已更新' : '样例 SQL 已保存');
      await loadKnowledgeData();
      resetKnowledgeExampleForm();
      touchKnowledgeTab('example-sql');
    } finally {
      knowledgeSaving.value = false;
    }
  }

  async function removeKnowledgeExample() {
    if (!knowledgeExampleForm.id) {
      message.info('请先选择要删除的样例 SQL');
      return;
    }
    if (!window.confirm('确定删除该样例 SQL 吗？')) {
      return;
    }
    knowledgeSaving.value = true;
    try {
      await postApi<boolean>('/api/knowledge/example/remove', {id: knowledgeExampleForm.id});
      message.success('样例 SQL 已删除');
      await loadKnowledgeData();
      resetKnowledgeExampleForm();
      touchKnowledgeTab('example-sql');
    } finally {
      knowledgeSaving.value = false;
    }
  }

  function openSaveQueryAsExampleModal(tab: QueryTab) {
    const sqlText = tab.sqlText.trim();
    if (!sqlText) {
      message.warning('请先输入要保存的 SQL');
      return;
    }
    saveQueryAsExampleDraft.connectionId = tab.connectionId;
    saveQueryAsExampleDraft.databaseName = tab.databaseName || '';
    saveQueryAsExampleDraft.sqlText = sqlText;
    saveQueryAsExampleDescription.value = '';
    saveQueryAsExampleModalOpen.value = true;
  }

  async function confirmSaveQueryAsExample() {
    const sqlText = saveQueryAsExampleDraft.sqlText.trim();
    if (!sqlText) {
      message.warning('没有可保存的 SQL');
      return;
    }
    saveQueryAsExampleSubmitting.value = true;
    try {
      const scope = scopeForContext(saveQueryAsExampleDraft.connectionId, saveQueryAsExampleDraft.databaseName);
      await postApi<KnowledgeExampleSqlVO>('/api/knowledge/example/save', {
        scope,
        connectionId: scope === 'GLOBAL' ? undefined : saveQueryAsExampleDraft.connectionId,
        databaseName: scope === 'DATABASE' ? saveQueryAsExampleDraft.databaseName : '',
        sqlText,
        description: saveQueryAsExampleDescription.value.trim(),
        termIds: [],
      });
      saveQueryAsExampleModalOpen.value = false;
      message.success('已保存为样例 SQL');
      if (runtime.activeKnowledgeTab.value) {
        await loadKnowledgeData();
      }
    } finally {
      saveQueryAsExampleSubmitting.value = false;
    }
  }

  async function rebuildKnowledgeVectors() {
    knowledgeRebuildLoading.value = true;
    try {
      const result = await postApi<KnowledgeVectorRebuildVO>('/api/knowledge/vectorize/rebuild', {
        connectionId: currentContext.value.connectionId || undefined,
        databaseName: currentContext.value.databaseName || '',
      });
      message.success(result.message || '知识向量已重建');
      await loadKnowledgeData();
    } finally {
      knowledgeRebuildLoading.value = false;
    }
  }

  function knowledgeScopeLabel(scope?: KnowledgeScope) {
    if (scope === 'DATABASE') {
      return '数据库级';
    }
    if (scope === 'CONNECTION') {
      return '连接级';
    }
    return '全局';
  }

  function knowledgeScopeColor(scope?: KnowledgeScope) {
    if (scope === 'DATABASE') {
      return 'blue';
    }
    if (scope === 'CONNECTION') {
      return 'purple';
    }
    return 'green';
  }

  watch(
    () => runtime.connections.value.map((item) => item.id).join(','),
    () => {
      if (!runtime.connections.value.length) {
        knowledgeConnectionId.value = 0;
        knowledgeDatabaseName.value = '';
        return;
      }
      if (!runtime.connections.value.some((item) => item.id === knowledgeConnectionId.value)) {
        void syncKnowledgeContextFromRuntime(true);
      }
    },
    {immediate: true},
  );

  watch(
    () => runtime.activeKnowledgeTab.value?.key || '',
    (tabKey) => {
      if (!tabKey) {
        return;
      }
      void loadKnowledgeData();
    },
  );

  resetKnowledgeTermForm();
  resetKnowledgeExampleForm();

  return {
    knowledgeActiveNode,
    knowledgeLoading,
    knowledgeSaving,
    knowledgeRebuildLoading,
    knowledgeKeyword,
    knowledgeConnectionId,
    knowledgeDatabaseName,
    knowledgeConnectionOptions,
    knowledgeDatabaseOptions,
    knowledgeTermItems,
    knowledgeExampleItems,
    filteredKnowledgeTermItems,
    filteredKnowledgeExampleItems,
    knowledgeTermForm,
    knowledgeExampleForm,
    knowledgeScopeOptions,
    knowledgeContextText,
    saveQueryAsExampleModalOpen,
    saveQueryAsExampleSubmitting,
    saveQueryAsExampleDescription,
    saveQueryAsExampleContextText,
    openKnowledgeNode,
    closeKnowledgeTab,
    handleKnowledgeConnectionChange,
    handleKnowledgeDatabaseChange,
    resetKnowledgeTermForm,
    resetKnowledgeExampleForm,
    selectKnowledgeTerm,
    selectKnowledgeExample,
    saveKnowledgeTerm,
    removeKnowledgeTerm,
    saveKnowledgeExample,
    removeKnowledgeExample,
    openSaveQueryAsExampleModal,
    confirmSaveQueryAsExample,
    rebuildKnowledgeVectors,
    loadKnowledgeData,
    knowledgeScopeLabel,
    knowledgeScopeColor,
  };
}
