package com.moin.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.moin.backend.auth.AuthInterceptor;

import lombok.RequiredArgsConstructor;

/** 인터셉터 하나로 인증을 건다. /error 를 빼먹으면 인터셉터가 던진 401 이 다시 인터셉터에 걸려 500 이 된다 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

	private final AuthInterceptor authInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(authInterceptor)
				.addPathPatterns("/**")
				.excludePathPatterns("/auth/**", "/error", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**");
	}
}
