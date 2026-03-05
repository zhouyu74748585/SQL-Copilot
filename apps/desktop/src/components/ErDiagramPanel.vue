<template>
  <div class="er-diagram-shell">
    <div ref="chartRef" class="er-diagram-canvas" />
    <div v-if="!optionReady" class="er-diagram-empty">{{ emptyText }}</div>
  </div>
</template>

<script setup lang="ts">
import type {ErColumnNodeVO, ErGraphVO, ErLayoutMode, ErRelationVO, ErTableNodeVO} from '../types';
import * as echarts from 'echarts';
import {nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue';

const props = withDefaults(defineProps<{
  graph: ErGraphVO | null;
  layoutMode?: ErLayoutMode;
  showComments?: boolean;
}>(), {
  layoutMode: 'GRID',
  showComments: false,
});

const chartRef = ref<HTMLElement | null>(null);
let chartInstance: echarts.ECharts | null = null;
let resizeObserver: ResizeObserver | null = null;
const optionReady = ref(false);
const emptyText = ref('No ER graph data');
const nodePositionOverrides = new Map<string, NodePosition>();
let graphNodeDragEndHandler: ((params: any) => void) | null = null;
let graphNodeMouseUpHandler: ((params: any) => void) | null = null;
let dragMouseUpFallbackTimer: number | null = null;
let lastDragCommitAt = 0;
let lastLayoutModeKey = '';
let lastTableSignature = '';

const NODE_WIDTH = 248;
const NODE_HEADER_HEIGHT = 46;
const NODE_MIN_BODY_ROWS = 1;
const NODE_MAX_COLUMNS = 12;
const NODE_ROW_HEIGHT = 16;
const NODE_COMMENT_PREVIEW_MAX = 14;
const NODE_HORIZONTAL_GAP = 14;
const NODE_VERTICAL_GAP = 16;
const NODE_BODY_TOP_PADDING = 8;
const NODE_BODY_BOTTOM_PADDING = 8;
const ORTHOGONAL_LANE_GAP = 22;

type NodePosition = { x: number; y: number };
type RelationDirection = 'SOURCE_TO_TARGET' | 'TARGET_TO_SOURCE' | 'BIDIRECTIONAL';

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

function safeText(text: string | null | undefined) {
  return (text || '').trim();
}

function normalizeIdentifier(text: string | null | undefined) {
  return safeText(text).toLowerCase();
}

function saveNodePositionOverride(tableName: string, x: number, y: number) {
  if (!tableName || !Number.isFinite(x) || !Number.isFinite(y)) {
    return;
  }
  nodePositionOverrides.set(normalizeIdentifier(tableName), {x, y});
}

function resolveNodePositionFromChart(tableName: string): NodePosition | null {
  if (!chartInstance || !tableName) {
    return null;
  }
  const tableKey = normalizeIdentifier(tableName);
  try {
    const ecModel = (chartInstance as any).getModel?.();
    const seriesModel = ecModel?.getSeriesByIndex?.(0);
    const seriesData = seriesModel?.getData?.();
    if (seriesData) {
      let matchedIndex = -1;
      if (typeof seriesData.indexOfName === 'function') {
        matchedIndex = Number(seriesData.indexOfName(tableName));
      }
      if (!Number.isInteger(matchedIndex) || matchedIndex < 0) {
        const count = typeof seriesData.count === 'function' ? Number(seriesData.count()) : 0;
        for (let i = 0; i < count; i += 1) {
          const name = normalizeIdentifier(seriesData.getName?.(i));
          if (name === tableKey) {
            matchedIndex = i;
            break;
          }
        }
      }
      if (matchedIndex >= 0 && Number.isInteger(matchedIndex)) {
        const layout = seriesData.getItemLayout?.(matchedIndex);
        if (Array.isArray(layout) && layout.length >= 2) {
          const x = Number(layout[0]);
          const y = Number(layout[1]);
          if (Number.isFinite(x) && Number.isFinite(y)) {
            return {x, y};
          }
        }
        const x = Number((layout as any)?.x);
        const y = Number((layout as any)?.y);
        if (Number.isFinite(x) && Number.isFinite(y)) {
          return {x, y};
        }
      }
    }
  } catch {
    // 忽略布局读取异常，回退到 option 数据读取。
  }
  const option = chartInstance.getOption() as any;
  const seriesList = Array.isArray(option?.series) ? option.series : [option?.series];
  for (const series of seriesList) {
    const data = Array.isArray(series?.data) ? series.data : [];
    const matched = data.find((item: any) => {
      const idKey = normalizeIdentifier(item?.id ?? item?.name);
      const tableNameKey = normalizeIdentifier(item?.table?.tableName);
      return idKey === tableKey || tableNameKey === tableKey;
    });
    if (!matched) {
      continue;
    }
    const x = Number(matched.x);
    const y = Number(matched.y);
    if (Number.isFinite(x) && Number.isFinite(y)) {
      return {x, y};
    }
  }
  return null;
}

function resolveDraggedTableName(params: any) {
  const tableNameByMeta = safeText(params?.data?.table?.tableName);
  if (tableNameByMeta) {
    return tableNameByMeta;
  }
  const tableNameById = safeText(params?.data?.id ?? params?.data?.name);
  if (tableNameById) {
    return tableNameById;
  }
  return safeText(params?.name);
}

function persistDraggedNodePosition(tableName: string, fallbackX: number, fallbackY: number) {
  const commit = () => {
    const chartPosition = resolveNodePositionFromChart(tableName);
    let x = Number(chartPosition?.x);
    let y = Number(chartPosition?.y);
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      x = Number(fallbackX);
      y = Number(fallbackY);
    }
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      return;
    }
    saveNodePositionOverride(tableName, x, y);
    renderGraph();
  };
  if (typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function') {
    window.requestAnimationFrame(() => {
      commit();
    });
    return;
  }
  setTimeout(() => {
    commit();
  }, 16);
}

