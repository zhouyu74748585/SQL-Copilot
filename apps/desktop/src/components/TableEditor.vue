<template>
  <div class="table-editor">
    <div class="table-editor-toolbar">
      <a-form layout="inline" class="table-editor-form">
        <a-form-item :label="tt('表名')">
          <a-input v-model:value="tableName" size="small" :disabled="tab.mode === 'edit'" style="width: 180px" />
        </a-form-item>
        <a-form-item :label="tt('备注')">
          <a-input v-model:value="tableComment" size="small" style="width: 200px" />
        </a-form-item>
      </a-form>
    </div>

    <a-tabs v-model:activeKey="activeTab" size="small" class="table-editor-tabs">
      <a-tab-pane key="columns" :tab="tt('字段')">
        <div class="panel-head">
          <span>{{ tt('字段定义') }}</span>
          <a-button size="small" type="link" @click="addColumn"><template #icon><plus-outlined /></template>{{ tt('添加字段') }}</a-button>
        </div>
        <div ref="columnsTableHost" class="table-grid-host">
          <a-table
            class="compact-schema-table columns-table"
            :columns="columnColumns"
            :data-source="columns"
            :pagination="false"
            size="small"
            row-key="uuid"
            bordered
            :scroll="{ x: 600, y: columnsTableScrollY }"
            :row-class-name="(record: ColumnRecord) => selectedColumnUuid === record.uuid ? 'selected-row' : ''"
            :expanded-row-keys="expandedColumnKeys"
            :show-expand-column="false"
            :custom-row="columnRowProps"
            @change="handleTableChange"
          >
            <template #bodyCell="{ column, record, index }">
              <div v-if="column.key === 'columnName'" class="column-name-cell" @click="selectColumn(record)">
                <key-outlined v-if="record.primaryKey" class="pk-icon" />
                <a-input v-model:value="record.columnName" size="small" />
              </div>
              <a-select
                v-else-if="column.key === 'dataType'"
                v-model:value="record.dataType"
                size="small"
                :options="dataTypeOptions"
                style="width: 100%"
                @change="handleTypeChange(record)"
              />
              <a-input v-else-if="column.key === 'length'" v-model:value="record.columnSize" size="small" style="width: 100%" />
              <a-input-number
                v-else-if="column.key === 'scale'"
                v-model:value="record.decimalDigits"
                size="small"
                :min="0"
                :disabled="!isDecimalType(record.dataType)"
                style="width: 100%"
              />
              <a-input v-else-if="column.key === 'comment'" v-model:value="record.columnComment" size="small" />
              <a-button v-else-if="column.key === 'actions'" size="small" type="link" danger @click="removeColumn(index)">
                <template #icon><delete-outlined /></template>
              </a-button>
            </template>
            <template #expandedRowRender="{ record }">
              <div class="column-inline-detail">
                <div class="column-inline-detail-head">
                  <span class="detail-title">{{ record.columnName || tt('未命名字段') }}</span>
                  <span class="detail-type">{{ typeWithSize(record) }}</span>
                </div>
                <div class="column-inline-detail-grid">
                  <a-checkbox v-model:checked="record.nullable" size="small">{{ tt('允许空值') }}</a-checkbox>
                  <a-checkbox v-model:checked="record.primaryKey" size="small">
                    <span class="detail-checkbox-label"><key-outlined class="pk-icon" />{{ tt('主键字段') }}</span>
                  </a-checkbox>
                  <a-checkbox v-model:checked="record.autoIncrement" size="small" :disabled="!record.primaryKey">{{ tt('自动递增') }}</a-checkbox>
                  <label class="inline-field">
                    <span class="detail-label">{{ tt('默认值') }}</span>
                    <a-input v-model:value="record.defaultValue" size="small" :disabled="record.defaultCurrentTimestamp" />
                  </label>
                  <a-checkbox
                    v-model:checked="record.defaultCurrentTimestamp"
                    size="small"
                    :disabled="!isTemporalType(record.dataType)"
                    @change="handleTemporalChange(record)"
                  >
                    {{ tt('默认当前时间') }}
                  </a-checkbox>
                  <a-checkbox v-model:checked="record.onUpdateCurrentTimestamp" size="small" :disabled="!isTemporalType(record.dataType)">
                    {{ tt('更新时自动刷新') }}
                  </a-checkbox>
                </div>
              </div>
            </template>
          </a-table>
        </div>
      </a-tab-pane>

      <a-tab-pane key="indexes" :tab="tt('索引')">
        <div class="panel-head">
          <span>{{ tt('索引管理') }}</span>
          <a-button size="small" type="link" @click="addIndex"><template #icon><plus-outlined /></template>{{ tt('添加索引') }}</a-button>
        </div>
        <div ref="indexesTableHost" class="table-grid-host">
          <a-table class="compact-schema-table indexes-table" :columns="indexColumns" :data-source="indexes" :pagination="false" size="small" row-key="uuid" bordered :scroll="{ x: 920, y: indexesTableScrollY }">
            <template #bodyCell="{ column, record, index }">
              <a-input v-if="column.key === 'indexName'" v-model:value="record.indexName" size="small" />
              <a-select v-else-if="column.key === 'indexType'" v-model:value="record.unique" size="small" :options="indexTypeOptions" style="width: 100%" />
              <a-select
                v-else-if="column.key === 'columns'"
                v-model:value="record.columns"
                mode="multiple"
                size="small"
                :options="columnOptions"
                style="width: 100%"
              />
              <a-button v-else-if="column.key === 'actions'" size="small" type="link" danger @click="removeIndex(index)">
                <template #icon><delete-outlined /></template>
              </a-button>
            </template>
          </a-table>
        </div>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useTemplateRef, watch, watchEffect } from 'vue';
