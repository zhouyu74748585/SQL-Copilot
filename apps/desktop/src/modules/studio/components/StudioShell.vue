<template>
  <a-config-provider :theme="antdThemeConfig" :locale="antLocale">
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
            data-testid="studio-browser-tab"
            @click="activateBrowserTab"
          >
            <span>对象浏览</span>
          </button>
          <button
            v-for="tab in knowledgeTabs"
            :key="tab.key"
            class="workspace-tab"
            :class="{ 'is-active': activeWorkbenchTab === tab.key }"
            @click="activeWorkbenchTab = tab.key"
          >
            <span>{{ tab.title }}</span>
            <close-outlined class="tab-close" @click.stop="closeKnowledgeTab(tab.key)" />
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
          <button
            v-for="tab in tableDataTabs"
            :key="tab.key"
            class="workspace-tab"
            :class="{ 'is-active': activeWorkbenchTab === tab.key }"
            @click="activeWorkbenchTab = tab.key"
          >
            <span>{{ tab.title }}</span>
            <close-outlined class="tab-close" @click.stop="closeTableDataTab(tab.key)" />
          </button>
          <button
            v-for="tab in objectDefinitionEditorTabs"
            :key="tab.key"
            class="workspace-tab"
            :class="{ 'is-active': activeWorkbenchTab === tab.key }"
            @click="activeWorkbenchTab = tab.key"
          >
            <span>{{ tab.title }}</span>
            <close-outlined class="tab-close" @click.stop="closeObjectDefinitionEditorTab(tab.key)" />
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
          <button class="tool-item top-action-btn" :disabled="!canOpenHistory" title="会话历史" data-testid="studio-top-history-button" @click="handleHistoryMenuClick">
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
                <a-button size="small" class="history-search-btn" @click="applyHistoryKeywordSearch">{{ tt('搜索') }}</a-button>
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
          <button class="tool-item top-action-btn" :disabled="!canOpenErSnapshot" title="ER图快照" data-testid="studio-top-er-snapshot-button" @click="handleErSnapshotMenuClick">
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
                <a-button size="small" class="history-search-btn" @click="applyErSnapshotKeywordSearch">{{ tt('搜索') }}</a-button>
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
        <button class="tool-item top-action-btn" data-testid="studio-top-settings-button" @click="openAiConfigModal" title="设置">
          <setting-outlined />
          <span>设置</span>
        </button>
        <a-tooltip :title="isDarkTheme ? '切换到浅色' : '切换到深色'">
          <button class="tool-item tool-theme-toggle top-action-btn top-action-icon-btn" data-testid="studio-top-theme-toggle" @click="toggleTheme">
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
        'workbench-table-data': !!activeTableDataTab,
        'workbench-object-definition': !!activeObjectDefinitionEditorTab,
        'is-table-data-detail-collapsed': !!activeTableDataTab?.detailCollapsed,
        'workbench-er': !!activeErTab,
        'is-er-detail-collapsed': !!activeErTab?.detailCollapsed,
        'workbench-browser': activeWorkbenchTab === browserTabKey,
        'is-browser-detail-collapsed': activeWorkbenchTab === browserTabKey && browserDetailCollapsed,
        'workbench-knowledge': !!activeKnowledgeTab,
      }"
      :style="workbenchStyle"
    >
      <aside class="pane pane-left">
        <a-collapse class="left-nav-collapse" :default-active-key="['connections', 'knowledge']" :bordered="false">
          <a-collapse-panel key="connections" header="我的连接">
            <template #extra>
              <div class="pane-title-actions left-nav-panel-actions" @click.stop>
                <a-tooltip title="新建连接">
                  <a-button size="small" type="text" class="toolbar-icon-btn" @click.stop="openCreateModal">
                    <template #icon>
                      <img class="toolbar-action-icon" :src="createConnectionIcon" alt="" />
                    </template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="新建分组">
                  <a-button size="small" type="text" class="toolbar-icon-btn" @click.stop="openCreateGroupModal">
                    <template #icon>
                      <img class="toolbar-action-icon" :src="createGroupIcon" alt="" />
                    </template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="刷新连接列表">
                  <a-button size="small" type="text" class="toolbar-icon-btn" :loading="connectionRefreshing" @click.stop="refreshConnections">
                    <template #icon><img class="toolbar-action-icon" :src="refreshIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
              </div>
            </template>
            <div class="pane-search">
              <a-input v-model:value="connectionKeyword" size="small" placeholder="搜索连接" allow-clear>
                <template #prefix>
                  <search-outlined />
                </template>
              </a-input>
            </div>

            <a-tree
              class="connection-tree"
              data-testid="studio-connection-tree"
              :tree-data="connectionTreeData"
              :selected-keys="selectedTreeKeys"
              :expanded-keys="expandedTreeKeys"
              block-node
              draggable
              @expand="handleTreeExpand"
              @drop="handleConnectionTreeDrop"
              @select="handleTreeSelect"
              @rightClick="handleTreeRightClick"
            >
              <template #title="{ title, dataRef }">
                <div
                  class="tree-title-row"
                  :class="{
                    'is-connection-expanded': dataRef.nodeType === 'connection' && expandedTreeKeys.includes(`conn-${dataRef.connectionId}`),
                  }"
                  :data-testid="treeNodeTestId(dataRef, title)"
                  @dblclick.stop="handleTreeNodeDblclick(dataRef)"
                >
                  <img
                    v-if="treeTitleIconSrc(dataRef, isTreeNodeExpanded(dataRef))"
                    class="tree-icon-img"
                    :src="treeTitleIconSrc(dataRef, isTreeNodeExpanded(dataRef))"
                    alt=""
                  />
                  <component
                    v-else
                    :is="dataRef.nodeType === 'connection' ? DatabaseOutlined : nodeIconComponent(dataRef)"
                    class="tree-icon-font"
                  />
                  <div class="tree-title-main">
                    <span class="tree-title-text" :class="{ 'tree-title-placeholder': dataRef.nodeType === 'group-empty' }">
                      {{ dataRef.nodeType === 'connection' ? (dataRef.connectionName || title) : title }}
                    </span>
                  </div>
                  <span
                    v-if="dataRef.nodeType === 'connection'"
                    class="tree-env-tag"
                    :class="envTagClass(dataRef.env)"
                  >
                    <component :is="envTagIcon(dataRef.env)" class="tree-env-tag-icon" />
                    {{ envTagText(dataRef.env) }}
                  </span>
                  <span
                    v-if="dataRef.nodeType === 'connection'"
                    class="tree-connection-status"
                    :class="connectionStatusClass(dataRef.connectionId)"
                    :title="connectionStatusText(dataRef.connectionId)"
                  />
                  <span
                    v-if="dataRef.nodeType === 'database' && !isKvConnectionId(dataRef.connectionId)"
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
          </a-collapse-panel>
          <a-collapse-panel key="knowledge" header="知识中心">
            <button
              class="knowledge-nav-item"
              :class="{ 'is-active': activeKnowledgeTab?.node === 'example-sql' }"
              data-testid="studio-knowledge-example-sql"
              @click="openKnowledgeNode('example-sql')"
            >
              <span>样例SQL</span>
              <span>{{ knowledgeGlobalExampleCount }}</span>
            </button>
            <button
              class="knowledge-nav-item"
              :class="{ 'is-active': activeKnowledgeTab?.node === 'terms' }"
              data-testid="studio-knowledge-terms"
              @click="openKnowledgeNode('terms')"
            >
              <span>术语管理</span>
              <span>{{ knowledgeGlobalTermCount }}</span>
            </button>
          </a-collapse-panel>
        </a-collapse>
      </aside>

      <div class="pane-splitter pane-splitter-left" @mousedown="startResizeLeftPane" />

      <template v-if="activeWorkbenchTab === browserTabKey">
          <section class="pane pane-center browser-center-pane">
            <div class="center-toolbar">
              <div v-if="currentObjectType === 'tables' && !activeConnectionIsKv" class="center-toolbar-left">
                <a-tooltip title="新建表">
                  <a-button
                    size="small"
                    type="default"
                    :disabled="!canCreateTable"
                    class="toolbar-icon-btn"
                    @click="openNewTableEditor()"
                  >
                    <template #icon><img class="toolbar-action-icon" :src="createTableIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="新建查询">
                  <a-button size="small" :disabled="!workflow.connectionId" class="toolbar-icon-btn" data-testid="studio-browser-toolbar-new-query" @click="openAiQueryTab()">
                    <template #icon><img class="toolbar-action-icon" :src="addQueryIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip :title="browserErEntryTooltip">
                  <a-button size="small" :disabled="!canOpenBrowserErFeature" class="toolbar-icon-btn" data-testid="studio-browser-toolbar-open-er" @click="openErTableSelectModal()">
                    <template #icon><img class="toolbar-action-icon" :src="erEntryIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
              </div>
              <div v-else-if="currentObjectType === 'tables' && activeConnectionIsKv" class="center-toolbar-left">
                <a-tooltip title="新建查询">
                  <a-button size="small" type="default" :disabled="!workflow.connectionId" class="toolbar-icon-btn" @click="openAiQueryTab()">
                    <template #icon><img class="toolbar-action-icon" :src="addQueryIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip v-if="activeConnectionIsRedis" title="新增键">
                  <a-button size="small" class="toolbar-icon-btn" @click="openCreateRedisKeyModal">
                    <template #icon><img class="toolbar-action-icon" :src="createRedisKeyIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
              </div>
              <div v-else-if="currentObjectType === 'views'" class="center-toolbar-left">
                <a-tooltip title="新建视图">
                  <a-button
                    size="small"
                    type="default"
                    :disabled="!canCreateView"
                    class="toolbar-icon-btn"
                    @click="openNewObjectDefinitionEditor(workflow.connectionId, getActiveDatabaseName(workflow.connectionId), 'views')"
                  >
                    <template #icon><img class="toolbar-action-icon" :src="createViewIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="新建查询">
                  <a-button size="small" :disabled="!workflow.connectionId" class="toolbar-icon-btn" @click="openAiQueryTab()">
                    <template #icon><img class="toolbar-action-icon" :src="addQueryIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
              </div>
              <div v-else-if="currentObjectType === 'functions'" class="center-toolbar-left">
                <a-tooltip title="新建函数">
                  <a-button
                    size="small"
                    type="default"
                    :disabled="!canCreateFunction"
                    class="toolbar-icon-btn"
                    @click="openNewObjectDefinitionEditor(workflow.connectionId, getActiveDatabaseName(workflow.connectionId), 'functions')"
                  >
                    <template #icon><img class="toolbar-action-icon" :src="createFunctionIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip title="新建查询">
                  <a-button size="small" :disabled="!workflow.connectionId" class="toolbar-icon-btn" @click="openAiQueryTab()">
                    <template #icon><img class="toolbar-action-icon" :src="addQueryIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
              </div>
              <div v-else-if="currentObjectType === 'queries'" class="center-toolbar-left">
                <a-tooltip title="新建查询">
                  <a-button size="small" type="default" :disabled="!workflow.connectionId" class="toolbar-icon-btn" @click="openAiQueryTab()">
                    <template #icon><img class="toolbar-action-icon" :src="addQueryIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
              </div>
              <div class="center-toolbar-right">
                <a-input
                  v-model:value="tableKeyword"
                  size="small"
                  :placeholder="tt(currentObjectType === 'queries' ? '搜索保存查询' : `搜索${objectTypeLabel(currentObjectType)}`)"
                  allow-clear
                >
                  <template #prefix><search-outlined /></template>
                </a-input>
                <a-button size="small" @click="refreshCurrentPageObjects({ force: true })" title="刷新当前对象">
                  <reload-outlined />
                </a-button>
                <a-radio-group
                  v-if="!(activeConnectionIsRedis && currentObjectType === 'tables')"
                  v-model:value="objectViewMode"
                  size="small"
                >
                  <a-radio-button value="row"><unordered-list-outlined /></a-radio-button>
                  <a-radio-button value="grid"><appstore-outlined /></a-radio-button>
                </a-radio-group>
                 <a-tooltip :title="browserDetailCollapsed ? '展开对象详情' : '收起对象详情'">
                  <a-button size="small" type="text" class="table-data-icon-btn" @click.stop="toggleBrowserDetailCollapsed">
                    <template #icon>
                      <menu-fold-outlined v-if="!browserDetailCollapsed" />
                      <menu-unfold-outlined v-else />
                    </template>
                  </a-button>
                </a-tooltip>
              </div>
            </div>

            <div class="object-browser-content">
              <div v-if="activeConnectionIsRedis && currentObjectType === 'tables'" class="redis-browser-layout">
                <section class="redis-list-pane redis-table-pane">
                  <a-table
                    class="object-list-table redis-browser-table"
                    size="small"
                    :loading="redisBrowserLoading"
                    :pagination="false"
                    :columns="objectColumns"
                    :data-source="redisBrowserRows"
                    row-key="nodeKey"
                    :scroll="{ y: tableScrollY }"
                    :custom-row="onObjectRow"
                    :expanded-row-keys="redisExpandedRowKeys"
                    :children-column-name="'children'"
                    :row-expandable="redisRowExpandable"
                    :show-expand-column="false"
                    :expand-icon-column-index="0"
                    @expand="handleRedisBrowserExpand"
                  >
                    <template #bodyCell="{ column, record }">
                      <template v-if="column.key === 'nodeName'">
                        <div class="table-name-cell" :class="{ 'is-active': redisRowIsActive(record), 'is-queryable': record.redisNodeType === 'KEY' || record.redisNodeType === 'LOAD_MORE', 'is-path-row': record.redisNodeType === 'PATH' }" :style="redisRowIndentStyle(record)" @dblclick.stop="onObjectRow(record).onDblclick()">
                          <img class="object-row-icon" :src="objectRowIconSrc(record)" alt="" />
                          <span>{{ record.nodeName || record.objectName }}</span>
                        </div>
                      </template>
                      <template v-else-if="column.key === 'redisNodeType'">
                        <span>{{ redisNodeTypeLabel(record) }}</span>
                      </template>
                      <template v-else-if="column.key === 'ttlSeconds'">
                        <span>{{ redisTtlLabel(record) }}</span>
                      </template>
                      <template v-else-if="column.key === 'description'">
                        <span class="object-desc-ellipsis">{{ record.description || '-' }}</span>
                      </template>
                    </template>
                  </a-table>
                </section>
              </div>

              <template v-else>
                <a-table
                  v-if="objectViewMode === 'row'"
                  class="object-list-table"
                  size="small"
                  :pagination="false"
                  :columns="objectColumns"
                  :data-source="currentObjectRows"
                  row-key="objectName"
                  :scroll="{ y: tableScrollY }"
                  :custom-row="onObjectRow"
                >
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'objectName'">
                      <div class="table-name-cell" :class="{ 'is-active': selectedObjectName === record.objectName, 'is-queryable': record.objectType === 'tables' || record.objectType === 'queries' }" :data-testid="objectRowTestId(record)" @dblclick.stop="onObjectRow(record).onDblclick()">
                        <img class="object-row-icon" :src="objectRowIconSrc(record)" alt="" />
                        <span>{{ record.objectName }}</span>
                      </div>
                    </template>
                    <template v-else-if="column.key === 'description'">
                      <span class="object-desc-ellipsis">{{ record.description || '-' }}</span>
                    </template>
                    <template v-else-if="column.key === 'vectorizeStatus'">
                      <a-tooltip :title="record.vectorizeMessage ? `${databaseStatusLabel(record.vectorizeStatus)} | ${record.vectorizeMessage}` : databaseStatusLabel(record.vectorizeStatus)">
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
                    v-for="item in currentObjectRows"
                    :key="item.objectName"
                    class="object-card"
                    :class="{ 'is-active': selectedObjectName === item.objectName }"
                    :data-testid="objectRowTestId(item)"
                    @click="onObjectRow(item).onClick()"
                    @dblclick="onObjectRow(item).onDblclick()"
                    @contextmenu.prevent.stop="onObjectRow(item).onContextmenu($event)"
                  >
                    <div class="object-card-title">
                      <img class="object-card-icon" :src="objectRowIconSrc(item)" alt="" />
                      <span>{{ item.objectName }}</span>
                    </div>
                    <div class="object-card-meta">{{ currentObjectType === 'queries' ? '保存查询' : objectTypeLabel(item.objectType) }}</div>
                    <div v-if="currentObjectType !== 'queries' && !activeConnectionIsKv" class="object-card-vectorize" :class="databaseStatusClass(item.vectorizeStatus)">
                      <component :is="databaseStatusIcon(item.vectorizeStatus)" class="object-vectorize-icon" />
                      <span>{{ databaseStatusLabel(item.vectorizeStatus) }}</span>
                    </div>
                    <div class="object-card-desc">{{ item.description || '-' }}</div>
                  </div>
                </div>
              </template>
            </div>

            <div class="center-status">
              <span>对象: {{ currentObjectRows.length }}</span>
              <span>类型: {{ currentObjectType === 'queries' ? '保存查询' : objectTypeLabel(currentObjectType) }}</span>
              <span v-if="activeConnectionIsRedis && currentObjectType === 'tables'">搜索: {{ tableKeyword.trim() || '无' }}</span>
            </div>
          </section>

          <div
            v-if="!browserDetailCollapsed"
            class="pane-splitter pane-splitter-right"
            @mousedown="startResizeBrowserPane"
          />

          <aside v-if="!browserDetailCollapsed" class="pane pane-right detail-pane browser-detail-pane">
            <div class="pane-title">详情</div>
            <div v-if="!selectedObjectRecord && !selectedTreeDetail" class="empty-pane">请从对象浏览中选择连接、数据库或对象</div>
            <div v-else-if="selectedObjectRecord" class="detail-wrapper">
              <div class="detail-summary">
                <div class="detail-row"><span>对象</span><strong>{{ selectedObjectRecord.objectName }}</strong></div>
                <div class="detail-row"><span>类型</span><strong>{{ currentObjectType === 'queries' ? '保存查询' : objectTypeLabel(selectedObjectRecord.objectType) }}</strong></div>
                <div class="detail-row detail-row-description"><span>说明</span><strong>{{ selectedObjectRecord.description || '-' }}</strong></div>
                <div class="detail-row"><span>连接</span><strong>{{ selectedConnection?.name ?? '-' }}</strong></div>
                <div class="detail-row"><span>数据库</span><strong>{{ getActiveDatabaseName(workflow.connectionId) || '-' }}</strong></div>
              </div>

              <div v-if="selectedObjectRecord.objectType === 'tables' && !activeConnectionIsKv" class="detail-table-panel">
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
              <div v-else-if="selectedObjectRecord.objectType === 'tables' && activeConnectionIsKv" class="detail-table-panel">
                <a-spin :spinning="kvObjectDetailLoading">
                  <div class="detail-summary">
                    <div class="detail-row"><span>完整键名</span><strong>{{ kvObjectDetail?.objectName || selectedObjectRecord.objectName }}</strong></div>
                    <div class="detail-row"><span>值类型</span><strong>{{ kvObjectDetail?.valueType || selectedObjectRecord.tableSize || '-' }}</strong></div>
                    <div class="detail-row"><span>TTL</span><strong>{{ kvObjectDetail?.ttlSeconds != null && Number(kvObjectDetail.ttlSeconds) >= 0 ? `${kvObjectDetail.ttlSeconds}s` : '永久' }}</strong></div>
                  </div>
                  <div class="detail-code-head">
                    <span>键操作</span>
                    <div class="redis-detail-actions">
                      <a-button size="small" @click="openEditRedisKeyModal" :disabled="kvObjectDetailLoading">
                        <template #icon><edit-outlined /></template>
                        编辑键
                      </a-button>
                      <a-button size="small" danger @click="deleteRedisKey(selectedObjectRecord.objectName)">
                        <template #icon><delete-outlined /></template>
                        删除键
                      </a-button>
                    </div>
                  </div>
                  <div class="detail-code-head">
                    <span>推荐查询模板</span>
                    <a-button size="small" type="text" :disabled="kvObjectDetailLoading" @click="copyTextContent(kvObjectDetail?.queryTemplate || '', '查询模板已复制')">
                      <template #icon><copy-outlined /></template>
                      复制
                    </a-button>
                  </div>
                  <pre class="detail-code-block"><code>{{ kvObjectDetail?.queryTemplate || '-- 暂无查询模板' }}</code></pre>
                  <div class="detail-code-head detail-code-head-secondary">
                    <span>值详情</span>
                  </div>
                  <pre class="detail-code-block redis-detail-value"><code>{{ redisDetailValueText }}</code></pre>
                  <div v-if="kvObjectDetail?.facts?.length" class="detail-note">
                    {{ kvObjectDetail.facts.join(' | ') }}
                  </div>
                </a-spin>
              </div>

              <div v-else-if="selectedObjectRecord.objectType === 'views' || selectedObjectRecord.objectType === 'functions'" class="detail-table-panel">
                <a-spin :spinning="objectDefinitionDetailLoading">
                  <div class="detail-code-head">
                    <span>{{ selectedObjectRecord.objectType === 'views' ? '视图定义 SQL' : '函数定义 SQL' }}</span>
                    <a-button size="small" type="text" :disabled="objectDefinitionDetailLoading" @click="copyTextContent(objectDefinitionSqlText, 'SQL 已复制')">
                      <template #icon><copy-outlined /></template>
                      复制
                    </a-button>
                  </div>
                  <pre class="detail-code-block"><code v-html="objectDefinitionSqlHighlighted"></code></pre>
                </a-spin>
              </div>

              <div v-else-if="selectedObjectRecord.objectType === 'queries'" class="detail-table-panel">
                <div class="detail-code-head">
                  <span>保存的 SQL</span>
                </div>
                <pre class="detail-code-block"><code>{{ selectedObjectRecord.sqlText || '' }}</code></pre>
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
            <div v-else-if="selectedTreeDetail?.kind === 'group'" class="detail-wrapper">
              <div class="detail-summary">
                <div class="detail-row"><span>分组</span><strong>{{ selectedTreeGroup?.name ?? '-' }}</strong></div>
                <div class="detail-row"><span>连接数量</span><strong>{{ selectedTreeGroup?.connectionCount ?? 0 }}</strong></div>
                <div class="detail-row"><span>默认分组</span><strong>{{ selectedTreeGroup?.defaultGroup ? '是' : '否' }}</strong></div>
              </div>
              <div class="detail-code-head">
                <span>分组操作</span>
                <div class="redis-detail-actions">
                  <a-button size="small" type="default"  @click="openCreateModal">
                    <template #icon><link-outlined /></template>
                    新建连接
                  </a-button>
                  <a-button size="small" @click="openRenameGroupModal(selectedTreeGroup?.id || 0)" :disabled="!selectedTreeGroup">
                    <template #icon><edit-outlined /></template>
                    重命名分组
                  </a-button>
                </div>
              </div>
            </div>
            <div v-else-if="selectedTreeDetail?.kind === 'database' || selectedTreeDetail?.kind === 'category'" class="detail-wrapper">
              <div class="detail-summary">
                <div class="detail-row"><span>数据库</span><strong>{{ selectedTreeDetail.databaseName || '-' }}</strong></div>
                <div class="detail-row"><span>连接</span><strong>{{ selectedTreeConnection?.name ?? '-' }}</strong></div>
                <div class="detail-row"><span>数据库类型</span><strong>{{ selectedTreeConnection?.dbType ?? '-' }}</strong></div>
                <div class="detail-row"><span>所属环境</span><strong>{{ envTagText(selectedTreeConnection?.env) }}</strong></div>
                <div v-if="selectedTreeConnection?.id ? !isKvConnectionId(selectedTreeConnection.id) : false" class="detail-row"><span>向量化</span><strong>{{ selectedTreeDatabaseStatusLabel }}</strong></div>
                <div class="detail-row"><span>表数量</span><strong>{{ selectedTreeDatabaseTableCount }}</strong></div>
                <div class="detail-row"><span>字段数</span><strong>{{ selectedTreeDatabaseColumnCount }}</strong></div>
              </div>
            </div>
            <div v-else class="empty-pane">对象详情加载中...</div>
          </aside>
      </template>

      <template v-else-if="activeKnowledgeTab">
        <section class="pane pane-center">
          <div class="center-toolbar">
            <div class="center-toolbar-left">
              <a-tooltip :title="knowledgeActiveNode === 'terms' ? '新建术语' : '新建样例'">
                <a-button size="small"  type="default" class="toolbar-icon-btn" @click="knowledgeActiveNode === 'terms' ? resetKnowledgeTermForm() : resetKnowledgeExampleForm()">
                  <template #icon><img class="toolbar-action-icon" :src="createGroupIcon" alt="" /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip title="手动重建向量">
                <a-button size="small" class="toolbar-icon-btn knowledge-vector-btn" :loading="knowledgeRebuildLoading" @click="rebuildKnowledgeVectors">
                  <template #icon><img class="toolbar-action-icon knowledge-vector-icon" :src="vectorIcon" alt="" /></template>
                </a-button>
              </a-tooltip>
            </div>
            <div class="center-toolbar-right knowledge-toolbar-right">
              <a-select
                :value="knowledgeFilterConnectionId || undefined"
                size="small"
                class="knowledge-toolbar-select"
                allow-clear
                placeholder="筛选连接"
                :options="knowledgeConnectionOptions"
                @change="handleKnowledgeFilterConnectionSelectorChange"
              />
              <a-select
                :value="knowledgeFilterDatabaseName || undefined"
                size="small"
                class="knowledge-toolbar-select"
                allow-clear
                placeholder="筛选数据库"
                :disabled="!knowledgeFilterConnectionId"
                :options="knowledgeFilterDatabaseOptions"
                @change="handleKnowledgeFilterDatabaseSelectorChange"
              />
              <a-input v-model:value="knowledgeKeyword" size="small" placeholder="搜索知识内容" allow-clear>
                <template #prefix><search-outlined /></template>
              </a-input>
              <a-button size="small" :loading="knowledgeLoading" @click="loadKnowledgeData">
                <reload-outlined />
              </a-button>
            </div>
          </div>

          <a-spin :spinning="knowledgeLoading">
            <div v-if="knowledgeActiveNode === 'terms'" class="knowledge-list">
              <button v-for="item in filteredKnowledgeTermItems" :key="item.id" class="knowledge-card" @click="selectKnowledgeTerm(item)">
                <div class="knowledge-card-head">
                  <strong>{{ item.term }}</strong>
                  <a-tag :color="knowledgeScopeColor(item.scope)">{{ knowledgeScopeLabel(item.scope) }}</a-tag>
                </div>
                <div class="knowledge-card-desc">{{ item.description || '暂无说明' }}</div>
                <div class="knowledge-card-meta">{{ formatTime(item.updatedAt) }}</div>
              </button>
              <div v-if="!filteredKnowledgeTermItems.length" class="empty-pane">暂无术语数据</div>
            </div>
            <div v-else class="knowledge-list">
              <button v-for="item in filteredKnowledgeExampleItems" :key="item.id" class="knowledge-card" @click="selectKnowledgeExample(item)">
                <div class="knowledge-card-head">
                  <strong>{{ item.description || item.sqlText.slice(0, 24) || '未命名样例' }}</strong>
                  <a-tag :color="knowledgeScopeColor(item.scope)">{{ knowledgeScopeLabel(item.scope) }}</a-tag>
                </div>
                <div class="knowledge-card-desc">{{ item.sqlText }}</div>
                <div class="knowledge-card-meta">{{ tt('关联术语') }} {{ item.termIds?.length || 0 }} · {{ formatTime(item.updatedAt) }}</div>
              </button>
              <div v-if="!filteredKnowledgeExampleItems.length" class="empty-pane">暂无样例 SQL 数据</div>
            </div>
          </a-spin>
        </section>

        <div class="pane-splitter pane-splitter-right" @mousedown="startResizeBrowserPane" />

        <aside class="pane pane-right detail-pane">
          <div class="pane-title">{{ knowledgeActiveNode === 'terms' ? '术语详情' : '样例详情' }}</div>
          <div class="detail-wrapper">
            <div v-if="knowledgeActiveNode === 'terms'">
              <div class="detail-summary">
                <div class="detail-row"><span>术语</span><strong>{{ knowledgeTermForm.term || '未命名术语' }}</strong></div>
                <div class="detail-row"><span>作用域</span><strong>{{ knowledgeScopeLabel(knowledgeTermForm.scope) }}</strong></div>
                <div class="detail-row"><span>连接</span><strong>{{ queryTabConnectionNameById(knowledgeTermForm.connectionId || 0) || '-' }}</strong></div>
                <div class="detail-row"><span>数据库</span><strong>{{ knowledgeTermForm.databaseName || '-' }}</strong></div>
                <div class="detail-row detail-row-description"><span>说明</span><strong>{{ knowledgeTermForm.description || '-' }}</strong></div>
              </div>
              <div class="detail-form-panel knowledge-form">
                <a-form layout="vertical" size="small">
                  <a-form-item label="作用域">
                    <a-select v-model:value="knowledgeTermForm.scope" :options="knowledgeScopeOptions" @change="handleKnowledgeTermScopeSelectorChange" />
                  </a-form-item>
                  <a-form-item v-if="knowledgeTermForm.scope !== 'GLOBAL'" label="目标连接">
                    <a-select
                      :value="knowledgeTermForm.connectionId"
                      size="small"
                      show-search
                      placeholder="目标连接"
                      :options="knowledgeConnectionOptions"
                      @change="handleKnowledgeTermTargetConnectionSelectorChange"
                    />
                  </a-form-item>
                  <a-form-item v-if="knowledgeTermForm.scope === 'DATABASE'" label="目标数据库">
                    <a-select
                      :value="knowledgeTermForm.databaseName || undefined"
                      size="small"
                      show-search
                      placeholder="目标数据库"
                      :disabled="!knowledgeTermForm.connectionId"
                      :options="knowledgeTermTargetDatabaseOptions"
                      @change="handleKnowledgeTermTargetDatabaseSelectorChange"
                    />
                  </a-form-item>
                  <a-form-item label="术语">
                    <a-input v-model:value="knowledgeTermForm.term" maxlength="120" />
                  </a-form-item>
                  <a-form-item label="说明">
                    <a-textarea v-model:value="knowledgeTermForm.description" :rows="5" />
                  </a-form-item>
                </a-form>
                <a-space class="detail-form-actions">
                  <a-button type="primary" size="small" :loading="knowledgeSaving" @click="saveKnowledgeTerm">{{ tt('保存') }}</a-button>
                  <a-button size="small" @click="resetKnowledgeTermForm">{{ tt('重置') }}</a-button>
                  <a-button v-if="knowledgeTermForm.id" danger size="small" :loading="knowledgeSaving" @click="removeKnowledgeTerm">{{ tt('删除') }}</a-button>
                </a-space>
              </div>
            </div>

            <div v-else>
              <div class="detail-summary">
                <div class="detail-row"><span>样例</span><strong>{{ knowledgeExampleForm.description || '未命名样例' }}</strong></div>
                <div class="detail-row"><span>作用域</span><strong>{{ knowledgeScopeLabel(knowledgeExampleForm.scope) }}</strong></div>
                <div class="detail-row"><span>连接</span><strong>{{ queryTabConnectionNameById(knowledgeExampleForm.connectionId || 0) || '-' }}</strong></div>
                <div class="detail-row"><span>数据库</span><strong>{{ knowledgeExampleForm.databaseName || '-' }}</strong></div>
                <div class="detail-row"><span>关联术语</span><strong>{{ knowledgeExampleForm.termIds.length }}</strong></div>
                <div class="detail-row detail-row-description"><span>说明</span><strong>{{ knowledgeExampleForm.description || '-' }}</strong></div>
              </div>
              <div class="detail-form-panel knowledge-form">
                <a-form layout="vertical" size="small">
                  <a-form-item label="作用域">
                    <a-select v-model:value="knowledgeExampleForm.scope" :options="knowledgeScopeOptions" @change="handleKnowledgeExampleScopeSelectorChange" />
                  </a-form-item>
                  <a-form-item v-if="knowledgeExampleForm.scope !== 'GLOBAL'" label="目标连接">
                    <a-select
                      :value="knowledgeExampleForm.connectionId"
                      size="small"
                      show-search
                      placeholder="目标连接"
                      :options="knowledgeConnectionOptions"
                      @change="handleKnowledgeExampleTargetConnectionSelectorChange"
                    />
                  </a-form-item>
                  <a-form-item v-if="knowledgeExampleForm.scope === 'DATABASE'" label="目标数据库">
                    <a-select
                      :value="knowledgeExampleForm.databaseName || undefined"
                      size="small"
                      show-search
                      placeholder="目标数据库"
                      :disabled="!knowledgeExampleForm.connectionId"
                      :options="knowledgeExampleTargetDatabaseOptions"
                      @change="handleKnowledgeExampleTargetDatabaseSelectorChange"
                    />
                  </a-form-item>
                  <a-form-item label="关联术语">
                    <a-select
                      v-model:value="knowledgeExampleForm.termIds"
                      mode="multiple"
                      :options="knowledgeVisibleExampleTermOptions"
                    />
                  </a-form-item>
                  <a-form-item label="说明">
                    <a-textarea v-model:value="knowledgeExampleForm.description" :rows="3" />
                  </a-form-item>
                  <a-form-item label="SQL 正文">
                    <div class="knowledge-editor-group">
                      <MonacoEditor
                        v-model:value="knowledgeExampleForm.sqlText"
                        language="sql"
                        width="100%"
                        height="240px"
                        :theme="monacoTheme"
                        :options="sqlEditorOptions"
                        class="sql-editor knowledge-sql-editor"
                        @mount="handleKnowledgeExampleSqlEditorMount"
                      >
                        <template #default>编辑器加载中...</template>
                        <template #failure>编辑器加载失败，请刷新页面重试</template>
                      </MonacoEditor>
                    </div>
                  </a-form-item>
                </a-form>
                <a-space class="detail-form-actions">
                  <a-button type="primary" size="small" :loading="knowledgeSaving" @click="saveKnowledgeExample">{{ tt('保存') }}</a-button>
                  <a-button size="small" @click="resetKnowledgeExampleForm">{{ tt('重置') }}</a-button>
                  <a-button v-if="knowledgeExampleForm.id" danger size="small" :loading="knowledgeSaving" @click="removeKnowledgeExample">{{ tt('删除') }}</a-button>
                </a-space>
              </div>
            </div>
          </div>
        </aside>
      </template>

      <template v-else-if="activeErTab">
        <section class="pane pane-center er-diagram-pane">
          <div class="er-toolbar">
            <a-space size="small">
              <a-tooltip :title="tt('重选')">
                <a-button size="small" class="sql-action-icon-btn" @click="openErTableSelectModal(activeErTab)">
                  <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="repickIcon" alt="" /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip :title="tt('刷新')">
                <a-button
                  size="small"
                  class="sql-action-icon-btn"
                  :loading="activeErTab.loading"
                  @click="refreshErGraphForTab(activeErTab, true)"
                >
                  <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="refreshIcon" alt="" /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip :title="tt('保存')">
                <a-button size="small" class="sql-action-icon-btn" @click="openErSnapshotSaveModal(activeErTab)">
                  <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="saveQueryIcon" alt="" /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip :title="tt('导出')">
                <a-button size="small" class="sql-action-icon-btn" @click="downloadActiveErDiagram(activeErTab)">
                  <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="exportIcon" alt="" /></template>
                </a-button>
              </a-tooltip>
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
                :value="activeErTab.layoutMode"
                size="small"
                style="width: 132px"
                :options="erLayoutModeOptions"
                @change="handleErLayoutModeChange(activeErTab, $event)"
              />
              <span class="er-toolbar-label">线型</span>
              <a-select
                v-model:value="activeErTab.lineType"
                size="small"
                style="width: 104px"
                :options="erLineTypeOptions"
                @change="touchErTab(activeErTab)"
              />
              <a-tooltip :title="activeErTab.detailCollapsed ? '展开 ER 图信息' : '收起 ER 图信息'">
                <a-button size="small" type="text" class="table-data-icon-btn" @click.stop="toggleErDetailCollapsed(activeErTab)">
                  <template #icon>
                    <menu-fold-outlined v-if="!activeErTab.detailCollapsed" />
                    <menu-unfold-outlined v-else />
                  </template>
                </a-button>
              </a-tooltip>
            </a-space>
          </div>
          <div class="er-canvas-wrap">
            <a-spin :spinning="activeErTab.loading">
              <ErDiagramPanel
                ref="erDiagramPanelRef"
                :graph="activeErDisplayGraph"
                :selected-relation-key="activeErTab.selectedRelationKey"
                :layout-mode="activeErTab.layoutMode"
                :line-type="activeErTab.lineType"
                :show-comments="activeErTab.showCardComments"
                :dark="isDarkTheme"
                @graph-layout-change="handleErGraphLayoutChange(activeErTab, $event)"
                @relation-route-change="handleErRelationRouteChange(activeErTab, $event)"
                @relation-select="setErSelectedRelation(activeErTab, $event)"
                @relation-delete-request="removeErRelation(activeErTab, $event)"
                @manual-relation-create="appendErManualRelation(activeErTab, $event)"
              />
            </a-spin>
          </div>
        </section>
        <div
          v-if="!activeErTab.detailCollapsed"
          class="pane-splitter pane-splitter-right er-pane-splitter"
          @mousedown="startResizeErPane"
        />

        <aside v-if="!activeErTab.detailCollapsed" class="pane pane-right er-side-pane">
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
            <div class="er-kpi-row">
              <span>手工关系</span>
              <strong>{{ activeErManualRelations.length }}</strong>
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
                    <span>AI 推断</span>
                    <a-tag color="gold">{{ activeErAiRelations.length }}</a-tag>
                  </div>
                  <div v-if="activeErAiRelations.length" class="er-rel-list">
                    <div
                      v-for="(relation, index) in activeErAiRelations"
                      :key="`ai-${erRelationKey(relation)}-${index}`"
                      class="er-rel-item er-rel-item-ai"
                      :class="{ 'is-selected': activeErTab.selectedRelationKey === erRelationKey(relation) }"
                      @click="setErSelectedRelation(activeErTab, erRelationKey(relation))"
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
                          @click.stop="removeErRelation(activeErTab, relation)"
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
                      :class="{ 'is-selected': activeErTab.selectedRelationKey === erRelationKey(relation) }"
                      @click="setErSelectedRelation(activeErTab, erRelationKey(relation))"
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
                          @click.stop="removeErRelation(activeErTab, relation)"
                        >
                          <template #icon><delete-outlined /></template>
                        </a-button>
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
                    <span>手工连线</span>
                    <a-tag color="geekblue">{{ activeErManualRelations.length }}</a-tag>
                  </div>
                  <div v-if="activeErManualRelations.length" class="er-rel-list">
                    <div
                      v-for="(relation, index) in activeErManualRelations"
                      :key="`manual-${erRelationKey(relation)}-${index}`"
                      class="er-rel-item er-rel-item-manual"
                      :class="{ 'is-selected': activeErTab.selectedRelationKey === erRelationKey(relation) }"
                      @click="setErSelectedRelation(activeErTab, erRelationKey(relation))"
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
                          @click.stop="removeErRelation(activeErTab, relation)"
                        >
                          <template #icon><delete-outlined /></template>
                        </a-button>
                      </div>
                      <div class="er-rel-meta">
                        <span>方向：{{ erRelationDirectionLabel(relation.relationDirection) }}</span>
                        <span>来源：手工连线</span>
                      </div>
                    </div>
                  </div>
                  <div v-else class="er-empty-tip">暂无手工连线</div>
                </div>
              </div>
            </div>
          </div>
        </aside>
      </template>

      <template v-else-if="activeTableEditorTab">
        <StudioConnectionContextBar
          class="table-editor-shared-meta"
          :connection-id="activeTableEditorTab.connectionId"
          :database-name="activeTableEditorTab.databaseName"
          :connection-options="connectionSelectOptions"
          :database-options="databaseOptionsForTableEditorTab(activeTableEditorTab)"
          :connection-disabled="activeTableEditorTab.mode === 'edit'"
          :database-disabled="activeTableEditorTab.mode === 'edit'"
          @connection-change="handleTableEditorConnectionSelectorChange(activeTableEditorTab, $event)"
          @database-change="handleTableEditorDatabaseSelectorChange(activeTableEditorTab, $event)"
        />

        <section class="pane pane-center table-editor-structure-pane">
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

      <template v-else-if="activeTableDataTab">
        <StudioConnectionContextBar
          class="table-data-shared-meta"
          :connection-id="activeTableDataTab.connectionId"
          :database-name="activeTableDataTab.databaseName"
          :connection-options="connectionSelectOptions"
          :database-options="databaseOptionsForTableDataTab(activeTableDataTab)"
          @connection-change="handleTableDataConnectionSelectorChange(activeTableDataTab, Number($event))"
          @database-change="handleTableDataDatabaseSelectorChange(activeTableDataTab, String($event))"
        />

        <section class="pane pane-center table-data-center-pane">
          <div class="pane-title pane-title-with-action">
            <div class="table-data-title-main">
              <span>{{ tt(`数据 · ${activeTableDataTab.tableName}`) }}</span>
              <a-tooltip :title="activeTableDataTab.filterPanelVisible ? '收起筛选与排序' : '展开筛选与排序'">
                <a-button size="small" type="text" class="table-data-icon-btn" @click.stop="toggleTableDataFilterPanel(activeTableDataTab)">
                  <template #icon><filter-outlined /></template>
                </a-button>
              </a-tooltip>
            </div>
            <div class="pane-title-actions">
              <a-tooltip :title="activeTableDataTab.detailCollapsed ? '展开数据详情' : '收起数据详情'">
                <a-button size="small" type="text" class="table-data-icon-btn" @click.stop="toggleTableDataDetailCollapsed(activeTableDataTab)">
                  <template #icon>
                    <menu-fold-outlined v-if="!activeTableDataTab.detailCollapsed" />
                    <menu-unfold-outlined v-else />
                  </template>
                </a-button>
              </a-tooltip>
            </div>
          </div>

          <div v-show="activeTableDataTab.filterPanelVisible" class="table-data-filter-panel">
            <div class="table-data-filter-block">
              <div class="table-data-filter-head">
                <span>{{ tt('筛选') }}</span>
                <a-button size="small" type="text" class="table-data-rule-add-btn" @click="addTableDataFilter(activeTableDataTab)">
                  <template #icon><plus-outlined /></template>
                </a-button>
              </div>
              <div
                v-for="filter in activeTableDataTab.filters"
                :key="filter.key"
                class="table-data-filter-item"
              >
                <a-select
                  v-model:value="filter.columnName"
                  size="small"
                  style="width: 150px"
                  :options="activeTableDataTab.columns.map((item) => ({ label: item.columnName, value: item.columnName }))"
                />
                <a-select
                  v-model:value="filter.operator"
                  size="small"
                  style="width: 130px"
                  :options="tableDataFilterOperatorOptions"
                />
                <a-input
                  v-model:value="filter.value"
                  size="small"
                  style="width: 200px"
                  :disabled="filter.operator === 'IS_NULL' || filter.operator === 'IS_NOT_NULL'"
                  :placeholder="tt('过滤值')"
                />
                <a-button size="small" type="text" danger class="table-data-icon-btn" @click="removeTableDataFilter(activeTableDataTab, filter.key)">
                  <template #icon><delete-outlined /></template>
                </a-button>
              </div>
            </div>

            <div class="table-data-filter-block">
              <div class="table-data-filter-head">
                <span>{{ tt('排序方式') }}</span>
                <a-button size="small" type="text" class="table-data-rule-add-btn" @click="addTableDataSort(activeTableDataTab)">
                  <template #icon><plus-outlined /></template>
                </a-button>
              </div>
              <div
                v-for="sort in activeTableDataTab.sorts"
                :key="sort.key"
                class="table-data-filter-item"
              >
                <a-select
                  v-model:value="sort.columnName"
                  size="small"
                  style="width: 150px"
                  :options="activeTableDataTab.columns.map((item) => ({ label: item.columnName, value: item.columnName }))"
                />
                <a-select
                  v-model:value="sort.direction"
                  size="small"
                  style="width: 130px"
                  :options="tableDataSortDirectionOptions"
                />
                <a-button size="small" type="text" danger class="table-data-icon-btn" @click="removeTableDataSort(activeTableDataTab, sort.key)">
                  <template #icon><delete-outlined /></template>
                </a-button>
              </div>
            </div>

            <div class="table-data-filter-actions">
              <a-button size="small" type="primary" :disabled="activeTableDataTab.loading" @click="applyTableDataFilters(activeTableDataTab)">
                {{ tt('应用筛选 & 排序') }}
              </a-button>
            </div>
          </div>

          <div v-if="activeTableDataTab.readOnlyReason" class="table-data-readonly-tip">
            {{ activeTableDataTab.readOnlyReason }}
          </div>
          <div v-if="activeTableDataTab.errorMessage" class="table-data-error-tip">
            {{ activeTableDataTab.errorMessage }}
          </div>

          <div class="table-data-grid-wrap">
            <a-spin :spinning="activeTableDataTab.loading && !activeTableDataTab.rows.length">
              <TableDataVirtualGrid
                :tab="activeTableDataTab!"
                :columns="tableDataDisplayColumns(activeTableDataTab!)"
                :rows="tableDataDisplayRows(activeTableDataTab!)"
                :scroll-x="tableDataScrollX(activeTableDataTab!)"
                :scroll-y="queryResultScrollY"
                :reset-key="`${activeTableDataTab!.key}:${activeTableDataTab!.connectionId}:${activeTableDataTab!.databaseName}:${activeTableDataTab!.tableName}:${activeTableDataTab!.pageNo}:${activeTableDataTab!.pageSize}`"
                :is-primary-key-column="(columnName: string) => isTableDataPrimaryKeyColumn(activeTableDataTab!, columnName)"
                :column-editor-type="(columnName: string) => tableDataColumnEditorType(activeTableDataTab!, columnName)"
                @select-row="(rowKey: string) => selectTableDataRow(activeTableDataTab!, rowKey)"
                @start-edit="(rowKey: string, columnName: string) => startTableDataCellEdit(activeTableDataTab!, rowKey, columnName)"
                @stop-edit="() => stopTableDataCellEdit(activeTableDataTab!)"
                @update-cell="(rowKey: string, columnName: string, value: string | null) => updateTableDataCell(activeTableDataTab!, rowKey, columnName, value)"
              />
            </a-spin>
          </div>

          <div class="table-data-bottom-bar">
            <div class="table-data-bottom-left">
              <a-space size="small">
                <a-button size="small" type="text" class="table-data-icon-btn" :disabled="!activeTableDataTab.editable" @click="addTableDataRow(activeTableDataTab)">
                  <template #icon><plus-outlined /></template>
                </a-button>
                <a-button size="small" type="text" class="table-data-icon-btn" danger :disabled="!activeTableDataTab.editable || !activeTableDataTab.selectedRowKey" @click="deleteSelectedTableDataRow(activeTableDataTab)">
                  <template #icon><minus-outlined /></template>
                </a-button>
                <a-button
                  size="small"
                  type="text"
                  class="table-data-icon-btn"
                  :loading="activeTableDataTab.submitting"
                  :disabled="!activeTableDataTab.editable || !activeTableDataTab.dirty"
                  @click="submitTableDataChanges(activeTableDataTab)"
                >
                  <template #icon><check-outlined /></template>
                </a-button>
                <a-button
                  size="small"
                  type="text"
                  class="table-data-icon-btn"
                  :disabled="!activeTableDataTab.dirty"
                  @click="discardTableDataChanges(activeTableDataTab)"
                >
                  <template #icon><close-outlined /></template>
                </a-button>
                <a-button size="small" type="text" class="table-data-icon-btn" :disabled="activeTableDataTab.loading" @click="reloadTableDataForTab(activeTableDataTab)">
                  <template #icon><reload-outlined /></template>
                </a-button>
              </a-space>
            </div>
            <div class="table-data-bottom-right">
              <a-space size="small">
                <a-button size="small" type="text" class="table-data-icon-btn" :disabled="activeTableDataTab.pageNo <= 1" @click="prevTableDataPage(activeTableDataTab)">
                  <template #icon><arrow-left-outlined /></template>
                </a-button>
                <span class="table-data-page-label">第 {{ activeTableDataTab.pageNo }} 页</span>
                <a-button size="small" type="text" class="table-data-icon-btn" :disabled="!activeTableDataTab.hasNextPage" @click="nextTableDataPage(activeTableDataTab)">
                  <template #icon><arrow-right-outlined /></template>
                </a-button>
                <span class="table-data-page-label">每页</span>
                <a-input-number
                  size="small"
                  :min="1"
                  :step="100"
                  :value="activeTableDataTab.pageSize"
                  @change="(value: number | null) => value && updateTableDataPageSize(activeTableDataTab!, value)"
                />
              </a-space>
            </div>
          </div>
        </section>

        <div
          v-if="!activeTableDataTab.detailCollapsed"
          class="pane-splitter pane-splitter-right table-data-pane-splitter"
          @mousedown="startResizeQueryPane"
        />

        <aside v-if="!activeTableDataTab.detailCollapsed" class="pane pane-right table-data-detail-pane">
          <div class="pane-title">数据详情</div>
          <div v-if="!selectedTableDataRow(activeTableDataTab)" class="empty-pane">请选择一行数据查看详情</div>
          <div v-else class="table-data-detail-form">
            <div
              v-for="column in activeTableDataTab.columns"
              :key="column.columnName"
              class="table-data-detail-item"
            >
              <label class="table-data-detail-label">
                <span class="table-data-detail-label-name">{{ column.columnName }}</span>
                <a-tooltip v-if="column.columnComment" :title="column.columnComment">
                  <span class="table-data-detail-label-comment">（{{ column.columnComment }}）</span>
                </a-tooltip>
              </label>
              <a-date-picker
                v-if="tableDataColumnEditorType(activeTableDataTab!, column.columnName) === 'date'"
                size="small"
                style="width: 100%"
                value-format="YYYY-MM-DD"
                :value="selectedTableDataRow(activeTableDataTab!)?.values[column.columnName] || undefined"
                :disabled="!activeTableDataTab!.editable || isTableDataPrimaryKeyColumn(activeTableDataTab!, column.columnName)"
                @update:value="(value: string | null) => selectedTableDataRow(activeTableDataTab!) && updateTableDataCell(activeTableDataTab!, selectedTableDataRow(activeTableDataTab!)?.rowKey || '', column.columnName, value ? String(value) : null)"
              />
              <a-date-picker
                v-else-if="tableDataColumnEditorType(activeTableDataTab!, column.columnName) === 'datetime'"
                size="small"
                style="width: 100%"
                show-time
                format="YYYY-MM-DD HH:mm:ss"
                value-format="YYYY-MM-DD HH:mm:ss"
                :value="selectedTableDataRow(activeTableDataTab!)?.values[column.columnName] || undefined"
                :disabled="!activeTableDataTab!.editable || isTableDataPrimaryKeyColumn(activeTableDataTab!, column.columnName)"
                @update:value="(value: string | null) => selectedTableDataRow(activeTableDataTab!) && updateTableDataCell(activeTableDataTab!, selectedTableDataRow(activeTableDataTab!)?.rowKey || '', column.columnName, value ? String(value) : null)"
              />
              <a-time-picker
                v-else-if="tableDataColumnEditorType(activeTableDataTab!, column.columnName) === 'time'"
                size="small"
                style="width: 100%"
                format="HH:mm:ss"
                value-format="HH:mm:ss"
                :value="selectedTableDataRow(activeTableDataTab!)?.values[column.columnName] || undefined"
                :disabled="!activeTableDataTab!.editable || isTableDataPrimaryKeyColumn(activeTableDataTab!, column.columnName)"
                @update:value="(value: string | null) => selectedTableDataRow(activeTableDataTab!) && updateTableDataCell(activeTableDataTab!, selectedTableDataRow(activeTableDataTab!)?.rowKey || '', column.columnName, value ? String(value) : null)"
              />
              <a-input
                v-else
                size="small"
                :value="selectedTableDataRow(activeTableDataTab!)?.values[column.columnName] ?? ''"
                :disabled="!activeTableDataTab!.editable || isTableDataPrimaryKeyColumn(activeTableDataTab!, column.columnName)"
                @update:value="(value: any) => selectedTableDataRow(activeTableDataTab!) && updateTableDataCell(activeTableDataTab!, selectedTableDataRow(activeTableDataTab!)?.rowKey || '', column.columnName, value === '' ? null : String(value))"
              />
            </div>
          </div>
        </aside>
      </template>

      <template v-else-if="activeObjectDefinitionEditorTab">
        <StudioConnectionContextBar
          class="table-editor-shared-meta"
          :connection-id="activeObjectDefinitionEditorTab.connectionId"
          :database-name="activeObjectDefinitionEditorTab.databaseName"
          :connection-options="connectionSelectOptions"
          :database-options="[{ label: activeObjectDefinitionEditorTab.databaseName, value: activeObjectDefinitionEditorTab.databaseName }]"
          :connection-disabled="true"
          :database-disabled="true"
        />

        <section class="pane pane-center object-definition-pane">
          <div class="pane-title pane-title-with-action">
            <div class="table-data-title-main">
              <span>定义编辑 · {{ activeObjectDefinitionEditorTab.title }}</span>
            </div>
            <div class="pane-title-actions">
              <a-space size="small">
                <a-tooltip placement="bottom" :title="tt('刷新')">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn object-definition-action-btn"
                    :aria-label="tt('刷新')"
                    :disabled="activeObjectDefinitionEditorTab.loading || activeObjectDefinitionEditorTab.saving"
                    @click="reloadObjectDefinition(activeObjectDefinitionEditorTab)"
                  >
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="refreshIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip placement="bottom" :title="tt('保存')">
                  <a-button
                    size="small"
                    :class="['sql-action-icon-btn', 'object-definition-action-btn', { 'is-dirty': activeObjectDefinitionEditorTab.dirty }]"
                    :aria-label="tt('保存')"
                    :loading="activeObjectDefinitionEditorTab.saving"
                    :disabled="activeObjectDefinitionEditorTab.loading || !activeObjectDefinitionEditorTab.dirty"
                    @click="saveObjectDefinition(activeObjectDefinitionEditorTab)"
                  >
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="saveQueryIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip placement="bottom" :title="tt('美化 SQL')">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn object-definition-action-btn"
                    :aria-label="tt('美化 SQL')"
                    :disabled="activeObjectDefinitionEditorTab.loading || activeObjectDefinitionEditorTab.saving"
                    @click="formatObjectDefinitionSql(activeObjectDefinitionEditorTab)"
                  >
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="prettyIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
              </a-space>
            </div>
          </div>
          <div v-if="activeObjectDefinitionEditorTab.errorMessage" class="table-data-error-tip">
            {{ activeObjectDefinitionEditorTab.errorMessage }}
          </div>
          <div class="editor-group object-definition-editor-group">
            <MonacoEditor
              :value="activeObjectDefinitionEditorTab.sqlText"
              language="sql"
              width="100%"
              height="100%"
              :theme="monacoTheme"
              :options="sqlEditorOptions"
              class="sql-editor"
              @update:value="handleObjectDefinitionSqlChange(activeObjectDefinitionEditorTab, String($event ?? ''))"
              @mount="handleObjectDefinitionSqlEditorMount"
            >
              <template #default>编辑器加载中...</template>
              <template #failure>编辑器加载失败，请刷新页面重试</template>
            </MonacoEditor>
          </div>
        </section>
      </template>

      <template v-else>
        <StudioConnectionContextBar
          v-if="activeQueryTab"
          :connection-id="activeQueryTab.connectionId"
          :database-name="activeQueryTab.databaseName"
          :connection-options="connectionSelectOptions"
          :database-options="databaseOptionsForTab(activeQueryTab)"
          @connection-change="handleQueryConnectionSelectorChange(activeQueryTab, $event)"
          @database-change="handleQueryDatabaseSelectorChange(activeQueryTab, $event)"
        />

        <section v-if="activeQueryTab" class="pane pane-center query-chat-pane">
          <div class="pane-title pane-title-with-action query-chat-title-bar">
            <span>{{ activeQueryTab.title }} · 对话</span>
            <div class="pane-title-actions">
              <a-tooltip placement="bottomRight" overlay-class-name="query-context-usage-tooltip">
                <template #title>
                  <div class="query-context-usage-tooltip-copy">
                    <strong>窗口上下文占比</strong>
                    <span>当前占用 {{ formatCompactCount(activeQueryContextUsage.usedTokens) }} tokens</span>
                    <span>总窗口 {{ formatCompactCount(activeQueryContextUsage.totalTokens) }} tokens</span>
                    <span>当前占比 {{ Math.max(0, activeQueryContextUsage.percent) }}%</span>
                    <span v-if="!activeQueryContextUsage.enabled">对话记忆已关闭，当前为估算参考值</span>
                  </div>
                </template>
                <span
                  class="query-context-usage-ring is-compact"
                  :class="[`is-${activeQueryContextUsage.tone}`, { 'is-disabled': !activeQueryContextUsage.enabled }]"
                  :style="{ '--context-usage-ratio': `${activeQueryContextUsage.cappedRatio}` }"
                >
                  <span class="query-context-usage-ring-core"></span>
                </span>
              </a-tooltip>
            </div>
          </div>
          <div class="pane-title">{{ activeQueryTab.title }} · 对话</div>

          <div ref="queryChatScrollRef" class="query-chat-scroll">
            <div v-if="!activeQueryTab.chatMessages.length" class="query-chat-empty">
              使用自然语言描述需求后发送消息；可使用 Auto 自动识别意图，或关闭 Auto 后手动选择“{{ generateActionLabelByDbType(queryTabDbType(activeQueryTab)) }}”“{{ explainActionLabelByDbType(queryTabDbType(activeQueryTab)) }}”“{{ analyzeActionLabelByDbType(queryTabDbType(activeQueryTab)) }}”{{ canGenerateChartForTab(activeQueryTab) ? '“生成图表”' : '' }}。
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
                    <span>
                      {{ formatTime(item.createdAt) }}
                      <span v-if="item.streaming"> · 流式中</span>
                      <span v-else-if="item.aborted"> · 已中止</span>
                    </span>
                  </div>
                  <div v-if="item.thinkingContent" class="query-chat-thinking-panel">
                    <div class="query-chat-thinking-title">Thinking</div>
                    <pre class="query-chat-thinking-content">{{ item.thinkingContent }}</pre>
                  </div>
                  <div
                    v-if="item.trace && detailOutputEnabledForTab(activeQueryTab)"
                    class="query-chat-trace-block"
                  >
                    <button class="query-chat-trace-toggle" @click="toggleMessageTraceExpanded(activeQueryTab, item.id)">
                      <span>过程详情</span>
                      <span>{{ item.trace.stageCount || item.trace.stages?.length || 0 }} 阶段 · {{ item.trace.totalDurationMs || 0 }}ms · {{ item.traceExpanded ? '收起' : '展开' }}</span>
                    </button>
                    <div v-if="item.traceExpanded" class="query-chat-trace-panel">
                      <div
                        v-for="stage in item.trace.stages || []"
                        :key="stage.stageCode"
                        class="query-chat-trace-stage"
                      >
                        <div class="query-chat-trace-stage-head">
                          <strong>{{ stage.stageLabel }}</strong>
                          <span>{{ stage.stageType }} · {{ stage.status }} · {{ stage.durationMs || 0 }}ms</span>
                        </div>
                        <div v-if="stage.inputFields?.length" class="query-chat-trace-section query-chat-trace-section-input">
                          <div class="query-chat-trace-section-title">输入</div>
                          <div class="query-chat-trace-fields">
                            <div v-for="field in stage.inputFields" :key="`in-${stage.stageCode}-${field.fieldCode}`" class="query-chat-trace-field">
                              <span>{{ field.fieldLabel }}</span>
                              <pre>{{ field.fieldValue }}</pre>
                            </div>
                          </div>
                        </div>
                        <div v-if="stage.outputFields?.length" class="query-chat-trace-section query-chat-trace-section-output">
                          <div class="query-chat-trace-section-title">输出</div>
                          <div class="query-chat-trace-fields">
                            <div v-for="field in stage.outputFields" :key="`out-${stage.stageCode}-${field.fieldCode}`" class="query-chat-trace-field">
                            <span>{{ field.fieldLabel }}</span>
                            <pre>{{ field.fieldValue }}</pre>
                            </div>
                          </div>
                        </div>
                        <div v-if="stage.llmCall" class="query-chat-trace-section query-chat-trace-section-llm">
                          <div class="query-chat-trace-section-title">大模型调用</div>
                          <div class="query-chat-trace-llm-meta">
                            模型 {{ stage.llmCall.modelId || '-' }} · {{ stage.llmCall.providerType || '-' }} · {{ stage.llmCall.actualModel || '-' }} · Token {{ stage.llmCall.totalTokens || 0 }}
                          </div>
                          <div v-if="stage.llmCall.providerRequestId" class="query-chat-trace-field">
                            <span>Provider Request ID</span>
                            <pre>{{ stage.llmCall.providerRequestId }}</pre>
                          </div>
                          <div v-if="stage.llmCall.systemPrompt" class="query-chat-trace-field">
                            <span>System Prompt</span>
                            <pre>{{ stage.llmCall.systemPrompt }}</pre>
                          </div>
                          <div v-if="stage.llmCall.userPrompt" class="query-chat-trace-field">
                            <span>User Prompt</span>
                            <pre>{{ stage.llmCall.userPrompt }}</pre>
                          </div>
                          <div v-if="stage.llmCall.thinkingContent" class="query-chat-trace-field">
                            <span>Thinking</span>
                            <pre>{{ stage.llmCall.thinkingContent }}</pre>
                          </div>
                          <div v-if="stage.llmCall.fullOutput" class="query-chat-trace-field">
                            <span>完整输出</span>
                            <pre>{{ stage.llmCall.fullOutput }}</pre>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                  <div v-if="item.content || item.liveOutput || item.pending" class="query-chat-text" :class="{ 'is-thinking': item.pending || item.streaming }">
                    <loading-outlined v-if="item.pending" class="query-chat-thinking-icon" />
                    <span>{{ item.liveOutput || item.content || '思考中...' }}</span>
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
                          <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="sqlActionIcon" alt="" /></template>
                        </a-button>
                      </a-tooltip>
                      <a-tooltip title="EXPLAIN">
                        <a-button size="small" class="sql-action-icon-btn" @click="explainSqlForTab(activeQueryTab, item.sqlText || '')">
                          <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="explainIcon" alt="" /></template>
                        </a-button>
                      </a-tooltip>
                      <a-tooltip :title="activeQueryTab.sqlExecuting ? '终止执行' : '执行 SQL'">
                        <a-button
                          size="small"
                          type="default"
                          :danger="activeQueryTab.sqlExecuting"
                          class="sql-action-icon-btn"
                          @click="activeQueryTab.sqlExecuting ? terminateSqlExecutionForTab(activeQueryTab) : executeSqlForTab(activeQueryTab, item.sqlText || '')"
                        >
                          <template #icon>
                            <img
                              class="toolbar-action-icon sql-action-icon-img"
                              :src="activeQueryTab.sqlExecuting ? stopActionIcon : executeIcon"
                              alt=""
                            />
                          </template>
                        </a-button>
                      </a-tooltip>
                      <a-tooltip title="解释 SQL">
                        <a-button size="small" class="sql-action-icon-btn" @click="explainMessageSqlInChat(activeQueryTab, item.sqlText || '')">
                          <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="interpretIcon" alt="" /></template>
                        </a-button>
                      </a-tooltip>
                      <a-tooltip title="分析 SQL">
                        <a-button size="small" class="sql-action-icon-btn" @click="analyzeMessageSqlInChat(activeQueryTab, item.sqlText || '')">
                          <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="analyzeIcon" alt="" /></template>
                        </a-button>
                      </a-tooltip>
                      <a-tooltip v-if="item.chartImageDataUrl || item.chartImageCacheKey" title="下载图表 PNG">
                        <a-button size="small" class="sql-action-icon-btn" @click="downloadMessageChart(item)">
                          <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="exportIcon" alt="" /></template>
                        </a-button>
                      </a-tooltip>
                    </a-space>
                  </template>
                </div>
              </template>
            </div>
          </div>

      <div class="query-chat-composer">
            <div class="query-chat-composer-input-wrap">
              <a-textarea
                v-model:value="activeQueryTab.prompt"
                :rows="4"
                :placeholder="tt('例如：查询近 7 天订单量，并按天聚合')"
                data-testid="studio-query-chat-prompt"
                @input="handleChatComposerInput($event, activeQueryTab)"
                @click="handleChatComposerCursorChange($event, activeQueryTab)"
                @keyup="handleChatComposerCursorChange($event, activeQueryTab)"
                @keydown="handleChatComposerKeydown($event, activeQueryTab)"
              />
              <div
                v-if="queryPromptAssist.visible && queryPromptAssist.tabKey === activeQueryTab.key"
                class="query-chat-prompt-assist query-chat-prompt-assist-floating"
              >
                <div class="query-chat-prompt-assist-head">
                  <span>{{ queryPromptAssist.mode === 'table' ? '引用当前库表' : `引用 ${queryPromptAssist.tableName} 字段` }}</span>
                  <button type="button" class="query-chat-prompt-assist-close" @mousedown.prevent @click="closeQueryPromptAssist">
                    关闭
                  </button>
                </div>
                <div v-if="queryPromptAssist.loading" class="query-chat-prompt-assist-empty">正在加载...</div>
                <div v-else-if="queryPromptAssist.items.length" ref="queryPromptAssistListRef" class="query-chat-prompt-assist-list">
                  <button
                    v-for="(option, index) in queryPromptAssist.items"
                    :key="option.key"
                    :ref="(el) => bindQueryPromptAssistItemRef(el, option.key)"
                    type="button"
                    class="query-chat-prompt-assist-item"
                    :class="{ 'is-active': queryPromptAssist.activeIndex === index }"
                    @mouseenter="setQueryPromptAssistActive(index)"
                    @mousedown.prevent
                    @click="applyPromptAssistOption(activeQueryTab, option)"
                  >
                    <span class="query-chat-prompt-assist-main">
                      <span class="query-chat-prompt-assist-label">{{ option.label }}</span>
                      <span v-if="option.meta" class="query-chat-prompt-assist-meta">{{ option.meta }}</span>
                    </span>
                    <span v-if="option.description" class="query-chat-prompt-assist-desc">{{ option.description }}</span>
                  </button>
                </div>
                <div v-else class="query-chat-prompt-assist-empty">{{ queryPromptAssist.emptyText }}</div>
                <div class="query-chat-prompt-assist-tip">输入 @ 选表，输入 . 选字段，Enter 或 Tab 确认</div>
              </div>
            </div>
            <div class="query-chat-composer-row">
              <div class="query-chat-composer-meta">
                <div class="query-chat-model-box">
                <span>模型</span>
                <a-select
                  v-model:value="activeQueryTab.selectedAiModel"
                  size="small"
                  style="min-width: 190px"
                  :options="aiModelOptions"
                />
                <a-dropdown placement="topLeft" :trigger="['click']">
                  <button type="button" class="query-chat-model-pill query-chat-model-trigger">
                    <span class="query-chat-model-pill-text">
                      {{ aiModelOptions.find((item) => item.value === activeQueryTab?.selectedAiModel)?.label || '未选择模型' }}
                    </span>
                  </button>
                  <template #overlay>
                    <a-menu
                      :selectedKeys="activeQueryTab?.selectedAiModel ? [activeQueryTab.selectedAiModel] : []"
                      @click="handleActiveQueryModelMenuClick"
                    >
                      <a-menu-item v-for="item in aiModelOptions" :key="String(item.value)">
                        {{ item.label }}
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
                </div>
                <span class="query-chat-mode-pill" :class="{ 'is-active': activeQueryTab?.autoMode }">
                  {{ activeQueryTab?.autoMode ? 'Auto' : 'Manual' }}
                </span>
                <a-tooltip placement="topRight" overlay-class-name="query-context-usage-tooltip">
                  <template #title>
                    <div class="query-context-usage-tooltip-copy">
                      <strong>窗口上下文占比</strong>
                      <span>当前占用 {{ formatCompactCount(activeQueryContextUsage.usedTokens) }} tokens</span>
                      <span>总窗口 {{ formatCompactCount(activeQueryContextUsage.totalTokens) }} tokens</span>
                      <span>当前占比 {{ Math.max(0, activeQueryContextUsage.percent) }}%</span>
                      <span v-if="!activeQueryContextUsage.enabled">对话记忆已关闭，当前为估算参考值</span>
                    </div>
                  </template>
                  <span
                    class="query-context-usage-ring"
                    :class="[`is-${activeQueryContextUsage.tone}`, { 'is-disabled': !activeQueryContextUsage.enabled }]"
                    :style="{ '--context-usage-ratio': `${activeQueryContextUsage.cappedRatio}` }"
                  >
                    <span class="query-context-usage-ring-core"></span>
                  </span>
                </a-tooltip>
                <a-popover placement="topRight" trigger="click" overlay-class-name="query-chat-settings-popover">
                  <template #content>
                    <div class="query-chat-settings-panel">
                      <div class="query-chat-settings-item">
                        <div class="query-chat-settings-copy">
                          <span class="query-chat-settings-title">Auto 模式</span>
                          <span class="query-chat-settings-desc">自动判断生成、解释解读、分析等动作</span>
                        </div>
                        <a-switch v-model:checked="activeQueryTab.autoMode" size="small" />
                      </div>
                      <div class="query-chat-settings-item is-column">
                        <div class="query-chat-settings-copy">
                          <span class="query-chat-settings-title">详情输出</span>
                          <span class="query-chat-settings-desc">控制回复详细程度，默认跟随全局配置</span>
                        </div>
                        <a-select
                          v-model:value="activeQueryTab.detailOutputOverride"
                          size="small"
                          style="width: 100%"
                          :options="[
                            { label: '详情: 跟随全局', value: null },
                            { label: '详情: 开', value: true },
                            { label: '详情: 关', value: false },
                          ]"
                        />
                      </div>
                      <div class="query-chat-settings-item">
                        <div class="query-chat-settings-copy">
                          <a-tooltip title="开启后会记忆并利用更长的对话上下文，适合连续追问与复杂任务。">
                            <span class="query-chat-settings-title is-help">长对话</span>
                          </a-tooltip>
                          <span class="query-chat-settings-desc">支持连续追问</span>
                        </div>
                        <a-switch v-model:checked="activeQueryTab.conversationMemoryEnabled" size="small" />
                      </div>
                      <div v-if="activeQueryTab.autoMode" class="query-chat-settings-item">
                        <div class="query-chat-settings-copy">
                          <span class="query-chat-settings-title">自动执行</span>
                          <span class="query-chat-settings-desc">生成 SQL 后直接执行</span>
                        </div>
                        <a-switch v-model:checked="activeQueryTab.autoExecute" size="small" />
                      </div>
                    </div>
                  </template>
                  <a-tooltip title="对话设置">
                    <a-button size="small" class="sql-action-icon-btn query-chat-settings-trigger">
                      <template #icon><setting-outlined /></template>
                    </a-button>
                  </a-tooltip>
                </a-popover>
                <a-select
                  v-model:value="activeQueryTab.detailOutputOverride"
                  size="small"
                  style="min-width: 150px"
                  :options="[
                    { label: '详情: 跟随全局', value: null },
                    { label: '详情: 开', value: true },
                    { label: '详情: 关', value: false },
                  ]"
                />
                <a-tooltip title="开启后会记忆并利用更长的对话上下文，适合连续追问与复杂任务。">
                  <span class="query-chat-long-dialog-label">长对话</span>
                </a-tooltip>
                <a-switch v-model:checked="activeQueryTab.conversationMemoryEnabled" size="small" />
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
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="stopActionIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
              </div>
              <div v-else-if="activeQueryTab.autoMode" class="query-chat-composer-actions">
                <a-tooltip title="Auto 发送">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn"
                    @click="sendAutoForTab(activeQueryTab)"
                  >
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="sendQueryIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
              </div>
              <div v-else class="query-chat-composer-actions">
                <a-tooltip :title="generateActionLabelByDbType(queryTabDbType(activeQueryTab))">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn"
                    data-testid="studio-query-chat-generate"
                    @click="generateSqlForTab(activeQueryTab, 'generate')"
                  >
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="sqlActionIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip :title="explainActionLabelByDbType(queryTabDbType(activeQueryTab))">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn"
                    data-testid="studio-query-chat-explain"
                    @click="generateSqlForTab(activeQueryTab, 'explain')"
                  >
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="interpretIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip :title="analyzeActionLabelByDbType(queryTabDbType(activeQueryTab))">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn"
                    data-testid="studio-query-chat-analyze"
                    @click="generateSqlForTab(activeQueryTab, 'analyze')"
                  >
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="analyzeIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip v-if="canGenerateChartForTab(activeQueryTab)" title="生成图表">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn"
                    @click="generateChartPlanForTab(activeQueryTab)"
                  >
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="chartIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
              </div>
            </div>
          </div>
        </section>

        <div v-if="activeQueryTab" class="pane-splitter pane-splitter-right query-pane-splitter" @mousedown="startResizeQueryPane" />

        <aside v-if="activeQueryTab" ref="queryEditorPaneRef" class="pane pane-right query-editor-pane">
          <div class="pane-title pane-title-with-action">
            <div class="pane-title-actions query-editor-header-actions">
              <a-tooltip
                v-if="!isKvConnectionId(activeQueryTab.connectionId)"
                :title="activeQueryTab.selectedSqlText ? `计划选择的${queryUnitLabelByDbType(queryTabDbType(activeQueryTab))}` : `计划 ${queryUnitLabelByDbType(queryTabDbType(activeQueryTab))}`"
              >
                <a-button
                  size="small"
                  :class="['sql-action-icon-btn', { 'is-selection-active': !!activeQueryTab.selectedSqlText }]"
                  data-testid="studio-query-editor-explain"
                  @click="explainSqlForTab(activeQueryTab)"
                >
                  <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="explainIcon" alt="" /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip :title="activeQueryTab.sqlExecuting ? '终止执行' : (activeQueryTab.selectedSqlText ? `执行选中的${queryUnitLabelByDbType(queryTabDbType(activeQueryTab))}` : `执行 ${queryUnitLabelByDbType(queryTabDbType(activeQueryTab))}`)">
                <a-button
                  size="small"
                  :danger="activeQueryTab.sqlExecuting"
                  :class="['sql-action-icon-btn', { 'is-selection-active': !!activeQueryTab.selectedSqlText }]"
                  data-testid="studio-query-editor-execute"
                  @click="activeQueryTab.sqlExecuting ? terminateSqlExecutionForTab(activeQueryTab) : executeSqlForTab(activeQueryTab)"
                >
                  <template #icon>
                    <img
                      class="toolbar-action-icon sql-action-icon-img"
                      :src="activeQueryTab.sqlExecuting ? stopActionIcon : executeIcon"
                      alt=""
                    />
                  </template>
                </a-button>
              </a-tooltip>
              <a-tooltip v-if="activeQueryTab.lastExecuteFailed && !isKvConnectionId(activeQueryTab.connectionId)" title="自动修复">
                <a-button size="small" class="sql-action-icon-btn" @click="repairSqlForTab(activeQueryTab)">
                  <template #icon><tool-outlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip :title="activeQueryTab.selectedSqlText ? `格式化选中的${queryUnitLabelByDbType(queryTabDbType(activeQueryTab))}` : `格式化 ${queryUnitLabelByDbType(queryTabDbType(activeQueryTab))}`">
                <a-button
                  size="small"
                  :class="['sql-action-icon-btn', { 'is-selection-active': !!activeQueryTab.selectedSqlText }]"
                  @click="formatSqlForTab(activeQueryTab)"
                >
                  <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="prettyIcon" alt="" /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip title="保存查询">
                <a-button size="small" class="sql-action-icon-btn" data-testid="studio-query-save" @click="openSaveQueryModal(activeQueryTab)">
                  <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="saveQueryIcon" alt="" /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip v-if="!isKvConnectionId(activeQueryTab.connectionId)" title="保存为样例 SQL">
                <a-button size="small" class="sql-action-icon-btn" data-testid="studio-query-save-example" @click="openSaveQueryAsExampleModal(activeQueryTab)">
                  <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="exampleIcon" alt="" /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip v-if="activeQueryTab.selectedSqlText" title="所选 SQL 加入对话">
                <a-button
                  size="small"
                  class="sql-action-icon-btn"
                  @click="appendSelectedSqlToPrompt(activeQueryTab)"
                >
                  <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="dialogIcon" alt="" /></template>
                </a-button>
              </a-tooltip>
            </div>
            <div v-if="!isKvConnectionId(activeQueryTab.connectionId)" class="pane-title-actions query-memory-title-actions">
              <span class="query-memory-title-label">记忆理解</span>
              <a-switch v-model:checked="activeQueryTab.sqlMemoryEnabled" size="small" />
              <a-tooltip title="开启后，执行成功的 SQL 会被理解记忆，并在后续生成与执行中参与向量召回。">
                <span class="query-memory-title-help">说明</span>
              </a-tooltip>
            </div>
          </div>

          <div
            ref="sqlEditorContainerRef"
            class="editor-group query-editor-group"
            :style="{ height: `${queryEditorSectionHeight}px` }"
          >
            <MonacoEditor
              v-model:value="activeQueryTab.sqlText"
              :language="activeQueryEditorLanguage"
              width="100%"
              height="100%"
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
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="dialogIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip :title="explainActionLabelByDbType(queryTabDbType(activeQueryTab))">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn sql-selection-popover-btn"
                    :loading="activeQueryTab.aiGenerating"
                    :disabled="activeQueryTab.aiGenerating"
                    @click="explainSelectedSqlInChat(activeQueryTab)"
                  >
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="interpretIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip :title="analyzeActionLabelByDbType(queryTabDbType(activeQueryTab))">
                  <a-button
                    size="small"
                    class="sql-action-icon-btn sql-selection-popover-btn"
                    :loading="activeQueryTab.aiGenerating"
                    :disabled="activeQueryTab.aiGenerating"
                    @click="analyzeSelectedSqlInChat(activeQueryTab)"
                  >
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="analyzeIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
              </a-space>
            </div>
          </div>

          <div
            class="query-editor-section-splitter"
            title="拖拽调整 SQL 编辑区和查询结果区高度"
            @mousedown="startResizeQueryEditorSections"
          />

          <div class="query-result-panel">
            <div class="query-result-title-row">
              <div class="query-result-title">查询结果</div>
              <a-space size="small">
                <a-tooltip title="表格结果">
                  <a-button
                    size="small"
                    :class="['sql-action-icon-btn', { 'is-selection-active': activeQueryTab.resultViewMode === 'table' }]"
                    data-testid="studio-query-result-table"
                    @click="setQueryResultViewMode(activeQueryTab, 'table')"
                  >
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="tableIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip v-if="canGenerateChartForTab(activeQueryTab)" title="图表结果">
                  <a-button
                    size="small"
                    :class="['sql-action-icon-btn', { 'is-selection-active': activeQueryTab.resultViewMode === 'chart' }]"
                    data-testid="studio-query-result-chart"
                    @click="setQueryResultViewMode(activeQueryTab, 'chart')"
                  >
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="chartIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip v-if="canExportActiveQueryResult(activeQueryTab)" :title="queryResultExportTooltip(activeQueryTab)">
                  <a-button size="small" class="sql-action-icon-btn" @click="exportActiveQueryResult(activeQueryTab)">
                    <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="exportIcon" alt="" /></template>
                  </a-button>
                </a-tooltip>
              </a-space>
            </div>
            <a-tabs
              v-if="activeQueryTab.statementResults.length"
              class="query-result-tabs"
              size="small"
              :activeKey="activeQueryTab.activeStatementResultKey || activeStatementResult?.key"
              @change="setActiveStatementResult(activeQueryTab, String($event || ''))"
            >
              <a-tab-pane v-for="item in activeQueryTab.statementResults" :key="item.key">
                <template #tab>
                  <span class="query-result-tab-label" :class="[`is-${item.status}`]">
                    <span class="query-result-tab-text">{{ resultTabTitle(item) }}</span>
                  </span>
                </template>
              </a-tab-pane>
            </a-tabs>
            <div v-if="activeQueryTab.lastExecuteFailed" class="query-result-error">
              <span class="query-result-error-text">{{ activeQueryTab.lastExecuteErrorMessage || `${queryUnitLabelByDbType(queryTabDbType(activeQueryTab))} 执行失败` }}</span>
              <a-button v-if="!isKvConnectionId(activeQueryTab.connectionId)" size="small" type="primary" danger @click="repairSqlForTab(activeQueryTab)">修复 SQL</a-button>
            </div>
            <template v-if="activeQueryTab.resultViewMode === 'table'">
              <TableDataVirtualGrid
                class="query-result-virtual-grid"
                :tab="{
                  key: activeQueryTab.key,
                  editable: false,
                  selectedRowKey: '',
                  editingCellKey: '',
                  pageNo: 1,
                  pageSize: activeResultRows.length,
                }"
                :columns="activeResultColumns"
                :rows="activeResultRows"
                :scroll-x="queryResultScrollX"
                :scroll-y="queryResultScrollY"
                :reset-key="`${activeQueryTab.key}:${activeQueryTab.updatedAt}:${activeResultRows.length}:${activeResultColumns.length}`"
                :is-primary-key-column="() => false"
                :column-editor-type="() => 'text'"
                @select-row="() => undefined"
                @start-edit="() => undefined"
                @stop-edit="() => undefined"
                @update-cell="() => undefined"
              />
            </template>
            <template v-else>
              <div class="query-chart-manual-panel">
                <div class="query-chart-manual-grid">
                  <div class="query-chart-control">
                    <a-tooltip title="选择图表类型；折线图、柱状图和趋势图支持按分组字段拆成多系列。">
                      <span class="query-chart-control-label">图表类型</span>
                    </a-tooltip>
                    <a-select
                      :value="activeQueryTab.manualChartConfig.chartType"
                      @update:value="handleManualChartTypeChange(activeQueryTab, String($event || 'LINE'))"
                      size="small"
                      style="width: 100%"
                      :options="chartTypeOptions"
                    />
                  </div>
                  <div
                    v-if="['LINE', 'BAR', 'SCATTER', 'TREND'].includes(activeQueryTab.manualChartConfig.chartType || '')"
                    class="query-chart-control"
                  >
                    <a-tooltip title="X 轴通常选择时间、分类或排序维度字段。">
                      <span class="query-chart-control-label">X 轴</span>
                    </a-tooltip>
                    <a-select
                      :value="activeQueryTab.manualChartConfig.xField"
                      @update:value="handleManualChartXAxisChange(activeQueryTab, String($event || ''))"
                      size="small"
                      style="width: 100%"
                      :options="activeChartFieldOptions"
                      placeholder="X 轴"
                    />
                  </div>
                  <div
                    v-if="['LINE', 'BAR', 'TREND'].includes(activeQueryTab.manualChartConfig.chartType || '')"
                    class="query-chart-control query-chart-control-wide"
                  >
                    <a-tooltip :title="activeQueryTab.manualChartConfig.seriesField ? '启用分组字段后，只保留一个数值字段，系统会按分组字段自动拆成多条系列。' : '未设置分组字段时，可同时选择多个数值字段作为多条系列。'">
                      <span class="query-chart-control-label">{{ activeQueryTab.manualChartConfig.seriesField ? 'Y 轴（单值）' : 'Y 轴（多选）' }}</span>
                    </a-tooltip>
                    <a-select
                      v-if="activeQueryTab.manualChartConfig.seriesField"
                      :value="activeQueryTab.manualChartConfig.yFields?.[0] || ''"
                      @update:value="handleManualChartSingleYFieldChange(activeQueryTab, String($event || ''))"
                      size="small"
                      style="width: 100%"
                      :options="activeNumericFieldOptions"
                      placeholder="Y 轴（单值）"
                    />
                    <a-select
                      v-else
                      :value="activeQueryTab.manualChartConfig.yFields"
                      @update:value="handleManualChartYFieldsChange(activeQueryTab, Array.isArray($event) ? $event.map((item) => String(item)) : [])"
                      size="small"
                      mode="multiple"
                      :max-tag-count="2"
                      style="width: 100%"
                      :options="activeNumericFieldOptions"
                      placeholder="Y 轴（多选）"
                    />
                  </div>
                  <div
                    v-if="['LINE', 'BAR', 'TREND'].includes(activeQueryTab.manualChartConfig.chartType || '')"
                    class="query-chart-control"
                  >
                    <a-tooltip title="按该字段拆分系列；像 model_id 这类 ID 字段也可以用来生成多条线。">
                      <span class="query-chart-control-label">分组字段</span>
                    </a-tooltip>
                    <a-select
                      :value="activeQueryTab.manualChartConfig.seriesField"
                      @update:value="handleManualChartSeriesFieldChange(activeQueryTab, String($event || ''))"
                      size="small"
                      allow-clear
                      style="width: 100%"
                      :options="activeSeriesFieldOptions"
                      placeholder="分组字段"
                    />
                  </div>
                  <div
                    v-if="activeQueryTab.manualChartConfig.chartType === 'SCATTER'"
                    class="query-chart-control"
                  >
                    <a-tooltip title="散点图需要 1 个数值型 Y 轴字段。">
                      <span class="query-chart-control-label">Y 轴</span>
                    </a-tooltip>
                    <a-select
                      :value="activeQueryTab.manualChartConfig.yFields"
                      @update:value="handleManualChartYFieldsChange(activeQueryTab, Array.isArray($event) ? $event.map((item) => String(item)) : [])"
                      size="small"
                      mode="multiple"
                      style="width: 100%"
                      :options="activeNumericFieldOptions"
                      placeholder="Y 轴"
                      :max-tag-count="1"
                      :max-count="1"
                    />
                  </div>
                  <div
                    v-if="activeQueryTab.manualChartConfig.chartType === 'PIE'"
                    class="query-chart-control"
                  >
                    <a-tooltip title="饼图按该字段聚合分类。">
                      <span class="query-chart-control-label">分类字段</span>
                    </a-tooltip>
                    <a-select
                      v-model:value="activeQueryTab.manualChartConfig.categoryField"
                      size="small"
                      style="width: 100%"
                      :options="activeChartFieldOptions"
                      placeholder="分类字段"
                    />
                  </div>
                  <div
                    v-if="activeQueryTab.manualChartConfig.chartType === 'PIE'"
                    class="query-chart-control"
                  >
                    <a-tooltip title="饼图使用该数值字段作为占比或总量。">
                      <span class="query-chart-control-label">数值字段</span>
                    </a-tooltip>
                    <a-select
                      v-model:value="activeQueryTab.manualChartConfig.valueField"
                      size="small"
                      style="width: 100%"
                      :options="activeNumericFieldOptions"
                      placeholder="数值字段"
                    />
                  </div>
                  <div class="query-chart-control">
                    <a-tooltip title="设置后会在渲染前先按该字段排序，时间趋势图建议与 X 轴保持一致。">
                      <span class="query-chart-control-label">排序字段</span>
                    </a-tooltip>
                    <a-select
                      v-model:value="activeQueryTab.manualChartConfig.sortField"
                      size="small"
                      style="width: 100%"
                      :options="activeChartFieldOptions"
                      placeholder="排序字段"
                      allow-clear
                    />
                  </div>
                  <div class="query-chart-control query-chart-control-compact">
                    <a-tooltip title="控制排序方向；趋势图一般使用升序。">
                      <span class="query-chart-control-label">排序方向</span>
                    </a-tooltip>
                    <a-select
                      v-model:value="activeQueryTab.manualChartConfig.sortDirection"
                      size="small"
                      style="width: 100%"
                      :options="chartSortDirectionOptions"
                    />
                  </div>
                  <div class="query-chart-control query-chart-control-action">
                    <a-tooltip title="按当前配置生成图表">
                      <span class="query-chart-control-label">生成图表</span>
                    </a-tooltip>
                    <a-button size="small" type="primary" class="sql-action-icon-btn query-chart-generate-btn" @click="generateManualChartForTab(activeQueryTab)">
                      <template #icon><img class="toolbar-action-icon sql-action-icon-img" :src="chartIcon" alt="" /></template>
                    </a-button>
                  </div>
                </div>
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
            <div class="query-result-footer">
              <template v-if="activeQueryTab.executeResult && !activeResultRows.length">
                影响 {{ activeQueryTab.executeResult.affectedRows || 0 }} 行
              </template>
              <template v-else>
                共 {{ activeResultRows.length }} 行
                <span v-if="activeQueryTab.executeResult?.truncated">，当前仅展示前 {{ activeResultRows.length }} 行</span>
              </template>
            </div>
          </div>
        </aside>
      </template>
    </main>

    <a-modal
      v-model:open="createModalOpen"
      :title="tt(isEditMode ? '编辑连接' : '新建连接')"
      width="640px"
      :ok-text="tt(isEditMode ? '保存' : '创建')"
      :cancel-text="tt('取消')"
      @ok="saveConnection"
      @cancel="resetConnectionModalState"
    >
      <a-form layout="vertical" data-testid="studio-settings-modal">
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
            <a-form-item label="连接分组">
              <a-select v-model:value="connectionForm.groupId" :options="connectionGroupOptions" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="环境">
              <a-select v-model:value="connectionForm.env" :options="envOptions" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row
          v-if="connectionFormDbTypeSpec?.requiresHost !== false || connectionFormDbTypeSpec?.requiresPort !== false"
          :gutter="12"
        >
          <a-col v-if="connectionFormDbTypeSpec?.requiresHost !== false" :span="12">
            <a-form-item label="主机">
              <a-input v-model:value="connectionForm.host" />
            </a-form-item>
          </a-col>
          <a-col v-if="connectionFormDbTypeSpec?.requiresPort !== false" :span="12">
            <a-form-item label="端口">
              <a-input-number v-model:value="connectionForm.port" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row
          v-if="connectionFormDbTypeSpec?.supportsUsername !== false || connectionFormDbTypeSpec?.supportsPassword !== false"
          :gutter="12"
        >
          <a-col v-if="connectionFormDbTypeSpec?.supportsUsername !== false" :span="12">
            <a-form-item label="用户">
              <a-input v-model:value="connectionForm.username" placeholder="请输入数据库用户" />
            </a-form-item>
          </a-col>
          <a-col v-if="connectionFormDbTypeSpec?.supportsPassword !== false" :span="12">
            <a-form-item label="密码">
              <a-input-password v-model:value="connectionForm.password" placeholder="请输入数据库密码" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row
          v-if="isMultiDatabaseFormType || connectionFormDbTypeSpec?.supportsDatabaseName !== false"
          :gutter="12"
        >
          <a-col :span="isMultiDatabaseFormType ? 24 : 16">
            <a-form-item :label="isMultiDatabaseFormType ? '展示数据库（多选）' : (connectionFormDbTypeSpec?.databaseNameLabel || '数据库名/路径')">
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
                    {{ tt('获取数据库') }}
                  </a-button>
                </div>
                <div class="connection-db-selector-tip">不勾选时，连接树显示该连接下全部数据库</div>
                <div v-if="connectionPreviewError" class="connection-db-selector-error">{{ connectionPreviewError }}</div>
              </template>
              <a-input
                v-else
                v-model:value="connectionForm.databaseName"
                :placeholder="getDatabaseNamePlaceholder(connectionForm.dbType)"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="自定义参数">
          <a-textarea
            v-model:value="connectionForm.customParams"
            :rows="4"
            :placeholder="tt('每行一个 key=value，例如： encrypt=true trustServerCertificate=true')"
          />
          <div class="connection-custom-params-tip">
            连接时会自动拼接到 JDBC 配置中。推荐每行填写一个参数，例如 `encrypt=true`。
          </div>
          <div v-if="connectionForm.dbType === 'SQLSERVER'" class="connection-custom-params-tip">
            SQL Server 默认启用 `encrypt=true` 与 `trustServerCertificate=true`，手工填写同名参数可覆盖默认行为。
          </div>
        </a-form-item>

        <a-space>
          <a-checkbox v-model:checked="connectionForm.readOnly">只读</a-checkbox>
          <a-checkbox v-if="connectionForm.dbType !== 'SQLITE'" v-model:checked="connectionForm.sshEnabled">SSH 隧道</a-checkbox>
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
      :title="tt('设置')"
      width="760px"
      :ok-text="tt('保存配置')"
      :cancel-text="tt('取消')"
      @ok="saveAiConfig"
    >
      <a-form layout="vertical">
        <a-tabs v-model:activeKey="aiConfigActiveTab">
          <a-tab-pane key="general" :tab="tt('界面设置')">
            <a-row :gutter="12">
              <a-col :span="12">
                <a-form-item :label="tt('界面语言')">
                  <a-select
                    :value="currentLocale"
                    :options="localeSelectOptions"
                    data-testid="studio-settings-locale-select"
                    @update:value="handleLocaleChange"
                  />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item :label="tt('深色模式')">
                  <a-switch :checked="isDarkTheme" data-testid="studio-settings-theme-switch" @change="toggleTheme" />
                </a-form-item>
              </a-col>
            </a-row>
            <a-divider style="margin: 12px 0;" />
            <a-row :gutter="12" align="middle">
              <a-col :span="12">
                <a-form-item :label="tt('关于')" style="margin-bottom: 0;">
                  <a-space>
                    <a-button size="small" @click="openPrivacyPolicy">
                      <template #icon><FileProtectOutlined /></template>
                      {{ tt('隐私政策') }}
                    </a-button>
                  </a-space>
                </a-form-item>
              </a-col>
            </a-row>
          </a-tab-pane>

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
                <a-form-item label="最近原文最大轮数">
                  <a-input-number v-model:value="aiConfigForm.conversationMemoryWindowSize" :min="4" :max="50" style="width: 100%" />
                </a-form-item>
              </a-col>
            </a-row>
            <a-row :gutter="12">
              <a-col :span="12">
                <a-form-item label="记忆窗口 Token 上限">
                  <a-input-number
                    v-model:value="aiConfigForm.conversationMemoryWindowTokens"
                    :min="512"
                    :max="32000"
                    :step="256"
                    style="width: 100%"
                  />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="自动压缩触发比例">
                  <a-input-number
                    v-model:value="aiConfigForm.conversationAutoCompressRatio"
                    :min="0.3"
                    :max="0.95"
                    :step="0.05"
                    style="width: 100%"
                  />
                </a-form-item>
              </a-col>
            </a-row>
            <a-row :gutter="12">
              <a-col :span="12">
                <a-form-item label="默认输出详情">
                  <a-switch v-model:checked="aiConfigForm.detailOutputEnabled" />
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
                    <a-input v-model:value="item.name" :placeholder="tt('GPT-4.1 / 本地 Codex CLI')" />
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
                      <a-input v-model:value="item.cliCommand" :placeholder="tt('codex / claude / 其他命令')" />
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
                <a-form-item label="运行模式">
                  <a-select
                    v-model:value="ragConfigForm.ragEmbeddingProviderType"
                    :options="ragProviderTypeOptions"
                    @change="handleEmbeddingProviderTypeChange"
                  />
                </a-form-item>
                <template v-if="ragLocalOnnxEnabled && ragConfigForm.ragEmbeddingProviderType === 'LOCAL_ONNX'">
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
                  <a-alert class="rag-local-tip" type="info" show-icon>
                    <template #message>{{ localEmbeddingTipTitle }}</template>
                    <template #description>
                      <div class="rag-local-tip-body">
                        <span>{{ localEmbeddingTipDescription }}</span>
                        <a href="" @click.prevent="openExternalLink(embeddingModelRepoUrl)">{{ localEmbeddingTipLinkText }}</a>
                      </div>
                    </template>
                  </a-alert>
                </template>
                <template v-else>
                  <a-form-item label="在线 Base URL">
                    <a-input
                      v-model:value="ragConfigForm.ragEmbeddingOnlineBaseUrl"
                      placeholder="https://api.openai.com/v1"
                    />
                  </a-form-item>
                  <a-form-item label="在线 API Key">
                    <a-input-password
                      v-model:value="ragConfigForm.ragEmbeddingOnlineApiKey"
                      placeholder="sk-..."
                    />
                  </a-form-item>
                  <a-form-item label="在线模型">
                    <a-input
                      v-model:value="ragConfigForm.ragEmbeddingOnlineModel"
                      placeholder="text-embedding-3-small"
                    />
                  </a-form-item>
                </template>
              </div>
              <div class="rag-config-card">
                <div class="rag-config-card-title">Rerank 配置</div>
                <a-form-item>
                  <a-switch v-model:checked="ragConfigForm.ragRerankEnabled" @change="handleRerankEnabledChange" />
                  <span style="margin-left: 8px;">启用 Rerank</span>
                </a-form-item>
                <template v-if="ragConfigForm.ragRerankEnabled">
                  <a-form-item label="运行模式">
                    <a-select
                      v-model:value="ragConfigForm.ragRerankProviderType"
                      :options="ragProviderTypeOptions"
                    />
                  </a-form-item>
                  <template v-if="ragLocalOnnxEnabled && ragConfigForm.ragRerankProviderType === 'LOCAL_ONNX'">
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
                    <a-alert class="rag-local-tip" type="info" show-icon>
                      <template #message>{{ localRerankTipTitle }}</template>
                      <template #description>
                        <div class="rag-local-tip-body">
                          <span>{{ localRerankTipDescription }}</span>
                          <a href="" @click.prevent="openExternalLink(rerankModelRepoUrl)">{{ localRerankTipLinkText }}</a>
                        </div>
                      </template>
                    </a-alert>
                  </template>
                  <template v-else>
                    <a-form-item label="在线 Base URL">
                      <a-input
                        v-model:value="ragConfigForm.ragRerankOnlineBaseUrl"
                        placeholder="https://api.openai.com/v1"
                      />
                    </a-form-item>
                    <a-form-item label="在线 API Key">
                      <a-input-password
                        v-model:value="ragConfigForm.ragRerankOnlineApiKey"
                        placeholder="sk-..."
                      />
                    </a-form-item>
                    <a-form-item label="在线模型">
                      <a-input
                        v-model:value="ragConfigForm.ragRerankOnlineModel"
                        placeholder="bge-reranker-v2-m3"
                      />
                    </a-form-item>
                  </template>
                </template>
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
      :ok-text="tt('确认生成')"
      :cancel-text="tt('取消')"
      :confirm-loading="erTableSelectSubmitting"
      @ok="confirmErTableSelection"
    >
      <div class="er-select-modal" data-testid="studio-er-select-modal">
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
          :placeholder="tt('搜索表名')"
          data-testid="studio-er-select-search"
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
                :data-testid="buildAutomationId('studio-er-select-option', tableName)"
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
      :ok-text="tt('保存')"
      :cancel-text="tt('取消')"
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
      data-testid="studio-context-menu"
      :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
    >
      <template v-for="action in contextMenuActions" :key="action.id">
        <div v-if="action.children?.length" class="context-menu-submenu" :class="{ 'is-disabled': action.disabled }">
          <button
            class="context-menu-item context-menu-item-with-arrow"
            :class="{ danger: action.danger }"
            :disabled="action.disabled"
            type="button"
            :data-testid="contextMenuActionTestId(action.id)"
          >
            {{ action.label }}
            <span class="context-menu-submenu-arrow">›</span>
          </button>
          <div class="context-menu-submenu-panel">
            <button
              v-for="child in action.children"
              :key="child.id"
              class="context-menu-item"
              :class="{ danger: child.danger }"
              :disabled="child.disabled"
              :data-testid="contextMenuActionTestId(child.id)"
              @click="triggerContextAction(child.id)"
            >
              {{ child.label }}
            </button>
          </div>
        </div>
        <button
          v-else
          class="context-menu-item"
          :class="{ danger: action.danger }"
          :disabled="action.disabled"
          :data-testid="contextMenuActionTestId(action.id)"
          @click="triggerContextAction(action.id)"
        >
          {{ action.label }}
        </button>
      </template>
    </div>

    <a-modal
      v-model:open="groupModalOpen"
      :title="groupForm.mode === 'create' ? '新建分组' : '重命名分组'"
      :ok-text="tt(groupForm.mode === 'create' ? '创建' : '保存')"
      :cancel-text="tt('取消')"
      :confirm-loading="groupModalSubmitting"
      @ok="confirmGroupModal"
      @cancel="closeGroupModal"
    >
      <a-form layout="vertical">
        <a-form-item label="分组名称">
          <a-input
            v-model:value="groupForm.name"
            maxlength="64"
            placeholder="请输入分组名称"
            @pressEnter="confirmGroupModal"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="redisKeyModalOpen"
      :title="redisKeyModalMode === 'create' ? '新增键' : '编辑键'"
      :ok-text="tt(redisKeyModalMode === 'create' ? '创建' : '保存')"
      :cancel-text="tt('取消')"
      :confirm-loading="redisKeyModalSubmitting"
      width="720px"
      @ok="confirmRedisKeyModal"
      @cancel="closeRedisKeyModal"
    >
      <a-form layout="vertical">
        <a-row :gutter="12">
          <a-col :span="14">
            <a-form-item label="键名">
              <a-input v-model:value="redisKeyForm.keyName" :disabled="redisKeyModalMode === 'edit'" :placeholder="tt('例如：user:1:profile')" />
            </a-form-item>
          </a-col>
          <a-col :span="10">
            <a-form-item label="值类型">
              <a-select
                v-model:value="redisKeyForm.valueType"
                :disabled="redisKeyModalMode === 'edit'"
                :options="[
                  { label: 'string', value: 'string' },
                  { label: 'hash', value: 'hash' },
                  { label: 'list', value: 'list' },
                  { label: 'set', value: 'set' },
                  { label: 'zset', value: 'zset' },
                ]"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="TTL（秒）">
              <a-input-number v-model:value="redisKeyForm.ttlSeconds" :min="-1" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item :label="redisKeyForm.valueType === 'string' ? '键值内容' : 'JSON 内容'">
          <a-textarea
            v-model:value="redisKeyForm.editorPayload"
            :rows="12"
            :placeholder="redisEditorPlaceholder"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="namespaceModalOpen"
      :title="tt(namespaceForm.mode === 'create' ? `新建${namespaceForm.namespaceLabel}` : `编辑${namespaceForm.namespaceLabel}`)"
      :ok-text="tt(namespaceForm.mode === 'create' ? '创建' : '保存')"
      :cancel-text="tt('取消')"
      :confirm-loading="namespaceModalSubmitting"
      @ok="confirmNamespaceModal"
      @cancel="closeNamespaceModal"
    >
      <a-form layout="vertical">
        <a-form-item v-if="namespaceForm.mode === 'rename'" :label="tt(`原${namespaceForm.namespaceLabel}名称`)">
          <a-input :value="namespaceForm.sourceNamespaceName" disabled />
        </a-form-item>
        <a-form-item :label="tt(`${namespaceForm.mode === 'create' ? '新' : ''}${namespaceForm.namespaceLabel}名称`)">
          <a-input
            v-model:value="namespaceForm.targetNamespaceName"
            :placeholder="tt(`请输入${namespaceForm.namespaceLabel}名称`)"
            maxlength="128"
            @pressEnter="confirmNamespaceModal"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="renameTableModalOpen"
      title="重命名表"
      :ok-text="tt('确认重命名')"
      :cancel-text="tt('取消')"
      :confirm-loading="renameTableSubmitting"
      @ok="confirmRenameTable"
      @cancel="closeRenameTableModal"
    >
      <a-form layout="vertical">
        <a-form-item label="当前表名">
          <a-input :value="renameTableForm.sourceTableName" disabled />
        </a-form-item>
        <a-form-item label="新表名">
          <a-input
            v-model:value="renameTableForm.targetTableName"
            maxlength="128"
            placeholder="请输入新表名"
            @pressEnter="confirmRenameTable"
          />
        </a-form-item>
      </a-form>
    </a-modal>

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
      v-model:open="saveQueryModalOpen"
      :title="tt('保存查询')"
      width="480px"
      :ok-text="tt('保存')"
      :cancel-text="tt('取消')"
      :confirm-loading="saveQuerySubmitting"
      @ok="activeQueryTab && saveCurrentQuery(activeQueryTab)"
      @cancel="saveQueryModalOpen = false"
    >
      <a-form layout="vertical" data-testid="studio-save-query-modal">
        <a-form-item :label="tt('名称')" required>
          <a-input v-model:value="saveQueryTitle" maxlength="80" show-count :placeholder="tt('请输入保存查询名称')" data-testid="studio-save-query-title" />
        </a-form-item>
        <a-form-item :label="tt('保存位置')">
          <div class="save-query-context">{{ activeQueryTab ? queryTabConnectionName(activeQueryTab) : '-' }} / {{ activeQueryTab?.databaseName || '未指定库' }}</div>
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="saveQueryAsExampleModalOpen"
      :title="tt('保存为样例 SQL')"
      width="520px"
      :ok-text="tt('保存')"
      :cancel-text="tt('取消')"
      :confirm-loading="saveQueryAsExampleSubmitting"
      @ok="confirmSaveQueryAsExample"
      @cancel="saveQueryAsExampleModalOpen = false"
    >
      <a-form layout="vertical" data-testid="studio-save-example-modal">
        <a-form-item :label="tt('保存位置')">
          <div class="save-query-context">{{ saveQueryAsExampleContextText }}</div>
        </a-form-item>
        <a-form-item :label="tt('说明')">
          <a-textarea
            v-model:value="saveQueryAsExampleDescription"
            :rows="4"
            :placeholder="tt('补充这段样例 SQL 的用途、适用场景或注意事项')"
            data-testid="studio-save-example-description"
          />
        </a-form-item>
      </a-form>
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
              <span>当前库适用总量</span>
              <strong>{{ formatCompactCount(vectorizeOverviewData.totalVectorCount) }}</strong>
            </div>
            <div class="vectorize-overview-kpi-card">
              <span>全局知识总量</span>
              <strong>{{ formatCompactCount(vectorizeOverviewData.globalVectorCount) }}</strong>
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

          <div class="vectorize-overview-section-title">当前库适用统计</div>
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
            <div class="vectorize-overview-item">
              <span>术语知识</span>
              <strong>{{ formatCompactCount(vectorizeOverviewData.metricTermVectorCount) }}</strong>
            </div>
            <div class="vectorize-overview-item">
              <span>样例 SQL</span>
              <strong>{{ formatCompactCount(vectorizeOverviewData.exampleSqlVectorCount) }}</strong>
            </div>
          </div>

          <div class="vectorize-overview-section-title">全局统计</div>
          <div class="vectorize-overview-breakdown">
            <div class="vectorize-overview-item">
              <span>全局术语</span>
              <strong>{{ formatCompactCount(vectorizeOverviewData.globalMetricTermVectorCount) }}</strong>
            </div>
            <div class="vectorize-overview-item">
              <span>全局样例</span>
              <strong>{{ formatCompactCount(vectorizeOverviewData.globalExampleSqlVectorCount) }}</strong>
            </div>
          </div>

          <div class="vectorize-overview-note">
            {{ vectorizeOverviewData.message || '仅展示概要统计，不展示具体向量明细' }}
          </div>
        </div>
        <div v-else class="empty-pane">暂无可展示的向量化数据概览</div>
      </a-spin>
    </a-modal>

    <a-modal
      v-model:open="tablePasteModalOpen"
      title="跨库复制表"
      :confirm-loading="tablePasteSubmitting"
      :ok-text="tt('开始复制')"
      :cancel-text="tt('取消')"
      @ok="confirmTablePaste"
      @cancel="closeTablePasteModal"
    >
      <a-form layout="vertical">
        <a-form-item label="源表">
          <div class="table-copy-summary">
            {{ tablePasteForm.sourceConnectionId }} / {{ tablePasteForm.sourceDatabaseName || '-' }} / {{ tablePasteForm.sourceTableName }}
          </div>
        </a-form-item>
        <a-form-item :label="tt('目标库')">
          <div class="table-copy-summary">
            {{ tablePasteForm.targetConnectionId }} / {{ tablePasteForm.targetDatabaseName || '-' }}
          </div>
        </a-form-item>
        <a-form-item :label="tt('目标表名')">
          <a-input
            v-model:value="tablePasteForm.targetTableName"
            maxlength="128"
            :placeholder="tt('请输入目标表名')"
            @pressEnter="confirmTablePaste"
          />
        </a-form-item>
        <a-form-item v-if="tablePasteForm.preferredCopyMode === 'STRUCTURE_AND_DATA'" :label="tt('复制数据')">
          <div class="table-copy-switch-row">
            <a-switch v-model:checked="tablePasteForm.copyData" />
            <span class="table-copy-switch-text">{{ tt(tablePasteForm.copyData ? '复制结构和数据' : '仅复制结构') }}</span>
          </div>
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="tableCopyTaskModalOpen"
      title="表复制进度"
      :footer="null"
      :mask-closable="false"
      :closable="tableCopyTaskInfo?.status !== 'PENDING' && tableCopyTaskInfo?.status !== 'RUNNING'"
      @cancel="closeTableCopyTaskModal"
    >
      <div v-if="tableCopyTaskInfo" class="table-copy-task-panel">
        <div class="table-copy-task-summary">
          <div><span>源表</span><strong>{{ tableCopyTaskInfo.sourceDatabaseName || '-' }} / {{ tableCopyTaskInfo.sourceTableName }}</strong></div>
          <div><span>目标表</span><strong>{{ tableCopyTaskInfo.targetDatabaseName || '-' }} / {{ tableCopyTaskInfo.targetTableName }}</strong></div>
          <div><span>状态</span><strong>{{ tableCopyTaskInfo.status }}</strong></div>
          <div><span>阶段</span><strong>{{ tableCopyTaskInfo.stage }}</strong></div>
        </div>
        <a-progress
          :percent="Math.max(0, tableCopyTaskInfo.progressPercent ?? 0)"
          :status="tableCopyTaskInfo.status === 'FAILED' ? 'exception' : tableCopyTaskInfo.status === 'SUCCESS' ? 'success' : 'active'"
        />
        <div class="table-copy-task-message">{{ tableCopyTaskInfo.message || '-' }}</div>
        <div class="table-copy-task-stats">
          <span>已复制: {{ tableCopyTaskInfo.copiedRows ?? 0 }}</span>
          <span>总行数: {{ tableCopyTaskInfo.totalRows ?? 0 }}</span>
          <span>更新时间: {{ formatTime(tableCopyTaskInfo.updatedAt) }}</span>
        </div>
      </div>
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
  ArrowRightOutlined,
  BulbFilled,
  BulbOutlined,
  CheckOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  CodeOutlined,
  CopyOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  EditOutlined,
  ExperimentOutlined,
  EyeOutlined,
  FilterOutlined,
  FolderAddOutlined,
  FolderOpenOutlined,
  HddOutlined,
  HighlightOutlined,
  HistoryOutlined,
  LinkOutlined,
  LoadingOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MessageOutlined,
  FileProtectOutlined,
  MinusOutlined,
  MinusCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReadOutlined,
  ReloadOutlined,
  SaveOutlined,
  SearchOutlined,
  SendOutlined,
  SettingOutlined,
  SyncOutlined,
  ToolOutlined,
  UnorderedListOutlined
} from '@ant-design/icons-vue';
import {Editor as MonacoEditor} from '@guolao/vue-monaco-editor';
import type * as MonacoApi from 'monaco-editor';
import {computed, nextTick, ref, watch} from 'vue';
import {translateText, useAppI18n, type AppLocale} from '../../../i18n';
import QueryChartPanel from '../../../components/QueryChartPanel.vue';
import ErDiagramPanel from '../../../components/ErDiagramPanel.vue';
import TableEditor from '../../../components/TableEditor.vue';
import StudioConnectionContextBar from './StudioConnectionContextBar.vue';
import TableDataVirtualGrid from './TableDataVirtualGrid.vue';
import addQueryIcon from '../../../assets/icons/add_query.png';
import createTableIcon from '../../../assets/icons/create-table.png';
import createGroupIcon from '../../../assets/icons/tree-add-folder.png';
import createConnectionIcon from '../../../assets/icons/tree-connected.png';
import erEntryIcon from '../../../assets/icons/ER.png';
import createRedisKeyIcon from '../../../assets/icons/key.svg';
import createViewIcon from '../../../assets/icons/tree-view.png';
import createFunctionIcon from '../../../assets/icons/tree-function.png';
import refreshIcon from '../../../assets/icons/refresh.svg';
import repickIcon from '../../../assets/icons/repick.svg';
import vectorIcon from '../../../assets/icons/vector.svg';
import analyzeIcon from '../../../assets/icons/analyze.svg';
import chartIcon from '../../../assets/icons/chart.svg';
import exampleIcon from '../../../assets/icons/example.svg';
import executeIcon from '../../../assets/icons/execute.svg';
import explainIcon from '../../../assets/icons/explain.svg';
import exportIcon from '../../../assets/icons/export.svg';
import dialogIcon from '../../../assets/icons/dialog.svg';
import prettyIcon from '../../../assets/icons/pretty.svg';
import saveQueryIcon from '../../../assets/icons/save.svg';
import sendQueryIcon from '../../../assets/icons/send.svg';
import sqlActionIcon from '../../../assets/icons/sql.svg';
import stopActionIcon from '../../../assets/icons/stop.png';
import tableIcon from '../../../assets/icons/table.svg';
import interpretIcon from '../../../assets/icons/interpret.svg';
import type {StudioController} from '../composables/useStudioController';

