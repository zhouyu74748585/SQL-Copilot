<template>
  <div class="table-data-virtual-grid" data-testid="studio-table-data-grid">
    <div class="table-data-virtual-grid-header">
      <div
        class="table-data-virtual-grid-header-row"
        :style="{
          width: `${gridWidth}px`,
          gridTemplateColumns,
          transform: `translateX(-${scrollLeft}px)`,
        }"
      >
        <div
          v-for="column in columns"
          :key="column.key"
          class="table-data-virtual-grid-header-cell"
          :title="String(column.title || '')"
        >
          <div class="table-data-virtual-grid-header-cell-inner">
            <span class="table-data-virtual-grid-header-title">{{ column.title }}</span>
            <span v-if="quickSortEnabled && sortDirectionForColumnValue(String(column.dataIndex || ''))" class="table-data-virtual-grid-sort-indicator">
              {{ sortDirectionForColumnValue(String(column.dataIndex || '')) === 'ASC' ? '↑' : '↓' }}
            </span>
            <a-dropdown v-if="quickSortEnabled" :trigger="['click']">
              <button class="table-data-virtual-grid-sort-btn" type="button">
                <down-outlined />
              </button>
              <template #overlay>
                <div class="table-data-virtual-grid-sort-menu" @click.stop>
                  <button type="button" class="table-data-virtual-grid-sort-menu-item" @click="emitQuickSort(String(column.dataIndex || ''), 'ASC')">
                    {{ quickSortAscText }}
                  </button>
                  <button type="button" class="table-data-virtual-grid-sort-menu-item" @click="emitQuickSort(String(column.dataIndex || ''), 'DESC')">
                    {{ quickSortDescText }}
                  </button>
                  <button type="button" class="table-data-virtual-grid-sort-menu-item" @click="emitQuickSort(String(column.dataIndex || ''), 'NONE')">
                    {{ quickSortClearText }}
                  </button>
                </div>
              </template>
            </a-dropdown>
          </div>
          <div class="table-data-virtual-grid-resize-handle" @mousedown.stop.prevent="startColumnResize($event, column)" />
        </div>
      </div>
    </div>

    <div
      ref="bodyRef"
      class="table-data-virtual-grid-body"
      @scroll="handleBodyScroll"
    >
      <div
        class="table-data-virtual-grid-spacer"
        :style="{
          height: `${totalHeight}px`,
          width: `${gridWidth}px`,
        }"
      >
        <div
          class="table-data-virtual-grid-row-layer"
          :style="{ transform: `translateY(${offsetTop}px)` }"
        >
          <div
            v-for="row in visibleRows"
            :key="row.__rowKey"
            class="table-data-virtual-grid-row"
            :class="{ 'is-selected': row.__rowKey === selectedRowKey }"
            :style="{ gridTemplateColumns }"
            :data-testid="rowTestId(row.__rowKey)"
            @click="emit('select-row', row.__rowKey)"
          >
            <div
              v-for="column in columns"
              :key="`${row.__rowKey}::${column.key}`"
              class="table-data-virtual-grid-cell"
              :class="{ 'is-readonly': isReadonlyColumn(String(column.dataIndex || '')) }"
              :data-testid="cellTestId(row.__rowKey, String(column.dataIndex || ''))"
              @dblclick.stop="emit('start-edit', row.__rowKey, String(column.dataIndex || ''))"
            >
              <template v-if="isCellEditing(row.__rowKey, String(column.dataIndex || ''))">
                <a-date-picker
                  v-if="columnEditorType(String(column.dataIndex || '')) === 'date'"
                  size="small"
                  style="width: 100%"
                  value-format="YYYY-MM-DD"
                  :value="row[String(column.dataIndex || '')] || undefined"
                  @update:value="(value: string | null) => handleCellValueChange(row.__rowKey, String(column.dataIndex || ''), value)"
                  @blur="emit('stop-edit')"
                />
                <a-date-picker
                  v-else-if="columnEditorType(String(column.dataIndex || '')) === 'datetime'"
                  size="small"
                  style="width: 100%"
                  show-time
                  format="YYYY-MM-DD HH:mm:ss"
                  value-format="YYYY-MM-DD HH:mm:ss"
                  :value="row[String(column.dataIndex || '')] || undefined"
                  @update:value="(value: string | null) => handleCellValueChange(row.__rowKey, String(column.dataIndex || ''), value)"
                  @blur="emit('stop-edit')"
                />
                <a-time-picker
                  v-else-if="columnEditorType(String(column.dataIndex || '')) === 'time'"
                  size="small"
                  style="width: 100%"
                  format="HH:mm:ss"
                  value-format="HH:mm:ss"
                  :value="row[String(column.dataIndex || '')] || undefined"
                  @update:value="(value: string | null) => handleCellValueChange(row.__rowKey, String(column.dataIndex || ''), value)"
                  @blur="emit('stop-edit')"
                />
                <a-input
                  v-else
                  size="small"
                  :value="row[String(column.dataIndex || '')] ?? ''"
                  @update:value="(value: string | number) => emit('update-cell', row.__rowKey, String(column.dataIndex || ''), normalizeInputValue(value))"
                  @pressEnter="emit('stop-edit')"
                  @blur="emit('stop-edit')"
                />
              </template>
              <template v-else>
                {{ row[String(column.dataIndex || '')] ?? '' }}
              </template>
            </div>
          </div>
        </div>
      </div>

      <div v-if="!rows.length" class="table-data-virtual-grid-empty">
        {{ emptyText }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {DownOutlined} from '@ant-design/icons-vue';
import {computed, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue';
import type {PropType} from 'vue';
import {translateText, useAppI18n} from '../../../i18n';

type TableDataEditorType = 'text' | 'date' | 'datetime' | 'time';
type TableDataQuickSortDirection = 'ASC' | 'DESC' | 'NONE';

interface TableDataDisplayColumn {
  title: string;
  dataIndex: string;
  key: string;
  width: number;
  ellipsis: boolean;
  columnType?: string;
  columnComment?: string;
}

type TableDataDisplayRow = Record<string, string | null> & {
  __rowKey: string;
  __rowState: string;
};

const props = defineProps({
  tab: {
    type: Object as PropType<{
      key: string;
      editable: boolean;
      selectedRowKey: string;
      editingCellKey: string;
      pageNo: number;
      pageSize: number;
    }>,
    required: true,
  },
  columns: {
    type: Array as PropType<TableDataDisplayColumn[]>,
    required: true,
  },
  rows: {
    type: Array as PropType<TableDataDisplayRow[]>,
    required: true,
  },
  scrollX: {
    type: Number,
    required: true,
  },
  scrollY: {
    type: Number,
    required: true,
  },
  resetKey: {
    type: String,
    required: true,
  },
  isPrimaryKeyColumn: {
    type: Function as PropType<(columnName: string) => boolean>,
    required: true,
  },
  columnEditorType: {
    type: Function as PropType<(columnName: string) => TableDataEditorType>,
    required: true,
  },
  quickSortEnabled: {
    type: Boolean,
    default: false,
  },
  sortDirectionForColumn: {
    type: Function as PropType<(columnName: string) => 'ASC' | 'DESC' | ''>,
    default: undefined,
  },
});

const emit = defineEmits<{
  'select-row': [rowKey: string];
  'start-edit': [rowKey: string, columnName: string];
  'stop-edit': [];
  'update-cell': [rowKey: string, columnName: string, value: string | null];
  'resize-column': [columnName: string, width: number];
  'quick-sort': [columnName: string, direction: TableDataQuickSortDirection];
}>();

const bodyRef = ref<HTMLDivElement | null>(null);
const scrollTop = ref(0);
const scrollLeft = ref(0);
const measuredBodyHeight = ref(0);
const {currentLocale} = useAppI18n();
const resizingColumnKey = ref('');
const resizingStartX = ref(0);
const resizingStartWidth = ref(0);

const ROW_HEIGHT = 28;
const OVERSCAN_COUNT = 8;
const MIN_COLUMN_WIDTH = 80;
const MAX_COLUMN_WIDTH = 640;

let bodyResizeObserver: ResizeObserver | null = null;

const bodyHeight = computed(() => Math.max(160, measuredBodyHeight.value || props.scrollY));
const gridWidth = computed(() => Math.max(props.scrollX, 960));
const gridTemplateColumns = computed(() => props.columns.map((column) => `${column.width || 132}px`).join(' '));
const totalHeight = computed(() => props.rows.length * ROW_HEIGHT);
const visibleStart = computed(() => Math.max(0, Math.floor(scrollTop.value / ROW_HEIGHT) - OVERSCAN_COUNT));
const visibleEnd = computed(() =>
  Math.min(
    props.rows.length,
    Math.ceil((scrollTop.value + bodyHeight.value) / ROW_HEIGHT) + OVERSCAN_COUNT,
  ),
);
const offsetTop = computed(() => visibleStart.value * ROW_HEIGHT);
const visibleRows = computed(() => props.rows.slice(visibleStart.value, visibleEnd.value));
const selectedRowKey = computed(() => props.tab.selectedRowKey);
const emptyText = computed(() => {
  void currentLocale.value;
  return translateText('暂无数据');
});
const quickSortAscText = computed(() => {
  void currentLocale.value;
  return translateText('正序');
});
const quickSortDescText = computed(() => {
  void currentLocale.value;
  return translateText('倒序');
});
const quickSortClearText = computed(() => {
  void currentLocale.value;
  return translateText('移除排序');
});

function normalizeTestIdSegment(value: string | null | undefined) {
  return String(value || '')
    .trim()
    .replace(/[^A-Za-z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    || 'empty';
}

function rowTestId(rowKey: string) {
  return `studio-table-data-row-${normalizeTestIdSegment(rowKey)}`;
}

function cellTestId(rowKey: string, columnName: string) {
  return `studio-table-data-cell-${normalizeTestIdSegment(rowKey)}-${normalizeTestIdSegment(columnName)}`;
}

watch(
  () => props.resetKey,
  async () => {
    scrollTop.value = 0;
    scrollLeft.value = 0;
    await nextTick();
    syncBodyMetrics();
    if (bodyRef.value) {
      bodyRef.value.scrollTop = 0;
      bodyRef.value.scrollLeft = 0;
    }
  },
);

onMounted(async () => {
  await nextTick();
  syncBodyMetrics();
  if (typeof ResizeObserver !== 'undefined' && bodyRef.value) {
    bodyResizeObserver = new ResizeObserver(() => {
      syncBodyMetrics();
    });
    bodyResizeObserver.observe(bodyRef.value);
  }
});

onBeforeUnmount(() => {
  bodyResizeObserver?.disconnect();
  bodyResizeObserver = null;
  stopColumnResize();
});

function handleBodyScroll(event: Event) {
  const target = event.target as HTMLDivElement;
  scrollTop.value = target.scrollTop;
  scrollLeft.value = target.scrollLeft;
}

function syncBodyMetrics() {
  measuredBodyHeight.value = bodyRef.value?.clientHeight || 0;
}

function clampColumnWidth(width: number) {
  const resolved = Number(width || 0);
  if (!Number.isFinite(resolved)) {
    return 132;
  }
  return Math.min(MAX_COLUMN_WIDTH, Math.max(MIN_COLUMN_WIDTH, Math.round(resolved)));
}

function sortDirectionForColumnValue(columnName: string) {
  return props.sortDirectionForColumn?.(columnName) || '';
}

function isReadonlyColumn(columnName: string) {
  return !props.tab.editable || props.isPrimaryKeyColumn(columnName);
}

function isCellEditing(rowKey: string, columnName: string) {
  return props.tab.editingCellKey === `${rowKey}::${columnName}`;
}

function handleCellValueChange(rowKey: string, columnName: string, value: string | null) {
  emit('update-cell', rowKey, columnName, value ? String(value) : null);
  emit('stop-edit');
}

function emitQuickSort(columnName: string, direction: TableDataQuickSortDirection) {
  emit('quick-sort', columnName, direction);
}

function startColumnResize(event: MouseEvent, column: TableDataDisplayColumn) {
  resizingColumnKey.value = String(column.dataIndex || column.key || '');
  resizingStartX.value = event.clientX;
  resizingStartWidth.value = clampColumnWidth(column.width || 132);
  window.addEventListener('mousemove', handleColumnResize);
  window.addEventListener('mouseup', stopColumnResize);
}

function handleColumnResize(event: MouseEvent) {
  if (!resizingColumnKey.value) {
    return;
  }
  const delta = event.clientX - resizingStartX.value;
  emit('resize-column', resizingColumnKey.value, clampColumnWidth(resizingStartWidth.value + delta));
}

function stopColumnResize() {
  resizingColumnKey.value = '';
  window.removeEventListener('mousemove', handleColumnResize);
  window.removeEventListener('mouseup', stopColumnResize);
}

function normalizeInputValue(value: string | number) {
  const normalized = value === '' ? null : String(value);
  return normalized;
}
</script>