import { message } from 'ant-design-vue';
import { DeleteOutlined, FileTextOutlined, KeyOutlined, PlayCircleOutlined, PlusOutlined } from '@ant-design/icons-vue';
import { postApi } from '../api/client';
import {translateText, useAppI18n} from '../i18n';
import type { TableDetailVO } from '../types';

type Mode = 'create' | 'edit';
interface ColumnRecord {
  uuid: string;
  columnName: string;
  dataType: string;
  columnSize: number | null;
  decimalDigits: number | null;
  defaultValue: string;
  autoIncrement: boolean;
  nullable: boolean;
  columnComment: string;
  primaryKey: boolean;
  indexed: boolean;
  defaultCurrentTimestamp: boolean;
  onUpdateCurrentTimestamp: boolean;
}
interface IndexRecord { uuid: string; indexName: string; unique: boolean; columns: string[]; }
interface Draft { tableName: string; tableComment: string; columns: ColumnRecord[]; indexes: IndexRecord[]; }
interface Tab {
  key: string;
  title: string;
  connectionId: number;
  databaseName: string;
  tableName: string;
  dbType: string;
  mode: Mode;
  tableDetail: TableDetailVO | null;
  draft: Draft | null;
  baselineDraft: Draft | null;
  previewSql: string;
  canSave: boolean;
  dirty: boolean;
  loading: boolean;
  saved: boolean;
  createdAt: number;
  updatedAt: number;
}
interface ChangePayload { draft: Draft; previewSql: string; canSave: boolean; dirty: boolean; }
const props = defineProps<{ tab: Tab }>();
const emit = defineEmits<{
  (e: 'save', tab: Tab): void;
  (e: 'change', payload: ChangePayload): void;
  (e: 'close'): void;
}>();

const activeTab = ref<'columns' | 'indexes'>('columns');
const tableName = ref('');
const tableComment = ref('');
const columns = ref<ColumnRecord[]>([]);
const indexes = ref<IndexRecord[]>([]);
const baseline = ref<Draft | null>(null);
const saving = ref(false);
const selectedColumnUuid = ref<string | null>(null);
const columnsTableHost = useTemplateRef<HTMLDivElement>('columnsTableHost');
const indexesTableHost = useTemplateRef<HTMLDivElement>('indexesTableHost');
const columnsTableHostHeight = ref(0);
const indexesTableHostHeight = ref(0);
const TABLE_SCROLL_BODY_OFFSET = 54;
let layoutObserver: ResizeObserver | null = null;
const {currentLocale} = useAppI18n();

const expandedColumnKeys = computed(() => (selectedColumnUuid.value ? [selectedColumnUuid.value] : []));
const columnsTableScrollY = computed(() => resolveTableScrollY(columnsTableHostHeight.value));
const indexesTableScrollY = computed(() => resolveTableScrollY(indexesTableHostHeight.value));

function tt(text: string) {
  void currentLocale.value;
  return translateText(text);
}

