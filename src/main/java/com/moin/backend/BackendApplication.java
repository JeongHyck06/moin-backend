package com.moin.backend;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

@SpringBootApplication
@EnableScheduling
@OpenAPIDefinition(info = @Info(title = "모인 API", version = "v1"), security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", description = "POST /auth/dev 또는 /auth/kakao 응답의 token")
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

	/** 모든 시각 계산의 기준, 서비스에서 Instant.now() 직접 호출 금지 (테스트가 시간을 못 움직임) */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/** 그룹 리셋 시각(04:00) 해석 기준 존, 사용자별 존은 없음 */
	@Bean
	ZoneId zone(@Value("${moin.zone}") String zone) {
		return ZoneId.of(zone);
	}
}
