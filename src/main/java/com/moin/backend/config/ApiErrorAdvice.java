package com.moin.backend.config;

import java.time.Clock;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 검증 실패 400 의 message 를 첫 필드 오류 문구로, 앱이 message 를 그대로 토스트에 띄움
 * sendError 로 넘기면 Boot 의 DefaultErrorAttributes 가 BindingResult 를 보고 "Validation failed for object=..." 로 덮어써서 본문을 직접 만듦
 * ResponseStatusException 은 Boot 기본 처리(spring.web.error.include-message=always)로 reason 이 message 에 실림
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class ApiErrorAdvice {

	private final Clock clock;

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> onInvalid(MethodArgumentNotValidException e, HttpServletRequest req) {
		String message = e.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(f -> f.getDefaultMessage())
				.orElse("요청 값을 확인해주세요");
		return ResponseEntity.badRequest().body(Map.of(
				"timestamp", clock.instant(),
				"status", HttpStatus.BAD_REQUEST.value(),
				"error", HttpStatus.BAD_REQUEST.getReasonPhrase(),
				"message", message,
				"path", req.getRequestURI()));
	}
}
