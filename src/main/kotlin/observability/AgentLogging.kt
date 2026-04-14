package net.lateinit.observability

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents

/**
 * 에이전트 실행 중 LLM 호출과 도구 사용 흐름을 콘솔에 남기는 로그 기능을 설치합니다.
 *
 * 학습 단계에서 에이전트가 실제로 어떤 도구를 어떤 순서로 호출하는지 눈으로 확인할 수 있도록,
 * LLM 시작 이벤트와 도구 시작/완료 이벤트를 간단한 텍스트 로그로 출력합니다.
 */
fun GraphAIAgent.FeatureContext.installAgentLogging() {
    handleEvents {
        onLLMCallStarting { context ->
            println("[llm:start] model=${context.model.id} tools=${context.tools.size}")
        }
        onToolCallStarting { context ->
            println("[tool:start] name=${context.toolName} args=${context.toolArgs}")
        }
        onToolCallCompleted { context ->
            println("[tool:done] name=${context.toolName} result=${context.toolResult.toString().take(200)}")
        }
    }
}
