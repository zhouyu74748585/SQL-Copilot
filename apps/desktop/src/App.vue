<template>
  <div class="studio-root" :class="{ 'is-mac': isMacOS }">
    <section class="tool-ribbon">
      <div class="window-app-title">SQL Copilot</div>
      <button class="tool-item" @click="openCreateModal" title="新建连接">
        <plus-outlined />
        <span>新建连接</span>
      </button>
      <button class="tool-item" @click="openAiQueryTab" title="AI 查询">
        <robot-outlined />
        <span>AI查询</span>
      </button>
      <a-dropdown placement="bottomLeft" :trigger="['click']">
        <button class="tool-item" :disabled="!canOpenHistory" title="会话历史" @click="handleHistoryMenuClick">
          <history-outlined />
          <span>会话历史</span>
        </button>
        <template #overlay>
          <div class="history-menu-panel">
            <div class="history-menu-title">
              <span>会话历史 · {{ queryTabConnectionNameById(historySessionConnectionId) || '-' }}</span>
              <span v-if="historyReloading" class="history-menu-loading">刷新中...</span>
            </div>
            <div class="history-menu-toolbar">
              <a-input
                v-model:value="historyKeywordInput"
                size="small"
                allow-clear
                placeholder="按标题搜索会话"
                @pressEnter="applyHistoryKeywordSearch"
              />
              <a-button size="small" class="history-search-btn" @click="applyHistoryKeywordSearch">搜索</a-button>
            </div>
            <div class="history-menu-list" @scroll="handleHistoryMenuScroll">
              <div v-if="!sessionHistoryTabs.length" class="history-menu-empty">暂无 AI 会话</div>
              <button
                v-for="item in sessionHistoryTabs"
                :key="historyItemKey(item)"
                class="history-menu-item"
                :class="{ 'is-active': isHistoryItemActive(item), 'is-loading': historySessionLoadingKey === historyItemKey(item) }"
                @click="openHistorySession(item)"
              >
                <div class="history-menu-item-head">
                  <a-input
                    v-if="editingHistoryTabKey === historyItemKey(item)"
                    v-model:value="editingHistoryTitle"
                    size="small"
                    class="history-menu-title-input"
                    maxlength="60"
                    @click.stop
                    @pressEnter="commitHistoryTitleEdit(item)"
                    @blur="commitHistoryTitleEdit(item)"
                    @keydown.esc.stop.prevent="cancelHistoryTitleEdit"
                  />
                  <span v-else class="history-menu-item-title">{{ historyItemDisplayTitle(item) }}</span>
                  <div class="history-menu-item-head-actions">
                    <span>{{ formatTime(item.updatedAt) }}</span>
                    <a-button size="small" type="link" class="history-menu-rename-btn" title="改名" @click.stop="startHistoryTitleEdit(item)">
                      <template #icon><edit-outlined /></template>
                    </a-button>
                  </div>
                </div>
                <div class="history-menu-item-meta">
                  会话ID: {{ item.sessionId }} | 记录数: {{ item.messageCount ?? 0 }}
                </div>
                <div class="history-menu-item-desc">创建于 {{ formatTime(item.createdAt) }}</div>
              </button>
              <div v-if="historyLoadingMore" class="history-menu-load-tip">加载中...</div>
              <div v-else-if="sessionHistoryTabs.length && !historySessionHasMore" class="history-menu-load-tip">没有更多会话</div>
            </div>
          </div>
        </template>
      </a-dropdown>
      <button class="tool-item" @click="openAiConfigModal" title="AI 配置">
        <setting-outlined />
        <span>AI 配置</span>
      </button>
      <div class="tool-status">
        <a-tag color="blue">Debug</a-tag>
        <span>{{ selectedConnection?.name ?? activeQueryConnectionName ?? '未选择连接' }}</span>
      </div>
    </section>

    <section class="workspace-tabs">
      <button
        class="workspace-tab"
        :class="{ 'is-active': activeWorkbenchTab === browserTabKey }"
        @click="activateBrowserTab"
      >
        <span>对象浏览</span>
      </button>
      <button
        v-for="tab in queryTabs"
        :key="tab.key"
        class="workspace-tab"
        :class="{ 'is-active': activeWorkbenchTab === tab.key }"
        @click="activeWorkbenchTab = tab.key"
      >
        <span>{{ tab.title }}</span>
        <close-outlined class="tab-close" @click.stop="closeQueryTab(tab.key)" />
      </button>
    </section>

    <main class="workbench" :class="{ 'workbench-query': activeWorkbenchTab !== browserTabKey }" :style="workbenchStyle">
      <aside class="pane pane-left">
        <div class="pane-title pane-title-with-action">
          <span>我的连接</span>
          <a-button size="small" type="text" :loading="connectionRefreshing" @click="refreshConnections" title="刷新连接列表">
            <template #icon>
              <reload-outlined />
            </template>
          </a-button>
        </div>
        <div class="pane-search">
          <a-input v-model:value="connectionKeyword" size="small" placeholder="搜索连接" allow-clear>
            <template #prefix>
              <search-outlined />
            </template>
          </a-input>
        </div>

        <a-tree
          class="connection-tree"
          :tree-data="connectionTreeData"
          :selected-keys="selectedTreeKeys"
          :expanded-keys="expandedTreeKeys"
          block-node
          @expand="handleTreeExpand"
          @select="handleTreeSelect"
          @rightClick="handleTreeRightClick"
        >
          <template #title="{ title, dataRef }">
            <div class="tree-title-row">
              <img v-if="dataRef.nodeType === 'connection'" class="tree-icon-img" :src="dbIconUrl(dataRef.dbType)" alt="db" />
              <component v-else :is="nodeIconComponent(dataRef)" class="tree-icon-font" />
              <span class="tree-title-text">{{ title }}</span>
              <span
                v-if="dataRef.nodeType === 'database'"
                class="db-vectorize-status"
                :class="databaseStatusClass(dataRef.vectorizeStatus)"
              >
                <a-tooltip :title="databaseStatusLabel(dataRef.vectorizeStatus)">
                  <component :is="databaseStatusIcon(dataRef.vectorizeStatus)" class="db-vectorize-status-icon" />
                </a-tooltip>
              </span>
            </div>
          </template>
        </a-tree>

        <div class="pane-footer">
          <div class="selected-name">{{ selectedConnection?.name ?? '未选择连接' }}</div>
          <div class="selected-desc">
            {{ selectedConnection?.host || '本地连接' }} : {{ selectedConnection?.port || '-' }} / {{ getActiveDatabaseName(workflow.connectionId) || '未指定库' }}
          </div>
        </div>
      </aside>

      <template v-if="activeWorkbenchTab === browserTabKey">
        <section class="pane pane-center">
          <div class="pane-title">对象浏览</div>
          <div class="center-toolbar">
            <a-space size="small">
              <a-button size="small" @click="quickSelectAll">SELECT</a-button>
            </a-space>
            <div class="center-toolbar-right">
              <a-input v-model:value="tableKeyword" size="small" placeholder="搜索表名" allow-clear>
                <template #prefix>
                  <search-outlined />
                </template>
              </a-input>
              <a-button size="small" @click="refreshCurrentObjects" title="刷新当前对象">
                <reload-outlined />
              </a-button>
              <a-radio-group v-model:value="objectViewMode" size="small">
                <a-radio-button value="row">
                  <unordered-list-outlined />
                </a-radio-button>
                <a-radio-button value="grid">
                  <appstore-outlined />
                </a-radio-button>
              </a-radio-group>
            </div>
          </div>

          <a-table
            v-if="objectViewMode === 'row'"
            size="small"
            :pagination="false"
            :columns="objectColumns"
            :data-source="filteredObjectRows"
            row-key="objectName"
            :scroll="{ y: tableScrollY }"
            :custom-row="onObjectRow"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'objectName'">
                <div class="table-name-cell" :class="{ 'is-active': selectedObjectName === record.objectName }">
                  <database-outlined />
                  <span>{{ record.objectName }}</span>
                </div>
              </template>
              <template v-else-if="column.key === 'description'">
                <span class="object-desc-ellipsis">{{ record.description || '-' }}</span>
              </template>
            </template>
          </a-table>

          <div v-else class="object-grid">
            <div
              v-for="item in filteredObjectRows"
              :key="item.objectName"
              class="object-card"
              :class="{ 'is-active': selectedObjectName === item.objectName }"
              @click="onObjectRow(item).onClick()"
            >
              <div class="object-card-title">{{ item.objectName }}</div>
              <div class="object-card-meta">{{ objectTypeLabel(item.objectType) }}</div>
              <div class="object-card-desc">{{ item.description || '-' }}</div>
            </div>
          </div>

          <div class="center-status">
            <span>对象: {{ filteredObjectRows.length }}</span>
            <span>类型: {{ objectTypeLabel(currentObjectType) }}</span>
            <span>字段: {{ schemaOverview?.columnCount ?? 0 }}</span>
          </div>
        </section>

        <div class="pane-splitter" @mousedown="startResizeBrowserPane" />

        <aside class="pane pane-right detail-pane">
          <div class="pane-title">对象详情</div>
          <div v-if="!selectedObjectRecord" class="empty-pane">请从对象浏览中选择表、视图或其他对象</div>
          <div v-else class="detail-wrapper">
            <div class="detail-summary">
              <div class="detail-row"><span>对象</span><strong>{{ selectedObjectRecord.objectName }}</strong></div>
              <div class="detail-row"><span>类型</span><strong>{{ objectTypeLabel(selectedObjectRecord.objectType) }}</strong></div>
              <div class="detail-row detail-row-description"><span>说明</span><strong>{{ selectedObjectRecord.description || '-' }}</strong></div>
              <div class="detail-row"><span>连接</span><strong>{{ selectedConnection?.name ?? '-' }}</strong></div>
              <div class="detail-row"><span>数据库</span><strong>{{ getActiveDatabaseName(workflow.connectionId) || '-' }}</strong></div>
            </div>

            <div v-if="selectedObjectRecord.objectType === 'tables'" class="detail-table-panel">
              <a-spin :spinning="tableDetailLoading">
                <a-table
                  size="small"
                  :pagination="false"
                  :columns="detailColumns"
                  :data-source="tableDetail?.columns ?? []"
                  row-key="columnName"
                  :scroll="{ y: detailScrollY }"
                >
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'nullable'">
                      {{ record.nullable ? '是' : '否' }}
                    </template>
                    <template v-else-if="column.key === 'indexed'">
                      {{ record.indexed ? '是' : '否' }}
                    </template>
                    <template v-else-if="column.key === 'primaryKey'">
                      {{ record.primaryKey ? '是' : '否' }}
                    </template>
                    <template v-else-if="column.key === 'autoIncrement'">
                      {{ record.autoIncrement ? '是' : '否' }}
                    </template>
                    <template v-else-if="column.key === 'defaultValue'">
                      {{ record.defaultValue || '-' }}
                    </template>
                    <template v-else-if="column.key === 'columnSize'">
                      {{ record.columnSize ?? '-' }}
                    </template>
                    <template v-else-if="column.key === 'decimalDigits'">
                      {{ record.decimalDigits ?? '-' }}
                    </template>
                  </template>
                </a-table>
              </a-spin>
            </div>

            <div v-else class="detail-note">当前对象类型暂无结构详情，仅展示基本信息。</div>
          </div>
        </aside>
      </template>

      <template v-else>
        <div v-if="activeQueryTab" class="query-shared-meta">
          <div class="query-meta-item">
            <span>连接</span>
            <a-select
              v-model:value="activeQueryTab.connectionId"
              size="small"
              style="min-width: 156px"
              :options="connectionSelectOptions"
              @change="handleQueryConnectionChange(activeQueryTab)"
            />
          </div>
          <div class="query-meta-item">
            <span>数据库</span>
            <a-select
              v-model:value="activeQueryTab.databaseName"
              size="small"
              style="min-width: 166px"
              :options="databaseOptionsForTab(activeQueryTab)"
              @change="handleQueryDatabaseChange(activeQueryTab)"
            />
          </div>
        </div>

        <section v-if="activeQueryTab" class="pane pane-center query-chat-pane">
          <div class="pane-title">{{ activeQueryTab.title }} · 对话</div>

          <div class="query-chat-scroll">
            <div v-if="!activeQueryTab.chatMessages.length" class="query-chat-empty">
              输入自然语言后点击“生成 SQL”“解释 SQL”或“分析 SQL”，这里会按对话展示历史。
            </div>
            <div
              v-for="item in activeQueryTab.chatMessages"
              :key="item.id"
              class="query-chat-message"
              :class="{ 'is-user': item.role === 'user', 'is-assistant': item.role === 'assistant' }"
            >
              <template v-if="item.role === 'user'">
                <div class="query-chat-user-bubble">{{ item.content }}</div>
              </template>
              <template v-else>
                <div class="query-chat-assistant-card">
                  <div class="query-chat-assistant-head">
                    <span>{{ assistantActionLabel(item.actionType) }}</span>
                    <span>{{ formatTime(item.createdAt) }}</span>
                  </div>
                  <template v-if="item.sqlText">
                    <pre class="query-chat-sql">{{ item.sqlText }}</pre>
                    <a-space size="small" wrap>
                      <a-tooltip title="追加到左侧编辑器">
                        <a-button size="small" class="sql-action-icon-btn" @click="appendSqlToEditor(activeQueryTab, item.sqlText || '')">
                          <template #icon><arrow-left-outlined /></template>
                        </a-button>
                      </a-tooltip>
                      <a-tooltip title="EXPLAIN">
                        <a-button size="small" class="sql-action-icon-btn" @click="explainSqlForTab(activeQueryTab, item.sqlText || '')">
                          <template #icon><eye-outlined /></template>
                        </a-button>
                      </a-tooltip>
                      <a-tooltip title="执行 SQL">
                        <a-button size="small" type="primary" class="sql-action-icon-btn" @click="executeSqlForTab(activeQueryTab, item.sqlText || '')">
                          <template #icon><play-circle-outlined /></template>
                        </a-button>
                      </a-tooltip>
                    </a-space>
                  </template>
                  <div v-else class="query-chat-text">{{ item.content || '-' }}</div>
                </div>
              </template>
            </div>
          </div>

          <div class="query-chat-composer">
            <a-textarea
              v-model:value="activeQueryTab.prompt"
              :rows="4"
              placeholder="例如：查询近 7 天订单量，并按天聚合"
            />
            <div class="query-chat-composer-row">
              <div class="query-chat-model-box">
                <span>模型</span>
                <a-select
                  v-model:value="activeQueryTab.selectedAiModel"
                  size="small"
                  style="min-width: 190px"
                  :options="aiModelOptions"
                />
              </div>
              <div class="query-chat-composer-actions">
                <a-tooltip title="生成 SQL">
                  <a-button
                    size="small"
                    type="primary"
                    class="sql-action-icon-btn"
                    :loading="activeQueryTab.aiGenerating"
                    :disabled="activeQueryTab.aiGenerating"
                    @click="generateSqlForTab(activeQueryTab, 'generate')"
                  >
                    <template #icon><code-outlined /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="解释 SQL">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn"
                    :loading="activeQueryTab.aiGenerating"
                    :disabled="activeQueryTab.aiGenerating"
                    @click="generateSqlForTab(activeQueryTab, 'explain')"
                  >
                    <template #icon><read-outlined /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="分析 SQL">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn"
                    :loading="activeQueryTab.aiGenerating"
                    :disabled="activeQueryTab.aiGenerating"
                    @click="generateSqlForTab(activeQueryTab, 'analyze')"
                  >
                    <template #icon><bar-chart-outlined /></template>
                  </a-button>
                </a-tooltip>
              </div>
            </div>
          </div>
        </section>

        <aside v-if="activeQueryTab" class="pane pane-right query-editor-pane">
          <div class="pane-title">SQL 编辑与执行</div>

          <div class="editor-group">
            <MonacoEditor
              v-model:value="activeQueryTab.sqlText"
              language="sql"
              width="100%"
              height="240px"
              theme="vs"
              :options="sqlEditorOptions"
              class="sql-editor"
              @mount="handleSqlEditorMount"
            >
              <template #default>编辑器加载中...</template>
              <template #failure>编辑器加载失败，请刷新页面重试</template>
            </MonacoEditor>
          </div>

          <div class="editor-actions">
            <a-space wrap>
              <a-tooltip :title="activeQueryTab.selectedSqlText ? 'EXPLAIN（仅作用于选中 SQL）' : 'EXPLAIN'">
                <a-button
                  size="small"
                  :class="['sql-action-icon-btn', { 'is-selection-active': !!activeQueryTab.selectedSqlText }]"
                  @click="explainSqlForTab(activeQueryTab)"
                >
                  <template #icon><eye-outlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip :title="activeQueryTab.selectedSqlText ? '执行 SQL（仅作用于选中 SQL）' : '执行 SQL'">
                <a-button
                  size="small"
                  type="primary"
                  :class="['sql-action-icon-btn', { 'is-selection-active': !!activeQueryTab.selectedSqlText }]"
                  @click="executeSqlForTab(activeQueryTab)"
                >
                  <template #icon><play-circle-outlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip title="风险评估">
                <a-button size="small" class="sql-action-icon-btn" @click="evaluateRiskForTab(activeQueryTab)">
                  <template #icon><safety-outlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip title="自动修复">
                <a-button size="small" class="sql-action-icon-btn" @click="repairSqlForTab(activeQueryTab)">
                  <template #icon><tool-outlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip title="导出结果（CSV）">
                <a-button size="small" class="sql-action-icon-btn" @click="exportCsvForTab(activeQueryTab)">
                  <template #icon><download-outlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip v-if="activeQueryTab.selectedSqlText" title="所选 SQL 加入对话">
                <a-button
                  size="small"
                  class="sql-action-icon-btn"
                  @click="appendSelectedSqlToPrompt(activeQueryTab)"
                >
                  <template #icon><message-outlined /></template>
                </a-button>
              </a-tooltip>
            </a-space>
          </div>

          <div class="query-result-panel">
            <div class="query-result-title">查询结果</div>
            <a-table
              size="small"
              :pagination="false"
              :columns="activeResultColumns"
              :data-source="activeResultRows"
              :scroll="{ x: queryResultScrollX, y: queryResultScrollY }"
              row-key="__rowKey"
            />
          </div>
        </aside>
      </template>
    </main>

    <a-modal
      v-model:open="createModalOpen"
      :title="isEditMode ? '编辑连接' : '新建连接'"
      width="640px"
      :ok-text="isEditMode ? '保存' : '创建'"
      cancel-text="取消"
      @ok="saveConnection"
      @cancel="resetConnectionModalState"
    >
      <a-form layout="vertical">
        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="连接名称">
              <a-input v-model:value="connectionForm.name" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="数据库类型">
              <a-select v-model:value="connectionForm.dbType" :options="dbTypeOptions" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="主机">
              <a-input v-model:value="connectionForm.host" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="端口">
              <a-input-number v-model:value="connectionForm.port" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="数据库名/路径">
              <a-input v-model:value="connectionForm.databaseName" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="环境">
              <a-select v-model:value="connectionForm.env" :options="envOptions" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="用户名">
              <a-input
                v-model:value="connectionForm.username"
                :disabled="connectionForm.dbType === 'SQLITE'"
                placeholder="请输入数据库用户名"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="密码">
              <a-input-password
                v-model:value="connectionForm.password"
                :disabled="connectionForm.dbType === 'SQLITE'"
                placeholder="请输入数据库密码"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-space>
          <a-checkbox v-model:checked="connectionForm.readOnly">只读</a-checkbox>
          <a-checkbox v-model:checked="connectionForm.sshEnabled">SSH 隧道</a-checkbox>
        </a-space>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="aiConfigModalOpen"
      title="AI 接入配置"
      width="760px"
      ok-text="保存配置"
      cancel-text="取消"
      @ok="saveAiConfig"
    >
      <a-form layout="vertical">
        <a-tabs v-model:activeKey="aiConfigActiveTab">
          <a-tab-pane key="model" tab="模型配置">
            <a-space>
              <a-button size="small" @click="addOpenAiModelOption">新增 OpenAI 模型</a-button>
              <a-button size="small" @click="addCliModelOption">新增 CLI 模型</a-button>
            </a-space>
            <div v-if="!aiConfigForm.modelOptions?.length" class="empty-pane" style="margin-top: 12px;">请至少配置一个模型</div>
            <div
              v-for="(item, index) in aiConfigForm.modelOptions"
              :key="item.id || index"
              class="model-option-card"
            >
              <div class="model-option-head">
                <strong>模型 {{ index + 1 }}</strong>
                <a-button
                  size="small"
                  type="text"
                  danger
                  :disabled="(aiConfigForm.modelOptions?.length ?? 0) <= 1"
                  @click="removeModelOption(index)"
                >
                  删除
                </a-button>
              </div>
              <a-row :gutter="12">
                <a-col :span="8">
                  <a-form-item label="标识 ID">
                    <a-input v-model:value="item.id" placeholder="openai-gpt41 / local-cli" />
                  </a-form-item>
                </a-col>
                <a-col :span="8">
                  <a-form-item label="展示名称">
                    <a-input v-model:value="item.name" placeholder="GPT-4.1 / 本地 Codex CLI" />
                  </a-form-item>
                </a-col>
                <a-col :span="8">
                  <a-form-item label="类型">
                    <a-select
                      v-model:value="item.providerType"
                      :options="[{ label: 'OpenAI API', value: 'OPENAI' }, { label: '本地 CLI', value: 'LOCAL_CLI' }]"
                    />
                  </a-form-item>
                </a-col>
              </a-row>
              <template v-if="item.providerType === 'OPENAI'">
                <a-row :gutter="12">
                  <a-col :span="24">
                    <a-form-item label="Base URL">
                      <a-input v-model:value="item.openaiBaseUrl" placeholder="https://api.openai.com/v1" />
                    </a-form-item>
                  </a-col>
                </a-row>
                <a-row :gutter="12">
                  <a-col :span="12">
                    <a-form-item label="API Key">
                      <a-input-password v-model:value="item.openaiApiKey" placeholder="sk-..." />
                    </a-form-item>
                  </a-col>
                  <a-col :span="12">
                    <a-form-item label="模型名">
                      <a-input v-model:value="item.openaiModel" placeholder="gpt-4.1-mini" />
                    </a-form-item>
                  </a-col>
                </a-row>
              </template>
              <template v-else>
                <a-row :gutter="12">
                  <a-col :span="12">
                    <a-form-item label="CLI 命令">
                      <a-input v-model:value="item.cliCommand" placeholder="codex / claude / 其他命令" />
                    </a-form-item>
                  </a-col>
                  <a-col :span="12">
                    <a-form-item label="工作目录">
                      <a-input v-model:value="item.cliWorkingDir" placeholder="/path/to/workdir（可选）" />
                    </a-form-item>
                  </a-col>
                </a-row>
              </template>
            </div>
          </a-tab-pane>

          <a-tab-pane key="embedding" tab="向量化配置">
            <a-form-item label="向量模型目录（推荐：填写 clone 的模型仓库目录）">
              <a-input
                v-model:value="ragConfigForm.ragEmbeddingModelDir"
                placeholder="/path/to/bge-m3-onnx-o4（目录优先于单文件路径）"
              />
            </a-form-item>
            <a-row :gutter="12">
              <a-col :span="12">
                <a-form-item label="ONNX 文件名">
                  <a-input v-model:value="ragConfigForm.ragEmbeddingModelFileName" placeholder="model_optimized.onnx" />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="ONNX 数据文件名（可选）">
                  <a-input v-model:value="ragConfigForm.ragEmbeddingModelDataFileName" placeholder="model_optimized.onnx.data" />
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item label="Tokenizer 文件名（必填）">
              <a-input v-model:value="ragConfigForm.ragEmbeddingTokenizerFileName" placeholder="tokenizer.json" />
            </a-form-item>
            <a-row :gutter="12">
              <a-col :span="12">
                <a-form-item label="Tokenizer 配置文件名">
                  <a-input v-model:value="ragConfigForm.ragEmbeddingTokenizerConfigFileName" placeholder="tokenizer_config.json" />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="模型配置文件名">
                  <a-input v-model:value="ragConfigForm.ragEmbeddingConfigFileName" placeholder="config.json" />
                </a-form-item>
              </a-col>
            </a-row>
            <a-row :gutter="12">
              <a-col :span="12">
                <a-form-item label="Special Tokens 文件名">
                  <a-input v-model:value="ragConfigForm.ragEmbeddingSpecialTokensFileName" placeholder="special_tokens_map.json" />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="SentencePiece 文件名">
                  <a-input v-model:value="ragConfigForm.ragEmbeddingSentencepieceFileName" placeholder="sentencepiece.bpe.model" />
                </a-form-item>
              </a-col>
            </a-row>
            <a-row :gutter="12">
              <a-col :span="12">
                <a-form-item label="ONNX 文件绝对路径（目录为空时生效）">
                  <a-input v-model:value="ragConfigForm.ragEmbeddingModelPath" placeholder="/path/to/model_optimized.onnx" />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="ONNX 数据绝对路径（可选）">
                  <a-input v-model:value="ragConfigForm.ragEmbeddingModelDataPath" placeholder="/path/to/model_optimized.onnx.data" />
                </a-form-item>
              </a-col>
            </a-row>
          </a-tab-pane>
        </a-tabs>
      </a-form>
    </a-modal>

    <div
      v-if="contextMenu.visible"
      class="context-menu-mask"
      @click="closeContextMenu"
      @contextmenu.prevent="closeContextMenu"
    />
    <div
      v-if="contextMenu.visible"
      class="context-menu"
      :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
    >
      <template v-if="contextMenu.targetType === 'connection'">
        <button class="context-menu-item" @click="triggerContextAction('edit')">编辑连接</button>
        <button class="context-menu-item" @click="triggerContextAction('test')">测试连接</button>
        <button class="context-menu-item" @click="triggerContextAction('sync')">同步 Schema</button>
        <button class="context-menu-item danger" @click="triggerContextAction('delete')">删除连接</button>
      </template>
      <template v-else-if="contextMenu.targetType === 'database'">
        <button
          class="context-menu-item"
          :disabled="isContextDatabaseVectorizing"
          @click="triggerContextAction('revectorize')"
        >
          重新向量化
        </button>
        <button
          class="context-menu-item"
          :disabled="!canInterruptContextVectorize"
          @click="triggerContextAction('interruptVectorize')"
        >
          中断向量化
        </button>
        <button
          class="context-menu-item"
          :disabled="!canViewContextVectorizedData"
          @click="triggerContextAction('viewVectorizedData')"
        >
          查看向量化数据
        </button>
      </template>
    </div>

    <a-modal
      v-model:open="vectorizeOverviewModalOpen"
      title="向量化数据概要"
      width="560px"
      :footer="null"
      @cancel="vectorizeOverviewModalOpen = false"
    >
      <a-spin :spinning="vectorizeOverviewLoading">
        <div v-if="vectorizeOverviewData" class="vectorize-overview-panel">
          <div class="vectorize-overview-head">
            <div class="vectorize-overview-db">{{ vectorizeOverviewData.databaseName }}</div>
            <a-tag :color="databaseStatusClass(vectorizeOverviewData.status) === 'is-success' ? 'green' : 'blue'">
              {{ databaseStatusLabel(vectorizeOverviewData.status) }}
            </a-tag>
          </div>

          <div class="vectorize-overview-kpis">
            <div class="vectorize-overview-kpi-card">
              <span>向量规模</span>
              <strong>{{ formatCompactCount(vectorizeOverviewData.totalVectorCount) }}</strong>
            </div>
            <div class="vectorize-overview-kpi-card">
              <span>向量维度</span>
              <strong>{{ vectorizeOverviewData.vectorDimension || '-' }}</strong>
            </div>
            <div class="vectorize-overview-kpi-card">
              <span>最近更新</span>
              <strong>{{ formatTime(vectorizeOverviewData.updatedAt) }}</strong>
            </div>
          </div>

          <div class="vectorize-overview-breakdown">
            <div class="vectorize-overview-item">
              <span>表向量</span>
              <strong>{{ formatCompactCount(vectorizeOverviewData.schemaTableVectorCount) }}</strong>
            </div>
            <div class="vectorize-overview-item">
              <span>字段向量</span>
              <strong>{{ formatCompactCount(vectorizeOverviewData.schemaColumnVectorCount) }}</strong>
            </div>
            <div class="vectorize-overview-item">
              <span>SQL 历史</span>
              <strong>{{ formatCompactCount(vectorizeOverviewData.sqlHistoryVectorCount) }}</strong>
            </div>
            <div class="vectorize-overview-item">
              <span>SQL 片段</span>
              <strong>{{ formatCompactCount(vectorizeOverviewData.sqlFragmentVectorCount) }}</strong>
            </div>
          </div>

          <div class="vectorize-overview-note">
            {{ vectorizeOverviewData.message || '仅展示概要统计，不展示具体向量明细。' }}
          </div>
        </div>
        <div v-else class="empty-pane">暂无可展示的向量化数据概要</div>
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import {
  AppstoreOutlined,
  BarChartOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  DownloadOutlined,
  CodeOutlined,
  DatabaseOutlined,
  EditOutlined,
  EyeOutlined,
  FolderOpenOutlined,
  HddOutlined,
  HistoryOutlined,
  LoadingOutlined,
  MessageOutlined,
  MinusCircleOutlined,
  ArrowLeftOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReadOutlined,
  ReloadOutlined,
  RobotOutlined,
  SafetyOutlined,
  SearchOutlined,
  SettingOutlined,
  ToolOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons-vue';
import {Editor as MonacoEditor} from '@guolao/vue-monaco-editor';
import type {IDisposable} from 'monaco-editor';
import type * as MonacoApi from 'monaco-editor';
import type {ConnectionVO} from '@sqlcopilot/shared-contracts';
import {message} from 'ant-design-vue';
import {computed, onBeforeUnmount, onMounted, reactive, ref, watch} from 'vue';
import {getApi, postApi} from './api/client';
import mysqlIcon from './assets/db/mysql.svg';
import oracleIcon from './assets/db/oracle.svg';
import postgresqlIcon from './assets/db/postgresql.svg';
import sqliteIcon from './assets/db/sqlite.svg';
import sqlserverIcon from './assets/db/sqlserver.svg';
import type {
  AiConfigSaveReq,
  AiConfigVO,
  AiGenerateSqlVO,
  AiModelOption,
  AiTextResponseVO,
  ConnectionCreateReq,
  ExplainVO,
  QueryHistorySessionPageVO,
  QueryHistorySessionVO,
  QueryHistoryVO,
  RagDatabaseVectorizeStatusVO,
  RagConfigSaveReq,
  RagConfigVO,
  RagVectorizeEnqueueVO,
  RagVectorizeInterruptVO,
  RagVectorizeOverviewVO,
  RiskEvaluateVO,
  SchemaDatabaseVO,
  SchemaOverviewVO,
  SqlExecuteVO,
  TableDetailVO,
} from './types';

interface ObjectRow {
  objectName: string;
  objectType: 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups';
  rowEstimate: number;
  tableSize: string;
  description: string;
}

interface QueryChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  sqlText?: string;
  actionType: 'generate' | 'explain' | 'analyze';
  createdAt: number;
}

