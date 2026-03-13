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
        <button class="tool-item top-action-btn" @click="openAiConfigModal" title="设置">
          <setting-outlined />
          <span>设置</span>
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
            <div class="pane-title-actions left-nav-panel-actions">
              <a-button size="small" type="text" @click="openCreateModal" title="新建连接">
                <template #icon>
                  <link-outlined />
                </template>
                新建连接
              </a-button>
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
                <div class="tree-title-row" @dblclick.stop="handleTreeNodeDblclick(dataRef)">
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
          </a-collapse-panel>
          <a-collapse-panel key="knowledge" header="知识中心">
            <button
              class="knowledge-nav-item"
              :class="{ 'is-active': activeKnowledgeTab?.node === 'example-sql' }"
              @click="openKnowledgeNode('example-sql')"
            >
              <span>样例SQL</span>
              <span>{{ knowledgeExampleItems.length }}</span>
            </button>
            <button
              class="knowledge-nav-item"
              :class="{ 'is-active': activeKnowledgeTab?.node === 'terms' }"
              @click="openKnowledgeNode('terms')"
            >
              <span>术语管理</span>
              <span>{{ knowledgeTermItems.length }}</span>
            </button>
          </a-collapse-panel>
        </a-collapse>
      </aside>

      <div class="pane-splitter pane-splitter-left" @mousedown="startResizeLeftPane" />

      <template v-if="activeWorkbenchTab === browserTabKey">
          <section class="pane pane-center browser-center-pane">
            <div class="center-toolbar">
              <div v-if="currentObjectType === 'tables'" class="center-toolbar-left">
                <a-button size="small" type="primary" :disabled="!canCreateTable" @click="openNewTableEditor()">
                  <template #icon><plus-outlined /></template>
                  新建表
                </a-button>
                <a-button size="small" :disabled="!workflow.connectionId" @click="openAiQueryTab()">
                  <template #icon><plus-outlined /></template>
                  新建查询
                </a-button>
                <a-tooltip :title="browserErEntryTooltip">
                  <a-button size="small" :disabled="!canOpenBrowserErFeature" @click="openErTableSelectModal()">
                    <template #icon><apartment-outlined /></template>
                    智能ER图
                  </a-button>
                </a-tooltip>
              </div>
              <div v-else-if="currentObjectType === 'views'" class="center-toolbar-left">
                <a-button
                  size="small"
                  type="primary"
                  :disabled="!canCreateView"
                  @click="openNewObjectDefinitionEditor(workflow.connectionId, getActiveDatabaseName(workflow.connectionId), 'views')"
                >
                  <template #icon><plus-outlined /></template>
                  新建视图
                </a-button>
                <a-button size="small" :disabled="!workflow.connectionId" @click="openAiQueryTab()">
                  <template #icon><plus-outlined /></template>
                  新建查询
                </a-button>
              </div>
              <div v-else-if="currentObjectType === 'functions'" class="center-toolbar-left">
                <a-button
                  size="small"
                  type="primary"
                  :disabled="!canCreateFunction"
                  @click="openNewObjectDefinitionEditor(workflow.connectionId, getActiveDatabaseName(workflow.connectionId), 'functions')"
                >
                  <template #icon><plus-outlined /></template>
                  新建函数
                </a-button>
                <a-button size="small" :disabled="!workflow.connectionId" @click="openAiQueryTab()">
                  <template #icon><plus-outlined /></template>
                  新建查询
                </a-button>
              </div>
              <div v-else-if="currentObjectType === 'queries'" class="center-toolbar-left">
                <a-button size="small" type="primary" :disabled="!workflow.connectionId" @click="openAiQueryTab()">
                  <template #icon><plus-outlined /></template>
                  新建查询
                </a-button>
              </div>
              <div class="center-toolbar-right">
                <a-input v-model:value="tableKeyword" size="small" :placeholder="currentObjectType === 'queries' ? '搜索保存查询' : '搜索表名'" allow-clear>
                  <template #prefix><search-outlined /></template>
                </a-input>
                <a-button size="small" @click="refreshCurrentPageObjects({ force: true })" title="刷新当前对象">
                  <reload-outlined />
                </a-button>
                <a-radio-group v-model:value="objectViewMode" size="small">
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
              <a-table
                v-if="objectViewMode === 'row'"
                class="object-list-table"
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
                    <div class="table-name-cell" :class="{ 'is-active': selectedObjectName === record.objectName, 'is-queryable': record.objectType === 'tables' || record.objectType === 'queries' }" @dblclick.stop="onObjectRow(record).onDblclick()">
                      <database-outlined />
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
                  v-for="item in filteredObjectRows"
                  :key="item.objectName"
                  class="object-card"
                  :class="{ 'is-active': selectedObjectName === item.objectName }"
                  @click="onObjectRow(item).onClick()"
                  @dblclick="onObjectRow(item).onDblclick()"
                  @contextmenu.prevent.stop="onObjectRow(item).onContextmenu($event)"
                >
                  <div class="object-card-title">{{ item.objectName }}</div>
                  <div class="object-card-meta">{{ currentObjectType === 'queries' ? '保存查询' : objectTypeLabel(item.objectType) }}</div>
                  <div v-if="currentObjectType !== 'queries'" class="object-card-vectorize" :class="databaseStatusClass(item.vectorizeStatus)">
                    <component :is="databaseStatusIcon(item.vectorizeStatus)" class="object-vectorize-icon" />
                    <span>{{ databaseStatusLabel(item.vectorizeStatus) }}</span>
                  </div>
                  <div class="object-card-desc">{{ item.description || '-' }}</div>
                </div>
              </div>
            </div>

            <div class="center-status">
              <span>对象: {{ filteredObjectRows.length }}</span>
              <span>类型: {{ currentObjectType === 'queries' ? '保存查询' : objectTypeLabel(currentObjectType) }}</span>
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

      <template v-else-if="activeKnowledgeTab">
        <StudioConnectionContextBar
          :connection-id="knowledgeConnectionId"
          :database-name="knowledgeDatabaseName"
          :connection-options="knowledgeConnectionOptions"
          :database-options="knowledgeDatabaseOptions"
          :database-disabled="!knowledgeConnectionId"
          @connection-change="handleKnowledgeConnectionSelectorChange"
          @database-change="handleKnowledgeDatabaseSelectorChange"
        />

        <section class="pane pane-center">
          <div class="pane-title">知识中心 · {{ knowledgeActiveNode === 'terms' ? '术语管理' : '样例SQL' }}</div>
          <div class="center-toolbar">
            <div class="center-toolbar-left">
              <a-button size="small" type="primary" @click="knowledgeActiveNode === 'terms' ? resetKnowledgeTermForm() : resetKnowledgeExampleForm()">
                <template #icon><plus-outlined /></template>
                新建{{ knowledgeActiveNode === 'terms' ? '术语' : '样例' }}
              </a-button>
              <a-button size="small" :loading="knowledgeRebuildLoading" @click="rebuildKnowledgeVectors">
                <template #icon><sync-outlined /></template>
                手动重建向量
              </a-button>
            </div>
            <div class="center-toolbar-right">
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
                <div class="knowledge-card-meta">关联术语 {{ item.termIds?.length || 0 }} · {{ formatTime(item.updatedAt) }}</div>
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
                <div class="detail-row"><span>连接</span><strong>{{ queryTabConnectionNameById(knowledgeConnectionId) || '-' }}</strong></div>
                <div class="detail-row"><span>数据库</span><strong>{{ knowledgeDatabaseName || '-' }}</strong></div>
                <div class="detail-row detail-row-description"><span>说明</span><strong>{{ knowledgeTermForm.description || '-' }}</strong></div>
              </div>
              <div class="detail-form-panel knowledge-form">
                <a-form layout="vertical" size="small">
                  <a-form-item label="作用域">
                    <a-select v-model:value="knowledgeTermForm.scope" :options="knowledgeScopeOptions" />
                  </a-form-item>
                  <a-form-item label="术语">
                    <a-input v-model:value="knowledgeTermForm.term" maxlength="120" />
                  </a-form-item>
                  <a-form-item label="说明">
                    <a-textarea v-model:value="knowledgeTermForm.description" :rows="5" />
                  </a-form-item>
                </a-form>
                <a-space class="detail-form-actions">
                  <a-button type="primary" size="small" :loading="knowledgeSaving" @click="saveKnowledgeTerm">保存</a-button>
                  <a-button size="small" @click="resetKnowledgeTermForm">重置</a-button>
                  <a-button v-if="knowledgeTermForm.id" danger size="small" :loading="knowledgeSaving" @click="removeKnowledgeTerm">删除</a-button>
                </a-space>
              </div>
            </div>

            <div v-else>
              <div class="detail-summary">
                <div class="detail-row"><span>样例</span><strong>{{ knowledgeExampleForm.description || '未命名样例' }}</strong></div>
                <div class="detail-row"><span>作用域</span><strong>{{ knowledgeScopeLabel(knowledgeExampleForm.scope) }}</strong></div>
                <div class="detail-row"><span>连接</span><strong>{{ queryTabConnectionNameById(knowledgeConnectionId) || '-' }}</strong></div>
                <div class="detail-row"><span>数据库</span><strong>{{ knowledgeDatabaseName || '-' }}</strong></div>
                <div class="detail-row"><span>关联术语</span><strong>{{ knowledgeExampleForm.termIds.length }}</strong></div>
                <div class="detail-row detail-row-description"><span>说明</span><strong>{{ knowledgeExampleForm.description || '-' }}</strong></div>
              </div>
              <div class="detail-form-panel knowledge-form">
                <a-form layout="vertical" size="small">
                  <a-form-item label="作用域">
                    <a-select v-model:value="knowledgeExampleForm.scope" :options="knowledgeScopeOptions" />
                  </a-form-item>
                  <a-form-item label="关联术语">
                    <a-select
                      v-model:value="knowledgeExampleForm.termIds"
                      mode="multiple"
                      :options="knowledgeTermItems.map((item) => ({ label: item.term, value: item.id }))"
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
                  <a-button type="primary" size="small" :loading="knowledgeSaving" @click="saveKnowledgeExample">保存</a-button>
                  <a-button size="small" @click="resetKnowledgeExampleForm">重置</a-button>
                  <a-button v-if="knowledgeExampleForm.id" danger size="small" :loading="knowledgeSaving" @click="removeKnowledgeExample">删除</a-button>
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
              <span>数据浏览 · {{ activeTableDataTab.tableName }}</span>
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
                <span>筛选</span>
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
                  placeholder="过滤值"
                />
                <a-button size="small" type="text" danger class="table-data-icon-btn" @click="removeTableDataFilter(activeTableDataTab, filter.key)">
                  <template #icon><delete-outlined /></template>
                </a-button>
              </div>
            </div>

            <div class="table-data-filter-block">
              <div class="table-data-filter-head">
                <span>排序方式</span>
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
                应用筛选 & 排序
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
                <a-button
                  size="small"
                  type="text"
                  class="btn-mini"
                  :disabled="activeObjectDefinitionEditorTab.loading || activeObjectDefinitionEditorTab.saving"
                  @click="reloadObjectDefinition(activeObjectDefinitionEditorTab)"
                >
                  <template #icon><sync-outlined /></template>
                  刷新
                </a-button>
                <a-button
                  size="small"
                  type="primary"
                  class="btn-execute"
                  :loading="activeObjectDefinitionEditorTab.saving"
                  :disabled="activeObjectDefinitionEditorTab.loading || !activeObjectDefinitionEditorTab.dirty"
                  @click="saveObjectDefinition(activeObjectDefinitionEditorTab)"
                >
                  <template #icon><play-circle-outlined /></template>
                  保存
                </a-button>
                <a-button
                  size="small"
                  type="text"
                  class="btn-mini"
                  :disabled="activeObjectDefinitionEditorTab.loading || activeObjectDefinitionEditorTab.saving"
                  @click="formatObjectDefinitionSql(activeObjectDefinitionEditorTab)"
                >
                  <template #icon><highlight-outlined /></template>
                  美化 SQL
                </a-button>
                <a-button size="small" type="text" class="btn-mini" @click="copyObjectDefinitionSql(activeObjectDefinitionEditorTab)">
                  <template #icon><copy-outlined /></template>
                  复制
                </a-button>
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
                            <span v-if="activeQueryTab.sqlExecuting" class="stop-square-icon" aria-hidden="true" />
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
            <div class="query-chat-composer-input-wrap">
              <a-textarea
                v-model:value="activeQueryTab.prompt"
                :rows="4"
                placeholder="例如：查询近 7 天订单量，并按天聚合"
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
                          <span class="query-chat-settings-desc">自动判断生成、解释、分析等动作</span>
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
                    <template #icon><span class="stop-square-icon" aria-hidden="true" /></template>
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

        <aside v-if="activeQueryTab" ref="queryEditorPaneRef" class="pane pane-right query-editor-pane">
          <div class="pane-title pane-title-with-action">
            <div class="pane-title-actions query-editor-header-actions">
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
                    <span v-if="activeQueryTab.sqlExecuting" class="stop-square-icon" aria-hidden="true" />
                    <play-circle-outlined v-else />
                  </template>
                </a-button>
              </a-tooltip>
              <a-tooltip v-if="activeQueryTab.lastExecuteFailed" title="自动修复">
                <a-button size="small" class="sql-action-icon-btn" @click="repairSqlForTab(activeQueryTab)">
                  <template #icon><tool-outlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip :title="activeQueryTab.selectedSqlText ? '美化选中的SQL' : '美化 SQL'">
                <a-button
                  size="small"
                  :class="['sql-action-icon-btn', { 'is-selection-active': !!activeQueryTab.selectedSqlText }]"
                  @click="formatSqlForTab(activeQueryTab)"
                >
                  <template #icon><highlight-outlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip title="导出结果（CSV）">
                <a-button size="small" class="sql-action-icon-btn" @click="exportCsvForTab(activeQueryTab)">
                  <template #icon><download-outlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip title="保存查询">
                <a-button size="small" class="sql-action-icon-btn" @click="openSaveQueryModal(activeQueryTab)">
                  <template #icon><save-outlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip title="保存为样例 SQL">
                <a-button size="small" class="sql-action-icon-btn" @click="openSaveQueryAsExampleModal(activeQueryTab)">
                  <template #icon><hdd-outlined /></template>
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
            </div>
            <div class="pane-title-actions query-memory-title-actions">
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
              language="sql"
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
                class="query-result-table"
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
            <div class="query-result-footer">共 {{ activeResultRows.length }} 行</div>
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
      title="设置"
      width="760px"
      ok-text="保存配置"
      cancel-text="取消"
      @ok="saveAiConfig"
    >
      <a-form layout="vertical">
        <a-tabs v-model:activeKey="aiConfigActiveTab">
          <a-tab-pane key="general" tab="界面设置">
            <a-row :gutter="12">
              <a-col :span="12">
                <a-form-item label="界面语言">
                  <a-select
                    :value="currentLocale"
                    :options="localeSelectOptions"
                    @update:value="handleLocaleChange"
                  />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="深色模式">
                  <a-switch :checked="isDarkTheme" @change="toggleTheme" />
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
                <a-form-item label="运行模式">
                  <a-select
                    v-model:value="ragConfigForm.ragEmbeddingProviderType"
                    :options="ragProviderTypeOptions"
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
                  <a-switch v-model:checked="ragConfigForm.ragRerankEnabled" />
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
      <template v-for="action in contextMenuActions" :key="action.id">
        <div v-if="action.children?.length" class="context-menu-submenu" :class="{ 'is-disabled': action.disabled }">
          <button
            class="context-menu-item context-menu-item-with-arrow"
            :class="{ danger: action.danger }"
            :disabled="action.disabled"
            type="button"
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
          @click="triggerContextAction(action.id)"
        >
          {{ action.label }}
        </button>
      </template>
    </div>

    <a-modal
      v-model:open="namespaceModalOpen"
      :title="namespaceForm.mode === 'create' ? `新建${namespaceForm.namespaceLabel}` : `编辑${namespaceForm.namespaceLabel}`"
      :ok-text="namespaceForm.mode === 'create' ? '创建' : '保存'"
      cancel-text="取消"
      :confirm-loading="namespaceModalSubmitting"
      @ok="confirmNamespaceModal"
      @cancel="closeNamespaceModal"
    >
      <a-form layout="vertical">
        <a-form-item v-if="namespaceForm.mode === 'rename'" :label="`原${namespaceForm.namespaceLabel}名称`">
          <a-input :value="namespaceForm.sourceNamespaceName" disabled />
        </a-form-item>
        <a-form-item :label="`${namespaceForm.mode === 'create' ? '新' : ''}${namespaceForm.namespaceLabel}名称`">
          <a-input
            v-model:value="namespaceForm.targetNamespaceName"
            :placeholder="`请输入${namespaceForm.namespaceLabel}名称`"
            maxlength="128"
            @pressEnter="confirmNamespaceModal"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="renameTableModalOpen"
      title="重命名表"
      ok-text="确认重命名"
      cancel-text="取消"
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
      title="保存查询"
      width="480px"
      ok-text="保存"
      :confirm-loading="saveQuerySubmitting"
      @ok="activeQueryTab && saveCurrentQuery(activeQueryTab)"
      @cancel="saveQueryModalOpen = false"
    >
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="saveQueryTitle" maxlength="80" show-count placeholder="请输入保存查询名称" />
        </a-form-item>
        <a-form-item label="保存位置">
          <div class="save-query-context">{{ activeQueryTab ? queryTabConnectionName(activeQueryTab) : '-' }} / {{ activeQueryTab?.databaseName || '未指定库' }}</div>
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="saveQueryAsExampleModalOpen"
      title="保存为样例 SQL"
      width="520px"
      ok-text="保存"
      :confirm-loading="saveQueryAsExampleSubmitting"
      @ok="confirmSaveQueryAsExample"
      @cancel="saveQueryAsExampleModalOpen = false"
    >
      <a-form layout="vertical">
        <a-form-item label="保存位置">
          <div class="save-query-context">{{ saveQueryAsExampleContextText }}</div>
        </a-form-item>
        <a-form-item label="说明">
          <a-textarea
            v-model:value="saveQueryAsExampleDescription"
            :rows="4"
            placeholder="补充这段样例 SQL 的用途、适用场景或注意事项"
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
      ok-text="开始复制"
      cancel-text="取消"
      @ok="confirmTablePaste"
      @cancel="closeTablePasteModal"
    >
      <a-form layout="vertical">
        <a-form-item label="源表">
          <div class="table-copy-summary">
            {{ tablePasteForm.sourceConnectionId }} / {{ tablePasteForm.sourceDatabaseName || '-' }} / {{ tablePasteForm.sourceTableName }}
          </div>
        </a-form-item>
        <a-form-item label="目标库">
          <div class="table-copy-summary">
            {{ tablePasteForm.targetConnectionId }} / {{ tablePasteForm.targetDatabaseName || '-' }}
          </div>
        </a-form-item>
        <a-form-item label="目标表名">
          <a-input
            v-model:value="tablePasteForm.targetTableName"
            maxlength="128"
            placeholder="请输入目标表名"
            @pressEnter="confirmTablePaste"
          />
        </a-form-item>
        <a-form-item v-if="tablePasteForm.preferredCopyMode === 'STRUCTURE_AND_DATA'" label="复制数据">
          <div class="table-copy-switch-row">
            <a-switch v-model:checked="tablePasteForm.copyData" />
            <span class="table-copy-switch-text">{{ tablePasteForm.copyData ? '复制结构和数据' : '仅复制结构' }}</span>
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
  DownloadOutlined,
  EditOutlined,
  ExperimentOutlined,
  EyeOutlined,
  FilterOutlined,
  FolderOpenOutlined,
  HddOutlined,
  HighlightOutlined,
  HistoryOutlined,
  LinkOutlined,
  LoadingOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MessageOutlined,
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
  TableOutlined,
  ToolOutlined,
  UnorderedListOutlined
} from '@ant-design/icons-vue';
import {Editor as MonacoEditor} from '@guolao/vue-monaco-editor';
import type * as MonacoApi from 'monaco-editor';
import {nextTick, ref, watch} from 'vue';
import {useAppI18n, type AppLocale} from '../../../i18n';
import QueryChartPanel from '../../../components/QueryChartPanel.vue';
import ErDiagramPanel from '../../../components/ErDiagramPanel.vue';
import TableEditor from '../../../components/TableEditor.vue';
import StudioConnectionContextBar from './StudioConnectionContextBar.vue';
import TableDataVirtualGrid from './TableDataVirtualGrid.vue';
import type {StudioController} from '../composables/useStudioController';

