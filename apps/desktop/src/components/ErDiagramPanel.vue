<template>
  <div class="er-diagram-shell">
    <div v-if="!optionReady" class="er-diagram-empty">{{ emptyText }}</div>
    <div v-else ref="chartRef" class="er-diagram-canvas" />
  </div>
</template>

<script setup lang="ts">
import type {ErGraphVO, ErRelationVO, ErTableNodeVO} from '../types';
import * as echarts from 'echarts';
import {computed, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue';

const props = defineProps<{
  graph: ErGraphVO | null;
}>();

const chartRef = ref<HTMLElement | null>(null);
let chartInstance: echarts.ECharts | null = null;
let resizeObserver: ResizeObserver | null = null;
const optionReady = ref(false);
const emptyText = ref('No ER graph data');

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

function buildGraphOption(graph: ErGraphVO | null): echarts.EChartsOption | null {
  if (!graph || !graph.tables?.length) {
    emptyText.value = 'No ER graph data';
    return null;
  }
  const nodeData = graph.tables.map((table, index) => ({
    id: table.tableName,
    name: table.tableName,
    value: buildNodeLabel(table),
    symbolSize: 84 + Math.min(56, (table.columns?.length || 0) * 2),
    category: 0,
    draggable: true,
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
      lineHeight: 14,
      width: 220,
      overflow: 'truncate' as const,
      padding: [8, 10, 8, 10],
    },
    x: (index % 5) * 260,
    y: Math.floor(index / 5) * 220,
  }));

  const fkRelations = graph.foreignKeyRelations || [];
  const aiRelations = graph.aiRelations || [];
  const allRelations = [...fkRelations, ...aiRelations];
  const links = allRelations.map((relation) => {
    const isAi = (relation.relationType || '').toUpperCase() === 'AI_INFERRED';
    const lineType = isAi ? ('dashed' as const) : ('solid' as const);
    return {
      source: relation.sourceTable,
      target: relation.targetTable,
      value: 1,
      name: `${relation.sourceColumn || ''} -> ${relation.targetColumn || ''}`,
      label: {
        show: isAi,
        formatter: edgeLabel(relation),
        color: isAi ? '#8d6d1f' : '#2f5fcc',
        fontSize: 10,
      },
      lineStyle: {
        type: lineType,
        width: isAi ? 1.7 : 2,
        color: isAi ? '#b8912e' : '#3b82f6',
        curveness: isAi ? 0.2 : 0.08,
      },
      emphasis: {
        lineStyle: {
          width: isAi ? 2.2 : 2.6,
        },
      },
      relation,
    };
  });

  return {
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: (params: any) => {
        if (params?.dataType === 'edge') {
          const relation = params?.data?.relation as ErRelationVO | undefined;
          if (!relation) {
            return params?.name || '';
          }
          const relationType = (relation.relationType || '').toUpperCase() === 'AI_INFERRED'
            ? 'AI'
            : 'FK';
          const confidence = relation.confidence == null
            ? ''
            : ` (${Math.round(Math.max(0, Math.min(1, Number(relation.confidence))) * 100)}%)`;
          const reason = (relation.reason || '').trim();
          return [
            `${relation.sourceTable}.${relation.sourceColumn} -> ${relation.targetTable}.${relation.targetColumn}`,
            `${relationType}${confidence}`,
            reason || '',
          ].filter((x) => !!x).join('<br/>');
        }
        const table = params?.data as {name?: string} | undefined;
        return table?.name || '';
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
        layout: 'force',
        roam: true,
        draggable: true,
        force: {
          repulsion: 560,
          edgeLength: [130, 220],
          gravity: 0.07,
        },
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
  const option = buildGraphOption(props.graph);
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
  () => props.graph,
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
        chartInstance?.resize();
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
  width: 100%;
  height: 100%;
  min-height: 420px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-2, #5f6f87);
  font-size: 12px;
}
</style>