interface QueryWorkspaceTab {
  key: string;
  title: string;
  connectionId: number;
  databaseName: string;
  sessionId: string;
  prompt: string;
  sqlText: string;
  riskAckToken: string;
  riskInfo: RiskEvaluateVO | null;
  executeResult: SqlExecuteVO | null;
  explainResult: ExplainVO | null;
  selectedAiModel: string;
  aiGenerating: boolean;
  selectedSqlText: string;
  chatMessages: QueryChatMessage[];
  createdAt: number;
  updatedAt: number;
}

const browserTabKey = 'browser';
const isMacOS = typeof navigator !== 'undefined' && /mac/i.test(navigator.platform);
let vectorizeStatusPollTimer: number | null = null;
const vectorizeStatusPollIntervalMs = 30000;

const connections = ref<ConnectionVO[]>([]);
const schemaOverview = ref<SchemaOverviewVO | null>(null);
const selectedObjectName = ref('');
const createModalOpen = ref(false);
const isEditMode = ref(false);
const editingConnectionId = ref<number | null>(null);
const connectionRefreshing = ref(false);
const connectionKeyword = ref('');
const tableKeyword = ref('');
const selectedTreeKeys = ref<string[]>([]);
const expandedTreeKeys = ref<string[]>([]);
const tableNameCache = ref<Record<string, string[]>>({});
const tableNameLoadedCache = ref<Record<string, boolean>>({});
const objectNameCache = ref<Record<string, string[]>>({});
const databaseListCache = ref<Record<number, string[]>>({});
const activeDatabaseMap = ref<Record<number, string>>({});
const databaseVectorizeStatusMap = ref<Record<string, RagDatabaseVectorizeStatusVO>>({});
const currentObjectType = ref<'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups'>('tables');
const objectViewMode = ref<'row' | 'grid'>('row');
const viewportHeight = ref(typeof window === 'undefined' ? 900 : window.innerHeight);
const vectorizeOverviewModalOpen = ref(false);
const vectorizeOverviewLoading = ref(false);
const vectorizeOverviewData = ref<RagVectorizeOverviewVO | null>(null);
const aiConfigModalOpen = ref(false);
const aiConfigActiveTab = ref<'model' | 'embedding'>('model');
const selectedAiModel = ref('');
const activeWorkbenchTab = ref(browserTabKey);
const queryTabs = ref<QueryWorkspaceTab[]>([]);
const historyReloading = ref(false);
const historyLoadingMore = ref(false);
const historySessionLoadingKey = ref('');
const historyKeywordInput = ref('');
const historyKeyword = ref('');
const historySessionItems = ref<QueryHistorySessionVO[]>([]);
const historySessionPageNo = ref(1);
const historySessionPageSize = 20;
const historySessionHasMore = ref(true);
const historySessionConnectionId = ref(0);
const editingHistoryTabKey = ref('');
const editingHistoryTitle = ref('');
const sessionTitleOverrides = ref<Record<string, string>>({});
const tableDetail = ref<TableDetailVO | null>(null);
const tableDetailLoading = ref(false);
const viewportWidth = ref(typeof window === 'undefined' ? 1440 : window.innerWidth);
const browserRightPaneWidth = ref(390);
const browserPaneResizeState = reactive({
  resizing: false,
  startX: 0,
  startWidth: 390,
});

