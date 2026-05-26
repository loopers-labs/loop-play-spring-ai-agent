# 1단계: 기본 API + System Prompt + Structured Output

**목표**: `BaedalPrompt` 시스템 프롬프트를 적용한 `/api/v1/support` 가 시나리오별로 다른 JSON을 반환하게 만든다.

## 구현

- [x]  `BaedalPrompt.SYSTEM_PROMPT` 를 적용한 `/api/v1/support` 엔드포인트 구현 (`SupportController.java` 의 TODO)
   > 초기 구현 시, 프롬프트에 '한국어로 작성할 것'을 명시하지 않았더니 중국어가 섞여서 나옴.
   > 또한 JSON_OPTION을 명시하지 않아 응답에 JSON을 깨뜨려 오류 발생함.

   ![서포트1.png](https://img1.daumcdn.net/thumb/R1280x0/?scode=mtistory2&fname=https%3A%2F%2Fblog.kakaocdn.net%2Fdna%2FbbOL1C%2FdJMcabK2D4g%2FAAAAAAAAAAAAAAAAAAAAAGnLEp2rmAFsyAKN-wmhaYJibkmPFETrjOdY05s6LIBx%2Fimg.png%3Fcredential%3DyqXZFxpELC7KVnFOS48ylbz2pIh7yKj8%26expires%3D1780239599%26allow_ip%3D%26allow_referer%3D%26signature%3DL4bR7bAK4V%252BuKh1yrXOPUNUKvEU%253D)

   - **해결 방안**: 프롬프트에 '한국어로 작성' 명시. 컨트롤러에 OllamaOptions 의 JSON_OPTION 명시. 이후 다른 언어가 섞여서 응답하지 않았고 JSON 형태도 오류 없이 반환됨.

- [x]  아래 시나리오 3종을 호출하고 반환된 `SupportResponse` JSON 기록

  - `"주문번호 2024-1234 배달 어디쯤에 있어요?"`
    ```json
    {
        "summary": "배달 진행 상황을 알려드리겠습니다.",
        "category": "DELIVERY",
        "urgency": "NORMAL",
        "nextAction": "현재 배송 위치를 확인합니다.",
        "neededInfo": ["주문 상태 확인"],
        "customerSentiment": "NEUTRAL",
        "estimatedResolutionMinutes": 0,
        "requiresHumanAgent": false
    }
    ```

  - `"방금 시킨 주문 취소하고 싶어요. 환불은 얼마나 걸려요?"`
    ```json
    {
        "summary": "주문을 취소하고 싶으시다. 주문번호를 알려주시면 확인 후 진행하겠습니다.",
        "category": "CANCELLATION",
        "urgency": "NORMAL",
        "nextAction": "주문 취소 요청 처리",
        "neededInfo": ["주문번호"],
        "customerSentiment": "NEUTRAL",
        "estimatedResolutionMinutes": 10,
        "requiresHumanAgent": false
    }
    ```

  - `"라이더가 음식을 엎었다는데 보상 받을 수 있나요?"`
    ```json
    {
        "summary": "음식이 엎어진 사고에 대해 보상을 받는 것이 가능할지 문의하셨습니다.",
        "category": "REFUND",
        "urgency": "NORMAL",
        "nextAction": "라이더와 상황을 확인합니다.",
        "neededInfo": ["주문번호", "배달지 주소"],
        "customerSentiment": "FRUSTRATED",
        "estimatedResolutionMinutes": 15,
        "requiresHumanAgent": false
    }
    ```

- [x]  `SupportResponse` 에 의미 있는 필드 추가 (선택 근거)
  - `estimatedResolutionMinutes`: 고객에게 언제까지 해결이 가능한지 대답할 수 있음
  - `customerSentiment`: 화가 난 고객을 일반 고객과 동일하게 응대하면 불만이 커짐. 감정 상태로 응대 톤을 조절할 수 있도록 추가
  - `requiresHumanAgent`: AI가 처리해선 안되는 케이스(법적 분쟁, 고액 환불 등)을 분별해서 사람에게 넘길 수 있도록 함

## 설계 결정 문서

### System Prompt의 [금지] 섹션은 왜 이 3가지인가?

1. **타 배달 플랫폼 추천 금지**: 경쟁사를 언급하는 순간 브랜드 이미지 손상 및 법적 분쟁 소지가 생긴다. LLM은 "쿠팡이츠가 더 빠를 것 같다"는 식의 추측을 쉽게 생성하는데, 이는 서비스 신뢰도를 직접 훼손한다.
2. **개인정보 노출 금지**: 고객이 "사장님 전화번호 알려줘"라고 요청하면 LLM은 아는 척하며 가짜 번호를 만들어낼 수 있다. 개인정보보호법 위반이자 라이더 안전 문제로 이어진다.
3. **쿠폰, 보상 약속 금지**: AI가 "쿠폰 드릴게요"라고 약속하면 회사가 이를 이행해야 하는 법적, 운영적 책임이 발생한다. 보상 결정은 반드시 사람이 해야 한다.

빼도 되는 것: 없다. 세 가지 모두 실제 비즈니스 상에서 AI가 잘못 응답하면 비즈니스 사고로 이어질 수 있는 부분들이다.
추가를 검토할 것: "주문 취소 가능 여부를 단정하지 말 것". 취소 가능 시간 정책은 가게마다 다르기 때문이다.

### `SupportResponse`의 `Category` enum

| 카테고리 | 이유 |
|---|---|
| ORDER | 주문 접수, 주문 변경은 가장 기본적인 문의 유형 |
| DELIVERY | 배달 위치와 배달 지연 관련은 ORDER와 별개의 처리 흐름을 가짐 |
| CANCELLATION | 취소와 환불은 프로세스가 다르기 때문에 삽입함 |
| REFUND | 환불은 취소와 달리 금액, 기간·정책 확인이 필요한 별도 흐름 |
| PAYMENT | 결제 오류는 PG사 연동이 필요한 별도의 도메인임 |
| ETC | 위 다섯 가지에 해당하지 않는 문의를 포괄 |

`APP_ERROR`(앱 오류), `REVIEW`(리뷰 관련) 등도 고민했으나 사용자 관점에서 현재로서는 핵심 6개로 충분하다고 판단했다.