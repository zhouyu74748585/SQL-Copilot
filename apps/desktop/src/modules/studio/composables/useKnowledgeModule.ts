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

interface KnowledgeScopedPayload {
  scope: KnowledgeScope;
  connectionId?: number;
  databaseName?: string;
}

export interface KnowledgeModule {
  knowledgeActiveNode: ComputedRef<KnowledgeNode>;
  knowledgeLoading: Ref<boolean>;
  knowledgeSaving: Ref<boolean>;
  knowledgeRebuildLoading: Ref<boolean>;
  knowledgeKeyword: Ref<string>;
  knowledgeFilterConnectionId: Ref<number>;
  knowledgeFilterDatabaseName: Ref<string>;
  knowledgeConnectionOptions: ComputedRef<SelectOption<number>[]>;
  knowledgeFilterDatabaseOptions: ComputedRef<SelectOption<string>[]>;
  knowledgeTermTargetDatabaseOptions: ComputedRef<SelectOption<string>[]>;
  knowledgeExampleTargetDatabaseOptions: ComputedRef<SelectOption<string>[]>;
  knowledgeGlobalTermCount: Ref<number>;
  knowledgeGlobalExampleCount: Ref<number>;
  knowledgeTermItems: Ref<KnowledgeTermVO[]>;
  knowledgeExampleItems: Ref<KnowledgeExampleSqlVO[]>;
  filteredKnowledgeTermItems: ComputedRef<KnowledgeTermVO[]>;
  filteredKnowledgeExampleItems: ComputedRef<KnowledgeExampleSqlVO[]>;
  knowledgeVisibleExampleTermOptions: ComputedRef<SelectOption<number>[]>;
  knowledgeTermForm: KnowledgeTermSaveReq;
  knowledgeExampleForm: KnowledgeExampleSqlSaveReq;
  knowledgeScopeOptions: Array<{ label: string; value: KnowledgeScope }>;
  saveQueryAsExampleModalOpen: Ref<boolean>;
  saveQueryAsExampleSubmitting: Ref<boolean>;
  saveQueryAsExampleDescription: Ref<string>;
  saveQueryAsExampleContextText: ComputedRef<string>;
  openKnowledgeNode: (node: KnowledgeNode) => Promise<void>;
  closeKnowledgeTab: (tabKey: string) => void;
  handleKnowledgeFilterConnectionChange: () => Promise<void>;
  handleKnowledgeFilterDatabaseChange: () => Promise<void>;
  handleKnowledgeTermScopeChange: () => Promise<void>;
  handleKnowledgeTermTargetConnectionChange: () => Promise<void>;
  handleKnowledgeTermTargetDatabaseChange: () => void;
  handleKnowledgeExampleScopeChange: () => Promise<void>;
  handleKnowledgeExampleTargetConnectionChange: () => Promise<void>;
  handleKnowledgeExampleTargetDatabaseChange: () => void;
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
  const knowledgeFilterConnectionId = ref(0);
  const knowledgeFilterDatabaseName = ref('');
  const knowledgeGlobalTermCount = ref(0);
  const knowledgeGlobalExampleCount = ref(0);
  const knowledgeAllTermItems = ref<KnowledgeTermVO[]>([]);
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
    scope: 'GLOBAL',
    connectionId: undefined,
    databaseName: '',
    term: '',
    description: '',
  });

  const knowledgeExampleForm = reactive<KnowledgeExampleSqlSaveReq>({
    scope: 'GLOBAL',
    connectionId: undefined,
    databaseName: '',
    sqlText: '',
    description: '',
    termIds: [],
  });

  const knowledgeActiveNode = computed<KnowledgeNode>(() => runtime.activeKnowledgeTab.value?.node ?? 'example-sql');
  const knowledgeConnectionOptions = computed(() => runtime.connectionSelectOptions.value);

  function normalizeDatabaseName(value?: string) {
    return (value || '').trim();
  }

  function normalizeConnectionId(value?: number | string | null) {
    const next = Number(value || 0);
    return Number.isFinite(next) && next > 0 ? next : 0;
  }

  function hasConnection(connectionId: number) {
    return runtime.connections.value.some((item) => item.id === connectionId);
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
    ].filter((item) => !!item)));
    return merged.map((item) => ({label: item, value: item}));
  }

  function normalizeSelectedDatabase(options: SelectOption<string>[], databaseName?: string) {
    const normalized = normalizeDatabaseName(databaseName);
    if (!normalized) {
      return '';
    }
    return options.some((item) => item.value === normalized) ? normalized : '';
  }

  async function ensureConnectionDatabasesLoaded(connectionId: number) {
    if (!connectionId) {
      return;
    }
    await runtime.prepareConnectionTreeData(connectionId);
  }

  const knowledgeFilterDatabaseOptions = computed<SelectOption<string>[]>(() => (
    buildDatabaseOptions(knowledgeFilterConnectionId.value, knowledgeFilterDatabaseName.value)
  ));

  const knowledgeTermTargetDatabaseOptions = computed<SelectOption<string>[]>(() => (
    buildDatabaseOptions(normalizeConnectionId(knowledgeTermForm.connectionId), knowledgeTermForm.databaseName)
  ));

  const knowledgeExampleTargetDatabaseOptions = computed<SelectOption<string>[]>(() => (
    buildDatabaseOptions(normalizeConnectionId(knowledgeExampleForm.connectionId), knowledgeExampleForm.databaseName)
  ));

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
    if (connectionId && normalizeDatabaseName(databaseName)) {
      return 'DATABASE';
    }
    if (connectionId) {
      return 'CONNECTION';
    }
    return 'GLOBAL';
  }

  function touchKnowledgeTab(node: KnowledgeNode) {
    const currentTab = runtime.knowledgeTabs.value.find((item) => item.node === node);
    if (!currentTab) {
      return;
    }
    currentTab.updatedAt = Date.now();
  }

  function normalizeScopePayload<T extends KnowledgeScopedPayload>(payload: T): T {
    const next = {...payload};
    if (next.scope === 'GLOBAL') {
      next.connectionId = undefined;
      next.databaseName = '';
      return next;
    }
    next.connectionId = normalizeConnectionId(next.connectionId) || undefined;
    next.databaseName = next.scope === 'DATABASE' ? normalizeDatabaseName(next.databaseName) : '';
    return next;
  }

  function buildKnowledgeQuery(connectionId: number, databaseName: string) {
    const params = new URLSearchParams();
    if (connectionId) {
      params.set('connectionId', String(connectionId));
    }
    const normalizedDatabaseName = normalizeDatabaseName(databaseName);
    if (normalizedDatabaseName) {
      params.set('databaseName', normalizedDatabaseName);
    }
    const query = params.toString();
    return query ? `?${query}` : '';
  }

  async function loadKnowledgeGlobalData() {
    const [allTerms, allExamples] = await Promise.all([
      getApi<KnowledgeTermVO[]>('/api/knowledge/term/list'),
      getApi<KnowledgeExampleSqlVO[]>('/api/knowledge/example/list'),
    ]);
    knowledgeAllTermItems.value = allTerms;
    knowledgeGlobalTermCount.value = allTerms.length;
    knowledgeGlobalExampleCount.value = allExamples.length;
    sanitizeKnowledgeExampleTermIds();
  }

  function matchesExampleVisibleTerm(term: KnowledgeTermVO) {
    if (knowledgeExampleForm.scope === 'GLOBAL') {
      return true;
    }
    const connectionId = normalizeConnectionId(knowledgeExampleForm.connectionId);
    if (!connectionId) {
      return term.scope === 'GLOBAL';
    }
    if (knowledgeExampleForm.scope === 'CONNECTION') {
      return term.scope === 'GLOBAL'
        || (term.scope === 'CONNECTION' && normalizeConnectionId(term.connectionId) === connectionId);
    }
    const databaseName = normalizeDatabaseName(knowledgeExampleForm.databaseName);
    return term.scope === 'GLOBAL'
      || (term.scope === 'CONNECTION' && normalizeConnectionId(term.connectionId) === connectionId)
      || (
        term.scope === 'DATABASE'
        && normalizeConnectionId(term.connectionId) === connectionId
        && normalizeDatabaseName(term.databaseName) === databaseName
      );
  }

  const knowledgeVisibleExampleTermOptions = computed<SelectOption<number>[]>(() => (
    knowledgeAllTermItems.value
      .filter((item) => matchesExampleVisibleTerm(item))
      .map((item) => ({label: item.term, value: item.id}))
  ));

  function sanitizeKnowledgeExampleTermIds() {
    const allowedIds = new Set(knowledgeVisibleExampleTermOptions.value.map((item) => item.value));
    knowledgeExampleForm.termIds = knowledgeExampleForm.termIds.filter((item) => allowedIds.has(item));
  }

  function resetKnowledgeTermForm() {
    knowledgeTermForm.id = undefined;
    knowledgeTermForm.scope = 'GLOBAL';
    knowledgeTermForm.connectionId = undefined;
    knowledgeTermForm.databaseName = '';
    knowledgeTermForm.term = '';
    knowledgeTermForm.description = '';
  }

  function resetKnowledgeExampleForm() {
    knowledgeExampleForm.id = undefined;
    knowledgeExampleForm.scope = 'GLOBAL';
    knowledgeExampleForm.connectionId = undefined;
    knowledgeExampleForm.databaseName = '';
    knowledgeExampleForm.sqlText = '';
    knowledgeExampleForm.description = '';
    knowledgeExampleForm.termIds = [];
    sanitizeKnowledgeExampleTermIds();
  }

  async function loadKnowledgeData() {
    knowledgeLoading.value = true;
    try {
      const query = buildKnowledgeQuery(knowledgeFilterConnectionId.value, knowledgeFilterDatabaseName.value);
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

  async function openKnowledgeNode(node: KnowledgeNode) {
    const tab = ensureKnowledgeTab(node);
    runtime.activeWorkbenchTab.value = tab.key;
    await Promise.all([
      loadKnowledgeData(),
      loadKnowledgeGlobalData(),
    ]);
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

  async function handleKnowledgeFilterConnectionChange() {
    const connectionId = normalizeConnectionId(knowledgeFilterConnectionId.value);
    knowledgeFilterConnectionId.value = connectionId;
    if (!connectionId) {
      knowledgeFilterDatabaseName.value = '';
      await loadKnowledgeData();
      return;
    }
    await ensureConnectionDatabasesLoaded(connectionId);
    knowledgeFilterDatabaseName.value = normalizeSelectedDatabase(
      buildDatabaseOptions(connectionId, knowledgeFilterDatabaseName.value),
      knowledgeFilterDatabaseName.value,
    );
    await loadKnowledgeData();
  }

  async function handleKnowledgeFilterDatabaseChange() {
    const connectionId = normalizeConnectionId(knowledgeFilterConnectionId.value);
    knowledgeFilterDatabaseName.value = normalizeSelectedDatabase(
      buildDatabaseOptions(connectionId, knowledgeFilterDatabaseName.value),
      knowledgeFilterDatabaseName.value,
    );
    await loadKnowledgeData();
  }

  async function handleKnowledgeTermScopeChange() {
    if (knowledgeTermForm.scope === 'GLOBAL') {
      knowledgeTermForm.connectionId = undefined;
      knowledgeTermForm.databaseName = '';
      return;
    }
    const connectionId = normalizeConnectionId(knowledgeTermForm.connectionId);
    knowledgeTermForm.connectionId = connectionId || undefined;
    if (!connectionId) {
      knowledgeTermForm.databaseName = '';
      return;
    }
    await ensureConnectionDatabasesLoaded(connectionId);
    if (knowledgeTermForm.scope === 'CONNECTION') {
      knowledgeTermForm.databaseName = '';
      return;
    }
    knowledgeTermForm.databaseName = normalizeSelectedDatabase(
      buildDatabaseOptions(connectionId, knowledgeTermForm.databaseName),
      knowledgeTermForm.databaseName,
    );
  }

  async function handleKnowledgeTermTargetConnectionChange() {
    const connectionId = normalizeConnectionId(knowledgeTermForm.connectionId);
    knowledgeTermForm.connectionId = connectionId || undefined;
    if (!connectionId) {
      knowledgeTermForm.databaseName = '';
      return;
    }
    await ensureConnectionDatabasesLoaded(connectionId);
    if (knowledgeTermForm.scope !== 'DATABASE') {
      knowledgeTermForm.databaseName = '';
      return;
    }
    knowledgeTermForm.databaseName = normalizeSelectedDatabase(
      buildDatabaseOptions(connectionId, knowledgeTermForm.databaseName),
      knowledgeTermForm.databaseName,
    );
  }

  function handleKnowledgeTermTargetDatabaseChange() {
    knowledgeTermForm.databaseName = normalizeSelectedDatabase(
      buildDatabaseOptions(normalizeConnectionId(knowledgeTermForm.connectionId), knowledgeTermForm.databaseName),
      knowledgeTermForm.databaseName,
    );
  }

  async function handleKnowledgeExampleScopeChange() {
    if (knowledgeExampleForm.scope === 'GLOBAL') {
      knowledgeExampleForm.connectionId = undefined;
      knowledgeExampleForm.databaseName = '';
      sanitizeKnowledgeExampleTermIds();
      return;
    }
    const connectionId = normalizeConnectionId(knowledgeExampleForm.connectionId);
    knowledgeExampleForm.connectionId = connectionId || undefined;
    if (!connectionId) {
      knowledgeExampleForm.databaseName = '';
      sanitizeKnowledgeExampleTermIds();
      return;
    }
    await ensureConnectionDatabasesLoaded(connectionId);
    if (knowledgeExampleForm.scope === 'CONNECTION') {
      knowledgeExampleForm.databaseName = '';
      sanitizeKnowledgeExampleTermIds();
      return;
    }
    knowledgeExampleForm.databaseName = normalizeSelectedDatabase(
      buildDatabaseOptions(connectionId, knowledgeExampleForm.databaseName),
      knowledgeExampleForm.databaseName,
    );
    sanitizeKnowledgeExampleTermIds();
  }

  async function handleKnowledgeExampleTargetConnectionChange() {
    const connectionId = normalizeConnectionId(knowledgeExampleForm.connectionId);
    knowledgeExampleForm.connectionId = connectionId || undefined;
    if (!connectionId) {
      knowledgeExampleForm.databaseName = '';
      sanitizeKnowledgeExampleTermIds();
      return;
    }
    await ensureConnectionDatabasesLoaded(connectionId);
    if (knowledgeExampleForm.scope !== 'DATABASE') {
      knowledgeExampleForm.databaseName = '';
      sanitizeKnowledgeExampleTermIds();
      return;
    }
    knowledgeExampleForm.databaseName = normalizeSelectedDatabase(
      buildDatabaseOptions(connectionId, knowledgeExampleForm.databaseName),
      knowledgeExampleForm.databaseName,
    );
    sanitizeKnowledgeExampleTermIds();
  }

  function handleKnowledgeExampleTargetDatabaseChange() {
    knowledgeExampleForm.databaseName = normalizeSelectedDatabase(
      buildDatabaseOptions(normalizeConnectionId(knowledgeExampleForm.connectionId), knowledgeExampleForm.databaseName),
      knowledgeExampleForm.databaseName,
    );
    sanitizeKnowledgeExampleTermIds();
  }

  function selectKnowledgeTerm(item: KnowledgeTermVO) {
    knowledgeTermForm.id = item.id;
    knowledgeTermForm.scope = item.scope;
    knowledgeTermForm.term = item.term;
    knowledgeTermForm.description = item.description || '';
    knowledgeTermForm.connectionId = normalizeConnectionId(item.connectionId) || undefined;
    knowledgeTermForm.databaseName = normalizeDatabaseName(item.databaseName);
    if (knowledgeTermForm.connectionId) {
      void ensureConnectionDatabasesLoaded(knowledgeTermForm.connectionId);
    }
    touchKnowledgeTab('terms');
  }

  function selectKnowledgeExample(item: KnowledgeExampleSqlVO) {
    knowledgeExampleForm.id = item.id;
    knowledgeExampleForm.scope = item.scope;
    knowledgeExampleForm.sqlText = item.sqlText;
    knowledgeExampleForm.description = item.description || '';
    knowledgeExampleForm.termIds = [...(item.termIds || [])];
    knowledgeExampleForm.connectionId = normalizeConnectionId(item.connectionId) || undefined;
    knowledgeExampleForm.databaseName = normalizeDatabaseName(item.databaseName);
    if (knowledgeExampleForm.connectionId) {
      void ensureConnectionDatabasesLoaded(knowledgeExampleForm.connectionId);
    }
    sanitizeKnowledgeExampleTermIds();
    touchKnowledgeTab('example-sql');
  }

  async function saveKnowledgeTerm() {
    if (!knowledgeTermForm.term.trim()) {
      message.warning('术语不能为空');
      return;
    }
    if (knowledgeTermForm.scope !== 'GLOBAL' && !normalizeConnectionId(knowledgeTermForm.connectionId)) {
      message.warning('当前作用域需要先选择目标连接');
      return;
    }
    if (knowledgeTermForm.scope === 'DATABASE' && !normalizeDatabaseName(knowledgeTermForm.databaseName)) {
      message.warning('数据库级术语需要先选择目标数据库');
      return;
    }
    knowledgeSaving.value = true;
    try {
      const payload = normalizeScopePayload({
        ...knowledgeTermForm,
        connectionId: normalizeConnectionId(knowledgeTermForm.connectionId) || undefined,
        databaseName: normalizeDatabaseName(knowledgeTermForm.databaseName),
      });
      await postApi<KnowledgeTermVO>('/api/knowledge/term/save', payload);
      message.success(knowledgeTermForm.id ? '术语已更新' : '术语已保存');
      await Promise.all([
        loadKnowledgeData(),
        loadKnowledgeGlobalData(),
      ]);
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
      await Promise.all([
        loadKnowledgeData(),
        loadKnowledgeGlobalData(),
      ]);
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
    if (knowledgeExampleForm.scope !== 'GLOBAL' && !normalizeConnectionId(knowledgeExampleForm.connectionId)) {
      message.warning('当前作用域需要先选择目标连接');
      return;
    }
    if (knowledgeExampleForm.scope === 'DATABASE' && !normalizeDatabaseName(knowledgeExampleForm.databaseName)) {
      message.warning('数据库级样例需要先选择目标数据库');
      return;
    }
    sanitizeKnowledgeExampleTermIds();
    knowledgeSaving.value = true;
    try {
      const payload = normalizeScopePayload({
        ...knowledgeExampleForm,
        connectionId: normalizeConnectionId(knowledgeExampleForm.connectionId) || undefined,
        databaseName: normalizeDatabaseName(knowledgeExampleForm.databaseName),
        termIds: [...knowledgeExampleForm.termIds],
      });
      await postApi<KnowledgeExampleSqlVO>('/api/knowledge/example/save', payload);
      message.success(knowledgeExampleForm.id ? '样例 SQL 已更新' : '样例 SQL 已保存');
      await Promise.all([
        loadKnowledgeData(),
        loadKnowledgeGlobalData(),
      ]);
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
      await Promise.all([
        loadKnowledgeData(),
        loadKnowledgeGlobalData(),
      ]);
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
        await Promise.all([
          loadKnowledgeData(),
          loadKnowledgeGlobalData(),
        ]);
      }
    } finally {
      saveQueryAsExampleSubmitting.value = false;
    }
  }

  async function rebuildKnowledgeVectors() {
    knowledgeRebuildLoading.value = true;
    try {
      const result = await postApi<KnowledgeVectorRebuildVO>('/api/knowledge/vectorize/rebuild', {
        connectionId: knowledgeFilterConnectionId.value || undefined,
        databaseName: normalizeDatabaseName(knowledgeFilterDatabaseName.value),
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
        knowledgeFilterConnectionId.value = 0;
        knowledgeFilterDatabaseName.value = '';
        knowledgeTermForm.connectionId = undefined;
        knowledgeTermForm.databaseName = '';
        knowledgeExampleForm.connectionId = undefined;
        knowledgeExampleForm.databaseName = '';
        sanitizeKnowledgeExampleTermIds();
        return;
      }
      if (knowledgeFilterConnectionId.value && !hasConnection(knowledgeFilterConnectionId.value)) {
        knowledgeFilterConnectionId.value = 0;
        knowledgeFilterDatabaseName.value = '';
      }
      if (normalizeConnectionId(knowledgeTermForm.connectionId) && !hasConnection(normalizeConnectionId(knowledgeTermForm.connectionId))) {
        knowledgeTermForm.connectionId = undefined;
        knowledgeTermForm.databaseName = '';
      }
      if (normalizeConnectionId(knowledgeExampleForm.connectionId) && !hasConnection(normalizeConnectionId(knowledgeExampleForm.connectionId))) {
        knowledgeExampleForm.connectionId = undefined;
        knowledgeExampleForm.databaseName = '';
        sanitizeKnowledgeExampleTermIds();
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
      void Promise.all([
        loadKnowledgeData(),
        loadKnowledgeGlobalData(),
      ]);
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
    knowledgeFilterConnectionId,
    knowledgeFilterDatabaseName,
    knowledgeConnectionOptions,
    knowledgeFilterDatabaseOptions,
    knowledgeTermTargetDatabaseOptions,
    knowledgeExampleTargetDatabaseOptions,
    knowledgeGlobalTermCount,
    knowledgeGlobalExampleCount,
    knowledgeTermItems,
    knowledgeExampleItems,
    filteredKnowledgeTermItems,
    filteredKnowledgeExampleItems,
    knowledgeVisibleExampleTermOptions,
    knowledgeTermForm,
    knowledgeExampleForm,
    knowledgeScopeOptions,
    saveQueryAsExampleModalOpen,
    saveQueryAsExampleSubmitting,
    saveQueryAsExampleDescription,
    saveQueryAsExampleContextText,
    openKnowledgeNode,
    closeKnowledgeTab,
    handleKnowledgeFilterConnectionChange,
    handleKnowledgeFilterDatabaseChange,
    handleKnowledgeTermScopeChange,
    handleKnowledgeTermTargetConnectionChange,
    handleKnowledgeTermTargetDatabaseChange,
    handleKnowledgeExampleScopeChange,
    handleKnowledgeExampleTargetConnectionChange,
    handleKnowledgeExampleTargetDatabaseChange,
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
