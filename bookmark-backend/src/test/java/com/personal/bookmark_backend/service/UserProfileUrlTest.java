package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.entity.User;
import com.personal.bookmark_backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserProfileUrlTest {
    @Test
    void savesOnlyTheFileKeyAndCleansUpTheCorrectFileAfterTransaction() {
        var users = mock(UserRepository.class);
        var storage = mock(AvatarStorageService.class);
        var file = mock(MultipartFile.class);
        var user = new User();
        user.setAvatarUrl("old-avatar");
        when(users.lockById(1L)).thenReturn(Optional.of(user));
        String name = "22222222-2222-2222-2222-222222222222.png";
        when(storage.store(file)).thenReturn(name);
        var service = new UserProfileService(users, storage, new PasswordResetRateLimiter());
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertSame(user, service.updateAvatar(1L, file));
            assertEquals(name, user.getAvatarUrl());
            var callback = TransactionSynchronizationManager.getSynchronizations().get(0);
            callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(storage).delete(name);
            verify(storage, never()).deletePrevious(any());
            callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            verify(storage).deletePrevious("old-avatar");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
