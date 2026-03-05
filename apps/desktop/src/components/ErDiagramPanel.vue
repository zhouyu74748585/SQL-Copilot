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

interface RelationRouteChangePayload {
  relationKey: string;
  routeManual: boolean;
  routeLaneX: number;
}

const props = withDefaults(defineProps<{
  graph: ErGraphVO | null;
  layoutMode?: ErLayoutMode;
  showComments?: boolean;
}>(), {
  layoutMode: 'GRID',
  showComments: false,
});

const emit = defineEmits<{
  (e: 'relation-route-change', payload: RelationRouteChangePayload): void;
}>();

const chartRef = ref<HTMLElement | null>(null);
let chartInstance: echarts.ECharts | null = null;
let resizeObserver: ResizeObserver | null = null;
const optionReady = ref(false);
const emptyText = ref('No ER graph data');

const nodePositionOverrides = new Map<string, NodePosition>();
const relationRouteOverrideMap = new Map<string, number>();

let graphClickHandler: ((params: any) => void) | null = null;
let graphMouseMoveHandler: ((params: any) => void) | null = null;
let graphGlobalOutHandler: (() => void) | null = null;
let graphNodeDragMoveHandler: ((params: any) => void) | null = null;
let graphNodeDragEndHandler: ((params: any) => void) | null = null;
let graphNodeMouseUpHandler: ((params: any) => void) | null = null;
let zrClickHandler: ((params: any) => void) | null = null;

let dragMouseUpFallbackTimer: number | null = null;
let renderRequestToken: number | null = null;
let lastLayoutModeKey = '';
let lastTableSignature = '';

let isNodeDragging = false;
let draggingTableKey = '';
let isRouteHandleDragging = false;
let activeRelationKey = '';
let editingRelationKey = '';
let hoveredRelationKey = '';

type NodePosition = { x: number; y: number };
type RelationDirection = 'SOURCE_TO_TARGET' | 'TARGET_TO_SOURCE' | 'BIDIRECTIONAL';

type RelationGeometry = {
  relation: ErRelationVO;
  relationKey: string;
  sourceX: number;
  sourceY: number;
  targetX: number;
  targetY: number;
  laneX: number;
  direction: RelationDirection;
};

const NODE_WIDTH = 224;
const NODE_HEADER_HEIGHT = 44;
const NODE_MIN_BODY_ROWS = 1;
const NODE_MAX_COLUMNS = 12;
const NODE_ROW_HEIGHT = 15;
const NODE_COMMENT_PREVIEW_MAX = 14;
const NODE_HORIZONTAL_GAP = 14;
const NODE_VERTICAL_GAP = 16;
const NODE_BODY_TOP_PADDING = 7;
const NODE_BODY_BOTTOM_PADDING = 7;
const ORTHOGONAL_LANE_GAP = 22;

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

function safeText(text: string | null | undefined) {
  return (text || '').trim();
}

function normalizeIdentifier(text: string | null | undefined) {
  return safeText(text).toLowerCase();
}

