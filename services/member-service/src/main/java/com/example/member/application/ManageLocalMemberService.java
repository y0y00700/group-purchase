package com.example.member.application;
import java.util.*;
import com.example.member.domain.member.*;
import com.example.member.application.port.PasswordVerifier;
import org.springframework.transaction.annotation.Transactional;

public class ManageLocalMemberService {
    public record Command(String loginId, String status, List<String> roles, String password, boolean update, boolean resetPassword) {
        public Command { roles=List.copyOf(roles); }
        @Override public String toString() { return "Local member command [redacted]"; }
    }
    private final MemberRepository members;
    private final PasswordVerifier passwords;
    public ManageLocalMemberService(MemberRepository members, PasswordVerifier passwords) { this.members=members; this.passwords=passwords; }
    @Transactional
    public void manage(Command command) {
        String loginId=command.loginId().toLowerCase(Locale.ROOT);
        MemberStatus status=MemberStatus.valueOf(command.status());
        Set<Role> roles=new HashSet<>();
        command.roles().forEach(role->roles.add(Role.valueOf(role)));
        var existing=members.findByLoginIdForUpdate(loginId).orElse(null);
        if(existing != null && !command.update()) throw new IllegalStateException("Existing member preserved; explicit update required");
        if(existing == null && command.update()) throw new IllegalStateException("Update target does not exist");
        boolean replacePassword=existing == null || command.resetPassword();
        String hash=replacePassword ? passwords.hash(command.password()) : existing.passwordHash();
        MemberId id=existing == null ? new MemberId(UUID.randomUUID()) : existing.id();
        Member managed=Member.managed(id,loginId,hash,status,roles);
        if(existing == null) members.create(managed); else members.update(managed,replacePassword);
    }
}
