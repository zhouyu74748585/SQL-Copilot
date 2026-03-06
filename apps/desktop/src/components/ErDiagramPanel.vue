<template>
  <div
    ref="viewportRef"
    class="er-diagram-shell"
    @wheel.prevent="onViewportWheel"
    @mousedown="onViewportMouseDown"
    @mousemove="onViewportMouseMove"
    @mouseleave="onViewportMouseLeave"
  >
    <div class="er-grid-layer" :style="gridStyle" />

    <svg class="er-svg-layer" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <marker
          id="er-arrow"
          markerWidth="6"
          markerHeight="6"
          refX="5.4"
          refY="3"
          orient="auto"
          markerUnits="userSpaceOnUse"
        >
          <path d="M 0 0 L 6 3 L 0 6 z" fill="currentColor" />
        </marker>
      </defs>

      <g :transform="svgWorldTransform">
        <g
          v-for="relation in relationViews"
          :key="relation.relationKey"
          class="er-relation-group"
          :style="{color: relation.strokeColor}"
          :class="{
            'is-hovered': relation.isHovered,
            'is-active': relation.isActive,
            'is-ai': relation.isAi,
          }"
        >
          <polyline
            class="er-relation-hit"
            :points="relation.pointsText"
            fill="none"
            stroke="transparent"
            stroke-width="16"
            data-er-interactive="true"
            @mousedown.stop.prevent="onRelationMouseDown(relation, $event)"
            @mouseenter="onRelationMouseEnter(relation, $event)"
            @mousemove="onRelationMouseMove(relation, $event)"
            @mouseleave="onRelationMouseLeave"
            @click.stop="onRelationClick(relation, $event)"
          />
          <polyline
            class="er-relation-line"
            :points="relation.pointsText"
            fill="none"
            :stroke="relation.strokeColor"
            :stroke-width="relation.strokeWidth"
            :stroke-dasharray="relation.dashArray"
            :marker-start="relation.markerStart"
            :marker-end="relation.markerEnd"
            vector-effect="non-scaling-stroke"
            data-er-interactive="true"
            @mousedown.stop.prevent="onRelationMouseDown(relation, $event)"
            @mouseenter="onRelationMouseEnter(relation, $event)"
            @mousemove="onRelationMouseMove(relation, $event)"
            @mouseleave="onRelationMouseLeave"
            @click.stop="onRelationClick(relation, $event)"
          />
          <text
            v-if="relation.confidenceText"
            class="er-relation-confidence"
            :x="relation.confidenceX"
            :y="relation.confidenceY"
            text-anchor="start"
            dominant-baseline="middle"
          >
            {{ relation.confidenceText }}
          </text>
        </g>

        <g v-if="activeRelationHandle" class="er-route-handle-group">
          <circle
            class="er-route-handle er-route-handle-endpoint"
            :cx="activeRelationHandle.source.x"
            :cy="activeRelationHandle.source.y"
            r="6.2"
            data-er-interactive="true"
            @mousedown.stop.prevent="onRouteAnchorMouseDown(activeRelationHandle.relationKey, 'source', $event)"
          />
          <circle
            class="er-route-handle er-route-handle-endpoint"
            :cx="activeRelationHandle.target.x"
            :cy="activeRelationHandle.target.y"
            r="6.2"
            data-er-interactive="true"
            @mousedown.stop.prevent="onRouteAnchorMouseDown(activeRelationHandle.relationKey, 'target', $event)"
          />
          <circle
            class="er-route-handle"
            :cx="activeRelationHandle.x"
            :cy="activeRelationHandle.y"
            r="7"
            data-er-interactive="true"
            @mousedown.stop.prevent="onRouteHandleMouseDown(activeRelationHandle, $event)"
          />
        </g>
      </g>
    </svg>

    <div class="er-card-layer" :style="worldTransformStyle">
      <div
        v-for="table in tableViews"
        :key="table.key"
        class="er-table-card"
        :class="{'is-dragging': dragState.kind === 'node' && dragState.tableKey === table.key}"
        :style="{
          width: `${table.width}px`,
          height: `${table.height}px`,
          left: `${table.left}px`,
          top: `${table.top}px`,
        }"
        data-er-interactive="true"
        @mousedown.stop.prevent="onTableMouseDown(table, $event)"
        @mouseenter="onTableMouseEnter(table, $event)"
        @mousemove="onTableMouseMove(table, $event)"
        @mouseleave="onTableMouseLeave"
      >
        <div class="er-table-card-header" :title="table.tableName">{{ table.tableName }}</div>
        <div v-if="showComments" class="er-table-card-subtitle" :title="table.tableComment || ''">
          {{ table.tableComment || ' ' }}
        </div>
        <div class="er-table-card-body">
          <div
            v-for="field in table.displayColumns"
            :key="field.key"
            class="er-table-field-row"
            :class="{'is-relation-endpoint': isRelationEndpointField(table.key, field.key)}"
            :title="field.fullTitle"
          >
            <span class="er-table-field-name">{{ field.columnName }}</span>
            <span class="er-table-field-tag-slot">
              <span v-if="field.primaryKey" class="er-field-mark er-field-mark-pk">PK</span>
              <span v-else-if="field.indexed" class="er-field-mark er-field-mark-idx">IDX</span>
            </span>
            <span class="er-table-field-type">{{ field.dataType || '-' }}</span>
            <span v-if="showComments && field.columnComment" class="er-table-field-comment">{{ field.columnComment }}</span>
          </div>
          <div v-if="table.moreCount > 0" class="er-table-more">{{ table.moreCount }} more columns...</div>
        </div>
      </div>
    </div>

    <div v-if="!optionReady" class="er-diagram-empty">{{ emptyText }}</div>

    <div
      v-if="tooltip.visible"
      class="er-float-tooltip"
      :style="tooltipStyle"
      v-html="tooltip.html"
    />
  </div>
</template>

<script setup lang="ts">
import type {ErColumnNodeVO, ErGraphVO, ErLayoutMode, ErRelationVO, ErTableNodeVO} from '../types';
import {computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch} from 'vue';

interface RelationRouteChangePayload {
  relationKey: string;
  routeManual: boolean;
  routeLaneX: number;
}

interface NodePosition {
  x: number;
  y: number;
}

interface ViewportState {
  width: number;
  height: number;
  scale: number;
  offsetX: number;
  offsetY: number;
}

interface RouteAnchorOffsets {
  sourceOffsetY?: number;
  targetOffsetY?: number;
}

interface RelationViewModel {
  relation: ErRelationVO;
  relationKey: string;
  sourceTableKey: string;
  sourceColumnKey: string;
  targetTableKey: string;
  targetColumnKey: string;
  sourceX: number;
  sourceY: number;
  targetX: number;
  targetY: number;
  laneX: number;
  pointsText: string;
  strokeColor: string;
  strokeWidth: number;
  dashArray: string;
  markerStart: string;
  markerEnd: string;
  isHovered: boolean;
  isActive: boolean;
  isAi: boolean;
  confidenceText: string;
  confidenceX: number;
  confidenceY: number;
}

