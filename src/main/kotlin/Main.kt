package net.lateinit

import ai.koog.agents.core.agent.AIAgent
import kotlinx.coroutines.runBlocking
import net.lateinit.observability.installAgentLogging
import net.lateinit.prompt.READ_ONLY_AGENT_SYSTEM_PROMPT
import net.lateinit.prompt.buildPrompt
import net.lateinit.runtime.selectRuntime
import net.lateinit.tooling.createProjectToolRegistry
import java.nio.file.Path

/**
 * Koog 기반의 읽기 전용 프로젝트 에이전트를 실행하는 진입점입니다.
 *
 * 이 함수는 다음 순서로 동작합니다.
 * 1. 현재 작업 디렉터리와 작업 루트 경로를 계산합니다.
 * 2. 읽기/쓰기 정책이 반영된 도구 레지스트리를 구성합니다.
 * 3. 사용자 프롬프트와 사용할 LLM 런타임을 준비합니다.
 * 4. `AIAgent`를 구성하고 실행 로그 기능을 설치합니다.
 * 5. 최종 응답을 콘솔에 출력하고 executor를 정리합니다.
 *
 * @param args 명령행에서 전달한 사용자 요청 문장입니다.
 */
fun main(args: Array<String>) = runBlocking {
    val workspacePath = System.getProperty("user.dir")
    val workspaceRoot = Path.of(workspacePath).toAbsolutePath().normalize()

    val toolRegistry = createProjectToolRegistry(workspaceRoot)
    val prompt = buildPrompt(args, workspacePath)
    val runtime = selectRuntime()

    println("[runtime] provider=${runtime.provider}, model=${runtime.model.id}")

    runtime.executor.use { executor ->
        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = runtime.model,
            toolRegistry = toolRegistry,
            systemPrompt = READ_ONLY_AGENT_SYSTEM_PROMPT,
            installFeatures = {
                installAgentLogging()
            }
        )

        val result = agent.run(prompt)
        println(result)
    }
}