const dataTypeOptions = [
  'INT', 'BIGINT', 'VARCHAR', 'TEXT', 'DECIMAL', 'DATETIME', 'TIMESTAMP', 'DATE', 'TIME', 'JSON', 'BOOLEAN', 'DOUBLE', 'FLOAT', 'CHAR', 'BLOB',
].map((v) => ({ value: v, label: v }));
const indexTypeOptions = computed(() => [
  { value: false, label: tt('普通索引') },
  { value: true, label: tt('唯一索引') },
]);

const columnColumns = computed(() => [
  { title: tt('字段名'), key: 'columnName', width: 180 },
  { title: tt('类型'), key: 'dataType', width: 120 },
  { title: tt('长度'), key: 'length', width: 70 },
  { title: tt('精度'), key: 'scale', width: 70 },
  { title: tt('备注'), key: 'comment', width: 160 },
  { title: '', key: 'actions', width: 50 },
]);
const indexColumns = computed(() => [
  { title: tt('索引名'), key: 'indexName', width: 220 },
  { title: tt('类型'), key: 'indexType', width: 120 },
  { title: tt('字段'), key: 'columns', width: 500 },
  { title: '', key: 'actions', width: 56 },
]);

const columnOptions = computed(() => {
  const seen = new Set<string>();
  return columns.value
    .map((c) => c.columnName.trim())
    .filter((name) => name && !seen.has(name.toLowerCase()) && seen.add(name.toLowerCase()))
    .map((name) => ({ label: name, value: name }));
});

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
function resolveTableScrollY(hostHeight: number) {
  return Math.max(180, Math.floor(hostHeight - TABLE_SCROLL_BODY_OFFSET));
}
function normalizeIntegerValue(value: unknown) {
  if (value == null) return null;
  const raw = typeof value === 'number' ? String(value) : norm(String(value));
  if (!raw) return null;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) return null;
  return Math.trunc(parsed);
}
function parseTypeDefinition(dataType: string) {
  const raw = normType(dataType);
  const match = raw.match(/^([A-Z0-9_]+)\s*\((\d+)(?:\s*,\s*(\d+))?\)$/);
  if (!match) return { dataType: raw, columnSize: null as number | null, decimalDigits: null as number | null };
  return {
    dataType: match[1],
    columnSize: Number(match[2]),
    decimalDigits: match[3] != null ? Number(match[3]) : null,
  };
}
function normalizeColumnTypeParts(column: Pick<ColumnRecord, 'dataType' | 'columnSize' | 'decimalDigits'>) {
  const parsed = parseTypeDefinition(column.dataType);
  const dataType = parsed.dataType || 'VARCHAR';
  const decimalDigits = normalizeIntegerValue(column.decimalDigits) ?? parsed.decimalDigits;
  return {
    dataType,
    columnSize: normalizeIntegerValue(column.columnSize) ?? parsed.columnSize,
    decimalDigits: isDecimalType(dataType) ? decimalDigits : null,
  };
}
function sameColumnTypeDefinition(a: Pick<ColumnRecord, 'dataType' | 'columnSize' | 'decimalDigits'>, b: Pick<ColumnRecord, 'dataType' | 'columnSize' | 'decimalDigits'>) {
  const left = normalizeColumnTypeParts(a);
  const right = normalizeColumnTypeParts(b);
  return left.dataType === right.dataType
    && left.columnSize === right.columnSize
    && left.decimalDigits === right.decimalDigits;
}
function normalizeColumnRecord(column: ColumnRecord): ColumnRecord {
  const normalized = normalizeColumnTypeParts(column);
  return {
    ...column,
    dataType: normalized.dataType,
    columnSize: normalized.columnSize,
    decimalDigits: normalized.decimalDigits,
  };
}
function cloneColumns(list: ColumnRecord[]) { return list.map((c) => normalizeColumnRecord({ ...c })); }
function cloneIndexes(list: IndexRecord[]) { return list.map((i) => ({ ...i, columns: [...i.columns] })); }
function cloneDraft(d: Draft): Draft { return { tableName: d.tableName, tableComment: d.tableComment, columns: cloneColumns(d.columns), indexes: cloneIndexes(d.indexes) }; }
function norm(s: string) { return String(s || '').trim(); }
function normType(s: string) { return norm(s).toUpperCase(); }
function idNorm(s: string) { return norm(s).toLowerCase(); }
function isTemporalType(t: string) { const n = normType(t); return n === 'TIMESTAMP' || n === 'DATETIME'; }
function isDecimalType(t: string) { const n = normType(t); return n === 'DECIMAL' || n === 'DOUBLE' || n === 'FLOAT'; }

