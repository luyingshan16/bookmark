package com.personal.bookmark_backend.controller;

import com.personal.bookmark_backend.dto.response.bookmark.DailyPickSelectionResponse;
import com.personal.bookmark_backend.service.BookmarkResponseAssembler;
import com.personal.bookmark_backend.service.DailyPickSelectionService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;

@RestController
@RequestMapping("/api/daily-picks")
@RequiredArgsConstructor
public class DailyPickController {
    private final DailyPickSelectionService selections;
    private final BookmarkResponseAssembler bookmarkResponses;

    @GetMapping
    public DailyPickSelectionResponse get(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        var result = selections.readCurrent(userId)
                .orElseGet(() -> selections.select(userId, false, null));
        return response(userId, result);
    }

    @PostMapping("/refresh")
    public DailyPickSelectionResponse refresh(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return response(userId, selections.select(userId, true, null));
    }

    private DailyPickSelectionResponse response(
            Long userId,
            DailyPickSelectionService.Selection result
    ) {
        var items = new ArrayList<DailyPickSelectionResponse.Item>();
        for (var response : bookmarkResponses.toResponses(userId, result.picks())) {
            items.add(
                    new DailyPickSelectionResponse.Item(items.size() + 1, response)
            );
        }
        return new DailyPickSelectionResponse(
                result.date(),
                result.count(),
                result.count() >= 3, items
        );
    }
}
