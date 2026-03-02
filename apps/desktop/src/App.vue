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
      <button class="tool-item" :disabled="!canOpenHistory" @click="openToolbarHistory" title="会话历史">
        <history-outlined />
        <span>会话历史</span>
      </button>
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
        <section v-if="activeQueryTab" class="pane pane-center query-pane-center">
          <div class="pane-title">{{ activeQueryTab.title }}</div>

          <div class="query-meta-bar">
            <div class="query-meta-item">
              <span>连接</span>
              <a-select
                v-model:value="activeQueryTab.connectionId"
                size="small"
                style="min-width: 180px"
                :options="connectionSelectOptions"
                @change="handleQueryConnectionChange(activeQueryTab)"
              />
            </div>
            <div class="query-meta-item">
              <span>数据库</span>
              <a-select
                v-model:value="activeQueryTab.databaseName"
                size="small"
                style="min-width: 190px"
                :options="databaseOptionsForTab(activeQueryTab)"
                @change="handleQueryDatabaseChange(activeQueryTab)"
              />
            </div>
            <div class="query-meta-item">
              <span>模型</span>
              <a-select
                v-model:value="activeQueryTab.selectedAiModel"
                size="small"
                style="min-width: 190px"
                :options="aiModelOptions"
              />
            </div>
          </div>

          <div class="editor-group">
            <label class="label">自然语言需求</label>
            <a-textarea v-model:value="activeQueryTab.prompt" :rows="3" placeholder="例如：统计近 7 天订单量趋势" />
          </div>

          <div class="editor-group">
            <label class="label">SQL</label>
            <a-textarea v-model:value="activeQueryTab.sqlText" :rows="9" class="sql-editor" />
          </div>

          <div class="editor-actions">
            <a-space wrap>
              <a-button size="small" @click="generateSqlForTab(activeQueryTab)">AI 生成 SQL</a-button>
              <a-button size="small" @click="explainSqlForTab(activeQueryTab)">EXPLAIN</a-button>
              <a-button size="small" @click="evaluateRiskForTab(activeQueryTab)">风险评估</a-button>
              <a-button size="small" type="primary" @click="executeSqlForTab(activeQueryTab)">执行 SQL</a-button>
              <a-button size="small" @click="repairSqlForTab(activeQueryTab)">自动修复</a-button>
              <a-button size="small" @click="openHistoryModal(activeQueryTab.key)">会话历史</a-button>
              <a-button size="small" @click="exportJsonForTab(activeQueryTab)">导出 JSON</a-button>
              <a-button size="small" @click="exportCsvForTab(activeQueryTab)">导出 CSV</a-button>
            </a-space>
          </div>

          <div class="query-result-panel">
            <div class="query-result-title">查询结果</div>
            <a-table
              size="small"
              :pagination="false"
              :columns="activeResultColumns"
              :data-source="activeResultRows"
              :scroll="{ y: queryResultScrollY }"
              row-key="__rowKey"
            />
          </div>
        </section>

        <aside v-if="activeQueryTab" class="pane pane-right query-pane-right">
          <div class="pane-title">查询会话</div>

          <div class="query-connection-card">
            <div class="query-card-title">当前连接</div>
            <div class="query-card-row"><span>连接:</span><strong>{{ queryTabConnectionName(activeQueryTab) }}</strong></div>
            <div class="query-card-row"><span>数据库:</span><strong>{{ activeQueryTab.databaseName || '-' }}</strong></div>
            <div class="query-card-row"><span>Session:</span><strong>{{ activeQueryTab.sessionId }}</strong></div>
          </div>

          <div class="risk-box">
            <div class="risk-header">
              <span>风险评估</span>
              <a-tag v-if="activeQueryTab.riskInfo" :color="riskColor(activeQueryTab.riskInfo.riskLevel)">{{ activeQueryTab.riskInfo.riskLevel }}</a-tag>
            </div>
            <ul class="risk-list">
              <li v-for="item in activeQueryTab.riskInfo?.riskItems ?? []" :key="item.ruleCode">
                <span class="risk-code">{{ item.ruleCode }}</span>
                <span>{{ item.description }}</span>
              </li>
              <li v-if="!activeQueryTab.riskInfo">尚未评估</li>
            </ul>
          </div>

          <div class="sql-preview">
            <div class="preview-title">Explain / 执行反馈</div>
            <div class="preview-content">
              <div>Explain: {{ activeQueryTab.explainResult?.summary ?? '-' }}</div>
              <div>耗时: {{ activeQueryTab.executeResult?.executionMs ?? '-' }} ms</div>
              <div>影响行数: {{ activeQueryTab.executeResult?.affectedRows ?? '-' }}</div>
              <div>状态: {{ activeQueryTab.executeResult?.message ?? '-' }}</div>
            </div>
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
      v-model:open="historyModalOpen"
      title="会话历史"
      width="860px"
      :footer="null"
      @cancel="historyModalOpen = false"
    >
      <a-spin :spinning="historyLoading">
        <a-list
          size="small"
          :data-source="queryHistoryList"
          :locale="{ emptyText: '暂无历史会话' }"
        >
          <template #renderItem="{ item }">
            <a-list-item class="history-item" @click="applyHistoryItem(item)">
              <div class="history-item-head">
                <span>Session: {{ item.sessionId || '-' }}</span>
                <span>{{ formatTime(item.createdAt) }}</span>
              </div>
              <div class="history-item-prompt">{{ item.promptText || '无自然语言输入' }}</div>
              <div class="history-item-sql">{{ item.sqlText }}</div>
            </a-list-item>
          </template>
        </a-list>
      </a-spin>
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
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import {
  AppstoreOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  CodeOutlined,
  DatabaseOutlined,
  EyeOutlined,
  FolderOpenOutlined,
  HddOutlined,
  HistoryOutlined,
  LoadingOutlined,
  MinusCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  SearchOutlined,
  SettingOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons-vue';
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
  ConnectionCreateReq,
  ExplainVO,
  QueryHistoryVO,
  RagDatabaseVectorizeStatusVO,
  RagConfigSaveReq,
  RagConfigVO,
  RagVectorizeEnqueueVO,
  RiskEvaluateVO,
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
}