const {currentLocale, antLocale, localeSelectOptions, setLocale, useDomI18n} = useAppI18n();
useDomI18n();

function tt(text: string) {
  void currentLocale.value;
  return translateText(text);
}

function buildAutomationId(...segments: Array<string | number | null | undefined>) {
  const normalized = segments
    .map((segment) => String(segment ?? '').trim())
    .filter(Boolean)
    .map((segment) => segment.replace(/[^A-Za-z0-9_-]+/g, '-').replace(/^-+|-+$/g, ''))
    .filter(Boolean);
  return normalized.join('-') || 'studio-empty';
}

function treeNodeTestId(dataRef: {
  nodeType?: string;
  connectionId?: number;
  databaseName?: string;
  objectName?: string;
}, title: string) {
  if (dataRef.nodeType === 'connection') {
    return buildAutomationId('studio-tree-node-connection', dataRef.connectionId);
  }
  if (dataRef.nodeType === 'database') {
    return buildAutomationId('studio-tree-node-database', dataRef.databaseName || title);
  }
  return buildAutomationId('studio-tree-node', dataRef.nodeType || 'unknown', title);
}

function objectRowTestId(record: { objectType: string; objectName: string }) {
  return buildAutomationId('studio-object-row', record.objectType, record.objectName);
}

