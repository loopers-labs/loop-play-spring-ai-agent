# /api/v1/chat/stream — curl -N raw

회고 05 부록의 원본 데이터예요.

## 호출 명령

```bash
curl -N -sS -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"주문번호 2024-1234 배달 어디쯤에 있어요?"}'
```

총 시간 약 4.58s, 186 chunk 가 SSE 형식으로 흘러나왔습니다.

## 청크 모양 (raw 스트림 처음 ~40 줄)

```text
data:{
data:
data: 
data: "
data:summary
data:":
data: "
data:고
data:객
data:님
data: 말씀
data:에
data: 따
data:르
data:면
data:,
data: 배
data:달
data: 상태
data:를
data: 확인
data:해
data: 주
data:시
data:길
data: 원
data:하시는
data: 것으로
data: 이해
data:됩니다
data:.",
data:
data: 
data: "
data:category
data:":
data: "
data:DEL
data:IVERY
data:",
```

JSON 한 글자 한 글자가 SSE 청크로 떨어지는 모양이에요. 회고 05 본문의 가설 ("JSON 강제 SYSTEM_PROMPT 와 .stream() 의 미스매치") 이 그대로 잡힌 자리.

## 청크를 모아 본 최종 JSON

```json
{
  "summary": "고객님 말씀에 따르면, 배달 상태를 확인해 주시길 원하시는 것으로 이해됩니다.",
  "category": "DELIVERY",
  "urgency": "NORMAL",
  "nextAction": "배송현황을 확인한 후 고객에게 통보합니다.",
  "neededInfo": [],
  "estimatedResolutionMinutes": null,
  "confidenceLevel": "HIGH"
}
```

support 5건은 `confidenceLevel: MEDIUM` 으로만 나왔는데 이 stream 한 건은 HIGH 가 떨어졌어요.
표본이 한 건이라 단정 못 하고, 회고 03 부록의 "confidenceLevel 이 잘 갈라지지 않는다" 가 한 번 더 흔들리는 그림으로 둡니다.

## 전체 stream 출력

원본 전체는 `stream-full.txt` 에 있어요.
