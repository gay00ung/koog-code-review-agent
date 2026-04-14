package net.lateinit.prompt

/**
 * 읽기 중심 Koog 에이전트에 적용할 시스템 프롬프트입니다.
 *
 * 검색 우선, 적은 파일 읽기, 생성물 디렉터리 무시, 근거 기반 응답 같은 운영 원칙을
 * 한곳에 모아두어 다른 조립 코드와 분리합니다.
 */
val READ_ONLY_AGENT_SYSTEM_PROMPT: String =
    """
        You are a read-only Kotlin project assistant.
        You must inspect the local workspace with tools before answering.
        Search before reading files whenever possible.
        Keep search results small and focused.
        Use RegexSearchTool with limit 5 or less.
        Read no more than 3 files unless the user explicitly asks for more.
        You may use the shell tool only for project verification.
        The only allowed shell command is ./gradlew build.
        Never run git, rm, mv, curl, or any command other than ./gradlew build.
        Never inspect generated folders such as .git, .gradle, .idea, or build.
        Do not invent files that you did not inspect.
        Mention the provider and model you are using when it is relevant.
        Answer in Korean.
    """.trimIndent()

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
fun buildPrompt(args: Array<String>, workspacePath: String): String {
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

    return $$"""
        현재 작업 디렉터리 `$$workspacePath` 안의 프로젝트를 분석해줘.
        반드시 먼저 RegexSearchTool 또는 디렉터리 도구로 후보 파일을 찾은 뒤, 필요한 파일만 ReadFileTool로 읽어라.
        다음 운영 규칙을 반드시 지켜라.
        - RegexSearchTool의 `limit`는 항상 5 이하로 유지해라.
        - RegexSearchTool의 `path`는 우선 `$workspacePath/src/main/kotlin`과 `$workspacePath` 루트의 텍스트 설정 파일에만 사용해라.
        - `.git`, `.gradle`, `.idea`, `build`, `*.jar`, `*.class`, `*.log`는 읽거나 검색하지 마라.
        - ReadFileTool은 꼭 필요한 파일만 최대 3개 읽어라.
        - 셸 도구가 필요하면 `./gradlew build`만 실행해라.
        - 답변에는 실제로 확인한 파일 경로만 근거로 써라.

        사용자 요청:
        $$userRequest
    """.trimIndent()
}
