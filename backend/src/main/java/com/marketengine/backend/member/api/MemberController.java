package com.marketengine.backend.member.api;

import org.springframework.web.bind.annotation.RequestParam;
import com.marketengine.backend.common.response.CursorPage;
import io.swagger.v3.oas.annotations.Operation;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.marketengine.backend.common.response.ApiResponse;
import com.marketengine.backend.member.api.MemberDtos.CreateMemberRequest;
import com.marketengine.backend.member.api.MemberDtos.MemberResponse;
import com.marketengine.backend.member.api.MemberDtos.UpdateMemberRequest;
import com.marketengine.backend.member.application.MemberService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @PostMapping
    public ApiResponse<MemberResponse> create(@Valid @RequestBody CreateMemberRequest request) {
        return ApiResponse.ok(memberService.create(request));
    }

    @GetMapping("/{memberId}")
    public ApiResponse<MemberResponse> get(@PathVariable Long memberId) {
        return ApiResponse.ok(memberService.get(memberId));
    }

    @GetMapping
    @Operation(summary = "List by descending ID", description = "Pass nextCursor as beforeId. Page size is 1 to 100.")
    public ApiResponse<CursorPage<MemberResponse>> list(@RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(memberService.list(beforeId, size));
    }

    @PutMapping("/{memberId}")
    public ApiResponse<MemberResponse> update(
            @PathVariable Long memberId,
            @Valid @RequestBody UpdateMemberRequest request
    ) {
        return ApiResponse.ok(memberService.update(memberId, request));
    }

    @DeleteMapping("/{memberId}")
    public ApiResponse<Void> delete(@PathVariable Long memberId) {
        memberService.delete(memberId);
        return ApiResponse.ok();
    }
}
