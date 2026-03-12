import type {StudioRuntime} from './useStudioRuntime';

type ErTab = StudioRuntime['erTabs']['value'][number];
type ErRelation = NonNullable<NonNullable<ErTab['graph']>['aiRelations']>[number];

interface ErRelationRouteChangePayload {
  relationKey: string;
  routeManual: boolean;
  routeLaneX: number;
}

export interface ErModule {
  touchErTab: (tab: ErTab) => void;
  toggleErDetailCollapsed: (tab: ErTab) => void;
  normalizeErRelationDirection: (rawDirection?: string) => 'SOURCE_TO_TARGET' | 'TARGET_TO_SOURCE' | 'BIDIRECTIONAL';
  normalizeErRelationType: (rawType?: string) => string;
  erRelationKey: (relation: ErRelation) => string;
  erRelationArrow: (directionRaw?: string) => '<-' | '->' | '<->';
  erRelationDirectionLabel: (directionRaw?: string) => '目标指向源' | '双向' | '源指向目标';
  formatErRelationConfidence: (value?: number) => string;
  normalizeErRelationConfidence: (value?: number) => number;
  erRelationReasonPreview: (reason?: string) => string;
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
    normalizeErRelationDirection,
    normalizeErRelationType,
    erRelationKey,
    erRelationArrow,
    erRelationDirectionLabel,
    formatErRelationConfidence,
    normalizeErRelationConfidence,
    erRelationReasonPreview,
    handleErRelationRouteChange,
    removeErAiRelation,
    closeErTab,
  };
}
