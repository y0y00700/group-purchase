package com.example.grouppurchase.infrastructure.messaging;

import com.example.grouppurchase.inbox.application.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class InboxDecoder {
    private final Map<String,String> topics;
    private final Map<String,IncomingMessageHandler> handlers;
    public InboxDecoder(Map<String,String> topics, Collection<IncomingMessageHandler> handlers) {
        this.topics=Map.copyOf(topics); var registry=new HashMap<String,IncomingMessageHandler>();
        for(var handler:handlers) {
            var kind=MessageKind.registered(handler.messageType());
            if(!kind.topic().equals(handler.topic()) || registry.putIfAbsent(handler.messageType(),handler)!=null)
                throw new IllegalArgumentException("Duplicate/mismatched inbox handler");
        }
        this.handlers=Map.copyOf(registry);
    }
    public Set<String> registeredTypes() { return handlers.keySet(); }
    public IncomingMessage decode(IncomingRecord r) {
        String topic=topics.get(r.topic()); if(topic==null) throw new MessageRejected("TOPIC_MISMATCH");
        String key;
        try {
            if(r.key()==null) throw new CharacterCodingException();
            key=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(r.key())).toString();
        } catch(CharacterCodingException e) { throw new MessageRejected("KEY_MALFORMED"); }
        var decoded=new MessageCodec().receive(r.value(),topic,key); var n=decoded.envelope();
        if(!handlers.containsKey(decoded.kind().name())) throw new MessageRejected("HANDLER_MISSING");
        var context=n.get("context");
        @SuppressWarnings("unchecked") Map<String,Object> payload=JsonMapper.builder().build().convertValue(n.get("payload"),Map.class);
        return new IncomingMessage(UUID.fromString(n.get("messageId").asString()),decoded.kind().name(),
                UUID.fromString(context.get("paymentAttemptId").asString()),UUID.fromString(n.get("correlationId").asString()),
                UUID.fromString(context.get("groupPurchaseId").asString()),UUID.fromString(context.get("participationId").asString()),
                n.get("sequence").longValue(),payload);
    }
    public void handle(IncomingMessage message) { handlers.get(message.type()).handle(message); }
}