const contextMenu = reactive({
  visible: false,
  x: 0,
  y: 0,
  targetType: 'none' as 'none' | 'connection' | 'database',
  connectionId: 0,
  databaseName: '',
});

const connectionForm = reactive<ConnectionCreateReq>(defaultConnectionForm());
const aiConfigForm = reactive<AiConfigSaveReq>(defaultAiConfigForm());
const ragConfigForm = reactive<RagConfigSaveReq>(defaultRagConfigForm());

const workflow = reactive({
  connectionId: 0,
  prompt: '统计当前数据库的表数量',
  sqlText: '',
});

const dbTypeOptions = [
  { label: 'MySQL', value: 'MYSQL' },
  { label: 'PostgreSQL', value: 'POSTGRESQL' },
  { label: 'SQLite', value: 'SQLITE' },
  { label: 'SQL Server', value: 'SQLSERVER' },
  { label: 'Oracle', value: 'ORACLE' },
];

const envOptions = [
  { label: '开发 DEV', value: 'DEV' },
  { label: '测试 TEST', value: 'TEST' },
  { label: '生产 PROD', value: 'PROD' },
];

const sqlEditorOptions = {
  automaticLayout: true,
  minimap: { enabled: false },
  fontSize: 12,
  lineHeight: 18,
  wordWrap: 'on',
  quickSuggestions: {
    comments: false,
    strings: false,
    other: true,
  },
  quickSuggestionsDelay: 80,
  suggestOnTriggerCharacters: true,
  scrollBeyondLastLine: false,
  tabSize: 2,
  insertSpaces: true,
};
const sqlKeywords = [
  'SELECT', 'FROM', 'WHERE', 'GROUP BY', 'ORDER BY', 'HAVING', 'LIMIT', 'OFFSET',
  'DISTINCT', 'AS', 'AND', 'OR', 'NOT', 'IN', 'EXISTS', 'BETWEEN', 'LIKE', 'IS NULL', 'IS NOT NULL',
  'INSERT INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'TRUNCATE', 'MERGE',
  'CREATE TABLE', 'ALTER TABLE', 'DROP TABLE', 'CREATE INDEX', 'DROP INDEX', 'CREATE VIEW', 'DROP VIEW',
  'JOIN', 'INNER JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'FULL JOIN', 'ON', 'UNION', 'UNION ALL',
  'CASE', 'WHEN', 'THEN', 'ELSE', 'END',
  'COUNT', 'SUM', 'AVG', 'MIN', 'MAX',
  'WITH', 'CTE', 'DESC', 'ASC', 'TOP',
];
let sqlCompletionProviderDisposable: IDisposable | null = null;
let sqlEditorTypeDisposable: IDisposable | null = null;
let sqlEditorSelectionDisposable: IDisposable | null = null;
let sqlAutoSuggestTimer: number | null = null;
let activeSqlEditorInstance: MonacoApi.editor.IStandaloneCodeEditor | null = null;
const pendingTableNameLoads = new Map<string, Promise<string[]>>();
const sessionTitleOverridesStorageKey = 'sqlcopilot.session-title-overrides.v1';

const selectedConnection = computed(() =>
  connections.value.find((item) => item.id === workflow.connectionId),
);

const activeQueryTab = computed(() =>
  queryTabs.value.find((item) => item.key === activeWorkbenchTab.value) ?? null,
);

const activeQueryConnectionName = computed(() => {
  if (!activeQueryTab.value) {
    return '';
  }
  return queryTabConnectionName(activeQueryTab.value);
});

const canOpenHistory = computed(() => {
  return connections.value.length > 0;
});

const currentHistoryConnectionId = computed(() => {
  if (activeQueryTab.value?.connectionId) {
    return activeQueryTab.value.connectionId;
  }
  if (workflow.connectionId) {
    return workflow.connectionId;
  }
  return connections.value[0]?.id ?? 0;
});

const sessionHistoryTabs = computed(() => historySessionItems.value);

const isContextDatabaseVectorizing = computed(() =>
  contextMenu.targetType === 'database'
  && isDatabaseVectorizing(contextMenu.connectionId, contextMenu.databaseName),
);

const canViewContextVectorizedData = computed(() =>
  contextMenu.targetType === 'database'
  && !!contextMenu.databaseName
  && !isDatabaseVectorizing(contextMenu.connectionId, contextMenu.databaseName),
);

const canInterruptContextVectorize = computed(() =>
  contextMenu.targetType === 'database'
  && !!contextMenu.databaseName
  && isDatabaseVectorizing(contextMenu.connectionId, contextMenu.databaseName),
);

const connectionSelectOptions = computed(() =>
  connections.value.map((item) => ({ label: `${item.name} (${item.env})`, value: item.id })),
);

const connectionTreeData = computed(() => {
  const keyword = connectionKeyword.value.trim().toLowerCase();
  const filtered = keyword
    ? connections.value.filter((item) => item.name.toLowerCase().includes(keyword))
    : connections.value;

  const envMap = new Map<string, ConnectionVO[]>();
  filtered.forEach((item) => {
    const list = envMap.get(item.env) ?? [];
    list.push(item);
    envMap.set(item.env, list);
  });

  return Array.from(envMap.entries()).map(([env, list]) => ({
    key: `env-${env}`,
    title: `${env}`,
    nodeType: 'env',
    selectable: false,
    children: list.map((conn) => buildConnectionNode(conn)),
  }));
});

const objectRows = computed<ObjectRow[]>(() => {
  if (currentObjectType.value === 'tables') {
    return (schemaOverview.value?.tableSummaries ?? []).map((item) => ({
      objectName: item.tableName,
      objectType: 'tables',
      rowEstimate: item.rowEstimate ?? 0,
      tableSize: formatSize(item.tableSizeBytes ?? 0),
      description: item.tableComment ?? '',
    }));
  }
  const databaseName = getActiveDatabaseName(workflow.connectionId);
  const names = objectNameCache.value[objectCacheKey(workflow.connectionId, databaseName, currentObjectType.value)] ?? [];
  return names.map((name) => ({
    objectName: name,
    objectType: currentObjectType.value,
    rowEstimate: 0,
    tableSize: '-',
    description: objectTypeLabel(currentObjectType.value),
  }));
});

const selectedObjectRecord = computed(() =>
  objectRows.value.find((item) => item.objectName === selectedObjectName.value) ?? null,
);

const filteredObjectRows = computed(() => {
  const keyword = tableKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return objectRows.value;
  }
  return objectRows.value.filter((item) => item.objectName.toLowerCase().includes(keyword));
});

const objectColumns = computed(() => {
  if (currentObjectType.value === 'tables') {
    return [
      { title: '名称', dataIndex: 'objectName', key: 'objectName', width: 260, ellipsis: true },
      { title: '行', dataIndex: 'rowEstimate', key: 'rowEstimate', width: 120 },
      { title: '数据长度', dataIndex: 'tableSize', key: 'tableSize', width: 120 },
      { title: '说明', dataIndex: 'description', key: 'description', width: 360, ellipsis: true },
    ];
  }
  return [
    { title: '名称', dataIndex: 'objectName', key: 'objectName', width: 360, ellipsis: true },
    { title: '类型', dataIndex: 'description', key: 'description', width: 180, ellipsis: true },
  ];
});

const detailColumns = [
  { title: '字段', dataIndex: 'columnName', key: 'columnName', width: 120, ellipsis: true },
  { title: '类型', dataIndex: 'dataType', key: 'dataType', width: 110, ellipsis: true },
  { title: '长度', dataIndex: 'columnSize', key: 'columnSize', width: 70, ellipsis: true },
  { title: '小数位', dataIndex: 'decimalDigits', key: 'decimalDigits', width: 70, ellipsis: true },
  { title: '默认值', dataIndex: 'defaultValue', key: 'defaultValue', width: 120, ellipsis: true },
  { title: '自增', dataIndex: 'autoIncrement', key: 'autoIncrement', width: 60, ellipsis: true },
  { title: '可空', dataIndex: 'nullable', key: 'nullable', width: 70, ellipsis: true },
  { title: '索引', dataIndex: 'indexed', key: 'indexed', width: 60, ellipsis: true },
  { title: '主键', dataIndex: 'primaryKey', key: 'primaryKey', width: 60, ellipsis: true },
  { title: '备注', dataIndex: 'columnComment', key: 'columnComment', ellipsis: true },
];

const tableScrollY = computed(() => Math.max(300, viewportHeight.value - 300));
const detailScrollY = computed(() => Math.max(220, viewportHeight.value - 360));
const queryResultScrollY = computed(() => Math.max(200, viewportHeight.value - 520));
const aiModelOptions = computed(() =>
  (aiConfigForm.modelOptions ?? []).map((item) => ({
    label: `${item.name || item.id || '-'} · ${item.providerType === 'LOCAL_CLI' ? 'CLI' : (item.openaiModel || 'OPENAI')}`,
    value: item.id,
  })),
);
const workbenchStyle = computed(() => {
  if (viewportWidth.value < 1200) {
    return {};
  }
  if (activeWorkbenchTab.value === browserTabKey) {
    return {
      gridTemplateColumns: `270px minmax(460px, 1fr) 6px ${browserRightPaneWidth.value}px`,
    };
  }
  return {
    gridTemplateColumns: '270px minmax(560px, 1.12fr) minmax(420px, 0.88fr)',
  };
});

const activeResultRows = computed(() => {
  if (!activeQueryTab.value) {
    return [] as Array<Record<string, string | null> & { __rowKey: string }>;
  }
  const rows = activeQueryTab.value.executeResult?.rows ?? activeQueryTab.value.explainResult?.rows ?? [];
  return rows.map((row, index) => {
    const result: Record<string, string | null> & { __rowKey: string } = { __rowKey: `${index}` };
    row.cells.forEach((cell) => {
      result[cell.columnName] = cell.cellValue;
    });
    return result;
  });
});

const activeResultColumns = computed(() => {
  if (!activeQueryTab.value) {
    return [];
  }
  const rows = activeQueryTab.value.executeResult?.rows ?? activeQueryTab.value.explainResult?.rows ?? [];
  if (!rows.length) {
    return [];
  }
  return rows[0].cells.map((cell) => ({
    title: cell.columnName,
    dataIndex: cell.columnName,
    key: cell.columnName,
    width: 180,
    ellipsis: true,
  }));
});

const queryResultScrollX = computed(() => Math.max(activeResultColumns.value.length * 180, 960));

function buildConnectionNode(conn: ConnectionVO) {
  if (requiresDatabaseLayer(conn)) {
    const databases = databaseListCache.value[conn.id] ?? [];
    const activeDbName = getActiveDatabaseName(conn.id);
    const databaseNodes = (databases.length ? databases : ['未发现数据库']).map((databaseName) => ({
      key: buildDatabaseNodeKey(conn.id, databaseName),
      title: databaseName,
      nodeType: 'database',
      vectorizeStatus: getDatabaseVectorizeStatus(conn.id, databaseName),
      selectable: databaseName !== '未发现数据库',
      children: buildCategoryChildren(conn.id, databaseName),
    }));
    return {
      key: `conn-${conn.id}`,
      title: conn.name,
      nodeType: 'connection',
      dbType: conn.dbType,
      children: databaseNodes,
    };
  }

  const configuredDbName = getActiveDatabaseName(conn.id);
  return {
    key: `conn-${conn.id}`,
    title: conn.name,
    nodeType: 'connection',
    dbType: conn.dbType,
    children: buildCategoryChildren(conn.id, configuredDbName),
  };
}