const browserTabKey = 'browser';
const isMacOS = typeof navigator !== 'undefined' && /mac/i.test(navigator.platform);
let vectorizeStatusPollTimer: number | null = null;

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
const objectNameCache = ref<Record<string, string[]>>({});
const databaseListCache = ref<Record<number, string[]>>({});
const activeDatabaseMap = ref<Record<number, string>>({});
const databaseVectorizeStatusMap = ref<Record<string, RagDatabaseVectorizeStatusVO>>({});
const currentObjectType = ref<'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups'>('tables');
const objectViewMode = ref<'row' | 'grid'>('row');
const viewportHeight = ref(typeof window === 'undefined' ? 900 : window.innerHeight);
const historyModalOpen = ref(false);
const historyLoading = ref(false);
const queryHistoryList = ref<QueryHistoryVO[]>([]);
const historyTargetTabKey = ref('');
const aiConfigModalOpen = ref(false);
const aiConfigActiveTab = ref<'model' | 'embedding'>('model');
const selectedAiModel = ref('');
const activeWorkbenchTab = ref(browserTabKey);
const queryTabs = ref<QueryWorkspaceTab[]>([]);
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
  if (activeQueryTab.value?.connectionId) {
    return true;
  }
  return !!selectedConnection.value;
});

const isContextDatabaseVectorizing = computed(() =>
  contextMenu.targetType === 'database'
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
    gridTemplateColumns: '270px minmax(520px, 1fr) 390px',
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
    ellipsis: true,
  }));
});

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
    if (!activeDbName && databases.length) {
      activeDatabaseMap.value = {
        ...activeDatabaseMap.value,
        [conn.id]: databases[0],
      };
    }
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
  const databaseName = getActiveDatabaseName(workflow.connectionId);
  const models = aiModelOptions.value.map((item) => String(item.value)).filter((item) => !!item);
  const tab: QueryWorkspaceTab = {
    key: `query-${Date.now()}-${Math.round(Math.random() * 1000)}`,
    title: `AI查询 ${queryTabs.value.length + 1}`,
    connectionId: workflow.connectionId,
    databaseName,
    sessionId: `session-${Date.now()}`,
    prompt: workflow.prompt || `查询 ${selectedObjectName.value || '当前数据库'} 最近数据`,
    sqlText: workflow.sqlText,
    riskAckToken: '',
    riskInfo: null,
    executeResult: null,
    explainResult: null,
    selectedAiModel: models[0] ?? '',
  };
  queryTabs.value = [...queryTabs.value, tab];
  activeWorkbenchTab.value = tab.key;
  void runSafely(async () => {
    await prepareConnectionTreeData(tab.connectionId);
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

async function prepareConnectionTreeData(connectionId: number) {
  const connection = connections.value.find((item) => item.id === connectionId);
  if (!connection) {
    return;
  }
  if (requiresDatabaseLayer(connection)) {
    await loadDatabaseListForConnection(connectionId);
    const databases = databaseListCache.value[connectionId] ?? [];
    if (databases.length && !activeDatabaseMap.value[connectionId]) {
      activeDatabaseMap.value = {
        ...activeDatabaseMap.value,
        [connectionId]: databases[0],
      };
    }
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
  const list = await getApi<string[]>(`/api/schema/databases?connectionId=${connectionId}`);
  databaseListCache.value = {
    ...databaseListCache.value,
    [connectionId]: list,
  };
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
    void refreshAllVectorizeStatuses();
  }, 3000);
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
      return;
    }
    await refreshAllVectorizeStatuses(list.map((item) => item.id));
    if (!workflow.connectionId || !list.some((item) => item.id === workflow.connectionId)) {
      workflow.connectionId = list[0].id;
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
    tableNameCache.value = {
      ...tableNameCache.value,
      [tableCacheKey(workflow.connectionId, databaseName)]: (overview.tableSummaries ?? []).map((item) => item.tableName),
    };
    objectNameCache.value = {
      ...objectNameCache.value,
      [objectCacheKey(workflow.connectionId, databaseName, 'tables')]: (overview.tableSummaries ?? []).map((item) => item.tableName),
    };
    expandConnectionNode(workflow.connectionId);
  });
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
    contextMenu.x = Math.min(event.event.clientX, window.innerWidth - 170);
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
  contextMenu.x = Math.min(event.event.clientX, window.innerWidth - 170);
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

