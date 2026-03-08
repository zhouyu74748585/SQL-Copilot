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
          <button
            v-for="tab in erTabs"
            :key="tab.key"
            class="workspace-tab"
            :class="{ 'is-active': activeWorkbenchTab === tab.key }"
            @click="activeWorkbenchTab = tab.key"
          >
            <span>{{ tab.title }}</span>
            <close-outlined class="tab-close" @click.stop="closeErTab(tab.key)" />
          </button>
          <button
            v-for="tab in tableEditorTabs"
            :key="tab.key"
            class="workspace-tab"
            :class="{ 'is-active': activeWorkbenchTab === tab.key }"
            @click="activeWorkbenchTab = tab.key"
          >
            <span>{{ tab.title }}</span>
            <close-outlined class="tab-close" @click.stop="closeTableEditorTab(tab.key)" />
          </button>
          <a-tooltip title="新建 AI 查询页签">
            <button class="top-chrome-tab-add" @click="openAiQueryTab()">
              <plus-outlined />
            </button>
          </a-tooltip>
        </div>
      </div>
      <div class="top-chrome-actions">
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
                    会话ID: {{ item.sessionId }} | 记录: {{ item.messageCount ?? 0 }} | 累计Token: {{ item.totalTokens ?? 0 }}
                  </div>
                  <div class="history-menu-item-desc">创建: {{ formatTime(item.createdAt) }}</div>
                </button>
                <div v-if="historyLoadingMore" class="history-menu-load-tip">加载中...</div>
                <div v-else-if="sessionHistoryTabs.length && !historySessionHasMore" class="history-menu-load-tip">没有更多会话</div>
              </div>
            </div>
          </template>
        </a-dropdown>
        <a-dropdown placement="bottomLeft" :trigger="['click']">
          <button class="tool-item top-action-btn" :disabled="!canOpenErSnapshot" title="ER图快照" @click="handleErSnapshotMenuClick">
            <apartment-outlined />
            <span>ER图</span>
          </button>
          <template #overlay>
            <div class="history-menu-panel er-snapshot-menu-panel">
              <div class="history-menu-title">
                <span>ER 图快照 · {{ queryTabConnectionNameById(erSnapshotConnectionId) || '-' }}</span>
                <span v-if="erSnapshotReloading" class="history-menu-loading">刷新中...</span>
              </div>
              <div class="history-menu-toolbar">
                <a-input
                  v-model:value="erSnapshotKeywordInput"
                  size="small"
                  allow-clear
                  placeholder="按名称或数据库搜索"
                  @pressEnter="applyErSnapshotKeywordSearch"
                />
                <a-button size="small" class="history-search-btn" @click="applyErSnapshotKeywordSearch">搜索</a-button>
              </div>
              <div class="history-menu-list" @scroll="handleErSnapshotMenuScroll">
                <div v-if="!erSnapshotItems.length" class="history-menu-empty">暂无 ER 图快照</div>
                <button
                  v-for="item in erSnapshotItems"
                  :key="erSnapshotItemKey(item)"
                  class="history-menu-item"
                  :class="{ 'is-active': isErSnapshotItemActive(item), 'is-loading': erSnapshotLoadingId === item.id || erSnapshotActionLoadingId === item.id }"
                  @click="openErSnapshot(item)"
                >
                  <div class="history-menu-item-head">
                    <a-input
                      v-if="editingErSnapshotId === item.id"
                      v-model:value="editingErSnapshotTitle"
                      size="small"
                      class="history-menu-title-input"
                      maxlength="80"
                      @click.stop
                      @pressEnter="commitErSnapshotTitleEdit(item)"
                      @blur="commitErSnapshotTitleEdit(item)"
                      @keydown.esc.stop.prevent="cancelErSnapshotTitleEdit"
                    />
                    <span v-else class="history-menu-item-title">{{ item.snapshotName || '未命名快照' }}</span>
                    <div class="history-menu-item-head-actions">
                      <span>{{ formatTime(item.updatedAt) }}</span>
                      <a-button size="small" type="link" class="history-menu-rename-btn" title="改名" @click.stop="startErSnapshotTitleEdit(item)">
                        <template #icon><edit-outlined /></template>
                      </a-button>
                      <a-button
                        size="small"
                        type="link"
                        danger
                        class="history-menu-delete-btn"
                        title="删除快照"
                        :disabled="erSnapshotLoadingId === item.id || erSnapshotActionLoadingId === item.id"
                        @click.stop="removeErSnapshot(item)"
                      >
                        <template #icon><delete-outlined /></template>
                      </a-button>
                    </div>
                  </div>
                  <div class="history-menu-item-meta">
                    数据库: {{ item.databaseName || '-' }} | 表: {{ item.tableCount ?? 0 }}
                  </div>
                  <div class="history-menu-item-desc">模型: {{ modelLabelById(item.modelName || '') }}</div>
                </button>
                <div v-if="erSnapshotLoadingMore" class="history-menu-load-tip">加载中...</div>
                <div v-else-if="erSnapshotItems.length && !erSnapshotHasMore" class="history-menu-load-tip">没有更多快照</div>
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

    <main
      class="workbench"
      :class="{
        'workbench-query': !!activeQueryTab,
        'workbench-table-editor': !!activeTableEditorTab,
        'workbench-er': !!activeErTab,
        'workbench-browser': activeWorkbenchTab === browserTabKey,
      }"
      :style="workbenchStyle"
    >
      <aside class="pane pane-left">
        <div class="pane-title pane-title-with-action">
          <span>我的连接</span>
          <div class="pane-title-actions">
            <a-button size="small" type="text" @click="openCreateModal" title="新建连接">
              <template #icon>
                <link-outlined />
              </template>
              新建链接
            </a-button>
            <a-button size="small" type="text" :loading="connectionRefreshing" @click="refreshConnections" title="刷新连接列表">
              <template #icon>
                <reload-outlined />
              </template>
            </a-button>
          </div>
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

      <div class="pane-splitter pane-splitter-left" @mousedown="startResizeLeftPane" />

      <template v-if="activeWorkbenchTab === browserTabKey">
        <section class="pane pane-center">
          <div class="pane-title">对象浏览</div>
          <div class="center-toolbar">
            <div v-if="currentObjectType === 'tables'" class="center-toolbar-left">
              <a-button
                size="small"
                type="primary"
                :disabled="!canCreateTable"
                @click="openNewTableEditor()"
              >
                <template #icon><plus-outlined /></template>
                新建表
              </a-button>
              <a-tooltip :title="browserErEntryTooltip">
                <a-button
                  size="small"
                  :disabled="!canOpenBrowserErFeature"
                  @click="openErTableSelectModal()"
                >
                  <template #icon><apartment-outlined /></template>
                  智能ER图
                </a-button>
              </a-tooltip>
            </div>
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
              <template v-else-if="column.key === 'vectorizeStatus'">
                <a-tooltip
                  :title="record.vectorizeMessage
                    ? `${databaseStatusLabel(record.vectorizeStatus)} | ${record.vectorizeMessage}`
                    : databaseStatusLabel(record.vectorizeStatus)"
                >
                  <span class="object-vectorize-cell" :class="databaseStatusClass(record.vectorizeStatus)">
                    <component :is="databaseStatusIcon(record.vectorizeStatus)" class="object-vectorize-icon" />
                    <span>{{ databaseStatusLabel(record.vectorizeStatus) }}</span>
                  </span>
                </a-tooltip>
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
              <div class="object-card-vectorize" :class="databaseStatusClass(item.vectorizeStatus)">
                <component :is="databaseStatusIcon(item.vectorizeStatus)" class="object-vectorize-icon" />
                <span>{{ databaseStatusLabel(item.vectorizeStatus) }}</span>
              </div>
              <div class="object-card-desc">{{ item.description || '-' }}</div>
            </div>
          </div>

          <div class="center-status">
            <span>对象: {{ filteredObjectRows.length }}</span>
            <span>类型: {{ objectTypeLabel(currentObjectType) }}</span>
            <span>字段: {{ schemaOverview?.columnCount ?? 0 }}</span>
          </div>
        </section>

        <div class="pane-splitter pane-splitter-right" @mousedown="startResizeBrowserPane" />

        <aside class="pane pane-right detail-pane">
          <div class="pane-title">对象详情</div>
          <div v-if="!selectedObjectRecord && !selectedTreeDetail" class="empty-pane">请从对象浏览中选择连接、数据库或对象</div>
          <div v-else-if="selectedObjectRecord" class="detail-wrapper">
            <div class="detail-summary">
              <div class="detail-row"><span>对象</span><strong>{{ selectedObjectRecord.objectName }}</strong></div>
              <div class="detail-row"><span>类型</span><strong>{{ objectTypeLabel(selectedObjectRecord.objectType) }}</strong></div>
              <div class="detail-row detail-row-description"><span>说明</span><strong>{{ selectedObjectRecord.description || '-' }}</strong></div>
              <div class="detail-row"><span>向量化</span><strong>{{ databaseStatusLabel(selectedObjectRecord.vectorizeStatus) }}</strong></div>
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

            <div v-else class="detail-note">当前对象类型暂无结构详情，仅展示基本信息</div>
          </div>
          <div v-else-if="selectedTreeDetail?.kind === 'connection'" class="detail-wrapper">
            <div class="detail-summary">
              <div class="detail-row"><span>连接</span><strong>{{ selectedTreeConnection?.name ?? '-' }}</strong></div>
              <div class="detail-row"><span>数据库类型</span><strong>{{ selectedTreeConnection?.dbType ?? '-' }}</strong></div>
              <div class="detail-row"><span>所属环境</span><strong>{{ envTagText(selectedTreeConnection?.env) }}</strong></div>
              <div class="detail-row"><span>主机</span><strong>{{ selectedTreeConnection?.host || '本地连接' }}</strong></div>
              <div class="detail-row"><span>端口</span><strong>{{ selectedTreeConnection?.port ?? '-' }}</strong></div>
              <div class="detail-row"><span>用户</span><strong>{{ selectedTreeConnection?.username || '-' }}</strong></div>
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

      <template v-else-if="activeErTab">
        <section class="pane pane-center er-diagram-pane">
          <div class="pane-title">智能ER图 · {{ activeErTab.title }}</div>
          <div class="er-toolbar">
            <a-space size="small">
              <a-button size="small" @click="openErTableSelectModal(activeErTab)">
                <template #icon><appstore-outlined /></template>
                重选
              </a-button>
              <a-button
                size="small"
                type="primary"
                :loading="activeErTab.loading"
                @click="refreshErGraphForTab(activeErTab, true)"
              >
                <template #icon><reload-outlined /></template>
                刷新
              </a-button>
              <a-button size="small" @click="openErSnapshotSaveModal(activeErTab)">
                <template #icon><hdd-outlined /></template>
                保存
              </a-button>
              <a-button size="small" @click="downloadActiveErDiagram(activeErTab)">
                <template #icon><download-outlined /></template>
                导出
              </a-button>
            </a-space>
            <a-space size="small">
              <span class="er-toolbar-label">模型</span>
              <a-select
                  v-model:value="activeErTab.selectedAiModel"
                  size="small"
                  style="min-width: 190px"
                  :options="aiModelOptions"
                  @change="touchErTab(activeErTab)"
              />
              <span class="er-toolbar-label">布局</span>
              <a-select
                v-model:value="activeErTab.layoutMode"
                size="small"
                style="width: 132px"
                :options="erLayoutModeOptions"
                @change="touchErTab(activeErTab)"
              />
              <span class="er-toolbar-label">线型</span>
              <a-select
                v-model:value="activeErTab.lineType"
                size="small"
                style="width: 104px"
                :options="erLineTypeOptions"
                @change="touchErTab(activeErTab)"
              />
            </a-space>
          </div>
          <div class="er-canvas-wrap">
            <a-spin :spinning="activeErTab.loading">
              <ErDiagramPanel
                ref="erDiagramPanelRef"
                :graph="activeErDisplayGraph"
                :layout-mode="activeErTab.layoutMode"
                :line-type="activeErTab.lineType"
                :show-comments="activeErTab.showCardComments"
                @relation-route-change="handleErRelationRouteChange(activeErTab, $event)"
              />
            </a-spin>
          </div>
        </section>
        <div class="pane-splitter pane-splitter-right er-pane-splitter" @mousedown="startResizeErPane" />

        <aside class="pane pane-right er-side-pane">
          <div class="pane-title">ER 图信息</div>
          <div class="er-side-content">
            <div class="er-kpi-row">
              <span>连接</span>
              <strong>{{ queryTabConnectionNameById(activeErTab.connectionId) || '-' }}</strong>
            </div>
            <div class="er-kpi-row">
              <span>数据库</span>
              <strong>{{ activeErTab.databaseName || '-' }}</strong>
            </div>
            <div class="er-kpi-row">
              <span>已选表</span>
              <strong>{{ activeErTab.selectedTableNames.length }}</strong>
            </div>
            <div class="er-kpi-row">
              <span>FK关系</span>
              <strong>{{ activeErTab.graph?.foreignKeyRelations?.length ?? 0 }}</strong>
            </div>
            <div class="er-kpi-row">
              <span>AI关系</span>
              <strong>{{ activeErAiRelations.length }} / {{ activeErAiRelationTotal }}</strong>
            </div>
            <div
              v-if="activeErTab.graph?.aiInference?.requested && !activeErTab.graph?.aiInference?.success"
              class="er-warning-tip"
            >
              {{ activeErTab.graph?.aiInference?.message || 'AI推断失败，仅显示外键关系' }}
            </div>

            <div class="er-side-block">
              <div class="er-side-block-head">
                <strong>表清单</strong>
                <div class="er-table-comment-toggle">
                  <span>显示注释</span>
                  <a-switch
                    v-model:checked="activeErTab.showCardComments"
                    size="small"
                    @change="touchErTab(activeErTab)"
                  />
                </div>
              </div>
              <div class="er-table-tags">
                <a-tag v-for="tableName in activeErTab.selectedTableNames" :key="tableName" color="blue">
                  {{ tableName }}
                </a-tag>
              </div>
            </div>

            <div class="er-side-block er-side-block-relations">
              <strong>关联关系</strong>
              <div class="er-rel-threshold">
                <span>AI 关系阈值</span>
                <span class="er-rel-threshold-value">{{ formatErRelationConfidence(activeErTab.aiConfidenceThreshold) }}</span>
              </div>
              <a-slider
                v-model:value="activeErTab.aiConfidenceThreshold"
                :min="0"
                :max="1"
                :step="0.01"
                @change="touchErTab(activeErTab)"
              />
              <div class="er-rel-groups">
                <div class="er-rel-group">
                  <div class="er-rel-group-head">
                    <span>外键识别</span>
                    <a-tag color="blue">{{ activeErForeignKeyRelations.length }}</a-tag>
                  </div>
                  <div v-if="activeErForeignKeyRelations.length" class="er-rel-list">
                    <div
                      v-for="(relation, index) in activeErForeignKeyRelations"
                      :key="`fk-${erRelationKey(relation)}-${index}`"
                      class="er-rel-item er-rel-item-fk"
                    >
                      <div class="er-rel-main er-rel-main-structured">
                        <a-tag color="blue" class="er-rel-table-tag">{{ relation.sourceTable }}</a-tag>
                        <span class="er-rel-field-chip er-rel-field-source">{{ relation.sourceColumn }}</span>
                        <span class="er-rel-arrow">{{ erRelationArrow(relation.relationDirection) }}</span>
                        <a-tag color="blue" class="er-rel-table-tag">{{ relation.targetTable }}</a-tag>
                        <span class="er-rel-field-chip er-rel-field-target">{{ relation.targetColumn }}</span>
                      </div>
                      <div class="er-rel-meta">
                        <span>方向：{{ erRelationDirectionLabel(relation.relationDirection) }}</span>
                        <span>来源：外键元数据</span>
                      </div>
                    </div>
                  </div>
                  <div v-else class="er-empty-tip">未识别到外键关系</div>
                </div>

                <div class="er-rel-group">
                  <div class="er-rel-group-head">
                    <span>AI 推断</span>
                    <a-tag color="gold">{{ activeErAiRelations.length }}</a-tag>
                  </div>
                  <div v-if="activeErAiRelations.length" class="er-rel-list">
                    <div
                      v-for="(relation, index) in activeErAiRelations"
                      :key="`ai-${erRelationKey(relation)}-${index}`"
                      class="er-rel-item er-rel-item-ai"
                    >
                      <div class="er-rel-main-row">
                        <div class="er-rel-main er-rel-main-structured">
                          <a-tag color="blue" class="er-rel-table-tag">{{ relation.sourceTable }}</a-tag>
                          <span class="er-rel-field-chip er-rel-field-source">{{ relation.sourceColumn }}</span>
                          <span class="er-rel-arrow">{{ erRelationArrow(relation.relationDirection) }}</span>
                          <a-tag color="blue" class="er-rel-table-tag">{{ relation.targetTable }}</a-tag>
                          <span class="er-rel-field-chip er-rel-field-target">{{ relation.targetColumn }}</span>
                        </div>
                        <a-button
                          size="small"
                          type="text"
                          danger
                          class="er-rel-delete-btn"
                          title="删除该关系"
                          @click.stop="removeErAiRelation(activeErTab, relation)"
                        >
                          <template #icon><delete-outlined /></template>
                        </a-button>
                      </div>
                      <div class="er-rel-meta">
                        <span>方向：{{ erRelationDirectionLabel(relation.relationDirection) }}</span>
                        <span>置信度：{{ formatErRelationConfidence(relation.confidence) }}</span>
                      </div>
                      <div class="er-rel-reason-wrap">
                        <a-popover trigger="hover" placement="leftTop">
                          <template #content>
                            <div class="er-rel-reason-popover">{{ relation.reason || '模型未返回理由' }}</div>
                          </template>
                          <div class="er-rel-reason">
                            {{ erRelationReasonPreview(relation.reason) }}
                          </div>
                        </a-popover>
                      </div>
                    </div>
                  </div>
                  <div v-else class="er-empty-tip">当前阈值下暂无 AI 推断关系</div>
                </div>
              </div>
            </div>
          </div>
        </aside>
      </template>

      <template v-else-if="activeTableEditorTab">
        <div class="query-shared-meta table-editor-shared-meta">
          <div class="query-meta-item">
            <span>连接</span>
            <a-select
              v-model:value="activeTableEditorTab.connectionId"
              size="small"
              style="min-width: 156px"
              :options="connectionSelectOptions"
              :disabled="activeTableEditorTab.mode === 'edit'"
              @change="handleTableEditorConnectionChange(activeTableEditorTab)"
            />
          </div>
          <div class="query-meta-item">
            <span>数据库</span>
            <a-select
              v-model:value="activeTableEditorTab.databaseName"
              size="small"
              style="min-width: 166px"
              :options="databaseOptionsForTableEditorTab(activeTableEditorTab)"
              :disabled="activeTableEditorTab.mode === 'edit'"
              @change="handleTableEditorDatabaseChange(activeTableEditorTab)"
            />
          </div>
        </div>

        <section class="pane pane-center table-editor-structure-pane">
          <div class="pane-title">表结构编辑 · {{ activeTableEditorTab.title }}</div>
          <TableEditor
            :key="activeTableEditorTab.key"
            :tab="activeTableEditorTab"
            @change="handleTableEditorChange(activeTableEditorTab, $event)"
            @save="handleTableEditorSave"
            @close="closeTableEditorTab(activeTableEditorTab.key)"
          />
        </section>

        <div class="pane-splitter pane-splitter-right table-editor-pane-splitter" @mousedown="startResizeQueryPane" />

        <aside class="pane pane-right table-editor-preview-pane">
          <div class="detail-code-head">
            <span>SQL 预览</span>
            <a-space size="small" class="sql-preview-actions">
              <a-button size="small" type="text" class="btn-mini" @click="handleTableEditorRefresh">
                <template #icon><sync-outlined /></template>
                刷新预览
              </a-button>
              <a-button size="small" type="primary" :loading="tableEditorSaving" :disabled="!activeTableEditorTab?.canSave" class="btn-execute" @click="handleTableEditorExecute">
                <template #icon><play-circle-outlined /></template>
                执行
              </a-button>
              <a-button size="small" type="text" class="btn-mini" @click="copyTableEditorSql">
                <template #icon><copy-outlined /></template>
                复制
              </a-button>
            </a-space>
          </div>
          <pre class="detail-code-block"><code v-html="tableEditorSqlHighlighted"></code></pre>
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

          <div ref="queryChatScrollRef" class="query-chat-scroll">
            <div v-if="!activeQueryTab.chatMessages.length" class="query-chat-empty">
              使用自然语言描述需求后发送消息；可使用 Auto 自动识别意图，或关闭 Auto 后手动选择“生成 SQL”“解释 SQL”“分析 SQL”“生成图表”。
            </div>
            <div
              v-for="item in activeQueryTab.chatMessages"
              :key="item.id"
              class="query-chat-message"
              :ref="(el) => bindQueryChatMessageRef(item.id, el)"
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
                  <div v-if="item.content" class="query-chat-text" :class="{ 'is-thinking': item.pending }">
                    <loading-outlined v-if="item.pending" class="query-chat-thinking-icon" />
                    <span>{{ item.content }}</span>
                  </div>
                  <div v-if="item.chartConfig" class="query-chat-chart-summary">
                    {{ item.chartConfigSummary || chartSummaryText(item.chartConfig) }}
                  </div>
                  <div v-if="item.chartImageDataUrl" class="query-chat-chart-image-wrap">
                    <img class="query-chat-chart-image" :src="item.chartImageDataUrl" alt="chart-preview" />
                  </div>
                  <template v-if="item.executionPreview">
                    <div class="query-chat-execution-summary">
                      执行成功 · 耗时 {{ item.executionPreview.executionMs }}ms · 影响行数 {{ item.executionPreview.affectedRows }}
                      <span v-if="item.executionPreview.truncated">（仅展示部分结果）</span>
                    </div>
                    <a-table
                      v-if="item.executionPreview.rows.length"
                      size="small"
                      class="query-chat-execution-table"
                      :pagination="false"
                      :columns="chatExecutionColumns(item.executionPreview)"
                      :data-source="item.executionPreview.rows"
                      row-key="__rowKey"
                      :scroll="{ x: true, y: 180 }"
                    />
                  </template>
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
                      <a-tooltip :title="activeQueryTab.sqlExecuting ? '终止执行' : '执行 SQL'">
                        <a-button
                          size="small"
                          :type="activeQueryTab.sqlExecuting ? 'default' : 'primary'"
                          :danger="activeQueryTab.sqlExecuting"
                          class="sql-action-icon-btn"
                          @click="activeQueryTab.sqlExecuting ? terminateSqlExecutionForTab(activeQueryTab) : executeSqlForTab(activeQueryTab, item.sqlText || '')"
                        >
                          <template #icon>
                            <stop-outlined v-if="activeQueryTab.sqlExecuting" />
                            <play-circle-outlined v-else />
                          </template>
                        </a-button>
                      </a-tooltip>
                      <a-tooltip title="解释 SQL">
                        <a-button size="small" class="sql-action-icon-btn" @click="explainMessageSqlInChat(activeQueryTab, item.sqlText || '')">
                          <template #icon><read-outlined /></template>
                        </a-button>
                      </a-tooltip>
                      <a-tooltip title="分析 SQL">
                        <a-button size="small" class="sql-action-icon-btn" @click="analyzeMessageSqlInChat(activeQueryTab, item.sqlText || '')">
                          <template #icon><experiment-outlined /></template>
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
              @keydown="handleChatComposerKeydown($event, activeQueryTab)"
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
                <a-tooltip title="开启后会记忆并利用更长的对话上下文，适合连续追问与复杂任务。">
                  <span class="query-chat-long-dialog-label">长对话</span>
                </a-tooltip>
                <a-switch v-model:checked="activeQueryTab.memoryEnabled" size="small" />
                <span style="margin-left: 12px; color: var(--ant-color-text-secondary);">≈Token: {{ activeQueryTab.lastTokenEstimate || 0 }}</span>
                <template v-if="activeQueryTab.autoMode">
                  <span class="query-chat-auto-label">自动执行</span>
                  <a-switch v-model:checked="activeQueryTab.autoExecute" size="small" />
                </template>
              </div>
              <div v-if="activeQueryTab.aiGenerating" class="query-chat-composer-actions">
                <a-tooltip title="终止对话执行">
                  <a-button
                    size="small"
                    danger
                    class="sql-action-icon-btn"
                    @click="terminateAiExecutionForTab(activeQueryTab)"
                  >
                    <template #icon><stop-outlined /></template>
                  </a-button>
                </a-tooltip>
              </div>
              <div v-else-if="activeQueryTab.autoMode" class="query-chat-composer-actions">
                <a-tooltip title="Auto 发送">
                  <a-button
                    size="small"
                    type="primary"
                    class="sql-action-icon-btn"
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
                    @click="generateSqlForTab(activeQueryTab, 'generate')"
                  >
                    <template #icon><code-outlined /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="解释 SQL">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn"
                    @click="generateSqlForTab(activeQueryTab, 'explain')"
                  >
                    <template #icon><read-outlined /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="分析 SQL">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn"
                    @click="generateSqlForTab(activeQueryTab, 'analyze')"
                  >
                    <template #icon><experiment-outlined /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="生成图表">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn"
                    @click="generateChartPlanForTab(activeQueryTab)"
                  >
                    <template #icon><area-chart-outlined /></template>
                  </a-button>
                </a-tooltip>
              </div>
            </div>
          </div>
        </section>

        <div v-if="activeQueryTab" class="pane-splitter pane-splitter-right query-pane-splitter" @mousedown="startResizeQueryPane" />

        <aside v-if="activeQueryTab" class="pane pane-right query-editor-pane">
          <div class="pane-title pane-title-with-action">
            <span>SQL 编辑与执行</span>
            <div class="pane-title-actions query-memory-title-actions">
              <span class="query-memory-title-label">记忆理解</span>
              <a-switch v-model:checked="activeQueryTab.memoryEnabled" size="small" />
              <a-tooltip title="开启后，执行成功的 SQL 会被理解记忆，并在后续生成与执行中参与向量召回。">
                <span class="query-memory-title-help">说明</span>
              </a-tooltip>
            </div>
          </div>

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
              <a-tooltip :title="activeQueryTab.sqlExecuting ? '终止执行' : (activeQueryTab.selectedSqlText ? '执行选中的SQL' : '执行 SQL')">
                <a-button
                  size="small"
                  :type="activeQueryTab.sqlExecuting ? 'default' : 'primary'"
                  :danger="activeQueryTab.sqlExecuting"
                  :class="['sql-action-icon-btn', { 'is-selection-active': !!activeQueryTab.selectedSqlText }]"
                  @click="activeQueryTab.sqlExecuting ? terminateSqlExecutionForTab(activeQueryTab) : executeSqlForTab(activeQueryTab)"
                >
                  <template #icon>
                    <stop-outlined v-if="activeQueryTab.sqlExecuting" />
                    <play-circle-outlined v-else />
                  </template>
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
                  历史图表预览为只读，点击对话中的“编辑图表（重跑 SQL）”可恢复可编辑状态。
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
            <a-form-item label="用户">
              <a-input
                v-model:value="connectionForm.username"
                :disabled="connectionForm.dbType === 'SQLITE'"
                placeholder="请输入数据库用户"
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
                <div class="connection-db-selector-tip">不勾选时，连接树显示该连接下全部数据库</div>
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
              <a-form-item label="SSH 用户">
                <a-input v-model:value="connectionForm.sshUser" placeholder="SSH 登录用户" />
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
            <a-row :gutter="12" style="margin-top: 12px;">
              <a-col :span="12">
                <a-form-item label="默认启用对话记忆">
                  <a-switch v-model:checked="aiConfigForm.conversationMemoryEnabled" />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="记忆窗口轮数">
                  <a-input-number v-model:value="aiConfigForm.conversationMemoryWindowSize" :min="4" :max="50" style="width: 100%" />
                </a-form-item>
              </a-col>
            </a-row>
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
                    <a-form-item label="模型">
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
            <div class="rag-config-grid">
              <div class="rag-config-card">
                <div class="rag-config-card-title">向量模型配置</div>
                <a-form-item label="向量模型目录（推荐：填写 clone 的模型仓库目录）">
                  <div class="file-picker-row">
                    <a-input
                      :value="ragConfigForm.ragEmbeddingModelDir"
                      readonly
                      placeholder="/path/to/bge-m3-onnx-o4"
                    />
                    <a-button :loading="pickingRagModelDir" @click="pickRagEmbeddingModelDir">选择目录</a-button>
                  </div>
                </a-form-item>
              </div>
              <div class="rag-config-card">
                <div class="rag-config-card-title">本地 Rerank 配置</div>
                <a-form-item label="Rerank 模型目录">
                  <div class="file-picker-row">
                    <a-input
                      :value="ragConfigForm.ragRerankModelDir"
                      readonly
                      placeholder="/path/to/rerank-model"
                    />
                    <a-button :loading="pickingRagRerankModelDir" @click="pickRagRerankModelDir">选择目录</a-button>
                  </div>
                </a-form-item>
                <a-form-item>
                  <a-switch v-model:checked="ragConfigForm.ragRerankEnabled" />
                  <span style="margin-left: 8px;">启用本地 Rerank</span>
                </a-form-item>
              </div>
            </div>
          </a-tab-pane>
        </a-tabs>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="erTableSelectModalOpen"
      title="选择ER图目标表"
      width="620px"
      ok-text="确认生成"
      cancel-text="取消"
      :confirm-loading="erTableSelectSubmitting"
      @ok="confirmErTableSelection"
    >
      <div class="er-select-modal">
        <div class="er-select-meta">
          <span>连接：{{ queryTabConnectionNameById(erSelectConnectionId) || '-' }}</span>
          <span>数据库：{{ erSelectDatabaseName || '-' }}</span>
          <span>已选：{{ erSelectTableValues.length }} / 30</span>
        </div>
        <div class="er-select-model-row">
          <span class="er-select-model-label">模型</span>
          <a-select
            v-model:value="erSelectModelName"
            size="small"
            style="min-width: 230px; width: 100%"
            :options="aiModelOptions"
            placeholder="请选择模型"
            :disabled="!aiModelOptions.length"
          />
        </div>
        <a-input
          v-model:value="erSelectTableKeyword"
          size="small"
          allow-clear
          placeholder="搜索表名"
        >
          <template #prefix><search-outlined /></template>
        </a-input>
        <div class="er-select-table-list">
          <a-checkbox-group v-model:value="erSelectTableValues" style="width: 100%">
            <a-space direction="vertical" style="width: 100%">
              <a-checkbox
                v-for="tableName in filteredErSelectTableOptions"
                :key="tableName"
                :value="tableName"
                :disabled="erSelectTableValues.length >= 30 && !erSelectTableValues.includes(tableName)"
              >
                {{ tableName }}
              </a-checkbox>
            </a-space>
          </a-checkbox-group>
          <div v-if="!filteredErSelectTableOptions.length" class="er-empty-tip">无可选表</div>
        </div>
      </div>
    </a-modal>

    <a-modal
      v-model:open="erSnapshotSaveModalOpen"
      title="保存 ER 图快照"
      width="480px"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="erSnapshotSaveSubmitting"
      @ok="confirmSaveErSnapshot"
    >
      <a-form layout="vertical">
        <a-form-item label="快照名称">
          <a-input
            v-model:value="erSnapshotSaveName"
            maxlength="80"
            show-count
            placeholder="请输入快照名称"
            @pressEnter="confirmSaveErSnapshot"
          />
        </a-form-item>
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
          :disabled="contextMenu.objectType !== 'tables' || !contextMenu.databaseName"
          @click="triggerContextAction('editTable')"
        >
          编辑表结构
        </button>
        <button
          class="context-menu-item"
          :disabled="contextMenu.objectType !== 'tables' && contextMenu.objectType !== 'views'"
          @click="triggerContextAction('queryData')"
        >
          查询数据
        </button>
        <button
          class="context-menu-item"
          :disabled="contextMenu.objectType !== 'tables' || !contextMenu.databaseName"
          @click="triggerContextAction('vectorizeTable')"
        >
          向量化
        </button>
        <div class="context-menu-divider" />
        <button
          class="context-menu-item danger"
          :disabled="contextMenu.objectType !== 'tables' || !contextMenu.databaseName"
          @click="triggerContextAction('truncateTable')"
        >
          清空表数据
        </button>
        <button
          class="context-menu-item danger"
          :disabled="contextMenu.objectType !== 'tables' || !contextMenu.databaseName"
          @click="triggerContextAction('dropTable')"
        >
          删除表
        </button>
      </template>
    </div>

    <!-- 清空表确认弹窗 -->
    <a-modal
      v-model:open="truncateTableModalOpen"
      title="清空表数据"
      width="420px"
      @ok="confirmTruncateTable"
      @cancel="truncateTableModalOpen = false"
    >
      <div style="padding: 16px 0;">
        <p>确定要清空表 <strong>{{ truncateTableName }}</strong> 的所有数据吗？</p>
        <p style="color: #ff4d4f; font-size: 12px;">此操作将删除表中所有数据，且不可恢复！</p>
      </div>
    </a-modal>

    <!-- 删除表确认弹窗 -->
    <a-modal
      v-model:open="dropTableModalOpen"
      title="删除表"
      width="420px"
      @ok="confirmDropTable"
      @cancel="dropTableModalOpen = false"
    >
      <div style="padding: 16px 0;">
        <p>确定要删除表 <strong>{{ dropTableName }}</strong> 吗？</p>
        <p style="color: #ff4d4f; font-size: 12px;">此操作将永久删除该表及其所有数据，且不可恢复！</p>
      </div>
    </a-modal>

    <a-modal
      v-model:open="vectorizeOverviewModalOpen"
      title="向量化数据概览"
      width="680px"
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
            <div class="vectorize-overview-kpi-card">
              <span>上次全量耗时</span>
              <strong>{{ formatDurationMs(vectorizeOverviewData.lastFullVectorizeDurationMs) }}</strong>
            </div>
            <div class="vectorize-overview-kpi-card">
              <span>上次执行引擎</span>
              <strong>{{ formatVectorizeProvider(vectorizeOverviewData.lastFullVectorizeProvider) }}</strong>
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
            {{ vectorizeOverviewData.message || '仅展示概要统计，不展示具体向量明细' }}
          </div>
        </div>
        <div v-else class="empty-pane">暂无可展示的向量化数据概览</div>
      </a-spin>
    </a-modal>
    </div>
  </a-config-provider>
