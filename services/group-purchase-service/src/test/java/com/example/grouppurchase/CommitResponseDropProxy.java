package com.example.grouppurchase;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only PostgreSQL wire proxy: drop an already committed transaction's response. */
final class CommitResponseDropProxy implements AutoCloseable {
    final AtomicBoolean armed = new AtomicBoolean();
    final AtomicBoolean dropped = new AtomicBoolean();
    private final ServerSocket listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    CommitResponseDropProxy(String targetHost,int targetPort) throws IOException {
        workers.submit(() -> {
            while(!closed) {
                try {
                    Socket client=listener.accept(), server=new Socket(targetHost,targetPort);
                    sockets.add(client); sockets.add(server);
                    workers.submit(() -> {
                        try { client.getInputStream().transferTo(server.getOutputStream()); }
                        catch(IOException ignored) { }
                        finally { close(client); close(server); }
                    });
                    workers.submit(() -> {
                        try {
                            var in=new DataInputStream(server.getInputStream()); var out=new DataOutputStream(client.getOutputStream());
                            while(true) {
                                int type=in.readUnsignedByte(), length=in.readInt(); byte[] body=in.readNBytes(length-4);
                                if(body.length!=length-4) throw new EOFException();
                                if(type=='C' && new String(body,StandardCharsets.UTF_8).equals("COMMIT\0") && armed.compareAndSet(true,false)) {
                                    dropped.set(true); return;
                                }
                                out.writeByte(type); out.writeInt(length); out.write(body); out.flush();
                            }
                        } catch(IOException ignored) { }
                        finally { close(client); close(server); }
                    });
                } catch(IOException ignored) { if(!closed) throw new UncheckedIOException(ignored); }
            }
        });
    }
    int port() { return listener.getLocalPort(); }
    private void close(Socket socket) { sockets.remove(socket); try {socket.close();} catch(IOException ignored) { } }
    @Override public void close() throws IOException {
        closed=true; listener.close(); for(var socket:sockets.toArray(Socket[]::new)) close(socket); workers.shutdownNow();
    }
}
