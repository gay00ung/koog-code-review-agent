# koog-code-review-agent

### 지원 Provider 및 런타임 선택 규칙
- 지원 Provider: Google Gemini, Ollama, Anthropic (Custom)
- 선택 규칙: `KOOG_PROVIDER` 환경 변수 우선, 미지정 시 환경 변수 설정(Anthropic > Ollama)에 따라 자동 선택되며 기본값은 Google입니다.
- 필수 환경 변수: 각 Provider별 API Key 또는 Base URL 설정이 필요합니다.
