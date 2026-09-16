package com.moin.backend.auth;

import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** Authorization: Bearer {token} 을 검증해 request attribute "userId" 로 넘긴다 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

	private final SessionRepository sessions;
	private final Clock clock;

	@Override
	public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
		String header = req.getHeader("Authorization");
		if (header == null || !header.startsWith("Bearer ")) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요해요");
		}
		Session session = sessions.findById(header.substring(7))
				.filter(s -> s.getExpiresAt().isAfter(clock.instant()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "세션이 만료됐어요"));
		req.setAttribute("userId", session.getUserId());
		return true;
	}
}
