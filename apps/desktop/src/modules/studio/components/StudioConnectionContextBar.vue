<template>
  <div class="query-shared-meta">
    <div class="query-meta-item">
      <span>{{ connectionLabel }}</span>
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
      <span>{{ databaseLabel }}</span>
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
  connectionLabel: '连接',
  databaseLabel: '数据库',
});

const emit = defineEmits<{
  connectionChange: [value: SelectValue];
  databaseChange: [value: string];
}>();

function handleConnectionChange(value: SelectValue) {
  emit('connectionChange', value);
}

function handleDatabaseChange(value: SelectValue) {
  emit('databaseChange', String(value ?? ''));
}
</script>
