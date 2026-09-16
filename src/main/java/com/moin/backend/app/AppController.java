package com.moin.backend.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 앱 시작 시 호출, 인증 불필요, 버전 비교(semver)는 앱이 함 */
@RestController
@RequestMapping("/app")
public class AppController {

	public record AppVersion(String minVersion, String latestVersion, String iosStoreUrl, String androidStoreUrl) {}

	@Value("${moin.app.min-version}")
	private String minVersion;
	@Value("${moin.app.latest-version}")
	private String latestVersion;
	@Value("${moin.app.ios-store-url}")
	private String iosStoreUrl;
	@Value("${moin.app.android-store-url}")
	private String androidStoreUrl;

	/** minVersion 미만이면 강제 업데이트, latestVersion 미만이면 권장 안내 */
	@GetMapping("/version")
	public AppVersion version() {
		return new AppVersion(minVersion, latestVersion, iosStoreUrl, androidStoreUrl);
	}
}