function pruneNodePositionOverrides(graph: ErGraphVO | null) {
  if (!graph?.tables?.length) {
    nodePositionOverrides.clear();
    return;
  }
  const currentKeys = new Set(graph.tables.map((item) => normalizeIdentifier(item.tableName)));
  Array.from(nodePositionOverrides.keys()).forEach((key) => {
    if (!currentKeys.has(key)) {
      nodePositionOverrides.delete(key);
    }
  });
}

function buildTableSignature(graph: ErGraphVO | null) {
  if (!graph?.tables?.length) {
    return '';
  }
  return graph.tables
    .map((item) => normalizeIdentifier(item.tableName))
    .filter((item) => !!item)
    .sort((a, b) => a.localeCompare(b))
    .join('|');
}

function resetNodePositionStateIfNeeded(graph: ErGraphVO | null, layoutMode: ErLayoutMode) {
  const nextLayoutModeKey = normalizeLayoutMode(layoutMode);
  const nextTableSignature = buildTableSignature(graph);
  if (nextLayoutModeKey === lastLayoutModeKey && nextTableSignature === lastTableSignature) {
    return;
  }
  nodePositionOverrides.clear();
  lastLayoutModeKey = nextLayoutModeKey;
  lastTableSignature = nextTableSignature;
}

function escapeHtml(text: string) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function truncateText(text: string, max: number) {
  const source = safeText(text);
  if (!source) {
    return '';
  }
  return source.length > max ? `${source.slice(0, max)}...` : source;
}

function normalizeLayoutMode(mode?: ErLayoutMode): ErLayoutMode {
  if (mode === 'CIRCLE' || mode === 'HIERARCHICAL') {
    return mode;
  }
  return 'GRID';
}

