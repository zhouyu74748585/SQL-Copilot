import {message} from 'ant-design-vue';
import {nextTick, type Ref} from 'vue';
import {postApi} from '../../../../api/client';
import type {ErGraphReq, ErGraphVO, ErRelationVO} from '../../../../types';
import {erDiagramExportPixelRatioCandidates} from './constants';
import {normalizeDownloadFileNamePart} from './charts';
import type {ErWorkspaceTab} from './types';

export function touchErTab(tab: ErWorkspaceTab) {
  tab.updatedAt = Date.now();
}

export function normalizeErRelationConfidence(value?: number) {
  const confidence = Number(value ?? 0);
  if (!Number.isFinite(confidence)) {
    return 0;
  }
  return Math.max(0, Math.min(1, confidence));
}

function normalizeErRelationType(rawType?: string) {
  return (rawType || '').trim().toUpperCase() || 'FK';
}

function normalizeErRelationDirection(rawDirection?: string) {
  const direction = (rawDirection || '').trim().toUpperCase().replace(/-/g, '_').replace(/\s+/g, '_');
  if (!direction) {
    return 'SOURCE_TO_TARGET';
  }
  if (direction === 'TARGET_TO_SOURCE' || direction === 'INBOUND' || direction === 'REVERSE' || direction === '<-') {
    return 'TARGET_TO_SOURCE';
  }
  if (direction === 'BIDIRECTIONAL' || direction === 'BOTH' || direction === 'TWO_WAY' || direction === '<->') {
    return 'BIDIRECTIONAL';
  }
  return 'SOURCE_TO_TARGET';
}

export function buildErRelationKey(relation: ErRelationVO) {
  return [
    normalizeErRelationType(relation.relationType),
    (relation.sourceTable || '').trim().toLowerCase(),
    (relation.sourceColumn || '').trim().toLowerCase(),
    (relation.targetTable || '').trim().toLowerCase(),
    (relation.targetColumn || '').trim().toLowerCase(),
    normalizeErRelationDirection(relation.relationDirection),
  ].join('|');
}

export function mergePersistedErGraphState(previousGraph: ErGraphVO | null, nextGraph: ErGraphVO) {
  if (!previousGraph) {
    return {
      ...nextGraph,
      manualRelations: nextGraph.manualRelations || [],
    };
  }
  const routeOverrideMap = new Map<string, Pick<ErRelationVO, 'routeManual' | 'routeLaneX' | 'routeVersion'>>();
  [
    ...(previousGraph.foreignKeyRelations || []),
    ...(previousGraph.aiRelations || []),
    ...(previousGraph.manualRelations || []),
  ].forEach((relation) => {
    const hasManualRoute = relation.routeManual === true && Number.isFinite(Number(relation.routeLaneX));
    if (!hasManualRoute) {
      return;
    }
    routeOverrideMap.set(buildErRelationKey(relation), {
      routeManual: true,
      routeLaneX: Number(relation.routeLaneX),
      routeVersion: relation.routeVersion,
    });
  });

  const mergeRelationRoutes = (relations: ErRelationVO[]) => relations.map((relation) => {
    const override = routeOverrideMap.get(buildErRelationKey(relation));
    return override ? {...relation, ...override} : relation;
  });

  return {
    ...nextGraph,
    layoutCanvas: previousGraph.layoutCanvas,
    nodePositions: previousGraph.nodePositions,
    relationAnchorOffsets: previousGraph.relationAnchorOffsets,
    foreignKeyRelations: mergeRelationRoutes(nextGraph.foreignKeyRelations || []),
    aiRelations: mergeRelationRoutes(nextGraph.aiRelations || []),
    manualRelations: mergeRelationRoutes(previousGraph.manualRelations || []),
  };
}

interface ErDiagramRuntimeHelperContext {
  activeWorkbenchTab: Ref<string>;
  closeContextMenu: () => void;
  downloadImage: (dataUrl: string, fileName: string) => void;
  ensureTableNamesLoaded: (connectionId: number, databaseName: string) => Promise<string[]>;
  erDiagramPanelExport: (pixelRatio?: number) => Promise<string>;
  erSelectConnectionId: Ref<number>;
  erSelectDatabaseName: Ref<string>;
  erSelectModelName: Ref<string>;
  erSelectTableKeyword: Ref<string>;
  erSelectTableOptions: Ref<string[]>;
  erSelectTableValues: Ref<string[]>;
  erSelectTargetTabKey: Ref<string>;
  erTableSelectModalOpen: Ref<boolean>;
  erTableSelectSubmitting: Ref<boolean>;
  erTabs: Ref<ErWorkspaceTab[]>;
  getActiveDatabaseName: (connectionId: number) => string;
  getAiModelOptionValues: () => string[];
  getSelectedAiModel: () => string;
  getWorkflowConnectionId: () => number;
  resolveErUnavailableReason: (connectionId: number, databaseName: string) => string;
  runSafely: (task: () => Promise<void>) => Promise<void>;
}

