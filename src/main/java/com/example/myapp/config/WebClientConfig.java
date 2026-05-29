package com.example.myapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;

@Configuration
public class WebClientConfig {
	@Bean
	WebClient webClient() {
		ExchangeStrategies strategies = ExchangeStrategies.builder()
			.codecs(configurer -> configurer.defaultCodecs()
				.maxInMemorySize(5 * 1024 * 1024))  // 5MB로 설정
			.build();
		
		return WebClient.builder()
		.baseUrl("http://localhost:8000")
		.exchangeStrategies(strategies)
		.build();
	}

}
