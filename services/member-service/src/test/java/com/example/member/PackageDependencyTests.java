package com.example.member;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Enforce the agreed source dependencies without introducing an architecture library. */
class PackageDependencyTests {
    @Test void layersDependOnTheirPortsAndModels() throws Exception {
        Path base=Path.of(System.getProperty("test.member-main"));
        try(var files=Files.walk(base)) {
            for(Path file:files.filter(p->p.toString().endsWith(".java")).toList()) {
                String source=Files.readString(file);
                String path=base.relativize(file).toString().replace('\\','/');
                String forbidden=null;
                if(path.startsWith("domain/"))
                    forbidden="(?:org\\.|jakarta\\.|javax\\.|com\\.example\\.member\\.(?:application|interfaces|infrastructure|config)\\.)";
                if(path.startsWith("application/"))
                    forbidden="(?:jakarta\\.|javax\\.|org\\.(?!springframework\\.transaction\\.annotation\\.)|com\\.example\\.member\\.(?:interfaces|infrastructure|config)\\.)";
                if(path.startsWith("interfaces/"))
                    forbidden="(?:org\\.springframework\\.jdbc\\.|java\\.sql\\.|com\\.example\\.member\\.(?:domain|config|infrastructure\\.persistence)\\.)";
                if(path.startsWith("infrastructure/"))
                    forbidden="com\\.example\\.member\\.(?:interfaces|config)\\.";
                if(forbidden!=null) assertFalse(Pattern.compile(forbidden).matcher(source).find(),path);
            }
        }
        // Root Members is only the legacy serialization shell, never a service/repository.
        String compatibility=Files.readString(base.resolve("Members.java"));
        assertFalse(compatibility.contains("@Service"));
        assertFalse(compatibility.contains("JdbcTemplate"));
        assertEquals(1L,java.io.ObjectStreamClass.lookup(Members.Identity.class).getSerialVersionUID());
    }
}
