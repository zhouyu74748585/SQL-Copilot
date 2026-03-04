<template>
  <a-config-provider :theme="antdThemeConfig">
    <div
      class="studio-root"
      :class="{
        'is-mac': isMacOS,
        'is-win': isWindows,
        'is-linux': isLinux,
        'theme-dark': isDarkTheme,
        'theme-light': !isDarkTheme,
      }"
    >
    <section class="top-chrome">
      <div class="top-chrome-safe top-chrome-safe-left" />
      <div class="top-chrome-center">
        <div class="top-chrome-tabs-scroll">
          <button
            class="workspace-tab workspace-tab-browser"
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
          <a-tooltip title="新建 AI 查询页签">
            <button class="top-chrome-tab-add" @click="openAiQueryTab()">
              <plus-outlined />
            </button>
          </a-tooltip>
        </div>
      </div>
      <div class="top-chrome-actions">
        <button class="tool-item top-action-btn" @click="openCreateModal" title="连接">
          <link-outlined />
          <span>连接</span>
        </button>
        <a-dropdown placement="bottomLeft" :trigger="['click']">
          <button class="tool-item top-action-btn" :disabled="!canOpenHistory" title="会话历史" @click="handleHistoryMenuClick">
            <history-outlined />
            <span>历史</span>
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
                      <a-button
                        size="small"
                        type="link"
                        danger
                        class="history-menu-delete-btn"
                        title="删除会话"
                        :disabled="historySessionLoadingKey === historyItemKey(item)"
                        @click.stop="removeHistorySession(item)"
                      >
                        <template #icon><delete-outlined /></template>
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
        <button class="tool-item top-action-btn" @click="openAiConfigModal" title="AI 配置">
          <setting-outlined />
          <span>配置</span>
        </button>
        <a-tooltip :title="isDarkTheme ? '切换到浅色' : '切换到深色'">
          <button class="tool-item tool-theme-toggle top-action-btn top-action-icon-btn" @click="toggleTheme">
            <bulb-filled v-if="isDarkTheme" />
            <bulb-outlined v-else />
          </button>
        </a-tooltip>
      </div>
      <div class="top-chrome-safe top-chrome-safe-right" />
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
                v-if="dataRef.nodeType === 'connection'"
                class="tree-env-tag"
                :class="envTagClass(dataRef.env)"
              >
                <component :is="envTagIcon(dataRef.env)" class="tree-env-tag-icon" />
                {{ envTagText(dataRef.env) }}
              </span>
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
                <div
                  class="table-name-cell"
                  :class="{ 'is-active': selectedObjectName === record.objectName, 'is-queryable': record.objectType === 'tables' }"
                  @dblclick.stop="openQueryTabByObject(record)"
                >
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
              @dblclick="onObjectRow(item).onDblclick()"
              @contextmenu.prevent.stop="onObjectRow(item).onContextmenu($event)"
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
          <div v-if="!selectedObjectRecord && !selectedTreeDetail" class="empty-pane">请从对象浏览中选择连接、数据库或对象</div>
          <div v-else-if="selectedObjectRecord" class="detail-wrapper">
            <div class="detail-summary">
              <div class="detail-row"><span>对象</span><strong>{{ selectedObjectRecord.objectName }}</strong></div>
              <div class="detail-row"><span>类型</span><strong>{{ objectTypeLabel(selectedObjectRecord.objectType) }}</strong></div>
              <div class="detail-row detail-row-description"><span>说明</span><strong>{{ selectedObjectRecord.description || '-' }}</strong></div>
              <div class="detail-row"><span>连接</span><strong>{{ selectedConnection?.name ?? '-' }}</strong></div>
              <div class="detail-row"><span>数据库</span><strong>{{ getActiveDatabaseName(workflow.connectionId) || '-' }}</strong></div>
            </div>

            <div v-if="selectedObjectRecord.objectType === 'tables'" class="detail-table-panel">
              <a-spin :spinning="tableDetailLoading">
                <div class="detail-code-head">
                  <span>建表语句</span>
                  <a-button size="small" type="text" @click="copyCreateTableSql">
                    <template #icon><copy-outlined /></template>
                    复制
                  </a-button>
                </div>
                <pre class="detail-code-block"><code v-html="createTableSqlHighlighted"></code></pre>
              </a-spin>
            </div>

            <div v-else class="detail-note">当前对象类型暂无结构详情，仅展示基本信息。</div>
          </div>
          <div v-else-if="selectedTreeDetail?.kind === 'connection'" class="detail-wrapper">
            <div class="detail-summary">
              <div class="detail-row"><span>连接</span><strong>{{ selectedTreeConnection?.name ?? '-' }}</strong></div>
              <div class="detail-row"><span>数据库类型</span><strong>{{ selectedTreeConnection?.dbType ?? '-' }}</strong></div>
              <div class="detail-row"><span>所属环境</span><strong>{{ envTagText(selectedTreeConnection?.env) }}</strong></div>
              <div class="detail-row"><span>主机</span><strong>{{ selectedTreeConnection?.host || '本地连接' }}</strong></div>
              <div class="detail-row"><span>端口</span><strong>{{ selectedTreeConnection?.port ?? '-' }}</strong></div>
              <div class="detail-row"><span>用户名</span><strong>{{ selectedTreeConnection?.username || '-' }}</strong></div>
              <div class="detail-row"><span>默认库</span><strong>{{ selectedTreeConnection?.databaseName || '未指定库' }}</strong></div>
              <div class="detail-row"><span>只读</span><strong>{{ selectedTreeConnection?.readOnly ? '是' : '否' }}</strong></div>
              <div class="detail-row"><span>SSH 隧道</span><strong>{{ selectedTreeConnection?.sshEnabled ? '已启用' : '未启用' }}</strong></div>
            </div>
          </div>
          <div v-else-if="selectedTreeDetail?.kind === 'database' || selectedTreeDetail?.kind === 'category'" class="detail-wrapper">
            <div class="detail-summary">
              <div class="detail-row"><span>数据库</span><strong>{{ selectedTreeDetail.databaseName || '-' }}</strong></div>
              <div class="detail-row"><span>连接</span><strong>{{ selectedTreeConnection?.name ?? '-' }}</strong></div>
              <div class="detail-row"><span>数据库类型</span><strong>{{ selectedTreeConnection?.dbType ?? '-' }}</strong></div>
              <div class="detail-row"><span>所属环境</span><strong>{{ envTagText(selectedTreeConnection?.env) }}</strong></div>
              <div class="detail-row"><span>向量化</span><strong>{{ selectedTreeDatabaseStatusLabel }}</strong></div>
              <div class="detail-row"><span>表数量</span><strong>{{ selectedTreeDatabaseTableCount }}</strong></div>
              <div class="detail-row"><span>字段数</span><strong>{{ selectedTreeDatabaseColumnCount }}</strong></div>
            </div>
          </div>
          <div v-else class="empty-pane">对象详情加载中...</div>
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
              输入自然语言后发送消息；可使用 Auto 自动识别意图，或关闭 Auto 后手动选择“生成 SQL”“解释 SQL”“分析 SQL”“生成图表”。
            </div>
            <div
              v-for="item in activeQueryTab.chatMessages"
              :key="item.id"
              class="query-chat-message"
              :class="{ 'is-user': item.role === 'user', 'is-assistant': item.role === 'assistant' }"
            >
              <template v-if="item.role === 'user'">
                <div class="query-chat-user-bubble-wrap">
                  <div class="query-chat-user-bubble" :class="userBubbleClass(item.actionType)">{{ item.content }}</div>
                  <div v-if="item.retryable" class="query-chat-user-retry-row">
                    <a-button
                      size="small"
                      type="link"
                      class="query-chat-user-retry-btn"
                      :loading="!!item.retryLoading"
                      @click="retryUserMessage(activeQueryTab, item)"
                    >
                      <template #icon><reload-outlined /></template>
                      重试
                    </a-button>
                  </div>
                </div>
              </template>
              <template v-else>
                <div class="query-chat-assistant-card">
                  <div class="query-chat-assistant-head">
                    <span>{{ assistantActionLabel(item.actionType) }}</span>
                    <span>{{ formatTime(item.createdAt) }}</span>
                  </div>
                  <div v-if="item.content" class="query-chat-text">{{ item.content }}</div>
                  <div v-if="item.chartConfig" class="query-chat-chart-summary">
                    {{ item.chartConfigSummary || chartSummaryText(item.chartConfig) }}
                  </div>
                  <div v-if="item.chartImageDataUrl" class="query-chat-chart-image-wrap">
                    <img class="query-chat-chart-image" :src="item.chartImageDataUrl" alt="chart-preview" />
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
                        <a-button
                          size="small"
                          type="primary"
                          class="sql-action-icon-btn"
                          :loading="activeQueryTab.sqlExecuting"
                          :disabled="activeQueryTab.sqlExecuting"
                          @click="executeSqlForTab(activeQueryTab, item.sqlText || '')"
                        >
                          <template #icon><play-circle-outlined /></template>
                        </a-button>
                      </a-tooltip>
                      <a-tooltip v-if="item.chartConfig" title="生成图表">
                        <a-button size="small" class="sql-action-icon-btn" @click="generateChartFromMessage(activeQueryTab, item)">
                          <template #icon><area-chart-outlined /></template>
                        </a-button>
                      </a-tooltip>
                      <a-tooltip v-if="item.chartConfig && item.chartImageCacheKey" title="编辑图表（重跑SQL）">
                        <a-button size="small" class="sql-action-icon-btn" @click="editChartFromHistory(activeQueryTab, item)">
                          <template #icon><line-chart-outlined /></template>
                        </a-button>
                      </a-tooltip>
                      <a-tooltip v-if="item.chartImageDataUrl || item.chartImageCacheKey" title="下载图表 PNG">
                        <a-button size="small" class="sql-action-icon-btn" @click="downloadMessageChart(item)">
                          <template #icon><download-outlined /></template>
                        </a-button>
                      </a-tooltip>
                    </a-space>
                  </template>
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
                <span class="query-chat-auto-label">Auto</span>
                <a-switch v-model:checked="activeQueryTab.autoMode" size="small" />
              </div>
              <div v-if="activeQueryTab.autoMode" class="query-chat-composer-actions">
                <a-tooltip title="Auto 发送">
                  <a-button
                    size="small"
                    type="primary"
                    class="sql-action-icon-btn"
                    :loading="activeQueryTab.aiGenerating"
                    :disabled="activeQueryTab.aiGenerating"
                    @click="sendAutoForTab(activeQueryTab)"
                  >
                    <template #icon><send-outlined /></template>
                  </a-button>
                </a-tooltip>
              </div>
              <div v-else class="query-chat-composer-actions">
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
                    <template #icon><experiment-outlined /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="生成图表">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn"
                    :loading="activeQueryTab.aiGenerating"
                    :disabled="activeQueryTab.aiGenerating"
                    @click="generateChartPlanForTab(activeQueryTab)"
                  >
                    <template #icon><area-chart-outlined /></template>
                  </a-button>
                </a-tooltip>
              </div>
            </div>
          </div>
        </section>

        <aside v-if="activeQueryTab" class="pane pane-right query-editor-pane">
          <div class="pane-title">SQL 编辑与执行</div>

          <div class="editor-group" ref="sqlEditorContainerRef">
            <MonacoEditor
              v-model:value="activeQueryTab.sqlText"
              language="sql"
              width="100%"
              height="240px"
              :theme="monacoTheme"
              :options="sqlEditorOptions"
              class="sql-editor"
              @mount="handleSqlEditorMount"
            >
              <template #default>编辑器加载中...</template>
              <template #failure>编辑器加载失败，请刷新页面重试</template>
            </MonacoEditor>
            <div
              v-if="sqlSelectionPopover.visible && activeQueryTab.selectedSqlText"
              class="sql-selection-popover"
              :style="{ left: `${sqlSelectionPopover.left}px`, top: `${sqlSelectionPopover.top}px` }"
            >
              <a-space size="small">
                <a-tooltip title="所选 SQL 加入对话">
                  <a-button size="small" class="sql-action-icon-btn sql-selection-popover-btn" @click="appendSelectedSqlToPrompt(activeQueryTab)">
                    <template #icon><message-outlined /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="解释 SQL">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn sql-selection-popover-btn"
                    :loading="activeQueryTab.aiGenerating"
                    :disabled="activeQueryTab.aiGenerating"
                    @click="explainSelectedSqlInChat(activeQueryTab)"
                  >
                    <template #icon><read-outlined /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="分析 SQL">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn sql-selection-popover-btn"
                    :loading="activeQueryTab.aiGenerating"
                    :disabled="activeQueryTab.aiGenerating"
                    @click="analyzeSelectedSqlInChat(activeQueryTab)"
                  >
                    <template #icon><experiment-outlined /></template>
                  </a-button>
                </a-tooltip>
              </a-space>
            </div>
          </div>

          <div class="editor-actions">
            <a-space wrap>
              <a-tooltip :title="activeQueryTab.selectedSqlText ? '计划选择的SQL' : '计划 SQL'">
                <a-button
                  size="small"
                  :class="['sql-action-icon-btn', { 'is-selection-active': !!activeQueryTab.selectedSqlText }]"
                  @click="explainSqlForTab(activeQueryTab)"
                >
                  <template #icon><eye-outlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip :title="activeQueryTab.selectedSqlText ? '执行选中的SQL' : '执行 SQL'">
                <a-button
                  size="small"
                  type="primary"
                  :class="['sql-action-icon-btn', { 'is-selection-active': !!activeQueryTab.selectedSqlText }]"
                  :loading="activeQueryTab.sqlExecuting"
                  :disabled="activeQueryTab.sqlExecuting"
                  @click="executeSqlForTab(activeQueryTab)"
                >
                  <template #icon><play-circle-outlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip v-if="activeQueryTab.lastExecuteFailed" title="自动修复">
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
            <div class="query-result-title-row">
              <div class="query-result-title">查询结果</div>
              <a-space size="small">
                <a-tooltip title="表格结果">
                  <a-button
                    size="small"
                    :class="['sql-action-icon-btn', { 'is-selection-active': activeQueryTab.resultViewMode === 'table' }]"
                    @click="activeQueryTab.resultViewMode = 'table'"
                  >
                    <template #icon><table-outlined /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="图表结果">
                  <a-button
                    size="small"
                    :class="['sql-action-icon-btn', { 'is-selection-active': activeQueryTab.resultViewMode === 'chart' }]"
                    @click="activeQueryTab.resultViewMode = 'chart'"
                  >
                    <template #icon><area-chart-outlined /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip v-if="activeQueryTab.resultViewMode === 'chart'" title="下载图表 PNG">
                  <a-button size="small" class="sql-action-icon-btn" @click="downloadActiveChart(activeQueryTab)">
                    <template #icon><download-outlined /></template>
                  </a-button>
                </a-tooltip>
              </a-space>
            </div>
            <div v-if="activeQueryTab.lastExecuteFailed" class="query-result-error">
              <span class="query-result-error-text">{{ activeQueryTab.lastExecuteErrorMessage || 'SQL 执行失败' }}</span>
              <a-button size="small" type="primary" danger @click="repairSqlForTab(activeQueryTab)">修复 SQL</a-button>
            </div>
            <template v-if="activeQueryTab.resultViewMode === 'table'">
              <a-table
                size="small"
                :pagination="false"
                :columns="activeResultColumns"
                :data-source="activeResultRows"
                :scroll="{ x: queryResultScrollX, y: queryResultScrollY }"
                row-key="__rowKey"
              />
            </template>
            <template v-else>
              <div class="query-chart-manual-panel">
                <a-space wrap size="small">
                  <a-select
                    v-model:value="activeQueryTab.manualChartConfig.chartType"
                    size="small"
                    style="width: 104px"
                    :options="chartTypeOptions"
                  />
                  <a-select
                    v-if="['LINE', 'BAR', 'SCATTER', 'TREND'].includes(activeQueryTab.manualChartConfig.chartType || '')"
                    v-model:value="activeQueryTab.manualChartConfig.xField"
                    size="small"
                    style="width: 132px"
                    :options="activeChartFieldOptions"
                    placeholder="X 轴"
                  />
                  <a-select
                    v-if="['LINE', 'BAR', 'TREND'].includes(activeQueryTab.manualChartConfig.chartType || '')"
                    v-model:value="activeQueryTab.manualChartConfig.yFields"
                    size="small"
                    mode="multiple"
                    :max-tag-count="2"
                    style="width: 184px"
                    :options="activeNumericFieldOptions"
                    placeholder="Y 轴（多选）"
                  />
                  <a-select
                    v-if="activeQueryTab.manualChartConfig.chartType === 'SCATTER'"
                    v-model:value="activeQueryTab.manualChartConfig.yFields"
                    size="small"
                    mode="multiple"
                    style="width: 148px"
                    :options="activeNumericFieldOptions"
                    placeholder="Y 轴"
                    :max-tag-count="1"
                    :max-count="1"
                  />
                  <a-select
                    v-if="activeQueryTab.manualChartConfig.chartType === 'PIE'"
                    v-model:value="activeQueryTab.manualChartConfig.categoryField"
                    size="small"
                    style="width: 128px"
                    :options="activeChartFieldOptions"
                    placeholder="分类字段"
                  />
                  <a-select
                    v-if="activeQueryTab.manualChartConfig.chartType === 'PIE'"
                    v-model:value="activeQueryTab.manualChartConfig.valueField"
                    size="small"
                    style="width: 128px"
                    :options="activeNumericFieldOptions"
                    placeholder="数值字段"
                  />
                  <a-select
                    v-model:value="activeQueryTab.manualChartConfig.sortField"
                    size="small"
                    style="width: 132px"
                    :options="activeChartFieldOptions"
                    placeholder="排序字段"
                    allow-clear
                  />
                  <a-select
                    v-model:value="activeQueryTab.manualChartConfig.sortDirection"
                    size="small"
                    style="width: 94px"
                    :options="chartSortDirectionOptions"
                  />
                  <a-tooltip title="按当前配置生成图表">
                    <a-button size="small" type="primary" class="sql-action-icon-btn" @click="generateManualChartForTab(activeQueryTab)">
                      <template #icon><area-chart-outlined /></template>
                    </a-button>
                  </a-tooltip>
                </a-space>
              </div>
              <div class="query-chart-render-panel">
                <QueryChartPanel
                  ref="queryChartPanelRef"
                  :rows="activeChartRows"
                  :config="activeQueryTab.activeChartConfig"
                />
                <div v-if="activeQueryTab.chartReadonly" class="query-chart-readonly-tip">
                  历史图表预览为只读，点击对话中的“编辑图表（重跑SQL）”可恢复可编辑状态。
                </div>
              </div>
            </template>
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

        <a-row :gutter="12">
          <a-col :span="16">
            <a-form-item :label="isMultiDatabaseFormType ? '展示数据库（多选）' : '数据库名/路径'">
              <template v-if="isMultiDatabaseFormType">
                <div class="connection-db-selector-row">
                  <a-select
                    v-model:value="connectionForm.selectedDatabases"
                    mode="multiple"
                    :options="connectionPreviewSelectOptions"
                    :max-tag-count="3"
                    allow-clear
                    placeholder="可选；不勾选默认展示全部数据库"
                    style="flex: 1"
                  />
                  <a-button
                    :loading="connectionPreviewLoading"
                    :disabled="!canPreviewDatabases"
                    @click="previewConnectionDatabases"
                  >
                    获取数据库
                  </a-button>
                </div>
                <div class="connection-db-selector-tip">不勾选时，连接树显示该连接下全部数据库。</div>
                <div v-if="connectionPreviewError" class="connection-db-selector-error">{{ connectionPreviewError }}</div>
              </template>
              <a-input
                v-else
                v-model:value="connectionForm.databaseName"
                :placeholder="connectionForm.dbType === 'SQLITE' ? 'SQLite 文件路径' : '数据库名/服务名'"
              />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="环境">
              <a-select v-model:value="connectionForm.env" :options="envOptions" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-space>
          <a-checkbox v-model:checked="connectionForm.readOnly">只读</a-checkbox>
          <a-checkbox v-model:checked="connectionForm.sshEnabled">SSH 隧道</a-checkbox>
        </a-space>

        <div v-if="connectionForm.sshEnabled" class="connection-ssh-panel">
          <a-row :gutter="12">
            <a-col :span="12">
              <a-form-item label="SSH 主机">
                <a-input v-model:value="connectionForm.sshHost" placeholder="例如 10.0.0.8" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="SSH 端口">
                <a-input-number v-model:value="connectionForm.sshPort" :min="1" :max="65535" style="width: 100%" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="12">
            <a-col :span="12">
              <a-form-item label="SSH 用户名">
                <a-input v-model:value="connectionForm.sshUser" placeholder="SSH 登录用户名" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="SSH 认证模式">
                <a-select v-model:value="connectionForm.sshAuthType" :options="sshAuthTypeOptions" />
              </a-form-item>
            </a-col>
          </a-row>

          <a-form-item v-if="connectionForm.sshAuthType === 'SSH_PASSWORD'" label="SSH 密码">
            <a-input-password
              v-model:value="connectionForm.sshPassword"
              :placeholder="isEditMode ? '留空表示不修改' : '请输入 SSH 密码'"
            />
          </a-form-item>
          <a-form-item v-else-if="connectionForm.sshAuthType === 'SSH_KEY_PATH'" label="SSH 私钥路径">
            <a-input
              v-model:value="connectionForm.sshPrivateKeyPath"
              :placeholder="isEditMode ? '留空表示不修改' : '例如 /Users/me/.ssh/id_rsa'"
            />
          </a-form-item>
          <a-form-item v-else label="SSH 私钥文本">
            <a-textarea
              v-model:value="connectionForm.sshPrivateKeyText"
              :rows="4"
              :placeholder="isEditMode ? '留空表示不修改' : '粘贴完整 PEM 私钥内容'"
            />
          </a-form-item>

          <a-form-item
            v-if="connectionForm.sshAuthType === 'SSH_KEY_PATH' || connectionForm.sshAuthType === 'SSH_KEY_TEXT'"
            label="私钥口令（可选）"
          >
            <a-input-password
              v-model:value="connectionForm.sshPrivateKeyPassphrase"
              :placeholder="isEditMode ? '留空表示不修改' : '私钥解密口令（可选）'"
            />
          </a-form-item>
        </div>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="aiConfigModalOpen"
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
              <div class="file-picker-row">
                <a-input
                  :value="ragConfigForm.ragEmbeddingModelDir"
                  readonly
                  placeholder="/path/to/bge-m3-onnx-o4 (directory first)"
                />
                <a-button :loading="pickingRagModelDir" @click="pickRagEmbeddingModelDir">选择目录</a-button>
              </div>
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
                  <div class="file-picker-row">
                    <a-input
                      :value="ragConfigForm.ragEmbeddingModelPath"
                      readonly
                      placeholder="/path/to/model_optimized.onnx"
                    />
                    <a-button :loading="pickingRagModelPath" @click="pickRagEmbeddingModelPath">选择文件</a-button>
                  </div>
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="ONNX 数据绝对路径（可选）">
                  <div class="file-picker-row">
                    <a-input
                      :value="ragConfigForm.ragEmbeddingModelDataPath"
                      readonly
                      placeholder="/path/to/model_optimized.onnx.data"
                    />
                    <a-button :loading="pickingRagModelDataPath" @click="pickRagEmbeddingModelDataPath">选择文件</a-button>
                  </div>
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
      <template v-else-if="contextMenu.targetType === 'object'">
        <button
          class="context-menu-item"
          :disabled="contextMenu.objectType !== 'tables' && contextMenu.objectType !== 'views'"
          @click="triggerContextAction('queryData')"
        >
          查询数据
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
  </a-config-provider>