function buildCategoryChildren(connectionId: number, databaseName: string) {
  const categoryNodes = [
    { suffix: 'tables', title: '表', nodeType: 'tables' },
    { suffix: 'views', title: '视图', nodeType: 'views' },
    { suffix: 'functions', title: '函数', nodeType: 'functions' },
    { suffix: 'events', title: '事件', nodeType: 'events' },
    { suffix: 'queries', title: '查询', nodeType: 'queries' },
    { suffix: 'backups', title: '备份', nodeType: 'backups' },
  ];
  return categoryNodes.map((category) => ({
    key: buildCategoryNodeKey(connectionId, databaseName, category.suffix),
    title: category.title,
    nodeType: category.nodeType,
    selectable: true,
    children: getCategoryChildren(connectionId, databaseName, category.suffix),
  }));
}

function getCategoryChildren(connectionId: number, databaseName: string, category: string) {
  const names = category === 'tables'
    ? tableNameCache.value[tableCacheKey(connectionId, databaseName)] ?? []
    : objectNameCache.value[objectCacheKey(connectionId, databaseName, category)] ?? [];
  return names.map((name) => ({
    key: buildObjectNodeKey(connectionId, databaseName, category, name),
    title: name,
    nodeType: category,
    objectType: category,
    objectName: name,
  }));
}

function requiresDatabaseLayer(connection: ConnectionVO) {
  return !parseConfiguredDatabaseName(connection).trim();
}

function parseConfiguredDatabaseName(connection: ConnectionVO) {
  const direct = (connection.databaseName ?? '').trim();
  if (direct) {
    return sanitizeDatabaseName(direct);
  }
  const host = (connection.host ?? '').trim();
  if (!host) {
    return '';
  }
  const stripped = host.replace(/^jdbc:[^:]+:\/\//i, '').replace(/^[a-z]+:\/\//i, '');
  const atIndex = stripped.lastIndexOf('@');
  const hostPart = atIndex >= 0 ? stripped.substring(atIndex + 1) : stripped;
  const slashIndex = hostPart.indexOf('/');
  if (slashIndex >= 0 && slashIndex < hostPart.length - 1) {
    return sanitizeDatabaseName(hostPart.substring(slashIndex + 1));
  }
  return '';
}

function sanitizeDatabaseName(raw: string) {
  const queryIndex = raw.indexOf('?');
  const semicolonIndex = raw.indexOf(';');
  let value = raw;
  if (queryIndex >= 0) {
    value = value.substring(0, queryIndex);
  }
  if (semicolonIndex >= 0) {
    value = value.substring(0, semicolonIndex);
  }
  while (value.startsWith('/')) {
    value = value.substring(1);
  }
  return value.trim();
}

function tableCacheKey(connectionId: number, databaseName: string) {
  return `${connectionId}|${databaseName || '__default__'}`;
}

function objectCacheKey(connectionId: number, databaseName: string, objectType: string) {
  return `${connectionId}|${databaseName || '__default__'}|${objectType}`;
}

function vectorizeStatusCacheKey(connectionId: number, databaseName: string) {
  return `${connectionId}|${databaseName.trim().toLowerCase()}`;
}

function getDatabaseVectorizeStatus(connectionId: number, databaseName: string) {
  if (!databaseName || databaseName === '未发现数据库') {
    return 'NOT_VECTORIZED';
  }
  const key = vectorizeStatusCacheKey(connectionId, databaseName);
  return databaseVectorizeStatusMap.value[key]?.status || 'NOT_VECTORIZED';
}

function isDatabaseVectorizing(connectionId: number, databaseName: string) {
  const status = getDatabaseVectorizeStatus(connectionId, databaseName);
  return status === 'PENDING' || status === 'RUNNING';
}

function databaseStatusLabel(status: string) {
  if (status === 'PENDING') {
    return '排队中';
  }
  if (status === 'RUNNING') {
    return '向量化中';
  }
  if (status === 'SUCCESS') {
    return '已向量化';
  }
  if (status === 'FAILED') {
    return '失败';
  }
  return '未向量化';
}

function databaseStatusClass(status: string) {
  if (status === 'PENDING') {
    return 'is-pending';
  }
  if (status === 'RUNNING') {
    return 'is-running';
  }
  if (status === 'SUCCESS') {
    return 'is-success';
  }
  if (status === 'FAILED') {
    return 'is-failed';
  }
  return 'is-none';
}

function databaseStatusIcon(status: string) {
  if (status === 'PENDING') {
    return ClockCircleOutlined;
  }
  if (status === 'RUNNING') {
    return LoadingOutlined;
  }
  if (status === 'SUCCESS') {
    return CheckCircleOutlined;
  }
  if (status === 'FAILED') {
    return CloseCircleOutlined;
  }
  return MinusCircleOutlined;
}

function getActiveDatabaseName(connectionId: number) {
  const selected = (activeDatabaseMap.value[connectionId] ?? '').trim();
  if (selected) {
    return selected;
  }
  const connection = connections.value.find((item) => item.id === connectionId);
  if (!connection) {
    return '';
  }
  const configured = parseConfiguredDatabaseName(connection);
  return configured || '';
}

function activateBrowserTab() {
  activeWorkbenchTab.value = browserTabKey;
}

function openCreateModal() {
  closeContextMenu();
  resetConnectionForm();
  isEditMode.value = false;
  editingConnectionId.value = null;
  createModalOpen.value = true;
}

function openEditModal(targetConnectionId?: number) {
  closeContextMenu();
  if (targetConnectionId) {
    workflow.connectionId = targetConnectionId;
  }
  ensureConnection();
  const current = connections.value.find((item) => item.id === workflow.connectionId);
  if (!current) {
    message.warning('请先选择连接');
    return;
  }
  fillConnectionForm(current);
  isEditMode.value = true;
  editingConnectionId.value = current.id;
  createModalOpen.value = true;
}

function openAiQueryTab() {
  ensureConnection();
  const now = Date.now();
  const databaseName = getActiveDatabaseName(workflow.connectionId);
  const models = aiModelOptions.value.map((item) => String(item.value)).filter((item) => !!item);
  const initialPrompt = workflow.prompt || `查询 ${selectedObjectName.value || '当前数据库'} 最近数据`;
  const tab: QueryWorkspaceTab = {
    key: `query-${now}-${Math.round(Math.random() * 1000)}`,
    title: '未命名会话',
    connectionId: workflow.connectionId,
    databaseName,
    sessionId: `session-${now}`,
    prompt: initialPrompt,
    sqlText: workflow.sqlText,
    riskAckToken: '',
    riskInfo: null,
    executeResult: null,
    explainResult: null,
    selectedAiModel: models[0] ?? '',
    aiGenerating: false,
    selectedSqlText: '',
    chatMessages: [],
    createdAt: now,
    updatedAt: now,
  };
  applySessionTitle(tab);
  queryTabs.value = [...queryTabs.value, tab];
  activeWorkbenchTab.value = tab.key;
  void runSafely(async () => {
    await prepareConnectionTreeData(tab.connectionId);
    tab.databaseName = tab.databaseName || getActiveDatabaseName(tab.connectionId);
    await warmupTableSuggestions(tab);
  });
}

function closeQueryTab(tabKey: string) {
  const index = queryTabs.value.findIndex((item) => item.key === tabKey);
  if (index < 0) {
    return;
  }
  const tabs = [...queryTabs.value];
  tabs.splice(index, 1);
  queryTabs.value = tabs;
  if (activeWorkbenchTab.value === tabKey) {
    activeWorkbenchTab.value = tabs[index]?.key || tabs[index - 1]?.key || browserTabKey;
  }
}

function handleHistoryMenuClick() {
  if (!canOpenHistory.value) {
    return;
  }
  const connectionId = currentHistoryConnectionId.value;
  if (!connectionId) {
    return;
  }
  if (historySessionConnectionId.value !== connectionId) {
    historyKeywordInput.value = '';
    historyKeyword.value = '';
  }
  historySessionConnectionId.value = connectionId;
  cancelHistoryTitleEdit();
  void runSafely(async () => {
    await loadHistorySessionPage(true);
  });
}

function sessionRefKey(connectionId: number, sessionId: string) {
  return `${connectionId}::${sessionId}`;
}

function sessionTitleOverrideKey(sessionRef: { connectionId: number; sessionId: string }) {
  return sessionRefKey(sessionRef.connectionId, sessionRef.sessionId);
}

function historyItemKey(item: QueryHistorySessionVO) {
  return sessionRefKey(item.connectionId, item.sessionId);
}

function findQueryTabBySession(connectionId: number, sessionId: string) {
  return queryTabs.value.find((tab) => tab.connectionId === connectionId && tab.sessionId === sessionId) ?? null;
}

function queryTabConnectionNameById(connectionId?: number) {
  if (!connectionId) {
    return '';
  }
  return connections.value.find((item) => item.id === connectionId)?.name ?? '';
}

function normalizeTitleSource(text: string) {
  return text.replace(/\s+/g, ' ').trim();
}

function buildSessionDefaultTitle(text: string) {
  const normalized = normalizeTitleSource(text);
  if (!normalized) {
    return '未命名会话';
  }
  const splitIndex = normalized.search(/[。！？!?；;\n]/);
  const firstSentence = (splitIndex >= 0 ? normalized.slice(0, splitIndex) : normalized).trim() || normalized;
  return firstSentence.length > 20 ? `${firstSentence.slice(0, 20)}...` : firstSentence;
}

function firstPromptForTitle(tab: QueryWorkspaceTab) {
  const firstUser = tab.chatMessages.find((item) => item.role === 'user' && item.content.trim());
  if (firstUser) {
    return firstUser.content;
  }
  return tab.prompt;
}

function applySessionTitle(tab: QueryWorkspaceTab) {
  const custom = (sessionTitleOverrides.value[sessionTitleOverrideKey(tab)] ?? '').trim();
  if (custom) {
    tab.title = custom;
    return;
  }
  tab.title = buildSessionDefaultTitle(firstPromptForTitle(tab));
}

function historyItemDisplayTitle(item: QueryHistorySessionVO) {
  const custom = (sessionTitleOverrides.value[sessionTitleOverrideKey(item)] ?? '').trim();
  if (custom) {
    return custom;
  }
  const source = (item.title ?? '').trim();
  return buildSessionDefaultTitle(source);
}

function isHistoryItemActive(item: QueryHistorySessionVO) {
  const tab = activeQueryTab.value;
  if (!tab) {
    return false;
  }
  return tab.connectionId === item.connectionId && tab.sessionId === item.sessionId;
}

function loadSessionTitleOverrides() {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    const raw = window.localStorage.getItem(sessionTitleOverridesStorageKey);
    if (!raw) {
      return;
    }
    const parsed = JSON.parse(raw) as Record<string, string>;
    if (!parsed || typeof parsed !== 'object') {
      return;
    }
    const next: Record<string, string> = {};
    Object.entries(parsed).forEach(([key, value]) => {
      if (typeof value !== 'string') {
        return;
      }
      const normalized = value.trim();
      if (normalized) {
        next[key] = normalized;
      }
    });
    sessionTitleOverrides.value = next;
  } catch {
    sessionTitleOverrides.value = {};
  }
}

function persistSessionTitleOverrides() {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage.setItem(sessionTitleOverridesStorageKey, JSON.stringify(sessionTitleOverrides.value));
  } catch {
    // 忽略本地存储异常，避免阻塞主流程。
  }
}

function startHistoryTitleEdit(item: QueryHistorySessionVO) {
  editingHistoryTabKey.value = historyItemKey(item);
  editingHistoryTitle.value = historyItemDisplayTitle(item);
}

function commitHistoryTitleEdit(item: QueryHistorySessionVO) {
  const key = historyItemKey(item);
  if (editingHistoryTabKey.value !== key) {
    return;
  }
  const overrideKey = sessionTitleOverrideKey(item);
  const renamed = editingHistoryTitle.value.trim();
  const next = {...sessionTitleOverrides.value};
  if (renamed) {
    next[overrideKey] = renamed;
  } else {
    delete next[overrideKey];
  }
  sessionTitleOverrides.value = next;
  persistSessionTitleOverrides();
  const tab = findQueryTabBySession(item.connectionId, item.sessionId);
  if (tab) {
    if (renamed) {
      tab.title = renamed;
    } else {
      applySessionTitle(tab);
    }
  }
  editingHistoryTabKey.value = '';
  editingHistoryTitle.value = '';
}

function cancelHistoryTitleEdit() {
  editingHistoryTabKey.value = '';
  editingHistoryTitle.value = '';
}

function buildHistoryChatMessages(connectionId: number, sessionId: string, rows: QueryHistoryVO[]) {
  const ordered = [...rows].sort((a, b) => (a.createdAt ?? 0) - (b.createdAt ?? 0));
  const messages: QueryChatMessage[] = [];
  ordered.forEach((item, index) => {
    const promptText = (item.promptText ?? '').trim();
    const sqlText = (item.sqlText ?? '').trim();
    if (!promptText || !sqlText) {
      return;
    }
    const ts = item.createdAt ?? Date.now() + index;
    messages.push({
      id: `chat-history-user-${connectionId}-${encodeURIComponent(sessionId)}-${item.id ?? index}`,
      role: 'user',
      content: promptText,
      actionType: 'generate',
      createdAt: ts,
    });
    messages.push({
      id: `chat-history-assistant-${connectionId}-${encodeURIComponent(sessionId)}-${item.id ?? index}`,
      role: 'assistant',
      content: '',
      sqlText,
      actionType: 'generate',
      createdAt: ts + 1,
    });
  });
  return messages;
}

function buildHistoryTabFromRows(connectionId: number, sessionId: string, rows: QueryHistoryVO[]) {
  const messages = buildHistoryChatMessages(connectionId, sessionId, rows);
  const ordered = [...rows].sort((a, b) => (a.createdAt ?? 0) - (b.createdAt ?? 0));
  const last = ordered[ordered.length - 1];
  const first = ordered[0];
  const models = aiModelOptions.value.map((item) => String(item.value)).filter((item) => !!item);
  const tab: QueryWorkspaceTab = {
    key: `query-history-${connectionId}-${encodeURIComponent(sessionId)}`,
    title: '未命名会话',
    connectionId,
    databaseName: getActiveDatabaseName(connectionId),
    sessionId,
    prompt: '',
    sqlText: (last?.sqlText ?? '').trim(),
    riskAckToken: '',
    riskInfo: null,
    executeResult: null,
    explainResult: null,
    selectedAiModel: models[0] ?? '',
    aiGenerating: false,
    selectedSqlText: '',
    chatMessages: messages,
    createdAt: first?.createdAt ?? Date.now(),
    updatedAt: last?.createdAt ?? Date.now(),
  };
  applySessionTitle(tab);
  return tab;
}

