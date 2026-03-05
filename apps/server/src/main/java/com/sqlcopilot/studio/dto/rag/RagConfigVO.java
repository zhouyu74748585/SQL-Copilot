package com.sqlcopilot.studio.dto.rag;

import lombok.Data;

/** RAG 向量配置响应对象。 */
@Data
public class RagConfigVO {

    /** RAG 向量模型目录路径（可直接配置 clone 后仓库目录）。 */
    private String ragEmbeddingModelDir;

    /** 最近更新时间戳（毫秒）。 */
    private Long updatedAt;
}