function normalizeRelationDirection(rawDirection?: string): RelationDirection {
  const direction = safeText(rawDirection).toUpperCase().replace(/-/g, '_').replace(/\s+/g, '_');
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

function relationArrowText(direction: RelationDirection) {
  if (direction === 'TARGET_TO_SOURCE') {
    return '<-';
  }
  if (direction === 'BIDIRECTIONAL') {
    return '<->';
  }
  return '->';
}

function relationDirectionText(direction: RelationDirection) {
  if (direction === 'TARGET_TO_SOURCE') {
    return '目标指向源';
  }
  if (direction === 'BIDIRECTIONAL') {
    return '双向';
  }
  return '源指向目标';
}

function relationEdgeSymbol(direction: RelationDirection): [string, string] {
  if (direction === 'TARGET_TO_SOURCE') {
    return ['arrow', 'none'];
  }
  if (direction === 'BIDIRECTIONAL') {
    return ['arrow', 'arrow'];
  }
  return ['none', 'arrow'];
}

function buildColumnMarks(column: ErColumnNodeVO) {
  const marks = [
    column.primaryKey ? 'PK' : '',
    column.indexed ? 'IDX' : '',
  ].filter((item) => !!item);
  return marks.length ? `[${marks.join(',')}]` : '';
}

function buildColumnDisplayName(column: ErColumnNodeVO) {
  const columnName = safeText(column.columnName);
  const marks = buildColumnMarks(column);
  if (!marks) {
    return columnName;
  }
  return `${columnName} ${marks}`;
}

function buildColumnPreviewComment(column: ErColumnNodeVO, showComments: boolean) {
  if (!showComments) {
    return '';
  }
  const comment = truncateText(column.columnComment || '', NODE_COMMENT_PREVIEW_MAX);
  if (!comment) {
    return '';
  }
  return ` // ${comment}`;
}

function buildColumnLine(column: ErColumnNodeVO, showComments: boolean) {
  const name = truncateText(buildColumnDisplayName(column), 24);
  const dataType = truncateText(safeText(column.dataType) || '-', 18);
  const comment = buildColumnPreviewComment(column, showComments);
  return truncateText(`${name}: ${dataType}${comment}`, 52);
}

function buildNodeLabel(table: ErTableNodeVO, showComments: boolean) {
  const tableName = truncateText(table.tableName || '', 30);
  const tableComment = showComments ? (truncateText(table.tableComment || '', 22) || ' ') : ' ';
  const columns = table.columns || [];
  const rows = columns.slice(0, NODE_MAX_COLUMNS).map((column) => `{col|${buildColumnLine(column, showComments)}}`);
  if (!rows.length) {
    rows.push('{empty|暂无字段}');
  }
  if (columns.length > NODE_MAX_COLUMNS) {
    rows.push(`{ellipsis|${columns.length - NODE_MAX_COLUMNS} more columns...}`);
  }
  return `{title|${tableName}}\n{subtitle|${tableComment}}\n${rows.join('\n')}`;
}

function edgeLabel(relation: ErRelationVO) {
  if ((relation.relationType || '').toUpperCase() !== 'AI_INFERRED') {
    return '';
  }
  const confidence = Number(relation.confidence ?? 0);
  if (!Number.isFinite(confidence)) {
    return '';
  }
  return `${Math.round(Math.max(0, Math.min(1, confidence)) * 100)}%`;
}

function normalizeConfidence(rawConfidence?: number) {
  const value = Number(rawConfidence ?? 0);
  if (!Number.isFinite(value)) {
    return 0;
  }
  return Math.max(0, Math.min(1, value));
}

function buildAiEdgeColor(confidence?: number) {
  const t = normalizeConfidence(confidence);
  const low = {r: 235, g: 221, b: 187};
  const high = {r: 122, g: 86, b: 16};
  const r = Math.round(low.r + (high.r - low.r) * t);
  const g = Math.round(low.g + (high.g - low.g) * t);
  const b = Math.round(low.b + (high.b - low.b) * t);
  return `rgb(${r}, ${g}, ${b})`;
}

function nodeHeight(table: ErTableNodeVO) {
  const rowCount = Math.max(NODE_MIN_BODY_ROWS, Math.min(NODE_MAX_COLUMNS, table.columns?.length || 0));
  const moreRow = (table.columns?.length || 0) > NODE_MAX_COLUMNS ? 1 : 0;
  return NODE_HEADER_HEIGHT + NODE_BODY_TOP_PADDING + (rowCount + moreRow) * NODE_ROW_HEIGHT + NODE_BODY_BOTTOM_PADDING;
}

function buildTableColumnIndex(table: ErTableNodeVO) {
  const indexMap = new Map<string, number>();
  (table.columns || []).forEach((column, index) => {
    const key = normalizeIdentifier(column.columnName);
    if (!key || indexMap.has(key)) {
      return;
    }
    indexMap.set(key, index);
  });
  return indexMap;
}

function resolveRelationAnchorOffsetY(table: ErTableNodeVO, columnName: string, columnIndexMap: Map<string, number>) {
  if (!columnIndexMap.size) {
    return NODE_HEADER_HEIGHT + NODE_BODY_TOP_PADDING + NODE_ROW_HEIGHT / 2;
  }
  const key = normalizeIdentifier(columnName);
  const rawIndex = key ? (columnIndexMap.get(key) ?? 0) : 0;
  const hasEllipsis = (table.columns?.length || 0) > NODE_MAX_COLUMNS;
  const rowIndex = rawIndex < NODE_MAX_COLUMNS
    ? rawIndex
    : (hasEllipsis ? NODE_MAX_COLUMNS : (NODE_MAX_COLUMNS - 1));
  return NODE_HEADER_HEIGHT + NODE_BODY_TOP_PADDING + rowIndex * NODE_ROW_HEIGHT + NODE_ROW_HEIGHT / 2;
}

function resolveCanvasSize() {
  const width = Math.max(520, chartRef.value?.clientWidth || 0);
  const height = Math.max(380, chartRef.value?.clientHeight || 0);
  return {width, height};
}

function buildGridPositions(tables: ErTableNodeVO[], canvasWidth: number): Record<string, NodePosition> {
  const positions: Record<string, NodePosition> = {};
  const paddingLeft = 16;
  const paddingTop = 16;
  const availableWidth = Math.max(1, canvasWidth - paddingLeft * 2 + NODE_HORIZONTAL_GAP);
  const columnCount = clamp(Math.floor(availableWidth / (NODE_WIDTH + NODE_HORIZONTAL_GAP)), 1, 8);
  let currentY = paddingTop;

  for (let row = 0; row * columnCount < tables.length; row += 1) {
    const rowStart = row * columnCount;
    const rowTables = tables.slice(rowStart, rowStart + columnCount);
    const rowCount = rowTables.length;
    const rowMaxHeight = Math.max(...rowTables.map((table) => nodeHeight(table)));
    const rowWidth = rowCount * NODE_WIDTH + (rowCount - 1) * NODE_HORIZONTAL_GAP;
    const startX = Math.max(paddingLeft, (canvasWidth - rowWidth) / 2);
    rowTables.forEach((table, col) => {
      const x = startX + col * (NODE_WIDTH + NODE_HORIZONTAL_GAP) + NODE_WIDTH / 2;
      const y = currentY + rowMaxHeight / 2;
      positions[table.tableName] = {x, y};
    });
    currentY += rowMaxHeight + NODE_VERTICAL_GAP;
  }
  return positions;
}

function buildCirclePositions(tables: ErTableNodeVO[], canvasWidth: number, canvasHeight: number): Record<string, NodePosition> {
  const positions: Record<string, NodePosition> = {};
  const centerX = canvasWidth / 2;
  const centerY = canvasHeight / 2;
  if (tables.length === 1) {
    positions[tables[0].tableName] = {x: centerX, y: centerY};
    return positions;
  }
  const maxNodeHeight = Math.max(...tables.map((table) => nodeHeight(table)));
  const radius = Math.max(
    96,
    Math.min(canvasWidth, canvasHeight) / 2 - Math.max(NODE_WIDTH, maxNodeHeight) / 2 - 12,
  );
  tables.forEach((table, index) => {
    const angle = (-Math.PI / 2) + (Math.PI * 2 * index) / tables.length;
    positions[table.tableName] = {
      x: centerX + radius * Math.cos(angle),
      y: centerY + radius * Math.sin(angle),
    };
  });
  return positions;
}

function buildHierarchyLevels(tables: ErTableNodeVO[], relations: ErRelationVO[]) {
  const tableNames = tables.map((table) => table.tableName);
  const tableNameSet = new Set(tableNames);
  const indegree = new Map<string, number>();
  const adjacency = new Map<string, Set<string>>();
  tableNames.forEach((tableName) => {
    indegree.set(tableName, 0);
    adjacency.set(tableName, new Set<string>());
  });
  relations.forEach((relation) => {
    const source = relation.sourceTable;
    const target = relation.targetTable;
    if (!tableNameSet.has(source) || !tableNameSet.has(target) || source === target) {
      return;
    }
    const neighbors = adjacency.get(source);
    if (!neighbors || neighbors.has(target)) {
      return;
    }
    neighbors.add(target);
    indegree.set(target, (indegree.get(target) || 0) + 1);
  });

  const queue: string[] = tableNames.filter((name) => (indegree.get(name) || 0) === 0);
  const levels = new Map<string, number>();
  queue.forEach((name) => {
    levels.set(name, 0);
  });

  let cursor = 0;
  while (cursor < queue.length) {
    const tableName = queue[cursor];
    cursor += 1;
    const currentLevel = levels.get(tableName) || 0;
    const neighbors = Array.from(adjacency.get(tableName) || []);
    neighbors.forEach((neighbor) => {
      levels.set(neighbor, Math.max(levels.get(neighbor) || 0, currentLevel + 1));
      indegree.set(neighbor, (indegree.get(neighbor) || 0) - 1);
      if ((indegree.get(neighbor) || 0) <= 0) {
        queue.push(neighbor);
      }
    });
  }

  tableNames.forEach((name) => {
    if (!levels.has(name)) {
      levels.set(name, 0);
    }
  });
  return levels;
}

function buildHierarchicalPositions(
  tables: ErTableNodeVO[],
  relations: ErRelationVO[],
  canvasWidth: number,
  canvasHeight: number,
): Record<string, NodePosition> {
  const positions: Record<string, NodePosition> = {};
  const levels = buildHierarchyLevels(tables, relations);
  const levelMap = new Map<number, string[]>();
  tables.forEach((table) => {
    const level = levels.get(table.tableName) || 0;
    if (!levelMap.has(level)) {
      levelMap.set(level, []);
    }
    levelMap.get(level)?.push(table.tableName);
  });
  const sortedLevels = Array.from(levelMap.keys()).sort((a, b) => a - b);
  const left = 24;
  const right = canvasWidth - 24;
  const top = 24;
  const bottom = canvasHeight - 24;
  const xStep = sortedLevels.length > 1 ? (right - left) / (sortedLevels.length - 1) : 0;

  sortedLevels.forEach((level, levelIndex) => {
    const names = (levelMap.get(level) || []).sort((a, b) => a.localeCompare(b));
    const stepY = (bottom - top) / (names.length + 1);
    names.forEach((name, index) => {
      positions[name] = {
        x: left + levelIndex * xStep,
        y: top + (index + 1) * stepY,
      };
    });
  });
  return positions;
}

function buildNodePositions(
  tables: ErTableNodeVO[],
  relations: ErRelationVO[],
  layoutMode: ErLayoutMode,
  canvasWidth: number,
  canvasHeight: number,
) {
  const mode = normalizeLayoutMode(layoutMode);
  if (mode === 'CIRCLE') {
    return buildCirclePositions(tables, canvasWidth, canvasHeight);
  }
  if (mode === 'HIERARCHICAL') {
    return buildHierarchicalPositions(tables, relations, canvasWidth, canvasHeight);
  }
  return buildGridPositions(tables, canvasWidth);
}

function buildNodeTooltip(table: ErTableNodeVO, showComments: boolean) {
  const tableName = escapeHtml(safeText(table.tableName) || '-');
  const tableComment = showComments ? escapeHtml(safeText(table.tableComment)) : '';
  const columns = table.columns || [];
  const columnLines = columns.slice(0, 40).map((column) => {
    const columnName = escapeHtml(safeText(column.columnName) || '-');
    const dataType = escapeHtml(safeText(column.dataType) || '-');
    const marks = buildColumnMarks(column);
    const markText = marks ? ` <span style="color:#1d4ed8;">${escapeHtml(marks)}</span>` : '';
    const comment = showComments ? safeText(column.columnComment) : '';
    const commentText = comment ? ` <span style="color:#667892;">// ${escapeHtml(comment)}</span>` : '';
    return `${columnName}: ${dataType}${markText}${commentText}`;
  }).join('<br/>');
  const more = columns.length > 40 ? '<div style="margin-top:4px;color:#6b7f99;">...</div>' : '';
  return [
    `<div style="font-weight:700;color:#17375d;">${tableName}</div>`,
    tableComment ? `<div style="color:#5f7594;margin-top:2px;">${tableComment}</div>` : '',
    `<div style="margin-top:6px;line-height:1.5;color:#314a67;">${columnLines || '暂无字段'}</div>`,
    more,
  ].filter((item) => !!item).join('');
}

function buildGraphOption(graph: ErGraphVO | null, layoutMode: ErLayoutMode): echarts.EChartsOption | null {
  if (!graph || !graph.tables?.length) {
    emptyText.value = 'No ER graph data';
    nodePositionOverrides.clear();
    lastLayoutModeKey = '';
    lastTableSignature = '';
    return null;
  }
  resetNodePositionStateIfNeeded(graph, layoutMode);
  pruneNodePositionOverrides(graph);
  const fkRelations = graph.foreignKeyRelations || [];
  const aiRelations = graph.aiRelations || [];
  const tableNameSet = new Set(graph.tables.map((table) => table.tableName));
  const allRelations = [...fkRelations, ...aiRelations]
    .filter((relation) => tableNameSet.has(relation.sourceTable) && tableNameSet.has(relation.targetTable));
  const canvasSize = resolveCanvasSize();
  const nodePositions = buildNodePositions(graph.tables, allRelations, layoutMode, canvasSize.width, canvasSize.height);

  const tableRenderMeta = new Map<string, {
    table: ErTableNodeVO;
    center: NodePosition;
    height: number;
    columnIndexMap: Map<string, number>;
  }>();

  const nodeData = graph.tables.map((table, index) => {
    const fallbackPosition = nodePositions[table.tableName] || {
      x: 90 + (index % 4) * (NODE_WIDTH + NODE_HORIZONTAL_GAP),
      y: 90 + Math.floor(index / 4) * 150,
    };
    const tableKey = normalizeIdentifier(table.tableName);
    const position = nodePositionOverrides.get(tableKey) || fallbackPosition;
    if (!nodePositionOverrides.has(tableKey)) {
      nodePositionOverrides.set(tableKey, position);
    }
    const symbolHeight = nodeHeight(table);
    tableRenderMeta.set(normalizeIdentifier(table.tableName), {
      table,
      center: position,
      height: symbolHeight,
      columnIndexMap: buildTableColumnIndex(table),
    });
    return {
      id: table.tableName,
      name: table.tableName,
      value: buildNodeLabel(table, props.showComments),
      symbol: 'rect',
      symbolSize: [NODE_WIDTH, symbolHeight],
      category: 0,
      draggable: true,
      x: position.x,
      y: position.y,
      itemStyle: {
        color: '#f1f3f5',
        borderColor: '#3f86c2',
        borderWidth: 1.3,
        borderRadius: 10,
        shadowBlur: 0,
      },
      label: {
        show: true,
        formatter: () => buildNodeLabel(table, props.showComments),
        rich: {
          title: {
            width: NODE_WIDTH - 16,
            color: '#ffffff',
            fontSize: 12,
            fontWeight: 700,
            lineHeight: 24,
            align: 'center' as const,
            backgroundColor: '#3f86c2',
            padding: [0, 8, 0, 8],
            borderRadius: [8, 8, 0, 0],
          },
          subtitle: {
            width: NODE_WIDTH - 16,
            color: '#cfe4f6',
            fontSize: 10,
            align: 'center' as const,
            lineHeight: 20,
            backgroundColor: '#3f86c2',
            padding: [0, 8, 0, 8],
          },
          col: {
            width: NODE_WIDTH - 16,
            color: '#1e2e42',
            fontSize: 10,
            align: 'left' as const,
            lineHeight: NODE_ROW_HEIGHT,
            padding: [0, 8, 0, 8],
          },
          empty: {
            width: NODE_WIDTH - 16,
            color: '#607693',
            fontSize: 10,
            lineHeight: NODE_ROW_HEIGHT,
            padding: [0, 8, 0, 8],
          },
          ellipsis: {
            width: NODE_WIDTH - 16,
            color: '#607693',
            fontSize: 10,
            lineHeight: NODE_ROW_HEIGHT,
            padding: [0, 8, 0, 8],
          },
        },
      },
      table,
    };
  });

  const anchorNodes: Array<Record<string, unknown>> = [];

  const links: Array<Record<string, unknown>> = [];

  allRelations.forEach((relation, relationIndex) => {
    const isAi = (relation.relationType || '').toUpperCase() === 'AI_INFERRED';
    const reason = safeText(relation.reason);
    const direction = normalizeRelationDirection(relation.relationDirection);
    const arrowText = relationArrowText(direction);
    const aiConfidence = normalizeConfidence(relation.confidence);
    const aiLineColor = buildAiEdgeColor(aiConfidence);
    const aiEmphasisColor = buildAiEdgeColor(Math.min(1, aiConfidence + 0.16));
    const sourceMeta = tableRenderMeta.get(normalizeIdentifier(relation.sourceTable));
    const targetMeta = tableRenderMeta.get(normalizeIdentifier(relation.targetTable));
    const sourceAnchorId = `anchor-source-${relationIndex}`;
    const targetAnchorId = `anchor-target-${relationIndex}`;
    const createSegmentLink = (
      sourceId: string,
      targetId: string,
      symbol: [string, string],
      showLabel: boolean,
    ) => ({
      source: sourceId,
      target: targetId,
      value: 1,
      name: `${relation.sourceColumn || ''} ${arrowText} ${relation.targetColumn || ''}`,
      symbol,
      symbolSize: [7, 11],
      label: {
        show: showLabel && isAi,
        position: 'middle' as const,
        formatter: edgeLabel(relation),
        color: isAi ? '#845f14' : '#2f5fcc',
        fontSize: 10,
        backgroundColor: isAi ? 'rgba(255, 246, 223, 0.94)' : 'transparent',
        borderColor: isAi ? '#d3ad59' : 'transparent',
        borderWidth: isAi ? 1 : 0,
        borderRadius: isAi ? 4 : 0,
        padding: isAi ? [2, 4, 2, 4] : [0, 0, 0, 0],
      },
      lineStyle: {
        type: isAi ? ('dashed' as const) : ('solid' as const),
        width: isAi ? 1.6 : 2,
        color: isAi ? aiLineColor : '#3b82f6',
        curveness: 0,
      },
      emphasis: {
        lineStyle: {
          width: isAi ? 2.2 : 2.6,
          color: isAi ? aiEmphasisColor : '#2f6fe1',
        },
      },
      relation: {
        ...relation,
        reason,
        relationDirection: direction,
      },
    });

    if (sourceMeta && targetMeta) {
      const sourceTargetCenterDeltaX = Math.abs(sourceMeta.center.x - targetMeta.center.x);
      const useSameSideAnchor = sourceTargetCenterDeltaX < NODE_WIDTH * 0.72;
      const sameSideAnchorOnRight = sourceMeta.center.x <= canvasSize.width / 2;
      const sourceOnRight = useSameSideAnchor
        ? sameSideAnchorOnRight
        : (targetMeta.center.x >= sourceMeta.center.x);
      const targetOnRight = useSameSideAnchor
        ? sameSideAnchorOnRight
        : (sourceMeta.center.x > targetMeta.center.x);
      const sourceY = sourceMeta.center.y - sourceMeta.height / 2
        + resolveRelationAnchorOffsetY(sourceMeta.table, relation.sourceColumn || '', sourceMeta.columnIndexMap);
      const targetY = targetMeta.center.y - targetMeta.height / 2
        + resolveRelationAnchorOffsetY(targetMeta.table, relation.targetColumn || '', targetMeta.columnIndexMap);
      const sourceX = sourceMeta.center.x + (sourceOnRight ? NODE_WIDTH / 2 : -NODE_WIDTH / 2);
      const targetX = targetMeta.center.x + (targetOnRight ? NODE_WIDTH / 2 : -NODE_WIDTH / 2);
      anchorNodes.push({
        id: sourceAnchorId,
        name: sourceAnchorId,
        symbol: 'circle',
        symbolSize: 1,
        draggable: false,
        x: sourceX,
        y: sourceY,
        itemStyle: {
          color: 'rgba(0,0,0,0)',
          borderColor: 'rgba(0,0,0,0)',
        },
        label: {show: false},
        tooltip: {show: false},
      });
      anchorNodes.push({
        id: targetAnchorId,
        name: targetAnchorId,
        symbol: 'circle',
        symbolSize: 1,
        draggable: false,
        x: targetX,
        y: targetY,
        itemStyle: {
          color: 'rgba(0,0,0,0)',
          borderColor: 'rgba(0,0,0,0)',
        },
        label: {show: false},
        tooltip: {show: false},
      });
      const deltaY = Math.abs(sourceY - targetY);
      const straightSymbol = relationEdgeSymbol(direction);
      if (deltaY <= 0.5) {
        links.push(
          createSegmentLink(sourceAnchorId, targetAnchorId, straightSymbol, true),
        );
        return;
      }

      const bendStartAnchorId = `anchor-bend-start-${relationIndex}`;
      const bendEndAnchorId = `anchor-bend-end-${relationIndex}`;
      let laneX: number;
      if (sourceOnRight === targetOnRight) {
        laneX = sourceOnRight
          ? Math.max(sourceX, targetX) + ORTHOGONAL_LANE_GAP
          : Math.min(sourceX, targetX) - ORTHOGONAL_LANE_GAP;
      } else {
        laneX = (sourceX + targetX) / 2;
      }
      anchorNodes.push({
        id: bendStartAnchorId,
        name: bendStartAnchorId,
        symbol: 'circle',
        symbolSize: 1,
        draggable: false,
        x: laneX,
        y: sourceY,
        itemStyle: {
          color: 'rgba(0,0,0,0)',
          borderColor: 'rgba(0,0,0,0)',
        },
        label: {show: false},
        tooltip: {show: false},
      });
      anchorNodes.push({
        id: bendEndAnchorId,
        name: bendEndAnchorId,
        symbol: 'circle',
        symbolSize: 1,
        draggable: false,
        x: laneX,
        y: targetY,
        itemStyle: {
          color: 'rgba(0,0,0,0)',
          borderColor: 'rgba(0,0,0,0)',
        },
        label: {show: false},
        tooltip: {show: false},
      });
      const firstSegmentSymbol: [string, string] = direction === 'TARGET_TO_SOURCE' || direction === 'BIDIRECTIONAL'
        ? ['arrow', 'none']
        : ['none', 'none'];
      const secondSegmentSymbol: [string, string] = direction === 'SOURCE_TO_TARGET' || direction === 'BIDIRECTIONAL'
        ? ['none', 'arrow']
        : ['none', 'none'];
      links.push(
        createSegmentLink(sourceAnchorId, bendStartAnchorId, firstSegmentSymbol, false),
        createSegmentLink(bendStartAnchorId, bendEndAnchorId, ['none', 'none'], true),
        createSegmentLink(bendEndAnchorId, targetAnchorId, secondSegmentSymbol, false),
      );
      return;
    }

    links.push(
      createSegmentLink(
        relation.sourceTable,
        relation.targetTable,
        relationEdgeSymbol(direction),
        true,
      ),
    );
  });

  return {
    animation: false,
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: (params: any) => {
        if (params?.dataType === 'edge') {
          const relation = params?.data?.relation as ErRelationVO | undefined;
          if (!relation) {
            return params?.name || '';
          }
          const direction = normalizeRelationDirection(relation.relationDirection);
          const arrowText = relationArrowText(direction);
          const expression = `${relation.sourceTable}.${relation.sourceColumn} ${arrowText} ${relation.targetTable}.${relation.targetColumn}`;
          const confidenceText = edgeLabel(relation) || '-';
          const reason = safeText(relation.reason);
          return [
            `<strong>${escapeHtml(expression)}</strong>`,
            `方向：${relationDirectionText(direction)}`,
            `类型：${(relation.relationType || '').toUpperCase() === 'AI_INFERRED' ? 'AI 推断' : '外键'}`,
            (relation.relationType || '').toUpperCase() === 'AI_INFERRED' ? `置信度：${confidenceText}` : '',
            (relation.relationType || '').toUpperCase() === 'AI_INFERRED' ? `理由：${escapeHtml(reason || '模型未返回理由')}` : '',
          ].filter((item) => !!item).join('<br/>');
        }
        const table = params?.data?.table as ErTableNodeVO | undefined;
        if (!table?.tableName) {
          return '';
        }
        return buildNodeTooltip(table, props.showComments);
      },
    },
    series: [
      {
        type: 'graph',
        layout: 'none',
        roam: true,
        draggable: true,
        data: [...nodeData, ...anchorNodes],
        links,
        categories: [{name: 'Table'}],
        lineStyle: {
          curveness: 0,
        },
      },
    ],
  };
}