interface TableColumnView {
  key: string;
  columnName: string;
  dataType?: string;
  columnComment?: string;
  primaryKey?: boolean;
  indexed?: boolean;
  fullTitle: string;
}

interface TableViewModel {
  key: string;
  tableName: string;
  tableComment?: string;
  width: number;
  height: number;
  left: number;
  top: number;
  centerX: number;
  centerY: number;
  columns: ErColumnNodeVO[];
  displayColumns: TableColumnView[];
  moreCount: number;
}

type RelationDirection = 'SOURCE_TO_TARGET' | 'TARGET_TO_SOURCE' | 'BIDIRECTIONAL';
type RouteAnchorRole = '' | 'source' | 'target';
type DragKind = 'none' | 'pan' | 'node' | 'route' | 'route-anchor';

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

const NODE_WIDTH = 226;
const NODE_TITLE_HEIGHT = 24;
const NODE_SUBTITLE_HEIGHT = 18;
const NODE_ROW_HEIGHT = 15;
const NODE_MAX_COLUMNS = 12;
const NODE_MIN_BODY_ROWS = 1;
const NODE_BODY_TOP_PADDING = 7;
const NODE_BODY_BOTTOM_PADDING = 7;
const NODE_HORIZONTAL_GAP = 16;
const NODE_VERTICAL_GAP = 18;
const ORTHOGONAL_LANE_GAP = 22;

const MIN_SCALE = 0.35;
const MAX_SCALE = 2.5;

const viewportRef = ref<HTMLDivElement | null>(null);
const optionReady = ref(false);
const emptyText = ref('No ER graph data');

const viewport = reactive<ViewportState>({
  width: 0,
  height: 0,
  scale: 1,
  offsetX: 0,
  offsetY: 0,
});

const manualNodePositions = reactive<Record<string, NodePosition>>({});
const manualRouteLaneX = reactive<Record<string, number>>({});
const manualRouteAnchorOffsets = reactive<Record<string, RouteAnchorOffsets>>({});

const activeRelationKey = ref('');
const hoveredRelationKey = ref('');

const tooltip = reactive({
  visible: false,
  x: 0,
  y: 0,
  html: '',
});

const dragState = reactive({
  kind: 'none' as DragKind,
  startClientX: 0,
  startClientY: 0,
  startOffsetX: 0,
  startOffsetY: 0,
  tableKey: '',
  startNodeX: 0,
  startNodeY: 0,
  startWorldX: 0,
  startWorldY: 0,
  relationKey: '',
  routeLaneX: 0,
  routeAnchorRole: '' as RouteAnchorRole,
  routeAnchorOffsetY: 0,
});

let resizeObserver: ResizeObserver | null = null;
let lastLayoutModeKey = '';
let lastTableSignature = '';

const showComments = computed(() => props.showComments === true);

function subtitleHeight() {
  return showComments.value ? NODE_SUBTITLE_HEIGHT : 0;
}

function headerHeight() {
  return NODE_TITLE_HEIGHT + subtitleHeight();
}

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

