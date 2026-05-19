package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.service.llm.LlmStreamListener;
import com.sqlcopilot.studio.service.stream.AiStreamObserver;

/**
 * 可取消的 LLM 流式监听器包装类。
 * 当 SSE 发送失败（如客户端断开连接）时，优雅降级并停止发送后续事件，
 * 避免异常向上传播导致整个流式调用中断。
 */
public class CancellableLlmStreamListener implements LlmStreamListener {

    private final AiStreamObserver observer;
    private final String sessionId;
    private final String actionType;
    private volatile boolean cancelled = false;

    public CancellableLlmStreamListener(AiStreamObserver observer, String sessionId, String actionType) {
        this.observer = observer;
        this.sessionId = sessionId;
        this.actionType = actionType;
    }

    @Override
    public void onThinkingDelta(String deltaText, String accumulatedText) {
        if (cancelled) {
            return;
        }
        try {
            observer.onThinkingDelta(sessionId, actionType, deltaText, accumulatedText);
        } catch (IllegalStateException ex) {
            // SSE 发送失败（如客户端断开连接 "Broken pipe"），静默停止发送后续事件
            cancelled = true;
        }
    }

    @Override
    public void onOutputDelta(String deltaText, String accumulatedText) {
        if (cancelled) {
            return;
        }
        try {
            observer.onOutputDelta(sessionId, actionType, deltaText, accumulatedText);
        } catch (IllegalStateException ex) {
            // SSE 发送失败，静默停止发送后续事件
            cancelled = true;
        }
    }
}