async function loadHistorySessionPage(reset: boolean) {
  if (!historySessionConnectionId.value) {
    return;
  }
  if (reset) {
    if (historyReloading.value) {
      return;
    }
    historyReloading.value = true;
  } else {
    if (historyLoadingMore.value || historyReloading.value || !historySessionHasMore.value) {
      return;
    }
    historyLoadingMore.value = true;
  }
  try {
    const requestPageNo = reset ? 1 : historySessionPageNo.value;
    const params = new URLSearchParams({
      connectionId: `${historySessionConnectionId.value}`,
      pageNo: `${requestPageNo}`,
      pageSize: `${historySessionPageSize}`,
    });
    if (historyKeyword.value) {
      params.set('keyword', historyKeyword.value);
    }
    const page = await getApi<QueryHistorySessionPageVO>(`/api/editor/history/session/page?${params.toString()}`);
    const pageItems = page.items ?? [];
    if (reset) {
      historySessionItems.value = pageItems;
    } else if (pageItems.length) {
      const merged = [...historySessionItems.value];
      const indexMap = new Map<string, number>();
      merged.forEach((entry, idx) => {
        indexMap.set(historyItemKey(entry), idx);
      });
      pageItems.forEach((entry) => {
        const entryKey = historyItemKey(entry);
        const existed = indexMap.get(entryKey);
        if (existed === undefined) {
          indexMap.set(entryKey, merged.length);
          merged.push(entry);
        } else {
          merged[existed] = entry;
        }
      });
      historySessionItems.value = merged;
    }
    historySessionPageNo.value = (page.pageNo ?? requestPageNo) + 1;
    historySessionHasMore.value = !!page.hasMore;
  } finally {
    if (reset) {
      historyReloading.value = false;
    } else {
      historyLoadingMore.value = false;
    }
  }
}

function applyHistoryKeywordSearch() {
  historyKeyword.value = historyKeywordInput.value.trim();
  historySessionPageNo.value = 1;
  historySessionHasMore.value = true;
  historySessionItems.value = [];
  cancelHistoryTitleEdit();
  void runSafely(async () => {
    await loadHistorySessionPage(true);
  });
}

function handleHistoryMenuScroll(event: Event) {
  if (historyLoadingMore.value || historyReloading.value || !historySessionHasMore.value) {
    return;
  }
  const target = event.target as HTMLElement | null;
  if (!target) {
    return;
  }
  if (target.scrollTop + target.clientHeight < target.scrollHeight - 36) {
    return;
  }
  void runSafely(async () => {
    await loadHistorySessionPage(false);
  });
}

async function openHistorySession(item: QueryHistorySessionVO) {
  const loadingKey = historyItemKey(item);
  if (historySessionLoadingKey.value === loadingKey) {
    return;
  }
  historySessionLoadingKey.value = loadingKey;
  try {
    const params = new URLSearchParams({
      connectionId: `${item.connectionId}`,
      sessionId: item.sessionId,
      limit: '5000',
    });
    const rows = await getApi<QueryHistoryVO[]>(`/api/editor/history/session/detail?${params.toString()}`);
    if (!rows.length) {
      message.info('该会话暂无可展示历史');
      return;
    }
    const customTitle = (sessionTitleOverrides.value[sessionTitleOverrideKey(item)] ?? '').trim();
    const fallbackTitle = historyItemDisplayTitle(item);
    let tab = findQueryTabBySession(item.connectionId, item.sessionId);
    if (tab) {
      const loaded = buildHistoryTabFromRows(item.connectionId, item.sessionId, rows);
      tab.chatMessages = loaded.chatMessages;
      tab.sqlText = loaded.sqlText;
      tab.selectedSqlText = '';
      tab.executeResult = null;
      tab.explainResult = null;
      tab.riskInfo = null;
      tab.riskAckToken = '';
      tab.prompt = '';
      tab.createdAt = item.createdAt ?? loaded.createdAt;
      tab.updatedAt = item.updatedAt ?? loaded.updatedAt;
      tab.databaseName = tab.databaseName || loaded.databaseName;
      tab.title = customTitle || fallbackTitle;
    } else {
      tab = buildHistoryTabFromRows(item.connectionId, item.sessionId, rows);
      tab.prompt = '';
      tab.selectedSqlText = '';
      tab.title = customTitle || fallbackTitle;
      tab.createdAt = item.createdAt ?? tab.createdAt;
      tab.updatedAt = item.updatedAt ?? tab.updatedAt;
      queryTabs.value = [...queryTabs.value, tab];
    }
    activeWorkbenchTab.value = tab.key;
    await prepareConnectionTreeData(tab.connectionId);
    tab.databaseName = tab.databaseName || getActiveDatabaseName(tab.connectionId);
    await warmupTableSuggestions(tab);
  } finally {
    historySessionLoadingKey.value = '';
  }
}

function modelLabelById(modelId: string) {
  const model = aiConfigForm.modelOptions?.find((item) => item.id === modelId);
  if (!model) {
    return modelId || '-';
  }
  return model.name || model.id || '-';
}

function lastPromptText(tab: QueryWorkspaceTab) {
  const latestPrompt = [...tab.chatMessages].reverse().find((item) => item.role === 'user');
  return latestPrompt?.content || '暂无自然语言对话';
}

function assistantActionLabel(actionType: QueryChatMessage['actionType']) {
  if (actionType === 'explain') {
    return '解释 SQL';
  }
  if (actionType === 'analyze') {
    return '分析 SQL';
  }
  return '生成 SQL';
}

function touchQueryTab(tab: QueryWorkspaceTab) {
  tab.updatedAt = Date.now();
}

function appendUserChatMessage(tab: QueryWorkspaceTab, promptText: string, actionType: QueryChatMessage['actionType']) {
  const now = Date.now();
  tab.chatMessages.push({
    id: `chat-user-${now}-${Math.random().toString(16).slice(2, 8)}`,
    role: 'user',
    content: promptText,
    actionType,
    createdAt: now,
  });
  applySessionTitle(tab);
  touchQueryTab(tab);
}

function appendAssistantSqlMessage(tab: QueryWorkspaceTab, sqlText: string, actionType: QueryChatMessage['actionType']) {
  const now = Date.now();
  tab.chatMessages.push({
    id: `chat-assistant-${now}-${Math.random().toString(16).slice(2, 8)}`,
    role: 'assistant',
    content: '',
    sqlText,
    actionType,
    createdAt: now,
  });
  touchQueryTab(tab);
}

function appendAssistantTextMessage(tab: QueryWorkspaceTab, content: string, actionType: QueryChatMessage['actionType']) {
  const now = Date.now();
  tab.chatMessages.push({
    id: `chat-assistant-${now}-${Math.random().toString(16).slice(2, 8)}`,
    role: 'assistant',
    content: content.trim(),
    actionType,
    createdAt: now,
  });
  touchQueryTab(tab);
}

function appendSqlToEditor(tab: QueryWorkspaceTab, sqlText: string) {
  const value = sqlText.trim();
  if (!value) {
    return;
  }
  tab.sqlText = tab.sqlText.trim() ? `${tab.sqlText.trim()}\n\n${value}` : value;
  tab.selectedSqlText = '';
  touchQueryTab(tab);
  activeWorkbenchTab.value = tab.key;
}

function appendSelectedSqlToPrompt(tab: QueryWorkspaceTab) {
  const value = tab.selectedSqlText.trim();
  if (!value) {
    message.info('请先在右侧 SQL 编辑器中选择一段 SQL');
    return;
  }
  tab.prompt = tab.prompt.trim() ? `${tab.prompt.trim()}\n${value}` : value;
  touchQueryTab(tab);
}

async function prepareConnectionTreeData(connectionId: number) {
  const connection = connections.value.find((item) => item.id === connectionId);
  if (!connection) {
    return;
  }
  if (requiresDatabaseLayer(connection)) {
    await loadDatabaseListForConnection(connectionId);
  } else {
    const configuredDb = parseConfiguredDatabaseName(connection);
    if (configuredDb) {
      activeDatabaseMap.value = {
        ...activeDatabaseMap.value,
        [connectionId]: configuredDb,
      };
    }
  }
}

async function loadDatabaseListForConnection(connectionId: number) {
  if (databaseListCache.value[connectionId]?.length) {
    return;
  }
  const list = await getApi<SchemaDatabaseVO[]>(`/api/schema/databases?connectionId=${connectionId}`);
  const databaseNames = list.map((item) => item.databaseName).filter((item) => !!item);
  databaseListCache.value = {
    ...databaseListCache.value,
    [connectionId]: databaseNames,
  };
  const next = {...databaseVectorizeStatusMap.value};
  const prefix = `${connectionId}|`;
  Object.keys(next).forEach((key) => {
    if (key.startsWith(prefix)) {
      delete next[key];
    }
  });
  list.forEach((item) => {
    if (!item.databaseName) {
      return;
    }
    const key = vectorizeStatusCacheKey(connectionId, item.databaseName);
    next[key] = {
      databaseName: item.databaseName,
      status: item.vectorizeStatus,
      message: item.vectorizeMessage,
      updatedAt: item.vectorizeUpdatedAt,
    };
  });
  databaseVectorizeStatusMap.value = next;
}

async function refreshVectorizeStatusForConnection(connectionId: number) {
  const list = await getApi<RagDatabaseVectorizeStatusVO[]>(
    `/api/rag/vectorize/status/list?connectionId=${connectionId}`,
  );
  const next = {...databaseVectorizeStatusMap.value};
  const prefix = `${connectionId}|`;
  Object.keys(next).forEach((key) => {
    if (key.startsWith(prefix)) {
      delete next[key];
    }
  });
  list.forEach((item) => {
    if (!item.databaseName) {
      return;
    }
    const key = vectorizeStatusCacheKey(connectionId, item.databaseName);
    next[key] = item;
  });
  databaseVectorizeStatusMap.value = next;
}

async function refreshAllVectorizeStatuses(targetConnectionIds?: number[]) {
  const ids = targetConnectionIds ?? connections.value.map((item) => item.id);
  if (!ids.length) {
    databaseVectorizeStatusMap.value = {};
    return;
  }
  await Promise.all(ids.map(async (connectionId) => {
    try {
      await refreshVectorizeStatusForConnection(connectionId);
    } catch {
      // 关键操作：状态轮询失败不阻断主流程，等待下一次轮询重试。
    }
  }));
}

function pruneVectorizeStatusMap(validConnectionIds: number[]) {
  const valid = new Set(validConnectionIds.map((item) => `${item}|`));
  const next: Record<string, RagDatabaseVectorizeStatusVO> = {};
  Object.entries(databaseVectorizeStatusMap.value).forEach(([key, value]) => {
    if (Array.from(valid).some((prefix) => key.startsWith(prefix))) {
      next[key] = value;
    }
  });
  databaseVectorizeStatusMap.value = next;
}

function startVectorizeStatusPolling() {
  stopVectorizeStatusPolling();
  vectorizeStatusPollTimer = window.setInterval(() => {
    const cachedConnectionIds = Object.keys(databaseListCache.value)
      .map((item) => Number(item))
      .filter((item) => Number.isFinite(item) && item > 0);
    const ids = cachedConnectionIds.length ? cachedConnectionIds : (workflow.connectionId ? [workflow.connectionId] : []);
    if (!ids.length) {
      return;
    }
    void refreshAllVectorizeStatuses(ids);
  }, vectorizeStatusPollIntervalMs);
}

function stopVectorizeStatusPolling() {
  if (vectorizeStatusPollTimer !== null) {
    window.clearInterval(vectorizeStatusPollTimer);
    vectorizeStatusPollTimer = null;
  }
}

async function loadConnections() {
  connectionRefreshing.value = true;
  try {
    const list = await getApi<ConnectionVO[]>('/api/connection/list');
    connections.value = list;
    pruneVectorizeStatusMap(list.map((item) => item.id));
    if (!list.length) {
      workflow.connectionId = 0;
      selectedTreeKeys.value = [];
      expandedTreeKeys.value = [];
      schemaOverview.value = null;
      queryTabs.value = [];
      activeWorkbenchTab.value = browserTabKey;
      historySessionConnectionId.value = 0;
      historySessionItems.value = [];
      historySessionPageNo.value = 1;
      historySessionHasMore.value = true;
      historyKeywordInput.value = '';
      historyKeyword.value = '';
      return;
    }
    await refreshAllVectorizeStatuses(list.map((item) => item.id));
    if (!workflow.connectionId || !list.some((item) => item.id === workflow.connectionId)) {
      workflow.connectionId = list[0].id;
    }
    if (historySessionConnectionId.value && !list.some((item) => item.id === historySessionConnectionId.value)) {
      historySessionConnectionId.value = 0;
      historySessionItems.value = [];
      historySessionPageNo.value = 1;
      historySessionHasMore.value = true;
      historyKeywordInput.value = '';
      historyKeyword.value = '';
    }
    currentObjectType.value = 'tables';
    selectedObjectName.value = '';
    clearObjectDetail();
    await prepareConnectionTreeData(workflow.connectionId);
    selectedTreeKeys.value = [`conn-${workflow.connectionId}`];
    expandConnectionNode(workflow.connectionId);
    const current = connections.value.find((item) => item.id === workflow.connectionId);
    if (current && (!requiresDatabaseLayer(current) || getActiveDatabaseName(workflow.connectionId))) {
      await loadOverview();
    } else {
      schemaOverview.value = null;
    }
  } finally {
    connectionRefreshing.value = false;
  }
}

async function refreshConnections() {
  await runSafely(async () => {
    await loadConnections();
  });
}

async function saveConnection() {
  await runSafely(async () => {
    const editing = isEditMode.value;
    const payload: ConnectionCreateReq & { id?: number } = {
      ...connectionForm,
    };
    const endpoint = editing ? '/api/connection/update' : '/api/connection/create';
    if (editing) {
      payload.id = editingConnectionId.value ?? undefined;
      if (!payload.id) {
        throw new Error('缺少待编辑连接 ID');
      }
    }
    const saved = await postApi<ConnectionVO>(endpoint, payload);
    createModalOpen.value = false;
    resetConnectionModalState();
    workflow.connectionId = saved.id;
    selectedTreeKeys.value = [`conn-${saved.id}`];
    message.success(editing ? '连接已更新' : '连接已创建');
    await loadConnections();
  });
}

async function testConnection(id: number) {
  await runSafely(async () => {
    const result = await postApi<{ success: boolean; message: string }>('/api/connection/test', { connectionId: id });
    message.info(result.message);
    await loadConnections();
  });
}

async function removeConnection(id: number) {
  await runSafely(async () => {
    await postApi<boolean>('/api/connection/remove', { id });
    message.success('连接已删除');
    if (workflow.connectionId === id) {
      workflow.connectionId = 0;
      schemaOverview.value = null;
      clearObjectDetail();
    }
    queryTabs.value = queryTabs.value.filter((item) => item.connectionId !== id);
    if (!queryTabs.value.some((item) => item.key === activeWorkbenchTab.value)) {
      activeWorkbenchTab.value = browserTabKey;
    }
    await loadConnections();
  });
}

async function syncSchema(targetConnectionId?: number) {
  if (targetConnectionId) {
    workflow.connectionId = targetConnectionId;
    selectedTreeKeys.value = [`conn-${targetConnectionId}`];
  }
  ensureConnection();
  await runSafely(async () => {
    const databaseName = getActiveDatabaseName(workflow.connectionId);
    const result = await postApi<{ success: boolean; tableCount: number; columnCount: number; message: string }>(
      '/api/schema/sync',
      { connectionId: workflow.connectionId, databaseName },
    );
    message.success(`${result.message}，表 ${result.tableCount}，字段 ${result.columnCount}`);
    currentObjectType.value = 'tables';
    await loadOverview();
  });
}

