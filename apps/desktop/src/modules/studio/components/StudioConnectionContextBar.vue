<template>
  <div class="query-shared-meta">
    <div class="query-meta-item">
      <span>{{ resolvedConnectionLabel }}</span>
      <a-select
        :value="connectionId"
        size="small"
        style="min-width: 156px"
        :options="connectionOptions"
        :disabled="connectionDisabled"
        @change="handleConnectionChange"
      />
    </div>
    <div class="query-meta-item">
      <span>{{ resolvedDatabaseLabel }}</span>
      <a-select
        :value="databaseName"
        size="small"
        style="min-width: 166px"
        :options="databaseOptions"
        :disabled="databaseDisabled"
        @change="handleDatabaseChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import {computed} from 'vue';
import {translateText, useAppI18n} from '../../../i18n';

type SelectValue = string | number;

interface SelectOption {
  label: string;
  value: SelectValue;
  disabled?: boolean;
}

const props = withDefaults(defineProps<{
  connectionId: SelectValue;
  databaseName: string;
  connectionOptions: SelectOption[];
  databaseOptions: SelectOption[];
  connectionDisabled?: boolean;
  databaseDisabled?: boolean;
  connectionLabel?: string;
  databaseLabel?: string;
}>(), {
  connectionDisabled: false,
  databaseDisabled: false,
  connectionLabel: '',
  databaseLabel: '',
});

const emit = defineEmits<{
  connectionChange: [value: SelectValue];
  databaseChange: [value: string];
}>();

const {currentLocale} = useAppI18n();

function tt(text: string) {
  void currentLocale.value;
  return translateText(text);
}

const resolvedConnectionLabel = computed(() => props.connectionLabel || tt('连接'));
const resolvedDatabaseLabel = computed(() => props.databaseLabel || tt('数据库'));

function handleConnectionChange(value: SelectValue) {
  emit('connectionChange', value);
}

function handleDatabaseChange(value: SelectValue) {
  emit('databaseChange', String(value ?? ''));
}
</script>