function contextMenuActionTestId(actionId: string | number) {
  return buildAutomationId('studio-context-menu-item', actionId);
}

const embeddingModelRepoUrl = 'https://huggingface.co/hooman650/bge-m3-onnx-o4/tree/main';
const rerankModelRepoUrl = 'https://huggingface.co/swulling/bge-reranker-base-onnx-o4/tree/main';

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
    connectionGroups,
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
    savedQueryCache,
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
    groupModalOpen,
    groupModalSubmitting,
    groupForm,
    saveQueryModalOpen,
    saveQuerySubmitting,
    saveQueryTitle,
    truncateTableModalOpen,
    truncateTableName,
    dropTableModalOpen,
    dropTableName,
    namespaceModalOpen,
    namespaceModalSubmitting,
    namespaceForm,
    browserDetailCollapsed,
    tablePasteModalOpen,
    tablePasteSubmitting,
    tablePasteForm,
    tableCopyTaskModalOpen,
    tableCopyTaskInfo,
    renameTableModalOpen,
    renameTableSubmitting,
    renameTableForm,
    aiConfigModalOpen,
    aiConfigActiveTab,
    uiTheme,
    selectedAiModel,
    activeWorkbenchTab,
    queryTabs,
    erTabs,
    tableEditorTabs,
    tableDataTabs,
    objectDefinitionEditorTabs,
    knowledgeTabs,
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
    kvObjectDetail,
    kvObjectDetailLoading,
    redisBrowserRows,
    redisBrowserLoading,
    redisExpandedRowKeys,
    redisKeyModalOpen,
    redisKeyModalSubmitting,
    redisKeyModalMode,
    redisKeyForm,
    objectDefinitionDetailLoading,
    queryEditorPaneRef,
    queryEditorSectionHeight,
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
    contextMenuActions,
    connectionForm,
    connectionPreviewDbOptions,
    connectionPreviewLoading,
    connectionPreviewError,
    aiConfigForm,
    ragConfigForm,
    ragLocalOnnxEnabled,
    ragProviderTypeOptions,
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
    activeQueryEditorLanguage,
    activeConnectionIsKv,
    activeQueryContextUsage,
    activeErTab,
    activeTableEditorTab,
    activeTableDataTab,
    activeObjectDefinitionEditorTab,
    activeKnowledgeTab,
    activeErConfidenceThreshold,
    activeErAiRelationTotal,
    activeErDisplayGraph,
    activeErForeignKeyRelations,
    activeErAiRelations,
    activeErManualRelations,
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
    canCreateView,
    canCreateFunction,
    connectionSelectOptions,
    connectionGroupOptions,
    connectionFormDbTypeSpec,
    isMultiDatabaseFormType,
    connectionPreviewSelectOptions,
    canPreviewDatabases,
    connectionTreeData,
    objectRows,
    activeConnectionIsRedis,
    selectedObjectRecord,
    selectedTreeGroup,
    selectedTreeDetail,
    selectedTreeConnection,
    selectedTreeDatabaseStatusLabel,
    selectedTreeDatabaseTableCount,
    selectedTreeDatabaseColumnCount,
    createTableSqlText,
    createTableSqlHighlighted,
    objectDefinitionSqlText,
    objectDefinitionSqlHighlighted,
    tableEditorSqlHighlighted,
    filteredObjectRows,
    objectColumns,
    tableScrollY,
    queryResultScrollY,
    aiModelOptions,
    workbenchStyle,
    activeStatementResult,
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
    activeSeriesFieldOptions,
    emptyManualChartConfig,
    cloneChartConfig,
    isNumericField,
    handleManualChartTypeChange,
    handleManualChartXAxisChange,
    handleManualChartYFieldsChange,
    handleManualChartSingleYFieldChange,
    handleManualChartSeriesFieldChange,
    setupManualChartConfigByResult,
    setActiveStatementResult,
    setQueryResultViewMode,
    canExportActiveQueryResult,
    queryResultExportTooltip,
    resultTabTitle,
    buildConnectionNode,
    buildCategoryChildren,
    getCategoryChildren,
    requiresDatabaseLayer,
    isMultiDatabaseType,
    isKvConnectionId,
    getDatabaseNamePlaceholder,
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
    toggleBrowserDetailCollapsed,
    openCreateModal,
    openEditModal,
    closeNamespaceModal,
    confirmNamespaceModal,
    openAiQueryTab,
    openSaveQueryModal,
    saveCurrentQuery,
    closeQueryTab,
    touchErTab,
    toggleErDetailCollapsed,
    handleErLayoutModeChange,
    normalizeErRelationDirection,
    normalizeErRelationType,
    erRelationKey,
    erRelationArrow,
    erRelationDirectionLabel,
    formatErRelationConfidence,
    normalizeErRelationConfidence,
    erRelationReasonPreview,
    setErSelectedRelation,
    handleErGraphLayoutChange,
    handleErRelationRouteChange,
    appendErManualRelation,
    removeErRelation,
    closeErTab,
    closeTableEditorTab,
    closeTableDataTab,
    closeObjectDefinitionEditorTab,
    openNewTableEditor,
    openNewObjectDefinitionEditor,
    openEditTableEditor,
    openTableDataTabByObject,
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
    detailOutputEnabledForTab,
    toggleMessageTraceExpanded,
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
    openCreateGroupModal,
    openRenameGroupModal,
    closeGroupModal,
    confirmGroupModal,
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
    refreshCurrentPageObjects,
    loadCategoryObjects,
    loadTreeChildrenByKey,
    handleTreeSelect,
    handleTreeExpand,
    handleConnectionTreeDrop,
    handleTreeRightClick,
    handleTreeNodeDblclick,
    closeContextMenu,
    triggerContextAction,
    confirmTablePaste,
    closeTablePasteModal,
    closeTableCopyTaskModal,
    confirmRenameTable,
    closeRenameTableModal,
    openVectorizeOverview,
    enqueueDatabaseRevectorize,
    vectorizeSingleTable,
    interruptDatabaseVectorize,
    onObjectRow,
    selectObject,
    loadObjectDetail,
    clearObjectDetail,
    openCreateRedisKeyModal,
    openEditRedisKeyModal,
    closeRedisKeyModal,
    confirmRedisKeyModal,
    deleteRedisKey,
    handleRedisBrowserExpand,
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
    startResizeQueryEditorSections,
    openQueryTabByObject,
    getDesktopBridge,
    pickRagEmbeddingModelDir,
    pickRagRerankModelDir,
    openAiConfigModal,
    saveAiConfig,
    databaseOptionsForTab,
    databaseOptionsForTableEditorTab,
    databaseOptionsForTableDataTab,
    queryTabConnectionName,
    handleQueryConnectionChange,
    handleQueryDatabaseChange,
    handleTableEditorConnectionChange,
    handleTableEditorDatabaseChange,
    queryTabDbType,
    handleTableDataConnectionSelectorChange,
    handleTableDataDatabaseSelectorChange,
    formatObjectDefinitionSql,
    handleObjectDefinitionSqlChange,
    saveObjectDefinition,
    reloadObjectDefinition,
    copyObjectDefinitionSql,
    tableDataFilterOperatorOptions,
    tableDataSortDirectionOptions,
    reloadTableDataForTab,
    toggleTableDataFilterPanel,
    toggleTableDataDetailCollapsed,
    addTableDataFilter,
    removeTableDataFilter,
    addTableDataSort,
    removeTableDataSort,
    applyTableDataFilters,
    prevTableDataPage,
    nextTableDataPage,
    updateTableDataPageSize,
    selectTableDataRow,
    startTableDataCellEdit,
    stopTableDataCellEdit,
    updateTableDataCell,
    tableDataColumnEditorType,
    selectedTableDataRow,
    addTableDataRow,
    deleteSelectedTableDataRow,
    submitTableDataChanges,
    discardTableDataChanges,
    tableDataDisplayRows,
    tableDataDisplayColumns,
    tableDataScrollX,
    isTableDataPrimaryKeyColumn,
    resolveSqlForAction,
    resolveSelectedSqlSnippet,
    queryUnitLabelByDbType,
    generateActionLabelByDbType,
    explainActionLabelByDbType,
    analyzeActionLabelByDbType,
    canGenerateChartForTab,
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
    knowledgeActiveNode,
    knowledgeLoading,
    knowledgeSaving,
    knowledgeRebuildLoading,
    knowledgeKeyword,
    knowledgeFilterConnectionId,
    knowledgeFilterDatabaseName,
    knowledgeConnectionOptions,
    knowledgeFilterDatabaseOptions,
    knowledgeTermTargetDatabaseOptions,
    knowledgeExampleTargetDatabaseOptions,
    knowledgeGlobalTermCount,
    knowledgeGlobalExampleCount,
    knowledgeTermItems,
    knowledgeExampleItems,
    filteredKnowledgeTermItems,
    filteredKnowledgeExampleItems,
    knowledgeVisibleExampleTermOptions,
    knowledgeTermForm,
    knowledgeExampleForm,
    knowledgeScopeOptions,
    openKnowledgeNode,
    closeKnowledgeTab,
    handleKnowledgeFilterConnectionChange,
    handleKnowledgeFilterDatabaseChange,
    handleKnowledgeTermScopeChange,
    handleKnowledgeTermTargetConnectionChange,
    handleKnowledgeTermTargetDatabaseChange,
    handleKnowledgeExampleScopeChange,
    handleKnowledgeExampleTargetConnectionChange,
    handleKnowledgeExampleTargetDatabaseChange,
    resetKnowledgeTermForm,
    resetKnowledgeExampleForm,
    selectKnowledgeTerm,
    selectKnowledgeExample,
    saveKnowledgeTerm,
    removeKnowledgeTerm,
    saveKnowledgeExample,
    removeKnowledgeExample,
    saveQueryAsExampleModalOpen,
    saveQueryAsExampleSubmitting,
    saveQueryAsExampleDescription,
    saveQueryAsExampleContextText,
    openSaveQueryAsExampleModal,
    confirmSaveQueryAsExample,
    rebuildKnowledgeVectors,
    loadKnowledgeData,
    knowledgeScopeLabel,
    knowledgeScopeColor,
    generateChartFromMessage,
    generateManualChartForTab,
    downloadActiveChart,
    exportResultTab,
    downloadMessageChart,
    downloadActiveErDiagram,
    hydrateHistoryChartImages,
    editChartFromHistory,
    explainSqlForTab,
    formatSqlForTab,
    RISK_EXECUTION_CANCELLED,
    SQL_EXECUTION_ABORTED,
    connectionEnvLabel,
    ensureRiskConfirmedBeforeExecute,
    executeSqlForTab,
    repairSqlForTab,
    exportActiveQueryResult,
    riskColor,
    normalizeRiskLevel,
    ensureConnection,
    runSafely,
    formatSize,
    formatCompactCount,
    formatTime,
    formatDurationMs,
    formatVectorizeProvider,
    queryPromptAssist,
    handleChatComposerInput,
    handleChatComposerCursorChange,
    handleChatComposerKeydown,
    closeQueryPromptAssist,
    setQueryPromptAssistActive,
    applyPromptAssistOption,
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
    connectionStatusClass,
    connectionStatusText,
    nodeIconComponent,
    quoteSqlIdentifier,
    buildColumnSqlDefinition,
    buildCreateTableSql,
    escapeHtml,
    highlightSqlForDisplay,
    copyTextContent,
    copyCreateTableSql,
    copyTableEditorSql,
    browserObjectIconSrc,
    treeTitleIconSrc,
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

