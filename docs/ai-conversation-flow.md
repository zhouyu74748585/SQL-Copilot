# AI 对话实现流程

## 一、系统架构总览

```mermaid
flowchart TB
    subgraph Frontend["【前端】"]
        A1[用户输入 Prompt]
        A2[SSE 流式接收]
        A3[流式事件处理]
    end

    subgraph Backend["【后端】"]
        B1[AiController<br/>api/ai/query]
        B2[AiServiceImpl]
        B3[意图识别]
        B4[上下文构建]
        B5[AI 生成]
    end

    subgraph Storage["【存储层】"]
        C1[(MySQL<br/>query_history)]
        C2[(MySQL<br/>memory_entry)]
        C3[(Qdrant<br/>向量数据库)]
    end

    A1 -->|POST generate/stream| B1
    B1 --> B2
    B2 --> B3
    B3 --> B4
    B4 --> B5
    B5 -->|SseEmitter| A2
    A2 --> A3

    B2 -->|queryHistory| C1
    B2 -->|persistMemory| C2
    B2 -->|upsertPoints| C3
```

## 二、对话完整时序图

```mermaid
sequenceDiagram
    participant U as 用户
    participant F as 前端<br/>StudioShell
    participant C as AiController
    participant S as AiServiceImpl
    participant I as 意图识别
    participant M as MemoryService
    participant Q as Qdrant
    participant AI as AI Provider

    U->>F: 输入 SQL/问题

    F->>C: POST /api/ai/query/generate/stream
    C->>S: processAiQuery(req)

    S->>I: identifyIntentLight()<br/>轻量级意图识别
    I-->>S: 轻量级意图 + 记忆信号

    alt 记忆信号触发
        S->>M: captureImmediateMemorySignal()<br/>立即记忆捕获
        M->>Q: upsertPoints()
        M->>C2: persistMemoryEntry()
    end

    S->>I: retrieveIntentHistoryContext()<br/>历史召回
    I-->>S: 历史召回结果

    S->>I: identifyIntentFinal()<br/>最终意图识别
    I-->>S: 最终意图 + 置信度

    S->>S: buildGenerationContext()<br/>构建生成上下文

    S->>AI: streamGenerate(prompt, context)<br/>流式生成

    loop 流式输出
        AI-->>S: delta token
        S-->>F: onEvent("content", delta)
    end

    AI-->>S: [DONE]
    S->>S: persistQueryHistory()<br/>保存对话历史

    S-->>F: [DONE]
    F-->>U: 展示结果
```

## 三、意图识别流程

```mermaid
flowchart TD
    Start([用户输入]) --> Step1["构建轻量级意图识别输入"]
    Step1 --> Step2["identifyIntentLight()<br/>使用轻量级 Prompt"]
    Step2 --> Step3{记忆信号<br/>signalConfidence >= 0.80?}

    Step3 -->|是<br/>CORRECTION/PRIORITY_HINT| Step4["captureImmediateMemorySignal()<br/>立即保存长期记忆"]
    Step3 -->|否| Step5["retrieveIntentHistoryContext()<br/>历史召回"]

    Step4 --> Step5

    Step5 --> Step6["构建最终意图识别输入"]
    Step6 --> Step7["identifyIntentFinal()<br/>使用完整 Prompt"]
    Step7 --> Step8(["返回最终意图<br/>INTENT_TYPE + confidence"])

    style Step4 fill:#ff6b6b,color:#fff
    style Step8 fill:#51cf66,color:#fff
```

## 四、记忆压缩触发机制

