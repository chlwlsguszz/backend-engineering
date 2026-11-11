package com.marketengine.backend.member.api;

import java.time.OffsetDateTime;

import com.marketengine.backend.member.domain.Member;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class MemberDtos {

    private MemberDtos() {
    }

    public record CreateMemberRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 100) String name
    ) {
    }

    public record UpdateMemberRequest(
            @NotBlank @Size(max = 100) String name
    ) {
    }

    public record MemberResponse(
            Long id,
            String email,
            String name,
            OffsetDateTime createdAt
    ) {
        public static MemberResponse from(Member member) {
            return new MemberResponse(member.getId(), member.getEmail(), member.getName(), member.getCreatedAt());
        }
    }
}