async function loadOverview() {
  ensureConnection();
  await runSafely(async () => {
    const databaseName = getActiveDatabaseName(workflow.connectionId);
    const query = databaseName
      ? `/api/schema/overview?connectionId=${workflow.connectionId}&databaseName=${encodeURIComponent(databaseName)}`
      : `/api/schema/overview?connectionId=${workflow.connectionId}`;
    const overview = await getApi<SchemaOverviewVO>(query);
    schemaOverview.value = overview;
    const cacheKey = tableCacheKey(workflow.connectionId, databaseName);
    const tableNames = (overview.tableSummaries ?? []).map((item) => item.tableName);
    tableNameCache.value = {
      ...tableNameCache.value,
      [cacheKey]: tableNames,
    };
    tableNameLoadedCache.value = {
      ...tableNameLoadedCache.value,
      [cacheKey]: true,
    };
    objectNameCache.value = {
      ...objectNameCache.value,
      [objectCacheKey(workflow.connectionId, databaseName, 'tables')]: tableNames,
    };
    expandConnectionNode(workflow.connectionId);
  });
}

async function loadTableNamesByConnection(connectionId: number, databaseName: string) {
  if (!connectionId || !databaseName) {
    return [];
  }
  const query = `/api/schema/overview?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}`;
  const overview = await getApi<SchemaOverviewVO>(query);
  const tableNames = (overview.tableSummaries ?? []).map((item) => item.tableName);
  const cacheKey = tableCacheKey(connectionId, databaseName);
  tableNameCache.value = {
    ...tableNameCache.value,
    [cacheKey]: tableNames,
  };
  tableNameLoadedCache.value = {
    ...tableNameLoadedCache.value,
    [cacheKey]: true,
  };
  objectNameCache.value = {
    ...objectNameCache.value,
    [objectCacheKey(connectionId, databaseName, 'tables')]: tableNames,
  };
  return tableNames;
}

function resolveQueryDatabaseName(tab: QueryWorkspaceTab | null) {
  if (!tab) {
    return '';
  }
  return (tab.databaseName || getActiveDatabaseName(tab.connectionId)).trim();
}

async function ensureTableNamesLoaded(connectionId: number, databaseName: string) {
  if (!connectionId || !databaseName || databaseName === '未发现数据库') {
    return [];
  }
  const cacheKey = tableCacheKey(connectionId, databaseName);
  const loaded = !!tableNameLoadedCache.value[cacheKey];
  if (loaded) {
    return tableNameCache.value[cacheKey] ?? [];
  }
  const pending = pendingTableNameLoads.get(cacheKey);
  if (pending) {
    return pending;
  }
  const task = loadTableNamesByConnection(connectionId, databaseName)
    .catch(() => {
      tableNameLoadedCache.value = {
        ...tableNameLoadedCache.value,
        [cacheKey]: true,
      };
      return [];
    })
    .finally(() => {
      pendingTableNameLoads.delete(cacheKey);
    });
  pendingTableNameLoads.set(cacheKey, task);
  return task;
}

function tableNameSuggestions(
  monaco: typeof MonacoApi,
  names: string[],
  range: MonacoApi.IRange,
  prefix: string,
  databaseName: string,
) {
  const keyword = prefix.trim().toLowerCase();
  const uniqueNames = Array.from(new Set(names.filter((item) => !!item)));
  const matched = keyword
    ? uniqueNames.filter((name) => name.toLowerCase().includes(keyword))
    : uniqueNames;
  return matched.slice(0, 300).map((name) => {
    const startsWithPrefix = keyword && name.toLowerCase().startsWith(keyword);
    return {
      label: name,
      kind: monaco.languages.CompletionItemKind.Struct,
      insertText: name,
      range,
      detail: `表 · ${databaseName}`,
      sortText: `${startsWithPrefix ? '0' : '1'}_${name}`,
    };
  });
}

function sqlKeywordSuggestions(
  monaco: typeof MonacoApi,
  range: MonacoApi.IRange,
  prefix: string,
) {
  const keyword = prefix.trim().toUpperCase();
  const matched = keyword
    ? sqlKeywords.filter((item) => item.includes(keyword))
    : sqlKeywords;
  return matched.map((item) => {
    const startsWithPrefix = keyword && item.startsWith(keyword);
    return {
      label: item,
      kind: monaco.languages.CompletionItemKind.Keyword,
      insertText: item,
      range,
      detail: 'SQL 关键字',
      sortText: `${startsWithPrefix ? '0' : '1'}_keyword_${item}`,
    };
  });
}

function registerSqlCompletionProvider(monaco: typeof MonacoApi) {
  if (sqlCompletionProviderDisposable) {
    return;
  }
  sqlCompletionProviderDisposable = monaco.languages.registerCompletionItemProvider('sql', {
    triggerCharacters: ['.', '`'],
    provideCompletionItems: async (model, position) => {
      const tab = activeQueryTab.value;
      if (!tab) {
        return { suggestions: [] };
      }
      const word = model.getWordUntilPosition(position);
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: word.startColumn,
        endColumn: word.endColumn,
      };
      const keywordSuggestions = sqlKeywordSuggestions(monaco, range, word.word || '');
      const databaseName = resolveQueryDatabaseName(tab);
      if (!databaseName || databaseName === '未发现数据库') {
        return { suggestions: keywordSuggestions };
      }
      const tableNames = await ensureTableNamesLoaded(tab.connectionId, databaseName);
      return {
        suggestions: [
          ...tableNameSuggestions(monaco, tableNames, range, word.word || '', databaseName),
          ...keywordSuggestions,
        ],
      };
    },
  });
}

