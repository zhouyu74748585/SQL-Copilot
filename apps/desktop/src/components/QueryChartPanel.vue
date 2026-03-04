<template>
  <div class="query-chart-shell">
    <div v-if="!optionReady" class="query-chart-empty">{{ emptyText }}</div>
    <div v-else ref="chartRef" class="query-chart-canvas" />
  </div>
</template>

<script setup lang="ts">
import type { ChartConfigVO } from '../types';
import * as echarts from 'echarts';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';

type ChartRow = Record<string, string | null>;

const props = withDefaults(defineProps<{
  rows: ChartRow[];
  config: ChartConfigVO | null;
  maxPoints?: number;
}>(), {
  maxPoints: 5000,
});

const chartRef = ref<HTMLElement | null>(null);
let chartInstance: echarts.ECharts | null = null;
let resizeObserver: ResizeObserver | null = null;
const optionReady = ref(false);
const emptyText = ref('暂无可渲染图表，请先生成图表。');

function parseNumber(value: string | null | undefined): number | null {
  if (value == null) {
    return null;
  }
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function sortedRows(rows: ChartRow[], config: ChartConfigVO): ChartRow[] {
  const limited = rows.slice(0, props.maxPoints);
  const sortField = (config.sortField || '').trim();
  const sortDirection = (config.sortDirection || 'NONE').toUpperCase();
  if (!sortField || sortDirection === 'NONE') {
    return limited;
  }
  const copied = [...limited];
  copied.sort((a, b) => {
    const av = a[sortField];
    const bv = b[sortField];
    const an = parseNumber(av);
    const bn = parseNumber(bv);
    let compareResult = 0;
    if (an != null && bn != null) {
      compareResult = an - bn;
    } else {
      compareResult = String(av ?? '').localeCompare(String(bv ?? ''));
    }
    return sortDirection === 'DESC' ? -compareResult : compareResult;
  });
  return copied;
}

function buildChartOption(rows: ChartRow[], config: ChartConfigVO): echarts.EChartsOption | null {
  const chartType = (config.chartType || '').toUpperCase();
  const dataRows = sortedRows(rows, config);
  if (!dataRows.length) {
    emptyText.value = '结果集为空，无法生成图表。';
    return null;
  }

  if (chartType === 'PIE') {
    const categoryField = (config.categoryField || '').trim();
    const valueField = (config.valueField || '').trim();
    if (!categoryField || !valueField) {
      emptyText.value = '饼图需要分类字段和值字段。';
      return null;
    }
    const pieData = dataRows
      .map((row) => ({
        name: String(row[categoryField] ?? ''),
        value: parseNumber(row[valueField]) ?? 0,
      }))
      .filter((item) => !!item.name);
    if (!pieData.length) {
      emptyText.value = '饼图数据为空，请调整字段。';
      return null;
    }
    return {
      title: { text: config.title || '', left: 'center', top: 10 },
      tooltip: { trigger: 'item' },
      legend: { bottom: 8, left: 'center' },
      series: [
        {
          name: config.description || '数据占比',
          type: 'pie',
          radius: ['36%', '66%'],
          itemStyle: { borderRadius: 6 },
          data: pieData,
        },
      ],
    };
  }

  if (chartType === 'SCATTER') {
    const xField = (config.xField || '').trim();
    const yField = (config.yFields?.[0] || '').trim();
    if (!xField || !yField) {
      emptyText.value = '散点图需要 X 和 Y 字段。';
      return null;
    }
    const scatterData = dataRows
      .map((row) => {
        const x = parseNumber(row[xField]);
        const y = parseNumber(row[yField]);
        return x == null || y == null ? null : [x, y];
      })
      .filter((item): item is [number, number] => Array.isArray(item));
    if (!scatterData.length) {
      emptyText.value = '散点图缺少数值数据，请更换字段。';
      return null;
    }
    return {
      title: { text: config.title || '', left: 'center', top: 10 },
      tooltip: { trigger: 'item' },
      xAxis: { type: 'value', name: xField },
      yAxis: { type: 'value', name: yField },
      series: [
        {
          type: 'scatter',
          name: yField,
          data: scatterData,
          symbolSize: 9,
        },
      ],
    };
  }

  const xField = (config.xField || '').trim();
  const yFields = (config.yFields || []).map((item) => (item || '').trim()).filter((item) => !!item);
  if (!xField || !yFields.length) {
    emptyText.value = '当前图表需要 X 字段和至少一个 Y 字段。';
    return null;
  }
  const xData = dataRows.map((row) => String(row[xField] ?? ''));
  const seriesType: 'bar' | 'line' = chartType === 'BAR' ? 'bar' : 'line';
  const smooth = chartType === 'TREND';
  const series = yFields.map((field) => ({
    type: seriesType,
    name: field,
    smooth,
    showSymbol: !smooth,
    data: dataRows.map((row) => parseNumber(row[field])),
  }));
  return {
    title: { text: config.title || '', left: 'center', top: 10 },
    tooltip: { trigger: 'axis' },
    legend: { top: 36, left: 'center' },
    grid: { left: 56, right: 24, top: 72, bottom: 44 },
    xAxis: {
      type: 'category',
      data: xData,
      name: xField,
      axisLabel: { rotate: xData.length > 12 ? 20 : 0 },
    },
    yAxis: { type: 'value' },
    series,
  };
}

const chartOption = computed(() => {
  if (!props.config) {
    emptyText.value = '暂无可渲染图表，请先生成图表。';
    return null;
  }
  return buildChartOption(props.rows || [], props.config);
});

function ensureChart() {
  if (!chartRef.value) {
    return null;
  }
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value);
  }
  return chartInstance;
}

function renderChart() {
  const option = chartOption.value;
  optionReady.value = !!option;
  if (!option) {
    if (chartInstance) {
      chartInstance.clear();
    }
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
  renderChart();
  await nextTick();
  await waitFrame();
  if (!chartInstance || !optionReady.value) {
    return '';
  }
  const pixelRatio = Number.isFinite(options?.pixelRatio)
    ? Math.max(1, Math.min(Number(options?.pixelRatio), 3))
    : 2;
  const backgroundColor = (options?.backgroundColor || '#ffffff').trim() || '#ffffff';
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
  () => [props.rows, props.config],
  () => {
    nextTick(() => {
      renderChart();
    });
  },
  { deep: true },
);

onMounted(() => {
  nextTick(() => {
    renderChart();
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
.query-chart-shell {
  width: 100%;
  height: 100%;
  min-height: 260px;
  border-radius: 8px;
  background: var(--surface-0, #fff);
  position: relative;
  overflow: hidden;
}

.query-chart-canvas {
  width: 100%;
  height: 100%;
  min-height: 260px;
}

.query-chart-empty {
  height: 100%;
  min-height: 260px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-2, #5f6f87);
  font-size: 12px;
  padding: 12px;
  text-align: center;
}
</style>