function normalizeLayoutMode(mode?: ErLayoutMode): ErLayoutMode {
  if (mode === 'CIRCLE' || mode === 'HIERARCHICAL') {
    return mode;
  }
  return 'GRID';
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
  const low = {r: 255, g: 236, b: 190};
  const high = {r: 246, g: 182, b: 72};
  const r = Math.round(low.r + (high.r - low.r) * t);
  const g = Math.round(low.g + (high.g - low.g) * t);
  const b = Math.round(low.b + (high.b - low.b) * t);
  return `rgb(${r}, ${g}, ${b})`;
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

function nodeHeight(table: ErTableNodeVO) {
  const rowCount = Math.max(NODE_MIN_BODY_ROWS, Math.min(NODE_MAX_COLUMNS, table.columns?.length || 0));
  const moreRow = (table.columns?.length || 0) > NODE_MAX_COLUMNS ? 1 : 0;
  return headerHeight() + NODE_BODY_TOP_PADDING + (rowCount + moreRow) * NODE_ROW_HEIGHT + NODE_BODY_BOTTOM_PADDING;
}

function hashString(input: string) {
  let hash = 0;
  for (let i = 0; i < input.length; i += 1) {
    hash = ((hash << 5) - hash) + input.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash);
}

function resolveAnchorOffsetBounds(table: TableViewModel) {
  const minY = headerHeight() + NODE_BODY_TOP_PADDING + NODE_ROW_HEIGHT / 2;
  const maxY = table.height - NODE_BODY_BOTTOM_PADDING - NODE_ROW_HEIGHT / 2;
  return {
    minY,
    maxY: Math.max(minY, maxY),
  };
}

function resolveAdaptiveAnchorY(
  table: TableViewModel,
  oppositeCenterY: number,
  relationKey: string,
  endpoint: 'source' | 'target',
) {
  const bounds = resolveAnchorOffsetBounds(table);
  if (bounds.maxY <= bounds.minY) {
    return table.centerY;
  }
  const minY = table.top + bounds.minY;
  const maxY = table.top + bounds.maxY;
  const baseY = clamp(oppositeCenterY, minY, maxY);
  const span = maxY - minY;
  const jitterRange = Math.max(2, Math.min(10, span * 0.2));
  const hashFactor = ((hashString(`${relationKey}|${table.key}|${endpoint}`) % 1000) / 999) - 0.5;
  return clamp(baseY + hashFactor * jitterRange, minY, maxY);
}

function buildGridPositions(tables: ErTableNodeVO[], canvasWidth: number): Record<string, NodePosition> {
  const positions: Record<string, NodePosition> = {};
  const paddingLeft = 24;
  const paddingTop = 24;
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
      positions[normalizeIdentifier(table.tableName)] = {
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
    positions[normalizeIdentifier(tables[0].tableName)] = {x: centerX, y: centerY};
    return positions;
  }
  const maxNodeHeight = Math.max(...tables.map((table) => nodeHeight(table)));
  const radius = Math.max(100, Math.min(canvasWidth, canvasHeight) / 2 - Math.max(NODE_WIDTH, maxNodeHeight) / 2 - 30);
  tables.forEach((table, index) => {
    const angle = (-Math.PI / 2) + (Math.PI * 2 * index) / tables.length;
    positions[normalizeIdentifier(table.tableName)] = {
      x: centerX + radius * Math.cos(angle),
      y: centerY + radius * Math.sin(angle),
    };
  });
  return positions;
}

function buildHierarchyLevels(tables: ErTableNodeVO[], relations: ErRelationVO[]) {
  const tableKeys = tables.map((table) => normalizeIdentifier(table.tableName));
  const keySet = new Set(tableKeys);
  const indegree = new Map<string, number>();
  const adjacency = new Map<string, Set<string>>();

  tableKeys.forEach((key) => {
    indegree.set(key, 0);
    adjacency.set(key, new Set<string>());
  });

  relations.forEach((relation) => {
    const source = normalizeIdentifier(relation.sourceTable);
    const target = normalizeIdentifier(relation.targetTable);
    if (!keySet.has(source) || !keySet.has(target) || source === target) {
      return;
    }
    const neighbors = adjacency.get(source);
    if (!neighbors || neighbors.has(target)) {
      return;
    }
    neighbors.add(target);
    indegree.set(target, (indegree.get(target) || 0) + 1);
  });

  const queue = tableKeys.filter((key) => (indegree.get(key) || 0) === 0);
  const levels = new Map<string, number>();
  queue.forEach((key) => levels.set(key, 0));

  let cursor = 0;
  while (cursor < queue.length) {
    const key = queue[cursor];
    cursor += 1;
    const currentLevel = levels.get(key) || 0;
    const neighbors = Array.from(adjacency.get(key) || []);
    neighbors.forEach((neighbor) => {
      levels.set(neighbor, Math.max(levels.get(neighbor) || 0, currentLevel + 1));
      indegree.set(neighbor, (indegree.get(neighbor) || 0) - 1);
      if ((indegree.get(neighbor) || 0) <= 0) {
        queue.push(neighbor);
      }
    });
  }

  tableKeys.forEach((key) => {
    if (!levels.has(key)) {
      levels.set(key, 0);
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
    const key = normalizeIdentifier(table.tableName);
    const level = levels.get(key) || 0;
    if (!levelMap.has(level)) {
      levelMap.set(level, []);
    }
    levelMap.get(level)?.push(key);
  });

  const sortedLevels = Array.from(levelMap.keys()).sort((a, b) => a - b);
  const left = 28;
  const right = canvasWidth - 28;
  const top = 28;
  const bottom = canvasHeight - 28;
  const xStep = sortedLevels.length > 1 ? (right - left) / (sortedLevels.length - 1) : 0;

  sortedLevels.forEach((level, levelIndex) => {
    const keys = (levelMap.get(level) || []).sort((a, b) => a.localeCompare(b));
    const stepY = (bottom - top) / (keys.length + 1);
    keys.forEach((key, index) => {
      positions[key] = {
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

function tableSignature(graph: ErGraphVO | null) {
  if (!graph?.tables?.length) {
    return '';
  }
  return graph.tables
    .map((table) => normalizeIdentifier(table.tableName))
    .filter((item) => !!item)
    .sort((a, b) => a.localeCompare(b))
    .join('|');
}

function clearRecord(record: Record<string, unknown>) {
  Object.keys(record).forEach((key) => {
    delete record[key];
  });
}

const allRelations = computed(() => {
  const graph = props.graph;
  if (!graph?.tables?.length) {
    return [] as ErRelationVO[];
  }
  const tableSet = new Set(graph.tables.map((table) => normalizeIdentifier(table.tableName)));
  return [...(graph.foreignKeyRelations || []), ...(graph.aiRelations || [])]
    .filter((relation) => tableSet.has(normalizeIdentifier(relation.sourceTable)) && tableSet.has(normalizeIdentifier(relation.targetTable)));
});

const defaultNodeCenters = computed<Record<string, NodePosition>>(() => {
  const graph = props.graph;
  if (!graph?.tables?.length) {
    return {};
  }
  const width = Math.max(640, viewport.width || 640);
  const height = Math.max(420, viewport.height || 420);
  return buildNodePositions(graph.tables, allRelations.value, normalizeLayoutMode(props.layoutMode), width, height);
});

const tableViews = computed<TableViewModel[]>(() => {
  const graph = props.graph;
  if (!graph?.tables?.length) {
    return [];
  }
  return graph.tables.map((table) => {
    const key = normalizeIdentifier(table.tableName);
    const defaultCenter = defaultNodeCenters.value[key] || {x: 160, y: 120};
    const center = manualNodePositions[key] || defaultCenter;
    const height = nodeHeight(table);
    const columns = table.columns || [];
    const displayColumns = columns.slice(0, NODE_MAX_COLUMNS).map((column) => {
      const columnName = safeText(column.columnName) || '-';
      const dataType = safeText(column.dataType) || '-';
      const comment = safeText(column.columnComment);
      const marks = [column.primaryKey ? 'PK' : '', column.indexed ? 'IDX' : ''].filter((item) => !!item).join(',');
      const markText = marks ? ` [${marks}]` : '';
      const commentText = comment ? ` // ${comment}` : '';
      return {
        key: normalizeIdentifier(column.columnName),
        columnName,
        dataType,
        columnComment: comment,
        primaryKey: column.primaryKey,
        indexed: column.indexed,
        fullTitle: `${columnName}${markText}: ${dataType}${commentText}`,
      };
    });
    return {
      key,
      tableName: table.tableName,
      tableComment: safeText(table.tableComment),
      width: NODE_WIDTH,
      height,
      left: center.x - NODE_WIDTH / 2,
      top: center.y - height / 2,
      centerX: center.x,
      centerY: center.y,
      columns,
      displayColumns,
      moreCount: Math.max(0, columns.length - NODE_MAX_COLUMNS),
    };
  });
});

const tableMap = computed(() => {
  const map = new Map<string, TableViewModel>();
  tableViews.value.forEach((table) => {
    map.set(table.key, table);
  });
  return map;
});

const relationViews = computed<RelationViewModel[]>(() => {
  return allRelations.value
    .map((relation) => {
      const sourceKey = normalizeIdentifier(relation.sourceTable);
      const targetKey = normalizeIdentifier(relation.targetTable);
      const sourceTable = tableMap.value.get(sourceKey);
      const targetTable = tableMap.value.get(targetKey);
      if (!sourceTable || !targetTable) {
        return null;
      }

      const relationKey = buildRelationKey(relation);
      const direction = normalizeRelationDirection(relation.relationDirection);
      const sourceTargetCenterDeltaX = Math.abs(sourceTable.centerX - targetTable.centerX);
      const useSameSideAnchor = sourceTargetCenterDeltaX < NODE_WIDTH * 0.72;
      const sameSideAnchorOnRight = sourceTable.centerX <= Math.max(viewport.width, 1) / 2;
      const sourceOnRight = useSameSideAnchor ? sameSideAnchorOnRight : (targetTable.centerX >= sourceTable.centerX);
      const targetOnRight = useSameSideAnchor ? sameSideAnchorOnRight : (sourceTable.centerX > targetTable.centerX);

      const sourceX = sourceTable.left + (sourceOnRight ? sourceTable.width : 0);
      const targetX = targetTable.left + (targetOnRight ? targetTable.width : 0);
      const sourceBounds = resolveAnchorOffsetBounds(sourceTable);
      const targetBounds = resolveAnchorOffsetBounds(targetTable);
      const manualAnchor = manualRouteAnchorOffsets[relationKey] || {};
      const sourceOffsetY = Number.isFinite(manualAnchor.sourceOffsetY)
        ? clamp(Number(manualAnchor.sourceOffsetY), sourceBounds.minY, sourceBounds.maxY)
        : (resolveAdaptiveAnchorY(sourceTable, targetTable.centerY, relationKey, 'source') - sourceTable.top);
      const targetOffsetY = Number.isFinite(manualAnchor.targetOffsetY)
        ? clamp(Number(manualAnchor.targetOffsetY), targetBounds.minY, targetBounds.maxY)
        : (resolveAdaptiveAnchorY(targetTable, sourceTable.centerY, relationKey, 'target') - targetTable.top);
      const sourceY = sourceTable.top + sourceOffsetY;
      const targetY = targetTable.top + targetOffsetY;

      const autoLaneX = sourceOnRight === targetOnRight
        ? (sourceOnRight ? Math.max(sourceX, targetX) + ORTHOGONAL_LANE_GAP : Math.min(sourceX, targetX) - ORTHOGONAL_LANE_GAP)
        : ((sourceX + targetX) / 2);
      const graphLaneX = Number(relation.routeLaneX);
      const laneX = Number.isFinite(manualRouteLaneX[relationKey])
        ? Number(manualRouteLaneX[relationKey])
        : ((relation.routeManual === true && Number.isFinite(graphLaneX)) ? graphLaneX : autoLaneX);

      const points = [
        [sourceX, sourceY],
        [laneX, sourceY],
        [laneX, targetY],
        [targetX, targetY],
      ];

      const isAi = normalizeRelationType(relation.relationType) === 'AI_INFERRED';
      const confidence = normalizeConfidence(relation.confidence);
      const baseColor = isAi ? buildAiEdgeColor(confidence) : '#f2c875';
      const hoverColor = isAi ? buildAiEdgeColor(Math.min(1, confidence + 0.28)) : '#ecb34d';
      const isHovered = hoveredRelationKey.value === relationKey;
      const isActive = activeRelationKey.value === relationKey;
      const isHighlighted = isHovered || isActive;

      const confidenceText = isAi ? `${Math.round(confidence * 100)}%` : '';
      return {
        relation,
        relationKey,
        sourceTableKey: sourceKey,
        sourceColumnKey: normalizeIdentifier(relation.sourceColumn),
        targetTableKey: targetKey,
        targetColumnKey: normalizeIdentifier(relation.targetColumn),
        sourceX,
        sourceY,
        targetX,
        targetY,
        laneX,
        pointsText: points.map((item) => `${item[0]},${item[1]}`).join(' '),
        strokeColor: isHighlighted ? hoverColor : baseColor,
        strokeWidth: isHighlighted ? (isAi ? 3.2 : 3.4) : (isAi ? 1.9 : 2.2),
        dashArray: '',
        markerStart: direction === 'TARGET_TO_SOURCE' || direction === 'BIDIRECTIONAL' ? 'url(#er-arrow)' : '',
        markerEnd: direction === 'SOURCE_TO_TARGET' || direction === 'BIDIRECTIONAL' ? 'url(#er-arrow)' : '',
        isHovered,
        isActive,
        isAi,
        confidenceText,
        confidenceX: laneX + 6,
        confidenceY: (sourceY + targetY) / 2,
      };
    })
    .filter((item): item is RelationViewModel => !!item);
});

const relationFocusKey = computed(() => hoveredRelationKey.value || activeRelationKey.value || '');

const highlightedFieldKeys = computed(() => {
  const result = new Set<string>();
  if (!relationFocusKey.value) {
    return result;
  }
  const relation = relationViews.value.find((item) => item.relationKey === relationFocusKey.value);
  if (!relation) {
    return result;
  }
  if (relation.sourceColumnKey) {
    result.add(`${relation.sourceTableKey}|${relation.sourceColumnKey}`);
  }
  if (relation.targetColumnKey) {
    result.add(`${relation.targetTableKey}|${relation.targetColumnKey}`);
  }
  return result;
});

function isRelationEndpointField(tableKey: string, columnKey: string) {
  if (!columnKey) {
    return false;
  }
  return highlightedFieldKeys.value.has(`${tableKey}|${columnKey}`);
}

const activeRelationHandle = computed(() => {
  const relation = relationViews.value.find((item) => item.relationKey === activeRelationKey.value);
  if (!relation) {
    return null;
  }
  return {
    relationKey: relation.relationKey,
    x: relation.laneX,
    y: (relation.sourceY + relation.targetY) / 2,
    source: {
      x: relation.sourceX,
      y: relation.sourceY,
    },
    target: {
      x: relation.targetX,
      y: relation.targetY,
    },
  };
});

const worldTransformStyle = computed(() => ({
  transform: `translate(${viewport.offsetX}px, ${viewport.offsetY}px) scale(${viewport.scale})`,
}));

const svgWorldTransform = computed(() => `translate(${viewport.offsetX} ${viewport.offsetY}) scale(${viewport.scale})`);

const gridStyle = computed(() => {
  const step = Math.max(6, 20 * viewport.scale);
  return {
    backgroundSize: `${step}px ${step}px`,
    backgroundPosition: `${viewport.offsetX}px ${viewport.offsetY}px`,
  };
});

const tooltipStyle = computed(() => {
  const width = Math.max(0, viewport.width - 16);
  const left = clamp(tooltip.x + 14, 8, Math.max(8, width - 360));
  const top = clamp(tooltip.y + 14, 8, Math.max(8, viewport.height - 120));
  return {
    left: `${left}px`,
    top: `${top}px`,
  };
});

function relationTooltipHtml(relation: ErRelationVO) {
  const direction = normalizeRelationDirection(relation.relationDirection);
  const expression = `${relation.sourceTable}.${relation.sourceColumn} ${relationArrowText(direction)} ${relation.targetTable}.${relation.targetColumn}`;
  const isAi = normalizeRelationType(relation.relationType) === 'AI_INFERRED';
  const reason = safeText(relation.reason);
  const confidence = `${Math.round(normalizeConfidence(relation.confidence) * 100)}%`;
  return [
    `<div style="font-weight:700;color:#0a3f72;">${escapeHtml(expression)}</div>`,
    `<div style="margin-top:4px;color:#3b5c85;">方向：${relationDirectionText(direction)}</div>`,
    `<div style="color:#3b5c85;">类型：${isAi ? 'AI 推断' : '外键'}</div>`,
    isAi ? `<div style="color:#3b5c85;">置信度：${confidence}</div>` : '',
    isAi ? `<div style="color:#5f4a1c;max-width:420px;">理由：${escapeHtml(reason || '模型未返回理由')}</div>` : '',
  ].filter((item) => !!item).join('');
}

function tableTooltipHtml(table: TableViewModel) {
  const tableComment = showComments.value ? safeText(table.tableComment) : '';
  const rows = table.columns.slice(0, 40).map((column) => {
    const marks = [column.primaryKey ? 'PK' : '', column.indexed ? 'IDX' : ''].filter((item) => !!item).join(',');
    const markText = marks ? ` <span style="color:#0f62c6;">[${escapeHtml(marks)}]</span>` : '';
    const comment = showComments.value ? safeText(column.columnComment) : '';
    const commentText = comment ? ` <span style="color:#5a6c86;">// ${escapeHtml(comment)}</span>` : '';
    return `${escapeHtml(safeText(column.columnName) || '-')} : ${escapeHtml(safeText(column.dataType) || '-')}${markText}${commentText}`;
  }).join('<br/>');
  return [
    `<div style="font-weight:700;color:#0a3f72;">${escapeHtml(table.tableName)}</div>`,
    tableComment ? `<div style="color:#516a8a;margin-top:2px;">${escapeHtml(tableComment)}</div>` : '',
    `<div style="margin-top:6px;line-height:1.5;color:#1f3554;">${rows || '暂无字段'}</div>`,
  ].filter((item) => !!item).join('');
}

function updateTooltipByClient(clientX: number, clientY: number, html: string) {
  const root = viewportRef.value;
  if (!root) {
    return;
  }
  const rect = root.getBoundingClientRect();
  tooltip.visible = true;
  tooltip.x = clientX - rect.left;
  tooltip.y = clientY - rect.top;
  tooltip.html = html;
}

function hideTooltip() {
  tooltip.visible = false;
  tooltip.html = '';
}

function toWorldByClient(clientX: number, clientY: number) {
  const root = viewportRef.value;
  if (!root) {
    return {x: 0, y: 0};
  }
  const rect = root.getBoundingClientRect();
  const localX = clientX - rect.left;
  const localY = clientY - rect.top;
  return {
    x: (localX - viewport.offsetX) / viewport.scale,
    y: (localY - viewport.offsetY) / viewport.scale,
  };
}

function toLocalByClient(clientX: number, clientY: number) {
  const root = viewportRef.value;
  if (!root) {
    return {x: 0, y: 0};
  }
  const rect = root.getBoundingClientRect();
  return {
    x: clientX - rect.left,
    y: clientY - rect.top,
  };
}

function initViewportSize() {
  const root = viewportRef.value;
  if (!root) {
    return;
  }
  viewport.width = Math.max(320, root.clientWidth || 0);
  viewport.height = Math.max(240, root.clientHeight || 0);
}

function maybeResetLayoutState() {
  const graph = props.graph;
  const nextLayoutModeKey = normalizeLayoutMode(props.layoutMode);
  const nextTableSignature = tableSignature(graph);
  if (nextLayoutModeKey === lastLayoutModeKey && nextTableSignature === lastTableSignature) {
    return;
  }
  clearRecord(manualNodePositions);
  clearRecord(manualRouteLaneX);
  clearRecord(manualRouteAnchorOffsets);
  activeRelationKey.value = '';
  hoveredRelationKey.value = '';
  hideTooltip();
  lastLayoutModeKey = nextLayoutModeKey;
  lastTableSignature = nextTableSignature;
}

function syncRouteOverridesFromGraph() {
  const validKeys = new Set<string>();
  allRelations.value.forEach((relation) => {
    const key = buildRelationKey(relation);
    validKeys.add(key);
    if (manualRouteLaneX[key] != null) {
      return;
    }
    const routeLaneX = Number(relation.routeLaneX);
    if (relation.routeManual === true && Number.isFinite(routeLaneX)) {
      manualRouteLaneX[key] = routeLaneX;
    }
  });
  Object.keys(manualRouteLaneX).forEach((key) => {
    if (!validKeys.has(key)) {
      delete manualRouteLaneX[key];
    }
  });
  Object.keys(manualRouteAnchorOffsets).forEach((key) => {
    if (!validKeys.has(key)) {
      delete manualRouteAnchorOffsets[key];
    }
  });
  if (activeRelationKey.value && !validKeys.has(activeRelationKey.value)) {
    activeRelationKey.value = '';
  }
  if (hoveredRelationKey.value && !validKeys.has(hoveredRelationKey.value)) {
    hoveredRelationKey.value = '';
  }
}

function startWindowDragListeners() {
  window.addEventListener('mousemove', onWindowMouseMove);
  window.addEventListener('mouseup', onWindowMouseUp);
}

function stopWindowDragListeners() {
  window.removeEventListener('mousemove', onWindowMouseMove);
  window.removeEventListener('mouseup', onWindowMouseUp);
}

function endDrag() {
  dragState.kind = 'none';
  dragState.tableKey = '';
  dragState.relationKey = '';
  dragState.routeAnchorRole = '';
  dragState.routeAnchorOffsetY = 0;
  stopWindowDragListeners();
}

function onViewportMouseDown(event: MouseEvent) {
  if (event.button !== 0) {
    return;
  }
  const target = event.target as HTMLElement | null;
  if (target?.closest('[data-er-interactive="true"]')) {
    return;
  }
  activeRelationKey.value = '';
  hoveredRelationKey.value = '';
  hideTooltip();
  dragState.kind = 'pan';
  dragState.startClientX = event.clientX;
  dragState.startClientY = event.clientY;
  dragState.startOffsetX = viewport.offsetX;
  dragState.startOffsetY = viewport.offsetY;
  startWindowDragListeners();
}

function onViewportWheel(event: WheelEvent) {
  if (dragState.kind !== 'none') {
    return;
  }
  const local = toLocalByClient(event.clientX, event.clientY);
  const before = {
    x: (local.x - viewport.offsetX) / viewport.scale,
    y: (local.y - viewport.offsetY) / viewport.scale,
  };
  const factor = event.deltaY < 0 ? 1.1 : 0.9;
  const nextScale = clamp(viewport.scale * factor, MIN_SCALE, MAX_SCALE);
  viewport.offsetX = local.x - before.x * nextScale;
  viewport.offsetY = local.y - before.y * nextScale;
  viewport.scale = nextScale;
}

function onTableMouseDown(table: TableViewModel, event: MouseEvent) {
  if (event.button !== 0) {
    return;
  }
  const world = toWorldByClient(event.clientX, event.clientY);
  dragState.kind = 'node';
  dragState.tableKey = table.key;
  dragState.startNodeX = table.centerX;
  dragState.startNodeY = table.centerY;
  dragState.startWorldX = world.x;
  dragState.startWorldY = world.y;
  hideTooltip();
  startWindowDragListeners();
}

function onRouteHandleMouseDown(handle: {relationKey: string; x: number; y: number}, event: MouseEvent) {
  if (event.button !== 0) {
    return;
  }
  const world = toWorldByClient(event.clientX, event.clientY);
  dragState.kind = 'route';
  dragState.relationKey = handle.relationKey;
  dragState.startWorldX = world.x;
  dragState.routeLaneX = handle.x;
  dragState.routeAnchorRole = '';
  hideTooltip();
  startWindowDragListeners();
}

function onRouteAnchorMouseDown(relationKey: string, anchorRole: Exclude<RouteAnchorRole, ''>, event: MouseEvent) {
  if (event.button !== 0) {
    return;
  }
  const relation = relationViews.value.find((item) => item.relationKey === relationKey);
  if (!relation) {
    return;
  }
  const world = toWorldByClient(event.clientX, event.clientY);
  const table = anchorRole === 'source'
    ? tableMap.value.get(relation.sourceTableKey)
    : tableMap.value.get(relation.targetTableKey);
  if (!table) {
    return;
  }
  dragState.kind = 'route-anchor';
  dragState.relationKey = relationKey;
  dragState.routeAnchorRole = anchorRole;
  dragState.startWorldX = world.x;
  dragState.startWorldY = world.y;
  dragState.routeAnchorOffsetY = anchorRole === 'source'
    ? (relation.sourceY - table.top)
    : (relation.targetY - table.top);
  hideTooltip();
  startWindowDragListeners();
}

function onWindowMouseMove(event: MouseEvent) {
  if (dragState.kind === 'pan') {
    const dx = event.clientX - dragState.startClientX;
    const dy = event.clientY - dragState.startClientY;
    viewport.offsetX = dragState.startOffsetX + dx;
    viewport.offsetY = dragState.startOffsetY + dy;
    return;
  }
  if (dragState.kind === 'node') {
    const world = toWorldByClient(event.clientX, event.clientY);
    const nextX = dragState.startNodeX + (world.x - dragState.startWorldX);
    const nextY = dragState.startNodeY + (world.y - dragState.startWorldY);
    if (dragState.tableKey) {
      manualNodePositions[dragState.tableKey] = {x: nextX, y: nextY};
    }
    hideTooltip();
    return;
  }
  if (dragState.kind === 'route') {
    const world = toWorldByClient(event.clientX, event.clientY);
    if (dragState.relationKey) {
      const laneX = dragState.routeLaneX + (world.x - dragState.startWorldX);
      manualRouteLaneX[dragState.relationKey] = laneX;
    }
    hideTooltip();
    return;
  }
  if (dragState.kind === 'route-anchor') {
    const world = toWorldByClient(event.clientX, event.clientY);
    const relation = relationViews.value.find((item) => item.relationKey === dragState.relationKey);
    if (!relation || !dragState.routeAnchorRole) {
      hideTooltip();
      return;
    }
    const table = dragState.routeAnchorRole === 'source'
      ? tableMap.value.get(relation.sourceTableKey)
      : tableMap.value.get(relation.targetTableKey);
    if (!table) {
      hideTooltip();
      return;
    }
    const bounds = resolveAnchorOffsetBounds(table);
    const nextOffsetY = clamp(
      dragState.routeAnchorOffsetY + (world.y - dragState.startWorldY),
      bounds.minY,
      bounds.maxY,
    );
    const existing = manualRouteAnchorOffsets[relation.relationKey] || {};
    manualRouteAnchorOffsets[relation.relationKey] = {
      sourceOffsetY: dragState.routeAnchorRole === 'source' ? nextOffsetY : existing.sourceOffsetY,
      targetOffsetY: dragState.routeAnchorRole === 'target' ? nextOffsetY : existing.targetOffsetY,
    };
    hideTooltip();
  }
}

function onWindowMouseUp() {
  if (dragState.kind === 'route' && dragState.relationKey) {
    const laneX = Number(manualRouteLaneX[dragState.relationKey]);
    if (Number.isFinite(laneX)) {
      emit('relation-route-change', {
        relationKey: dragState.relationKey,
        routeManual: true,
        routeLaneX: laneX,
      });
    }
  }
  endDrag();
}

function onTableMouseEnter(table: TableViewModel, event: MouseEvent) {
  if (dragState.kind !== 'none') {
    return;
  }
  updateTooltipByClient(event.clientX, event.clientY, tableTooltipHtml(table));
}

function onTableMouseMove(table: TableViewModel, event: MouseEvent) {
  if (dragState.kind !== 'none') {
    return;
  }
  updateTooltipByClient(event.clientX, event.clientY, tableTooltipHtml(table));
}

function onTableMouseLeave() {
  if (dragState.kind === 'none') {
    hideTooltip();
  }
}

function onRelationMouseEnter(relation: RelationViewModel, event: MouseEvent) {
  hoveredRelationKey.value = relation.relationKey;
  updateTooltipByClient(event.clientX, event.clientY, relationTooltipHtml(relation.relation));
}

function onRelationMouseMove(relation: RelationViewModel, event: MouseEvent) {
  hoveredRelationKey.value = relation.relationKey;
  updateTooltipByClient(event.clientX, event.clientY, relationTooltipHtml(relation.relation));
}

function onRelationMouseLeave() {
  hoveredRelationKey.value = '';
  if (dragState.kind === 'none') {
    hideTooltip();
  }
}

function onRelationClick(relation: RelationViewModel, event: MouseEvent) {
  event.stopPropagation();
  activeRelationKey.value = relation.relationKey;
}

function onRelationMouseDown(relation: RelationViewModel, event: MouseEvent) {
  if (event.button !== 0) {
    return;
  }
  event.stopPropagation();
  activeRelationKey.value = relation.relationKey;
  const world = toWorldByClient(event.clientX, event.clientY);
  dragState.kind = 'route';
  dragState.relationKey = relation.relationKey;
  dragState.startWorldX = world.x;
  dragState.routeLaneX = relation.laneX;
  dragState.routeAnchorRole = '';
  hideTooltip();
  startWindowDragListeners();
}

function onViewportMouseMove(event: MouseEvent) {
  if (dragState.kind === 'pan' || dragState.kind === 'node' || dragState.kind === 'route' || dragState.kind === 'route-anchor') {
    hideTooltip();
    return;
  }
  const target = event.target as HTMLElement | null;
  if (!target?.closest('[data-er-interactive="true"]')) {
    hideTooltip();
    hoveredRelationKey.value = '';
  }
}

function onViewportMouseLeave() {
  if (dragState.kind === 'none') {
    hoveredRelationKey.value = '';
    hideTooltip();
  }
}

function buildCardRowText(field: TableColumnView) {
  const marks = [field.primaryKey ? 'PK' : '', field.indexed ? 'IDX' : ''].filter((item) => !!item).join(',');
  const markText = marks ? ` [${marks}]` : '';
  const comment = showComments.value ? safeText(field.columnComment) : '';
  const commentText = comment ? ` // ${comment}` : '';
  return `${field.columnName}${markText}: ${field.dataType || '-'}${commentText}`;
}

function drawRoundedRect(ctx: CanvasRenderingContext2D, x: number, y: number, width: number, height: number, radius: number) {
  const r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + width, y, x + width, y + height, r);
  ctx.arcTo(x + width, y + height, x, y + height, r);
  ctx.arcTo(x, y + height, x, y, r);
  ctx.arcTo(x, y, x + width, y, r);
  ctx.closePath();
}

function drawArrow(ctx: CanvasRenderingContext2D, fromX: number, fromY: number, toX: number, toY: number, color: string, size: number) {
  const angle = Math.atan2(toY - fromY, toX - fromX);
  ctx.save();
  ctx.translate(toX, toY);
  ctx.rotate(angle);
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(0, 0);
  ctx.lineTo(-size, size * 0.55);
  ctx.lineTo(-size, -size * 0.55);
  ctx.closePath();
  ctx.fill();
  ctx.restore();
}

function worldToScreen(point: NodePosition) {
  return {
    x: point.x * viewport.scale + viewport.offsetX,
    y: point.y * viewport.scale + viewport.offsetY,
  };
}

interface ExportPngOptions {
  pixelRatio?: number;
  backgroundColor?: string;
}

async function exportPngDataUrl(options?: ExportPngOptions) {
  await nextTick();
  if (!optionReady.value) {
    return '';
  }
  const width = Math.max(1, viewport.width);
  const height = Math.max(1, viewport.height);
  const pixelRatio = Number.isFinite(options?.pixelRatio)
    ? Math.max(1, Math.min(Number(options?.pixelRatio), 3))
    : 2;
  const backgroundColor = safeText(options?.backgroundColor || '#f3f5f8') || '#f3f5f8';

  const canvas = document.createElement('canvas');
  canvas.width = Math.round(width * pixelRatio);
  canvas.height = Math.round(height * pixelRatio);
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    return '';
  }

  ctx.scale(pixelRatio, pixelRatio);
  ctx.fillStyle = backgroundColor;
  ctx.fillRect(0, 0, width, height);

  const gridStep = Math.max(6, 20 * viewport.scale);
  ctx.strokeStyle = '#d9e1eb';
  ctx.lineWidth = 1;
  const startX = ((viewport.offsetX % gridStep) + gridStep) % gridStep;
  const startY = ((viewport.offsetY % gridStep) + gridStep) % gridStep;
  for (let x = startX; x <= width; x += gridStep) {
    ctx.beginPath();
    ctx.moveTo(x, 0);
    ctx.lineTo(x, height);
    ctx.stroke();
  }
  for (let y = startY; y <= height; y += gridStep) {
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(width, y);
    ctx.stroke();
  }

  relationViews.value.forEach((relation) => {
    const points = [
      worldToScreen({x: relation.sourceX, y: relation.sourceY}),
      worldToScreen({x: relation.laneX, y: relation.sourceY}),
      worldToScreen({x: relation.laneX, y: relation.targetY}),
      worldToScreen({x: relation.targetX, y: relation.targetY}),
    ];

    ctx.save();
    ctx.strokeStyle = relation.strokeColor;
    ctx.lineWidth = relation.strokeWidth;
    if (relation.dashArray) {
      const [a, b] = relation.dashArray.split(' ').map((item) => Number(item));
      if (Number.isFinite(a) && Number.isFinite(b)) {
        ctx.setLineDash([a, b]);
      }
    }
    ctx.beginPath();
    ctx.moveTo(points[0].x, points[0].y);
    for (let i = 1; i < points.length; i += 1) {
      ctx.lineTo(points[i].x, points[i].y);
    }
    ctx.stroke();
    ctx.restore();

    const arrowSize = 5.2;
    if (relation.markerEnd) {
      drawArrow(
        ctx,
        points[points.length - 2].x,
        points[points.length - 2].y,
        points[points.length - 1].x,
        points[points.length - 1].y,
        relation.strokeColor,
        arrowSize,
      );
    }
    if (relation.markerStart) {
      drawArrow(
        ctx,
        points[1].x,
        points[1].y,
        points[0].x,
        points[0].y,
        relation.strokeColor,
        arrowSize,
      );
    }

    if (relation.confidenceText) {
      const confidencePoint = worldToScreen({x: relation.confidenceX, y: relation.confidenceY});
      ctx.save();
      ctx.font = '11px sans-serif';
      const textWidth = ctx.measureText(relation.confidenceText).width;
      drawRoundedRect(ctx, confidencePoint.x - 3, confidencePoint.y - 8, textWidth + 7, 16, 4);
      ctx.fillStyle = 'rgba(241, 247, 255, 0.95)';
      ctx.fill();
      ctx.strokeStyle = '#8db5ea';
      ctx.lineWidth = 1;
      ctx.stroke();
      ctx.fillStyle = '#09396d';
      ctx.fillText(relation.confidenceText, confidencePoint.x, confidencePoint.y + 4);
      ctx.restore();
    }
  });

  tableViews.value.forEach((table) => {
    const topLeft = worldToScreen({x: table.left, y: table.top});
    const cardWidth = table.width * viewport.scale;
    const cardHeight = table.height * viewport.scale;
    const headerHeight = NODE_TITLE_HEIGHT * viewport.scale;
    const subtitleHeight = (showComments.value ? NODE_SUBTITLE_HEIGHT : 0) * viewport.scale;

    ctx.save();
    ctx.shadowColor = 'rgba(7, 39, 76, 0.18)';
    ctx.shadowBlur = 8;
    drawRoundedRect(ctx, topLeft.x, topLeft.y, cardWidth, cardHeight, 10 * viewport.scale);
    ctx.fillStyle = '#fcfeff';
    ctx.fill();
    ctx.strokeStyle = '#7cb4eb';
    ctx.lineWidth = 1.4;
    ctx.stroke();
    ctx.restore();

    ctx.save();
    drawRoundedRect(ctx, topLeft.x, topLeft.y, cardWidth, headerHeight + subtitleHeight, 10 * viewport.scale);
    ctx.clip();
    ctx.fillStyle = '#3e8de9';
    ctx.fillRect(topLeft.x, topLeft.y, cardWidth, headerHeight);
    if (subtitleHeight > 0) {
      ctx.fillStyle = '#5da5f0';
      ctx.fillRect(topLeft.x, topLeft.y + headerHeight, cardWidth, subtitleHeight);
    }
    ctx.restore();

    const titleFont = Math.max(10, 12 * viewport.scale);
    const subFont = Math.max(9, 10 * viewport.scale);
    const bodyFont = Math.max(9, 10 * viewport.scale);

    ctx.save();
    ctx.fillStyle = '#ffffff';
    ctx.font = `700 ${titleFont}px sans-serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(truncateText(table.tableName, 28), topLeft.x + cardWidth / 2, topLeft.y + headerHeight / 2);

    if (subtitleHeight > 0) {
      ctx.fillStyle = '#e8f3ff';
      ctx.font = `${subFont}px sans-serif`;
      const subtitle = truncateText(table.tableComment || '', 24);
      ctx.fillText(subtitle || ' ', topLeft.x + cardWidth / 2, topLeft.y + headerHeight + subtitleHeight / 2);
    }
    ctx.restore();

    const bodyStartY = topLeft.y + headerHeight + subtitleHeight + (NODE_BODY_TOP_PADDING * viewport.scale);
    table.displayColumns.forEach((field, index) => {
      const y = bodyStartY + index * (NODE_ROW_HEIGHT * viewport.scale);
      const rowText = truncateText(buildCardRowText(field), 54);
      ctx.save();
      ctx.fillStyle = '#1a2f4f';
      ctx.font = `${bodyFont}px sans-serif`;
      ctx.textAlign = 'left';
      ctx.textBaseline = 'alphabetic';
      ctx.fillText(rowText, topLeft.x + 7 * viewport.scale, y + 11 * viewport.scale);
      ctx.restore();
    });

    if (table.moreCount > 0) {
      const y = bodyStartY + table.displayColumns.length * (NODE_ROW_HEIGHT * viewport.scale);
      ctx.save();
      ctx.fillStyle = '#5f7391';
      ctx.font = `${bodyFont}px sans-serif`;
      ctx.fillText(`${table.moreCount} more columns...`, topLeft.x + 7 * viewport.scale, y + 11 * viewport.scale);
      ctx.restore();
    }
  });

  return canvas.toDataURL('image/png');
}

defineExpose({
  exportPngDataUrl,
});

watch(
  [() => props.graph, () => props.layoutMode],
  () => {
    maybeResetLayoutState();
    optionReady.value = !!(props.graph?.tables?.length);
    if (!optionReady.value) {
      emptyText.value = 'No ER graph data';
      hideTooltip();
      activeRelationKey.value = '';
      hoveredRelationKey.value = '';
    }
  },
  {deep: true, immediate: true},
);

watch(
  () => allRelations.value,
  () => {
    syncRouteOverridesFromGraph();
  },
  {deep: true, immediate: true},
);

onMounted(() => {
  initViewportSize();
  if (viewportRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => {
      initViewportSize();
    });
    resizeObserver.observe(viewportRef.value);
  }
});

onBeforeUnmount(() => {
  stopWindowDragListeners();
  if (resizeObserver && viewportRef.value) {
    resizeObserver.unobserve(viewportRef.value);
  }
  resizeObserver = null;
});
</script>

<style scoped>
.er-diagram-shell {
  width: 100%;
  height: 100%;
  min-height: 360px;
  border-radius: 10px;
  background: #f3f5f8;
  position: relative;
  overflow: hidden;
  user-select: none;
}

.er-grid-layer {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(#d9e1eb 1px, transparent 1px),
    linear-gradient(90deg, #d9e1eb 1px, transparent 1px);
  pointer-events: none;
}

.er-svg-layer {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.er-card-layer {
  position: absolute;
  inset: 0;
  transform-origin: 0 0;
  pointer-events: none;
}

.er-table-card {
  position: absolute;
  border-radius: 10px;
  border: 1.4px solid #7cb4eb;
  background: #fcfeff;
  box-shadow: 0 4px 12px rgba(42, 90, 148, 0.14);
  overflow: hidden;
  cursor: grab;
  pointer-events: auto;
}

.er-table-card.is-dragging {
  cursor: grabbing;
  box-shadow: 0 8px 20px rgba(42, 90, 148, 0.2);
}

.er-table-card-header {
  height: 24px;
  line-height: 24px;
  padding: 0 8px;
  text-align: center;
  background: #3e8de9;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.er-table-card-subtitle {
  height: 18px;
  line-height: 18px;
  padding: 0 8px;
  text-align: center;
  background: #5da5f0;
  color: #e8f3ff;
  font-size: 10px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.er-table-card-body {
  padding-top: 7px;
  padding-bottom: 7px;
}

.er-table-field-row {
  height: 15px;
  line-height: 15px;
  padding: 0 7px;
  font-size: 10px;
  color: #244367;
  display: grid;
  grid-template-columns: minmax(0, 112px) 30px minmax(0, 68px) minmax(0, 1fr);
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  overflow: hidden;
}

.er-table-field-row.is-relation-endpoint {
  background: rgba(255, 202, 106, 0.23);
}

.er-table-field-row.is-relation-endpoint .er-table-field-name {
  color: #7a4300;
  font-weight: 700;
}

.er-table-field-row.is-relation-endpoint .er-table-field-type,
.er-table-field-row.is-relation-endpoint .er-table-field-comment {
  color: #8a5b15;
}

.er-table-field-name {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: left;
}

.er-table-field-tag-slot {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  justify-content: flex-start;
}

.er-table-field-type {
  color: #3f6693;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.er-table-field-comment {
  color: #6d85a6;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.er-field-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 12px;
  border-radius: 999px;
  padding: 0 4px;
  font-size: 9px;
  font-weight: 700;
}

.er-field-mark-pk {
  color: #8a5100;
  background: #ffebb9;
  border: 1px solid #e8be73;
}

.er-field-mark-idx {
  color: #2f5c9c;
  background: #edf4ff;
  border: 1px solid #aac2e8;
}

.er-table-more {
  height: 15px;
  line-height: 15px;
  padding: 0 7px;
  font-size: 10px;
  color: #5f7391;
}

.er-relation-group {
  color: #f2c875;
}

.er-relation-group.is-ai {
  color: #f0bf67;
}

.er-relation-group.is-hovered,
.er-relation-group.is-active {
  color: #ecb34d;
}

.er-relation-line {
  filter: drop-shadow(0 0 0 rgba(0, 0, 0, 0));
}

.er-relation-group.is-hovered .er-relation-line,
.er-relation-group.is-active .er-relation-line {
  filter: drop-shadow(0 0 7px rgba(236, 179, 77, 0.34));
}

.er-relation-confidence {
  font-size: 10px;
  font-weight: 700;
  fill: #845305;
  paint-order: stroke;
  stroke: rgba(255, 255, 255, 0.86);
  stroke-width: 2;
  pointer-events: none;
}

.er-route-handle {
  fill: #ffd37a;
  stroke: #6e4500;
  stroke-width: 1.5;
  cursor: ew-resize;
  filter: drop-shadow(0 0 4px rgba(110, 69, 0, 0.35));
}

.er-route-handle-endpoint {
  fill: #ffe6a8;
  stroke: #a36500;
  cursor: ns-resize;
}

.er-float-tooltip {
  position: absolute;
  max-width: 420px;
  z-index: 12;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid #c2d2ea;
  background: rgba(252, 254, 255, 0.98);
  box-shadow: 0 8px 20px rgba(15, 40, 79, 0.22);
  color: #223a5a;
  font-size: 12px;
  line-height: 1.45;
  pointer-events: none;
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