</template>

<script setup lang="ts">
import {
  AreaChartOutlined,
  AppstoreOutlined,
  ExperimentOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  BulbFilled,
  BulbOutlined,
  CodeOutlined,
  DatabaseOutlined,
  EditOutlined,
  EyeOutlined,
  FolderOpenOutlined,
  HddOutlined,
  HistoryOutlined,
  LinkOutlined,
  LoadingOutlined,
  LineChartOutlined,
  MessageOutlined,
  MinusCircleOutlined,
  ArrowLeftOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  TableOutlined,
  ReadOutlined,
  ReloadOutlined,
  SearchOutlined,
  SendOutlined,
  SettingOutlined,
  ToolOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons-vue';
import {Editor as MonacoEditor} from '@guolao/vue-monaco-editor';
import type {IDisposable} from 'monaco-editor';
import type * as MonacoApi from 'monaco-editor';
import type {ConnectionVO} from '@sqlcopilot/shared-contracts';
import {message, Modal, theme as antdTheme} from 'ant-design-vue';
import {computed, h, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch} from 'vue';
import {getApi, postApi} from './api/client';
import QueryChartPanel from './components/QueryChartPanel.vue';
import mysqlIcon from './assets/db/mysql.svg';
import oracleIcon from './assets/db/oracle.svg';
import postgresqlIcon from './assets/db/postgresql.svg';
import sqliteIcon from './assets/db/sqlite.svg';
import sqlserverIcon from './assets/db/sqlserver.svg';
import type {
  AiConfigSaveReq,
  AiConfigVO,
  AiAutoQueryVO,
  AiGenerateChartVO,
  ChartCacheReadVO,
  ChartCacheSaveReq,
  ChartCacheSaveVO,
  ChartConfigVO,
  ChartType,
  AiIntentType,
  AiGenerateSqlVO,
  AiRepairVO,
  AiModelOption,
  AiTextResponseVO,
  ConnectionDatabasePreviewReq,
  ConnectionDatabasePreviewVO,
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
  SortDirection,
  TableDetailVO,
} from './types';

interface DesktopDialogFilter {
  name: string;
  extensions: string[];
}

interface DesktopPickFileOptions {
  title?: string;
  defaultPath?: string;
  filters?: DesktopDialogFilter[];
}

interface DesktopBridge {
  pickFile: (options?: DesktopPickFileOptions) => Promise<string>;
  pickDirectory: (options?: Omit<DesktopPickFileOptions, 'filters'>) => Promise<string>;
}

interface ObjectRow {
  objectName: string;
  objectType: 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups';
  rowEstimate: number;
  tableSize: string;
  description: string;
}

type QueryActionType =
  | 'generate'
  | 'explain'
  | 'analyze'
  | 'auto_generate'
  | 'auto_explain'
  | 'auto_analyze'
  | 'auto_chart_auto_plan'
  | 'repair'
  | 'chart_auto_plan'
  | 'chart_manual_render'
  | 'chart_auto_render';
type AiActionType = 'generate' | 'explain' | 'analyze';
type UiTheme = 'light' | 'dark';
type QueryResultViewMode = 'table' | 'chart';
type RetryActionKind = 'ai_action' | 'auto' | 'chart_plan';

interface RetryRequestMeta {
  kind: RetryActionKind;
  actionType?: AiActionType;
  promptText: string;
  finalPrompt: string;
  actionSqlSnippet?: string;
}

interface QueryChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  sqlText?: string;
  actionType: QueryActionType;
  chartConfig?: ChartConfigVO;
  chartConfigSummary?: string;
  chartImageCacheKey?: string;
  chartImageDataUrl?: string;
  retryable?: boolean;
  retryLoading?: boolean;
  retryMeta?: RetryRequestMeta;
  historySaved?: boolean;
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
  autoMode: boolean;
  aiGenerating: boolean;
  sqlExecuting: boolean;
  selectedSqlText: string;
  chatMessages: QueryChatMessage[];
  lastExecuteFailed: boolean;
  lastExecuteErrorMessage: string;
  lastFailedSqlText: string;
  resultViewMode: QueryResultViewMode;
  manualChartConfig: ChartConfigVO;
  activeChartConfig: ChartConfigVO | null;
  chartImageDataUrl: string;
  chartImageCacheKey: string;
  chartReadonly: boolean;
  createdAt: number;
  updatedAt: number;
}