function emptyColumn(): ColumnRecord {
  return {
    uuid: uuid(), columnName: '', dataType: 'VARCHAR', columnSize: 255, decimalDigits: null, defaultValue: '',
    autoIncrement: false, nullable: true, columnComment: '', primaryKey: false, indexed: false,
    defaultCurrentTimestamp: false, onUpdateCurrentTimestamp: false,
  };
}
function defaultColumns() {
  const c = emptyColumn();
  c.columnName = 'id';
  c.dataType = 'BIGINT';
  c.columnSize = null;
  c.nullable = false;
  c.primaryKey = true;
  c.autoIncrement = true;
  return [c];
}
function emptyIndex(): IndexRecord { return { uuid: uuid(), indexName: '', unique: false, columns: [] }; }
function addColumn() { columns.value.push(emptyColumn()); }
function removeColumn(index: number) {
  const removed = columns.value[index];
  columns.value.splice(index, 1);
  if (removed?.uuid && removed.uuid === selectedColumnUuid.value) {
    selectedColumnUuid.value = columns.value[index]?.uuid || columns.value[index - 1]?.uuid || null;
  }
}
function addIndex() { indexes.value.push(emptyIndex()); }
function removeIndex(index: number) { indexes.value.splice(index, 1); }
function handleTypeChange(c: ColumnRecord) {
  const normalized = normalizeColumnTypeParts(c);
  c.dataType = normalized.dataType;
  c.columnSize = normalized.columnSize;
  c.decimalDigits = normalized.decimalDigits;
  if (!isDecimalType(c.dataType)) c.decimalDigits = null;
  if (!isTemporalType(c.dataType)) {
    c.defaultCurrentTimestamp = false;
    c.onUpdateCurrentTimestamp = false;
  }
}
function handleTemporalChange(c: ColumnRecord) {
  if (c.defaultCurrentTimestamp) c.defaultValue = '';
}

function selectColumn(record: ColumnRecord) {
  selectedColumnUuid.value = record.uuid;
}

function columnRowProps(record: ColumnRecord) {
  return {
    onClick: () => {
      selectColumn(record);
    },
  };
}

function handleTableChange() {
  // 保持选中状态
}

function quoteId(name: string) {
  const n = norm(name);
  if (!n) return '';
  const db = normType(props.tab.dbType || 'MYSQL');
  if (db === 'SQLSERVER') return `[${n}]`;
  if (db === 'POSTGRESQL' || db === 'ORACLE') return `"${n}"`;
  return `\`${n}\``;
}
function quoteStr(v: string) { return `'${norm(v).replace(/'/g, "''")}'`; }

function indexListValid(list: IndexRecord[]) {
  return list
    .map((i) => ({ ...i, indexName: norm(i.indexName), columns: i.columns.map((c) => norm(c)).filter((c) => !!c) }))
    .filter((i) => !!i.indexName && i.columns.length > 0);
}

const canSave = computed(() => {
  if (!norm(tableName.value) || !columns.value.length) return false;
  const colNames = new Set<string>();
  for (const c of columns.value) {
    if (!norm(c.columnName) || !norm(c.dataType)) return false;
    const key = idNorm(c.columnName);
    if (colNames.has(key)) return false;
    colNames.add(key);
  }
  const idxNames = new Set<string>();
  for (const i of indexListValid(indexes.value)) {
    const key = idNorm(i.indexName);
    if (idxNames.has(key)) return false;
    idxNames.add(key);
  }
  return true;
});

