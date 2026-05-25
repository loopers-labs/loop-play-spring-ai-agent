package com.baedal.support;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ObservationRegistry 빈을 등록한다.
 * <p>
 * spring-boot-starter-actuator 없이도 Micrometer Observation을 사용할 수 있도록
 * ObservationRegistry를 직접 빈으로 등록한다. actuator가 추가될 경우 그 쪽 빈이 우선한다.
 * <p>
 * OllamaChatAutoConfiguration은 ObjectProvider로 이 빈을 주입받아 사용한다.
 * 빈이 없으면 ObservationRegistry.NOOP을 써서 PerCallObservationHandler가 동작하지 않는다.
 */
@Configuration
public class ObservationConfig {

    @Bean
    @ConditionalOnMissingBean
    ObservationRegistry observationRegistry() {
        return ObservationRegistry.create();
    }
}
