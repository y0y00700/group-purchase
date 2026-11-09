package com.example.member.domain.member;
import java.util.Optional;
public interface MemberRepository {
    Optional<Member> findByLoginId(String loginId);
    Optional<Member> findById(MemberId id);
    Optional<Member> findByLoginIdForUpdate(String loginId);
    void create(Member member);
    void update(Member member, boolean replacePassword);
}