function typeWithSize(c: ColumnRecord) {
  const normalized = normalizeColumnTypeParts(c);
  const t = normalized.dataType;
  if (/\(.+\)/.test(t)) return t;
  const noSize = new Set(['INT', 'TEXT', 'DATE', 'DATETIME', 'TIMESTAMP', 'TIME', 'JSON', 'BOOLEAN', 'BLOB']);
  if (noSize.has(t)) return t;
  if (normalized.columnSize != null && normalized.columnSize > 0) {
    if (isDecimalType(t) && normalized.decimalDigits != null && normalized.decimalDigits >= 0) return `${t}(${normalized.columnSize},${normalized.decimalDigits})`;
    return `${t}(${normalized.columnSize})`;
  }
  return t || 'VARCHAR';
}
function defaultSql(c: ColumnRecord) {
  if (c.defaultCurrentTimestamp) return 'CURRENT_TIMESTAMP';
  const raw = norm(c.defaultValue);
  if (!raw) return '';
  if (/^'.*'$/.test(raw) || /^".*"$/.test(raw) || /^-?\d+(\.\d+)?$/.test(raw)) return raw;
  const upper = raw.toUpperCase();
  if (upper === 'NULL' || upper === 'CURRENT_TIMESTAMP' || upper === 'CURRENT_TIMESTAMP()' || upper === 'NOW()') return raw;
  if (/^[A-Z_][A-Z0-9_]*\s*\([^)]*\)$/i.test(raw)) return raw;
  return quoteStr(raw);
}
function columnSql(c: ColumnRecord) {
  const seg: string[] = [`${quoteId(c.columnName)} ${typeWithSize(c)}`];
  if (!c.nullable) seg.push('NOT NULL');
  if (c.autoIncrement && c.primaryKey) seg.push('AUTO_INCREMENT');
  const d = defaultSql(c);
  if (d) seg.push(`DEFAULT ${d}`);
  if (c.onUpdateCurrentTimestamp) seg.push('ON UPDATE CURRENT_TIMESTAMP');
  if (norm(c.columnComment)) seg.push(`COMMENT ${quoteStr(c.columnComment)}`);
  return seg.join(' ');
}
function indexSql(i: IndexRecord, alter: boolean) {
  const cols = i.columns.map((c) => quoteId(c)).join(', ');
  if (!cols) return '';
  if (alter) return `${i.unique ? 'ADD UNIQUE INDEX' : 'ADD INDEX'} ${quoteId(i.indexName)} (${cols})`;
  return `${i.unique ? 'UNIQUE KEY' : 'KEY'} ${quoteId(i.indexName)} (${cols})`;
}

function draftNow(): Draft {
  return { tableName: norm(tableName.value), tableComment: norm(tableComment.value), columns: cloneColumns(columns.value), indexes: cloneIndexes(indexes.value) };
}
function colEq(a: ColumnRecord, b: ColumnRecord) {
  return idNorm(a.columnName) === idNorm(b.columnName)
    && sameColumnTypeDefinition(a, b)
    && !!a.nullable === !!b.nullable
    && !!a.autoIncrement === !!b.autoIncrement
    && (a.defaultCurrentTimestamp ? '__CTS__' : norm(a.defaultValue)) === (b.defaultCurrentTimestamp ? '__CTS__' : norm(b.defaultValue))
    && !!a.onUpdateCurrentTimestamp === !!b.onUpdateCurrentTimestamp
    && norm(a.columnComment) === norm(b.columnComment)
    && !!a.primaryKey === !!b.primaryKey;
}
function idxSig(i: IndexRecord) { return `${i.unique ? 1 : 0}|${i.columns.map((c) => idNorm(c)).join(',')}`; }
function draftEq(a: Draft, b: Draft) {
  if (idNorm(a.tableName) !== idNorm(b.tableName) || norm(a.tableComment) !== norm(b.tableComment)) return false;
  const ac = [...a.columns].sort((x, y) => x.columnName.localeCompare(y.columnName));
  const bc = [...b.columns].sort((x, y) => x.columnName.localeCompare(y.columnName));
  if (ac.length !== bc.length) return false;
  for (let i = 0; i < ac.length; i += 1) if (!colEq(ac[i], bc[i])) return false;
  const ai = indexListValid(a.indexes).sort((x, y) => x.indexName.localeCompare(y.indexName));
  const bi = indexListValid(b.indexes).sort((x, y) => x.indexName.localeCompare(y.indexName));
  if (ai.length !== bi.length) return false;
  for (let i = 0; i < ai.length; i += 1) if (idNorm(ai[i].indexName) !== idNorm(bi[i].indexName) || idxSig(ai[i]) !== idxSig(bi[i])) return false;
  return true;
}

