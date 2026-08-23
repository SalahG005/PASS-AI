package com.pass.ai_assistant_backend.ai;

/**
 * Optional callback for streaming scaffold/agent progress to the client (SSE).
 */
@FunctionalInterface
public interface AgentProgressListener {

    void onEvent(String event, Object data);
}
