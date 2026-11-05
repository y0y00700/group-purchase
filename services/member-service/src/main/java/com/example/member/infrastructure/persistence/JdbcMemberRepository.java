package com.example.member.infrastructure.persistence;
import java.util.*;
import com.example.member.domain.member.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMemberRepository implements MemberRepository {
    private final JdbcTemplate db;
    public JdbcMemberRepository(JdbcTemplate db) { this.db=db; }
    private Optional<Member> find(String clause, Object key) {
        return db.query("SELECT member_id,login_id,password_hash,status FROM app.members WHERE "+clause,
            (rs,n)->new Member(new MemberId(rs.getObject(1,UUID.class)),rs.getString(2),rs.getString(3),
                MemberStatus.valueOf(rs.getString(4)),Set.of()),key).stream().findFirst().map(account->{
                    var roles=new HashSet<>(db.query("SELECT role FROM app.member_roles WHERE member_id=? ORDER BY role",
                        (rs,n)->Role.valueOf(rs.getString(1)),account.id().value()));
                    return new Member(account.id(),account.loginId(),account.passwordHash(),account.status(),roles);
                });
    }
    @Override public Optional<Member> findByLoginId(String id) { return find("login_id=?",id); }
    @Override public Optional<Member> findById(MemberId id) { return find("member_id=?",id.value()); }
    @Override public Optional<Member> findByLoginIdForUpdate(String id) { return find("login_id=? FOR UPDATE",id); }
    @Override public void create(Member member) {
        db.update("INSERT INTO app.members(member_id,login_id,password_hash,status) VALUES (?,?,?,?)",
            member.id().value(),member.loginId(),member.passwordHash(),member.status().name());
        insertRoles(member);
    }
    @Override public void update(Member member, boolean replacePassword) {
        db.update("UPDATE app.members SET status=?,updated_at=now() WHERE member_id=?",member.status().name(),member.id().value());
        if(replacePassword) db.update("UPDATE app.members SET password_hash=? WHERE member_id=?",member.passwordHash(),member.id().value());
        db.update("DELETE FROM app.member_roles WHERE member_id=?",member.id().value());
        insertRoles(member);
    }
    private void insertRoles(Member member) {
        member.roles().forEach(role->db.update("INSERT INTO app.member_roles VALUES (?,?)",member.id().value(),role.name()));
    }
}
