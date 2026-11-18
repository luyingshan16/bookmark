package com.personal.bookmark_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AvatarStorageServiceTest {
    @TempDir
    Path directory;

    @Test
    void rejectsFakeImageRegardlessOfDeclaredContentType() throws Exception {
        var service = new AvatarStorageService(directory.toString(), "https://avatar.example.org");
        var upload = new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1, 2, 3});

        assertThrows(IllegalArgumentException.class, () -> service.store(upload));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void detectsFormatFromImageAndStripsTrailingData() throws Exception {
        var service = new AvatarStorageService(directory.toString(), "https://avatar.example.org");
        var source = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(16, 12, BufferedImage.TYPE_INT_ARGB), "png", source));
        source.write(new byte[512]);
        var upload = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", source.toByteArray());

        String name = service.store(upload);
        assertTrue(name.endsWith(".png"));
        assertTrue(Files.size(directory.resolve(name)) < source.size());
        assertEquals(16, ImageIO.read(directory.resolve(name).toFile()).getWidth());

        service.deletePrevious("https://example.com/uploads/avatars/" + name);
        assertFalse(Files.exists(directory.resolve(name)));
    }

    @Test
    void rebasesExistingLocalFilesAndIgnoresMissingOrUnrecognizedLegacyUrls() throws Exception {
        String name = "11111111-1111-1111-1111-111111111111.png";
        var service = new AvatarStorageService(directory.toString(), "https://new.example.org/prefix/");
        String legacy = "http://old.example.org/old-prefix/uploads/avatars/" + name;

        assertNull(service.publicUrl(legacy));
        Files.createFile(directory.resolve(name));
        String expected = "https://new.example.org/prefix/uploads/avatars/" + name;
        assertEquals(expected, service.publicUrl(legacy));
        assertEquals(expected, service.publicUrl(name));
        assertNull(service.publicUrl("https://old.example.org/images/" + name));
        assertNull(service.publicUrl(legacy + "?external=true"));
        assertNull(service.publicUrl("https://old.example.org/uploads/avatars/extra/" + name));
        assertNull(service.publicUrl("../" + name));
        service.deletePrevious("https://old.example.org/uploads/avatars/extra/" + name);
        assertTrue(Files.exists(directory.resolve(name)));
    }

    @Test
    void rejectsUnusablePublicBaseConfiguration() {
        for (String url : new String[]{"//example.org", "ftp://example.org",
                "https://user@example.org", "https://example.org/?query=1",
                "https://example.org/#section", "https://example.org/a/../b"}) {
            assertThrows(IllegalArgumentException.class, () -> new AvatarStorageService(directory.toString(), url), url);
        }
    }
}
