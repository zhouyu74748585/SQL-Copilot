import {message, Modal} from 'ant-design-vue';
import {getApi, postApi} from '../../../api/client';
import type {
  ErGraphSnapshotPageVO,
  ErGraphSnapshotRemoveReq,
  ErGraphSnapshotRenameReq,
  ErGraphSnapshotSaveReq,
  ErGraphSnapshotVO,
} from '../../../types';
import type {StudioRuntime} from './useStudioRuntime';

type ErTab = StudioRuntime['erTabs']['value'][number];
type ErSnapshotItem = StudioRuntime['erSnapshotItems']['value'][number];

export interface ErSnapshotModule {
  erSnapshotItemKey: (item: ErSnapshotItem) => string;
  isErSnapshotItemActive: (item: ErSnapshotItem) => boolean;
  findErSnapshotSummaryById: (snapshotId: number) => ErSnapshotItem | null;
  updateErSnapshotTabsTitle: (snapshotId: number) => void;
  startErSnapshotTitleEdit: (item: ErSnapshotItem) => void;
  cancelErSnapshotTitleEdit: () => void;
  commitErSnapshotTitleEdit: (item: ErSnapshotItem) => Promise<void>;
  removeErSnapshot: (item: ErSnapshotItem) => Promise<void>;
  buildErSnapshotTabTitle: (snapshot: ErGraphSnapshotVO) => string;
  findErTabBySnapshotId: (snapshotId: number) => ErTab | null;
  loadErSnapshotPage: (reset: boolean) => Promise<void>;
  applyErSnapshotKeywordSearch: () => void;
  handleErSnapshotMenuScroll: (event: Event) => void;
  handleErSnapshotMenuClick: () => void;
  openErSnapshot: (item: ErSnapshotItem) => Promise<void>;
  openErSnapshotSaveModal: (tab?: ErTab | null) => void;
  confirmSaveErSnapshot: () => Promise<void>;
}