function createSql() {
  if (!norm(tableName.value)) return `-- ${tt('请先输入表名')}`;
  for (const c of columns.value) if (!norm(c.columnName) || !norm(c.dataType)) return `-- ${tt('请先完善字段定义')}`;
  const defs = columns.value.map((c) => `  ${columnSql(c)}`);
  const pks = columns.value.filter((c) => c.primaryKey).map((c) => quoteId(c.columnName));
  if (pks.length) defs.push(`  PRIMARY KEY (${pks.join(', ')})`);
  indexListValid(indexes.value).forEach((i) => { const s = indexSql(i, false); if (s) defs.push(`  ${s}`); });
  let tail = ')';
  if (norm(tableComment.value)) tail += ` COMMENT=${quoteStr(tableComment.value)}`;
  tail += ';';
  return [`CREATE TABLE ${quoteId(tableName.value)} (`, defs.join(',\n'), tail].join('\n');
}

function alterSql() {
  if (!baseline.value) return `-- ${tt('等待加载原始表结构')}`;
  for (const c of columns.value) if (!norm(c.columnName) || !norm(c.dataType)) return `-- ${tt('请先完善字段定义')}`;
  const acts: string[] = [];
  const oldMap = new Map(baseline.value.columns.map((c) => [idNorm(c.columnName), c]));
  const newMap = new Map(columns.value.map((c) => [idNorm(c.columnName), c]));
  for (const [k, oldC] of oldMap) if (!newMap.has(k)) acts.push(`DROP COLUMN ${quoteId(oldC.columnName)}`);
  for (const [k, newC] of newMap) {
    const oldC = oldMap.get(k);
    if (!oldC) acts.push(`ADD COLUMN ${columnSql(newC)}`);
    else if (!colEq(oldC, newC)) acts.push(`MODIFY COLUMN ${columnSql(newC)}`);
  }
  const oldPk = baseline.value.columns.filter((c) => c.primaryKey).map((c) => idNorm(c.columnName)).join(',');
  const newPk = columns.value.filter((c) => c.primaryKey).map((c) => idNorm(c.columnName)).join(',');
  if (oldPk !== newPk) {
    if (oldPk) acts.push('DROP PRIMARY KEY');
    const cols = columns.value.filter((c) => c.primaryKey).map((c) => quoteId(c.columnName)).join(', ');
    if (cols) acts.push(`ADD PRIMARY KEY (${cols})`);
  }
  const oldIdx = new Map(indexListValid(baseline.value.indexes).map((i) => [idNorm(i.indexName), i]));
  const newIdx = new Map(indexListValid(indexes.value).map((i) => [idNorm(i.indexName), i]));
  for (const [k, oldI] of oldIdx) {
    const next = newIdx.get(k);
    if (!next || idxSig(oldI) !== idxSig(next)) acts.push(`DROP INDEX ${quoteId(oldI.indexName)}`);
  }
  for (const [k, newI] of newIdx) {
    const oldI = oldIdx.get(k);
    if (!oldI || idxSig(oldI) !== idxSig(newI)) acts.push(indexSql(newI, true));
  }
  if (norm(baseline.value.tableComment) !== norm(tableComment.value)) acts.push(`COMMENT = ${quoteStr(tableComment.value)}`);
  if (!acts.length) return `-- ${tt('未检测到结构变更')}`;
  return `ALTER TABLE ${quoteId(tableName.value)}\n  ${acts.join(',\n  ')};`;
}

const previewSql = computed(() => (props.tab.mode === 'create' ? createSql() : alterSql()));
const dirty = computed(() => (props.tab.mode === 'create' ? true : !!baseline.value && !draftEq(draftNow(), baseline.value)));

function emitChange() {
  if (props.tab.mode === 'edit' && !props.tab.tableDetail && !baseline.value) {
    return;
  }
  emit('change', { draft: draftNow(), previewSql: previewSql.value, canSave: canSave.value, dirty: dirty.value });
}

function syncTableHostHeights() {
  columnsTableHostHeight.value = columnsTableHost.value?.clientHeight ?? 0;
  indexesTableHostHeight.value = indexesTableHost.value?.clientHeight ?? 0;
}

function observeTableHosts() {
  if (!layoutObserver) {
    return;
  }
  if (columnsTableHost.value) {
    layoutObserver.observe(columnsTableHost.value);
  }
  if (indexesTableHost.value) {
    layoutObserver.observe(indexesTableHost.value);
  }
}

