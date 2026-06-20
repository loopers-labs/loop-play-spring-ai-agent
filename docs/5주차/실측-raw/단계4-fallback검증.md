# 4단계 raw — Fallback 검증 (Tool / LLM 실패)

`AssistantController.ask()` 전체를 try/catch로 감싸고, 예외 시 `fallback(e)`가 스택트레이스를 응답에 노출하지 않고
내부 `log.error`로만 남기며 연결번호 1600-0987을 포함한 안전 응답을 돌려준다.
(SupportController도 같은 방식으로 SupportResponse fallback 추가.)

## 실험 A — Tool 강제 실패

`OrderTools.getDeliveryStatus`에 `throw new RuntimeException("simulated Tool failure");`를 한 줄 넣고 재기동.

```
입력: 주문번호 2024-1234 상태 알려줘
응답: 해당 주문번호를 찾지 못했습니다. 다시 확인해주세요.
[http=200 t=1.835378s]
```

서버 로그:
```
[Tool] getDeliveryStatus(orderId=2024-***)
DefaultToolExecutionExceptionProcessor : Exception thrown by tool: getDeliveryStatus. Message: simulated Tool failure
PerformanceLoggingAdvisor : LLM call elapsedMs=1646 promptTokens=3530 completionTokens=46 totalTokens=3576
```

관찰: Tool 예외는 컨트롤러 try/catch까지 **오지 않았다**.
Spring AI의 `DefaultToolExecutionExceptionProcessor`가 예외를 가로채 그 메시지를 **모델에게 tool 결과로 돌려줬고**, 모델이 두 번째 호출에서 "주문번호를 찾지 못했습니다"로 응답했다.
스택트레이스는 응답에 노출되지 않았지만, 컨트롤러 Fallback이 아니라 Spring AI의 기본 처리기가 막은 것이다.
검증 후 throw 라인은 제거함.

## 실험 B — LLM 연결/호출 실패

먼저 숙제대로 `base-url: http://localhost:1`로 바꿔 재기동했더니 **앱이 기동에 실패**했다.
`KnowledgeLoader.alreadyLoaded()`가 시작 시 임베딩 검색(`POST http://localhost:1/api/embed`)을 하는데 connection refused가 나면서 ApplicationRunner가 던졌다.

```
Started BaedalSupportApplication in 1.15 seconds
ERROR o.s.boot.SpringApplication : Application run failed
org.springframework.web.client.ResourceAccessException: I/O error on POST request for "http://localhost:1/api/embed": Connection refused
    at com.baedal.support.rag.KnowledgeLoader.alreadyLoaded(KnowledgeLoader.java:150)
Commencing graceful shutdown
```

임베딩이 startup 임계경로에 있어서, base-url을 통째로 죽이면 요청 시점 Fallback을 보기도 전에 앱이 안 뜬다.
그래서 **요청 시점** LLM 실패를 보려고 base-url은 11434로 두고 chat 모델만 없는 이름(`nonexistent-model-xyz`)으로 바꿔 재기동했다(임베딩은 정상이라 startup 통과).

```
입력: 주문번호 2024-1234 상태 알려줘
응답: 죄송합니다. 일시적인 오류로 요청을 처리하지 못했습니다. 잠시 후 다시 시도하시거나 고객센터 1600-0987로 문의해 주세요.
[http=200 t=0.165879s]
```

서버 로그 (스택트레이스는 내부에만):
```
WARN  PerformanceLoggingAdvisor : LLM call failed elapsedMs=8 type=NonTransientAiException message=404 - {"error":"model 'nonexistent-model-xyz' not found"}
ERROR AssistantController : [Fallback] assistant 처리 실패 — 내부 오류 (응답에는 미노출)
org.springframework.ai.retry.NonTransientAiException: 404 - {"error":"model 'nonexistent-model-xyz' not found"}
    at org.springframework.ai.ollama.api.OllamaApi.chat(OllamaApi.java:115)
```

관찰: chat 호출 예외(`NonTransientAiException`)는 컨트롤러까지 전파됐고, `fallback(e)`가 받아 스택트레이스 없는 안전 응답을 돌려줬다.
검증 후 모델명은 qwen2.5로 원복함.

## 정량 비교표

| 실패 지점 | 응답 본문 | 스택트레이스 노출 | 연결번호 | 서버 로그 수준 | 막은 주체 |
| --- | --- | --- | --- | --- | --- |
| Tool (getDeliveryStatus throw) | "주문번호를 찾지 못했습니다" | 안 됨 | 없음 | DEBUG (tool 예외) | Spring AI 처리기(모델 복구) |
| LLM (없는 모델, 요청시점) | "일시적인 오류... 1600-0987" | 안 됨 | 포함 | ERROR ([Fallback]) | 컨트롤러 try/catch |
| LLM (base-url localhost:1, 시작시) | (요청 불가 — 앱 미기동) | 안 됨 | 없음 | ERROR (startup) | 없음(startup 실패) |

스택트레이스는 세 경우 모두 HTTP 응답으로 새지 않았다.
다만 "누가 막느냐"가 갈렸다 — Tool 실패는 Spring AI가, LLM 실패는 컨트롤러 Fallback이 막았고, base-url 전체 차단은 startup 단계에서 걸렸다.
