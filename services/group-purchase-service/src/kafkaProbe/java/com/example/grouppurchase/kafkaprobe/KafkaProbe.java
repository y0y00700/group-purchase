package com.example.grouppurchase.kafkaprobe;

import com.example.grouppurchase.infrastructure.messaging.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import static com.example.grouppurchase.kafkaprobe.ProbeTransport.*;

/** Finite verification commands. Mutations are limited to a validated verify.<runId>. prefix. */
public final class KafkaProbe {
    public static final Map<String, String> SETTINGS = Map.of("min.insync.replicas", "1", "cleanup.policy", "delete",
            "retention.ms", "604800000", "retention.bytes", "-1", "segment.ms", "3600000", "max.message.bytes", "1048576");
    private final String bootstrap;
    private final String prefix;
    private static final MessageKind APPROVAL = MessageKind.PaymentApprovalRequested;
    private static final byte[] VALID = ProbeSamples.sample(APPROVAL, 1);
    public KafkaProbe(String bootstrap, String runId) {
        if (!runId.matches("[a-z0-9-]{1,64}")) throw new IllegalArgumentException("invalid runId");
        this.bootstrap = bootstrap; this.prefix = "verify." + runId + ".";
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("<bootstrap> <runId> <prepare|smoke|seed|retained|uncommitted|committed|bad|outage|cleanup>");
        KafkaProbe probe = new KafkaProbe(args[0], args[1]);
        switch (args[2]) {
            case "prepare" -> probe.prepare();
            case "business" -> probe.business();
            case "smoke" -> probe.smoke();
            case "ping" -> probe.ping();
            case "seed" -> probe.seed();
            case "retained" -> probe.retained();
            case "uncommitted" -> probe.restart(false);
            case "committed" -> probe.restart(true);
            case "bad" -> probe.bad();
            case "outage" -> probe.outage();
            case "cleanup" -> probe.cleanup();
            default -> throw new IllegalArgumentException(args[2]);
        }
        System.out.println("PROBE_OK " + args[2] + " " + probe.prefix);
    }
    private Admin admin() {
        return Admin.create(Map.of("bootstrap.servers", bootstrap, "default.api.timeout.ms", "15000", "request.timeout.ms", "10000"));
    }
    public void prepare() throws Exception {
        try (Admin admin = admin()) {
            for (String suffix : List.of("round", "groups", "restart", "bad", "retained", "internal")) {
                admin.createTopics(List.of(new NewTopic(prefix + suffix, 3, (short) 1).configs(SETTINGS))).all().get(20, TimeUnit.SECONDS);
            }
            checkTopics(admin, List.of(prefix + "round", prefix + "groups", prefix + "restart", prefix + "bad", prefix + "retained"));
        }
    }
    public static void checkTopics(Admin admin, Collection<String> names) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            try { checkVisibleTopics(admin, names); return; }
            catch (ExecutionException notReady) {
                if (!(notReady.getCause() instanceof org.apache.kafka.common.errors.UnknownTopicOrPartitionException)
                        || System.nanoTime() >= deadline) throw notReady;
                Thread.sleep(100);
            }
        }
    }
    private static void checkVisibleTopics(Admin admin, Collection<String> names) throws Exception {
        var descriptions = admin.describeTopics(names).allTopicNames().get(20, TimeUnit.SECONDS);
        for (String name : names) {
            var desc = descriptions.get(name);
            require(desc.partitions().size() == 3 && desc.partitions().stream().allMatch(p -> p.replicas().size() == 1), "topic topology " + name);
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
            Config config = admin.describeConfigs(List.of(resource)).all().get(20, TimeUnit.SECONDS).get(resource);
            for (var setting : SETTINGS.entrySet()) require(setting.getValue().equals(config.get(setting.getKey()).value()), "config " + name + " " + setting);
            System.out.println("TOPIC_OK " + name + " partitions=3 replicas=1 " + SETTINGS);
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private String logical(byte[] value) { return new MessageCodec().decode(value).kind().topic(); }
    public void ping() throws Exception {
        try (var producer = new KafkaProducer<String, byte[]>(producerProperties(bootstrap))) {
            publish(producer, prefix + "internal", ProbeSamples.ATTEMPT, VALID);
        }
        readGroup(prefix + "internal", prefix + "internal-group", 1);
    }
    public void smoke() throws Exception {
        Map<String, Integer> partitions = new HashMap<>();
        Map<String, byte[]> sent = new HashMap<>();
        Map<String, Long> sequences = new HashMap<>();
        try (var producer = new KafkaProducer<String, byte[]>(producerProperties(bootstrap))) {
            for (MessageKind kind : MessageKind.values()) {
                long sequence = sequences.merge(kind.topic(), 1L, Long::sum);
                byte[] value = ProbeSamples.sample(kind, sequence);
                var metadata = publish(producer, prefix + "round", ProbeSamples.key(kind), value);
                Integer previous = partitions.putIfAbsent(ProbeSamples.key(kind), metadata.partition());
                require(previous == null || previous == metadata.partition(), "same key changed partition");
                sent.put(metadata.partition() + ":" + metadata.offset(), value);
            }
            for (long sequence = 4; sequence <= 8; sequence++) {
                byte[] value = ProbeSamples.sample(APPROVAL, sequence);
                var saved = publish(producer, prefix + "round", ProbeSamples.ATTEMPT, value);
                require(saved.partition() == partitions.get(ProbeSamples.ATTEMPT), "same key sequence partition");
                sent.put(saved.partition() + ":" + saved.offset(), value);
            }
            Map<Integer, String> keys = new HashMap<>();
            for (int n = 0; keys.size() < 3 && n < 1000; n++) {
                String key = UUID.nameUUIDFromBytes((prefix + n).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                int partition = org.apache.kafka.common.utils.Utils.toPositive(org.apache.kafka.common.utils.Utils.murmur2(
                        key.getBytes(java.nio.charset.StandardCharsets.UTF_8))) % 3;
                keys.putIfAbsent(partition, key);
            }
            require(keys.size() == 3, "keys for all partitions");
            for (var entry : keys.entrySet()) {
                for (long sequence = 1; sequence <= 4; sequence++) {
                    var saved = publish(producer, prefix + "groups", entry.getValue(), ProbeSamples.forAttempt(entry.getValue(), sequence));
                    require(saved.partition() == entry.getKey(), "default key partition");
                }
            }
        }
        var first = readGroup(prefix + "round", prefix + "independent-a", 11, sent);
        var second = readGroup(prefix + "round", prefix + "independent-b", 11, sent);
        require(first.equals(second) && first.size() == 11, "independent groups");
        distributedGroup();
    }
    public void business() throws Exception {
        try (Admin admin = admin()) {
            checkTopics(admin, Arrays.stream(MessageKind.values()).map(MessageKind::topic).distinct().toList());
        }
    }
    private Set<String> readGroup(String topic, String group, int expected) {
        return readGroup(topic, group, expected, null);
    }
    private Set<String> readGroup(String topic, String group, int expected, Map<String, byte[]> sent) {
        Set<String> seen = new HashSet<>(); Map<Integer, Long> last = new HashMap<>();
        try (var consumer = consumer(bootstrap, group)) {
            consumer.subscribe(List.of(topic)); long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (seen.size() < expected && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(300))) {
                    Long previous = last.put(record.partition(), record.offset());
                    require(previous == null || previous < record.offset(), "partition order");
                    validateAndCommit(consumer, record, logical(record.value()), true);
                    if (sent != null) require(Arrays.equals(sent.get(record.partition() + ":" + record.offset()), record.value()), "send/receive order or bytes mismatch");
                    require(seen.add(record.partition() + ":" + record.offset()), "duplicate delivery in normal committed run");
                }
            }
            require(seen.size() == expected, "group count " + group + " got=" + seen.size());
        }
        return seen;
    }
    private void distributedGroup() throws Exception {
        // Both consumers stay alive until all 3 partitions are stably split and all 12 records are committed.
        Set<String> seen = ConcurrentHashMap.newKeySet();
        var assignments = new ConcurrentHashMap<Integer, Set<TopicPartition>>();
        CountDownLatch done = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int id = 0; id < 2; id++) {
                int member = id;
                futures.add(executor.submit(() -> {
                    try (var consumer = consumer(bootstrap, prefix + "shared")) {
                        consumer.subscribe(List.of(prefix + "groups"));
                        long deadline = System.nanoTime() + Duration.ofSeconds(50).toNanos();
                        while (done.getCount() > 0 && System.nanoTime() < deadline) {
                            for (var record : consumer.poll(Duration.ofMillis(200))) {
                                validateAndCommit(consumer, record, APPROVAL.topic(), true);
                                require(seen.add(record.partition() + ":" + record.offset()), "shared duplicate");
                            }
                            assignments.put(member, Set.copyOf(consumer.assignment()));
                            if (seen.size() == 12 && assignments.size() == 2) {
                                var a = assignments.get(0); var b = assignments.get(1);
                                if (!a.isEmpty() && !b.isEmpty() && Collections.disjoint(a, b) && a.size() + b.size() == 3) done.countDown();
                            }
                        }
                        require(done.getCount() == 0, "group distribution timeout");
                    }
                }));
            }
            for (var future : futures) future.get(60, TimeUnit.SECONDS);
        }
        System.out.println("GROUP_SPLIT " + assignments + " uniqueRecords=" + seen.size());
    }
    public void seed() throws Exception {
        try (var producer = new KafkaProducer<String, byte[]>(producerProperties(bootstrap))) {
            // Explicit partition isolates the A -> invalid B -> C offset boundary.
            for (String suffix : List.of("restart", "retained")) {
                for (int n = 0; n < 2; n++) producer.send(new ProducerRecord<>(prefix + suffix, 0, ProbeSamples.ATTEMPT, VALID)).get(35, TimeUnit.SECONDS);
            }
            for (byte[] bytes : List.of(VALID, "{bad".getBytes(java.nio.charset.StandardCharsets.UTF_8), VALID))
                producer.send(new ProducerRecord<>(prefix + "bad", 0, ProbeSamples.ATTEMPT, bytes)).get(35, TimeUnit.SECONDS);
        }
        try (var consumer = consumer(bootstrap, prefix + "preserved")) {
            TopicPartition tp = new TopicPartition(prefix + "retained", 0); consumer.assign(List.of(tp));
            var record = next(consumer);
            require(record.offset() == 0, "retained seed"); validateAndCommit(consumer, record, APPROVAL.topic(), true);
        }
    }
    public void retained() {
        try (var consumer = consumer(bootstrap, prefix + "preserved")) {
            TopicPartition tp = new TopicPartition(prefix + "retained", 0); consumer.assign(List.of(tp));
            verifyRestartOffset(consumer, tp, 1L, 1);
            require(consumer.beginningOffsets(List.of(tp)).get(tp) == 0 && consumer.endOffsets(List.of(tp)).get(tp) == 2, "volume messages");
            var record = next(consumer); require(record.offset() == 1, "volume resume");
            validateAndCommit(consumer, record, APPROVAL.topic(), false);
        }
    }
    public void restart(boolean commit) {
        TopicPartition tp = new TopicPartition(prefix + "restart", 0);
        try (var consumer = consumer(bootstrap, prefix + "restart-group")) {
            consumer.assign(List.of(tp));
            verifyRestartOffset(consumer, tp, null, 0);
            var record = next(consumer);
            require(record.offset() == 0, "uncommitted must replay offset 0");
            validateAndCommit(consumer, record, APPROVAL.topic(), commit);
        }
        if (commit) try (var restarted = consumer(bootstrap, prefix + "restart-group")) {
            restarted.assign(List.of(tp)); verifyRestartOffset(restarted, tp, 1L, 1);
            require(next(restarted).offset() == 1, "commit resume");
        }
    }
    public void bad() {
        TopicPartition tp = new TopicPartition(prefix + "bad", 0);
        try (var consumer = consumer(bootstrap, prefix + "bad-group")) {
            consumer.assign(List.of(tp));
            var committed = consumer.committed(Set.of(tp), Duration.ofSeconds(10)).get(tp);
            verifyRestartOffset(consumer, tp, committed == null ? null : 1L, committed == null ? 0 : 1);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) for (var record : consumer.poll(Duration.ofMillis(200))) {
                validateAndCommit(consumer, record, APPROVAL.topic(), true);
            }
            throw new IllegalStateException("invalid B was not rejected");
        }
    }
    public void outage() throws Exception {
        long started = System.nanoTime(); boolean failed = false;
        try (var producer = new KafkaProducer<String, byte[]>(producerProperties(bootstrap))) {
            try { publish(producer, prefix + "round", ProbeSamples.ATTEMPT, VALID); }
            catch (Exception expected) { failed = true; System.out.println("OUTAGE_FAILURE " + expected.getClass().getSimpleName() + " storage=UNKNOWN"); }
        }
        require(failed && System.nanoTime() - started < Duration.ofSeconds(50).toNanos(), "outage bound");
    }
    public void cleanup() throws Exception {
        try (Admin admin = admin()) {
            var groups = admin.listGroups().all().get(20, TimeUnit.SECONDS).stream().map(GroupListing::groupId).filter(n -> n.startsWith(prefix)).toList();
            if (!groups.isEmpty()) admin.deleteConsumerGroups(groups).all().get(20, TimeUnit.SECONDS);
            var topics = admin.listTopics().names().get(20, TimeUnit.SECONDS).stream().filter(n -> n.startsWith(prefix)).toList();
            if (!topics.isEmpty()) admin.deleteTopics(topics).all().get(20, TimeUnit.SECONDS);
            System.out.println("CLEANUP topics=" + topics + " groups=" + groups);
        }
    }
    public static ConsumerRecord<String, byte[]> next(KafkaConsumer<String, byte[]> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            var records = consumer.poll(Duration.ofMillis(200));
            if (!records.isEmpty()) return records.iterator().next();
        }
        throw new IllegalStateException("poll timeout");
    }
}