async function triggerContextAction(action: 'edit' | 'test' | 'sync' | 'delete' | 'revectorize') {
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

async function openToolbarHistory() {
  if (activeQueryTab.value) {
    await openHistoryModal(activeQueryTab.value.key);
    return;
  }
  await openHistoryModal();
}

async function openHistoryModal(targetTabKey?: string) {
  const connectionId = targetTabKey
    ? queryTabs.value.find((item) => item.key === targetTabKey)?.connectionId ?? 0
    : workflow.connectionId;
  if (!connectionId) {
    throw new Error('请先选择连接');
  }
  historyTargetTabKey.value = targetTabKey ?? '';
  historyModalOpen.value = true;
  await loadHistory(connectionId);
}

async function loadHistory(connectionId: number) {
  historyLoading.value = true;
  try {
    queryHistoryList.value = await getApi<QueryHistoryVO[]>(
      `/api/editor/history/list?connectionId=${connectionId}&limit=80`,
    );
  } finally {
    historyLoading.value = false;
  }
}

function applyHistoryItem(item: QueryHistoryVO) {
  if (historyTargetTabKey.value) {
    const tab = queryTabs.value.find((entry) => entry.key === historyTargetTabKey.value);
    if (tab) {
      tab.sessionId = item.sessionId || tab.sessionId;
      tab.prompt = item.promptText || tab.prompt;
      tab.sqlText = item.sqlText;
    }
  } else {
    workflow.prompt = item.promptText || workflow.prompt;
    workflow.sqlText = item.sqlText;
  }
  historyModalOpen.value = false;
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
  });
}

function handleQueryDatabaseChange(tab: QueryWorkspaceTab) {
  tab.riskAckToken = '';
}

async function generateSqlForTab(tab: QueryWorkspaceTab) {
  await runSafely(async () => {
    const generated = await postApi<AiGenerateSqlVO>('/api/ai/query/generate', {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      prompt: tab.prompt,
      databaseName: tab.databaseName || undefined,
      sqlSnippet: tab.sqlText || undefined,
      modelName: tab.selectedAiModel || undefined,
    });
    tab.sqlText = generated.sqlText;
    if (generated.reasoning) {
      message.info(generated.reasoning);
    }
    message.success('SQL 已生成');
  });
}

async function explainSqlForTab(tab: QueryWorkspaceTab) {
  await runSafely(async () => {
    tab.explainResult = await postApi<ExplainVO>('/api/sql/explain', {
      connectionId: tab.connectionId,
      sqlText: tab.sqlText,
    });
    tab.executeResult = null;
    message.success('EXPLAIN 完成');
  });
}

async function evaluateRiskForTab(tab: QueryWorkspaceTab) {
  await runSafely(async () => {
    const result = await postApi<RiskEvaluateVO>('/api/sql/risk/evaluate', {
      connectionId: tab.connectionId,
      sqlText: tab.sqlText,
    });
    tab.riskInfo = result;
    tab.riskAckToken = result.riskAckToken ?? '';
    message.info(`风险级别: ${result.riskLevel}`);
  });
}

async function executeSqlForTab(tab: QueryWorkspaceTab) {
  await runSafely(async () => {
    const result = await postApi<SqlExecuteVO>('/api/sql/execute', {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      sqlText: tab.sqlText,
      riskAckToken: tab.riskAckToken,
      operatorName: 'desktop-user',
    });
    tab.executeResult = result;
    tab.explainResult = null;
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

async function exportJsonForTab(tab: QueryWorkspaceTab) {
  await runSafely(async () => {
    const result = await postApi<{ filePath: string }>('/api/editor/result/export', {
      connectionId: tab.connectionId,
      sqlText: tab.sqlText,
      format: 'JSON',
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
});

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
