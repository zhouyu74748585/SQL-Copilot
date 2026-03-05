<template>
  <div class="er-diagram-shell">
    <div ref="chartRef" class="er-diagram-canvas" />
    <div v-if="!optionReady" class="er-diagram-empty">{{ emptyText }}</div>
  </div>
</template>

<script setup lang="ts">
import type {ErGraphVO, ErLayoutMode, ErRelationVO, ErTableNodeVO} from '../types';
import * as echarts from 'echarts';
import {nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue';

const props = withDefaults(defineProps<{
  graph: ErGraphVO | null;
  layoutMode?: ErLayoutMode;
}>(), {
  layoutMode: 'GRID',
});

const chartRef = ref<HTMLElement | null>(null);
let chartInstance: echarts.ECharts | null = null;
let resizeObserver: ResizeObserver | null = null;
const optionReady = ref(false);
const emptyText = ref('No ER graph data');
const NODE_WIDTH = 240;
const NODE_MIN_HEIGHT = 70;
const NODE_MAX_COLUMNS = 8;
const NODE_ROW_HEIGHT = 14;

type NodePosition = { x: number; y: number };

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

function normalizeLayoutMode(mode?: ErLayoutMode): ErLayoutMode {
  if (mode === 'CIRCLE' || mode === 'HIERARCHICAL') {
    return mode;
  }
  return 'GRID';
}

function buildNodeLabel(table: ErTableNodeVO) {
  const name = (table.tableName || '').trim();
  const columns = table.columns || [];
  if (!columns.length) {
    return name;
  }
  const preview = columns.slice(0, 8).map((item) => {
    const col = (item.columnName || '').trim();
    if (!col) {
      return '';
    }
    const marks = [
      item.primaryKey ? 'PK' : '',
      item.indexed ? 'IDX' : '',
    ].filter((x) => !!x).join(',');
    return marks ? `${col} [${marks}]` : col;
  }).filter((x) => !!x);
  const suffix = columns.length > 8 ? '\n...' : '';
  return `${name}\n${preview.join('\n')}${suffix}`;
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

function nodeHeight(table: ErTableNodeVO) {
  const rowCount = Math.max(1, Math.min(NODE_MAX_COLUMNS, table.columns?.length || 0));
  return NODE_MIN_HEIGHT + rowCount * NODE_ROW_HEIGHT;
}

function resolveCanvasSize() {
  const width = Math.max(860, chartRef.value?.clientWidth || 0);
  const height = Math.max(560, chartRef.value?.clientHeight || 0);
  return {width, height};
}

function buildGridPositions(tables: ErTableNodeVO[], canvasWidth: number): Record<string, NodePosition> {
  const positions: Record<string, NodePosition> = {};
  const maxNodeHeight = Math.max(...tables.map((table) => nodeHeight(table)));
  const rowHeight = maxNodeHeight + 52;
  const columnCount = clamp(Math.floor((canvasWidth - 56) / (NODE_WIDTH + 34)), 1, 6);
  const horizontalGap = Math.max(24, (canvasWidth - columnCount * NODE_WIDTH) / (columnCount + 1));
  const topOffset = 40;
  tables.forEach((table, index) => {
    const col = index % columnCount;
    const row = Math.floor(index / columnCount);
    const x = horizontalGap + col * (NODE_WIDTH + horizontalGap) + NODE_WIDTH / 2;
    const y = topOffset + row * rowHeight + rowHeight / 2;
    positions[table.tableName] = {x, y};
  });
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
    120,
    Math.min(canvasWidth, canvasHeight) / 2 - Math.max(NODE_WIDTH, maxNodeHeight) / 2 - 24,
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
  const left = 72;
  const right = canvasWidth - 72;
  const top = 46;
  const bottom = canvasHeight - 46;
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

function buildGraphOption(graph: ErGraphVO | null, layoutMode: ErLayoutMode): echarts.EChartsOption | null {
  if (!graph || !graph.tables?.length) {
    emptyText.value = 'No ER graph data';
    return null;
  }
  const fkRelations = graph.foreignKeyRelations || [];
  const aiRelations = graph.aiRelations || [];
  const tableNameSet = new Set(graph.tables.map((table) => table.tableName));
  const allRelations = [...fkRelations, ...aiRelations]
    .filter((relation) => tableNameSet.has(relation.sourceTable) && tableNameSet.has(relation.targetTable));
  const canvasSize = resolveCanvasSize();
  const nodePositions = buildNodePositions(graph.tables, allRelations, layoutMode, canvasSize.width, canvasSize.height);
  const nodeData = graph.tables.map((table, index) => {
    const position = nodePositions[table.tableName] || {
      x: 120 + (index % 5) * 250,
      y: 120 + Math.floor(index / 5) * 170,
    };
    const symbolHeight = nodeHeight(table);
    return {
      id: table.tableName,
      name: table.tableName,
      value: buildNodeLabel(table),
      symbol: 'rect',
      symbolSize: [NODE_WIDTH, symbolHeight],
      category: 0,
      draggable: true,
      x: position.x,
      y: position.y,
      itemStyle: {
        color: '#ecf4ff',
        borderColor: '#7aa4ff',
        borderWidth: 1.2,
      },
      label: {
        show: true,
        formatter: () => buildNodeLabel(table),
        fontSize: 11,
        color: '#1f2f45',
        lineHeight: NODE_ROW_HEIGHT,
        width: NODE_WIDTH - 20,
        overflow: 'truncate' as const,
        align: 'left' as const,
        padding: [8, 10, 8, 10],
      },
    };
  });
  const links = allRelations.map((relation) => {
    const isAi = (relation.relationType || '').toUpperCase() === 'AI_INFERRED';
    const lineType = isAi ? ('dashed' as const) : ('solid' as const);
    const reason = (relation.reason || '').trim();
    return {
      source: relation.sourceTable,
      target: relation.targetTable,
      value: 1,
      name: `${relation.sourceColumn || ''} -> ${relation.targetColumn || ''}`,
      label: {
        show: isAi,
        position: 'middle' as const,
        formatter: edgeLabel(relation),
        color: isAi ? '#8d6d1f' : '#2f5fcc',
        fontSize: 10,
        backgroundColor: isAi ? 'rgba(255, 245, 217, 0.95)' : 'transparent',
        borderColor: isAi ? '#d8b35c' : 'transparent',
        borderWidth: isAi ? 1 : 0,
        borderRadius: isAi ? 4 : 0,
        padding: isAi ? [2, 4, 2, 4] : [0, 0, 0, 0],
      },
      lineStyle: {
        type: lineType,
        width: isAi ? 1.7 : 2,
        color: isAi ? '#b8912e' : '#3b82f6',
        curveness: isAi ? 0.15 : 0.04,
      },
      emphasis: {
        lineStyle: {
          width: isAi ? 2.2 : 2.6,
        },
      },
      relation: {
        ...relation,
        reason,
      },
    };
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
          const isAi = (relation.relationType || '').toUpperCase() === 'AI_INFERRED';
          if (!isAi) {
            return `${relation.sourceTable}.${relation.sourceColumn} -> ${relation.targetTable}.${relation.targetColumn}<br/>FK`;
          }
          const confidenceText = edgeLabel(relation) || '-';
          const reason = (relation.reason || '').trim();
          return [
            `<strong>${relation.sourceTable}.${relation.sourceColumn} -> ${relation.targetTable}.${relation.targetColumn}</strong>`,
            `置信度：${confidenceText}`,
            `理由：${reason || '模型未返回理由'}`,
          ].filter((x) => !!x).join('<br/>');
        }
        const table = params?.data as {name?: string; value?: string} | undefined;
        if (!table?.name) {
          return '';
        }
        return `${table.name}<br/>${String(table.value || '').split('\n').length - 1} 列`;
      },
    },
    legend: {
      show: true,
      data: ['Table'],
      top: 10,
      left: 'center',
    },
    series: [
      {
        type: 'graph',
        layout: 'none',
        roam: true,
        draggable: true,
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: [4, 9],
        data: nodeData,
        links,
        categories: [{name: 'Table'}],
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

watch(
  [() => props.graph, () => props.layoutMode],
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
  chartInstance?.dispose();
  chartInstance = null;
});
</script>

<style scoped>
.er-diagram-shell {
  width: 100%;
  height: 100%;
  min-height: 420px;
  border-radius: 10px;
  background: var(--surface-0, #fff);
  position: relative;
  overflow: hidden;
}

.er-diagram-canvas {
  width: 100%;
  height: 100%;
  min-height: 420px;
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