function loadFromTab() {
  activeTab.value = 'columns';
  selectedColumnUuid.value = null;
  if (props.tab.mode === 'edit') {
    // 编辑模式下，需要等待 tableDetail 加载完成
    if (!props.tab.tableDetail) {
      tableName.value = props.tab.tableName || '';
      tableComment.value = '';
      columns.value = [];
      indexes.value = [];
      baseline.value = null;
      return;
    }
    if (!props.tab.baselineDraft) {
      props.tab.baselineDraft = {
        tableName: props.tab.tableDetail.tableName || '',
        tableComment: props.tab.tableDetail.tableComment || '',
        columns: (props.tab.tableDetail.columns || []).map((c) => ({
          uuid: uuid(), columnName: c.columnName || '', dataType: normType(c.dataType || 'VARCHAR'), columnSize: c.columnSize ?? null,
          decimalDigits: c.decimalDigits ?? null, defaultValue: c.defaultCurrentTimestamp ? '' : norm(c.defaultValue || ''),
          autoIncrement: c.autoIncrement === true, nullable: c.nullable !== false, columnComment: c.columnComment || '',
          primaryKey: c.primaryKey === true, indexed: c.indexed === true, defaultCurrentTimestamp: c.defaultCurrentTimestamp === true,
          onUpdateCurrentTimestamp: c.onUpdateCurrentTimestamp === true,
        })),
        indexes: (props.tab.tableDetail.indexes || []).map((i) => ({ uuid: uuid(), indexName: i.indexName || '', unique: i.unique === true, columns: [...(i.columns || [])] })),
      };
    }
    baseline.value = cloneDraft(props.tab.baselineDraft);
    const src = props.tab.draft ? cloneDraft(props.tab.draft) : cloneDraft(props.tab.baselineDraft);
    tableName.value = src.tableName; tableComment.value = src.tableComment; columns.value = cloneColumns(src.columns); indexes.value = cloneIndexes(src.indexes);
    emitChange();
    return;
  }
  const src = props.tab.draft ? cloneDraft(props.tab.draft) : { tableName: '', tableComment: '', columns: defaultColumns(), indexes: [] };
  baseline.value = null;
  tableName.value = src.tableName; tableComment.value = src.tableComment; columns.value = cloneColumns(src.columns); indexes.value = cloneIndexes(src.indexes);
  emitChange();
}

// 记录上次处理的 tableDetail 引用，避免重复加载
let lastProcessedTableDetail: TableDetailVO | null = null;

// 使用 watchEffect 自动追踪依赖变化
watchEffect(() => {
  const currentDetail = props.tab.tableDetail;
  const currentKey = props.tab.key;
  const mode = props.tab.mode;
  
  // 编辑模式下，当 tableDetail 从 null 变为有值时加载
  if (mode === 'edit' && currentDetail && currentDetail !== lastProcessedTableDetail) {
    lastProcessedTableDetail = currentDetail;
    loadFromTab();
  }
});

// 监听 tab 的 key 变化（切换 tab 时重新加载）
watch(() => props.tab.key, (newKey, oldKey) => {
  if (newKey !== oldKey) {
    lastProcessedTableDetail = null;
    loadFromTab();
  }
}, { immediate: true });
watch(() => columns.value, (list) => {
  list.forEach((c) => {
    if (!c.primaryKey) c.autoIncrement = false;
    if (!isTemporalType(c.dataType)) { c.defaultCurrentTimestamp = false; c.onUpdateCurrentTimestamp = false; }
    if (!isDecimalType(c.dataType)) c.decimalDigits = null;
    if (c.defaultCurrentTimestamp) c.defaultValue = '';
  });
  if (selectedColumnUuid.value && !list.some((c) => c.uuid === selectedColumnUuid.value)) {
    selectedColumnUuid.value = list[0]?.uuid || null;
  }
  const names = new Set(list.map((c) => norm(c.columnName)).filter((n) => !!n));
  indexes.value.forEach((i) => { i.columns = i.columns.filter((n) => names.has(norm(n))); });
}, { deep: true });
watch(() => [tableName.value, tableComment.value, columns.value, indexes.value], () => emitChange(), { deep: true });
watch(activeTab, async () => {
  await nextTick();
  observeTableHosts();
  syncTableHostHeights();
});

onMounted(() => {
  syncTableHostHeights();
  if (typeof ResizeObserver !== 'undefined') {
    layoutObserver = new ResizeObserver(() => {
      syncTableHostHeights();
    });
    observeTableHosts();
  }
  window.addEventListener('resize', syncTableHostHeights);
});