const browserTabKey = 'browser';
const uiThemeStorageKey = 'sqlcopilot.ui-theme.v1';
const {defaultAlgorithm, darkAlgorithm} = antdTheme;
const isMacOS = typeof navigator !== 'undefined' && /mac/i.test(navigator.platform);
const isWindows = typeof navigator !== 'undefined' && /win/i.test(navigator.platform);
const isLinux = typeof navigator !== 'undefined' && /linux/i.test(navigator.platform);
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
const uiTheme = ref<UiTheme>('light');
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
const sqlEditorContainerRef = ref<HTMLElement | null>(null);
const queryChartPanelRef = ref<InstanceType<typeof QueryChartPanel> | null>(null);
const sqlSelectionPopover = reactive({
  visible: false,
  left: 0,
  top: 0,
});
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
  targetType: 'none' as 'none' | 'connection' | 'database' | 'object',
  connectionId: 0,
  databaseName: '',
  objectType: '' as '' | ObjectRow['objectType'],
  objectName: '',
});

const connectionForm = reactive<ConnectionCreateReq>(defaultConnectionForm());
const connectionPreviewDbOptions = ref<string[]>([]);
const connectionPreviewLoading = ref(false);
const connectionPreviewError = ref('');
const aiConfigForm = reactive<AiConfigSaveReq>(defaultAiConfigForm());
const ragConfigForm = reactive<RagConfigSaveReq>(defaultRagConfigForm());
const pickingRagModelDir = ref(false);
const pickingRagModelPath = ref(false);
const pickingRagModelDataPath = ref(false);

