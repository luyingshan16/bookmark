package com.personal.bookmark_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AvatarStorageService {

    private static final Logger log = LoggerFactory.getLogger(AvatarStorageService.class);
    private static final long MAX_AVATAR_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000;
    private static final int MAX_AVATAR_EDGE = 512;
    private static final float JPEG_QUALITY = 0.92f;
    private static final Pattern AVATAR_NAME = Pattern.compile(
            "[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\.(?:jpg|png|webp)");

    private final Path storageDirectory;
    private final String publicBaseUrl;
    private static final String AVATAR_PATH = "/uploads/avatars/";

    public AvatarStorageService(
            @Value("${bookmark.avatar.storage-directory:uploads/avatars}") String storageDirectory,
            @Value("${bookmark.avatar.public-base-url:http://localhost:8080}") String publicBaseUrl
    ) {
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
        URI base = URI.create(publicBaseUrl);
        if (!isHttpOrigin(base) || !base.normalize().equals(base)
                || !base.getRawPath().matches("(?:/[A-Za-z0-9._~-]+)*/?")) {
            throw new IllegalArgumentException("BOOKMARK_PUBLIC_BASE_URL must be an HTTP(S) URL with an optional path prefix");
        }
        this.publicBaseUrl = base.toASCIIString().replaceFirst("/$", "");
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("請選擇要上傳的頭像圖片");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("圖片大小不可超過 10 MB");
        }

        String fileName = null;
        try {
            try (var source = file.getInputStream(); var input = new MemoryCacheImageInputStream(source)) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    throw new IllegalArgumentException("請上傳有效的 JPG 或 PNG 圖片");
                }
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!format.equals("jpeg") && !format.equals("png")) {
                        throw new IllegalArgumentException("請上傳有效的 JPG 或 PNG 圖片");
                    }
                    reader.setInput(input, true, true);
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                        throw new IllegalArgumentException("頭像不可超過 1600 萬像素");
                    }
                    var image = resizeAvatar(reader.read(0));
                    Files.createDirectories(storageDirectory);
                    Path output = Files.createTempFile(storageDirectory, "avatar-", ".tmp");
                    try {
                        if (!writeAvatar(image, format, output) || Files.size(output) > MAX_AVATAR_BYTES) {
                            throw new IllegalArgumentException("處理後的圖片不可超過 10 MB");
                        }
                        String name = UUID.randomUUID() + (format.equals("jpeg") ? ".jpg" : ".png");
                        Files.move(output, storageDirectory.resolve(name));
                        fileName = name;
                    } finally {
                        Files.deleteIfExists(output);
                    }
                } finally {
                    reader.dispose();
                }
            }
            return fileName;
        } catch (javax.imageio.IIOException exception) {
            delete(fileName);
            throw new IllegalArgumentException("無法處理此頭像圖片");
        } catch (IOException exception) {
            delete(fileName);
            throw new IllegalStateException("頭像圖片儲存失敗", exception);
        }
    }

    private static BufferedImage resizeAvatar(BufferedImage source) {
        int cropSize = Math.min(source.getWidth(), source.getHeight());
        int size = Math.min(cropSize, MAX_AVATAR_EDGE);
        if (source.getWidth() == size && source.getHeight() == size) {
            return source;
        }

        int left = (source.getWidth() - cropSize) / 2;
        int top = (source.getHeight() - cropSize) / 2;
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        var avatar = new BufferedImage(size, size, type);
        var graphics = avatar.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, size, size, left, top, left + cropSize, top + cropSize, null);
        } finally {
            graphics.dispose();
        }
        return avatar;
    }

    private static boolean writeAvatar(BufferedImage image, String format, Path output) throws IOException {
        if (!format.equals("jpeg")) {
            return ImageIO.write(image, format, output.toFile());
        }

        var writer = ImageIO.getImageWritersByFormatName(format).next();
        try (var stream = ImageIO.createImageOutputStream(output.toFile())) {
            writer.setOutput(stream);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), parameters);
            return true;
        } finally {
            writer.dispose();
        }
    }

    public Path getStorageDirectory() {
        return storageDirectory;
    }

    public static boolean isAvatarFileName(String name) {
        return name != null && AVATAR_NAME.matcher(name).matches();
    }

    public String publicUrl(String storedValue) {
        String name = localFileName(storedValue);
        if (name == null || !Files.isRegularFile(storageDirectory.resolve(name), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return publicBaseUrl + AVATAR_PATH + name;
    }

    void deletePrevious(String storedValue) {
        delete(localFileName(storedValue));
    }

    private static String localFileName(String value) {
        if (isAvatarFileName(value)) return value;
        if (value == null) return null;
        try {
            URI legacy = URI.create(value);
            if (!isHttpOrigin(legacy) || !legacy.normalize().equals(legacy)) return null;
            String path = legacy.getRawPath();
            int fileStart = path.lastIndexOf('/') + 1;
            if (fileStart == 0 || !path.substring(0, fileStart).endsWith(AVATAR_PATH)) return null;
            String name = path.substring(fileStart);
            return isAvatarFileName(name) ? name : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean isHttpOrigin(URI uri) {
        return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535)
                && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
    }

    void delete(String fileName) {
        if (!isAvatarFileName(fileName)) return;
        try {
            Files.deleteIfExists(storageDirectory.resolve(fileName));
        } catch (IOException exception) {
            log.warn("無法清理頭像檔案 {}", fileName, exception);
        }
    }
}
