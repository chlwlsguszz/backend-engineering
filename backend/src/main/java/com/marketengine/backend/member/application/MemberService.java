package com.marketengine.backend.member.application;

import org.postgresql.util.PSQLState;
import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.common.exception.PostgresConstraint;
import com.marketengine.backend.common.response.CursorPage;
import com.marketengine.backend.member.api.MemberDtos.CreateMemberRequest;
import com.marketengine.backend.member.api.MemberDtos.MemberResponse;
import com.marketengine.backend.member.api.MemberDtos.UpdateMemberRequest;
import com.marketengine.backend.member.domain.Member;
import com.marketengine.backend.member.domain.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;

    @Transactional
    public MemberResponse create(CreateMemberRequest request) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Email already exists");
        }
        try {
            Member saved = memberRepository.saveAndFlush(new Member(request.email(), null, request.name()));
            return MemberResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            if (PostgresConstraint.matches(ex, PSQLState.UNIQUE_VIOLATION, "members", "members_email_key")) {
                throw new BusinessException(ErrorCode.CONFLICT, "Email already exists");
            }
            throw ex;
        }
    }

    public MemberResponse get(Long memberId) {
        return MemberResponse.from(findMember(memberId));
    }

    public CursorPage<MemberResponse> list(Long beforeId, int size) {
        return CursorPage.read(beforeId, size, memberRepository::listThroughId, MemberResponse::id);
    }

    @Transactional
    public MemberResponse update(Long memberId, UpdateMemberRequest request) {
        Member member = findMember(memberId);
        member.changeName(request.name());
        return MemberResponse.from(member);
    }

    @Transactional
    public void delete(Long memberId) {
        Member member = findMember(memberId);
        try {
            memberRepository.delete(member);
            memberRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            if (PostgresConstraint.matches(ex, PSQLState.FOREIGN_KEY_VIOLATION, "orders", "fk_orders_member")) {
                throw new BusinessException(ErrorCode.CONFLICT, "Member has order history");
            }
            throw ex;
        }
    }

    private Member findMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Member not found"));
    }
}
