package com.moin.backend.config;

import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.moin.backend.auth.AuthInterceptor;

import lombok.RequiredArgsConstructor;

/** 인증은 인터셉터 하나로 처리, /error 를 예외에서 빼면 인터셉터가 던진 401 이 다시 걸려 500 으로 바뀜 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

	private final AuthInterceptor authInterceptor;
	private final com.moin.backend.storage.FileDeletionService fileDeletion;

	@Value("${moin.upload-dir}")
	private String uploadDir;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(fileDeletion).addPathPatterns("/videos/**", "/avatars/**");
		registry.addInterceptor(authInterceptor)
				.addPathPatterns("/**")
				.excludePathPatterns("/callbacks/admob", "/auth/**", "/app/**", "/error", "/videos/**", "/avatars/**", "/actuator/health", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**");
	}

	/** 업로드 디렉터리를 /videos/** 로 그대로 서빙, 파일명이 UUID 라 인증 없이 열어도 추측 불가 */
	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		String location = Paths.get(uploadDir).toAbsolutePath().toUri().toString();
		registry.addResourceHandler("/videos/**")
				.addResourceLocations(location.endsWith("/") ? location : location + "/");
		String avatars = Paths.get(uploadDir, "avatars").toAbsolutePath().toUri().toString();
		registry.addResourceHandler("/avatars/**")
				.addResourceLocations(avatars.endsWith("/") ? avatars : avatars + "/");
	}
}