function ensureChart() {
  if (!chartRef.value) {
    return null;
  }
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value);
    graphNodeDragEndHandler = (params: any) => {
      if (params?.dataType !== 'node') {
        return;
      }
      const tableName = resolveDraggedTableName(params);
      if (!tableName) {
        return;
      }
      persistDraggedNodePosition(tableName, Number.NaN, Number.NaN);
      lastDragCommitAt = Date.now();
    };
    graphNodeMouseUpHandler = (params: any) => {
      if (params?.dataType !== 'node') {
        return;
      }
      const tableName = resolveDraggedTableName(params);
      if (!tableName) {
        return;
      }
      const scheduledAt = Date.now();
      if (dragMouseUpFallbackTimer != null) {
        window.clearTimeout(dragMouseUpFallbackTimer);
      }
      // 关键操作：dragend 未触发时由 mouseup 兜底重绘连线，并避免覆盖 dragend 的最终落点。
      dragMouseUpFallbackTimer = window.setTimeout(() => {
        if (lastDragCommitAt > scheduledAt) {
          return;
        }
        persistDraggedNodePosition(tableName, Number.NaN, Number.NaN);
      }, 48);
    };
    chartInstance.on('dragend', graphNodeDragEndHandler);
    chartInstance.on('mouseup', graphNodeMouseUpHandler);
  }
  return chartInstance;
}

