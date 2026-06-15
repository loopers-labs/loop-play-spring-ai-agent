package com.baedal.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 한국어 description 사용 시 다국어 발화에서의 Tool 호출 성공률을 측정한다.
 *
 * 실행 방법 (Ollama + qwen2.5 실행 중이어야 함):
 *   ./gradlew test --tests "com.baedal.support.OrderToolsMultilingualAccuracyTest"
 *
 * 결과를 README의 다국어 정확도 표에 기록할 것.
 * 성공률이 3/5 미만이면 description을 영어 또는 한국어+영어 병기로 전환을 검토한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("multilingual")
class OrderToolsMultilingualAccuracyTest {

    @Autowired
    private MockMvc mockMvc;

    static Stream<Arguments> deliveryStatusQueries() {
        return Stream.of(
            arguments("ko",       "주문번호 2024-1234 배달 어디쯤에 있어요?"),
            arguments("en",       "Where is my order 2024-1234?"),
            arguments("ja",       "注文番号2024-1234の配達状況を教えてください"),
            arguments("zh",       "订单2024-1234现在在哪里？"),
            arguments("en-casual","is order 2024-1234 on its way?")
        );
    }

    /**
     * 각 언어로 배달 위치를 물었을 때 getDeliveryStatus Tool이 호출되는지 확인.
     * 콘솔에서 "[Tool] getDeliveryStatus(orderId=2024-1234)" 로그가 찍히면 성공.
     * 응답 본문에 "역삼역 사거리"가 포함되면 Tool 호출 + 정확한 응답 모두 성공.
     */
    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("deliveryStatusQueries")
    void getDeliveryStatus_is_called_for_language(String lang, String message) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"" + message + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 결과를 표로 기록하기 위해 출력 (테스트는 assert 하지 않고 관찰값만 수집)
        System.out.printf("[multilingual][%s] contains '역삼역 사거리': %s%n",
                lang, body.contains("역삼역 사거리"));
        System.out.printf("[multilingual][%s] response: %s%n", lang, body);
    }
}
