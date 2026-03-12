import type {StudioRuntime} from './useStudioRuntime';

type ErTab = StudioRuntime['erTabs']['value'][number];
type ErRelation = NonNullable<NonNullable<ErTab['graph']>['aiRelations']>[number];
type ErLayoutMode = ErTab['layoutMode'];

interface ErGraphLayoutChangePayload {
  layoutCanvas?: {
    width: number;
    height: number;
  };
  nodePositions?: Record<string, {
    x: number;
    y: number;
  }>;
  relationAnchorOffsets?: Record<string, {
    sourcePerimeterPos?: number;
    targetPerimeterPos?: number;
  }>;
}

interface ErRelationRouteChangePayload {
  relationKey: string;
  routeManual: boolean;
  routeLaneX: number;
}

export interface ErModule {
  touchErTab: (tab: ErTab) => void;
  toggleErDetailCollapsed: (tab: ErTab) => void;
  handleErLayoutModeChange: (tab: ErTab, nextLayoutMode?: ErLayoutMode | string) => void;
  normalizeErRelationDirection: (rawDirection?: string) => 'SOURCE_TO_TARGET' | 'TARGET_TO_SOURCE' | 'BIDIRECTIONAL';
  normalizeErRelationType: (rawType?: string) => string;
  erRelationKey: (relation: ErRelation) => string;
  erRelationArrow: (directionRaw?: string) => '<-' | '->' | '<->';
  erRelationDirectionLabel: (directionRaw?: string) => '目标指向源' | '双向' | '源指向目标';
  formatErRelationConfidence: (value?: number) => string;
  normalizeErRelationConfidence: (value?: number) => number;
  erRelationReasonPreview: (reason?: string) => string;
  handleErGraphLayoutChange: (tab: ErTab, payload: ErGraphLayoutChangePayload) => void;
  handleErRelationRouteChange: (tab: ErTab, payload: ErRelationRouteChangePayload) => void;
  removeErAiRelation: (tab: ErTab, relation: ErRelation) => void;
  closeErTab: (tabKey: string) => void;
}

