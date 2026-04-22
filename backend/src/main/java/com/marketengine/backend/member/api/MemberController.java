package com.marketengine.backend.member.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MemberResponse>> create(@Valid @RequestBody CreateMemberRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(memberService.create(request)));
    }

    @GetMapping("/{memberId}")
    public ResponseEntity<ApiResponse<MemberResponse>> get(@PathVariable Long memberId) {
        return ResponseEntity.ok(ApiResponse.ok(memberService.get(memberId)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<MemberResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(memberService.list()));
    }

    @PutMapping("/{memberId}")
    public ResponseEntity<ApiResponse<MemberResponse>> update(
            @PathVariable Long memberId,
            @Valid @RequestBody UpdateMemberRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(memberService.update(memberId, request)));
    }

    @DeleteMapping("/{memberId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long memberId) {
        memberService.delete(memberId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}