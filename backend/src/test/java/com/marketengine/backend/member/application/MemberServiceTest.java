package com.marketengine.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.member.api.MemberDtos.CreateMemberRequest;
import com.marketengine.backend.member.api.MemberDtos.MemberResponse;
import com.marketengine.backend.member.api.MemberDtos.UpdateMemberRequest;
import com.marketengine.backend.member.domain.Member;
import com.marketengine.backend.member.domain.MemberRepository;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberService memberService;

    @Test
    void create_savesAndReturnsResponse() {
        CreateMemberRequest request = new CreateMemberRequest("user@test.com", "pw", "tester");

        when(memberRepository.existsByEmail(request.email())).thenReturn(false);
        when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            return member;
        });

        MemberResponse response = memberService.create(request);

        assertThat(response.email()).isEqualTo("user@test.com");
        assertThat(response.name()).isEqualTo("tester");
        verify(memberRepository).save(any(Member.class));
    }

    @Test
    void create_throwsConflictWhenEmailExists() {
        CreateMemberRequest request = new CreateMemberRequest("dup@test.com", "pw", "tester");
        when(memberRepository.existsByEmail(request.email())).thenReturn(true);

        assertThatThrownBy(() -> memberService.create(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void update_throwsNotFoundWhenMissing() {
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.update(1L, new UpdateMemberRequest("new", "pw")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }
}