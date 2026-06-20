package com.baedal.support.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OllamaHealthIndicatorTest {

    @Test
    void healthIsUpWhenTagsEndpointResponds() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("http://ollama.test/api/tags"))
                .andRespond(withSuccess("{\"models\":[]}", org.springframework.http.MediaType.APPLICATION_JSON));

        var indicator = new OllamaHealthIndicator(builder.build());

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("responseLength", 13);
        server.verify();
    }

    @Test
    void healthIsDownWhenTagsEndpointFails() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("http://ollama.test/api/tags"))
                .andRespond(withException(new IOException("connection refused")));

        var indicator = new OllamaHealthIndicator(builder.build());

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        server.verify();
    }
}
