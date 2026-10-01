package com.moin.backend.auth;

import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** Authorization: Bearer {token} 검증 후 request attribute "userId" 로 전달 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

	private final com.moin.backend.safety.SafetyService safety;
	private final SessionRepository sessions;
	private final com.moin.backend.user.UserRepository users;
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
		var user = users.findById(session.getUserId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        boolean deletion = req.getRequestURI().equals("/me") && req.getMethod().equals("DELETE");
        if (user.isSuspended() && !deletion && !req.getRequestURI().equals("/me/logout")) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"이용이 제한된 계정이에요. 고객지원으로 문의하거나 계정을 삭제할 수 있어요");
		if (!java.util.Set.of("GET","HEAD","OPTIONS","DELETE").contains(req.getMethod()) && (req.getRequestURI().startsWith("/groups") || req.getRequestURI().equals("/me/profile"))) safety.requireTerms(session.getUserId());
        req.setAttribute("userId", session.getUserId());
		return true;
	}
}
