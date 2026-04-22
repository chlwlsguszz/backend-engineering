package com.marketengine.backend.member.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.member.api.MemberDtos.CreateMemberRequest;
import com.marketengine.backend.member.api.MemberDtos.MemberResponse;
import com.marketengine.backend.member.api.MemberDtos.UpdateMemberRequest;
import com.marketengine.backend.member.domain.Member;
import com.marketengine.backend.member.domain.MemberRepository;

@Service
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;

    public MemberService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Transactional
    public MemberResponse create(CreateMemberRequest request) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Email already exists");
        }
        Member saved = memberRepository.save(new Member(request.email(), request.passwordHash(), request.name()));
        return MemberResponse.from(saved);
    }

    public MemberResponse get(Long memberId) {
        return MemberResponse.from(findMember(memberId));
    }

    public List<MemberResponse> list() {
        return memberRepository.findAll().stream().map(MemberResponse::from).toList();
    }

    @Transactional
    public MemberResponse update(Long memberId, UpdateMemberRequest request) {
        Member member = findMember(memberId);
        member.changeName(request.name());
        if (request.passwordHash() != null && !request.passwordHash().isBlank()) {
            member.changePasswordHash(request.passwordHash());
        }
        return MemberResponse.from(member);
    }

    @Transactional
    public void delete(Long memberId) {
        Member member = findMember(memberId);
        memberRepository.delete(member);
    }

    private Member findMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Member not found"));
    }
}