export function useErModule(runtime: StudioRuntime): ErModule {
  function touchErTab(tab: ErTab) {
    tab.updatedAt = Date.now();
  }

  function toggleErDetailCollapsed(tab: ErTab) {
    tab.detailCollapsed = !tab.detailCollapsed;
    touchErTab(tab);
  }

  function normalizeErLayoutMode(rawLayoutMode?: ErLayoutMode | string): ErLayoutMode {
    return rawLayoutMode === 'CIRCLE' || rawLayoutMode === 'HIERARCHICAL'
      ? rawLayoutMode
      : 'GRID';
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

  function normalizeErRelationType(rawType?: string) {
    return (rawType || '').trim().toUpperCase() || 'FK';
  }

  function erRelationKey(relation: ErRelation) {
    return [
      normalizeErRelationType(relation.relationType),
      (relation.sourceTable || '').trim().toLowerCase(),
      (relation.sourceColumn || '').trim().toLowerCase(),
      (relation.targetTable || '').trim().toLowerCase(),
      (relation.targetColumn || '').trim().toLowerCase(),
      normalizeErRelationDirection(relation.relationDirection),
    ].join('|');
  }

  function erRelationArrow(directionRaw?: string) {
    const direction = normalizeErRelationDirection(directionRaw);
    if (direction === 'TARGET_TO_SOURCE') {
      return '<-';
    }
    if (direction === 'BIDIRECTIONAL') {
      return '<->';
    }
    return '->';
  }

  function erRelationDirectionLabel(directionRaw?: string) {
    const direction = normalizeErRelationDirection(directionRaw);
    if (direction === 'TARGET_TO_SOURCE') {
      return '目标指向源';
    }
    if (direction === 'BIDIRECTIONAL') {
      return '双向';
    }
    return '源指向目标';
  }

  function normalizeErRelationConfidence(value?: number) {
    const confidence = Number(value ?? 0);
    if (!Number.isFinite(confidence)) {
      return 0;
    }
    return Math.max(0, Math.min(1, confidence));
  }

  function formatErRelationConfidence(value?: number) {
    const confidence = normalizeErRelationConfidence(value);
    return `${Math.round(confidence * 100)}%`;
  }

  function erRelationReasonPreview(reason?: string) {
    const text = (reason || '').trim();
    if (!text) {
      return '模型未返回理由';
    }
    return text.length > 36 ? `${text.slice(0, 36)}...` : text;
  }

  function sanitizeGraphLayoutPayload(tab: ErTab, payload: ErGraphLayoutChangePayload) {
    const validTableKeys = new Set((tab.graph?.tables || []).map((table) => (table.tableName || '').trim().toLowerCase()));
    const validRelationKeys = new Set([
      ...(tab.graph?.foreignKeyRelations || []),
      ...(tab.graph?.aiRelations || []),
    ].map((relation) => erRelationKey(relation)));

    const layoutCanvas = Number.isFinite(Number(payload.layoutCanvas?.width)) && Number.isFinite(Number(payload.layoutCanvas?.height))
      && Number(payload.layoutCanvas?.width) > 0 && Number(payload.layoutCanvas?.height) > 0
      ? {
        width: Number(payload.layoutCanvas?.width),
        height: Number(payload.layoutCanvas?.height),
      }
      : undefined;

    const nodePositionEntries = Object.entries(payload.nodePositions || {})
      .map(([key, value]) => {
        const normalizedKey = (key || '').trim().toLowerCase();
        const x = Number(value?.x);
        const y = Number(value?.y);
        if (!validTableKeys.has(normalizedKey) || !Number.isFinite(x) || !Number.isFinite(y)) {
          return null;
        }
        return [normalizedKey, {x, y}] as const;
      })
      .filter((entry): entry is readonly [string, {x: number; y: number}] => !!entry);

    const relationAnchorEntries = Object.entries(payload.relationAnchorOffsets || {})
      .map(([key, value]) => {
        if (!validRelationKeys.has(key)) {
          return null;
        }
        const nextValue: {sourcePerimeterPos?: number; targetPerimeterPos?: number} = {};
        const sourcePerimeterPos = Number(value?.sourcePerimeterPos);
        const targetPerimeterPos = Number(value?.targetPerimeterPos);
        if (Number.isFinite(sourcePerimeterPos)) {
          nextValue.sourcePerimeterPos = sourcePerimeterPos;
        }
        if (Number.isFinite(targetPerimeterPos)) {
          nextValue.targetPerimeterPos = targetPerimeterPos;
        }
        if (nextValue.sourcePerimeterPos == null && nextValue.targetPerimeterPos == null) {
          return null;
        }
        return [key, nextValue] as const;
      })
      .filter((entry): entry is readonly [string, {sourcePerimeterPos?: number; targetPerimeterPos?: number}] => !!entry);

    return {
      layoutCanvas,
      nodePositions: nodePositionEntries.length ? Object.fromEntries(nodePositionEntries) : undefined,
      relationAnchorOffsets: relationAnchorEntries.length ? Object.fromEntries(relationAnchorEntries) : undefined,
    };
  }

  function handleErLayoutModeChange(tab: ErTab, nextLayoutMode?: ErLayoutMode | string) {
    const normalizedLayoutMode = normalizeErLayoutMode(nextLayoutMode);
    tab.layoutMode = normalizedLayoutMode;
    if (tab.graph) {
      const resetRelationRoute = (relation: ErRelation) => {
        const nextRelation = {...relation};
        delete nextRelation.routeManual;
        delete nextRelation.routeLaneX;
        delete nextRelation.routeVersion;
        return nextRelation;
      };
      tab.graph = {
        ...tab.graph,
        layoutCanvas: undefined,
        nodePositions: undefined,
        relationAnchorOffsets: undefined,
        foreignKeyRelations: (tab.graph.foreignKeyRelations || []).map(resetRelationRoute),
        aiRelations: (tab.graph.aiRelations || []).map(resetRelationRoute),
      };
    }
    touchErTab(tab);
  }

  function handleErGraphLayoutChange(tab: ErTab, payload: ErGraphLayoutChangePayload) {
    if (!tab.graph) {
      return;
    }
    const normalizedLayout = sanitizeGraphLayoutPayload(tab, payload);
    const currentLayout = sanitizeGraphLayoutPayload(tab, {
      layoutCanvas: tab.graph.layoutCanvas,
      nodePositions: tab.graph.nodePositions,
      relationAnchorOffsets: tab.graph.relationAnchorOffsets,
    });
    if (JSON.stringify(currentLayout) === JSON.stringify(normalizedLayout)) {
      return;
    }
    tab.graph = {
      ...tab.graph,
      layoutCanvas: normalizedLayout.layoutCanvas,
      nodePositions: normalizedLayout.nodePositions,
      relationAnchorOffsets: normalizedLayout.relationAnchorOffsets,
    };
    touchErTab(tab);
  }

  function handleErRelationRouteChange(tab: ErTab, payload: ErRelationRouteChangePayload) {
    if (!tab?.graph?.tables?.length) {
      return;
    }
    const relationKey = (payload.relationKey || '').trim();
    const routeLaneX = Number(payload.routeLaneX);
    if (!relationKey || !Number.isFinite(routeLaneX)) {
      return;
    }
    const patchList = (sourceList: ErRelation[]) => {
      let changed = false;
      const nextList = sourceList.map((item) => {
        if (erRelationKey(item) !== relationKey) {
          return item;
        }
        changed = true;
        const nextRouteVersionRaw = Number(item.routeVersion ?? 0);
        const nextRouteVersion = Number.isFinite(nextRouteVersionRaw) ? nextRouteVersionRaw + 1 : 1;
        return {
          ...item,
          routeManual: payload.routeManual === true,
          routeLaneX,
          routeVersion: nextRouteVersion,
        };
      });
      return {changed, nextList};
    };

    const fkPatched = patchList(tab.graph.foreignKeyRelations || []);
    const aiPatched = patchList(tab.graph.aiRelations || []);
    if (!fkPatched.changed && !aiPatched.changed) {
      return;
    }
    tab.graph = {
      ...tab.graph,
      foreignKeyRelations: fkPatched.nextList,
      aiRelations: aiPatched.nextList,
    };
    touchErTab(tab);
  }

  function removeErAiRelation(tab: ErTab, relation: ErRelation) {
    if (!tab.graph?.aiRelations?.length) {
      return;
    }
    const sourceList = tab.graph.aiRelations;
    let removeIndex = sourceList.findIndex((item) => item === relation);
    if (removeIndex < 0) {
      const direction = normalizeErRelationDirection(relation.relationDirection);
      const confidence = normalizeErRelationConfidence(relation.confidence);
      const reason = (relation.reason || '').trim();
      removeIndex = sourceList.findIndex((item) => (
        item.sourceTable === relation.sourceTable
        && item.sourceColumn === relation.sourceColumn
        && item.targetTable === relation.targetTable
        && item.targetColumn === relation.targetColumn
        && normalizeErRelationDirection(item.relationDirection) === direction
        && normalizeErRelationConfidence(item.confidence) === confidence
        && (item.reason || '').trim() === reason
      ));
    }
    if (removeIndex < 0) {
      return;
    }
    const nextAiRelations = [...sourceList];
    nextAiRelations.splice(removeIndex, 1);
    tab.graph = {
      ...tab.graph,
      aiRelations: nextAiRelations,
    };
    touchErTab(tab);
  }

  function closeErTab(tabKey: string) {
    const index = runtime.erTabs.value.findIndex((item) => item.key === tabKey);
    if (index < 0) {
      return;
    }
    const tabs = [...runtime.erTabs.value];
    tabs.splice(index, 1);
    runtime.erTabs.value = tabs;
    if (runtime.activeWorkbenchTab.value === tabKey) {
      runtime.activeWorkbenchTab.value = tabs[index]?.key || tabs[index - 1]?.key || runtime.browserTabKey;
      runtime.ensureActiveWorkbenchTab();
    }
  }

  return {
    touchErTab,
    toggleErDetailCollapsed,
    handleErLayoutModeChange,
    normalizeErRelationDirection,
    normalizeErRelationType,
    erRelationKey,
    erRelationArrow,
    erRelationDirectionLabel,
    formatErRelationConfidence,
    normalizeErRelationConfidence,
    erRelationReasonPreview,
    handleErGraphLayoutChange,
    handleErRelationRouteChange,
    removeErAiRelation,
    closeErTab,
  };
}