function renderGraph() {
  const option = buildGraphOption(props.graph, props.layoutMode);
  optionReady.value = !!option;
  if (!option) {
    chartInstance?.clear();
    return;
  }
  const chart = ensureChart();
  if (!chart) {
    return;
  }
  chart.setOption(option, true);
  chart.resize();
}

function waitFrame() {
  return new Promise<void>((resolve) => {
    if (typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(() => resolve());
      return;
    }
    setTimeout(() => resolve(), 16);
  });
}

interface ExportPngOptions {
  pixelRatio?: number;
  backgroundColor?: string;
}

async function exportPngDataUrl(options?: ExportPngOptions) {
  await nextTick();
  renderGraph();
  await nextTick();
  await waitFrame();
  if (!chartInstance || !optionReady.value) {
    return '';
  }
  const pixelRatio = Number.isFinite(options?.pixelRatio)
    ? Math.max(1, Math.min(Number(options?.pixelRatio), 3))
    : 2;
  const backgroundColor = (options?.backgroundColor || '#f6f9ff').trim() || '#f6f9ff';
  return chartInstance.getDataURL({
    pixelRatio,
    backgroundColor,
    type: 'png',
  });
}

defineExpose({
  exportPngDataUrl,
});

watch(
  [() => props.graph, () => props.layoutMode, () => props.showComments],
  () => {
    nextTick(() => {
      renderGraph();
    });
  },
  {deep: true},
);

