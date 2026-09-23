package com.moin.backend.user;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 실제 이미지 형식·크기 확인 후 정사각 JPEG로 재인코딩, 원본 메타데이터 제외 */
@Component
public class AvatarStorage {

	private final Path root;

	public AvatarStorage(@Value("${moin.upload-dir}") String dir) throws IOException {
		root = Path.of(dir).toAbsolutePath().resolve("avatars");
		Files.createDirectories(root);
	}

	public String save(MultipartFile file) {
		if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진은 5MB 이하로 선택해주세요");
		}
		try (var stream = file.getInputStream(); var input = ImageIO.createImageInputStream(stream)) {
			var readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) throw invalidImage();
			var reader = readers.next();
			try {
				reader.setInput(input);
				if (!Set.of("JPEG", "PNG").contains(reader.getFormatName().toUpperCase(java.util.Locale.ROOT))) throw invalidImage();
				int width = reader.getWidth(0), height = reader.getHeight(0);
				if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진 가로·세로는 4096픽셀 이하로 선택해주세요");
				}
				BufferedImage source = reader.read(0);
				BufferedImage avatar = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
				var graphics = avatar.createGraphics();
				try {
					graphics.setColor(Color.WHITE);
					graphics.fillRect(0, 0, 512, 512);
					graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
					int side = Math.min(width, height), x = (width - side) / 2, y = (height - side) / 2;
					graphics.drawImage(source, 0, 0, 512, 512, x, y, x + side, y + side, null);
				} finally {
					graphics.dispose();
				}
				String name = UUID.randomUUID() + ".jpg";
				if (!ImageIO.write(avatar, "jpeg", root.resolve(name).toFile())) throw new IOException("JPEG encoder unavailable");
				return "/avatars/" + name;
			} finally {
				reader.dispose();
			}
		} catch (javax.imageio.IIOException e) {
			throw invalidImage();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private ResponseStatusException invalidImage() {
		return new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "JPG 또는 PNG 사진을 선택해주세요");
	}
}