```mermaid
flowchart TD
    subgraph Config["配置参数"]
        C1["CONVERSATION_MEMORY_WINDOW_TOKENS = 6000"]
        C2["CONVERSATION_AUTO_COMPRESS_RATIO = 0.75"]
    end

    Start([每轮对话结束]) --> Calc["计算压缩触发阈值"]
    Calc --> Formula["compressTriggerTokens = 6000 × 0.75 = 4500"]

    Formula --> Check{"当前窗口<br/>windowTokens >= 4500?"}

    Check -->|否| End1([不压缩<br/>保持原文])
    Check -->|是| Compress["触发压缩"]

    Compress --> Reserve["resolveRecentRawTokenBudget<br/>保留原文区"]
    Reserve --> Summary["buildStructuredMemorySummary<br/>生成结构化摘要"]

    Summary --> Split["分割历史"]
    Split --> Old["olderRecords → 滑动摘要<br/>slidingSummary"]
    Split --> Recent["recentRecords → 原文保留<br/>windowDialogContext"]

    Old --> Save["memoryService.autoUpsertSessionMemory()<br/>保存压缩结果"]

    style Compress fill:#ffa94d,color:#fff
    style Save fill:#ff6b6b,color:#fff
```

## 五、记忆召回流程

```mermaid
flowchart LR
    subgraph Retrieval["检索参数构建"]
        R1["IntentRetrievalParams"]
        R1 --> R2["sessionTopK = 4"]
        R1 --> R3["globalTopK = 6"]
        R1 --> R4["query = 检索短句"]
        R1 --> R5["focusTables = []"]
    end

    subgraph VectorSearch["向量相似度搜索"]
        V1["ragEmbeddingService.embedText()<br/>用户输入 → 向量"]
        V1 --> V2["searchManagedMemories<br/>Qdrant 搜索"]
        V2 --> V3["过滤: connection_id<br/>scope, database_name"]
        V3 --> V4["返回 Top-K 相似记忆"]
    end

    subgraph Assembly["召回结果组装"]
        A1["组装格式"]
        A1 --> A2["序号. 类型=xxx"]
        A2 --> A3["摘要=xxx<br/>纠正=xxx<br/>约束=xxx<br/>重点提示=xxx"]
        A3 --> A4["Tables=xxx"]
    end

    Retrieval --> VectorSearch --> Assembly
```

## 六、上下文窗口分层模型

```mermaid
flowchart TB
    subgraph QueryHistory["对话历史 QueryHistory"]
        Q1["历史记录 1"]
        Q2["历史记录 2"]
        Q3["历史记录 N"]
        Q4["..."]
    end

    subgraph Compression["压缩层"]
        direction TB
        Comp1["olderRecords<br/>超出窗口的历史"]
        Comp1 -->|"buildStructuredMemorySummary"| Comp2["slidingSummary<br/>滑动摘要"]
    end

    subgraph Window["窗口层 Window"]
        direction TB
        W1["windowSummary<br/>窗口摘要<br/>(压缩后)"]
        W2["windowDialogContext<br/>最近对话原文"]
        W3["windowStructuredContext<br/>结构化上下文 JSON"]
    end

    subgraph Budget["Token 预算分配<br/>6000 tokens"]
        direction LR
        B1["压缩触发线<br/>4500 tokens"]:::trigger
        B2["原文保留区<br/>1500 tokens"]:::reserve
    end

    Q1 --> Q2
    Q2 --> Q3
    Q3 --> Q4
    Q4 --> Comp1

    classDef trigger fill:#ff6b6b,color:#fff
    classDef reserve fill:#51cf66,color:#fff

    BudgetNote["压缩触发时: 4500 tokens 压缩为摘要, 1500 tokens 保留原文"]
```

## 七、记忆保存双写机制

```mermaid
flowchart TB
    subgraph Trigger["保存触发"]
        T1["立即信号<br/>CORRECTION/PRIORITY_HINT<br/>confidence >= 0.80"]
        T2["滑动压缩<br/>窗口超出阈值"]
        T3["手动保存<br/>用户主动"]
    end

    subgraph Process["保存流程"]
        P1["ensureStructuredSummary<br/>构建结构化摘要"]
        P1 --> P2["ragEmbeddingService.embedText<br/>向量化"]
        P2 --> P3["双写存储"]
    end

    subgraph Storage["存储层"]
        direction LR
        S1["MySQL<br/>memory_entry 表"]
        S2["Qdrant<br/>managed_memory 集合"]
    end

    Trigger --> Process --> Storage

    P3 -->|同步写入| S1
    P3 -->|upsertPoints| S2

    StorageNote["MySQL: 持久化存储, Qdrant: 向量检索"]
```

## 八、完整对话流程图

