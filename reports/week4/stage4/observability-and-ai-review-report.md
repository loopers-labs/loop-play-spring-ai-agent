# 4단계 — Observability (RAG 토큰 관찰)

> RAG 가 주입하는 토큰 비용을 (a)(b)(c) 3조건으로 직접 관찰한다. 측정 질문(단일턴):
> `"배달 완료 후에도 환불 받을 수 있나요?"` / temp 0.3, qwen2.5 + qwen3-embedding:0.6b.

## A. RAG 주입 토큰 비용 (Phase 4-1)

처음엔 `num_ctx` 기본값(4096)에서 쟀는데 (c)가 천장에 막혀 RAG 비용이 가려졌다 → `num-ctx: 8192` 로 올려 재측정.
(`AssistantChatClientConfig` 의 `defaultAdvisors` 를 임시 수정해 (a)/(b)/(c) 체인을 만든다. 단일턴 질문.)

| 조건 | Advisor 체인 | 입력 토큰 (num_ctx **4096**) | 입력 토큰 (num_ctx **8192**) |
|---|---|---:|---:|
| (a) Memory·RAG 없음 | performance | 3889 | 3889 |
| (b) Memory만 | memory, performance | 3889 | 3889 (빈 Memory) |
| (c) Memory+RAG | memory, rag, performance | **4096** (천장에 막힘) | **4917** |
| **RAG 진짜 비용 (c−a)** | | +207 (가짜) | **+1028** |

**관찰:**
- **(a) = (b) = 3889** — 단일턴이라 Memory 가 비어 토큰 0 추가. "빈 Memory 는 비용 없음" 수치 확인.
- **num_ctx 4096 일 땐 (c)가 정확히 4096** — system(~3500) + Context 가 천장을 넘어 잘림(truncation). RAG 비용이 +207 로만 보였다.
- **num_ctx 8192 로 올리니 (c) = 4917** — RAG 가 실제로 더한 건 **+1028 토큰**(`refund-after-delivered` 정책 문서 + QA 템플릿 래퍼). **4096 천장이 비용의 ~80%(약 821토큰)를 가리고 있었던 것.**
- **천장은 측정뿐 아니라 답 품질도 깎고 있었다** — 4096 일 때 (c) 출력 26토큰(Context 가 잘려 빈약)이었는데, 8192 에선 417토큰으로 답이 풍부해졌다. 응답시간도 토큰↑·검색으로 ~30s(=RAG 는 정확도를 토큰·시간으로 산다).
- 결론: RAG 비용이 +1028 로 수치화됐다. 다만 **system prompt(~3500)가 큰 게 근본 비용** — num_ctx 를 키워 천장은 풀었지만 system prompt 다이어트는 별도 과제. (이 관찰을 위해 `num-ctx: 8192` 채택)

## B. Context 블록 원문 캡처 (Phase 4-2)

조건 (c) DEBUG 로그 — QA Advisor 가 USER 메시지에 주입한 Context:
```
#0 [SYSTEM]  [역할] 당신은 배달 서비스의 ...
#1 [USER]    배달 완료 후에도 환불 받을 수 있나요?

             아래는 참고용 배달 정책 문서입니다. (질문과 무관하면 무시하세요)
             ---------------------
             # 배달 완료 후 환불 정책
             ## 배달 완료 후 환불 가능 사유
             - 음식 누락 / 오배송 ... (refund-after-delivered 원문)
             ---------------------
```
→ 질문("배달 완료 후 환불")에 `refund-after-delivered` 문서가 threshold 0.5 를 넘겨 정확히 매칭·주입됐다.
이 Context 블록이 (a) 대비 입력 토큰을 늘린 실체다(단, 위 A 처럼 천장에 일부 잘림).

## 학습 기록

→ Round 4 공통 학습 기록은 [README](../../../README.md) 의 *Round 4 — 공통 학습 기록* 참조.
