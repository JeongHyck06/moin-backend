package com.moin.backend.freeze;

import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/groups/{groupId}/freezes")
@RequiredArgsConstructor
public class FreezeController {
    private final FreezeService service;
    public record Use(@NotNull LocalDate date) {}

    @GetMapping
    public FreezeService.Inventory inventory(@RequestAttribute("userId") Long userId, @PathVariable Long groupId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return service.inventory(groupId, userId, month);
    }

    @PostMapping
    public FreezeService.Inventory use(@RequestAttribute("userId") Long userId, @PathVariable Long groupId, @Valid @RequestBody Use body) {
        return service.use(groupId, userId, body.date());
    }
}
