package com.example.grouppurchase.inbox.infrastructure;

import com.example.grouppurchase.inbox.application.IncomingRecord;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public final class InboxBytes {
    private InboxBytes() { }
    public static byte[] fingerprint(IncomingRecord r) {
        try {
            var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
            write(out,r.key()); write(out,r.value());
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static byte[] headers(List<IncomingRecord.Header> headers) {
        try {
            var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes); out.writeInt(headers.size());
            for(var h:headers) { write(out,h.name().getBytes(java.nio.charset.StandardCharsets.UTF_8)); write(out,h.value()); }
            return bytes.toByteArray();
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
    public static List<IncomingRecord.Header> headers(byte[] bytes) {
        try {
            var in=new DataInputStream(new ByteArrayInputStream(bytes)); var result=new ArrayList<IncomingRecord.Header>();
            int count=in.readInt();
            for(int i=0;i<count;i++) result.add(new IncomingRecord.Header(new String(read(in),java.nio.charset.StandardCharsets.UTF_8),read(in)));
            return List.copyOf(result);
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
    private static void write(DataOutputStream out,byte[] b) throws IOException { out.writeInt(b==null ? -1 : b.length); if(b!=null) out.write(b); }
    private static byte[] read(DataInputStream in) throws IOException { int n=in.readInt(); return n<0 ? null : in.readNBytes(n); }
}
