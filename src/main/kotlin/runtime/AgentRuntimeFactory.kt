package net.lateinit.runtime

import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaModels

/**
 * 환경 변수 조합을 바탕으로 사용할 provider와 모델을 결정합니다.
 *
 * 우선순위는 다음과 같습니다.
 * 1. `KOOG_PROVIDER`가 명시된 경우
 * 2. custom Anthropic 관련 환경 변수가 모두 있는 경우
 * 3. Ollama 모델 환경 변수가 있는 경우
 * 4. 위 조건이 모두 아니면 Google
 *
 * @return 현재 실행에 사용할 런타임 설정입니다.
 */
fun selectRuntime(): AgentRuntime {
    val explicitProvider = System.getenv("KOOG_PROVIDER")?.trim()?.lowercase()
    val hasCustomAnthropic = !System.getenv("ANTHROPIC_BASE_URL").isNullOrBlank() &&
        (!System.getenv("ANTHROPIC_MODEL").isNullOrBlank() || !System.getenv("ANTHROPIC_MODEL_NAME").isNullOrBlank())
    val hasOllama = !System.getenv("OLLAMA_MODEL").isNullOrBlank() || explicitProvider == "ollama"

    return when {
        explicitProvider == "anthropic" || explicitProvider == "custom_anthropic" -> createCustomAnthropicRuntime()
        explicitProvider == "ollama" -> createOllamaRuntime()
        explicitProvider == "google" -> createGoogleRuntime()
        hasCustomAnthropic -> createCustomAnthropicRuntime()
        hasOllama -> createOllamaRuntime()
        else -> createGoogleRuntime()
    }
}

/**
 * Google Gemini executor와 기본 모델을 구성합니다.
 *
 * @return Google provider용 런타임 설정입니다.
 */
private fun createGoogleRuntime(): AgentRuntime {
    val apiKey = System.getenv("GOOGLE_API_KEY")
        ?: error("GOOGLE_API_KEY environment variable is not set")

    return AgentRuntime(
        provider = "google",
        executor = MultiLLMPromptExecutor(GoogleLLMClient(apiKey)),
        model = GoogleModels.Gemini3_Flash_Preview,
    )
}

/**
 * Ollama executor와 모델을 구성합니다.
 *
 * `OLLAMA_MODEL`이 있으면 해당 값을 모델 ID로 사용하고, 없으면 기본 모델을 사용합니다.
 *
 * @return Ollama provider용 런타임 설정입니다.
 */
private fun createOllamaRuntime(): AgentRuntime {
    val baseUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434"
    val requestedModel = System.getenv("OLLAMA_MODEL")

    val model = if (requestedModel.isNullOrBlank()) {
        OllamaModels.Meta.LLAMA_3_2
    } else {
        OllamaModels.Meta.LLAMA_3_2.copy(id = requestedModel)
    }

    return AgentRuntime(
        provider = "ollama",
        executor = MultiLLMPromptExecutor(OllamaClient(baseUrl)),
        model = model,
    )
}

/**
 * Anthropic 호환 커스텀 서버용 executor와 모델을 구성합니다.
 *
 * 모델 이름은 `ANTHROPIC_MODEL_NAME`을 우선 사용하고, 없으면 `ANTHROPIC_MODEL`을 사용합니다.
 * 두 값이 모두 존재하면서 서로 다르면 `ANTHROPIC_MODEL_NAME`을 우선 적용합니다.
 *
 * @return custom Anthropic provider용 런타임 설정입니다.
 */
private fun createCustomAnthropicRuntime(): AgentRuntime {
    val baseUrl = System.getenv("ANTHROPIC_BASE_URL")
        ?: error("ANTHROPIC_BASE_URL environment variable is not set")
    val modelName = resolveAnthropicModelName()
    val apiKey = System.getenv("ANTHROPIC_AUTH_TOKEN")
        ?: System.getenv("ANTHROPIC_API_KEY")
        ?: error("ANTHROPIC_AUTH_TOKEN or ANTHROPIC_API_KEY environment variable is not set")

    val model = AnthropicModels.Sonnet_4_5.copy(id = modelName)
    val settings = AnthropicClientSettings(
        modelVersionsMap = mapOf(model to modelName),
        baseUrl = baseUrl,
        timeoutConfig = ConnectionTimeoutConfig(),
    )

    return AgentRuntime(
        provider = "custom_anthropic",
        executor = MultiLLMPromptExecutor(AnthropicLLMClient(apiKey, settings)),
        model = model,
    )
}

/**
 * Anthropic 호환 서버에서 사용할 모델 이름을 환경 변수에서 해석합니다.
 *
 * 우선순위는 다음과 같습니다.
 * 1. `ANTHROPIC_MODEL_NAME`
 * 2. `ANTHROPIC_MODEL`
 *
 * 두 값이 모두 존재하면서 서로 다르면 `ANTHROPIC_MODEL_NAME`이 우선된다는 안내를 출력합니다.
 *
 * @return Anthropic 호환 서버에 전달할 모델 이름입니다.
 */
private fun resolveAnthropicModelName(): String {
    val modelName = System.getenv("ANTHROPIC_MODEL_NAME")?.takeIf { it.isNotBlank() }
    val legacyModelName = System.getenv("ANTHROPIC_MODEL")?.takeIf { it.isNotBlank() }

    if (modelName != null && legacyModelName != null && modelName != legacyModelName) {
        println("[runtime] ANTHROPIC_MODEL_NAME is preferred over ANTHROPIC_MODEL")
    }

    return modelName
        ?: legacyModelName
        ?: error("ANTHROPIC_MODEL_NAME or ANTHROPIC_MODEL environment variable is not set")
}