onBeforeUnmount(() => {
  layoutObserver?.disconnect();
  layoutObserver = null;
  window.removeEventListener('resize', syncTableHostHeights);
});

</script>

<style scoped>
.table-editor { display: flex; flex: 1 1 auto; flex-direction: column; height: 100%; min-height: 0; }
.table-editor-toolbar { display: flex; justify-content: space-between; gap: 10px; padding: 8px 10px; border-bottom: 1px solid var(--line, #e8e8e8); background: var(--surface-1, #ebebeb); }
.table-editor-form { flex: 1; min-width: 0; }
.table-editor-tabs { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.table-editor-tabs :deep(.ant-tabs-nav) { margin-bottom: 8px; }
.table-editor-tabs :deep(.ant-tabs-tab) { padding: 4px 12px; }
.table-editor-tabs :deep(.ant-tabs-content-holder),
.table-editor-tabs :deep(.ant-tabs-content),
.table-editor-tabs :deep(.ant-tabs-tabpane-active) { flex: 1; min-height: 0; height: 100%; display: flex; flex-direction: column; }
.table-editor-tabs :deep(.ant-tabs-tabpane) { height: 100%; display: flex; flex-direction: column; min-height: 0; }
.table-editor-tabs :deep(.ant-table-wrapper) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.table-editor-tabs :deep(.ant-spin-nested-loading),
.table-editor-tabs :deep(.ant-spin-container),
.table-editor-tabs :deep(.ant-table-container) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.table-editor-tabs :deep(.compact-schema-table) { font-size: 12px; }
.table-editor-tabs :deep(.compact-schema-table .ant-table-thead > tr > th) { padding: 5px 8px; font-size: 11px; line-height: 1.2; }
.table-editor-tabs :deep(.compact-schema-table .ant-table-tbody > tr > td) { padding: 3px 8px; line-height: 1.15; }
.table-editor-tabs :deep(.compact-schema-table .ant-table-expanded-row > td) { padding: 0 !important; }
.table-editor-tabs :deep(.compact-schema-table .ant-table-cell .ant-input),
.table-editor-tabs :deep(.compact-schema-table .ant-table-cell .ant-input-number),
.table-editor-tabs :deep(.compact-schema-table .ant-table-cell .ant-select-selector) { min-height: 24px; height: 24px; font-size: 12px; }
.table-editor-tabs :deep(.compact-schema-table .ant-table-cell .ant-input) { padding: 1px 7px; }
.table-editor-tabs :deep(.compact-schema-table .ant-table-cell .ant-select-selection-item),
.table-editor-tabs :deep(.compact-schema-table .ant-table-cell .ant-select-selection-placeholder) { line-height: 22px; font-size: 12px; }
.table-editor-tabs :deep(.compact-schema-table .ant-input-number-input) { height: 22px; font-size: 12px; }
.table-editor-tabs :deep(.compact-schema-table .ant-btn.ant-btn-sm) { height: 24px; padding: 0 6px; font-size: 12px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; padding: 4px 10px; border-bottom: 1px solid var(--line, #e8e8e8); background: color-mix(in srgb, var(--surface-1, #ebebeb) 92%, transparent); color: var(--text, inherit); font-weight: 600; }
.table-grid-host { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.column-name-cell { display: flex; align-items: center; gap: 6px; cursor: pointer; }
.pk-icon { color: #d7992d; font-size: 12px; flex-shrink: 0; }
.selected-row { background-color: var(--accent-soft, rgba(24, 144, 255, 0.1)) !important; }
.column-inline-detail { padding: 8px 12px 9px; background: color-mix(in srgb, var(--surface-1, #ebebeb) 94%, transparent); border-top: 1px solid var(--line, rgba(0, 0, 0, 0.08)); }
.column-inline-detail-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.detail-title { color: var(--text, inherit); font-weight: 600; font-size: 12px; }
.detail-type { color: var(--text-sub, #666); font-size: 11px; }
.column-inline-detail-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 6px 12px; align-items: center; }
.inline-field { display: grid; grid-template-columns: 44px minmax(0, 1fr); align-items: center; gap: 6px; }
.detail-label { color: var(--text-sub, #666); font-size: 11px; }
.detail-checkbox-label { display: inline-flex; align-items: center; gap: 4px; }
@media (max-width: 1400px) { .table-editor-toolbar { flex-direction: column; } }
</style>