function isEnglishLocale() {
  return currentLocale.value === 'en-US';
}

function handleEmbeddingProviderTypeChange(value: 'LOCAL_ONNX' | 'ONLINE_OPENAI_COMPAT') {
  ragConfigForm.ragEmbeddingProviderType = value;
  if (!ragLocalOnnxEnabled || !ragConfigForm.ragRerankEnabled) {
    return;
  }
  ragConfigForm.ragRerankProviderType = value;
}

function handleRerankEnabledChange(enabled: boolean) {
  ragConfigForm.ragRerankEnabled = enabled;
  if (!enabled || !ragLocalOnnxEnabled) {
    return;
  }
  ragConfigForm.ragRerankProviderType = ragConfigForm.ragEmbeddingProviderType;
}

const localEmbeddingTipTitle = computed(() => (
  isEnglishLocale() ? 'Local embedding model download' : '本地向量模型下载'
));

const localEmbeddingTipDescription = computed(() => (
  isEnglishLocale()
    ? 'Download the BGE-M3 ONNX model repository first, then select the cloned directory here.'
    : '请先下载 BGE-M3 ONNX 模型仓库，再在这里选择 clone 后的目录。'
));

const localEmbeddingTipLinkText = computed(() => (
  isEnglishLocale() ? 'Open embedding model repository' : '打开向量模型仓库'
));