</template>

<script setup lang="ts">
import {
  ApartmentOutlined,
  AppstoreOutlined,
  AreaChartOutlined,
  ArrowLeftOutlined,
  BulbFilled,
  BulbOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  CodeOutlined,
  CopyOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  ExperimentOutlined,
  EyeOutlined,
  FolderOpenOutlined,
  HddOutlined,
  HistoryOutlined,
  LinkOutlined,
  LoadingOutlined,
  MessageOutlined,
  MinusCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReadOutlined,
  ReloadOutlined,
  SearchOutlined,
  SendOutlined,
  SettingOutlined,
  StopOutlined,
  SyncOutlined,
  TableOutlined,
  ToolOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons-vue';
import {Editor as MonacoEditor} from '@guolao/vue-monaco-editor';
import QueryChartPanel from '../../../components/QueryChartPanel.vue';
import ErDiagramPanel from '../../../components/ErDiagramPanel.vue';
import TableEditor from '../../../components/TableEditor.vue';
import type {StudioController} from '../composables/useStudioController';

const props = defineProps<{ controller: StudioController }>();
const {
  browserTabKey,
    uiThemeStorageKey,
    defaultAlgorithm,
    darkAlgorithm,
    isMacOS,
    isWindows,
    isLinux,
    vectorizeStatusPollTimer,
    vectorizeStatusPollIntervalMs,
    tableStatsMinRequestIntervalMs,
    tableStatsPollIntervalMs,
    connections,
    schemaOverview,
    selectedObjectName,
    createModalOpen,
    isEditMode,
    editingConnectionId,
    connectionRefreshing,
    connectionKeyword,
    tableKeyword,
    selectedTreeKeys,
    expandedTreeKeys,
    tableNameCache,
    tableNameLoadedCache,
    objectNameCache,
    tableStatsCache,
    tableStatsLoadingState,
    tableStatsLastRequestAt,
    databaseListCache,
    activeDatabaseMap,
    databaseVectorizeStatusMap,
    currentObjectType,
    objectViewMode,
    viewportHeight,
    vectorizeOverviewModalOpen,
    vectorizeOverviewLoading,
    vectorizeOverviewData,
    truncateTableModalOpen,
    truncateTableName,
    dropTableModalOpen,
    dropTableName,
    aiConfigModalOpen,
    aiConfigActiveTab,
    uiTheme,
    selectedAiModel,
    activeWorkbenchTab,
    queryTabs,
    erTabs,
    tableEditorTabs,
    erTableSelectModalOpen,
    erTableSelectSubmitting,
    erSelectConnectionId,
    erSelectDatabaseName,
    erSelectTargetTabKey,
    erSelectTableKeyword,
    erSelectTableOptions,
    erSelectTableValues,
    erSelectModelName,
    historyReloading,
    historyLoadingMore,
    historySessionLoadingKey,
    historyKeywordInput,
    historyKeyword,
    historySessionItems,
    historySessionPageNo,
    historySessionPageSize,
    historySessionHasMore,
    historySessionConnectionId,
    erSnapshotReloading,
    erSnapshotLoadingMore,
    erSnapshotLoadingId,
    erSnapshotActionLoadingId,
    erSnapshotKeywordInput,
    erSnapshotKeyword,
    erSnapshotItems,
    erSnapshotPageNo,
    erSnapshotPageSize,
    erSnapshotHasMore,
    erSnapshotConnectionId,
    erSnapshotSaveModalOpen,
    erSnapshotSaveSubmitting,
    erSnapshotSaveName,
    erSnapshotSaveTabKey,
    editingErSnapshotId,
    editingErSnapshotTitle,
    editingHistoryTabKey,
    editingHistoryTitle,
    sessionTitleOverrides,
    tableDetail,
    tableDetailLoading,
    sqlEditorContainerRef,
    queryChatScrollRef,
    queryChatMessageElementMap,
    queryChartPanelRef,
    erDiagramPanelRef,
    sqlSelectionPopover,
    viewportWidth,
    leftPaneWidth,
    leftPaneResizeState,
    browserRightPaneWidth,
    browserPaneResizeState,
    erRightPaneWidth,
    erPaneResizeState,
    queryRightPaneWidth,
    queryPaneResizeState,
    contextMenu,
    connectionForm,
    connectionPreviewDbOptions,
    connectionPreviewLoading,
    connectionPreviewError,
    aiConfigForm,
    ragConfigForm,
    pickingRagModelDir,
    pickingRagRerankModelDir,
    workflow,
    dbTypeOptions,
    envOptions,
    sshAuthTypeOptions,
    sqlEditorOptions,
    sqlKeywords,
    sqlCompletionProviderDisposable,
    sqlEditorTypeDisposable,
    sqlEditorSelectionDisposable,
    sqlEditorScrollDisposable,
    sqlEditorMouseDownDisposable,
    sqlEditorMouseUpDisposable,
    sqlAutoSuggestTimer,
    activeSqlEditorInstance,
    pendingTableNameLoads,
    tableStatsPollingTimers,
    sessionTitleOverridesStorageKey,
    sqlExecutionAbortControllerMap,
    sqlExecutionAbortReasonMap,
    aiRequestAbortControllerMap,
    aiRequestAbortReasonMap,
    selectedConnection,
    activeQueryTab,
    activeErTab,
    activeTableEditorTab,
    activeErConfidenceThreshold,
    activeErAiRelationTotal,
    activeErDisplayGraph,
    activeErForeignKeyRelations,
    activeErAiRelations,
    canOpenHistory,
    canOpenErSnapshot,
    isDarkTheme,
    monacoTheme,
    antdThemeConfig,
    currentHistoryConnectionId,
    currentErSnapshotConnectionId,
    sessionHistoryTabs,
    filteredErSelectTableOptions,
    isContextDatabaseVectorizing,
    canViewContextVectorizedData,
    canInterruptContextVectorize,
    canOpenBrowserErFeature,
    browserErEntryTooltip,
    canCreateTable,
    connectionSelectOptions,
    isMultiDatabaseFormType,
    connectionPreviewSelectOptions,
    canPreviewDatabases,
    connectionTreeData,
    objectRows,
    selectedObjectRecord,
    selectedTreeDetail,
    selectedTreeConnection,
    selectedTreeDatabaseStatusLabel,
    selectedTreeDatabaseTableCount,
    selectedTreeDatabaseColumnCount,
    createTableSqlText,
    createTableSqlHighlighted,
    tableEditorSqlHighlighted,
    filteredObjectRows,
    objectColumns,
    tableScrollY,
    queryResultScrollY,
    aiModelOptions,
    workbenchStyle,
    activeResultRows,
    activeResultColumns,
    queryResultScrollX,
    chartTypeOptions,
    chartSortDirectionOptions,
    erLayoutModeOptions,
    erLineTypeOptions,
    activeChartRows,
    activeChartFieldOptions,
    activeNumericFieldOptions,
    emptyManualChartConfig,
    cloneChartConfig,
    isNumericField,
    setupManualChartConfigByResult,
    buildConnectionNode,
    buildCategoryChildren,
    getCategoryChildren,
    requiresDatabaseLayer,
    isMultiDatabaseType,
    normalizeSelectedDatabases,
    visibleDatabasesForConnection,
    parseConfiguredDatabaseName,
    sanitizeDatabaseName,
    tableCacheKey,
    objectCacheKey,
    vectorizeStatusCacheKey,
    getDatabaseVectorizeStatus,
    getDatabaseVectorizeStatusRecord,
    canUseErFeature,
    resolveErUnavailableReason,
    isDatabaseVectorizing,
    databaseStatusLabel,
    databaseStatusClass,
    databaseStatusIcon,
    getActiveDatabaseName,
    activateBrowserTab,
    openCreateModal,
    openEditModal,
    openAiQueryTab,
    closeQueryTab,
    touchErTab,
    normalizeErRelationDirection,
    normalizeErRelationType,
    erRelationKey,
    erRelationArrow,
    erRelationDirectionLabel,
    formatErRelationConfidence,
    normalizeErRelationConfidence,
    erRelationReasonPreview,
    handleErRelationRouteChange,
    removeErAiRelation,
    closeErTab,
    closeTableEditorTab,
    openNewTableEditor,
    openEditTableEditor,
    handleTableEditorChange,
    handleTableEditorSave,
    tableEditorSaving,
    handleTableEditorRefresh,
    confirmTruncateTable,
    confirmDropTable,
    refreshSchemaMetadata,
    handleTableEditorExecute,
    hasWorkbenchTab,
    ensureActiveWorkbenchTab,
    openErTableSelectModal,
    refreshErGraphForTab,
    confirmErTableSelection,
    erSnapshotItemKey,
    isErSnapshotItemActive,
    findErSnapshotSummaryById,
    updateErSnapshotTabsTitle,
    startErSnapshotTitleEdit,
    cancelErSnapshotTitleEdit,
    commitErSnapshotTitleEdit,
    removeErSnapshot,
    buildErSnapshotTabTitle,
    findErTabBySnapshotId,
    loadErSnapshotPage,
    applyErSnapshotKeywordSearch,
    handleErSnapshotMenuScroll,
    handleErSnapshotMenuClick,
    openErSnapshot,
    openErSnapshotSaveModal,
    confirmSaveErSnapshot,
    handleHistoryMenuClick,
    toggleTheme,
    loadUiThemePreference,
    persistUiThemePreference,
    sessionRefKey,
    sessionTitleOverrideKey,
    historyItemKey,
    findQueryTabBySession,
    queryTabConnectionNameById,
    normalizeTitleSource,
    buildSessionDefaultTitle,
    firstPromptForTitle,
    buildNewQueryPlaceholderTitle,
    applySessionTitle,
    historyItemDisplayTitle,
    isHistoryItemActive,
    loadSessionTitleOverrides,
    persistSessionTitleOverrides,
    startHistoryTitleEdit,
    removeHistorySession,
    commitHistoryTitleEdit,
    cancelHistoryTitleEdit,
    normalizeHistoryAssistantPayload,
    buildHistoryChatMessages,
    buildHistoryTabFromRows,
    loadHistorySessionPage,
    applyHistoryKeywordSearch,
    handleHistoryMenuScroll,
    openHistorySession,
    modelLabelById,
    lastPromptText,
    assistantActionLabel,
    normalizeHistoryActionType,
    userBubbleClass,
    touchQueryTab,
    bindQueryChatMessageRef,
    scrollToQueryChatMessage,
    appendUserChatMessage,
    appendAssistantThinkingMessage,
    removeQueryChatMessage,
    prepareAssistantMessage,
    appendAssistantSqlMessage,
    appendAssistantTextMessage,
    appendSqlToEditor,
    appendSelectedSqlToPrompt,
    explainSelectedSqlInChat,
    analyzeSelectedSqlInChat,
    explainMessageSqlInChat,
    analyzeMessageSqlInChat,
    runAiTextActionWithSelectedSql,
    runAiTextActionWithSql,
    prepareConnectionTreeData,
    loadDatabaseListForConnection,
    refreshVectorizeStatusForConnection,
    refreshAllVectorizeStatuses,
    pruneVectorizeStatusMap,
    startVectorizeStatusPolling,
    stopVectorizeStatusPolling,
    loadConnections,
    refreshConnections,
    saveConnection,
    previewConnectionDatabases,
    testConnection,
    removeConnection,
    syncSchema,
    loadOverview,
    clearTableStatsPollingTimer,
    clearAllTableStatsPollingTimers,
    applyTableStatsSnapshot,
    isDatabaseNodeExpanded,
    collectExpandedDatabaseTargets,
    fetchTableStatsForDatabase,
    scheduleTableStatsForExpandedDatabases,
    loadTableNamesByConnection,
    resolveQueryDatabaseName,
    ensureTableNamesLoaded,
    tableNameSuggestions,
    sqlKeywordSuggestions,
    hasKeywordSuggestion,
    hasTableSuggestion,
    shouldAutoTriggerSuggest,
    registerSqlCompletionProvider,
    registerSqlAutoSuggest,
    readSelectedSql,
    hideSqlSelectionPopover,
    updateSqlSelectionPopoverPosition,
    syncSelectedSqlForActiveTab,
    registerSqlSelectionTracker,
    registerSqlSelectionPopoverTrigger,
    registerSqlScrollTracker,
    warmupTableSuggestions,
    handleSqlEditorMount,
    loadObjectNames,
    refreshCurrentObjects,
    loadCategoryObjects,
    loadTreeChildrenByKey,
    handleTreeSelect,
    handleTreeExpand,
    handleTreeRightClick,
    closeContextMenu,
    triggerContextAction,
    openVectorizeOverview,
    enqueueDatabaseRevectorize,
    vectorizeSingleTable,
    interruptDatabaseVectorize,
    onObjectRow,
    selectObject,
    loadObjectDetail,
    clearObjectDetail,
    startResizeLeftPane,
    handleResizeLeftPane,
    stopResizeLeftPane,
    startResizeBrowserPane,
    handleResizeBrowserPane,
    stopResizeBrowserPane,
    startResizeErPane,
    handleResizeErPane,
    stopResizeErPane,
    startResizeQueryPane,
    handleResizeQueryPane,
    stopResizeQueryPane,
    openQueryTabByObject,
    getDesktopBridge,
    pickRagEmbeddingModelDir,
    pickRagRerankModelDir,
    openAiConfigModal,
    saveAiConfig,
    databaseOptionsForTab,
    databaseOptionsForTableEditorTab,
    queryTabConnectionName,
    handleQueryConnectionChange,
    handleQueryDatabaseChange,
    handleTableEditorConnectionChange,
    handleTableEditorDatabaseChange,
    resolveSqlForAction,
    resolveSelectedSqlSnippet,
    saveConversationHistory,
    buildStructuredContextForTab,
    saveConversationHistoryOnce,
    timeoutRetryErrorMessage,
    isTimeoutErrorMessage,
    isAbortError,
    getErrorMessage,
    clearUserRetryState,
    markUserMessageRetryable,
    mergePromptWithSqlSnippet,
    aiRequestTimeoutMs,
    AI_REQUEST_ABORTED,
    isAiRequestAbortedMessage,
    terminateAiExecutionForTab,
    terminateSqlExecutionForTab,
    postAiApiWithTimeout,
    looksLikeSqlText,
    generateSqlForTab,
    autoActionTypeByIntent,
    sendAutoForTab,
    retryUserMessage,
    buildChartPrompt,
    chartTypeLabel,
    chartSummaryText,
    isChartConfigRenderable,
    buildExecutionPreview,
    chatExecutionColumns,
    chartExportPixelRatioCandidates,
    erDiagramExportPixelRatioCandidates,
    exportChartPngDataUrl,
    exportErDiagramPngDataUrl,
    normalizeDownloadFileNamePart,
    isChartCacheRetryableError,
    normalizeChartCacheErrorMessage,
    isLikelyLocalFilePath,
    saveChartImageCache,
    loadChartImageDataUrl,
    cacheChartImageWithRetry,
    downloadImage,
    generateChartPlanForTab,
    generateChartFromMessage,
    generateManualChartForTab,
    downloadActiveChart,
    downloadMessageChart,
    downloadActiveErDiagram,
    hydrateHistoryChartImages,
    editChartFromHistory,
    explainSqlForTab,
    RISK_EXECUTION_CANCELLED,
    SQL_EXECUTION_ABORTED,
    connectionEnvLabel,
    ensureRiskConfirmedBeforeExecute,
    executeSqlForTab,
    repairSqlForTab,
    exportCsvForTab,
    riskColor,
    normalizeRiskLevel,
    ensureConnection,
    runSafely,
    formatSize,
    formatCompactCount,
    formatTime,
    formatDurationMs,
    formatVectorizeProvider,
    handleChatComposerKeydown,
    handleWindowResize,
    expandConnectionNode,
    buildDatabaseNodeKey,
    buildCategoryNodeKey,
    buildObjectNodeKey,
    expandCategoryNode,
    toObjectType,
    objectTypeLabel,
    normalizeEnv,
    envTagText,
    envTagClass,
    envTagIcon,
    nodeIconComponent,
    quoteSqlIdentifier,
    buildColumnSqlDefinition,
    buildCreateTableSql,
    escapeHtml,
    highlightSqlForDisplay,
    copyTextContent,
    copyCreateTableSql,
    copyTableEditorSql,
    dbIconUrl,
    normalizeModelOptions,
    nextModelOptionId,
    addOpenAiModelOption,
    addCliModelOption,
    removeModelOption,
    defaultConnectionForm,
    resetConnectionForm,
    fillConnectionForm,
    defaultAiConfigForm,
    fillAiConfigForm,
    defaultRagConfigForm,
    fillRagConfigForm,
    resetConnectionModalState
} = props.controller;
</script>
