package com.sqlcopilot.studio.dto.rag;

import lombok.Data;

/** RAG 向量配置响应对象。 */
@Data
public class RagConfigVO {

    /** RAG 向量模型目录路径（可直接配置 clone 后仓库目录）。 */
    private String ragEmbeddingModelDir;

    /** RAG 向量模型文件名（默认 model_optimized.onnx）。 */
    private String ragEmbeddingModelFileName;

    /** RAG 向量模型外部数据文件名（默认 model_optimized.onnx.data）。 */
    private String ragEmbeddingModelDataFileName;

    /** RAG 向量分词器文件名（默认 tokenizer.json）。 */
    private String ragEmbeddingTokenizerFileName;

    /** RAG 分词器配置文件名（默认 tokenizer_config.json）。 */
    private String ragEmbeddingTokenizerConfigFileName;

    /** RAG 模型配置文件名（默认 config.json）。 */
    private String ragEmbeddingConfigFileName;

    /** RAG 特殊词映射文件名（默认 special_tokens_map.json）。 */
    private String ragEmbeddingSpecialTokensFileName;

    /** RAG SentencePiece 文件名（默认 sentencepiece.bpe.model）。 */
    private String ragEmbeddingSentencepieceFileName;

    /** RAG 向量模型 ONNX 文件路径（可选兜底）。 */
    private String ragEmbeddingModelPath;

    /** RAG 向量模型外部数据文件路径（可选，支持 .onnx.data）。 */
    private String ragEmbeddingModelDataPath;

    /** 最近更新时间戳（毫秒）。 */
    private Long updatedAt;
}