export function useErSnapshotModule(runtime: StudioRuntime): ErSnapshotModule {
  function erSnapshotItemKey(item: ErSnapshotItem) {
    return `${item.connectionId}-${item.id}`;
  }

  function isErSnapshotItemActive(item: ErSnapshotItem) {
    const tab = runtime.activeErTab.value;
    if (!tab) {
      return false;
    }
    return tab.snapshotId === item.id;
  }

  function findErSnapshotSummaryById(snapshotId: number) {
    return runtime.erSnapshotItems.value.find((item) => item.id === snapshotId) ?? null;
  }

  function updateErSnapshotTabsTitle(snapshotId: number) {
    const summary = findErSnapshotSummaryById(snapshotId);
    runtime.erTabs.value.forEach((tab) => {
      if (tab.snapshotId !== snapshotId) {
        return;
      }
      const snapshotName = (summary?.snapshotName || '').trim();
      const databaseName = (summary?.databaseName || tab.databaseName || '').trim();
      tab.title = snapshotName ? `ER · ${snapshotName}` : (databaseName ? `ER · ${databaseName}` : 'ER · 快照');
      tab.updatedAt = Date.now();
    });
  }

  function startErSnapshotTitleEdit(item: ErSnapshotItem) {
    if (runtime.erSnapshotActionLoadingId.value === item.id || runtime.erSnapshotLoadingId.value === item.id) {
      return;
    }
    runtime.editingErSnapshotId.value = item.id;
    runtime.editingErSnapshotTitle.value = (item.snapshotName || '').trim();
  }

  function cancelErSnapshotTitleEdit() {
    runtime.editingErSnapshotId.value = null;
    runtime.editingErSnapshotTitle.value = '';
  }

  async function commitErSnapshotTitleEdit(item: ErSnapshotItem) {
    if (runtime.editingErSnapshotId.value !== item.id) {
      return;
    }
    const renamed = runtime.editingErSnapshotTitle.value.trim();
    if (!renamed) {
      message.error('快照名称不能为空');
      return;
    }
    if (renamed === (item.snapshotName || '').trim()) {
      cancelErSnapshotTitleEdit();
      return;
    }
    if (runtime.erSnapshotActionLoadingId.value === item.id) {
      return;
    }
    runtime.erSnapshotActionLoadingId.value = item.id;
    try {
      const payload: ErGraphSnapshotRenameReq = {
        connectionId: item.connectionId,
        snapshotId: item.id,
        snapshotName: renamed,
      };
      await postApi<boolean>('/api/editor/er/snapshot/rename', payload);
      const now = Date.now();
      runtime.erSnapshotItems.value = runtime.erSnapshotItems.value.map((entry) => (entry.id === item.id
        ? {...entry, snapshotName: renamed, updatedAt: now}
        : entry));
      updateErSnapshotTabsTitle(item.id);
      cancelErSnapshotTitleEdit();
      message.success('快照名称已更新');
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(msg || '快照改名失败');
    } finally {
      runtime.erSnapshotActionLoadingId.value = null;
    }
  }

  async function removeErSnapshot(item: ErSnapshotItem) {
    if (runtime.erSnapshotActionLoadingId.value === item.id || runtime.erSnapshotLoadingId.value === item.id) {
      return;
    }
    const snapshotName = (item.snapshotName || '').trim() || '未命名快照';
    const confirmed = await new Promise<boolean>((resolve) => {
      Modal.confirm({
        title: '删除 ER 图快照',
        content: `确定删除快照“${snapshotName}”吗？删除后不可恢复。`,
        okText: '删除',
        okButtonProps: {danger: true},
        cancelText: '取消',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      });
    });
    if (!confirmed) {
      return;
    }

    runtime.erSnapshotActionLoadingId.value = item.id;
    try {
      const payload: ErGraphSnapshotRemoveReq = {
        connectionId: item.connectionId,
        snapshotId: item.id,
      };
      await postApi<boolean>('/api/editor/er/snapshot/remove', payload);
      if (runtime.editingErSnapshotId.value === item.id) {
        cancelErSnapshotTitleEdit();
      }
      runtime.erSnapshotItems.value = runtime.erSnapshotItems.value.filter((entry) => entry.id !== item.id);
      runtime.erTabs.value = runtime.erTabs.value.filter((tab) => tab.snapshotId !== item.id);
      runtime.ensureActiveWorkbenchTab();
      message.success('快照已删除');
      if (!runtime.erSnapshotItems.value.length) {
        runtime.erSnapshotPageNo.value = 1;
        runtime.erSnapshotHasMore.value = true;
        await loadErSnapshotPage(true);
      }
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(msg || '删除快照失败');
    } finally {
      runtime.erSnapshotActionLoadingId.value = null;
    }
  }

  function buildErSnapshotTabTitle(snapshot: ErGraphSnapshotVO) {
    const snapshotName = (snapshot.snapshotName || '').trim();
    const databaseName = (snapshot.databaseName || '').trim();
    if (snapshotName) {
      return `ER · ${snapshotName}`;
    }
    if (databaseName) {
      return `ER · ${databaseName}`;
    }
    return 'ER · 快照';
  }

  function findErTabBySnapshotId(snapshotId: number) {
    return runtime.erTabs.value.find((item) => item.snapshotId === snapshotId) ?? null;
  }

  async function loadErSnapshotPage(reset: boolean) {
    if (!runtime.erSnapshotConnectionId.value) {
      return;
    }
    if (reset) {
      if (runtime.erSnapshotReloading.value) {
        return;
      }
      runtime.erSnapshotReloading.value = true;
    } else {
      if (runtime.erSnapshotLoadingMore.value || runtime.erSnapshotReloading.value || !runtime.erSnapshotHasMore.value) {
        return;
      }
      runtime.erSnapshotLoadingMore.value = true;
    }
    try {
      const requestPageNo = reset ? 1 : runtime.erSnapshotPageNo.value;
      const params = new URLSearchParams({
        connectionId: `${runtime.erSnapshotConnectionId.value}`,
        pageNo: `${requestPageNo}`,
        pageSize: `${runtime.erSnapshotPageSize}`,
      });
      if (runtime.erSnapshotKeyword.value) {
        params.set('keyword', runtime.erSnapshotKeyword.value);
      }
      const page = await getApi<ErGraphSnapshotPageVO>(`/api/editor/er/snapshot/page?${params.toString()}`);
      const pageItems = page.items ?? [];
      if (reset) {
        runtime.erSnapshotItems.value = pageItems;
      } else if (pageItems.length) {
        const merged = [...runtime.erSnapshotItems.value];
        const indexMap = new Map<number, number>();
        merged.forEach((entry, idx) => {
          indexMap.set(entry.id, idx);
        });
        pageItems.forEach((entry) => {
          const existed = indexMap.get(entry.id);
          if (existed === undefined) {
            indexMap.set(entry.id, merged.length);
            merged.push(entry);
          } else {
            merged[existed] = entry;
          }
        });
        runtime.erSnapshotItems.value = merged;
      }
      runtime.erSnapshotPageNo.value = (page.pageNo ?? requestPageNo) + 1;
      runtime.erSnapshotHasMore.value = !!page.hasMore;
    } finally {
      if (reset) {
        runtime.erSnapshotReloading.value = false;
      } else {
        runtime.erSnapshotLoadingMore.value = false;
      }
    }
  }

  function applyErSnapshotKeywordSearch() {
    runtime.erSnapshotKeyword.value = runtime.erSnapshotKeywordInput.value.trim();
    runtime.erSnapshotPageNo.value = 1;
    runtime.erSnapshotHasMore.value = true;
    runtime.erSnapshotItems.value = [];
    cancelErSnapshotTitleEdit();
    void runtime.runSafely(async () => {
      await loadErSnapshotPage(true);
    });
  }

  function handleErSnapshotMenuScroll(event: Event) {
    if (runtime.erSnapshotLoadingMore.value || runtime.erSnapshotReloading.value || !runtime.erSnapshotHasMore.value) {
      return;
    }
    const target = event.target as HTMLElement | null;
    if (!target) {
      return;
    }
    if (target.scrollTop + target.clientHeight < target.scrollHeight - 36) {
      return;
    }
    void runtime.runSafely(async () => {
      await loadErSnapshotPage(false);
    });
  }

  function handleErSnapshotMenuClick() {
    if (!runtime.canOpenErSnapshot.value) {
      return;
    }
    const connectionId = runtime.currentErSnapshotConnectionId.value;
    if (!connectionId) {
      return;
    }
    if (runtime.erSnapshotConnectionId.value !== connectionId) {
      runtime.erSnapshotKeywordInput.value = '';
      runtime.erSnapshotKeyword.value = '';
      runtime.erSnapshotPageNo.value = 1;
      runtime.erSnapshotHasMore.value = true;
      runtime.erSnapshotItems.value = [];
      cancelErSnapshotTitleEdit();
    }
    runtime.erSnapshotConnectionId.value = connectionId;
    void runtime.runSafely(async () => {
      await loadErSnapshotPage(true);
    });
  }

  async function openErSnapshot(item: ErSnapshotItem) {
    if (runtime.editingErSnapshotId.value === item.id || runtime.erSnapshotActionLoadingId.value === item.id) {
      return;
    }
    if (!item.id || runtime.erSnapshotLoadingId.value === item.id) {
      return;
    }
    runtime.erSnapshotLoadingId.value = item.id;
    try {
      const detail = await getApi<ErGraphSnapshotVO>(`/api/editor/er/snapshot/detail?id=${item.id}`);
      if (!detail.graph) {
        throw new Error('该快照缺少 ER 图数据，无法回显');
      }
      detail.graph.manualRelations = detail.graph.manualRelations || [];
      const models = runtime.aiModelOptions.value.map((entry) => String(entry.value)).filter((entry) => !!entry);
      const selectedModel = (detail.modelName || '').trim();
      const modelName = selectedModel && models.includes(selectedModel)
        ? selectedModel
        : (models[0] ?? '');
      const layoutMode: ErTab['layoutMode'] = detail.layoutMode === 'CIRCLE' || detail.layoutMode === 'HIERARCHICAL'
        ? detail.layoutMode
        : 'GRID';
      const aiConfidenceThreshold = Number(detail.aiConfidenceThreshold);
      const normalizedTables = Array.from(
        new Set((detail.selectedTableNames ?? []).map((entry) => (entry || '').trim()).filter((entry) => !!entry)),
      );
      const now = Date.now();
      const existingTab = findErTabBySnapshotId(detail.id);
      let tab: ErTab;
      if (!existingTab) {
        tab = {
          key: `er-snapshot-${detail.id}`,
          title: buildErSnapshotTabTitle(detail),
          snapshotId: detail.id,
          connectionId: detail.connectionId,
          databaseName: detail.databaseName || '',
          selectedTableNames: normalizedTables,
          selectedAiModel: modelName,
          layoutMode,
          lineType: 'POLYLINE',
          showCardComments: false,
          detailCollapsed: false,
          aiConfidenceThreshold: Number.isFinite(aiConfidenceThreshold) ? aiConfidenceThreshold : 0.6,
          includeAiInference: detail.includeAiInference !== false,
          loading: false,
          graph: detail.graph,
          selectedRelationKey: '',
          errorMessage: '',
          createdAt: detail.createdAt ?? now,
          updatedAt: detail.updatedAt ?? now,
        };
        runtime.erTabs.value = [...runtime.erTabs.value, tab];
      } else {
        tab = existingTab;
        tab.title = buildErSnapshotTabTitle(detail);
        tab.snapshotId = detail.id;
        tab.connectionId = detail.connectionId;
        tab.databaseName = detail.databaseName || '';
        tab.selectedTableNames = normalizedTables;
        tab.selectedAiModel = modelName;
        tab.layoutMode = layoutMode;
        if (tab.lineType !== 'STRAIGHT' && tab.lineType !== 'POLYLINE') {
          tab.lineType = 'POLYLINE';
        }
        tab.showCardComments = tab.showCardComments === true;
        tab.detailCollapsed = tab.detailCollapsed === true;
        tab.aiConfidenceThreshold = Number.isFinite(aiConfidenceThreshold) ? aiConfidenceThreshold : 0.6;
        tab.includeAiInference = detail.includeAiInference !== false;
        tab.graph = detail.graph;
        tab.selectedRelationKey = '';
        tab.loading = false;
        tab.errorMessage = '';
        tab.updatedAt = now;
      }
      runtime.activeWorkbenchTab.value = tab.key;
      await runtime.prepareConnectionTreeData(tab.connectionId);
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(msg || '恢复 ER 图快照失败');
    } finally {
      runtime.erSnapshotLoadingId.value = null;
    }
  }

  function openErSnapshotSaveModal(tab?: ErTab | null) {
    const targetTab = tab ?? runtime.activeErTab.value;
    if (!targetTab) {
      message.warning('请先打开 ER 图标签');
      return;
    }
    if (!targetTab.graph) {
      message.warning('当前 ER 图暂无可保存内容');
      return;
    }
    runtime.erSnapshotSaveTabKey.value = targetTab.key;
    const baseName = targetTab.title.replace(/^ER\s*·\s*/, '').trim();
    runtime.erSnapshotSaveName.value = baseName || `${targetTab.databaseName || 'ER图'}快照`;
    runtime.erSnapshotSaveModalOpen.value = true;
  }

  async function confirmSaveErSnapshot() {
    if (runtime.erSnapshotSaveSubmitting.value) {
      return;
    }
    const tab = runtime.erTabs.value.find((item) => item.key === runtime.erSnapshotSaveTabKey.value) ?? runtime.activeErTab.value;
    if (!tab) {
      message.error('未找到待保存的 ER 图标签');
      return;
    }
    const snapshotName = runtime.erSnapshotSaveName.value.trim();
    if (!snapshotName) {
      message.error('请输入快照名称');
      return;
    }
    if (!tab.graph) {
      message.error('当前 ER 图暂无可保存内容');
      return;
    }
    if (!tab.connectionId || !tab.databaseName.trim()) {
      message.error('当前 ER 图缺少连接或数据库信息，无法保存');
      return;
    }
    const payload: ErGraphSnapshotSaveReq = {
      snapshotId: tab.snapshotId,
      connectionId: tab.connectionId,
      databaseName: tab.databaseName,
      snapshotName,
      selectedTableNames: [...tab.selectedTableNames],
      modelName: tab.selectedAiModel || undefined,
      layoutMode: tab.layoutMode,
      aiConfidenceThreshold: tab.aiConfidenceThreshold,
      includeAiInference: tab.includeAiInference,
      graph: tab.graph,
    };
    runtime.erSnapshotSaveSubmitting.value = true;
    try {
      await postApi<boolean>('/api/editor/er/snapshot/save', payload);
      runtime.erSnapshotSaveModalOpen.value = false;
      message.success('ER 图快照已保存');
      if (runtime.erSnapshotConnectionId.value === tab.connectionId) {
        await loadErSnapshotPage(true);
      }
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(msg || '保存 ER 图快照失败');
    } finally {
      runtime.erSnapshotSaveSubmitting.value = false;
    }
  }

  return {
    erSnapshotItemKey,
    isErSnapshotItemActive,
    findErSnapshotSummaryById,
    updateErSnapshotTabsTitle,
    startErSnapshotTitleEdit,
    cancelErSnapshotTitleEdit,
    commitErSnapshotTitleEdit,
    removeErSnapshot,
    buildErSnapshotTabTitle,
    findErTabBySnapshotId,
    loadErSnapshotPage,
    applyErSnapshotKeywordSearch,
    handleErSnapshotMenuScroll,
    handleErSnapshotMenuClick,
    openErSnapshot,
    openErSnapshotSaveModal,
    confirmSaveErSnapshot,
  };
}