onMounted(() => {
  nextTick(() => {
    renderGraph();
    if (chartRef.value && typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(() => {
        renderGraph();
      });
      resizeObserver.observe(chartRef.value);
    }
  });
});

onBeforeUnmount(() => {
  if (resizeObserver && chartRef.value) {
    resizeObserver.unobserve(chartRef.value);
  }
  resizeObserver = null;
  if (dragMouseUpFallbackTimer != null) {
    window.clearTimeout(dragMouseUpFallbackTimer);
    dragMouseUpFallbackTimer = null;
  }
  if (chartInstance && graphNodeDragEndHandler) {
    chartInstance.off('dragend', graphNodeDragEndHandler);
  }
  if (chartInstance && graphNodeMouseUpHandler) {
    chartInstance.off('mouseup', graphNodeMouseUpHandler);
  }
  graphNodeDragEndHandler = null;
  graphNodeMouseUpHandler = null;
  nodePositionOverrides.clear();
  chartInstance?.dispose();
  chartInstance = null;
});
</script>

<style scoped>
.er-diagram-shell {
  width: 100%;
  height: 100%;
  min-height: 360px;
  border-radius: 10px;
  background-color: #f3f4f6;
  background-image:
    linear-gradient(#e2e5ea 1px, transparent 1px),
    linear-gradient(90deg, #e2e5ea 1px, transparent 1px);
  background-size: 20px 20px;
  position: relative;
  overflow: hidden;
}

.er-diagram-canvas {
  width: 100%;
  height: 100%;
  min-height: 360px;
}

.er-diagram-empty {
  position: absolute;
  inset: 0;
  min-height: inherit;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--surface-0, #fff);
  color: var(--text-2, #5f6f87);
  font-size: 12px;
}
</style>