const workflow = reactive({
  connectionId: 0,
  prompt: '',
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

const sshAuthTypeOptions = [
  { label: '密码', value: 'SSH_PASSWORD' },
  { label: '私钥路径', value: 'SSH_KEY_PATH' },
  { label: '私钥文本', value: 'SSH_KEY_TEXT' },
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
    other: false,
  },
  quickSuggestionsDelay: 80,
  suggestOnTriggerCharacters: false,
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
let sqlEditorScrollDisposable: IDisposable | null = null;
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

const canOpenHistory = computed(() => {
  return connections.value.length > 0;
});

const isDarkTheme = computed(() => uiTheme.value === 'dark');

const monacoTheme = computed(() => (isDarkTheme.value ? 'vs-dark' : 'vs'));

const antdThemeConfig = computed(() => ({
  algorithm: isDarkTheme.value ? darkAlgorithm : defaultAlgorithm,
  token: {
    colorPrimary: '#3b82f6',
    borderRadius: 10,
    wireframe: false,
  },
  components: {
    Button: {
      controlHeightSM: 28,
    },
    Input: {
      controlHeightSM: 28,
    },
    Select: {
      controlHeightSM: 28,
    },
  },
}));

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

const isMultiDatabaseFormType = computed(() => isMultiDatabaseType(connectionForm.dbType));

const connectionPreviewSelectOptions = computed(() => {
  const selected = connectionForm.selectedDatabases ?? [];
  const merged = Array.from(new Set([
    ...connectionPreviewDbOptions.value,
    ...selected.filter((item) => !!item),
  ]));
  return merged.map((item) => ({ label: item, value: item }));
});

const canPreviewDatabases = computed(() => {
  if (!isMultiDatabaseFormType.value) {
    return false;
  }
  if (!connectionForm.host?.trim() || !connectionForm.username?.trim()) {
    return false;
  }
  if (!connectionForm.port || connectionForm.port <= 0) {
    return false;
  }
  if (connectionForm.sshEnabled) {
    if (!connectionForm.sshHost?.trim() || !connectionForm.sshUser?.trim()) {
      return false;
    }
    if (!connectionForm.sshPort || connectionForm.sshPort <= 0) {
      return false;
    }
    const mode = connectionForm.sshAuthType || 'SSH_PASSWORD';
    if (mode === 'SSH_PASSWORD') {
      return !!connectionForm.sshPassword?.trim();
    }
    if (mode === 'SSH_KEY_PATH') {
      return !!connectionForm.sshPrivateKeyPath?.trim();
    }
    if (mode === 'SSH_KEY_TEXT') {
      return !!connectionForm.sshPrivateKeyText?.trim();
    }
    return false;
  }
  return true;
});

const connectionTreeData = computed(() => {
  const keyword = connectionKeyword.value.trim().toLowerCase();
  const filtered = keyword
    ? connections.value.filter((item) => item.name.toLowerCase().includes(keyword))
    : connections.value;
  return filtered.map((conn) => buildConnectionNode(conn));
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

const selectedTreeDetail = computed(() => {
  const key = selectedTreeKeys.value[0];
  if (!key) {
    return null;
  }
  const keyValue = String(key);
  const objectMatch = keyValue.match(/^conn-(\d+)-db-(.+?)-obj-([a-z]+)-(.+)$/);
  if (objectMatch) {
    return {
      kind: 'object' as const,
      connectionId: Number(objectMatch[1]),
      databaseName: decodeURIComponent(objectMatch[2]),
      objectType: toObjectType(objectMatch[3]),
      objectName: decodeURIComponent(objectMatch[4]),
    };
  }
  const categoryMatch = keyValue.match(/^conn-(\d+)-db-(.+?)-category-([a-z]+)$/);
  if (categoryMatch) {
    return {
      kind: 'category' as const,
      connectionId: Number(categoryMatch[1]),
      databaseName: decodeURIComponent(categoryMatch[2]),
      category: categoryMatch[3],
    };
  }
  const databaseMatch = keyValue.match(/^conn-(\d+)-db-(.+)$/);
  if (databaseMatch) {
    return {
      kind: 'database' as const,
      connectionId: Number(databaseMatch[1]),
      databaseName: decodeURIComponent(databaseMatch[2]),
    };
  }
  const connectionMatch = keyValue.match(/^conn-(\d+)$/);
  if (connectionMatch) {
    return {
      kind: 'connection' as const,
      connectionId: Number(connectionMatch[1]),
    };
  }
  return null;
});

const selectedTreeConnection = computed(() => {
  const connectionId = selectedTreeDetail.value?.connectionId ?? workflow.connectionId;
  return connections.value.find((item) => item.id === connectionId) ?? null;
});

const selectedTreeDatabaseStatusLabel = computed(() => {
  const detail = selectedTreeDetail.value;
  if (!detail || (detail.kind !== 'database' && detail.kind !== 'category')) {
    return '-';
  }
  return databaseStatusLabel(getDatabaseVectorizeStatus(detail.connectionId, detail.databaseName));
});

const selectedTreeDatabaseTableCount = computed(() => {
  const detail = selectedTreeDetail.value;
  if (!detail || (detail.kind !== 'database' && detail.kind !== 'category')) {
    return '-';
  }
  if (schemaOverview.value && schemaOverview.value.databaseName === detail.databaseName) {
    return `${schemaOverview.value.tableCount ?? 0}`;
  }
  const tableNames = tableNameCache.value[tableCacheKey(detail.connectionId, detail.databaseName)] ?? [];
  return `${tableNames.length}`;
});

const selectedTreeDatabaseColumnCount = computed(() => {
  const detail = selectedTreeDetail.value;
  if (!detail || (detail.kind !== 'database' && detail.kind !== 'category')) {
    return '-';
  }
  if (schemaOverview.value && schemaOverview.value.databaseName === detail.databaseName) {
    return `${schemaOverview.value.columnCount ?? 0}`;
  }
  return '-';
});

const createTableSqlText = computed(() => {
  if (!selectedObjectRecord.value || selectedObjectRecord.value.objectType !== 'tables') {
    return '-- 当前未选中表';
  }
  const tableName = selectedObjectRecord.value.objectName;
  const dbType = selectedConnection.value?.dbType ?? selectedTreeConnection.value?.dbType ?? 'MYSQL';
  const columns = tableDetail.value?.columns ?? [];
  if (!columns.length) {
    return `-- 未读取到表 ${tableName} 的字段元数据`;
  }
  return buildCreateTableSql(tableName, columns, dbType);
});

const createTableSqlHighlighted = computed(() => highlightSqlForDisplay(createTableSqlText.value));

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

const tableScrollY = computed(() => Math.max(300, viewportHeight.value - 300));
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

const chartTypeOptions = [
  { label: '折线图', value: 'LINE' as ChartType },
  { label: '柱状图', value: 'BAR' as ChartType },
  { label: '饼图', value: 'PIE' as ChartType },
  { label: '散点图', value: 'SCATTER' as ChartType },
  { label: '趋势图', value: 'TREND' as ChartType },
];

const chartSortDirectionOptions = [
  { label: '不排序', value: 'NONE' as SortDirection },
  { label: '升序', value: 'ASC' as SortDirection },
  { label: '降序', value: 'DESC' as SortDirection },
];

const activeChartRows = computed(() => activeResultRows.value.map((row) => {
  const normalized: Record<string, string | null> = {};
  Object.keys(row).forEach((key) => {
    if (key === '__rowKey') {
      return;
    }
    normalized[key] = row[key] ?? null;
  });
  return normalized;
}));

const activeChartFieldOptions = computed(() => activeResultColumns.value.map((column) => ({
  label: String(column.title || column.key),
  value: String(column.dataIndex),
})));

const activeNumericFieldOptions = computed(() => {
  const rows = activeChartRows.value;
  const fields = activeChartFieldOptions.value.map((item) => String(item.value));
  const numericFields = fields.filter((field) => isNumericField(rows, field));
  return numericFields.map((field) => ({
    label: field,
    value: field,
  }));
});

function emptyManualChartConfig(): ChartConfigVO {
  return {
    chartType: 'LINE',
    xField: '',
    yFields: [],
    categoryField: '',
    valueField: '',
    sortField: '',
    sortDirection: 'NONE',
    title: '',
    description: '',
  };
}

function cloneChartConfig(config: ChartConfigVO | null | undefined): ChartConfigVO {
  if (!config) {
    return emptyManualChartConfig();
  }
  return {
    chartType: (config.chartType || 'LINE') as ChartType,
    xField: config.xField || '',
    yFields: [...(config.yFields || [])],
    categoryField: config.categoryField || '',
    valueField: config.valueField || '',
    sortField: config.sortField || '',
    sortDirection: (config.sortDirection || 'NONE') as SortDirection,
    title: config.title || '',
    description: config.description || '',
  };
}

function isNumericField(rows: Array<Record<string, string | null>>, field: string) {
  const values = rows
    .map((row) => row[field])
    .filter((value): value is string => value != null && String(value).trim() !== '')
    .slice(0, 120);
  if (!values.length) {
    return false;
  }
  return values.every((value) => Number.isFinite(Number(value)));
}

function setupManualChartConfigByResult(tab: QueryWorkspaceTab) {
  const rows = (tab.executeResult?.rows ?? tab.explainResult?.rows ?? []);
  if (!rows.length) {
    tab.manualChartConfig = emptyManualChartConfig();
    return;
  }
  const fields = rows[0].cells.map((cell) => cell.columnName).filter((item) => !!item);
  if (!fields.length) {
    tab.manualChartConfig = emptyManualChartConfig();
    return;
  }
  const rowObjects = rows.map((row) => {
    const result: Record<string, string | null> = {};
    row.cells.forEach((cell) => {
      result[cell.columnName] = cell.cellValue;
    });
    return result;
  });
  const numericFields = fields.filter((field) => isNumericField(rowObjects, field));
  const fallbackY = numericFields[0] || fields[1] || fields[0];
  tab.manualChartConfig = {
    chartType: 'LINE',
    xField: fields[0],
    yFields: fallbackY ? [fallbackY] : [],
    categoryField: fields[0],
    valueField: numericFields[0] || '',
    sortField: '',
    sortDirection: 'NONE',
    title: '',
    description: '',
  };
}

function buildConnectionNode(conn: ConnectionVO) {
  if (requiresDatabaseLayer(conn)) {
    const databases = visibleDatabasesForConnection(conn);
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
      env: conn.env,
      dbType: conn.dbType,
      children: databaseNodes,
    };
  }

  const configuredDbName = getActiveDatabaseName(conn.id);
  return {
    key: `conn-${conn.id}`,
    title: conn.name,
    nodeType: 'connection',
    env: conn.env,
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
  if (isMultiDatabaseType(connection.dbType)) {
    return true;
  }
  return !parseConfiguredDatabaseName(connection).trim();
}

function isMultiDatabaseType(dbType: string) {
  return dbType === 'MYSQL' || dbType === 'POSTGRESQL' || dbType === 'SQLSERVER';
}

function normalizeSelectedDatabases(values: string[] | undefined) {
  if (!values?.length) {
    return [];
  }
  const set = new Set<string>();
  values.forEach((item) => {
    const value = (item || '').trim();
    if (value) {
      set.add(value);
    }
  });
  return Array.from(set);
}

function visibleDatabasesForConnection(connection: ConnectionVO) {
  const allDatabases = databaseListCache.value[connection.id] ?? [];
  if (!isMultiDatabaseType(connection.dbType)) {
    return allDatabases;
  }
  const selected = normalizeSelectedDatabases(connection.selectedDatabases);
  if (!selected.length) {
    return allDatabases;
  }
  const selectedSet = new Set(selected.map((item) => item.toLowerCase()));
  return allDatabases.filter((item) => selectedSet.has(item.toLowerCase()));
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
  const connection = connections.value.find((item) => item.id === connectionId);
  if (!connection) {
    return selected;
  }
  const visibleDatabases = visibleDatabasesForConnection(connection);
  if (selected && (!visibleDatabases.length || visibleDatabases.includes(selected))) {
    return selected;
  }
  if (selected && visibleDatabases.length && !visibleDatabases.includes(selected)) {
    return '';
  }
  const configured = parseConfiguredDatabaseName(connection);
  if (configured && (!visibleDatabases.length || visibleDatabases.includes(configured))) {
    return configured;
  }
  return '';
}

function activateBrowserTab() {
  activeWorkbenchTab.value = browserTabKey;
}

function openCreateModal() {
  closeContextMenu();
  resetConnectionForm();
  connectionPreviewDbOptions.value = [];
  connectionPreviewError.value = '';
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
  connectionPreviewDbOptions.value = databaseListCache.value[current.id] ?? [];
  connectionPreviewError.value = '';
  isEditMode.value = true;
  editingConnectionId.value = current.id;
  createModalOpen.value = true;
}

function openAiQueryTab(initialPrompt = '') {
  ensureConnection();
  const now = Date.now();
  const databaseName = getActiveDatabaseName(workflow.connectionId);
  const models = aiModelOptions.value.map((item) => String(item.value)).filter((item) => !!item);
  const tab: QueryWorkspaceTab = {
    key: `query-${now}-${Math.round(Math.random() * 1000)}`,
    title: '新的查询',
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
    autoMode: true,
    aiGenerating: false,
    sqlExecuting: false,
    selectedSqlText: '',
    chatMessages: [],
    lastExecuteFailed: false,
    lastExecuteErrorMessage: '',
    lastFailedSqlText: '',
    resultViewMode: 'table',
    manualChartConfig: emptyManualChartConfig(),
    activeChartConfig: null,
    chartImageDataUrl: '',
    chartImageCacheKey: '',
    chartReadonly: false,
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
  return tab;
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

function toggleTheme() {
  uiTheme.value = uiTheme.value === 'dark' ? 'light' : 'dark';
}

function loadUiThemePreference() {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    const raw = window.localStorage.getItem(uiThemeStorageKey);
    if (raw === 'light' || raw === 'dark') {
      uiTheme.value = raw;
      return;
    }
    uiTheme.value = 'light';
  } catch {
    uiTheme.value = 'light';
  }
}

function persistUiThemePreference() {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage.setItem(uiThemeStorageKey, uiTheme.value);
  } catch {
    // 忽略本地存储异常，避免阻塞主流程。
  }
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

function buildNewQueryPlaceholderTitle(tab: QueryWorkspaceTab) {
  const connectionName = (queryTabConnectionNameById(tab.connectionId) || '').trim() || '未命名连接';
  const databaseName = (tab.databaseName || getActiveDatabaseName(tab.connectionId) || '').trim() || '未指定库';
  return `${connectionName} / ${databaseName} · 新的查询`;
}

function applySessionTitle(tab: QueryWorkspaceTab) {
  const custom = (sessionTitleOverrides.value[sessionTitleOverrideKey(tab)] ?? '').trim();
  if (custom) {
    tab.title = custom;
    return;
  }
  const firstPrompt = firstPromptForTitle(tab).trim();
  if (firstPrompt) {
    tab.title = buildSessionDefaultTitle(firstPrompt);
    return;
  }
  tab.title = buildNewQueryPlaceholderTitle(tab);
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

async function removeHistorySession(item: QueryHistorySessionVO) {
  const targetKey = historyItemKey(item);
  if (historySessionLoadingKey.value === targetKey) {
    return;
  }
  const confirmed = await new Promise<boolean>((resolve) => {
    Modal.confirm({
      title: '删除会话历史',
      content: `确定删除会话“${historyItemDisplayTitle(item)}”的全部历史记录吗？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    });
  });
  if (!confirmed) {
    return;
  }

  historySessionLoadingKey.value = targetKey;
  try {
    await postApi<boolean>('/api/editor/history/session/remove', {
      connectionId: item.connectionId,
      sessionId: item.sessionId,
    });
    const overrideKey = sessionTitleOverrideKey(item);
    if (sessionTitleOverrides.value[overrideKey]) {
      const next = {...sessionTitleOverrides.value};
      delete next[overrideKey];
      sessionTitleOverrides.value = next;
      persistSessionTitleOverrides();
    }
    if (editingHistoryTabKey.value === targetKey) {
      cancelHistoryTitleEdit();
    }
    historySessionItems.value = historySessionItems.value.filter((entry) => historyItemKey(entry) !== targetKey);
    queryTabs.value = queryTabs.value.filter((tab) => !(tab.connectionId === item.connectionId && tab.sessionId === item.sessionId));
    if (!queryTabs.value.some((tab) => tab.key === activeWorkbenchTab.value)) {
      activeWorkbenchTab.value = queryTabs.value[0]?.key ?? browserTabKey;
    }
    message.success('会话已删除');
    if (!historySessionItems.value.length) {
      historySessionPageNo.value = 1;
      historySessionHasMore.value = true;
      await loadHistorySessionPage(true);
    }
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    message.error(msg);
  } finally {
    historySessionLoadingKey.value = '';
  }
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
    const actionType = normalizeHistoryActionType(item.actionType);
    const ts = item.createdAt ?? Date.now() + index;
    if (promptText) {
      messages.push({
        id: `chat-history-user-${connectionId}-${encodeURIComponent(sessionId)}-${item.id ?? index}`,
        role: 'user',
        content: promptText,
        actionType,
        createdAt: ts,
      });
    }
    const assistantContent = (item.assistantContent ?? '').trim();
    const hasAssistantPayload = !!assistantContent || !!sqlText || !!item.chartConfig || !!item.chartImageCacheKey;
    if (hasAssistantPayload) {
      messages.push({
        id: `chat-history-assistant-${connectionId}-${encodeURIComponent(sessionId)}-${item.id ?? index}`,
        role: 'assistant',
        content: assistantContent,
        sqlText: sqlText || undefined,
        actionType,
        chartConfig: item.chartConfig ?? undefined,
        chartConfigSummary: assistantContent || undefined,
        chartImageCacheKey: (item.chartImageCacheKey || '').trim() || undefined,
        createdAt: ts + 1,
      });
    }
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
    databaseName: (last?.databaseName || '').trim() || getActiveDatabaseName(connectionId),
    sessionId,
    prompt: '',
    sqlText: (last?.sqlText ?? '').trim(),
    riskAckToken: '',
    riskInfo: null,
    executeResult: null,
    explainResult: null,
    selectedAiModel: models[0] ?? '',
    autoMode: true,
    aiGenerating: false,
    sqlExecuting: false,
    selectedSqlText: '',
    chatMessages: messages,
    lastExecuteFailed: false,
    lastExecuteErrorMessage: '',
    lastFailedSqlText: '',
    resultViewMode: 'table',
    manualChartConfig: emptyManualChartConfig(),
    activeChartConfig: null,
    chartImageDataUrl: '',
    chartImageCacheKey: '',
    chartReadonly: false,
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
      tab.resultViewMode = 'table';
      tab.manualChartConfig = emptyManualChartConfig();
      tab.activeChartConfig = null;
      tab.chartImageDataUrl = '';
      tab.chartImageCacheKey = '';
      tab.chartReadonly = true;
      tab.createdAt = item.createdAt ?? loaded.createdAt;
      tab.updatedAt = item.updatedAt ?? loaded.updatedAt;
      tab.databaseName = tab.databaseName || loaded.databaseName;
      tab.title = customTitle || fallbackTitle;
    } else {
      tab = buildHistoryTabFromRows(item.connectionId, item.sessionId, rows);
      tab.prompt = '';
      tab.selectedSqlText = '';
      tab.title = customTitle || fallbackTitle;
      tab.resultViewMode = 'table';
      tab.chartReadonly = true;
      tab.createdAt = item.createdAt ?? tab.createdAt;
      tab.updatedAt = item.updatedAt ?? tab.updatedAt;
      queryTabs.value = [...queryTabs.value, tab];
    }
    activeWorkbenchTab.value = tab.key;
    await prepareConnectionTreeData(tab.connectionId);
    tab.databaseName = tab.databaseName || getActiveDatabaseName(tab.connectionId);
    await warmupTableSuggestions(tab);
    await hydrateHistoryChartImages(tab);
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
  if (actionType === 'auto_generate') {
    return 'Auto · 生成 SQL';
  }
  if (actionType === 'auto_explain') {
    return 'Auto · 解释 SQL';
  }
  if (actionType === 'auto_analyze') {
    return 'Auto · 分析 SQL';
  }
  if (actionType === 'auto_chart_auto_plan') {
    return 'Auto · 图表方案';
  }
  if (actionType === 'explain') {
    return '解释 SQL';
  }
  if (actionType === 'analyze') {
    return '分析 SQL';
  }
  if (actionType === 'repair') {
    return '修复 SQL';
  }
  if (actionType === 'chart_auto_plan') {
    return '图表方案';
  }
  if (actionType === 'chart_manual_render') {
    return '手动制图';
  }
  if (actionType === 'chart_auto_render') {
    return '自动制图';
  }
  return '生成 SQL';
}

function normalizeHistoryActionType(actionType?: string): QueryActionType {
  const normalized = (actionType || '').trim().toLowerCase();
  if (normalized === 'auto_generate') {
    return 'auto_generate';
  }
  if (normalized === 'auto_explain') {
    return 'auto_explain';
  }
  if (normalized === 'auto_analyze') {
    return 'auto_analyze';
  }
  if (normalized === 'auto_chart_auto_plan') {
    return 'auto_chart_auto_plan';
  }
  if (normalized === 'explain') {
    return 'explain';
  }
  if (normalized === 'analyze') {
    return 'analyze';
  }
  if (normalized === 'repair') {
    return 'repair';
  }
  if (normalized === 'chart_auto_plan') {
    return 'chart_auto_plan';
  }
  if (normalized === 'chart_manual_render') {
    return 'chart_manual_render';
  }
  if (normalized === 'chart_auto_render') {
    return 'chart_auto_render';
  }
  return 'generate';
}

function userBubbleClass(actionType: QueryActionType) {
  if (actionType === 'auto_explain') {
    return 'is-explain';
  }
  if (actionType === 'auto_analyze') {
    return 'is-analyze';
  }
  if (actionType === 'explain') {
    return 'is-explain';
  }
  if (actionType === 'analyze') {
    return 'is-analyze';
  }
  if (actionType === 'repair') {
    return 'is-repair';
  }
  return 'is-generate';
}

function touchQueryTab(tab: QueryWorkspaceTab) {
  tab.updatedAt = Date.now();
}

function appendUserChatMessage(tab: QueryWorkspaceTab, promptText: string, actionType: QueryChatMessage['actionType']) {
  const now = Date.now();
  const messageItem: QueryChatMessage = {
    id: `chat-user-${now}-${Math.random().toString(16).slice(2, 8)}`,
    role: 'user',
    content: promptText,
    actionType,
    retryable: false,
    retryLoading: false,
    historySaved: false,
    createdAt: now,
  };
  tab.chatMessages.push(messageItem);
  applySessionTitle(tab);
  touchQueryTab(tab);
  return messageItem;
}

function appendAssistantSqlMessage(
  tab: QueryWorkspaceTab,
  sqlText: string,
  actionType: QueryChatMessage['actionType'],
  content = '',
  chartConfig?: ChartConfigVO | null,
  chartConfigSummary?: string,
  chartImageCacheKey?: string,
) {
  const now = Date.now();
  const messageItem: QueryChatMessage = {
    id: `chat-assistant-${now}-${Math.random().toString(16).slice(2, 8)}`,
    role: 'assistant',
    content: content.trim(),
    sqlText,
    actionType,
    chartConfig: chartConfig ? cloneChartConfig(chartConfig) : undefined,
    chartConfigSummary: (chartConfigSummary || '').trim() || undefined,
    chartImageCacheKey: (chartImageCacheKey || '').trim() || undefined,
    createdAt: now,
  };
  tab.chatMessages.push(messageItem);
  touchQueryTab(tab);
  return messageItem;
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
  hideSqlSelectionPopover();
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
  hideSqlSelectionPopover();
}

async function explainSelectedSqlInChat(tab: QueryWorkspaceTab) {
  await runAiTextActionWithSelectedSql(tab, 'explain');
}

async function analyzeSelectedSqlInChat(tab: QueryWorkspaceTab) {
  await runAiTextActionWithSelectedSql(tab, 'analyze');
}

async function runAiTextActionWithSelectedSql(tab: QueryWorkspaceTab, actionType: 'explain' | 'analyze') {
  if (tab.aiGenerating) {
    return;
  }
  const selectedSqlText = tab.selectedSqlText.trim();
  if (!selectedSqlText) {
    message.info('请先选择一段 SQL');
    return;
  }

  const promptText = actionType === 'explain' ? '请解释这段 SQL 的含义。' : '请分析这段 SQL 的合理性。';
  appendUserChatMessage(tab, `${promptText}\n\n${selectedSqlText}`, actionType);
  tab.aiGenerating = true;
  hideSqlSelectionPopover();

  try {
    const endpoint = actionType === 'explain' ? '/api/ai/query/explain' : '/api/ai/query/analyze';
    const result = await postApi<AiTextResponseVO>(endpoint, {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      prompt: mergePromptWithSqlSnippet(promptText, selectedSqlText),
      databaseName: tab.databaseName || undefined,
      modelName: tab.selectedAiModel || undefined,
    });
    appendAssistantTextMessage(tab, result.content || '未返回内容', actionType);
    if (result.reasoning) {
      message.info(result.reasoning);
    }
    message.success(actionType === 'explain' ? 'SQL 含义解释已生成' : 'SQL 合理性分析已生成');
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    message.error(msg);
  } finally {
    tab.aiGenerating = false;
    touchQueryTab(tab);
  }
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
  const connection = connections.value.find((item) => item.id === connectionId);
  if (connection) {
    const visibleNames = visibleDatabasesForConnection(connection);
    const current = (activeDatabaseMap.value[connectionId] || '').trim();
    if (current && visibleNames.length && !visibleNames.includes(current)) {
      activeDatabaseMap.value = {
        ...activeDatabaseMap.value,
        [connectionId]: '',
      };
    }
  }
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
    queryTabs.value.forEach((tab) => {
      const connection = list.find((item) => item.id === tab.connectionId);
      if (!connection || !isMultiDatabaseType(connection.dbType)) {
        return;
      }
      const visibleNames = visibleDatabasesForConnection(connection);
      if (tab.databaseName && visibleNames.length && !visibleNames.includes(tab.databaseName)) {
        tab.databaseName = '';
      }
    });
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
    const normalizedSelectedDatabases = isMultiDatabaseType(connectionForm.dbType)
      ? normalizeSelectedDatabases(connectionForm.selectedDatabases)
      : [];
    const sshAuthType = connectionForm.sshEnabled ? (connectionForm.sshAuthType || 'SSH_PASSWORD') : undefined;
    const payload: ConnectionCreateReq & { id?: number } = {
      ...connectionForm,
      selectedDatabases: normalizedSelectedDatabases,
      sshAuthType,
      sshPassword: connectionForm.sshEnabled && sshAuthType === 'SSH_PASSWORD'
        ? (connectionForm.sshPassword || '').trim()
        : '',
      sshPrivateKeyPath: connectionForm.sshEnabled && sshAuthType === 'SSH_KEY_PATH'
        ? (connectionForm.sshPrivateKeyPath || '').trim()
        : '',
      sshPrivateKeyText: connectionForm.sshEnabled && sshAuthType === 'SSH_KEY_TEXT'
        ? (connectionForm.sshPrivateKeyText || '').trim()
        : '',
      sshPrivateKeyPassphrase: connectionForm.sshEnabled
        && (sshAuthType === 'SSH_KEY_PATH' || sshAuthType === 'SSH_KEY_TEXT')
        ? (connectionForm.sshPrivateKeyPassphrase || '').trim()
        : '',
    };
    if (!isMultiDatabaseType(payload.dbType)) {
      payload.selectedDatabases = [];
    }
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

async function previewConnectionDatabases() {
  if (!canPreviewDatabases.value) {
    return;
  }
  connectionPreviewLoading.value = true;
  connectionPreviewError.value = '';
  try {
    const payload: ConnectionDatabasePreviewReq = {
      dbType: connectionForm.dbType,
      host: (connectionForm.host || '').trim(),
      port: connectionForm.port,
      databaseName: (connectionForm.databaseName || '').trim(),
      username: (connectionForm.username || '').trim(),
      password: (connectionForm.password || '').trim(),
      sshEnabled: connectionForm.sshEnabled,
      sshHost: (connectionForm.sshHost || '').trim(),
      sshPort: connectionForm.sshPort,
      sshUser: (connectionForm.sshUser || '').trim(),
      sshAuthType: connectionForm.sshEnabled ? (connectionForm.sshAuthType || 'SSH_PASSWORD') : undefined,
      sshPassword: connectionForm.sshEnabled && (connectionForm.sshAuthType || 'SSH_PASSWORD') === 'SSH_PASSWORD'
        ? (connectionForm.sshPassword || '').trim()
        : '',
      sshPrivateKeyPath: connectionForm.sshEnabled && connectionForm.sshAuthType === 'SSH_KEY_PATH'
        ? (connectionForm.sshPrivateKeyPath || '').trim()
        : '',
      sshPrivateKeyText: connectionForm.sshEnabled && connectionForm.sshAuthType === 'SSH_KEY_TEXT'
        ? (connectionForm.sshPrivateKeyText || '').trim()
        : '',
      sshPrivateKeyPassphrase: connectionForm.sshEnabled
        && (connectionForm.sshAuthType === 'SSH_KEY_PATH' || connectionForm.sshAuthType === 'SSH_KEY_TEXT')
        ? (connectionForm.sshPrivateKeyPassphrase || '').trim()
        : '',
    };
    const result = await postApi<ConnectionDatabasePreviewVO>('/api/connection/databases/preview', payload);
    connectionPreviewDbOptions.value = Array.from(
      new Set((result.databaseNames ?? []).map((item) => (item || '').trim()).filter((item) => !!item)),
    );
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    connectionPreviewError.value = msg || '获取数据库失败';
  } finally {
    connectionPreviewLoading.value = false;
  }
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
  if (!keyword) {
    return [];
  }
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
  if (!keyword) {
    return [];
  }
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

function hasKeywordSuggestion(prefix: string) {
  const keyword = prefix.trim().toUpperCase();
  if (!keyword) {
    return false;
  }
  return sqlKeywords.some((item) => item.includes(keyword));
}

function hasTableSuggestion(names: string[], prefix: string) {
  const keyword = prefix.trim().toLowerCase();
  if (!keyword) {
    return false;
  }
  return names.some((name) => name.toLowerCase().includes(keyword));
}

function shouldAutoTriggerSuggest(tab: QueryWorkspaceTab, prefix: string) {
  if (hasKeywordSuggestion(prefix)) {
    return true;
  }
  const databaseName = resolveQueryDatabaseName(tab);
  if (!databaseName || databaseName === '未发现数据库') {
    return false;
  }
  const cacheKey = tableCacheKey(tab.connectionId, databaseName);
  const loaded = !!tableNameLoadedCache.value[cacheKey];
  if (!loaded) {
    void ensureTableNamesLoaded(tab.connectionId, databaseName);
    return false;
  }
  const tableNames = tableNameCache.value[cacheKey] ?? [];
  return hasTableSuggestion(tableNames, prefix);
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
        return undefined;
      }
      const word = model.getWordUntilPosition(position);
      const wordPrefix = (word.word || '').trim();
      if (!wordPrefix) {
        return undefined;
      }
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: word.startColumn,
        endColumn: word.endColumn,
      };
      const keywordSuggestions = sqlKeywordSuggestions(monaco, range, wordPrefix);
      const databaseName = resolveQueryDatabaseName(tab);
      if (!databaseName || databaseName === '未发现数据库') {
        return keywordSuggestions.length ? { suggestions: keywordSuggestions } : undefined;
      }
      const tableNames = await ensureTableNamesLoaded(tab.connectionId, databaseName);
      const tableSuggestions = tableNameSuggestions(monaco, tableNames, range, wordPrefix, databaseName);
      const suggestions = [...tableSuggestions, ...keywordSuggestions];
      if (!suggestions.length) {
        return undefined;
      }
      return {
        suggestions,
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
    const model = editor.getModel();
    const position = editor.getPosition();
    if (!model || !position) {
      return;
    }
    const currentWord = model.getWordUntilPosition(position).word.trim();
    if (!currentWord) {
      return;
    }
    const tab = activeQueryTab.value;
    if (!tab || !shouldAutoTriggerSuggest(tab, currentWord)) {
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

function hideSqlSelectionPopover() {
  sqlSelectionPopover.visible = false;
}

function updateSqlSelectionPopoverPosition(editor: MonacoApi.editor.IStandaloneCodeEditor) {
  const selection = editor.getSelection();
  const container = sqlEditorContainerRef.value;
  const editorNode = editor.getDomNode();
  if (!selection || selection.isEmpty() || !container || !editorNode) {
    hideSqlSelectionPopover();
    return;
  }
  const visiblePosition = editor.getScrolledVisiblePosition(selection.getEndPosition());
  if (!visiblePosition) {
    hideSqlSelectionPopover();
    return;
  }
  const containerRect = container.getBoundingClientRect();
  const editorRect = editorNode.getBoundingClientRect();
  const popoverWidth = 340;
  const estimatedHeight = 36;
  const baseLeft = editorRect.left - containerRect.left + visiblePosition.left;
  const baseTop = editorRect.top - containerRect.top + visiblePosition.top;
  const maxLeft = Math.max(8, container.clientWidth - popoverWidth - 8);
  const left = Math.min(Math.max(8, baseLeft), maxLeft);
  const top = Math.max(8, baseTop - estimatedHeight - 6);
  sqlSelectionPopover.left = left;
  sqlSelectionPopover.top = top;
  sqlSelectionPopover.visible = true;
}

function syncSelectedSqlForActiveTab() {
  if (!activeSqlEditorInstance || !activeQueryTab.value) {
    hideSqlSelectionPopover();
    return;
  }
  activeQueryTab.value.selectedSqlText = readSelectedSql(activeSqlEditorInstance);
  if (!activeQueryTab.value.selectedSqlText) {
    hideSqlSelectionPopover();
    return;
  }
  updateSqlSelectionPopoverPosition(activeSqlEditorInstance);
}

function registerSqlSelectionTracker(editor: MonacoApi.editor.IStandaloneCodeEditor) {
  sqlEditorSelectionDisposable?.dispose();
  sqlEditorSelectionDisposable = editor.onDidChangeCursorSelection(() => {
    syncSelectedSqlForActiveTab();
  });
}

function registerSqlScrollTracker(editor: MonacoApi.editor.IStandaloneCodeEditor) {
  sqlEditorScrollDisposable?.dispose();
  sqlEditorScrollDisposable = editor.onDidScrollChange(() => {
    if (!activeQueryTab.value?.selectedSqlText) {
      hideSqlSelectionPopover();
      return;
    }
    updateSqlSelectionPopoverPosition(editor);
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
  registerSqlScrollTracker(editor);
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
  activateBrowserTab();

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
  contextMenu.objectType = '';
  contextMenu.objectName = '';
}

async function triggerContextAction(action: 'edit' | 'test' | 'sync' | 'delete' | 'revectorize' | 'interruptVectorize' | 'viewVectorizedData' | 'queryData') {
  const id = contextMenu.connectionId;
  const databaseName = contextMenu.databaseName;
  const targetType = contextMenu.targetType;
  const objectType = contextMenu.objectType;
  const objectName = contextMenu.objectName;
  closeContextMenu();
  if (!id) {
    return;
  }
  if (action === 'queryData') {
    if (targetType !== 'object' || !objectName || (objectType !== 'tables' && objectType !== 'views')) {
      return;
    }
    openQueryTabByObject({
      objectName,
      objectType,
      rowEstimate: 0,
      tableSize: '-',
      description: '',
    }, true);
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
      closeContextMenu();
      const databaseName = getActiveDatabaseName(workflow.connectionId);
      void runSafely(async () => {
        await selectObject(workflow.connectionId, databaseName, record.objectType, record.objectName);
      });
    },
    onDblclick: () => {
      openQueryTabByObject(record);
    },
    onContextmenu: (event: MouseEvent) => {
      event.preventDefault();
      event.stopPropagation();
      closeContextMenu();
      const databaseName = getActiveDatabaseName(workflow.connectionId);
      void runSafely(async () => {
        await selectObject(workflow.connectionId, databaseName, record.objectType, record.objectName);
      });
      contextMenu.visible = true;
      contextMenu.x = Math.min(event.clientX, window.innerWidth - 220);
      contextMenu.y = Math.min(event.clientY, window.innerHeight - 180);
      contextMenu.targetType = 'object';
      contextMenu.connectionId = workflow.connectionId;
      contextMenu.databaseName = databaseName;
      contextMenu.objectType = record.objectType;
      contextMenu.objectName = record.objectName;
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

function openQueryTabByObject(record: ObjectRow, autoExecute = false) {
  if (record.objectType !== 'tables' && record.objectType !== 'views') {
    return;
  }
  const sql = `SELECT * FROM ${record.objectName} LIMIT 100`;
  const prompt = `查询 ${record.objectName} 最近数据`;
  workflow.sqlText = sql;
  workflow.prompt = prompt;
  const tab = openAiQueryTab(prompt);
  if (!tab) {
    return;
  }
  tab.sqlText = sql;
  tab.prompt = '';
  tab.databaseName = getActiveDatabaseName(tab.connectionId);
  touchQueryTab(tab);
  if (autoExecute) {
    void runSafely(async () => {
      await executeSqlForTab(tab, sql);
    });
  }
}

function getDesktopBridge(): DesktopBridge | null {
  if (typeof window === 'undefined') {
    return null;
  }
  const bridge = (window as Window & { sqlCopilotDesktop?: DesktopBridge }).sqlCopilotDesktop;
  if (!bridge || typeof bridge.pickFile !== 'function') {
    return null;
  }
  return bridge;
}

async function pickRagEmbeddingModelDir() {
  const bridge = getDesktopBridge();
  if (!bridge || typeof bridge.pickDirectory !== 'function') {
    message.warning('当前运行环境不支持目录选择器，请通过桌面端运行。');
    return;
  }
  if (pickingRagModelDir.value) {
    return;
  }
  pickingRagModelDir.value = true;
  try {
    const selectedPath = await bridge.pickDirectory({
      title: '选择向量化模型目录',
      defaultPath: ragConfigForm.ragEmbeddingModelDir || ragConfigForm.ragEmbeddingModelPath || undefined,
    });
    if (!selectedPath) {
      return;
    }
    ragConfigForm.ragEmbeddingModelDir = selectedPath;
  } finally {
    pickingRagModelDir.value = false;
  }
}

async function pickRagEmbeddingModelPath() {
  const bridge = getDesktopBridge();
  if (!bridge) {
    message.warning('当前运行环境不支持文件选择器，请通过桌面端运行。');
    return;
  }
  if (pickingRagModelPath.value) {
    return;
  }
  pickingRagModelPath.value = true;
  try {
    const selectedPath = await bridge.pickFile({
      title: '选择向量化 ONNX 模型文件',
      defaultPath: ragConfigForm.ragEmbeddingModelPath || ragConfigForm.ragEmbeddingModelDir || undefined,
      filters: [{ name: 'ONNX Model', extensions: ['onnx'] }],
    });
    if (!selectedPath) {
      return;
    }
    ragConfigForm.ragEmbeddingModelPath = selectedPath;
  } finally {
    pickingRagModelPath.value = false;
  }
}

async function pickRagEmbeddingModelDataPath() {
  const bridge = getDesktopBridge();
  if (!bridge) {
    message.warning('当前运行环境不支持文件选择器，请通过桌面端运行。');
    return;
  }
  if (pickingRagModelDataPath.value) {
    return;
  }
  pickingRagModelDataPath.value = true;
  try {
    const selectedPath = await bridge.pickFile({
      title: '选择 ONNX 数据文件',
      defaultPath: ragConfigForm.ragEmbeddingModelDataPath
        || ragConfigForm.ragEmbeddingModelPath
        || ragConfigForm.ragEmbeddingModelDir
        || undefined,
      filters: [{ name: 'ONNX Data', extensions: ['data'] }],
    });
    if (!selectedPath) {
      return;
    }
    ragConfigForm.ragEmbeddingModelDataPath = selectedPath;
  } finally {
    pickingRagModelDataPath.value = false;
  }
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
  const connection = connections.value.find((item) => item.id === tab.connectionId);
  const cached = connection ? visibleDatabasesForConnection(connection) : [];
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
    tab.lastExecuteFailed = false;
    tab.lastExecuteErrorMessage = '';
    tab.lastFailedSqlText = '';
    tab.selectedSqlText = '';
    tab.resultViewMode = 'table';
    tab.manualChartConfig = emptyManualChartConfig();
    tab.activeChartConfig = null;
    tab.chartImageDataUrl = '';
    tab.chartImageCacheKey = '';
    tab.chartReadonly = false;
    touchQueryTab(tab);
    await warmupTableSuggestions(tab);
  });
}

function handleQueryDatabaseChange(tab: QueryWorkspaceTab) {
  tab.riskAckToken = '';
  tab.lastExecuteFailed = false;
  tab.lastExecuteErrorMessage = '';
  tab.lastFailedSqlText = '';
  tab.resultViewMode = 'table';
  tab.manualChartConfig = emptyManualChartConfig();
  tab.activeChartConfig = null;
  tab.chartImageDataUrl = '';
  tab.chartImageCacheKey = '';
  tab.chartReadonly = false;
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

interface SaveConversationHistoryOptions {
  actionType?: QueryActionType;
  assistantContent?: string;
  databaseName?: string;
  chartConfig?: ChartConfigVO | null;
  chartImageCacheKey?: string;
  historyType?: 'CHAT' | 'EXECUTE';
  executionMs?: number;
  success?: boolean;
}

async function saveConversationHistory(
  tab: QueryWorkspaceTab,
  promptText: string,
  sqlText: string,
  options?: SaveConversationHistoryOptions,
) {
  try {
    await postApi<boolean>('/api/editor/history/save', {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      promptText,
      sqlText,
      historyType: options?.historyType || 'CHAT',
      actionType: options?.actionType || 'generate',
      assistantContent: options?.assistantContent || '',
      databaseName: options?.databaseName || tab.databaseName || '',
      chartConfigJson: options?.chartConfig ? JSON.stringify(options.chartConfig) : '',
      chartImageCacheKey: options?.chartImageCacheKey || '',
      executionMs: options?.executionMs,
      success: options?.success ?? true,
    });
  } catch {
    // 关键操作：会话历史持久化失败不阻塞主流程。
  }
}

async function saveConversationHistoryOnce(
  tab: QueryWorkspaceTab,
  userMessage: QueryChatMessage,
  promptText: string,
  sqlText: string,
  options?: SaveConversationHistoryOptions,
) {
  if (userMessage.historySaved) {
    return;
  }
  await saveConversationHistory(tab, promptText, sqlText, options);
  userMessage.historySaved = true;
}

function timeoutRetryErrorMessage(rawMessage: string) {
  const normalized = rawMessage.trim();
  if (!normalized) {
    return '请求超时，请点击重试';
  }
  return normalized;
}

function isTimeoutErrorMessage(rawMessage: string) {
  const normalized = rawMessage.trim().toLowerCase();
  return normalized.includes('timeout')
    || normalized.includes('timed out')
    || normalized.includes('超时')
    || normalized.includes('http 504')
    || normalized.includes('http 408');
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function clearUserRetryState(userMessage: QueryChatMessage) {
  userMessage.retryable = false;
  userMessage.retryLoading = false;
  userMessage.retryMeta = undefined;
}

function markUserMessageRetryable(
  tab: QueryWorkspaceTab,
  userMessage: QueryChatMessage,
  retryMeta: RetryRequestMeta,
) {
  userMessage.retryable = true;
  userMessage.retryLoading = false;
  userMessage.retryMeta = retryMeta;
  touchQueryTab(tab);
}

function mergePromptWithSqlSnippet(promptText: string, selectedSqlText?: string) {
  const basePrompt = promptText.trim();
  const snippet = (selectedSqlText ?? '').trim();
  if (!snippet) {
    return basePrompt;
  }
  if (!basePrompt) {
    return snippet;
  }
  return [
    basePrompt,
    '',
    snippet,
  ].join('\n');
}

const aiRequestTimeoutMs = 120000;

async function postAiApiWithTimeout<T>(path: string, payload: unknown, timeoutMs = aiRequestTimeoutMs) {
  return await Promise.race<T>([
    postApi<T>(path, payload),
    new Promise<T>((_, reject) => {
      setTimeout(() => {
        reject(new Error(`请求超时（>${Math.floor(timeoutMs / 1000)}s）`));
      }, timeoutMs);
    }),
  ]);
}

function looksLikeSqlText(text: string) {
  const normalized = text.trim().toLowerCase();
  return /^(select|with|insert|update|delete|replace|create|alter|drop|truncate|merge|show|explain)\b/.test(normalized);
}

interface RetryInvokeOptions {
  userMessage: QueryChatMessage;
  promptText: string;
  finalPrompt: string;
  actionSqlSnippet?: string;
}

async function generateSqlForTab(
  tab: QueryWorkspaceTab,
  actionType: AiActionType = 'generate',
  retryOptions?: RetryInvokeOptions,
) {
  if (tab.aiGenerating) {
    return;
  }
  const rawPrompt = retryOptions?.promptText ?? tab.prompt.trim();
  const actionSqlSnippet = retryOptions?.actionSqlSnippet ?? (actionType === 'generate' ? '' : resolveSqlForAction(tab));
  if (actionType === 'generate' && !rawPrompt.trim()) {
    message.info('请先输入自然语言需求');
    return;
  }
  if (actionType !== 'generate' && !rawPrompt.trim() && !actionSqlSnippet) {
    message.info('请先输入说明，或在右侧编辑器中选择 SQL');
    return;
  }
  const promptText = rawPrompt.trim() || (actionType === 'explain'
    ? '请解释这段 SQL 的含义。'
    : '请分析这段 SQL 的合理性。');
  const userMessage = retryOptions?.userMessage ?? appendUserChatMessage(tab, promptText, actionType);
  if (!retryOptions) {
    tab.prompt = '';
  }
  const finalPrompt = retryOptions?.finalPrompt ?? (actionType === 'generate'
    ? promptText
    : mergePromptWithSqlSnippet(promptText, actionSqlSnippet));
  const retryMeta: RetryRequestMeta = {
    kind: 'ai_action',
    actionType,
    promptText,
    finalPrompt,
    actionSqlSnippet,
  };
  tab.aiGenerating = true;
  try {
    if (actionType === 'generate') {
      const generated = await postAiApiWithTimeout<AiGenerateSqlVO>('/api/ai/query/generate', {
        connectionId: tab.connectionId,
        sessionId: tab.sessionId,
        prompt: finalPrompt,
        databaseName: tab.databaseName || undefined,
        modelName: tab.selectedAiModel || undefined,
      });
      const generatedText = (generated.sqlText || '').trim();
      if (looksLikeSqlText(generatedText)) {
        appendAssistantSqlMessage(tab, generatedText, actionType);
        await saveConversationHistoryOnce(tab, userMessage, promptText, generatedText);
        message.success('SQL 已生成');
      } else {
        appendAssistantTextMessage(tab, generatedText || '未返回可执行 SQL', actionType);
        message.warning('未生成可执行 SQL，已返回说明内容');
      }
      if (generated.reasoning) {
        message.info(generated.reasoning);
      }
      clearUserRetryState(userMessage);
      return;
    }

    const endpoint = actionType === 'explain' ? '/api/ai/query/explain' : '/api/ai/query/analyze';
    const result = await postAiApiWithTimeout<AiTextResponseVO>(endpoint, {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      prompt: finalPrompt,
      databaseName: tab.databaseName || undefined,
      modelName: tab.selectedAiModel || undefined,
    });
    appendAssistantTextMessage(tab, result.content || '未返回内容', actionType);
    if (result.reasoning) {
      message.info(result.reasoning);
    }
    message.success(actionType === 'explain' ? 'SQL 含义解释已生成' : 'SQL 合理性分析已生成');
    clearUserRetryState(userMessage);
  } catch (error) {
    const msg = getErrorMessage(error);
    if (isTimeoutErrorMessage(msg)) {
      markUserMessageRetryable(tab, userMessage, retryMeta);
      message.error(timeoutRetryErrorMessage(msg));
    } else {
      clearUserRetryState(userMessage);
      message.error(msg);
    }
  } finally {
    tab.aiGenerating = false;
    touchQueryTab(tab);
  }
}

function autoActionTypeByIntent(intentType: AiIntentType): QueryActionType {
  if (intentType === 'EXPLAIN_SQL') {
    return 'auto_explain';
  }
  if (intentType === 'ANALYZE_SQL') {
    return 'auto_analyze';
  }
  if (intentType === 'GENERATE_CHART') {
    return 'auto_chart_auto_plan';
  }
  return 'auto_generate';
}

async function sendAutoForTab(tab: QueryWorkspaceTab, retryOptions?: RetryInvokeOptions) {
  if (tab.aiGenerating) {
    return;
  }
  const rawPrompt = retryOptions?.promptText ?? tab.prompt.trim();
  if (!rawPrompt) {
    message.info('请先输入自然语言需求');
    return;
  }
  const sqlSnippet = retryOptions?.actionSqlSnippet ?? resolveSqlForAction(tab);
  const finalPrompt = retryOptions?.finalPrompt ?? mergePromptWithSqlSnippet(rawPrompt, sqlSnippet);
  const userMessage = retryOptions?.userMessage ?? appendUserChatMessage(tab, rawPrompt, 'auto_generate');
  if (!retryOptions) {
    tab.prompt = '';
  }
  const retryMeta: RetryRequestMeta = {
    kind: 'auto',
    promptText: rawPrompt,
    finalPrompt,
    actionSqlSnippet: sqlSnippet,
  };
  tab.aiGenerating = true;
  try {
    const result = await postAiApiWithTimeout<AiAutoQueryVO>('/api/ai/query/auto', {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      prompt: finalPrompt,
      databaseName: tab.databaseName || undefined,
      modelName: tab.selectedAiModel || undefined,
    });
    const actionType = autoActionTypeByIntent(result.intentType);
    if (result.intentType === 'GENERATE_SQL') {
      const sqlText = (result.sqlText || '').trim();
      if (looksLikeSqlText(sqlText)) {
        appendAssistantSqlMessage(tab, sqlText, actionType);
        await saveConversationHistoryOnce(tab, userMessage, rawPrompt, sqlText, {
          actionType,
          databaseName: tab.databaseName,
        });
      } else {
        const contentText = sqlText || '未返回可执行 SQL';
        appendAssistantTextMessage(tab, contentText, actionType);
        await saveConversationHistoryOnce(tab, userMessage, rawPrompt, sqlText || '', {
          actionType,
          assistantContent: contentText,
          databaseName: tab.databaseName,
        });
      }
    } else if (result.intentType === 'GENERATE_CHART') {
      const sqlText = (result.sqlText || '').trim();
      const config = result.chartConfig ? cloneChartConfig(result.chartConfig) : null;
      const summary = (result.configSummary || '').trim() || chartSummaryText(config);
      if (!sqlText) {
        appendAssistantTextMessage(tab, summary || '未返回图表方案', actionType);
        await saveConversationHistoryOnce(tab, userMessage, rawPrompt, '', {
          actionType,
          assistantContent: summary || '未返回图表方案',
          chartConfig: config,
          databaseName: tab.databaseName,
        });
      } else {
        const plannedMessage = appendAssistantSqlMessage(
          tab,
          sqlText,
          actionType,
          summary,
          config,
          summary,
        );
        await saveConversationHistoryOnce(tab, userMessage, rawPrompt, sqlText, {
          actionType,
          assistantContent: summary,
          chartConfig: config,
          databaseName: tab.databaseName,
        });
        const generatedChart = await generateChartFromMessage(tab, plannedMessage, {
          appendRenderMessage: false,
          silentSuccess: true,
        });
        if (!generatedChart) {
          message.warning('图表方案已生成，自动执行失败，请手动点击“生成图表”');
        }
      }
    } else if (result.intentType === 'EXPLAIN_SQL' || result.intentType === 'ANALYZE_SQL') {
      const content = (result.content || '').trim() || '未返回内容';
      appendAssistantTextMessage(tab, content, actionType);
      await saveConversationHistoryOnce(tab, userMessage, rawPrompt, sqlSnippet || '', {
        actionType,
        assistantContent: content,
        databaseName: tab.databaseName,
      });
    } else {
      throw new Error('未识别的 Auto 意图类型');
    }

    if (result.fallbackUsed) {
      message.warning('Auto 模式已触发降级，请检查返回内容');
    }
    clearUserRetryState(userMessage);
  } catch (error) {
    const msg = getErrorMessage(error);
    if (isTimeoutErrorMessage(msg)) {
      markUserMessageRetryable(tab, userMessage, retryMeta);
      message.error(timeoutRetryErrorMessage(msg));
    } else {
      clearUserRetryState(userMessage);
      message.error(msg);
    }
  } finally {
    tab.aiGenerating = false;
    touchQueryTab(tab);
  }
}

async function retryUserMessage(tab: QueryWorkspaceTab, userMessage: QueryChatMessage) {
  const retryMeta = userMessage.retryMeta;
  if (!retryMeta || tab.aiGenerating) {
    return;
  }
  userMessage.retryLoading = true;
  touchQueryTab(tab);
  try {
    const retryOptions: RetryInvokeOptions = {
      userMessage,
      promptText: retryMeta.promptText,
      finalPrompt: retryMeta.finalPrompt,
      actionSqlSnippet: retryMeta.actionSqlSnippet || '',
    };
    if (retryMeta.kind === 'ai_action') {
      await generateSqlForTab(tab, retryMeta.actionType || 'generate', retryOptions);
      return;
    }
    if (retryMeta.kind === 'auto') {
      await sendAutoForTab(tab, retryOptions);
      return;
    }
    await generateChartPlanForTab(tab, retryOptions);
  } finally {
    userMessage.retryLoading = false;
    touchQueryTab(tab);
  }
}

function buildChartPrompt(promptText: string) {
  return [
    '请生成图表方案。',
    '要求：',
    '1) 返回可执行 SQL 和结构化图表配置；',
    '2) 配置需包含图表类型、字段映射、排序建议；',
    '3) 仅基于当前数据库上下文。',
    `用户需求：${promptText}`,
  ].join('\n');
}

function chartTypeLabel(chartType?: string) {
  const normalized = (chartType || '').toUpperCase();
  if (normalized === 'BAR') {
    return '柱状图';
  }
  if (normalized === 'PIE') {
    return '饼图';
  }
  if (normalized === 'SCATTER') {
    return '散点图';
  }
  if (normalized === 'TREND') {
    return '趋势图';
  }
  return '折线图';
}

function chartSummaryText(config?: ChartConfigVO | null) {
  if (!config) {
    return '未返回可用图表配置，请手动配置。';
  }
  const type = chartTypeLabel(config.chartType);
  if ((config.chartType || '').toUpperCase() === 'PIE') {
    return `${type} · 分类: ${config.categoryField || '-'} · 数值: ${config.valueField || '-'}`;
  }
  const y = (config.yFields || []).join(', ') || '-';
  return `${type} · X: ${config.xField || '-'} · Y: ${y}`;
}

function isChartConfigRenderable(config: ChartConfigVO | null | undefined, rows: Array<Record<string, string | null>>) {
  if (!config) {
    return false;
  }
  const fields = rows.length ? Object.keys(rows[0]) : [];
  const hasField = (field?: string) => !!field && fields.includes(field);
  const chartType = (config.chartType || '').toUpperCase();
  if (chartType === 'PIE') {
    return hasField(config.categoryField) && hasField(config.valueField);
  }
  if (chartType === 'SCATTER') {
    return hasField(config.xField) && !!config.yFields?.[0] && hasField(config.yFields[0]);
  }
  return hasField(config.xField) && !!config.yFields?.length && config.yFields.every((field) => hasField(field));
}

async function exportChartPngDataUrl() {
  for (let attempt = 0; attempt < 6; attempt += 1) {
    await nextTick();
    await new Promise((resolve) => setTimeout(resolve, attempt === 0 ? 40 : 90));
    const dataUrl = (await queryChartPanelRef.value?.exportPngDataUrl?.()) || '';
    if (dataUrl) {
      return dataUrl;
    }
  }
  return '';
}

async function saveChartImageCache(
  tab: QueryWorkspaceTab,
  imageDataUrl: string,
  suggestedFileName: string,
) {
  const payload: ChartCacheSaveReq = {
    connectionId: tab.connectionId,
    sessionId: tab.sessionId,
    imageBase64Png: imageDataUrl,
    suggestedFileName,
  };
  const saved = await postApi<ChartCacheSaveVO>('/api/editor/chart/cache/save', payload);
  return saved.cacheKey || '';
}

function downloadImage(dataUrl: string, fileName: string) {
  if (!dataUrl) {
    return;
  }
  const anchor = document.createElement('a');
  anchor.href = dataUrl;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
}

async function generateChartPlanForTab(tab: QueryWorkspaceTab, retryOptions?: RetryInvokeOptions) {
  if (tab.aiGenerating) {
    return;
  }
  const rawPrompt = retryOptions?.promptText ?? tab.prompt.trim();
  if (!rawPrompt) {
    message.info('请先输入图表需求');
    return;
  }
  const finalPrompt = retryOptions?.finalPrompt ?? buildChartPrompt(rawPrompt);
  const userMessage = retryOptions?.userMessage ?? appendUserChatMessage(tab, rawPrompt, 'chart_auto_plan');
  if (!retryOptions) {
    tab.prompt = '';
  }
  const retryMeta: RetryRequestMeta = {
    kind: 'chart_plan',
    promptText: rawPrompt,
    finalPrompt,
  };
  tab.aiGenerating = true;
  try {
    const generated = await postAiApiWithTimeout<AiGenerateChartVO>('/api/ai/query/generate-chart', {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      prompt: finalPrompt,
      databaseName: tab.databaseName || undefined,
      modelName: tab.selectedAiModel || undefined,
    });
    const sqlText = (generated.sqlText || '').trim();
    const config = generated.chartConfig ? cloneChartConfig(generated.chartConfig) : null;
    const summary = (generated.configSummary || '').trim() || chartSummaryText(config);
    if (!sqlText) {
      appendAssistantTextMessage(tab, summary || '未返回图表方案', 'chart_auto_plan');
      message.warning('未生成可执行 SQL');
      return;
    }
    const plannedMessage = appendAssistantSqlMessage(
      tab,
      sqlText,
      'chart_auto_plan',
      summary,
      config,
      summary,
    );
    await saveConversationHistoryOnce(tab, userMessage, rawPrompt, sqlText, {
      actionType: 'chart_auto_plan',
      assistantContent: summary,
      chartConfig: config,
      databaseName: tab.databaseName,
    });
    if (generated.reasoning) {
      message.info(generated.reasoning);
    }
    const generatedChart = await generateChartFromMessage(tab, plannedMessage, {
      appendRenderMessage: false,
      silentSuccess: true,
    });
    if (!generatedChart) {
      message.warning('图表方案已生成，自动执行失败，请手动点击“生成图表”');
      return;
    }
    message.success('AI 图表方案已执行并生成图表');
    clearUserRetryState(userMessage);
  } catch (error) {
    const msg = getErrorMessage(error);
    if (isTimeoutErrorMessage(msg)) {
      markUserMessageRetryable(tab, userMessage, retryMeta);
      message.error(timeoutRetryErrorMessage(msg));
    } else {
      clearUserRetryState(userMessage);
      message.error(msg);
    }
  } finally {
    tab.aiGenerating = false;
    touchQueryTab(tab);
  }
}

interface GenerateChartFromMessageOptions {
  appendRenderMessage?: boolean;
  silentSuccess?: boolean;
}

async function generateChartFromMessage(
  tab: QueryWorkspaceTab,
  item: QueryChatMessage,
  options?: GenerateChartFromMessageOptions,
) {
  const sqlText = (item.sqlText || '').trim();
  if (!sqlText) {
    message.warning('当前消息不包含 SQL，无法生成图表');
    return;
  }
  const success = await executeSqlForTab(tab, sqlText, { silentSuccess: true });
  if (!success) {
    return;
  }

  const rows = activeChartRows.value;
  let config = item.chartConfig ? cloneChartConfig(item.chartConfig) : null;
  if (!isChartConfigRenderable(config, rows)) {
    setupManualChartConfigByResult(tab);
    config = cloneChartConfig(tab.manualChartConfig);
    message.warning('AI 配置与结果字段不匹配，已切换为手动默认配置');
  } else {
    tab.manualChartConfig = cloneChartConfig(config);
  }
  tab.activeChartConfig = config;
  tab.resultViewMode = 'chart';
  tab.chartReadonly = false;
  touchQueryTab(tab);

  const imageDataUrl = await exportChartPngDataUrl();
  if (!imageDataUrl) {
    message.warning('图表渲染完成，但图片导出失败');
    return false;
  }
  tab.chartImageDataUrl = imageDataUrl;
  item.chartImageDataUrl = imageDataUrl;
  let cacheKey = '';
  try {
    cacheKey = await saveChartImageCache(tab, imageDataUrl, `chart-auto-${Date.now()}`);
    tab.chartImageCacheKey = cacheKey;
    item.chartImageCacheKey = cacheKey;
  } catch {
    message.warning('图表已生成，但图片缓存失败，仅本次可见');
  }
  if (options?.appendRenderMessage !== false) {
    const renderMessage = appendAssistantSqlMessage(
      tab,
      sqlText,
      'chart_auto_render',
      '图表已生成',
      config,
      chartSummaryText(config),
      cacheKey,
    );
    renderMessage.chartImageDataUrl = imageDataUrl;
  }
  await saveConversationHistory(tab, '生成图表', sqlText, {
    actionType: 'chart_auto_render',
    assistantContent: chartSummaryText(config),
    chartConfig: config,
    chartImageCacheKey: cacheKey,
    databaseName: tab.databaseName,
  });
  if (!options?.silentSuccess) {
    message.success('图表已生成');
  }
  return true;
}

async function generateManualChartForTab(tab: QueryWorkspaceTab) {
  if (!tab.executeResult?.rows?.length) {
    message.info('当前没有可用于制图的查询结果');
    return;
  }
  const config = cloneChartConfig(tab.manualChartConfig);
  const rows = activeChartRows.value;
  if (!isChartConfigRenderable(config, rows)) {
    message.warning('图表字段配置不完整，请先选择有效字段');
    return;
  }
  tab.activeChartConfig = config;
  tab.resultViewMode = 'chart';
  tab.chartReadonly = false;
  touchQueryTab(tab);

  const imageDataUrl = await exportChartPngDataUrl();
  if (!imageDataUrl) {
    message.warning('图表渲染完成，但图片导出失败');
    return;
  }
  tab.chartImageDataUrl = imageDataUrl;
  let cacheKey = '';
  try {
    cacheKey = await saveChartImageCache(tab, imageDataUrl, `chart-manual-${Date.now()}`);
    tab.chartImageCacheKey = cacheKey;
  } catch {
    message.warning('图表已生成，但图片缓存失败，仅本次可见');
  }
  message.success('手动图表已生成');
}

async function downloadActiveChart(tab: QueryWorkspaceTab) {
  let dataUrl = tab.chartImageDataUrl;
  if (!dataUrl) {
    dataUrl = await exportChartPngDataUrl();
  }
  if (!dataUrl) {
    message.info('暂无可下载的图表图片');
    return;
  }
  downloadImage(dataUrl, `chart-${Date.now()}.png`);
}

async function downloadMessageChart(item: QueryChatMessage) {
  let dataUrl = item.chartImageDataUrl || '';
  if (!dataUrl && item.chartImageCacheKey) {
    try {
      const loaded = await getApi<ChartCacheReadVO>(
        `/api/editor/chart/cache/read?cacheKey=${encodeURIComponent(item.chartImageCacheKey)}`,
      );
      dataUrl = loaded.dataUrl || '';
      item.chartImageDataUrl = dataUrl;
    } catch {
      message.error('缓存图表不存在，请重跑 SQL 后重新生成');
      return;
    }
  }
  if (!dataUrl) {
    message.info('当前消息暂无图表图片');
    return;
  }
  downloadImage(dataUrl, `chart-${Date.now()}.png`);
}

async function hydrateHistoryChartImages(tab: QueryWorkspaceTab) {
  const targets = tab.chatMessages.filter((item) => item.role === 'assistant' && !!item.chartImageCacheKey);
  for (const item of targets) {
    if (item.chartImageDataUrl || !item.chartImageCacheKey) {
      continue;
    }
    try {
      const loaded = await getApi<ChartCacheReadVO>(
        `/api/editor/chart/cache/read?cacheKey=${encodeURIComponent(item.chartImageCacheKey)}`,
      );
      item.chartImageDataUrl = loaded.dataUrl || '';
    } catch {
      // 历史图缺失不阻断会话加载。
    }
  }
}

async function editChartFromHistory(tab: QueryWorkspaceTab, item: QueryChatMessage) {
  await generateChartFromMessage(tab, item);
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

const RISK_EXECUTION_CANCELLED = 'RISK_EXECUTION_CANCELLED';

function connectionEnvLabel(connectionId: number) {
  const env = connections.value.find((item) => item.id === connectionId)?.env ?? 'DEV';
  return env.toUpperCase();
}

async function ensureRiskConfirmedBeforeExecute(tab: QueryWorkspaceTab, sqlText: string) {
  const result = await postApi<RiskEvaluateVO>('/api/sql/risk/evaluate', {
    connectionId: tab.connectionId,
    sqlText,
  });
  tab.riskInfo = result;
  touchQueryTab(tab);
  const riskAckToken = (result.riskAckToken ?? '').trim();
  if (!result.confirmRequired) {
    return riskAckToken;
  }
  const normalizedRiskLevel = normalizeRiskLevel(result.riskLevel);
  const confirmLevelClass = `risk-level-${normalizedRiskLevel.toLowerCase()}`;
  const riskItemsText = (result.riskItems ?? [])
    .map((item, index) => `${index + 1}. [${item.level}] ${item.description}`)
    .join('\n') || '无风险明细';
  const confirmed = await new Promise<boolean>((resolve) => {
    Modal.confirm({
      title: `${connectionEnvLabel(tab.connectionId)} 环境 SQL 风险确认`,
      content: h('div', { class: ['risk-confirm-content', confirmLevelClass] }, [
        h('div', { class: 'risk-confirm-level-row' }, [
          h('span', '风险级别'),
          h('span', { class: 'risk-confirm-level-badge' }, normalizedRiskLevel),
        ]),
        h('div', `确认策略: ${result.confirmReason || '-'}`),
        h('pre', { class: 'risk-confirm-pre' }, riskItemsText),
      ]),
      okText: '确认执行',
      cancelText: '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    });
  });
  if (!confirmed) {
    throw new Error(RISK_EXECUTION_CANCELLED);
  }
  if (!riskAckToken) {
    throw new Error('风险确认令牌缺失，请重新执行');
  }
  return riskAckToken;
}

async function executeSqlForTab(
  tab: QueryWorkspaceTab,
  sqlOverride?: string,
  options?: { silentSuccess?: boolean },
) {
  if (tab.sqlExecuting) {
    return false;
  }
  const sqlText = resolveSqlForAction(tab, sqlOverride);
  if (!sqlText) {
    message.error('请先输入或选择 SQL');
    return false;
  }
  tab.sqlExecuting = true;
  touchQueryTab(tab);
  let riskAckToken = '';
  try {
    try {
      riskAckToken = await ensureRiskConfirmedBeforeExecute(tab, sqlText);
    } catch (error) {
      const errMsg = error instanceof Error ? error.message : String(error);
      if (errMsg === RISK_EXECUTION_CANCELLED) {
        message.info('已取消执行');
        return false;
      }
      message.error(errMsg);
      return false;
    }
    try {
      tab.riskAckToken = riskAckToken;
      const result = await postApi<SqlExecuteVO>('/api/sql/execute', {
        connectionId: tab.connectionId,
        sessionId: tab.sessionId,
        sqlText,
        databaseName: tab.databaseName || undefined,
        riskAckToken: riskAckToken || undefined,
        operatorName: 'desktop-user',
      });
      tab.executeResult = result;
      tab.explainResult = null;
      tab.riskAckToken = '';
      tab.lastExecuteFailed = false;
      tab.lastExecuteErrorMessage = '';
      tab.lastFailedSqlText = '';
      tab.resultViewMode = 'table';
      tab.chartReadonly = false;
      tab.chartImageDataUrl = '';
      tab.chartImageCacheKey = '';
      setupManualChartConfigByResult(tab);
      if (!options?.silentSuccess) {
        message.success(`执行成功，耗时 ${result.executionMs}ms`);
      }
      return true;
    } catch (error) {
      const errMsg = error instanceof Error ? error.message : String(error);
      tab.executeResult = null;
      tab.explainResult = null;
      tab.riskAckToken = '';
      tab.lastExecuteFailed = true;
      tab.lastExecuteErrorMessage = errMsg;
      tab.lastFailedSqlText = sqlText;
      return false;
    }
  } finally {
    tab.sqlExecuting = false;
    touchQueryTab(tab);
  }
}

async function repairSqlForTab(tab: QueryWorkspaceTab) {
  if (!tab.lastExecuteFailed) {
    message.info('最近一次 SQL 执行未失败，无需修复');
    return;
  }
  const failedSql = tab.lastFailedSqlText.trim();
  const errorMessage = tab.lastExecuteErrorMessage.trim();
  if (!failedSql || !errorMessage) {
    message.error('缺少失败 SQL 或错误信息，无法执行修复');
    return;
  }
  if (tab.aiGenerating) {
    return;
  }
  tab.aiGenerating = true;
  try {
    appendUserChatMessage(
      tab,
      `请修复以下 SQL 执行错误。\n错误信息：${errorMessage}\n\nSQL:\n${failedSql}`,
      'repair',
    );
    const repaired = await postApi<AiRepairVO>('/api/ai/query/repair', {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      sqlText: failedSql,
      errorMessage,
      databaseName: tab.databaseName || undefined,
      modelName: tab.selectedAiModel || undefined,
    });
    appendAssistantSqlMessage(
      tab,
      repaired.repairedSql || failedSql,
      'repair',
      (repaired.errorExplanation || repaired.repairNote || '已尝试修复 SQL').trim(),
    );
    tab.lastExecuteFailed = false;
    tab.lastExecuteErrorMessage = '';
    tab.lastFailedSqlText = '';
    touchQueryTab(tab);
    message.success(repaired.repairNote || '修复建议已生成');
  } catch (error) {
    const errMsg = error instanceof Error ? error.message : String(error);
    message.error(errMsg);
  } finally {
    tab.aiGenerating = false;
  }
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
  const normalizedLevel = normalizeRiskLevel(level);
  if (normalizedLevel === 'HIGH') {
    return 'red';
  }
  if (normalizedLevel === 'MEDIUM') {
    return 'orange';
  }
  return 'green';
}

function normalizeRiskLevel(level?: string): 'LOW' | 'MEDIUM' | 'HIGH' {
  if (level === 'HIGH' || level === 'MEDIUM' || level === 'LOW') {
    return level;
  }
  return 'LOW';
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
  loadUiThemePreference();
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
  sqlEditorScrollDisposable?.dispose();
  sqlEditorScrollDisposable = null;
  sqlCompletionProviderDisposable?.dispose();
  sqlCompletionProviderDisposable = null;
  activeSqlEditorInstance = null;
  hideSqlSelectionPopover();
  if (sqlAutoSuggestTimer !== null) {
    window.clearTimeout(sqlAutoSuggestTimer);
    sqlAutoSuggestTimer = null;
  }
});

watch(
  () => uiTheme.value,
  () => {
    persistUiThemePreference();
  },
);

watch(
  () => [activeWorkbenchTab.value, activeQueryTab.value?.connectionId ?? 0, activeQueryTab.value?.databaseName ?? ''],
  () => {
    if (!activeQueryTab.value) {
      hideSqlSelectionPopover();
      return;
    }
    activeQueryTab.value.selectedSqlText = '';
    hideSqlSelectionPopover();
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
      connectionForm.password = '';
      connectionForm.selectedDatabases = [];
    }
    if (!isMultiDatabaseType(dbType)) {
      connectionForm.selectedDatabases = [];
    } else if (connectionForm.databaseName === 'sample.db') {
      connectionForm.databaseName = '';
    }
    connectionPreviewDbOptions.value = [];
    connectionPreviewError.value = '';
  },
  { immediate: true },
);

watch(
  () => connectionForm.sshEnabled,
  (enabled) => {
    if (!enabled) {
      connectionForm.sshAuthType = 'SSH_PASSWORD';
      connectionForm.sshPassword = '';
      connectionForm.sshPrivateKeyPath = '';
      connectionForm.sshPrivateKeyText = '';
      connectionForm.sshPrivateKeyPassphrase = '';
      return;
    }
    if (!connectionForm.sshPort || connectionForm.sshPort <= 0) {
      connectionForm.sshPort = 22;
    }
    if (!connectionForm.sshAuthType) {
      connectionForm.sshAuthType = 'SSH_PASSWORD';
    }
  },
  { immediate: true },
);

watch(
  () => connectionForm.sshAuthType,
  (mode) => {
    if (!connectionForm.sshEnabled) {
      return;
    }
    if (mode === 'SSH_PASSWORD') {
      connectionForm.sshPrivateKeyPath = '';
      connectionForm.sshPrivateKeyText = '';
      connectionForm.sshPrivateKeyPassphrase = '';
      return;
    }
    if (mode === 'SSH_KEY_PATH') {
      connectionForm.sshPassword = '';
      connectionForm.sshPrivateKeyText = '';
      return;
    }
    if (mode === 'SSH_KEY_TEXT') {
      connectionForm.sshPassword = '';
      connectionForm.sshPrivateKeyPath = '';
    }
  },
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

function normalizeEnv(value?: string) {
  const env = (value || '').trim().toUpperCase();
  if (env === 'PROD' || env === 'TEST' || env === 'DEV') {
    return env;
  }
  return 'DEV';
}

function envTagText(value?: string) {
  const env = normalizeEnv(value);
  if (env === 'PROD') {
    return '生产';
  }
  if (env === 'TEST') {
    return '测试';
  }
  return '开发';
}

function envTagClass(value?: string) {
  const env = normalizeEnv(value);
  if (env === 'PROD') {
    return 'is-prod';
  }
  if (env === 'TEST') {
    return 'is-test';
  }
  return 'is-dev';
}

function envTagIcon(value?: string) {
  const env = normalizeEnv(value);
  if (env === 'PROD') {
    return CloseCircleOutlined;
  }
  if (env === 'TEST') {
    return CheckCircleOutlined;
  }
  return ToolOutlined;
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

function quoteSqlIdentifier(identifier: string, dbType: string) {
  const text = String(identifier || '').trim();
  if (!text) {
    return '';
  }
  if (dbType === 'SQLSERVER') {
    return `[${text}]`;
  }
  if (dbType === 'POSTGRESQL' || dbType === 'ORACLE') {
    return `"${text}"`;
  }
  return `\`${text}\``;
}

function buildColumnSqlDefinition(
  column: TableDetailVO['columns'][number],
  dbType: string,
) {
  const colName = quoteSqlIdentifier(column.columnName, dbType) || column.columnName;
  const baseType = (column.dataType || 'TEXT').trim();
  let typeSql = baseType;
  if (!/\(/.test(baseType) && column.columnSize && column.columnSize > 0) {
    if (column.decimalDigits && column.decimalDigits > 0) {
      typeSql = `${baseType}(${column.columnSize},${column.decimalDigits})`;
    } else if (/char|binary|var|text|int|number|decimal|numeric/i.test(baseType)) {
      typeSql = `${baseType}(${column.columnSize})`;
    }
  }
  const fragments = [`${colName} ${typeSql}`];
  if (column.nullable === false) {
    fragments.push('NOT NULL');
  }
  if (column.defaultValue != null && String(column.defaultValue).trim() !== '') {
    fragments.push(`DEFAULT ${String(column.defaultValue).trim()}`);
  }
  if (column.autoIncrement) {
    if (dbType === 'SQLSERVER') {
      fragments.push('IDENTITY(1,1)');
    } else if (dbType === 'POSTGRESQL') {
      fragments.push('GENERATED BY DEFAULT AS IDENTITY');
    } else {
      fragments.push('AUTO_INCREMENT');
    }
  }
  if (column.columnComment && dbType === 'MYSQL') {
    fragments.push(`COMMENT '${column.columnComment.replace(/'/g, "''")}'`);
  }
  return fragments.join(' ');
}

function buildCreateTableSql(tableName: string, columns: TableDetailVO['columns'], dbTypeRaw: string) {
  const dbType = (dbTypeRaw || 'MYSQL').toUpperCase();
  const lines = columns.map((column) => `  ${buildColumnSqlDefinition(column, dbType)}`);
  const primaryKeys = columns
    .filter((column) => column.primaryKey)
    .map((column) => quoteSqlIdentifier(column.columnName, dbType) || column.columnName);
  if (primaryKeys.length) {
    lines.push(`  PRIMARY KEY (${primaryKeys.join(', ')})`);
  }
  const tableQuoted = quoteSqlIdentifier(tableName, dbType) || tableName;
  return `CREATE TABLE ${tableQuoted} (\n${lines.join(',\n')}\n);`;
}

function escapeHtml(raw: string) {
  return raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function highlightSqlForDisplay(sqlText: string) {
  const escaped = escapeHtml(sqlText || '');
  const keywords = [
    'CREATE',
    'TABLE',
    'PRIMARY',
    'KEY',
    'NOT',
    'NULL',
    'DEFAULT',
    'AUTO_INCREMENT',
    'COMMENT',
    'GENERATED',
    'BY',
    'AS',
    'IDENTITY',
  ];
  const dataTypes = [
    'BIGINT',
    'INT',
    'INTEGER',
    'SMALLINT',
    'TINYINT',
    'MEDIUMINT',
    'SERIAL',
    'BIGSERIAL',
    'DECIMAL',
    'NUMERIC',
    'FLOAT',
    'DOUBLE',
    'REAL',
    'BOOLEAN',
    'BIT',
    'CHAR',
    'NCHAR',
    'VARCHAR',
    'NVARCHAR',
    'TEXT',
    'MEDIUMTEXT',
    'LONGTEXT',
    'DATE',
    'TIME',
    'DATETIME',
    'TIMESTAMP',
    'YEAR',
    'BLOB',
    'LONGBLOB',
    'JSON',
    'UUID',
  ];
  const keywordSet = new Set(keywords.map((item) => item.toUpperCase()));
  const typeSet = new Set(dataTypes.map((item) => item.toUpperCase()));
  const tokens = Array.from(new Set([...keywords, ...dataTypes]))
    .sort((a, b) => b.length - a.length);
  const tokenPattern = new RegExp(`\\b(${tokens.join('|')})\\b`, 'gi');
  return escaped.replace(tokenPattern, (matched) => {
    const upper = matched.toUpperCase();
    if (keywordSet.has(upper)) {
      return `<span class="sql-keyword">${matched}</span>`;
    }
    if (typeSet.has(upper)) {
      return `<span class="sql-datatype">${matched}</span>`;
    }
    return matched;
  });
}

async function copyTextContent(text: string, successText: string) {
  if (!text.trim()) {
    return;
  }
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      message.success(successText);
      return;
    }
    throw new Error('clipboard unavailable');
  } catch {
    const input = document.createElement('textarea');
    input.value = text;
    input.setAttribute('readonly', 'true');
    input.style.position = 'fixed';
    input.style.opacity = '0';
    document.body.appendChild(input);
    input.select();
    document.execCommand('copy');
    document.body.removeChild(input);
    message.success(successText);
  }
}

async function copyCreateTableSql() {
  await copyTextContent(createTableSqlText.value, '建表语句已复制');
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
    selectedDatabases: [],
    username: '',
    password: '',
    authType: 'PASSWORD',
    env: 'DEV',
    readOnly: false,
    sshEnabled: false,
    sshHost: '',
    sshPort: 22,
    sshUser: '',
    sshAuthType: 'SSH_PASSWORD',
    sshPassword: '',
    sshPrivateKeyPath: '',
    sshPrivateKeyText: '',
    sshPrivateKeyPassphrase: '',
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
    selectedDatabases: normalizeSelectedDatabases(connection.selectedDatabases),
    username: connection.username ?? '',
    password: '',
    authType: 'PASSWORD',
    env: connection.env,
    readOnly: connection.readOnly,
    sshEnabled: connection.sshEnabled,
    sshHost: connection.sshHost ?? '',
    sshPort: connection.sshPort ?? 22,
    sshUser: connection.sshUser ?? '',
    sshAuthType: (connection.sshAuthType as ConnectionCreateReq['sshAuthType']) || 'SSH_PASSWORD',
    sshPassword: '',
    sshPrivateKeyPath: connection.sshPrivateKeyPath ?? '',
    sshPrivateKeyText: '',
    sshPrivateKeyPassphrase: '',
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
  connectionPreviewDbOptions.value = [];
  connectionPreviewError.value = '';
}
</script>
