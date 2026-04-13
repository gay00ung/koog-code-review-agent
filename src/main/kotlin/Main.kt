package net.lateinit

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import kotlinx.coroutines.runBlocking

/**
 * Koog 기반의 가장 작은 대화형 에이전트를 실행하는 진입점입니다.
 *
 * 이 함수는 다음 순서로 동작합니다.
 * 1. 환경 변수에서 `GOOGLE_API_KEY`를 읽습니다.
 * 2. 명령행 인수 또는 기본 질문으로 사용자 프롬프트를 만듭니다.
 * 3. Google Gemini 모델과 연결된 Koog executor를 생성합니다.
 * 4. `AIAgent`를 구성하고 모델에 요청을 보냅니다.
 * 5. 최종 응답을 콘솔에 출력하고 executor를 정리합니다.
 *
 * @param args 명령행에서 전달한 사용자 요청 문장입니다.
 */
fun main(args: Array<String>) = runBlocking {
    // 에이전트가 LLM과 통신할 수 있도록 Google API 키를 읽습니다.
    val apiKey = System.getenv("GOOGLE_API_KEY") ?: throw IllegalStateException("GOOGLE_API_KEY environment variable is not set")

    // 사용자가 인수를 넘기면 그 내용을 그대로 쓰고, 없으면 기본 데모 질문을 사용합니다.
    val prompt = if (args.isNotEmpty()) {
        args.joinToString(" ")
    } else {
        "Koog가 무엇인지 한국어 두 문장으로 설명해줘."
    }

    // Koog가 Google Gemini API를 호출할 수 있도록 executor를 생성합니다.
    val executor = simpleGoogleAIExecutor(apiKey)

    executor.use { executor ->
        // 시스템 프롬프트와 모델을 묶어 실제 Koog 에이전트를 구성합니다.
        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = GoogleModels.Gemini3_Flash_Preview,
            systemPrompt = "You are a concise Kotlin AI assistant. Answer in Korean."
        )

        // 에이전트를 실행한 뒤 최종 응답을 콘솔에 출력합니다.
        val result = agent.run(prompt)
        println(result)
    }
}