const localRerankTipTitle = computed(() => (
  isEnglishLocale() ? 'Local rerank model download' : '本地 Rerank 模型下载'
));

const localRerankTipDescription = computed(() => (
  isEnglishLocale()
    ? 'Download the BGE reranker ONNX model repository first, then select the cloned directory here.'
    : '请先下载 BGE Reranker ONNX 模型仓库，再在这里选择 clone 后的目录。'
));

const localRerankTipLinkText = computed(() => (
  isEnglishLocale() ? 'Open rerank model repository' : '打开 Rerank 模型仓库'
));

async function openExternalLink(url: string) {
  const bridge = typeof window !== 'undefined'
    ? (window as Window & { sqlCopilotDesktop?: { openExternal?: (value?: string) => Promise<boolean> } }).sqlCopilotDesktop
    : null;
  if (bridge?.openExternal) {
    await bridge.openExternal(url);
    return;
  }
  window.open(url, '_blank', 'noopener,noreferrer');
}

async function openPrivacyPolicy() {
  const bridge = typeof window !== 'undefined'
    ? (window as Window & { sqlCopilotDesktop?: { openPrivacyPolicy?: () => Promise<boolean> } }).sqlCopilotDesktop
    : null;
  if (bridge?.openPrivacyPolicy) {
    await bridge.openPrivacyPolicy();
    return;
  }
  window.open('./privacy-policy.html', '_blank', 'noopener,noreferrer');
}

