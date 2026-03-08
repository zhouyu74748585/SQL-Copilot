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

export interface KnowledgeModule {
  knowledgeActiveNode: Ref<KnowledgeNode>;
  knowledgeLoading: Ref<boolean>;
  knowledgeSaving: Ref<boolean>;
  knowledgeRebuildLoading: Ref<boolean>;
  knowledgeKeyword: Ref<string>;
  knowledgeTermItems: Ref<KnowledgeTermVO[]>;
  knowledgeExampleItems: Ref<KnowledgeExampleSqlVO[]>;
  filteredKnowledgeTermItems: ComputedRef<KnowledgeTermVO[]>;
  filteredKnowledgeExampleItems: ComputedRef<KnowledgeExampleSqlVO[]>;
  knowledgeTermForm: KnowledgeTermSaveReq;
  knowledgeExampleForm: KnowledgeExampleSqlSaveReq;
  knowledgeScopeOptions: Array<{ label: string; value: KnowledgeScope }>;
  knowledgeContextText: ComputedRef<string>;
  openKnowledgeNode: (node: KnowledgeNode) => Promise<void>;
  resetKnowledgeTermForm: () => void;
  resetKnowledgeExampleForm: () => void;
  selectKnowledgeTerm: (item: KnowledgeTermVO) => void;
  selectKnowledgeExample: (item: KnowledgeExampleSqlVO) => void;
  saveKnowledgeTerm: () => Promise<void>;
  removeKnowledgeTerm: () => Promise<void>;
  saveKnowledgeExample: () => Promise<void>;
  removeKnowledgeExample: () => Promise<void>;
  rebuildKnowledgeVectors: () => Promise<void>;
  loadKnowledgeData: () => Promise<void>;
  knowledgeScopeLabel: (scope?: KnowledgeScope) => string;
  knowledgeScopeColor: (scope?: KnowledgeScope) => string;
}

export function useKnowledgeModule(runtime: StudioRuntime): KnowledgeModule {
  const knowledgeActiveNode = ref<KnowledgeNode>('example-sql');
  const knowledgeLoading = ref(false);
  const knowledgeSaving = ref(false);
  const knowledgeRebuildLoading = ref(false);
  const knowledgeKeyword = ref('');
  const knowledgeTermItems = ref<KnowledgeTermVO[]>([]);
  const knowledgeExampleItems = ref<KnowledgeExampleSqlVO[]>([]);

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

  const currentContext = computed(() => {
    const connectionId = runtime.workflow.connectionId || runtime.activeQueryTab.value?.connectionId || 0;
    return {
      connectionId,
      databaseName: connectionId ? runtime.getActiveDatabaseName(connectionId) || '' : '',
    };
  });

  const knowledgeContextText = computed(() => {
    const connectionName = runtime.connections.value.find((item) => item.id === currentContext.value.connectionId)?.name || '未选择连接';
    const databaseName = currentContext.value.databaseName || '未选择数据库';
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

  function preferredScope(): KnowledgeScope {
    if (currentContext.value.connectionId && currentContext.value.databaseName) {
      return 'DATABASE';
    }
    if (currentContext.value.connectionId) {
      return 'CONNECTION';
    }
    return 'GLOBAL';
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

  function resetKnowledgeTermForm() {
    knowledgeTermForm.id = undefined;
    knowledgeTermForm.scope = preferredScope();
    knowledgeTermForm.term = '';
    knowledgeTermForm.description = '';
    knowledgeTermForm.connectionId = undefined;
    knowledgeTermForm.databaseName = '';
  }

  function resetKnowledgeExampleForm() {
    knowledgeExampleForm.id = undefined;
    knowledgeExampleForm.scope = preferredScope();
    knowledgeExampleForm.sqlText = '';
    knowledgeExampleForm.description = '';
    knowledgeExampleForm.termIds = [];
    knowledgeExampleForm.connectionId = undefined;
    knowledgeExampleForm.databaseName = '';
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
    runtime.browserNavMode.value = 'knowledge';
    runtime.activeWorkbenchTab.value = runtime.browserTabKey;
    knowledgeActiveNode.value = node;
    await loadKnowledgeData();
    if (node === 'terms' && !knowledgeTermForm.term) {
      resetKnowledgeTermForm();
    }
    if (node === 'example-sql' && !knowledgeExampleForm.sqlText) {
      resetKnowledgeExampleForm();
    }
  }

  function selectKnowledgeTerm(item: KnowledgeTermVO) {
    knowledgeTermForm.id = item.id;
    knowledgeTermForm.scope = item.scope;
    knowledgeTermForm.term = item.term;
    knowledgeTermForm.description = item.description || '';
    knowledgeTermForm.connectionId = item.connectionId;
    knowledgeTermForm.databaseName = item.databaseName || '';
  }

  function selectKnowledgeExample(item: KnowledgeExampleSqlVO) {
    knowledgeExampleForm.id = item.id;
    knowledgeExampleForm.scope = item.scope;
    knowledgeExampleForm.sqlText = item.sqlText;
    knowledgeExampleForm.description = item.description || '';
    knowledgeExampleForm.termIds = [...(item.termIds || [])];
    knowledgeExampleForm.connectionId = item.connectionId;
    knowledgeExampleForm.databaseName = item.databaseName || '';
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
    } finally {
      knowledgeSaving.value = false;
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
    () => [runtime.browserNavMode.value, currentContext.value.connectionId, currentContext.value.databaseName],
    ([mode]) => {
      if (mode !== 'knowledge') {
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
    knowledgeTermItems,
    knowledgeExampleItems,
    filteredKnowledgeTermItems,
    filteredKnowledgeExampleItems,
    knowledgeTermForm,
    knowledgeExampleForm,
    knowledgeScopeOptions,
    knowledgeContextText,
    openKnowledgeNode,
    resetKnowledgeTermForm,
    resetKnowledgeExampleForm,
    selectKnowledgeTerm,
    selectKnowledgeExample,
    saveKnowledgeTerm,
    removeKnowledgeTerm,
    saveKnowledgeExample,
    removeKnowledgeExample,
    rebuildKnowledgeVectors,
    loadKnowledgeData,
    knowledgeScopeLabel,
    knowledgeScopeColor,
  };
}
