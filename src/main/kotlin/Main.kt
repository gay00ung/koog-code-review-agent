package net.lateinit

import ai.koog.agents.core.agent.*
import ai.koog.agents.core.tools.*
import ai.koog.agents.ext.tool.file.*
import ai.koog.prompt.executor.clients.*
import ai.koog.prompt.executor.clients.anthropic.*
import ai.koog.prompt.executor.clients.google.*
import ai.koog.prompt.executor.llms.*
import ai.koog.prompt.executor.model.*
import ai.koog.prompt.executor.ollama.client.*
import ai.koog.prompt.llm.*
import ai.koog.rag.base.files.*
import kotlinx.coroutines.*
import java.nio.file.*

/**
 * Koog 기반의 읽기 전용 프로젝트 에이전트를 실행하는 진입점입니다.
 *
 * 이 함수는 다음 순서로 동작합니다.
 * 1. 현재 작업 디렉터리를 기준으로 읽기 전용 파일 도구를 구성합니다.
 * 2. 환경 변수 조합에 따라 사용할 LLM provider와 모델을 선택합니다.
 * 3. 명령행 인수 또는 기본 질문으로 사용자 프롬프트를 만듭니다.
 * 4. `AIAgent`를 구성하고 모델에 요청을 보냅니다.
 * 5. 최종 응답을 콘솔에 출력하고 executor를 정리합니다.
 *
 * @param args 명령행에서 전달한 사용자 요청 문장입니다.
 */
fun main(args: Array<String>) = runBlocking {
    val workspacePath = System.getProperty("user.dir")
    val fileSystem: FileSystemProvider.ReadOnly<Path> = JVMFileSystemProvider.ReadOnly

    val toolRegistry = ToolRegistry {
        tool(ListDirectoryTool(fileSystem))
        tool(ReadFileTool(fileSystem))
    }

    // 사용자가 인수를 넘기면 그 내용을 그대로 쓰고, 없으면 기본 데모 질문을 사용합니다.
    val prompt = if (args.isNotEmpty()) {
        args.joinToString(" ")
    } else {
        """
        현재 작업 디렉터리 `$workspacePath` 안의 프로젝트를 분석해줘.
        반드시 도구를 사용해서 디렉터리와 파일을 직접 확인한 뒤 답해줘.
        우선 `build.gradle.kts`와 `src/main/kotlin/Main.kt`를 읽고,
        1. 프로젝트 목적 추정
        2. 현재 Koog 학습 상태
        3. 다음에 해볼 만한 실습 3개
        형식으로 한국어로 정리해줘.
        """.trimIndent()
    }

    // 환경 변수 조합에 따라 provider와 모델을 선택합니다.
    val runtime = selectRuntime()
    println("[runtime] provider=${runtime.provider}, model=${runtime.model.id}")

    runtime.executor.use { executor ->
        // 시스템 프롬프트와 모델을 묶어 실제 Koog 에이전트를 구성합니다.
        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = runtime.model,
            toolRegistry = toolRegistry,
            systemPrompt = """
                You are a read-only Kotlin project assistant.
                You must inspect the local workspace with tools before answering.
                Do not invent files that you did not inspect.
                Mention the provider and model you are using when it is relevant.
                Answer in Korean.
            """.trimIndent()
        )

        // 에이전트를 실행한 뒤 최종 응답을 콘솔에 출력합니다.
        val result = agent.run(prompt)
        println(result)
    }
}

/**
 * 현재 실행에 사용할 LLM executor와 모델, provider 이름을 묶는 런타임 설정입니다.
 *
 * @property provider 현재 선택된 provider 식별자입니다.
 * @property executor 실제 요청을 보내는 executor입니다.
 * @property model 현재 executor와 함께 사용할 모델 정의입니다.
 */
private data class AgentRuntime(
    val provider: String,
    val executor: PromptExecutor,
    val model: LLModel,
)

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
private fun selectRuntime(): AgentRuntime {
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