function registerSqlAutoSuggest(editor: MonacoApi.editor.IStandaloneCodeEditor) {
  sqlEditorTypeDisposable?.dispose();
  sqlEditorTypeDisposable = editor.onDidChangeModelContent((event) => {
    if (event.isFlush || !event.changes.length) {
      return;
    }
    const latestChange = event.changes[event.changes.length - 1];
    const typedText = latestChange.text ?? '';
    if (!typedText || typedText.length > 2 || /\s/.test(typedText)) {
      return;
    }
    if (!/[\w.`]/.test(typedText)) {
      return;
    }
    if (sqlAutoSuggestTimer !== null) {
      window.clearTimeout(sqlAutoSuggestTimer);
    }
    sqlAutoSuggestTimer = window.setTimeout(() => {
      editor.trigger('sql-auto-suggest', 'editor.action.triggerSuggest', {});
    }, 60);
    syncSelectedSqlForActiveTab();
  });
}

function readSelectedSql(editor: MonacoApi.editor.IStandaloneCodeEditor) {
  const model = editor.getModel();
  const selection = editor.getSelection();
  if (!model || !selection || selection.isEmpty()) {
    return '';
  }
  return model.getValueInRange(selection).trim();
}

function syncSelectedSqlForActiveTab() {
  if (!activeSqlEditorInstance || !activeQueryTab.value) {
    return;
  }
  activeQueryTab.value.selectedSqlText = readSelectedSql(activeSqlEditorInstance);
}

function registerSqlSelectionTracker(editor: MonacoApi.editor.IStandaloneCodeEditor) {
  sqlEditorSelectionDisposable?.dispose();
  sqlEditorSelectionDisposable = editor.onDidChangeCursorSelection(() => {
    syncSelectedSqlForActiveTab();
  });
}

async function warmupTableSuggestions(tab: QueryWorkspaceTab | null) {
  if (!tab) {
    return;
  }
  const databaseName = resolveQueryDatabaseName(tab);
  if (!databaseName || databaseName === '未发现数据库') {
    return;
  }
  await ensureTableNamesLoaded(tab.connectionId, databaseName);
}

function handleSqlEditorMount(
  editor: MonacoApi.editor.IStandaloneCodeEditor,
  monaco: typeof MonacoApi,
) {
  activeSqlEditorInstance = editor;
  registerSqlCompletionProvider(monaco);
  registerSqlAutoSuggest(editor);
  registerSqlSelectionTracker(editor);
  syncSelectedSqlForActiveTab();
  void warmupTableSuggestions(activeQueryTab.value);
}

async function loadObjectNames(connectionId: number, databaseName: string, objectType: string) {
  const query = `/api/schema/objectNames?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}&objectType=${encodeURIComponent(objectType)}`;
  const names = await getApi<string[]>(query);
  objectNameCache.value = {
    ...objectNameCache.value,
    [objectCacheKey(connectionId, databaseName, objectType)]: names,
  };
}

async function refreshCurrentObjects() {
  ensureConnection();
  await runSafely(async () => {
    const databaseName = getActiveDatabaseName(workflow.connectionId);
    if (currentObjectType.value === 'tables') {
      await loadOverview();
      return;
    }
    await loadObjectNames(workflow.connectionId, databaseName, currentObjectType.value);
  });
}

async function loadCategoryObjects(connectionId: number, databaseName: string, category: string) {
  currentObjectType.value = toObjectType(category);
  if (currentObjectType.value === 'tables') {
    await loadOverview();
    return;
  }
  await runSafely(async () => {
    await loadObjectNames(connectionId, databaseName, currentObjectType.value);
    expandCategoryNode(connectionId, databaseName, currentObjectType.value);
  });
}

async function handleTreeSelect(keys: (string | number)[]) {
  if (!keys.length) {
    return;
  }
  closeContextMenu();
  const value = String(keys[0]);
  selectedTreeKeys.value = [value];

  const connectionMatch = value.match(/^conn-(\d+)$/);
  if (connectionMatch) {
    const connectionId = Number(connectionMatch[1]);
    workflow.connectionId = connectionId;
    currentObjectType.value = 'tables';
    selectedObjectName.value = '';
    clearObjectDetail();
    await prepareConnectionTreeData(connectionId);
    expandConnectionNode(connectionId);
    const current = connections.value.find((item) => item.id === connectionId);
    if (current && (!requiresDatabaseLayer(current) || getActiveDatabaseName(connectionId))) {
      await loadOverview();
    } else {
      schemaOverview.value = null;
    }
    return;
  }

  const objectMatch = value.match(/^conn-(\d+)-db-(.+?)-obj-([a-z]+)-(.+)$/);
  if (objectMatch) {
    const connectionId = Number(objectMatch[1]);
    const databaseName = decodeURIComponent(objectMatch[2]);
    const objectType = toObjectType(objectMatch[3]);
    const objectName = decodeURIComponent(objectMatch[4]);
    workflow.connectionId = connectionId;
    activeDatabaseMap.value = {
      ...activeDatabaseMap.value,
      [connectionId]: databaseName,
    };
    currentObjectType.value = objectType;
    expandConnectionNode(connectionId);
    await runSafely(async () => {
      await selectObject(connectionId, databaseName, objectType, objectName);
    });
    return;
  }

  const categoryMatch = value.match(/^conn-(\d+)-db-(.+?)-category-([a-z]+)$/);
  if (categoryMatch) {
    const connectionId = Number(categoryMatch[1]);
    const databaseName = decodeURIComponent(categoryMatch[2]);
    const category = categoryMatch[3];
    workflow.connectionId = connectionId;
    activeDatabaseMap.value = {
      ...activeDatabaseMap.value,
      [connectionId]: databaseName,
    };
    selectedObjectName.value = '';
    clearObjectDetail();
    await loadCategoryObjects(connectionId, databaseName, category);
    return;
  }

  const databaseMatch = value.match(/^conn-(\d+)-db-(.+)$/);
  if (databaseMatch) {
    const connectionId = Number(databaseMatch[1]);
    const databaseName = decodeURIComponent(databaseMatch[2]);
    workflow.connectionId = connectionId;
    activeDatabaseMap.value = {
      ...activeDatabaseMap.value,
      [connectionId]: databaseName,
    };
    currentObjectType.value = 'tables';
    selectedObjectName.value = '';
    clearObjectDetail();
    expandConnectionNode(connectionId);
    await loadOverview();
  }
}

function handleTreeExpand(keys: (string | number)[]) {
  expandedTreeKeys.value = keys.map((item) => String(item));
}

async function handleTreeRightClick(event: { event: MouseEvent; node: { key?: string | number } }) {
  event.event.preventDefault();
  event.event.stopPropagation();
  const keyValue = String(event.node?.key ?? '');
  const objectMatch = keyValue.match(/^conn-(\d+)-db-(.+?)-obj-([a-z]+)-(.+)$/);
  if (objectMatch) {
    return;
  }
  const categoryMatch = keyValue.match(/^conn-(\d+)-db-(.+?)-category-([a-z]+)$/);
  if (categoryMatch) {
    return;
  }

  const connectionMatch = keyValue.match(/^conn-(\d+)$/);
  const databaseMatch = keyValue.match(/^conn-(\d+)-db-(.+)$/);
  if (!connectionMatch && !databaseMatch) {
    return;
  }

  if (connectionMatch) {
    const connectionId = Number(connectionMatch[1]);
    workflow.connectionId = connectionId;
    selectedTreeKeys.value = [keyValue];
    contextMenu.visible = true;
    contextMenu.x = Math.min(event.event.clientX, window.innerWidth - 220);
    contextMenu.y = Math.min(event.event.clientY, window.innerHeight - 180);
    contextMenu.targetType = 'connection';
    contextMenu.connectionId = connectionId;
    contextMenu.databaseName = '';
    return;
  }

  if (!databaseMatch) {
    return;
  }
  const connectionId = Number(databaseMatch[1]);
  const databaseName = decodeURIComponent(databaseMatch[2]);
  if (databaseName === '未发现数据库') {
    return;
  }
  workflow.connectionId = connectionId;
  activeDatabaseMap.value = {
    ...activeDatabaseMap.value,
    [connectionId]: databaseName,
  };
  try {
    await refreshVectorizeStatusForConnection(connectionId);
  } catch {
    // 关键操作：右键菜单唤起时尝试刷新状态，失败则沿用本地缓存状态。
  }
  selectedTreeKeys.value = [keyValue];
  contextMenu.visible = true;
  contextMenu.x = Math.min(event.event.clientX, window.innerWidth - 220);
  contextMenu.y = Math.min(event.event.clientY, window.innerHeight - 180);
  contextMenu.targetType = 'database';
  contextMenu.connectionId = connectionId;
  contextMenu.databaseName = databaseName;
}

function closeContextMenu() {
  contextMenu.visible = false;
  contextMenu.targetType = 'none';
  contextMenu.databaseName = '';
}

async function triggerContextAction(action: 'edit' | 'test' | 'sync' | 'delete' | 'revectorize' | 'interruptVectorize' | 'viewVectorizedData') {
  const id = contextMenu.connectionId;
  const databaseName = contextMenu.databaseName;
  const targetType = contextMenu.targetType;
  closeContextMenu();
  if (!id) {
    return;
  }
  if (action === 'revectorize') {
    if (targetType !== 'database' || !databaseName) {
      return;
    }
    if (isDatabaseVectorizing(id, databaseName)) {
      message.info('该数据库正在向量化，请等待当前任务完成');
      return;
    }
    await enqueueDatabaseRevectorize(id, databaseName);
    return;
  }
  if (action === 'interruptVectorize') {
    if (targetType !== 'database' || !databaseName) {
      return;
    }
    await interruptDatabaseVectorize(id, databaseName);
    return;
  }
  if (action === 'viewVectorizedData') {
    if (targetType !== 'database' || !databaseName) {
      return;
    }
    await openVectorizeOverview(id, databaseName);
    return;
  }
  if (action === 'edit') {
    openEditModal(id);
    return;
  }
  if (action === 'test') {
    await testConnection(id);
    return;
  }
  if (action === 'sync') {
    await syncSchema(id);
    return;
  }
  await removeConnection(id);
}

async function openVectorizeOverview(connectionId: number, databaseName: string) {
  vectorizeOverviewModalOpen.value = true;
  vectorizeOverviewLoading.value = true;
  vectorizeOverviewData.value = null;
  try {
    vectorizeOverviewData.value = await getApi<RagVectorizeOverviewVO>(
      `/api/rag/vectorize/overview?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}`,
    );
    if ((vectorizeOverviewData.value.totalVectorCount ?? 0) <= 0) {
      message.info('该数据库暂无向量化数据');
      vectorizeOverviewModalOpen.value = false;
    }
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    message.error(msg);
    vectorizeOverviewModalOpen.value = false;
  } finally {
    vectorizeOverviewLoading.value = false;
  }
}

async function enqueueDatabaseRevectorize(connectionId: number, databaseName: string) {
  await runSafely(async () => {
    const result = await postApi<RagVectorizeEnqueueVO>('/api/rag/vectorize/enqueue', {
      connectionId,
      databaseName,
    });
    if (result.enqueued) {
      const key = vectorizeStatusCacheKey(connectionId, databaseName);
      databaseVectorizeStatusMap.value = {
        ...databaseVectorizeStatusMap.value,
        [key]: {
          databaseName,
          status: 'PENDING',
          message: result.message,
          updatedAt: Date.now(),
        },
      };
    }
    await refreshVectorizeStatusForConnection(connectionId);
    if (result.enqueued) {
      message.success(`${result.message}（队列数: ${result.queueSize}）`);
      return;
    }
    message.info(`${result.message}（队列数: ${result.queueSize}）`);
  });
}

async function interruptDatabaseVectorize(connectionId: number, databaseName: string) {
  await runSafely(async () => {
    const result = await postApi<RagVectorizeInterruptVO>('/api/rag/vectorize/interrupt', {
      connectionId,
      databaseName,
    });
    const key = vectorizeStatusCacheKey(connectionId, databaseName);
    databaseVectorizeStatusMap.value = {
      ...databaseVectorizeStatusMap.value,
      [key]: {
        databaseName,
        status: result.status,
        message: result.message,
        updatedAt: result.updatedAt ?? Date.now(),
      },
    };
    await refreshVectorizeStatusForConnection(connectionId);
    if (result.interrupted) {
      message.success(result.message);
      return;
    }
    message.info(result.message);
  });
}

function onObjectRow(record: ObjectRow) {
  return {
    onClick: () => {
      const databaseName = getActiveDatabaseName(workflow.connectionId);
      void runSafely(async () => {
        await selectObject(workflow.connectionId, databaseName, record.objectType, record.objectName);
      });
    },
  };
}

async function selectObject(connectionId: number, databaseName: string, objectType: ObjectRow['objectType'], objectName: string) {
  selectedObjectName.value = objectName;
  workflow.prompt = `查询 ${objectName} 最近数据`;
  if (objectType === 'tables' || objectType === 'views') {
    workflow.sqlText = `SELECT * FROM ${objectName} LIMIT 100`;
  }
  await loadObjectDetail(connectionId, databaseName, objectType, objectName);
}

async function loadObjectDetail(
  connectionId: number,
  databaseName: string,
  objectType: ObjectRow['objectType'],
  objectName: string,
) {
  tableDetail.value = null;

  if (objectType !== 'tables') {
    return;
  }

  tableDetailLoading.value = true;
  try {
    const query = databaseName
      ? `/api/schema/tableDetail?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}&tableName=${encodeURIComponent(objectName)}`
      : `/api/schema/tableDetail?connectionId=${connectionId}&tableName=${encodeURIComponent(objectName)}`;
    tableDetail.value = await getApi<TableDetailVO>(query);
  } finally {
    tableDetailLoading.value = false;
  }
}

function clearObjectDetail() {
  tableDetail.value = null;
}

function startResizeBrowserPane(event: MouseEvent) {
  if (activeWorkbenchTab.value !== browserTabKey) {
    return;
  }
  event.preventDefault();
  browserPaneResizeState.resizing = true;
  browserPaneResizeState.startX = event.clientX;
  browserPaneResizeState.startWidth = browserRightPaneWidth.value;
  window.addEventListener('mousemove', handleResizeBrowserPane);
  window.addEventListener('mouseup', stopResizeBrowserPane);
}

function handleResizeBrowserPane(event: MouseEvent) {
  if (!browserPaneResizeState.resizing) {
    return;
  }
  const delta = browserPaneResizeState.startX - event.clientX;
  const next = browserPaneResizeState.startWidth + delta;
  browserRightPaneWidth.value = Math.min(760, Math.max(280, next));
}

function stopResizeBrowserPane() {
  if (!browserPaneResizeState.resizing) {
    return;
  }
  browserPaneResizeState.resizing = false;
  window.removeEventListener('mousemove', handleResizeBrowserPane);
  window.removeEventListener('mouseup', stopResizeBrowserPane);
}

function quickSelectAll() {
  if (!selectedObjectName.value) {
    message.warning('请先在中间列表选择一个对象');
    return;
  }
  if (currentObjectType.value !== 'tables' && currentObjectType.value !== 'views') {
    message.warning('当前对象类型不支持自动生成 SELECT');
    return;
  }
  workflow.sqlText = `SELECT * FROM ${selectedObjectName.value} LIMIT 100`;
  workflow.prompt = `查询 ${selectedObjectName.value} 最近数据`;
  openAiQueryTab();
}

async function openAiConfigModal() {
  aiConfigModalOpen.value = true;
  await runSafely(async () => {
    const aiConfig = await getApi<AiConfigVO>('/api/ai/config/get');
    const ragConfig = await getApi<RagConfigVO>('/api/rag/config/get');
    fillAiConfigForm(aiConfig);
    fillRagConfigForm(ragConfig);
  });
}

async function saveAiConfig() {
  await runSafely(async () => {
    const modelOptions = normalizeModelOptions(aiConfigForm.modelOptions);
    if (!modelOptions.length) {
      throw new Error('请至少配置一个模型');
    }
    aiConfigForm.modelOptions = modelOptions;
    aiConfigForm.providerType = modelOptions[0].providerType;
    aiConfigForm.openaiBaseUrl = modelOptions[0].openaiBaseUrl || '';
    aiConfigForm.openaiApiKey = modelOptions[0].openaiApiKey || '';
    aiConfigForm.openaiModel = modelOptions[0].openaiModel || '';
    aiConfigForm.cliCommand = modelOptions[0].cliCommand || '';
    aiConfigForm.cliWorkingDir = modelOptions[0].cliWorkingDir || '';
    const savedAi = await postApi<AiConfigVO>('/api/ai/config/save', aiConfigForm);
    const savedRag = await postApi<RagConfigVO>('/api/rag/config/save', ragConfigForm);
    fillAiConfigForm(savedAi);
    fillRagConfigForm(savedRag);
    aiConfigModalOpen.value = false;
    message.success('AI 与 RAG 配置已保存');
  });
}

function databaseOptionsForTab(tab: QueryWorkspaceTab) {
  const cached = databaseListCache.value[tab.connectionId] ?? [];
  if (cached.length) {
    return cached.map((item) => ({ label: item, value: item }));
  }
  const fallback = tab.databaseName || getActiveDatabaseName(tab.connectionId);
  if (!fallback) {
    return [];
  }
  return [{ label: fallback, value: fallback }];
}

function queryTabConnectionName(tab: QueryWorkspaceTab) {
  return connections.value.find((item) => item.id === tab.connectionId)?.name ?? '-';
}

async function handleQueryConnectionChange(tab: QueryWorkspaceTab) {
  await runSafely(async () => {
    await prepareConnectionTreeData(tab.connectionId);
    tab.databaseName = getActiveDatabaseName(tab.connectionId);
    tab.riskAckToken = '';
    tab.riskInfo = null;
    tab.executeResult = null;
    tab.explainResult = null;
    tab.selectedSqlText = '';
    touchQueryTab(tab);
    await warmupTableSuggestions(tab);
  });
}

function handleQueryDatabaseChange(tab: QueryWorkspaceTab) {
  tab.riskAckToken = '';
  touchQueryTab(tab);
  void warmupTableSuggestions(tab);
}

function resolveSqlForAction(tab: QueryWorkspaceTab, sqlOverride?: string) {
  const override = (sqlOverride ?? '').trim();
  if (override) {
    return override;
  }
  const selected = tab.selectedSqlText.trim();
  if (selected) {
    return selected;
  }
  return tab.sqlText.trim();
}

async function saveConversationHistory(tab: QueryWorkspaceTab, promptText: string, sqlText: string) {
  try {
    await postApi<boolean>('/api/editor/history/save', {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      promptText,
      sqlText,
      success: true,
    });
  } catch {
    // 关键操作：会话历史持久化失败不阻塞主流程。
  }
}

function buildAiPrompt(promptText: string, actionType: QueryChatMessage['actionType'], sqlSnippet?: string) {
  const snippet = (sqlSnippet ?? '').trim();
  if (actionType === 'explain') {
    return [
      '请用中文解释下面 SQL 的业务含义。',
      '要求：',
      '1) 不要执行 EXPLAIN；',
      '2) 用自然语言解释查询目的、条件、关联和聚合。',
      `用户补充：${promptText}`,
      snippet ? `SQL片段：\n${snippet}` : '',
    ].filter((item) => !!item).join('\n');
  }
  if (actionType === 'analyze') {
    return [
      '请基于数据库元数据分析下面 SQL 的合理性。',
      '要求：',
      '1) 输出结论、问题、优化建议；',
      '2) 不要执行 EXPLAIN；',
      '3) 指出不确定项。',
      `用户补充：${promptText}`,
      snippet ? `SQL片段：\n${snippet}` : '',
    ].filter((item) => !!item).join('\n');
  }
  return [
    '你是 SQL 生成助手。',
    '请根据用户需求生成可直接执行的 SQL。',
    '要求：',
    '1) 只返回 SQL，不要解释和 markdown；',
    '2) 语句尽量完整且可执行。',
    `用户需求：${promptText}`,
  ].join('\n');
}

async function generateSqlForTab(tab: QueryWorkspaceTab, actionType: QueryChatMessage['actionType'] = 'generate') {
  if (tab.aiGenerating) {
    return;
  }
  const rawPrompt = tab.prompt.trim();
  const actionSqlSnippet = actionType === 'generate' ? '' : resolveSqlForAction(tab);
  if (actionType === 'generate' && !rawPrompt) {
    message.info('请先输入自然语言需求');
    return;
  }
  if (actionType !== 'generate' && !rawPrompt && !actionSqlSnippet) {
    message.info('请先输入说明，或在右侧编辑器中选择 SQL');
    return;
  }
  const promptText = rawPrompt || (actionType === 'explain'
    ? '请解释这段 SQL 的含义。'
    : '请分析这段 SQL 的合理性。');
  appendUserChatMessage(tab, promptText, actionType);
  tab.prompt = '';
  const finalPrompt = buildAiPrompt(promptText, actionType, actionSqlSnippet);
  const sqlSnippet = actionType === 'generate' ? undefined : (actionSqlSnippet || undefined);
  tab.aiGenerating = true;
  await runSafely(async () => {
    if (actionType === 'generate') {
      const generated = await postApi<AiGenerateSqlVO>('/api/ai/query/generate', {
        connectionId: tab.connectionId,
        sessionId: tab.sessionId,
        prompt: finalPrompt,
        databaseName: tab.databaseName || undefined,
        sqlSnippet: undefined,
        modelName: tab.selectedAiModel || undefined,
      });
      appendAssistantSqlMessage(tab, generated.sqlText, actionType);
      await saveConversationHistory(tab, promptText, generated.sqlText);
      if (generated.reasoning) {
        message.info(generated.reasoning);
      }
      message.success('SQL 已生成');
      return;
    }

    const endpoint = actionType === 'explain' ? '/api/ai/query/explain' : '/api/ai/query/analyze';
    const result = await postApi<AiTextResponseVO>(endpoint, {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      prompt: finalPrompt,
      databaseName: tab.databaseName || undefined,
      sqlSnippet,
      modelName: tab.selectedAiModel || undefined,
    });
    appendAssistantTextMessage(tab, result.content || '未返回内容', actionType);
    if (result.reasoning) {
      message.info(result.reasoning);
    }
    message.success(actionType === 'explain' ? 'SQL 含义解释已生成' : 'SQL 合理性分析已生成');
  });
  tab.aiGenerating = false;
}

async function explainSqlForTab(tab: QueryWorkspaceTab, sqlOverride?: string) {
  await runSafely(async () => {
    const sqlText = resolveSqlForAction(tab, sqlOverride);
    if (!sqlText) {
      throw new Error('请先输入或选择 SQL');
    }
    tab.explainResult = await postApi<ExplainVO>('/api/sql/explain', {
      connectionId: tab.connectionId,
      sqlText,
      databaseName: tab.databaseName || undefined,
    });
    tab.executeResult = null;
    touchQueryTab(tab);
    message.success('EXPLAIN 完成');
  });
}

async function evaluateRiskForTab(tab: QueryWorkspaceTab) {
  await runSafely(async () => {
    const sqlText = resolveSqlForAction(tab);
    if (!sqlText) {
      throw new Error('请先输入或选择 SQL');
    }
    const result = await postApi<RiskEvaluateVO>('/api/sql/risk/evaluate', {
      connectionId: tab.connectionId,
      sqlText,
    });
    tab.riskInfo = result;
    tab.riskAckToken = result.riskAckToken ?? '';
    touchQueryTab(tab);
    message.info(`风险级别: ${result.riskLevel}`);
  });
}

async function executeSqlForTab(tab: QueryWorkspaceTab, sqlOverride?: string) {
  await runSafely(async () => {
    const sqlText = resolveSqlForAction(tab, sqlOverride);
    if (!sqlText) {
      throw new Error('请先输入或选择 SQL');
    }
    const result = await postApi<SqlExecuteVO>('/api/sql/execute', {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      sqlText,
      databaseName: tab.databaseName || undefined,
      riskAckToken: tab.riskAckToken,
      operatorName: 'desktop-user',
    });
    tab.executeResult = result;
    tab.explainResult = null;
    touchQueryTab(tab);
    message.success(`执行成功，耗时 ${result.executionMs}ms`);
  });
}

async function repairSqlForTab(tab: QueryWorkspaceTab) {
  await runSafely(async () => {
    const repaired = await postApi<{ repairedSql: string; repaired: boolean; repairNote: string }>('/api/ai/query/repair', {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      sqlText: tab.sqlText,
      errorMessage: 'unknown column',
    });
    tab.sqlText = repaired.repairedSql;
    tab.selectedSqlText = '';
    touchQueryTab(tab);
    message.success(repaired.repairNote);
  });
}

async function exportCsvForTab(tab: QueryWorkspaceTab) {
  await runSafely(async () => {
    const result = await postApi<{ filePath: string }>('/api/editor/result/export', {
      connectionId: tab.connectionId,
      sqlText: tab.sqlText,
      format: 'CSV',
      fileName: `aidb_${Date.now()}`,
    });
    message.success(`已导出: ${result.filePath}`);
  });
}

function riskColor(level: string) {
  if (level === 'HIGH') {
    return 'red';
  }
  if (level === 'MEDIUM') {
    return 'orange';
  }
  return 'green';
}

function ensureConnection() {
  if (!workflow.connectionId) {
    throw new Error('请先选择连接');
  }
}

async function runSafely(task: () => Promise<void>) {
  try {
    await task();
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    message.error(msg);
  }
}

function formatSize(sizeBytes: number) {
  if (sizeBytes >= 1024 * 1024) {
    return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
  }
  if (sizeBytes >= 1024) {
    return `${(sizeBytes / 1024).toFixed(1)} KB`;
  }
  return `${sizeBytes} B`;
}

function formatCompactCount(count?: number) {
  const value = count ?? 0;
  if (value <= 0) {
    return '0';
  }
  if (value < 1000) {
    return `${value}`;
  }
  return new Intl.NumberFormat('zh-CN', {
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value);
}

function formatTime(ts?: number) {
  if (!ts) {
    return '-';
  }
  const date = new Date(ts);
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  const hh = String(date.getHours()).padStart(2, '0');
  const mm = String(date.getMinutes()).padStart(2, '0');
  const ss = String(date.getSeconds()).padStart(2, '0');
  return `${y}-${m}-${d} ${hh}:${mm}:${ss}`;
}

function handleWindowResize() {
  viewportHeight.value = window.innerHeight;
  viewportWidth.value = window.innerWidth;
}

onMounted(async () => {
  window.addEventListener('resize', handleWindowResize);
  loadSessionTitleOverrides();
  startVectorizeStatusPolling();
  await loadConnections();
  await runSafely(async () => {
    const aiConfig = await getApi<AiConfigVO>('/api/ai/config/get');
    const ragConfig = await getApi<RagConfigVO>('/api/rag/config/get');
    fillAiConfigForm(aiConfig);
    fillRagConfigForm(ragConfig);
  });
});

onBeforeUnmount(() => {
  stopVectorizeStatusPolling();
  window.removeEventListener('resize', handleWindowResize);
  window.removeEventListener('mousemove', handleResizeBrowserPane);
  window.removeEventListener('mouseup', stopResizeBrowserPane);
  sqlEditorTypeDisposable?.dispose();
  sqlEditorTypeDisposable = null;
  sqlEditorSelectionDisposable?.dispose();
  sqlEditorSelectionDisposable = null;
  sqlCompletionProviderDisposable?.dispose();
  sqlCompletionProviderDisposable = null;
  activeSqlEditorInstance = null;
  if (sqlAutoSuggestTimer !== null) {
    window.clearTimeout(sqlAutoSuggestTimer);
    sqlAutoSuggestTimer = null;
  }
});

watch(
  () => [activeWorkbenchTab.value, activeQueryTab.value?.connectionId ?? 0, activeQueryTab.value?.databaseName ?? ''],
  () => {
    if (!activeQueryTab.value) {
      return;
    }
    activeQueryTab.value.selectedSqlText = '';
    void warmupTableSuggestions(activeQueryTab.value);
    syncSelectedSqlForActiveTab();
  },
  { immediate: true },
);

watch(
  () => connectionForm.dbType,
  (dbType) => {
    if (dbType === 'MYSQL' && (!connectionForm.port || connectionForm.port <= 0)) {
      connectionForm.port = 3306;
    } else if (dbType === 'POSTGRESQL' && (!connectionForm.port || connectionForm.port <= 0)) {
      connectionForm.port = 5432;
    } else if (dbType === 'SQLSERVER' && (!connectionForm.port || connectionForm.port <= 0)) {
      connectionForm.port = 1433;
    } else if (dbType === 'ORACLE' && (!connectionForm.port || connectionForm.port <= 0)) {
      connectionForm.port = 1521;
    } else if (dbType === 'SQLITE') {
      connectionForm.host = '';
      connectionForm.port = 0;
      connectionForm.username = '';
    }
  },
  { immediate: true },
);

watch(
  () => JSON.stringify(aiConfigForm.modelOptions ?? []),
  () => {
    const models = aiModelOptions.value.map((item) => String(item.value)).filter((item) => !!item);
    if (!models.length) {
      selectedAiModel.value = '';
      queryTabs.value.forEach((tab) => {
        tab.selectedAiModel = '';
      });
      return;
    }
    if (!models.includes(selectedAiModel.value)) {
      selectedAiModel.value = models[0];
    }
    queryTabs.value.forEach((tab) => {
      if (!models.includes(tab.selectedAiModel)) {
        tab.selectedAiModel = models[0];
      }
    });
  },
  { immediate: true },
);

function expandConnectionNode(connectionId: number) {
  const connection = connections.value.find((item) => item.id === connectionId);
  if (!connection) {
    return;
  }
  const keys = new Set(expandedTreeKeys.value);
  keys.add(`env-${connection.env}`);
  keys.add(`conn-${connectionId}`);
  const activeDb = getActiveDatabaseName(connectionId);
  const activeCategory = currentObjectType.value || 'tables';
  if (requiresDatabaseLayer(connection)) {
    if (activeDb) {
      keys.add(buildDatabaseNodeKey(connectionId, activeDb));
      keys.add(buildCategoryNodeKey(connectionId, activeDb, activeCategory));
    }
  } else {
    keys.add(buildCategoryNodeKey(connectionId, activeDb, activeCategory));
  }
  expandedTreeKeys.value = Array.from(keys);
}

function buildDatabaseNodeKey(connectionId: number, databaseName: string) {
  return `conn-${connectionId}-db-${encodeURIComponent(databaseName)}`;
}

function buildCategoryNodeKey(connectionId: number, databaseName: string, category: string) {
  return `conn-${connectionId}-db-${encodeURIComponent(databaseName)}-category-${category}`;
}

function buildObjectNodeKey(connectionId: number, databaseName: string, objectType: string, objectName: string) {
  return `conn-${connectionId}-db-${encodeURIComponent(databaseName)}-obj-${objectType}-${encodeURIComponent(objectName)}`;
}

function expandCategoryNode(connectionId: number, databaseName: string, category: string) {
  const keys = new Set(expandedTreeKeys.value);
  keys.add(buildCategoryNodeKey(connectionId, databaseName, category));
  expandedTreeKeys.value = Array.from(keys);
}

function toObjectType(value: string): 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups' {
  const normalized = value.toLowerCase();
  if (normalized === 'tables' || normalized === 'views' || normalized === 'functions'
    || normalized === 'events' || normalized === 'queries' || normalized === 'backups') {
    return normalized;
  }
  return 'tables';
}

function objectTypeLabel(value: string) {
  if (value === 'tables') {
    return '表';
  }
  if (value === 'views') {
    return '视图';
  }
  if (value === 'functions') {
    return '函数';
  }
  if (value === 'events') {
    return '事件';
  }
  if (value === 'queries') {
    return '查询';
  }
  if (value === 'backups') {
    return '备份';
  }
  return value;
}

function nodeIconComponent(dataRef: { nodeType?: string }) {
  if (dataRef.nodeType === 'database') {
    return DatabaseOutlined;
  }
  if (dataRef.nodeType === 'tables') {
    return FolderOpenOutlined;
  }
  if (dataRef.nodeType === 'views') {
    return EyeOutlined;
  }
  if (dataRef.nodeType === 'functions') {
    return CodeOutlined;
  }
  if (dataRef.nodeType === 'events') {
    return ClockCircleOutlined;
  }
  if (dataRef.nodeType === 'queries') {
    return SearchOutlined;
  }
  if (dataRef.nodeType === 'backups') {
    return HddOutlined;
  }
  return AppstoreOutlined;
}

function dbIconUrl(dbType: string) {
  if (dbType === 'MYSQL') {
    return mysqlIcon;
  }
  if (dbType === 'POSTGRESQL') {
    return postgresqlIcon;
  }
  if (dbType === 'SQLITE') {
    return sqliteIcon;
  }
  if (dbType === 'SQLSERVER') {
    return sqlserverIcon;
  }
  if (dbType === 'ORACLE') {
    return oracleIcon;
  }
  return sqliteIcon;
}

function normalizeModelOptions(options: AiModelOption[] | undefined) {
  const list = (options ?? [])
    .map((item, index) => {
      const providerType: 'OPENAI' | 'LOCAL_CLI' = item.providerType === 'LOCAL_CLI' ? 'LOCAL_CLI' : 'OPENAI';
      return {
        id: (item.id || '').trim() || `${providerType === 'LOCAL_CLI' ? 'cli' : 'openai'}-${index + 1}`,
        name: (item.name || '').trim() || (providerType === 'LOCAL_CLI' ? `CLI-${index + 1}` : `OpenAI-${index + 1}`),
        providerType,
        openaiBaseUrl: (item.openaiBaseUrl || '').trim(),
        openaiApiKey: (item.openaiApiKey || '').trim(),
        openaiModel: (item.openaiModel || '').trim(),
        cliCommand: (item.cliCommand || '').trim(),
        cliWorkingDir: (item.cliWorkingDir || '').trim(),
      } satisfies AiModelOption;
    })
    .filter((item) => !!item.id);
  if (list.length) {
    return list;
  }
  return defaultAiConfigForm().modelOptions ?? [];
}

function nextModelOptionId(prefix: 'openai' | 'cli') {
  const existing = new Set((aiConfigForm.modelOptions ?? []).map((item) => item.id));
  let index = 1;
  while (existing.has(`${prefix}-${index}`)) {
    index++;
  }
  return `${prefix}-${index}`;
}

function addOpenAiModelOption() {
  const id = nextModelOptionId('openai');
  aiConfigForm.modelOptions = [
    ...(aiConfigForm.modelOptions ?? []),
    {
      id,
      name: `OpenAI ${id}`,
      providerType: 'OPENAI',
      openaiBaseUrl: 'https://api.openai.com/v1',
      openaiApiKey: '',
      openaiModel: 'gpt-4.1-mini',
      cliCommand: '',
      cliWorkingDir: '',
    },
  ];
}

function addCliModelOption() {
  const id = nextModelOptionId('cli');
  aiConfigForm.modelOptions = [
    ...(aiConfigForm.modelOptions ?? []),
    {
      id,
      name: `CLI ${id}`,
      providerType: 'LOCAL_CLI',
      openaiBaseUrl: '',
      openaiApiKey: '',
      openaiModel: '',
      cliCommand: '',
      cliWorkingDir: '',
    },
  ];
}

function removeModelOption(index: number) {
  const list = [...(aiConfigForm.modelOptions ?? [])];
  list.splice(index, 1);
  aiConfigForm.modelOptions = list.length ? list : (defaultAiConfigForm().modelOptions ?? []);
}

function defaultConnectionForm(): ConnectionCreateReq {
  return {
    name: '本地 SQLite',
    dbType: 'SQLITE',
    host: '',
    port: 0,
    databaseName: 'sample.db',
    username: '',
    password: '',
    authType: 'PASSWORD',
    env: 'DEV',
    readOnly: false,
    sshEnabled: false,
    sshHost: '',
    sshPort: 22,
    sshUser: '',
  };
}

function resetConnectionForm() {
  Object.assign(connectionForm, defaultConnectionForm());
}

function fillConnectionForm(connection: ConnectionVO) {
  Object.assign(connectionForm, {
    name: connection.name,
    dbType: connection.dbType,
    host: connection.host ?? '',
    port: connection.port ?? 0,
    databaseName: connection.databaseName ?? '',
    username: connection.username ?? '',
    password: '',
    authType: 'PASSWORD',
    env: connection.env,
    readOnly: connection.readOnly,
    sshEnabled: connection.sshEnabled,
    sshHost: connection.sshHost ?? '',
    sshPort: connection.sshPort ?? 22,
    sshUser: connection.sshUser ?? '',
  } satisfies ConnectionCreateReq);
}

function defaultAiConfigForm(): AiConfigSaveReq {
  const defaultOption: AiModelOption = {
    id: 'openai-1',
    name: 'OpenAI gpt-4.1-mini',
    providerType: 'OPENAI',
    openaiBaseUrl: 'https://api.openai.com/v1',
    openaiApiKey: '',
    openaiModel: 'gpt-4.1-mini',
    cliCommand: '',
    cliWorkingDir: '',
  };
  return {
    providerType: 'OPENAI',
    openaiBaseUrl: defaultOption.openaiBaseUrl,
    openaiApiKey: defaultOption.openaiApiKey,
    openaiModel: defaultOption.openaiModel,
    cliCommand: defaultOption.cliCommand,
    cliWorkingDir: defaultOption.cliWorkingDir,
    modelOptions: [defaultOption],
  };
}

function fillAiConfigForm(config: AiConfigVO) {
  const options = normalizeModelOptions(config.modelOptions);
  const first = options[0];
  Object.assign(aiConfigForm, {
    providerType: first.providerType,
    openaiBaseUrl: first.openaiBaseUrl || 'https://api.openai.com/v1',
    openaiApiKey: first.openaiApiKey || '',
    openaiModel: first.openaiModel || 'gpt-4.1-mini',
    cliCommand: first.cliCommand || '',
    cliWorkingDir: first.cliWorkingDir || '',
    modelOptions: options,
  } satisfies AiConfigSaveReq);
  const models = options.map((item) => item.id).filter((item) => !!item);
  if (!models.includes(selectedAiModel.value)) {
    selectedAiModel.value = models[0] || '';
  }
}

function defaultRagConfigForm(): RagConfigSaveReq {
  return {
    ragEmbeddingModelDir: '',
    ragEmbeddingModelFileName: 'model_optimized.onnx',
    ragEmbeddingModelDataFileName: 'model_optimized.onnx.data',
    ragEmbeddingTokenizerFileName: 'tokenizer.json',
    ragEmbeddingTokenizerConfigFileName: 'tokenizer_config.json',
    ragEmbeddingConfigFileName: 'config.json',
    ragEmbeddingSpecialTokensFileName: 'special_tokens_map.json',
    ragEmbeddingSentencepieceFileName: 'sentencepiece.bpe.model',
    ragEmbeddingModelPath: './models/bge-m3/model.onnx',
    ragEmbeddingModelDataPath: '',
  };
}

function fillRagConfigForm(config: RagConfigVO) {
  Object.assign(ragConfigForm, {
    ragEmbeddingModelDir: config.ragEmbeddingModelDir || '',
    ragEmbeddingModelFileName: config.ragEmbeddingModelFileName || 'model_optimized.onnx',
    ragEmbeddingModelDataFileName: config.ragEmbeddingModelDataFileName || 'model_optimized.onnx.data',
    ragEmbeddingTokenizerFileName: config.ragEmbeddingTokenizerFileName || 'tokenizer.json',
    ragEmbeddingTokenizerConfigFileName: config.ragEmbeddingTokenizerConfigFileName || 'tokenizer_config.json',
    ragEmbeddingConfigFileName: config.ragEmbeddingConfigFileName || 'config.json',
    ragEmbeddingSpecialTokensFileName: config.ragEmbeddingSpecialTokensFileName || 'special_tokens_map.json',
    ragEmbeddingSentencepieceFileName: config.ragEmbeddingSentencepieceFileName || 'sentencepiece.bpe.model',
    ragEmbeddingModelPath: config.ragEmbeddingModelPath || './models/bge-m3/model.onnx',
    ragEmbeddingModelDataPath: config.ragEmbeddingModelDataPath || '',
  } satisfies RagConfigSaveReq);
}

function resetConnectionModalState() {
  isEditMode.value = false;
  editingConnectionId.value = null;
  resetConnectionForm();
}
</script>