export function createErDiagramRuntimeHelpers(ctx: ErDiagramRuntimeHelperContext) {
  async function exportErDiagramPngDataUrl(pixelRatio = 2) {
    for (let attempt = 0; attempt < 6; attempt += 1) {
      await nextTick();
      await new Promise((resolve) => setTimeout(resolve, attempt === 0 ? 40 : 90));
      const dataUrl = (await ctx.erDiagramPanelExport(pixelRatio)) || '';
      if (dataUrl) {
        return dataUrl;
      }
    }
    return '';
  }

  async function downloadActiveErDiagram(tab: ErWorkspaceTab) {
    if (!tab.graph || tab.loading) {
      message.info('当前 ER 图尚未准备好，无法导出');
      return;
    }
    let dataUrl = '';
    for (const pixelRatio of erDiagramExportPixelRatioCandidates) {
      dataUrl = await exportErDiagramPngDataUrl(pixelRatio);
      if (dataUrl) {
        break;
      }
    }
    if (!dataUrl) {
      message.error('ER 图导出失败，请稍后重试');
      return;
    }
    const fileNamePart = normalizeDownloadFileNamePart(tab.databaseName || tab.title || 'er');
    ctx.downloadImage(dataUrl, `er-${fileNamePart}-${Date.now()}.png`);
  }

  async function openErTableSelectModal(tab?: ErWorkspaceTab) {
    ctx.closeContextMenu();
    const resolvedConnectionId = tab?.connectionId || ctx.getWorkflowConnectionId();
    if (!resolvedConnectionId) {
      message.error('请先选择连接');
      return;
    }
    const databaseName = (tab?.databaseName || ctx.getActiveDatabaseName(resolvedConnectionId)).trim();
    if (!databaseName || databaseName === '未发现数据库') {
      message.error('请先选择当前数据库');
      return;
    }
    const erUnavailableReason = ctx.resolveErUnavailableReason(resolvedConnectionId, databaseName);
    if (erUnavailableReason) {
      message.warning(erUnavailableReason);
      return;
    }
    const models = ctx.getAiModelOptionValues();
    if (!models.length) {
      message.error('请先在 AI 配置中至少新增一个模型');
      return;
    }
    const selectedAiModel = ctx.getSelectedAiModel();
    const fallbackModel = models.includes(selectedAiModel) ? selectedAiModel : models[0];
    const targetModel = tab && models.includes(tab.selectedAiModel) ? tab.selectedAiModel : fallbackModel;

    ctx.erSelectConnectionId.value = resolvedConnectionId;
    ctx.erSelectDatabaseName.value = databaseName;
    ctx.erSelectTargetTabKey.value = tab?.key || '';
    ctx.erSelectTableKeyword.value = '';
    ctx.erSelectTableValues.value = tab ? [...tab.selectedTableNames] : [];
    ctx.erSelectModelName.value = targetModel;
    ctx.erTableSelectModalOpen.value = true;

    await ctx.runSafely(async () => {
      const tables = await ctx.ensureTableNamesLoaded(resolvedConnectionId, databaseName);
      const normalized = Array.from(new Set((tables ?? []).map((item) => (item || '').trim()).filter((item) => !!item)));
      ctx.erSelectTableOptions.value = normalized.sort((a, b) => a.localeCompare(b));
    });
  }

  async function refreshErGraphForTab(tab: ErWorkspaceTab, includeAiInference?: boolean) {
    if (!tab.connectionId || !tab.databaseName || !tab.selectedTableNames.length) {
      return;
    }
    const erUnavailableReason = ctx.resolveErUnavailableReason(tab.connectionId, tab.databaseName);
    if (erUnavailableReason) {
      tab.errorMessage = erUnavailableReason;
      touchErTab(tab);
      message.warning(erUnavailableReason);
      return;
    }
    tab.loading = true;
    tab.errorMessage = '';
    touchErTab(tab);
    try {
      const previousGraph = tab.graph;
      const payload: ErGraphReq = {
        connectionId: tab.connectionId,
        databaseName: tab.databaseName,
        tableNames: [...tab.selectedTableNames],
        modelName: tab.selectedAiModel || undefined,
        includeAiInference: includeAiInference == null ? tab.includeAiInference : includeAiInference,
        aiConfidenceThreshold: tab.aiConfidenceThreshold,
      };
      const graph = await postApi<ErGraphVO>('/api/schema/er/graph', payload);
      tab.graph = mergePersistedErGraphState(previousGraph, graph);
      tab.includeAiInference = payload.includeAiInference !== false;
      tab.errorMessage = '';
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      tab.errorMessage = msg || '加载ER图失败';
      message.error(tab.errorMessage);
    } finally {
      tab.loading = false;
      touchErTab(tab);
    }
  }

  async function confirmErTableSelection() {
    if (!ctx.erSelectConnectionId.value || !ctx.erSelectDatabaseName.value) {
      message.error('缺少连接或数据库信息');
      return;
    }
    const erUnavailableReason = ctx.resolveErUnavailableReason(
      ctx.erSelectConnectionId.value,
      ctx.erSelectDatabaseName.value,
    );
    if (erUnavailableReason) {
      message.warning(erUnavailableReason);
      return;
    }
    const selected = Array.from(
      new Set(ctx.erSelectTableValues.value.map((item) => (item || '').trim()).filter((item) => !!item)),
    );
    if (!selected.length) {
      message.error('请至少选择一张表');
      return;
    }
    if (selected.length > 30) {
      message.error('最多选择 30 张表');
      return;
    }

    const models = ctx.getAiModelOptionValues();
    if (!models.length) {
      message.error('请先在 AI 配置中至少新增一个模型');
      return;
    }
    const selectedModel = ctx.erSelectModelName.value.trim();
    if (!selectedModel) {
      message.error('请选择用于 ER 关系推断的模型');
      return;
    }
    if (!models.includes(selectedModel)) {
      message.error('所选模型已不可用，请重新选择');
      ctx.erSelectModelName.value = models[0] ?? '';
      return;
    }

    ctx.erTableSelectSubmitting.value = true;
    try {
      const targetTab = ctx.erSelectTargetTabKey.value
        ? ctx.erTabs.value.find((item) => item.key === ctx.erSelectTargetTabKey.value) ?? null
        : null;
      let tab: ErWorkspaceTab;
      if (!targetTab) {
        const now = Date.now();
        const createdTab: ErWorkspaceTab = {
          key: `er-${now}-${Math.round(Math.random() * 1000)}`,
          title: `ER · ${ctx.erSelectDatabaseName.value}`,
          snapshotId: undefined,
          connectionId: ctx.erSelectConnectionId.value,
          databaseName: ctx.erSelectDatabaseName.value,
          selectedTableNames: [...selected],
          selectedAiModel: selectedModel,
          layoutMode: 'GRID',
          lineType: 'POLYLINE',
          showCardComments: false,
          detailCollapsed: false,
          aiConfidenceThreshold: 0.6,
          includeAiInference: true,
          loading: false,
          graph: null,
          selectedRelationKey: '',
          errorMessage: '',
          createdAt: now,
          updatedAt: now,
        };
        ctx.erTabs.value = [...ctx.erTabs.value, createdTab];
        tab = ctx.erTabs.value.find((item) => item.key === createdTab.key) ?? createdTab;
      } else {
        tab = targetTab;
        tab.snapshotId = undefined;
        tab.connectionId = ctx.erSelectConnectionId.value;
        tab.databaseName = ctx.erSelectDatabaseName.value;
        tab.selectedTableNames = [...selected];
        tab.selectedAiModel = selectedModel;
        if (!tab.layoutMode) {
          tab.layoutMode = 'GRID';
        }
        if (tab.lineType !== 'STRAIGHT' && tab.lineType !== 'POLYLINE') {
          tab.lineType = 'POLYLINE';
        }
        if (tab.showCardComments == null) {
          tab.showCardComments = false;
        }
        if (tab.detailCollapsed == null) {
          tab.detailCollapsed = false;
        }
        if (typeof tab.selectedRelationKey !== 'string') {
          tab.selectedRelationKey = '';
        }
        tab.title = `ER · ${ctx.erSelectDatabaseName.value}`;
        touchErTab(tab);
      }
      ctx.activeWorkbenchTab.value = tab.key;
      ctx.erTableSelectModalOpen.value = false;
      await nextTick();
      void refreshErGraphForTab(tab, true);
    } finally {
      ctx.erTableSelectSubmitting.value = false;
    }
  }

  return {
    exportErDiagramPngDataUrl,
    downloadActiveErDiagram,
    openErTableSelectModal,
    refreshErGraphForTab,
    confirmErTableSelection,
  };
}
