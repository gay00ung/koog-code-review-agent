package net.lateinit

import ai.koog.agents.core.agent.*
import ai.koog.agents.core.tools.*
import ai.koog.agents.ext.tool.file.*
import ai.koog.agents.ext.tool.search.*
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.clients.*
import ai.koog.prompt.executor.clients.anthropic.*
import ai.koog.prompt.executor.clients.google.*
import ai.koog.prompt.executor.llms.*
import ai.koog.prompt.executor.model.*
import ai.koog.prompt.executor.ollama.client.*
import ai.koog.prompt.llm.*
import ai.koog.rag.base.files.*
import kotlinx.coroutines.*
import kotlinx.io.Source
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
    val workspaceRoot = Path.of(workspacePath).toAbsolutePath().normalize()
    val fileSystem = createWorkspaceFileSystem(workspaceRoot)

    val toolRegistry = ToolRegistry {
        tool(ListDirectoryTool(fileSystem))
        tool(ReadFileTool(fileSystem))
        tool(RegexSearchTool(fileSystem))
    }

    // 사용자가 인수를 넘기면 그 내용을 그대로 쓰고, 없으면 기본 데모 질문을 사용합니다.
    val prompt = buildPrompt(args, workspacePath)

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
                Search before reading files whenever possible.
                Keep search results small and focused.
                Use RegexSearchTool with limit 5 or less.
                Read no more than 3 files unless the user explicitly asks for more.
                Never inspect generated folders such as .git, .gradle, .idea, or build.
                Do not invent files that you did not inspect.
                Mention the provider and model you are using when it is relevant.
                Answer in Korean.
            """.trimIndent(),
            // 에이전트가 어떤 모델 호출과 도구 실행을 하는지 콘솔에서 추적할 수 있게 합니다.
            installFeatures = {
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

/**
 * Koog 파일 도구가 생성물과 IDE 메타데이터를 읽지 않도록 작업 공간 파일 시스템을 제한합니다.
 *
 * 이 파일 시스템은 작업 루트 밖의 경로를 숨기고, `.git`, `.gradle`, `.idea`, `build` 디렉터리와
 * `jar`, `class`, `log` 같은 대용량 또는 바이너리 성격의 파일을 기본적으로 제외합니다.
 *
 * @param workspaceRoot 현재 프로젝트의 작업 루트 절대 경로입니다.
 * @return 안전한 탐색 범위만 노출하는 읽기 전용 파일 시스템입니다.
 */
private fun createWorkspaceFileSystem(workspaceRoot: Path): FileSystemProvider.ReadOnly<Path> {
    return RestrictedWorkspaceFileSystemProvider(
        delegate = JVMFileSystemProvider.ReadOnly,
        workspaceRoot = workspaceRoot,
    )
}

/**
 * 사용자 요청을 안전한 도구 사용 규칙과 함께 감싼 최종 프롬프트를 만듭니다.
 *
 * 직접 전달한 명령행 질문이 있더라도 그대로 모델에 넘기지 않고, 검색 범위와 읽기 개수를 제한하는
 * 운영 규칙을 먼저 붙입니다. 이렇게 하면 도구 결과가 과도하게 누적되어 컨텍스트가 폭증하는 문제를 줄일 수 있습니다.
 *
 * @param args 명령행에서 받은 사용자 요청 인수입니다.
 * @param workspacePath 현재 작업 디렉터리의 절대 경로 문자열입니다.
 * @return 안전 규칙이 포함된 최종 사용자 프롬프트입니다.
 */
private fun buildPrompt(args: Array<String>, workspacePath: String): String {
    val userRequest = if (args.isNotEmpty()) {
        args.joinToString(" ")
    } else {
        """
        우선 `tool(`, `AIAgent(`, `MultiLLMPromptExecutor`, `Anthropic`, `Ollama`, `Google` 관련 코드를 검색하고,
        1. 현재 에이전트 구조
        2. 지원하는 provider 종류
        3. 다음 단계에서 개선할 만한 점 3개
        형식으로 한국어로 정리해줘.
        """.trimIndent()
    }

    return """
        현재 작업 디렉터리 `$workspacePath` 안의 프로젝트를 분석해줘.
        반드시 먼저 RegexSearchTool 또는 디렉터리 도구로 후보 파일을 찾은 뒤, 필요한 파일만 ReadFileTool로 읽어라.
        다음 운영 규칙을 반드시 지켜라.
        - RegexSearchTool의 `limit`는 항상 5 이하로 유지해라.
        - RegexSearchTool의 `path`는 우선 `$workspacePath/src/main/kotlin`과 `$workspacePath` 루트의 텍스트 설정 파일에만 사용해라.
        - `.git`, `.gradle`, `.idea`, `build`, `*.jar`, `*.class`, `*.log`는 읽거나 검색하지 마라.
        - ReadFileTool은 꼭 필요한 파일만 최대 3개 읽어라.
        - 답변에는 실제로 확인한 파일 경로만 근거로 써라.

        사용자 요청:
        $userRequest
    """.trimIndent()
}

/**
 * 특정 경로가 에이전트 도구에 노출되어도 되는 작업 공간 경로인지 판별합니다.
 *
 * @param path 검사할 실제 파일 시스템 경로입니다.
 * @param workspaceRoot 현재 프로젝트의 작업 루트입니다.
 * @return 작업 공간 내부이면서 생성물/메타데이터 경로가 아니면 `true`입니다.
 */
private fun isAllowedWorkspacePath(path: Path, workspaceRoot: Path): Boolean {
    val normalizedPath = path.toAbsolutePath().normalize()

    if (!normalizedPath.startsWith(workspaceRoot)) {
        return false
    }

    if (normalizedPath == workspaceRoot) {
        return true
    }

    val relativePath = workspaceRoot.relativize(normalizedPath)
    val segments = relativePath.map { it.toString() }
    val excludedDirectories = setOf(".git", ".gradle", ".idea", "build")

    if (segments.any { it in excludedDirectories }) {
        return false
    }

    val fileName = normalizedPath.fileName?.toString().orEmpty()
    val excludedExtensions = setOf("jar", "class", "log")
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")

    return extension !in excludedExtensions
}

/**
 * 생성물 디렉터리와 바이너리 파일을 숨기는 작업 공간 전용 읽기 전용 파일 시스템입니다.
 *
 * Koog의 파일 도구는 이 래퍼를 통해 경로를 탐색하므로, 허용되지 않은 경로는 목록에서 빠지고
 * 직접 읽으려고 해도 접근할 수 없습니다.
 *
 * @property delegate 실제 파일 시스템 작업을 수행하는 기반 provider입니다.
 * @property workspaceRoot 현재 프로젝트의 작업 루트 절대 경로입니다.
 */
private class RestrictedWorkspaceFileSystemProvider(
    private val delegate: FileSystemProvider.ReadOnly<Path>,
    private val workspaceRoot: Path,
) : FileSystemProvider.ReadOnly<Path> {

    override fun toAbsolutePathString(path: Path): String = delegate.toAbsolutePathString(path)

    override fun fromAbsolutePathString(path: String): Path = delegate.fromAbsolutePathString(path)

    override fun joinPath(base: Path, vararg parts: String): Path = delegate.joinPath(base, *parts)

    override fun name(path: Path): String = delegate.name(path)

    override fun extension(path: Path): String = delegate.extension(path)

    override suspend fun metadata(path: Path): FileMetadata {
        ensureAllowed(path)
        return requireNotNull(delegate.metadata(path)) {
            "Metadata is unavailable for $path"
        }
    }

    override suspend fun getFileContentType(path: Path): FileMetadata.FileContentType {
        ensureAllowed(path)
        return delegate.getFileContentType(path)
    }

    override suspend fun list(directory: Path): List<Path> {
        if (!isAllowedWorkspacePath(directory, workspaceRoot)) {
            return emptyList()
        }

        return delegate.list(directory)
            .filter { isAllowedWorkspacePath(it, workspaceRoot) }
    }

    override fun parent(path: Path): Path =
        requireNotNull(delegate.parent(path)) { "Parent path is unavailable for $path" }

    override fun relativize(root: Path, path: Path): String =
        requireNotNull(delegate.relativize(root, path)) { "Relative path is unavailable for $path" }

    override suspend fun exists(path: Path): Boolean {
        return isAllowedWorkspacePath(path, workspaceRoot) && delegate.exists(path)
    }

    override suspend fun readBytes(path: Path): ByteArray {
        ensureAllowed(path)
        return delegate.readBytes(path)
    }

    override suspend fun inputStream(path: Path): Source {
        ensureAllowed(path)
        return delegate.inputStream(path)
    }

    override suspend fun size(path: Path): Long {
        ensureAllowed(path)
        return delegate.size(path)
    }

    /**
     * 허용되지 않은 경로 접근을 조기에 차단합니다.
     *
     * @param path 실제로 읽으려는 대상 경로입니다.
     */
    private fun ensureAllowed(path: Path) {
        if (!isAllowedWorkspacePath(path, workspaceRoot)) {
            throw NoSuchFileException(path.toString())
        }
    }
}