```mermaid
flowchart TB
    subgraph Init["1. 对话初始化"]
        I1["用户输入 SQL/问题"]
        I2["前端发送请求<br/>POST /api/ai/query/stream"]
    end

    subgraph Intent["2. 意图识别"]
        I3["轻量级意图识别<br/>identifyIntentLight"]
        I4{记忆信号<br/>触发?}
        I5["立即记忆捕获<br/>captureImmediateMemorySignal"]
        I6["历史召回<br/>retrieveIntentHistoryContext"]
        I7["最终意图识别<br/>identifyIntentFinal"]
    end

    subgraph Context["3. 上下文构建"]
        C1["loadConversationMemorySnapshot<br/>加载记忆快照"]
        C2["buildRetrievalInputForRag<br/>构建检索输入"]
        C3["RAG 向量召回<br/>ragRetrievalService.retrievePromptContext"]
        C4["Schema 降级<br/>schemaService.buildContext"]
        C5["segments 组装<br/>摘要 + 上下文 + 外部知识"]
    end

    subgraph Generate["4. AI 生成"]
        G1["streamGenerate<br/>流式生成"]
        G2["SSE 推送<br/>onEvent"]
        G3["persistQueryHistory<br/>保存对话历史"]
    end

    subgraph Memory["5. 记忆处理"]
        M1{"窗口超限?"}
        M2["压缩触发<br/>buildStructuredMemorySummary"]
        M3["保存压缩结果<br/>autoUpsertSessionMemory"]
    end

    Init --> Intent
    Intent --> I4
    I4 -->|是| I5
    I4 -->|否| I6
    I5 --> I6
    I6 --> I7
    I7 --> Context
    Context --> C5
    C5 --> Generate
    Generate --> G3
    G3 --> Memory
    Memory --> M1
    M1 -->|是| M2
    M1 -->|否| End([结束])
    M2 --> M3
    M3 --> End

    style I5 fill:#ff6b6b,color:#fff
    style M2 fill:#ffa94d,color:#fff
    style End fill:#51cf66,color:#fff
```

## 九、关键配置参数表

| 参数名 | 默认值 | 说明 |
|--------|--------|------|
| `CONVERSATION_MEMORY_WINDOW_SIZE` | 12 | 会话窗口大小（条记录） |
| `CONVERSATION_MEMORY_WINDOW_TOKENS` | 6000 | 会话窗口 Token 预算 |
| `CONVERSATION_AUTO_COMPRESS_RATIO` | 0.75 | 自动压缩触发比例 |
| `MEMORY_SIGNAL_TRIGGER_CONFIDENCE` | 0.80 | 记忆信号触发置信度阈值 |
| `SESSION_HISTORY_RECALL_LIMIT` | 8 | 会话历史召回上限 |
| `GLOBAL_HISTORY_RECALL_LIMIT` | 10 | 全局历史召回上限 |
| `AUTO_INTENT_MIN_CONFIDENCE` | 0.70 | 意图识别最低置信度 |

## 十、核心类对应关系

| 功能 | 核心类 | 路径 |
|------|--------|------|
| 对话入口 | `AiController` | `apps/server/src/.../controller/AiController.java` |
| 对话服务 | `AiServiceImpl` | `apps/server/src/.../service/impl/AiServiceImpl.java` |
| 上下文管理 | `AiConversationContextManager` | `apps/server/src/.../service/impl/AiConversationContextManager.java` |
| 记忆服务 | `MemoryService` | `apps/server/src/.../service/MemoryService.java` |
| 记忆存储 | `MemoryEntryMapper` | `apps/server/src/.../mapper/MemoryEntryMapper.java` |
| 历史存储 | `QueryHistoryMapper` | `apps/server/src/.../mapper/QueryHistoryMapper.java` |
| 向量存储 | `QdrantClientService` | `apps/server/src/.../service/QdrantClientService.java` |
| 前端对话 | `useStudioRuntime.ts` | `apps/desktop/src/modules/studio/composables/useStudioRuntime.ts` |
| 前端记忆 | `useMemoryModule.ts` | `apps/desktop/src/modules/studio/composables/useMemoryModule.ts` |
