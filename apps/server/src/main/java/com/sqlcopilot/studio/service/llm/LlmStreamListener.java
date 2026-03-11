package com.sqlcopilot.studio.service.llm;

/** LLM 流式回调监听器。 */
public interface LlmStreamListener {

    /** 思考内容增量。 */
    void onThinkingDelta(String deltaText, String accumulatedText);

    /** 正文内容增量。 */
    void onOutputDelta(String deltaText, String accumulatedText);
}
