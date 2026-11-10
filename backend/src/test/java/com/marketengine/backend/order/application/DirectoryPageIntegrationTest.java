package com.marketengine.backend.order.application;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.member.api.MemberDtos.CreateMemberRequest;
import com.marketengine.backend.member.application.MemberService;
import com.marketengine.backend.order.api.OrderDtos.OrderResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "marketengine.elasticsearch.enabled=false", "spring.data.elasticsearch.repositories.enabled=false",
        "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=none", "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.flyway.enabled=true", "spring.flyway.placeholders[catalogProductCount]=1",
        "spring.flyway.placeholders[catalogSeed]=42", "spring.flyway.placeholders[catalogAsOf]=2026-11-01T00:00:00Z"
})
class DirectoryPageIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired OrderService orders;
    @Autowired MemberService members;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;

    @Test
    void pagesOneHundredThousandOrdersWithOneSelectPerPage() {
        jdbc.update("""
                insert into orders(member_id,product_id,quantity,requested_quantity,unit_price,status,total_amount)
                select 1,1,1,1,10,'CREATED',10 from generate_series(1,100000)
                """);
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        long before = stats.getPrepareStatementCount();
        var first = orders.list(null, 100);
        var next = orders.list(first.nextCursor(), 100);
        assertThat(stats.getPrepareStatementCount() - before).isEqualTo(2);
        assertThat(first.items()).hasSize(100);
        assertThat(next.items()).hasSize(100);
        var expected = jdbc.queryForList("select id from orders order by id desc limit 200", Long.class);
        assertThat(Stream.concat(first.items().stream(), next.items().stream()).map(OrderResponse::id).toList())
                .containsExactlyElementsOf(expected);
        assertThat(orders.list(1L, 20).nextCursor()).isNull();
        assertThatThrownBy(() -> orders.list(null, 101)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> members.list(0L, 20)).isInstanceOf(BusinessException.class);
        var memberPage = members.list(null, 3);
        assertThat(memberPage.items()).hasSize(3);
        assertThat(members.list(memberPage.nextCursor(), 3).items().getFirst().id()).isLessThan(memberPage.nextCursor());
    }

    @Test
    void competingSignupsAndDeletionOfAnOrderingMemberReturnDomainConflicts() throws Exception {
        var request = new CreateMemberRequest("race@example.test", "demo");
        Callable<String> signup = () -> {
            try { members.create(request); return "created"; }
            catch (BusinessException ex) { return ex.errorCode().name(); }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(signup);
            var second = pool.submit(signup);
            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder("created", "CONFLICT");
        }
        long orderingMember = jdbc.queryForObject("select member_id from orders order by id limit 1", Long.class);
        assertThatThrownBy(() -> members.delete(orderingMember)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
}
