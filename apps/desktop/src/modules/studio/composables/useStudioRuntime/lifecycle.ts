import {onBeforeUnmount, onMounted, watch} from 'vue';
import {getApi} from '../../../../api/client';
import type {AiConfigVO, RagConfigVO} from '../../../../types';

const RUNTIME_BOOTSTRAP_RETRY_DELAY_MS = 1200;
const RUNTIME_BOOTSTRAP_MAX_ATTEMPTS = 25;

function wait(ms: number) {
  return new Promise<void>((resolve) => {
    window.setTimeout(resolve, ms);
  });
}

async function loadRuntimeBootstrapData(runtime: any) {
  let lastError: unknown = null;
  for (let attempt = 0; attempt < RUNTIME_BOOTSTRAP_MAX_ATTEMPTS; attempt += 1) {
    let ready = true;
    try {
      await runtime.loadSupportedDbTypes();
    } catch (error) {
      ready = false;
      lastError = error;
    }
    try {
      await runtime.loadConnections();
    } catch (error) {
      ready = false;
      lastError = error;
    }
    if (ready) {
      return;
    }
    await wait(RUNTIME_BOOTSTRAP_RETRY_DELAY_MS);
  }
  if (lastError) {
    throw lastError;
  }
}

export function setupStudioRuntimeLifecycle(runtime: any) {
  onMounted(async () => {
    runtime.syncViewportSize();
    window.addEventListener('resize', runtime.syncViewportSize);
    runtime.loadSessionTitleOverrides();
    runtime.startVectorizeStatusPolling();
    await runtime.runSafely(async () => {
      await loadRuntimeBootstrapData(runtime);
    });
    await runtime.runSafely(async () => {
      const aiConfig = await getApi<AiConfigVO>('/api/ai/config/get');
      const ragConfig = await getApi<RagConfigVO>('/api/rag/config/get');
      runtime.fillAiConfigForm(aiConfig);
      runtime.fillRagConfigForm(ragConfig);
    });
  });

  onBeforeUnmount(() => {
    window.removeEventListener('resize', runtime.syncViewportSize);
    runtime.stopVectorizeStatusPolling();
    runtime.clearAllTableStatsPollingTimers();
    runtime.sqlEditorTypeDisposable?.dispose();
    runtime.sqlEditorTypeDisposable = null;
    runtime.sqlEditorSelectionDisposable?.dispose();
    runtime.sqlEditorSelectionDisposable = null;
    runtime.sqlEditorScrollDisposable?.dispose();
    runtime.sqlEditorScrollDisposable = null;
    runtime.sqlEditorMouseDownDisposable?.dispose();
    runtime.sqlEditorMouseDownDisposable = null;
    runtime.sqlEditorMouseUpDisposable?.dispose();
    runtime.sqlEditorMouseUpDisposable = null;
    runtime.sqlCompletionProviderDisposable?.dispose();
    runtime.sqlCompletionProviderDisposable = null;
    runtime.sqlEditorContextResolverMap.clear();
    runtime.activeSqlEditorInstance = null;
    runtime.queryChatMessageElementMap.clear();
    runtime.queryChatScrollRef.value = null;
    runtime.hideSqlSelectionPopover();
    if (runtime.sqlAutoSuggestTimer !== null) {
      window.clearTimeout(runtime.sqlAutoSuggestTimer);
      runtime.sqlAutoSuggestTimer = null;
    }
    if (runtime.redisBrowserSearchTimer !== null) {
      window.clearTimeout(runtime.redisBrowserSearchTimer);
      runtime.redisBrowserSearchTimer = null;
    }
  });

  watch(
    () => [runtime.activeWorkbenchTab.value, runtime.activeQueryTab.value?.connectionId ?? 0, runtime.activeQueryTab.value?.databaseName ?? ''],
    () => {
      if (!runtime.activeQueryTab.value) {
        runtime.hideSqlSelectionPopover();
        return;
      }
      runtime.activeQueryTab.value.selectedSqlText = '';
      runtime.hideSqlSelectionPopover();
      void runtime.warmupTableSuggestions(runtime.activeQueryTab.value);
      runtime.syncSelectedSqlForActiveTab(false);
    },
    {immediate: true},
  );

  watch(
    () => [runtime.activeQueryTab.value?.key ?? '', runtime.activeQueryTab.value?.activeStatementResultKey ?? ''],
    () => {
      if (!runtime.activeQueryTab.value) {
        return;
      }
      runtime.syncTabPresentationFromStatementResult(
        runtime.activeQueryTab.value,
        runtime.getActiveStatementResultForTab(runtime.activeQueryTab.value),
      );
    },
    {immediate: true},
  );

  watch(
    () => runtime.activeQueryTab.value?.manualChartConfig,
    () => {
      if (!runtime.activeQueryTab.value || !runtime.activeStatementResult.value) {
        return;
      }
      runtime.syncActiveStatementResultFromTab(runtime.activeQueryTab.value);
    },
    {deep: true},
  );

  watch(
    () => runtime.connectionForm.dbType,
    (dbType) => {
      const spec = runtime.findSupportedDbType(dbType);
      const defaultPort = runtime.defaultPortForDbType(dbType);
      if (spec?.requiresHost === false) {
        runtime.connectionForm.host = '';
      }
      if (spec?.requiresPort === false) {
        runtime.connectionForm.port = 0;
      } else if (defaultPort > 0 && (!runtime.connectionForm.port || runtime.connectionForm.port <= 0)) {
        runtime.connectionForm.port = defaultPort;
      }
      if (spec?.supportsUsername === false || dbType === 'SQLITE') {
        runtime.connectionForm.username = '';
      }
      if (spec?.supportsPassword === false || dbType === 'SQLITE') {
        runtime.connectionForm.password = '';
      }
      if (!spec?.supportsDatabasePreview || !runtime.isMultiDatabaseType(dbType)) {
        runtime.connectionForm.selectedDatabases = [];
      }
      if (spec?.supportsDatabaseName === false) {
        runtime.connectionForm.databaseName = '';
      } else if (runtime.connectionForm.databaseName === 'sample.db') {
        runtime.connectionForm.databaseName = '';
      }
      runtime.connectionPreviewDbOptions.value = [];
      runtime.connectionPreviewError.value = '';
    },
    {immediate: true},
  );

  watch(
    () => runtime.connectionForm.sshEnabled,
    (enabled) => {
      if (!enabled) {
        runtime.connectionForm.sshAuthType = 'SSH_PASSWORD';
        runtime.connectionForm.sshPassword = '';
        runtime.connectionForm.sshPrivateKeyPath = '';
        runtime.connectionForm.sshPrivateKeyText = '';
        runtime.connectionForm.sshPrivateKeyPassphrase = '';
        return;
      }
      if (!runtime.connectionForm.sshPort || runtime.connectionForm.sshPort <= 0) {
        runtime.connectionForm.sshPort = 22;
      }
      if (!runtime.connectionForm.sshAuthType) {
        runtime.connectionForm.sshAuthType = 'SSH_PASSWORD';
      }
    },
    {immediate: true},
  );

  watch(
    () => runtime.connectionForm.sshAuthType,
    (mode) => {
      if (!runtime.connectionForm.sshEnabled) {
        return;
      }
      if (mode === 'SSH_PASSWORD') {
        runtime.connectionForm.sshPrivateKeyPath = '';
        runtime.connectionForm.sshPrivateKeyText = '';
        runtime.connectionForm.sshPrivateKeyPassphrase = '';
        return;
      }
      if (mode === 'SSH_KEY_PATH') {
        runtime.connectionForm.sshPassword = '';
        runtime.connectionForm.sshPrivateKeyText = '';
        return;
      }
      if (mode === 'SSH_KEY_TEXT') {
        runtime.connectionForm.sshPassword = '';
        runtime.connectionForm.sshPrivateKeyPath = '';
      }
    },
  );

  watch(
    () => runtime.tableKeyword.value,
    () => {
      if (!runtime.activeConnectionIsRedis.value || runtime.currentObjectType.value !== 'tables' || !runtime.workflow.connectionId) {
        return;
      }
      if (runtime.redisBrowserSearchTimer !== null) {
        window.clearTimeout(runtime.redisBrowserSearchTimer);
      }
      runtime.redisBrowserSearchTimer = window.setTimeout(() => {
        void runtime.runSafely(async () => {
          await runtime.loadRedisBrowserRows();
        });
      }, 300);
    },
  );

  watch(
    () => JSON.stringify(runtime.aiConfigForm.modelOptions ?? []),
    () => {
      const models = runtime.aiModelOptions.value.map((item: {value: string}) => String(item.value)).filter((item: string) => !!item);
      if (!models.length) {
        runtime.selectedAiModel.value = '';
        runtime.erSelectModelName.value = '';
        runtime.queryTabs.value.forEach((tab: {selectedAiModel: string}) => {
          tab.selectedAiModel = '';
        });
        runtime.erTabs.value.forEach((tab: {selectedAiModel: string}) => {
          tab.selectedAiModel = '';
        });
        return;
      }
      if (!models.includes(runtime.selectedAiModel.value)) {
        runtime.selectedAiModel.value = models[0];
      }
      if (!models.includes(runtime.erSelectModelName.value)) {
        runtime.erSelectModelName.value = models[0];
      }
      runtime.queryTabs.value.forEach((tab: {selectedAiModel: string}) => {
        if (!models.includes(tab.selectedAiModel)) {
          tab.selectedAiModel = models[0];
        }
      });
      runtime.erTabs.value.forEach((tab: {selectedAiModel: string}) => {
        if (!models.includes(tab.selectedAiModel)) {
          tab.selectedAiModel = models[0];
        }
      });
    },
    {immediate: true},
  );
}