const queryPromptAssistListRef = ref<HTMLElement | null>(null);
const queryPromptAssistItemRefMap = new Map<string, HTMLElement>();

function bindQueryPromptAssistItemRef(element: unknown, key: string) {
  if (element instanceof HTMLElement) {
    queryPromptAssistItemRefMap.set(key, element);
    return;
  }
  queryPromptAssistItemRefMap.delete(key);
}

function syncQueryPromptAssistActiveIntoView() {
  if (!queryPromptAssist.visible || !queryPromptAssist.items.length) {
    return;
  }
  const activeItem = queryPromptAssist.items[queryPromptAssist.activeIndex];
  if (!activeItem) {
    return;
  }
  const target = queryPromptAssistItemRefMap.get(activeItem.key);
  if (!target || !queryPromptAssistListRef.value) {
    return;
  }
  target.scrollIntoView({
    block: 'nearest',
    inline: 'nearest',
  });
}

watch(
  () => ({
    visible: queryPromptAssist.visible,
    tabKey: queryPromptAssist.tabKey,
    activeKey: queryPromptAssist.items[queryPromptAssist.activeIndex]?.key || '',
  }),
  () => {
    void nextTick().then(() => {
      syncQueryPromptAssistActiveIntoView();
    });
  },
  {flush: 'post'},
);

