package com.moin.backend.checkin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 인증 영상을 moin.upload-dir 에 UUID 이름으로 저장, 길이·코덱 검증은 없음 (앱이 3초 자동 녹화로 보장) */
@Component
public class VideoStorage {

	private final Path root;

	public VideoStorage(@Value("${moin.upload-dir}") String dir) throws IOException {
		root = Paths.get(dir).toAbsolutePath();
		Files.createDirectories(root);
	}

	/** 저장 후 /videos/{name} 상대 경로 반환, base URL 은 클라이언트가 붙임 */
	public String save(MultipartFile file) {
		String contentType = Objects.toString(file.getContentType(), "");
		if (!contentType.startsWith("video/")) {
			throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "영상 파일만 올릴 수 있어요");
		}
		if (file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "영상이 비어 있어요");
		}
		String name = UUID.randomUUID() + (contentType.contains("quicktime") ? ".mov" : ".mp4");
		try {
			file.transferTo(root.resolve(name));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return "/videos/" + name;
	}
}
