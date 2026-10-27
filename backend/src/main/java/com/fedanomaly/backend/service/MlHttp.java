package com.fedanomaly.backend.service;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/** RestTemplate for ML-service calls, with timeouts so a sleeping or slow ML host cannot block the simulation. */
final class MlHttp {
    private MlHttp() {}

    static RestTemplate restTemplate(int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }
}