function handleKnowledgeExampleSqlEditorMount(
  editor: MonacoApi.editor.IStandaloneCodeEditor,
  monaco: typeof MonacoApi,
) {
  handleSqlEditorMount(editor, monaco, {
    getContext: () => ({
      connectionId: knowledgeExampleForm.connectionId || 0,
      databaseName: knowledgeExampleForm.databaseName || '',
    }),
    enableSelectionActions: false,
  });
}

function handleObjectDefinitionSqlEditorMount(
  editor: MonacoApi.editor.IStandaloneCodeEditor,
  monaco: typeof MonacoApi,
) {
  handleSqlEditorMount(editor, monaco, {
    getContext: () => {
      if (!activeObjectDefinitionEditorTab.value) {
        return null;
      }
      return {
        connectionId: activeObjectDefinitionEditorTab.value.connectionId,
        databaseName: activeObjectDefinitionEditorTab.value.databaseName,
      };
    },
    enableSelectionActions: false,
  });
}

function handleKnowledgeFilterConnectionSelectorChange(value: string | number | undefined) {
  knowledgeFilterConnectionId.value = value ? Number(value) : 0;
  void handleKnowledgeFilterConnectionChange();
}

function handleKnowledgeFilterDatabaseSelectorChange(value: string | undefined) {
  knowledgeFilterDatabaseName.value = value || '';
  void handleKnowledgeFilterDatabaseChange();
}