function normalizeRelationType(rawType?: string) {
  return safeText(rawType).toUpperCase() || 'FK';
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

function buildRelationKey(relation: ErRelationVO) {
  return [
    normalizeRelationType(relation.relationType),
    normalizeIdentifier(relation.sourceTable),
    normalizeIdentifier(relation.sourceColumn),
    normalizeIdentifier(relation.targetTable),
    normalizeIdentifier(relation.targetColumn),
    normalizeRelationDirection(relation.relationDirection),
  ].join('|');
}

function normalizeLayoutMode(mode?: ErLayoutMode): ErLayoutMode {
  if (mode === 'CIRCLE' || mode === 'HIERARCHICAL') {
    return mode;
  }
  return 'GRID';
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

function normalizeConfidence(rawConfidence?: number) {
  const value = Number(rawConfidence ?? 0);
  if (!Number.isFinite(value)) {
    return 0;
  }
  return Math.max(0, Math.min(1, value));
}

function buildAiEdgeColor(confidence?: number) {
  const t = normalizeConfidence(confidence);
  const low = {r: 204, g: 214, b: 231};
  const high = {r: 27, g: 56, b: 122};
  const r = Math.round(low.r + (high.r - low.r) * t);
  const g = Math.round(low.g + (high.g - low.g) * t);
  const b = Math.round(low.b + (high.b - low.b) * t);
  return `rgb(${r}, ${g}, ${b})`;
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
  return marks ? `${columnName} ${marks}` : columnName;
}

function buildColumnPreviewComment(column: ErColumnNodeVO, showComments: boolean) {
  if (!showComments) {
    return '';
  }
  const comment = truncateText(column.columnComment || '', NODE_COMMENT_PREVIEW_MAX);
  return comment ? ` // ${comment}` : '';
}

function buildColumnLine(column: ErColumnNodeVO, showComments: boolean) {
  const name = truncateText(buildColumnDisplayName(column), 22);
  const dataType = truncateText(safeText(column.dataType) || '-', 16);
  const comment = buildColumnPreviewComment(column, showComments);
  return truncateText(`${name}: ${dataType}${comment}`, 50);
}

function nodeHeight(table: ErTableNodeVO) {
  const rowCount = Math.max(NODE_MIN_BODY_ROWS, Math.min(NODE_MAX_COLUMNS, table.columns?.length || 0));
  const moreRow = (table.columns?.length || 0) > NODE_MAX_COLUMNS ? 1 : 0;
  return NODE_HEADER_HEIGHT + NODE_BODY_TOP_PADDING + (rowCount + moreRow) * NODE_ROW_HEIGHT + NODE_BODY_BOTTOM_PADDING;
}

function buildNodeLabel(table: ErTableNodeVO, showComments: boolean) {
  const tableName = truncateText(table.tableName || '', 28);
  const tableComment = showComments ? (truncateText(table.tableComment || '', 20) || ' ') : ' ';
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
  if (normalizeRelationType(relation.relationType) !== 'AI_INFERRED') {
    return '';
  }
  const confidence = normalizeConfidence(relation.confidence);
  return `${Math.round(confidence * 100)}%`;
}

function buildNodeTooltip(table: ErTableNodeVO, showComments: boolean) {
  const tableName = escapeHtml(safeText(table.tableName) || '-');
  const tableComment = showComments ? escapeHtml(safeText(table.tableComment)) : '';
  const columns = table.columns || [];
  const columnLines = columns.slice(0, 40).map((column) => {
    const columnName = escapeHtml(safeText(column.columnName) || '-');
    const dataType = escapeHtml(safeText(column.dataType) || '-');
    const marks = buildColumnMarks(column);
    const markText = marks ? ` <span style="color:#0f62c6;">${escapeHtml(marks)}</span>` : '';
    const comment = showComments ? safeText(column.columnComment) : '';
    const commentText = comment ? ` <span style="color:#51617a;">// ${escapeHtml(comment)}</span>` : '';
    return `${columnName}: ${dataType}${markText}${commentText}`;
  }).join('<br/>');
  const more = columns.length > 40 ? '<div style="margin-top:4px;color:#556780;">...</div>' : '';
  return [
    `<div style="font-weight:700;color:#0b2f5a;">${tableName}</div>`,
    tableComment ? `<div style="color:#4b617f;margin-top:2px;">${tableComment}</div>` : '',
    `<div style="margin-top:6px;line-height:1.5;color:#1f3554;">${columnLines || '暂无字段'}</div>`,
    more,
  ].filter((item) => !!item).join('');
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
  const rowIndex = rawIndex < NODE_MAX_COLUMNS ? rawIndex : (hasEllipsis ? NODE_MAX_COLUMNS : (NODE_MAX_COLUMNS - 1));
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
    const rowMaxHeight = Math.max(...rowTables.map((table) => nodeHeight(table)));
    const rowWidth = rowTables.length * NODE_WIDTH + (rowTables.length - 1) * NODE_HORIZONTAL_GAP;
    const startX = Math.max(paddingLeft, (canvasWidth - rowWidth) / 2);
    rowTables.forEach((table, col) => {
      positions[table.tableName] = {
        x: startX + col * (NODE_WIDTH + NODE_HORIZONTAL_GAP) + NODE_WIDTH / 2,
        y: currentY + rowMaxHeight / 2,
      };
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
  const radius = Math.max(96, Math.min(canvasWidth, canvasHeight) / 2 - Math.max(NODE_WIDTH, maxNodeHeight) / 2 - 12);
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

  const queue = tableNames.filter((name) => (indegree.get(name) || 0) === 0);
  const levels = new Map<string, number>();
  queue.forEach((name) => levels.set(name, 0));

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
  relationRouteOverrideMap.clear();
  activeRelationKey = '';
  editingRelationKey = '';
  hoveredRelationKey = '';
  lastLayoutModeKey = nextLayoutModeKey;
  lastTableSignature = nextTableSignature;
}

function getGraphAllRelations(graph: ErGraphVO | null) {
  if (!graph) {
    return [] as ErRelationVO[];
  }
  return [...(graph.foreignKeyRelations || []), ...(graph.aiRelations || [])];
}

function syncRouteOverridesFromGraph(relations: ErRelationVO[]) {
  const validKeys = new Set<string>();
  relations.forEach((relation) => {
    const relationKey = buildRelationKey(relation);
    validKeys.add(relationKey);
    const hasLocalOverride = relationRouteOverrideMap.has(relationKey);
    const routeLaneX = Number(relation.routeLaneX);
    if (!hasLocalOverride && relation.routeManual === true && Number.isFinite(routeLaneX)) {
      relationRouteOverrideMap.set(relationKey, routeLaneX);
    }
  });
  Array.from(relationRouteOverrideMap.keys()).forEach((key) => {
    if (!validKeys.has(key)) {
      relationRouteOverrideMap.delete(key);
    }
  });
  if (activeRelationKey && !validKeys.has(activeRelationKey)) {
    activeRelationKey = '';
  }
  if (editingRelationKey && !validKeys.has(editingRelationKey)) {
    editingRelationKey = '';
  }
  if (hoveredRelationKey && !validKeys.has(hoveredRelationKey)) {
    hoveredRelationKey = '';
  }
}

function resolveRelationLaneX(relation: ErRelationVO, relationKey: string, autoLaneX: number) {
  const localLaneX = Number(relationRouteOverrideMap.get(relationKey));
  if (Number.isFinite(localLaneX)) {
    return localLaneX;
  }
  const routeLaneX = Number(relation.routeLaneX);
  if (relation.routeManual === true && Number.isFinite(routeLaneX)) {
    return routeLaneX;
  }
  return autoLaneX;
}

function saveNodePositionOverride(tableName: string, x: number, y: number) {
  if (!tableName || !Number.isFinite(x) || !Number.isFinite(y)) {
    return;
  }
  nodePositionOverrides.set(normalizeIdentifier(tableName), {x, y});
}

function resolveNodePositionFromChart(nodeIdOrName: string): NodePosition | null {
  if (!chartInstance || !nodeIdOrName) {
    return null;
  }
  const key = normalizeIdentifier(nodeIdOrName);
  try {
    const ecModel = (chartInstance as any).getModel?.();
    const seriesModel = ecModel?.getSeriesByIndex?.(0);
    const seriesData = seriesModel?.getData?.();
    if (seriesData) {
      let matchedIndex = -1;
      if (typeof seriesData.indexOfName === 'function') {
        matchedIndex = Number(seriesData.indexOfName(nodeIdOrName));
      }
      if (!Number.isInteger(matchedIndex) || matchedIndex < 0) {
        const count = typeof seriesData.count === 'function' ? Number(seriesData.count()) : 0;
        for (let i = 0; i < count; i += 1) {
          const name = normalizeIdentifier(seriesData.getName?.(i));
          const id = normalizeIdentifier(seriesData.getId?.(i));
          if (name === key || id === key) {
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
    // 忽略读取异常，回退 option 查询。
  }

  const option = chartInstance.getOption() as any;
  const seriesList = Array.isArray(option?.series) ? option.series : [option?.series];
  for (const series of seriesList) {
    const data = Array.isArray(series?.data) ? series.data : [];
    const matched = data.find((item: any) => {
      const idKey = normalizeIdentifier(item?.id ?? item?.name);
      return idKey === key;
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

function scheduleRenderGraph() {
  if (renderRequestToken != null) {
    return;
  }
  const scheduler = typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function'
    ? window.requestAnimationFrame.bind(window)
    : ((cb: FrameRequestCallback) => window.setTimeout(() => cb(performance.now()), 16));
  renderRequestToken = scheduler(() => {
    renderRequestToken = null;
    renderGraph();
  }) as unknown as number;
}

function relationExpression(relation: ErRelationVO, direction: RelationDirection) {
  const arrow = relationArrowText(direction);
  return `${relation.sourceTable}.${relation.sourceColumn} ${arrow} ${relation.targetTable}.${relation.targetColumn}`;
}

function buildGraphOption(graph: ErGraphVO | null, layoutMode: ErLayoutMode): echarts.EChartsOption | null {
  if (!graph || !graph.tables?.length) {
    emptyText.value = 'No ER graph data';
    nodePositionOverrides.clear();
    relationRouteOverrideMap.clear();
    activeRelationKey = '';
    editingRelationKey = '';
    hoveredRelationKey = '';
    lastLayoutModeKey = '';
    lastTableSignature = '';
    return null;
  }

  resetNodePositionStateIfNeeded(graph, layoutMode);
  pruneNodePositionOverrides(graph);

  const tableNameSet = new Set(graph.tables.map((table) => table.tableName));
  const allRelations = getGraphAllRelations(graph)
    .filter((relation) => tableNameSet.has(relation.sourceTable) && tableNameSet.has(relation.targetTable));
  syncRouteOverridesFromGraph(allRelations);

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
    tableRenderMeta.set(tableKey, {
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
        color: '#f8fbff',
        borderColor: '#0a4a87',
        borderWidth: 1.4,
        borderRadius: 10,
        shadowColor: 'rgba(7, 39, 76, 0.18)',
        shadowBlur: 8,
      },
      emphasis: {
        itemStyle: {
          borderColor: '#0f62c6',
          borderWidth: 1.8,
          shadowColor: 'rgba(9, 54, 112, 0.28)',
          shadowBlur: 14,
        },
      },
      label: {
        show: true,
        formatter: () => buildNodeLabel(table, props.showComments),
        rich: {
          title: {
            width: NODE_WIDTH - 14,
            color: '#ffffff',
            fontSize: 12,
            fontWeight: 700,
            lineHeight: 24,
            align: 'center' as const,
            backgroundColor: '#0a4a87',
            padding: [0, 7, 0, 7],
            borderRadius: [8, 8, 0, 0],
          },
          subtitle: {
            width: NODE_WIDTH - 14,
            color: '#b7d3f5',
            fontSize: 10,
            align: 'center' as const,
            lineHeight: 18,
            backgroundColor: '#0a3f72',
            padding: [0, 7, 0, 7],
          },
          col: {
            width: NODE_WIDTH - 14,
            color: '#1a2f4f',
            fontSize: 10,
            align: 'left' as const,
            lineHeight: NODE_ROW_HEIGHT,
            padding: [0, 7, 0, 7],
            backgroundColor: '#f8fbff',
          },
          empty: {
            width: NODE_WIDTH - 14,
            color: '#536885',
            fontSize: 10,
            lineHeight: NODE_ROW_HEIGHT,
            padding: [0, 7, 0, 7],
            backgroundColor: '#f8fbff',
          },
          ellipsis: {
            width: NODE_WIDTH - 14,
            color: '#536885',
            fontSize: 10,
            lineHeight: NODE_ROW_HEIGHT,
            padding: [0, 7, 0, 7],
            backgroundColor: '#f1f6fd',
          },
        },
      },
      table,
    };
  });

  const anchorNodes: Array<Record<string, unknown>> = [];
  const links: Array<Record<string, unknown>> = [];
  const relationGeometries: RelationGeometry[] = [];

  allRelations.forEach((relation, relationIndex) => {
    const sourceMeta = tableRenderMeta.get(normalizeIdentifier(relation.sourceTable));
    const targetMeta = tableRenderMeta.get(normalizeIdentifier(relation.targetTable));
    if (!sourceMeta || !targetMeta) {
      return;
    }
    const relationKey = buildRelationKey(relation);
    const direction = normalizeRelationDirection(relation.relationDirection);

    const sourceTargetCenterDeltaX = Math.abs(sourceMeta.center.x - targetMeta.center.x);
    const useSameSideAnchor = sourceTargetCenterDeltaX < NODE_WIDTH * 0.72;
    const sameSideAnchorOnRight = sourceMeta.center.x <= canvasSize.width / 2;
    const sourceOnRight = useSameSideAnchor ? sameSideAnchorOnRight : (targetMeta.center.x >= sourceMeta.center.x);
    const targetOnRight = useSameSideAnchor ? sameSideAnchorOnRight : (sourceMeta.center.x > targetMeta.center.x);

    const sourceY = sourceMeta.center.y - sourceMeta.height / 2
      + resolveRelationAnchorOffsetY(sourceMeta.table, relation.sourceColumn || '', sourceMeta.columnIndexMap);
    const targetY = targetMeta.center.y - targetMeta.height / 2
      + resolveRelationAnchorOffsetY(targetMeta.table, relation.targetColumn || '', targetMeta.columnIndexMap);

    const sourceX = sourceMeta.center.x + (sourceOnRight ? NODE_WIDTH / 2 : -NODE_WIDTH / 2);
    const targetX = targetMeta.center.x + (targetOnRight ? NODE_WIDTH / 2 : -NODE_WIDTH / 2);

    const autoLaneX = sourceOnRight === targetOnRight
      ? (sourceOnRight ? Math.max(sourceX, targetX) + ORTHOGONAL_LANE_GAP : Math.min(sourceX, targetX) - ORTHOGONAL_LANE_GAP)
      : ((sourceX + targetX) / 2);
    const laneX = resolveRelationLaneX(relation, relationKey, autoLaneX);

    relationGeometries.push({
      relation,
      relationKey,
      sourceX,
      sourceY,
      targetX,
      targetY,
      laneX,
      direction,
    });

    const sourceAnchorId = `anchor-source-${relationIndex}`;
    const targetAnchorId = `anchor-target-${relationIndex}`;
    const bendStartAnchorId = `anchor-bend-start-${relationIndex}`;
    const bendEndAnchorId = `anchor-bend-end-${relationIndex}`;

    anchorNodes.push(
      {
        id: sourceAnchorId,
        name: sourceAnchorId,
        symbol: 'circle',
        symbolSize: 1,
        draggable: false,
        x: sourceX,
        y: sourceY,
        itemStyle: {color: 'rgba(0,0,0,0)', borderColor: 'rgba(0,0,0,0)'},
        label: {show: false},
        tooltip: {show: false},
        anchorType: 'REL_ANCHOR',
      },
      {
        id: targetAnchorId,
        name: targetAnchorId,
        symbol: 'circle',
        symbolSize: 1,
        draggable: false,
        x: targetX,
        y: targetY,
        itemStyle: {color: 'rgba(0,0,0,0)', borderColor: 'rgba(0,0,0,0)'},
        label: {show: false},
        tooltip: {show: false},
        anchorType: 'REL_ANCHOR',
      },
      {
        id: bendStartAnchorId,
        name: bendStartAnchorId,
        symbol: 'circle',
        symbolSize: 1,
        draggable: false,
        x: laneX,
        y: sourceY,
        itemStyle: {color: 'rgba(0,0,0,0)', borderColor: 'rgba(0,0,0,0)'},
        label: {show: false},
        tooltip: {show: false},
        anchorType: 'REL_ANCHOR',
      },
      {
        id: bendEndAnchorId,
        name: bendEndAnchorId,
        symbol: 'circle',
        symbolSize: 1,
        draggable: false,
        x: laneX,
        y: targetY,
        itemStyle: {color: 'rgba(0,0,0,0)', borderColor: 'rgba(0,0,0,0)'},
        label: {show: false},
        tooltip: {show: false},
        anchorType: 'REL_ANCHOR',
      },
    );

    const isAi = normalizeRelationType(relation.relationType) === 'AI_INFERRED';
    const aiConfidence = normalizeConfidence(relation.confidence);
    const baseColor = isAi ? buildAiEdgeColor(aiConfidence) : '#2164d4';
    const hoverColor = isAi ? buildAiEdgeColor(Math.min(1, aiConfidence + 0.2)) : '#124eb6';
    const confidenceLabel = edgeLabel(relation);

    const createSegmentLink = (
      sourceId: string,
      targetId: string,
      symbol: [string, string],
      showLabel: boolean,
      edgePart: string,
    ) => {
      const relationHovered = relationKey === hoveredRelationKey;
      return ({
      source: sourceId,
      target: targetId,
      name: `${relation.sourceColumn || ''} ${relationArrowText(direction)} ${relation.targetColumn || ''}`,
      symbol,
      symbolSize: relationHovered ? [10, 14] : [8, 12],
      relation,
      relationKey,
      relationDirection: direction,
      edgePart,
      lineStyle: {
        type: isAi ? ('dashed' as const) : ('solid' as const),
        width: relationHovered ? (isAi ? 3.2 : 3.4) : (isAi ? 1.9 : 2.1),
        color: relationHovered ? hoverColor : baseColor,
        shadowColor: relationHovered
          ? (isAi ? 'rgba(25, 67, 125, 0.36)' : 'rgba(15, 73, 163, 0.36)')
          : 'transparent',
        shadowBlur: relationHovered ? 12 : 0,
        opacity: relationHovered ? 1 : 0.96,
        curveness: 0,
      },
      label: {
        show: showLabel && isAi,
        position: 'middle' as const,
        formatter: confidenceLabel,
        color: '#09396d',
        fontSize: 10,
        fontWeight: 700,
        backgroundColor: 'rgba(241, 247, 255, 0.92)',
        borderColor: '#8db5ea',
        borderWidth: 1,
        borderRadius: 4,
        padding: [2, 4, 2, 4],
      },
      emphasis: {
        lineStyle: {
          width: isAi ? 3.2 : 3.4,
          color: hoverColor,
          shadowColor: isAi ? 'rgba(25, 67, 125, 0.36)' : 'rgba(15, 73, 163, 0.36)',
          shadowBlur: 12,
          opacity: 1,
        },
      },
    });
    };

    const firstSegmentSymbol: [string, string] = direction === 'TARGET_TO_SOURCE' || direction === 'BIDIRECTIONAL'
      ? ['arrow', 'none']
      : ['none', 'none'];
    const secondSegmentSymbol: [string, string] = direction === 'SOURCE_TO_TARGET' || direction === 'BIDIRECTIONAL'
      ? ['none', 'arrow']
      : ['none', 'none'];

    links.push(
      createSegmentLink(sourceAnchorId, bendStartAnchorId, firstSegmentSymbol, false, 'source'),
      createSegmentLink(bendStartAnchorId, bendEndAnchorId, ['none', 'none'], true, 'middle'),
      createSegmentLink(bendEndAnchorId, targetAnchorId, secondSegmentSymbol, false, 'target'),
    );
  });

  relationGeometries.forEach((item, index) => {
    if (item.relationKey !== activeRelationKey && item.relationKey !== editingRelationKey) {
      return;
    }
    const handleId = `route-handle-${index}`;
    const handleY = (item.sourceY + item.targetY) / 2;
    anchorNodes.push({
      id: handleId,
      name: handleId,
      symbol: 'diamond',
      symbolSize: 14,
      draggable: true,
      x: item.laneX,
      y: handleY,
      itemStyle: {
        color: '#ffe2a8',
        borderColor: '#8a5d00',
        borderWidth: 1.4,
        shadowColor: 'rgba(138, 93, 0, 0.35)',
        shadowBlur: 8,
      },
      emphasis: {
        itemStyle: {
          color: '#ffd37a',
          borderColor: '#6e4500',
          borderWidth: 1.8,
          shadowColor: 'rgba(110, 69, 0, 0.42)',
          shadowBlur: 12,
        },
      },
      label: {show: false},
      tooltip: {show: false},
      controlType: 'RELATION_HANDLE',
      relationKey: item.relationKey,
    });
  });

  return {
    animation: false,
    tooltip: {
      trigger: 'item',
      triggerOn: 'mousemove',
      confine: true,
      formatter: (params: any) => {
        if (params?.dataType === 'edge') {
          const relation = params?.data?.relation as ErRelationVO | undefined;
          if (!relation) {
            return params?.name || '';
          }
          const direction = normalizeRelationDirection(relation.relationDirection);
          const expression = relationExpression(relation, direction);
          const confidenceText = edgeLabel(relation) || '-';
          const reason = safeText(relation.reason);
          return [
            `<div style="font-weight:700;color:#0a3f72;">${escapeHtml(expression)}</div>`,
            `<div style="margin-top:4px;color:#37577d;">方向：${relationDirectionText(direction)}</div>`,
            `<div style="color:#37577d;">类型：${normalizeRelationType(relation.relationType) === 'AI_INFERRED' ? 'AI 推断' : '外键'}</div>`,
            normalizeRelationType(relation.relationType) === 'AI_INFERRED' ? `<div style="color:#37577d;">置信度：${confidenceText}</div>` : '',
            normalizeRelationType(relation.relationType) === 'AI_INFERRED'
              ? `<div style="color:#5f4a1c;max-width:380px;">理由：${escapeHtml(reason || '模型未返回理由')}</div>`
              : '',
          ].filter((item) => !!item).join('');
        }
        if (params?.data?.controlType === 'RELATION_HANDLE' || params?.data?.anchorType === 'REL_ANCHOR') {
          return '';
        }
        const table = params?.data?.table as ErTableNodeVO | undefined;
        if (!table?.tableName || isNodeDragging) {
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

function commitTablePosition(tableName: string) {
  const position = resolveNodePositionFromChart(tableName);
  if (!position) {
    return;
  }
  saveNodePositionOverride(tableName, position.x, position.y);
}

function commitRouteHandlePosition(handleId: string, relationKey: string) {
  const position = resolveNodePositionFromChart(handleId);
  if (!position || !Number.isFinite(position.x)) {
    return;
  }
  relationRouteOverrideMap.set(relationKey, position.x);
  emit('relation-route-change', {
    relationKey,
    routeManual: true,
    routeLaneX: position.x,
  });
}

function ensureChart() {
  if (!chartRef.value) {
    return null;
  }
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value);

    graphClickHandler = (params: any) => {
      if (params?.dataType !== 'edge') {
        return;
      }
      const relationKey = safeText(params?.data?.relationKey);
      if (!relationKey || relationKey === activeRelationKey) {
        return;
      }
      activeRelationKey = relationKey;
      editingRelationKey = '';
      scheduleRenderGraph();
    };

    graphMouseMoveHandler = (params: any) => {
      const relationKey = params?.dataType === 'edge' ? safeText(params?.data?.relationKey) : '';
      if (relationKey === hoveredRelationKey) {
        return;
      }
      hoveredRelationKey = relationKey;
      scheduleRenderGraph();
    };

    graphGlobalOutHandler = () => {
      if (!hoveredRelationKey) {
        return;
      }
      hoveredRelationKey = '';
      scheduleRenderGraph();
    };

    graphNodeDragMoveHandler = (params: any) => {
      if (params?.dataType !== 'node') {
        return;
      }

      if (params?.data?.controlType === 'RELATION_HANDLE') {
        const relationKey = safeText(params?.data?.relationKey);
        const handleId = safeText(params?.data?.id ?? params?.data?.name ?? params?.name);
        if (!relationKey || !handleId) {
          return;
        }
        isRouteHandleDragging = true;
        editingRelationKey = relationKey;
        activeRelationKey = relationKey;
        chartInstance?.dispatchAction({type: 'hideTip'});
        commitRouteHandlePosition(handleId, relationKey);
        scheduleRenderGraph();
        return;
      }

      const tableName = resolveDraggedTableName(params);
      if (!tableName) {
        return;
      }
      isNodeDragging = true;
      draggingTableKey = normalizeIdentifier(tableName);
      chartInstance?.dispatchAction({type: 'hideTip'});
      commitTablePosition(tableName);
      scheduleRenderGraph();
    };

    graphNodeDragEndHandler = (params: any) => {
      if (params?.dataType !== 'node') {
        return;
      }

      if (params?.data?.controlType === 'RELATION_HANDLE') {
        const relationKey = safeText(params?.data?.relationKey);
        const handleId = safeText(params?.data?.id ?? params?.data?.name ?? params?.name);
        if (relationKey && handleId) {
          commitRouteHandlePosition(handleId, relationKey);
        }
        isRouteHandleDragging = false;
        editingRelationKey = '';
        scheduleRenderGraph();
        return;
      }

      const tableName = resolveDraggedTableName(params);
      if (!tableName) {
        return;
      }
      commitTablePosition(tableName);
      isNodeDragging = false;
      draggingTableKey = '';
      scheduleRenderGraph();
    };

    graphNodeMouseUpHandler = (params: any) => {
      if (params?.dataType !== 'node') {
        return;
      }
      if (dragMouseUpFallbackTimer != null) {
        window.clearTimeout(dragMouseUpFallbackTimer);
      }
      dragMouseUpFallbackTimer = window.setTimeout(() => {
        if (params?.data?.controlType === 'RELATION_HANDLE') {
          const relationKey = safeText(params?.data?.relationKey);
          const handleId = safeText(params?.data?.id ?? params?.data?.name ?? params?.name);
          if (relationKey && handleId) {
            commitRouteHandlePosition(handleId, relationKey);
            isRouteHandleDragging = false;
            editingRelationKey = '';
            scheduleRenderGraph();
          }
          return;
        }

        const tableName = resolveDraggedTableName(params);
        if (!tableName) {
          return;
        }
        const tableKey = normalizeIdentifier(tableName);
        if (!isNodeDragging && draggingTableKey !== tableKey) {
          return;
        }
        commitTablePosition(tableName);
        isNodeDragging = false;
        draggingTableKey = '';
        scheduleRenderGraph();
      }, 44);
    };

    zrClickHandler = (params: any) => {
      if (params?.target) {
        return;
      }
      if (!activeRelationKey) {
        return;
      }
      activeRelationKey = '';
      editingRelationKey = '';
      scheduleRenderGraph();
    };

    chartInstance.on('click', graphClickHandler);
    chartInstance.on('mousemove', graphMouseMoveHandler);
    chartInstance.on('globalout', graphGlobalOutHandler);
    chartInstance.on('drag', graphNodeDragMoveHandler);
    chartInstance.on('dragend', graphNodeDragEndHandler);
    chartInstance.on('mouseup', graphNodeMouseUpHandler);
    chartInstance.getZr().on('click', zrClickHandler);
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
  chart.setOption(option, {
    notMerge: true,
    lazyUpdate: true,
    replaceMerge: ['series'],
    silent: false,
  });
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
  if (renderRequestToken != null && typeof window !== 'undefined' && typeof window.cancelAnimationFrame === 'function') {
    window.cancelAnimationFrame(renderRequestToken);
    renderRequestToken = null;
  }

  isNodeDragging = false;
  draggingTableKey = '';
  isRouteHandleDragging = false;
  activeRelationKey = '';
  editingRelationKey = '';
  hoveredRelationKey = '';

  if (chartInstance && graphClickHandler) {
    chartInstance.off('click', graphClickHandler);
  }
  if (chartInstance && graphMouseMoveHandler) {
    chartInstance.off('mousemove', graphMouseMoveHandler);
  }
  if (chartInstance && graphGlobalOutHandler) {
    chartInstance.off('globalout', graphGlobalOutHandler);
  }
  if (chartInstance && graphNodeDragMoveHandler) {
    chartInstance.off('drag', graphNodeDragMoveHandler);
  }
  if (chartInstance && graphNodeDragEndHandler) {
    chartInstance.off('dragend', graphNodeDragEndHandler);
  }
  if (chartInstance && graphNodeMouseUpHandler) {
    chartInstance.off('mouseup', graphNodeMouseUpHandler);
  }
  if (chartInstance && zrClickHandler) {
    chartInstance.getZr().off('click', zrClickHandler);
  }

  graphClickHandler = null;
  graphMouseMoveHandler = null;
  graphGlobalOutHandler = null;
  graphNodeDragMoveHandler = null;
  graphNodeDragEndHandler = null;
  graphNodeMouseUpHandler = null;
  zrClickHandler = null;

  nodePositionOverrides.clear();
  relationRouteOverrideMap.clear();
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
  background-color: #f3f5f8;
  background-image:
    linear-gradient(#d9e1eb 1px, transparent 1px),
    linear-gradient(90deg, #d9e1eb 1px, transparent 1px);
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
