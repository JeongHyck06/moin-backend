package com.moin.backend.notification;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;

import com.moin.backend.user.PushDeviceRepository;

/**
 * FCM 멀티캐스트 1회 발송, 자격증명(moin.fcm.credentials)이 없으면 로그만 남기고 끝
 * 테스트는 이 클래스를 상속한 기록용 빈으로 대체
 */
@Component
public class PushSender {

	private static final Logger log = LoggerFactory.getLogger(PushSender.class);

	private final PushDeviceRepository devices;
	private final boolean enabled;

	public PushSender(PushDeviceRepository devices, @Value("${moin.fcm.credentials:}") String credentialsPath) throws IOException {
		this.devices = devices;
		if (credentialsPath.isBlank()) {
			log.warn("FCM 비활성, moin.fcm.credentials 가 비어 있어 푸시는 로그로만 남김");
			enabled = false;
			return;
		}
		if (FirebaseApp.getApps().isEmpty()) {
			try (FileInputStream in = new FileInputStream(credentialsPath)) {
				FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(in)).build());
			}
		}
		enabled = true;
	}

	/** ponytail: 동기 발송, 인증 API 응답이 느껴지게 느려지면 @Async 로 */
	public void send(List<String> tokens, String title, String body, Map<String, String> data) {
		if (tokens.isEmpty()) return;
		if (!enabled) {
			log.info("[push:off] {}대 | {} | {} | {}", tokens.size(), title, body, data);
			return;
		}
		MulticastMessage.Builder message = MulticastMessage.builder()
				.setNotification(Notification.builder().setTitle(title).setBody(body).build())
				.putAllData(data);
		tokens.forEach(message::addToken);
		try {
			BatchResponse response = FirebaseMessaging.getInstance().sendEachForMulticast(message.build());
			dropUnregistered(tokens, response);
		} catch (FirebaseMessagingException e) {
			log.warn("FCM 발송 실패 {}", e.getMessage());
		}
	}

	/** 앱 삭제 등으로 죽은 토큰은 바로 지워 다음 발송에서 빠지게 */
	private void dropUnregistered(List<String> tokens, BatchResponse response) {
		List<SendResponse> results = response.getResponses();
		for (int i = 0; i < results.size(); i++) {
			FirebaseMessagingException ex = results.get(i).getException();
			if (ex != null && ex.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
				devices.deleteById(tokens.get(i));
			}
		}
	}
}