function handleKnowledgeTermScopeSelectorChange() {
  void handleKnowledgeTermScopeChange();
}

function handleKnowledgeTermTargetConnectionSelectorChange(value: string | number | undefined) {
  knowledgeTermForm.connectionId = value ? Number(value) : undefined;
  void handleKnowledgeTermTargetConnectionChange();
}

function handleKnowledgeTermTargetDatabaseSelectorChange(value: string | undefined) {
  knowledgeTermForm.databaseName = value || '';
  handleKnowledgeTermTargetDatabaseChange();
}

function handleKnowledgeExampleScopeSelectorChange() {
  void handleKnowledgeExampleScopeChange();
}

function handleKnowledgeExampleTargetConnectionSelectorChange(value: string | number | undefined) {
  knowledgeExampleForm.connectionId = value ? Number(value) : undefined;
  void handleKnowledgeExampleTargetConnectionChange();
}

function handleKnowledgeExampleTargetDatabaseSelectorChange(value: string | undefined) {
  knowledgeExampleForm.databaseName = value || '';
  handleKnowledgeExampleTargetDatabaseChange();
}

function handleLocaleChange(value: string) {
  setLocale(value as AppLocale);
}

const currentObjectRows = computed(() => (
  activeConnectionIsRedis.value && currentObjectType.value === 'tables'
    ? redisBrowserRows.value
    : filteredObjectRows.value
));

function isTreeNodeExpanded(dataRef: { key?: string | number }) {
  const key = String(dataRef.key || '');
  return !!key && expandedTreeKeys.value.includes(key);
}

function isRedisRowExpanded(record: { redisNodeType?: string; nodeKey?: string }) {
  return record.redisNodeType === 'PATH'
    && !!record.nodeKey
    && redisExpandedRowKeys.value.includes(record.nodeKey);
}

function objectRowIconSrc(record: {
  redisNodeType?: string;
  nodeKey?: string;
  objectType?: string;
}) {
  return browserObjectIconSrc(record as never, {
    expanded: isRedisRowExpanded(record),
  });
}

function redisRowExpandable(record: { redisNodeType?: string }) {
  return record.redisNodeType === 'PATH';
}

function redisRowDepth(record: {
  redisNodeType?: string;
  fullPath?: string;
  objectName?: string;
}) {
  const rawPath = record.redisNodeType === 'KEY'
    ? (record.objectName || '')
    : (record.fullPath || '');
  const normalizedPath = rawPath.replace(/^:+/, '').replace(/:+$/, '').trim();
  if (!normalizedPath) {
    return 0;
  }
  const depth = normalizedPath.split(':').filter((segment) => !!segment).length - 1;
  if (record.redisNodeType === 'LOAD_MORE') {
    return depth + 1;
  }
  return Math.max(depth, 0);
}

function redisRowIndentStyle(record: {
  redisNodeType?: string;
  fullPath?: string;
  objectName?: string;
}) {
  const depth = redisRowDepth(record);
  if (depth <= 0) {
    return undefined;
  }
  return {
    paddingLeft: `${depth * 18}px`,
  };
}

function redisNodeTypeLabel(record: { redisNodeType?: string }) {
  if (record.redisNodeType === 'PATH') {
    return '目录';
  }
  if (record.redisNodeType === 'LOAD_MORE') {
    return '更多';
  }
  return '键';
}

function redisTtlLabel(record: { redisNodeType?: string; ttlSeconds?: number }) {
  if (record.redisNodeType !== 'KEY') {
    return '-';
  }
  return record.ttlSeconds != null && Number(record.ttlSeconds) >= 0 ? `${record.ttlSeconds}s` : '永久';
}

function redisRowIsActive(record: { redisNodeType?: string; objectName?: string; fullPath?: string }) {
  if (record.redisNodeType === 'KEY') {
    return selectedObjectName.value === record.objectName;
  }
  return false;
}

const redisDetailValueText = computed(() => (
  kvObjectDetail.value?.editorPayload
  || kvObjectDetail.value?.sampleJson
  || `-- ${tt('暂无数据')}`
));

const redisEditorPlaceholder = computed(() => {
  if (redisKeyForm.valueType === 'string') {
    return tt('请输入字符串值');
  }
  if (redisKeyForm.valueType === 'hash') {
    return '{\n  "field": "value"\n}';
  }
  if (redisKeyForm.valueType === 'zset') {
    return '[\n  { "member": "item-1", "score": 1 }\n]';
  }
  return '[\n  "item-1",\n  "item-2"\n]';
});

function handleActiveQueryModelMenuClick(event: { key: string | number }) {
  if (!activeQueryTab.value) {
    return;
  }
  activeQueryTab.value.selectedAiModel = String(event.key);
}

function handleQueryConnectionSelectorChange(
  tab: (typeof queryTabs.value)[number],
  value: string | number,
) {
  tab.connectionId = Number(value);
  void handleQueryConnectionChange(tab);
}

function handleQueryDatabaseSelectorChange(
  tab: (typeof queryTabs.value)[number],
  value: string,
) {
  tab.databaseName = value;
  handleQueryDatabaseChange(tab);
}

function handleTableEditorConnectionSelectorChange(
  tab: (typeof tableEditorTabs.value)[number],
  value: string | number,
) {
  tab.connectionId = Number(value);
  void handleTableEditorConnectionChange(tab);
}

function handleTableEditorDatabaseSelectorChange(
  tab: (typeof tableEditorTabs.value)[number],
  value: string,
) {
  tab.databaseName = value;
  handleTableEditorDatabaseChange(tab);
}
</script>

<style scoped>
.rag-local-tip {
  margin-top: 8px;
}

.rag-local-tip-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.rag-local-tip-body a {
  align-self: flex-start;
  word-break: break-all;
}

.tree-title-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.tree-title-subtext {
  color: var(--ant-color-text-description);
  font-size: 12px;
  line-height: 1.2;
}

.tree-title-placeholder {
  color: var(--ant-color-text-description);
}

.redis-browser-layout {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}

.redis-list-pane {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.redis-hierarchy-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: var(--ant-color-text-secondary);
}

.redis-toolbar-main,
.redis-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.redis-current-path {
  max-width: 60%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.redis-detail-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.is-path-row {
  font-weight: 500;
}

.redis-detail-value {
  max-height: 240px;
  overflow: auto;
}
</style>
