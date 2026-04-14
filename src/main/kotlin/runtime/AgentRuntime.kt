package net.lateinit.runtime

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel

/**
 * 현재 실행에 사용할 LLM executor와 모델, provider 이름을 묶는 런타임 설정입니다.
 *
 * @property provider 현재 선택된 provider 식별자입니다.
 * @property executor 실제 요청을 보내는 executor입니다.
 * @property model 현재 executor와 함께 사용할 모델 정의입니다.
 */
data class AgentRuntime(
    val provider: String,
    val executor: PromptExecutor,
    val model: LLModel,
)
