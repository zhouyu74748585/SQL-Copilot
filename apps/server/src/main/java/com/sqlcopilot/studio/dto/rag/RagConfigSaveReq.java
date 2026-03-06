package com.sqlcopilot.studio.dto.rag;

import lombok.Data;

/** 保存 RAG 向量配置请求对象。 */
@Data
public class RagConfigSaveReq {

    /** RAG 向量模型目录路径（可直接配置 clone 后仓库目录）。 */
    private String ragEmbeddingModelDir;

    /** 是否启用本地 Rerank。 */
    private Boolean ragRerankEnabled;

    /** 本地 Rerank 模型目录。 */
    private String ragRerankModelDir;
}
