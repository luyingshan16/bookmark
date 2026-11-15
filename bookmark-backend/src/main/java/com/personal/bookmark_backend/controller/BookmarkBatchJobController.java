package com.personal.bookmark_backend.controller;

import com.personal.bookmark_backend.exception.ResourceNotFoundException;
import com.personal.bookmark_backend.service.BookmarkBatchJobs;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/bookmarks/move-jobs")
@RequiredArgsConstructor
public class BookmarkBatchJobController {
    private final BookmarkBatchJobs jobs;

    @PostMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BookmarkBatchJobs.Status submit(
            @PathVariable UUID id, @Valid @RequestBody Request request, Authentication authentication
    ) {
        return jobs.submit((Long) authentication.getPrincipal(), id.toString(),
                request.sourceFolderId(), request.targetFolderId(), request.ids());
    }

    @PostMapping("/{id}/delete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BookmarkBatchJobs.Status submitDelete(
            @PathVariable UUID id, @Valid @RequestBody DeleteRequest request, Authentication authentication
    ) {
        return jobs.submitDelete((Long) authentication.getPrincipal(), id.toString(),
                request.sourceFolderId(), request.ids());
    }

    @GetMapping("/{id}")
    public BookmarkBatchJobs.Status status(@PathVariable UUID id, Authentication authentication) {
        var status = jobs.find((Long) authentication.getPrincipal(), id.toString());
        if (status == null) throw new ResourceNotFoundException("找不到移動任務");
        return status;
    }

    @GetMapping("/{id}/failed-ids")
    public List<Long> failedIds(@PathVariable UUID id, Authentication authentication) {
        return jobs.failedIds((Long) authentication.getPrincipal(), id.toString());
    }

    @GetMapping("/latest")
    public ResponseEntity<BookmarkBatchJobs.Status> latest(
            @RequestParam(defaultValue = "false") boolean includeDelete, Authentication authentication
    ) {
        var status = jobs.latest((Long) authentication.getPrincipal(), includeDelete);
        return status == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(status);
    }

    public record Request(
            @NotNull @Positive Long sourceFolderId,
            @NotNull @Positive Long targetFolderId,
            @NotEmpty @Size(max = 50000) List<@NotNull @Positive Long> ids
    ) {}

    public record DeleteRequest(
            @NotNull @Positive Long sourceFolderId,
            @NotEmpty List<@NotNull @Positive Long> ids
    ) {}
}