const {currentLocale, antLocale, localeSelectOptions, setLocale, useDomI18n} = useAppI18n();
useDomI18n();

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
    objectDefinitionSqlText,
    objectDefinitionSqlHighlighted,
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
    knowledgeActiveNode,
    knowledgeLoading,
    knowledgeSaving,
    knowledgeRebuildLoading,
    knowledgeKeyword,
    knowledgeConnectionId,
    knowledgeDatabaseName,
    knowledgeConnectionOptions,
    knowledgeDatabaseOptions,
    knowledgeTermItems,
    knowledgeExampleItems,
    filteredKnowledgeTermItems,
    filteredKnowledgeExampleItems,
    knowledgeTermForm,
    knowledgeExampleForm,
    knowledgeScopeOptions,
    openKnowledgeNode,
    closeKnowledgeTab,
    handleKnowledgeConnectionChange,
    handleKnowledgeDatabaseChange,
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
      connectionId: knowledgeConnectionId.value,
      databaseName: knowledgeDatabaseName.value,
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

function handleKnowledgeConnectionSelectorChange(value: string | number) {
  knowledgeConnectionId.value = Number(value);
  void handleKnowledgeConnectionChange();
}

function handleKnowledgeDatabaseSelectorChange(value: string) {
  knowledgeDatabaseName.value = value;
  void handleKnowledgeDatabaseChange();
}

function handleLocaleChange(value: string) {
  setLocale(value as AppLocale);
}

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
