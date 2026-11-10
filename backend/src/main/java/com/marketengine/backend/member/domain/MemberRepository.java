package com.marketengine.backend.member.domain;

import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.marketengine.backend.member.api.MemberDtos.MemberResponse;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
    @Query("""
            select new com.marketengine.backend.member.api.MemberDtos$MemberResponse(m.id, m.email, m.name, m.createdAt)
            from Member m where m.id <= :upperBound order by m.id desc
            """)
    List<MemberResponse> listThroughId(@Param("upperBound") long upperBound, Limit limit);

    boolean existsByEmail(String email);

    Optional<Member> findByEmail(String email);
}
