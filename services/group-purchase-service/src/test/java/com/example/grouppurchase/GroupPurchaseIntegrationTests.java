package com.example.grouppurchase;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.grouppurchase.purchase.application.ChangeGroupPurchase;
import com.example.grouppurchase.purchase.application.PurchaseChange;
import com.example.grouppurchase.purchase.domain.SellingTerms;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GroupPurchaseIntegrationTests {
    private static final String ROOT = "/api/group-purchases";
    private static final String MINE = ROOT + "/mine";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FUTURE = "2090-01-01T00:00:00Z";
    private static PostgresFixture database;
    private static ConfigClientIntegrationTests.ConfigServerFixture config;
    private static ConfigurableApplicationContext app;
    private static HttpClient client;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        database = new PostgresFixture();
        config = new ConfigClientIntegrationTests.ConfigServerFixture(Path.of(System.getProperty("test.config-repo")));
        try (var v1 = app("--spring.flyway.target=1");
                var connection = database.connection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT to_regclass('app.group_purchases') IS NULL")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getBoolean(1)).isTrue();
        }
        app = app();
        port = ((WebServerApplicationContext) app).getWebServer().getPort();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        assertThat(sqlInt("SELECT count(*) FROM app.flyway_schema_history WHERE version IN ('1', '2') AND success"))
                .isEqualTo(2);
    }

    private static ConfigurableApplicationContext app(String... extra) {
        var arguments = new ArrayList<>(List.of("--spring.profiles.active=local",
                "--CONFIG_SERVER_URL=" + config.url(), "--spring.cloud.config.enabled=true",
                "--eureka.client.enabled=false", "--server.port=0"));
        arguments.addAll(List.of(extra));
        return database.configure(new SpringApplicationBuilder(GroupPurchaseApplication.class), "local")
                .run(arguments.toArray(String[]::new));
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (client != null) client.close();
            if (app != null) app.close();
        } finally {
            try {
                if (config != null) config.close();
            } finally {
                if (database != null) database.close();
            }
        }
    }

    @Test
    void emptyDraftCanBeCompletedAndPublishedWithTheVersionReadByTheSeller() throws Exception {
        UUID seller = UUID.randomUUID();
        String id = register(seller, "{}");
        String detail = MINE + "/" + id;
        var draft = ok(send("GET", detail, seller, null, null));
        assertThat(draft.path("status").stringValue()).isEqualTo("DRAFT");
        assertThat(draft.path("draftVersion").longValue()).isEqualTo(1);
        assertThat(draft.path("productName").isNull()).isTrue();
        assertThat(draft.path("publishedAt").isNull()).isTrue();
        assertThat(draft.path("projectedInitialRecruitmentTarget").isNull()).isTrue();
        assertThat(draft.path("recruitmentOpen").booleanValue()).isFalse();
        var publicDraft = send("GET", ROOT + "/" + id, seller, null, null);
        problem(publicDraft, 404, "NOT_FOUND");
        var head = send("HEAD", ROOT + "/" + id, seller, null, null);
        assertThat(head.statusCode()).isEqualTo(404);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().firstValue("Content-Type")).isEqualTo(publicDraft.headers().firstValue("Content-Type"));

        String failedKey = key();
        problem(send("POST", ROOT + "/" + id + "/publish", seller, failedKey, version(1)),
                409, "GROUP_PURCHASE_NOT_READY");
        problem(send("PUT", ROOT + "/" + id, seller, key(), "{\"draftVersion\":1}"), 400, "INVALID_REQUEST");
        assertThat(send("PUT", ROOT + "/" + id, seller, key(), terms("이름", 10, FUTURE, 1)
                .replace("\"설명\\n두 번째 줄\"", "null")).statusCode()).isEqualTo(200);
        assertThat(ok(send("GET", detail, seller, null, null)).path("productDescription").isNull()).isTrue();

        String original = terms("  이름  ", 11, "2090-01-01T09:00:00+09:00", 2);
        String revisionKey = key();
        var revised = send("PUT", ROOT + "/" + id, seller, revisionKey, original);
        ok(revised);
        var ready = ok(send("GET", detail, seller, null, null));
        assertThat(ready.path("draftVersion").longValue()).isEqualTo(3);
        assertThat(ready.path("projectedInitialRecruitmentTarget").intValue()).isEqualTo(14);
        assertThat(ready.path("finalDeadline").stringValue()).isEqualTo(FUTURE);
        problem(send("POST", ROOT + "/" + id + "/publish", seller, key(), version(2)),
                409, "GROUP_PURCHASE_DRAFT_VERSION_MISMATCH");
        var published = send("POST", ROOT + "/" + id + "/publish", seller, failedKey, version(3));
        ok(published);
        assertThat(send("POST", ROOT + "/" + id + "/publish", seller, failedKey, version(3)).body())
                .isEqualTo(published.body());
        var replay = send("PUT", ROOT + "/" + id, seller, revisionKey, terms("이름", 11, FUTURE, 2));
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(revised.body());
        assertThat(replay.headers().firstValue("X-Request-Id")).isNotEqualTo(revised.headers().firstValue("X-Request-Id"));
        problem(send("PUT", ROOT + "/" + id, seller, revisionKey, terms("이름", 11, FUTURE, 3)),
                409, "IDEMPOTENCY_KEY_CONFLICT");
        problem(send("PUT", ROOT + "/" + id, seller, key(), terms("이름", 11, FUTURE, 3)),
                409, "GROUP_PURCHASE_NOT_DRAFT");

        var visible = ok(send("GET", ROOT + "/" + id, null, null, null));
        assertThat(visible.path("initialRecruitmentTarget").intValue()).isEqualTo(14);
        assertThat(visible.path("productDescription").stringValue()).isEqualTo("설명\n두 번째 줄");
        assertThat(visible.path("recruitmentOpen").booleanValue()).isTrue();
        assertThat(visible.has("sellerId") || visible.has("draftVersion") || visible.has("createdAt")).isFalse();
        var publishedHead = send("HEAD", ROOT + "/" + id, null, null, null);
        assertThat(publishedHead.statusCode()).isEqualTo(200);
        assertThat(publishedHead.body()).isEmpty();
        assertThat(publishedHead.headers().firstValue("Cache-Control")).contains("no-store");
        var owner = ok(send("GET", detail, seller, null, null));
        assertThat(owner.path("draftVersion").longValue()).isEqualTo(3);
        assertThat(owner.path("projectedInitialRecruitmentTarget").isNull()).isTrue();
    }

    @Test
    void strictInputAndSellerOwnershipApplyBeforeMutationOrReplay() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String id = register(seller, "{}");
        String path = ROOT + "/" + id;
        String otherId = register(seller, "{}");
        problem(send("GET", MINE + "/" + id, other, null, null), 404, "NOT_FOUND");
        problem(send("GET", MINE, null, null, null), 403, "INTERNAL_CALLER_DENIED");
        problem(client.send(request("GET", MINE, seller, null, null, "BUYER").build(),
                HttpResponse.BodyHandlers.ofString()), 403, "SELLER_ROLE_REQUIRED");
        assertThat(ok(send("GET", MINE, other, null, null)).path("items").isEmpty()).isTrue();

        String token = key();
        String update = terms("찻잔", 10, FUTURE, 1);
        ok(send("PUT", path, seller, token, update));
        problem(send("PUT", path, other, token, update), 404, "NOT_FOUND");
        problem(send("PUT", ROOT + "/" + otherId, seller, token, update), 409, "IDEMPOTENCY_KEY_CONFLICT");
        for (String value : List.of(
                "[]", "{}{}", "{\"productName\":null,\"productName\":null}", "{\"draftVersion\":1}",
                "{\"productName\":\" \"}", "{\"productName\":\"\\u0000\"}",
                "{\"productName\":\"" + "🫖".repeat(101) + "\"}",
                "{\"productDescription\":\"" + "x".repeat(2001) + "\"}",
                "{\"unitPrice\":0}", "{\"unitPrice\":1000000001}", "{\"unitPrice\":1.0}", "{\"unitPrice\":\"1\"}",
                "{\"baseQuantity\":9}", "{\"baseQuantity\":1000001}",
                "{\"finalDeadline\":\"2026-02-29T00:00:00Z\"}",
                "{\"finalDeadline\":\"2090-01-01T00:00Z\"}",
                "{\"finalDeadline\":\"2090-01-01T00:00:00.0001Z\"}")) {
            problem(send("POST", ROOT, seller, key(), value), 400, "INVALID_REQUEST");
        }
        register(seller, "{\"productName\":\"" + "🫖".repeat(100) + "\",\"productDescription\":\""
                + "x".repeat(2000) + "\",\"unitPrice\":1000000000,\"baseQuantity\":1000000}");
        for (String body : List.of("{}", "{\"draftVersion\":null}", "{\"draftVersion\":0}",
                "{\"draftVersion\":1.0}", "{\"draftVersion\":9223372036854775808}",
                "{\"draftVersion\":2,\"productName\":\"added\"}")) {
            problem(send("POST", path + "/publish", seller, key(), body), 400, "INVALID_REQUEST");
        }
        problem(send("POST", ROOT, seller, null, "{}"), 400, "INVALID_REQUEST");
        problem(send("POST", ROOT, seller, "invalid,key", "{}"), 400, "INVALID_REQUEST");
        var repeated = request("POST", ROOT, seller, key(), "{}", "SELLER").header("idempotency-key", key());
        problem(client.send(repeated.build(), HttpResponse.BodyHandlers.ofString()), 400, "INVALID_REQUEST");
    }

    @Test
    void twoTabsCannotBothReviseTheSameVersion() throws Exception {
        UUID seller = UUID.randomUUID();
        String path = ROOT + "/" + register(seller, "{}");
        var first = pending("PUT", path, seller, key(), terms("first", 11, FUTURE, 1));
        var second = pending("PUT", path, seller, key(), terms("second", 11, FUTURE, 1));
        var responses = List.of(first.get(8, TimeUnit.SECONDS), second.get(8, TimeUnit.SECONDS));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
        problem(responses.stream().filter(result -> result.statusCode() == 409).findFirst().orElseThrow(),
                409, "GROUP_PURCHASE_DRAFT_VERSION_MISMATCH");
        var current = ok(send("GET", MINE + path.substring(ROOT.length()), seller, null, null));
        assertThat(current.path("draftVersion").longValue()).isEqualTo(2);
        assertThat(current.path("productName").stringValue()).isEqualTo(first.get().statusCode() == 200 ? "first" : "second");
    }

    @Test
    void waitingForARevisionRejectsStalePublishAndWaitingForPublishRejectsNewRevision() throws Exception {
        UUID seller = UUID.randomUUID();
        String id = register(seller, terms("before", 10, FUTURE, null));
        String path = ROOT + "/" + id;
        var replacement = new SellingTerms("after", "description", 2000L, 11, Instant.parse(FUTURE));
        var revise = new PurchaseChange(PurchaseChange.Operation.REVISE, UUID.fromString(id), 1L, replacement);
        var stale = queueAfterChange(seller, revise, "POST", path + "/publish", version(1));
        problem(stale, 409, "GROUP_PURCHASE_DRAFT_VERSION_MISMATCH");

        int version = ok(send("GET", MINE + "/" + id, seller, null, null)).path("draftVersion").intValue();
        var publish = new PurchaseChange(PurchaseChange.Operation.PUBLISH, UUID.fromString(id), (long) version, null);
        var tooLate = queueAfterChange(seller, publish, "PUT", path, terms("late", 10, FUTURE, version));
        problem(tooLate, 409, "GROUP_PURCHASE_NOT_DRAFT");
        assertThat(ok(send("GET", path, null, null, null)).path("productName").stringValue()).isEqualTo("after");
    }

    @Test
    void deadlineIsCheckedAfterTheLockAndAfterExpiryTheDetailRemainsPublic() throws Exception {
        UUID seller = UUID.randomUUID();
        String id = register(seller, terms("locked", 11, FUTURE, null));
        String path = ROOT + "/" + id;
        try (var holder = database.connection(); var statement = holder.createStatement()) {
            holder.setAutoCommit(false);
            statement.executeUpdate("UPDATE app.group_purchases SET final_deadline = clock_timestamp() "
                    + "+ interval '1 second' WHERE id = '" + id + "'");
            var publish = pending("POST", path + "/publish", seller, key(), version(1));
            awaitBlocked(backendPid(holder));
            statement.execute("SELECT pg_sleep(GREATEST(0, EXTRACT(EPOCH FROM (final_deadline - clock_timestamp()))) "
                    + "+ 0.02) FROM app.group_purchases WHERE id = '" + id + "'");
            holder.commit();
            problem(publish.get(8, TimeUnit.SECONDS), 409, "GROUP_PURCHASE_DEADLINE_PASSED");
        }

        String open = register(seller, terms("will expire", 11, FUTURE, null));
        ok(send("POST", ROOT + "/" + open + "/publish", seller, key(), version(1)));
        sql("UPDATE app.group_purchases SET published_at = statement_timestamp() - interval '2 seconds', "
                + "updated_at = statement_timestamp() - interval '2 seconds', "
                + "created_at = statement_timestamp() - interval '3 seconds', final_deadline = statement_timestamp() "
                + "- interval '1 second' WHERE id = '" + open + "'");
        var detail = ok(send("GET", ROOT + "/" + open, null, null, null));
        assertThat(detail.path("recruitmentOpen").booleanValue()).isFalse();
        assertThat(itemIds(ok(send("GET", ROOT, null, null, null)))).doesNotContain(open);
    }

    @Test
    void timeoutRollsBackBothRowAndIdempotencyKeyWaits() throws Exception {
        UUID seller = UUID.randomUUID();
        String id = register(seller, "{}");
        String rowKey = key();
        String update = terms("after timeout", 10, FUTURE, 1);
        try (var holder = database.connection(); var statement = holder.createStatement()) {
            holder.setAutoCommit(false);
            statement.execute("SELECT id FROM app.group_purchases WHERE id = '" + id + "' FOR UPDATE");
            problem(send("PUT", ROOT + "/" + id, seller, rowKey, update), 503, "GROUP_PURCHASE_BUSY");
            holder.rollback();
        }
        ok(send("PUT", ROOT + "/" + id, seller, rowKey, update));

        String key = key();
        try (var holder = database.connection(); var statement = holder.createStatement()) {
            holder.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO app.group_purchase_idempotency "
                    + "(member_id, operation, idempotency_key, fingerprint_version, fingerprint) VALUES ('"
                    + seller + "', 'REGISTER', '" + key + "', 1, decode(repeat('00', 32), 'hex'))");
            problem(send("POST", ROOT, seller, key, "{}"), 503, "GROUP_PURCHASE_BUSY");
            holder.rollback();
        }
        assertThat(send("POST", ROOT, seller, key, "{}").statusCode()).isEqualTo(201);
    }

    @Test
    void sameRegistrationKeyConvergesAndAReceiptFailureRollsBackTheBusinessWrite() throws Exception {
        UUID seller = UUID.randomUUID();
        String key = key();
        var first = pending("POST", ROOT, seller, key, "{}");
        var second = pending("POST", ROOT, seller, key, "{\"productName\":null,\"unitPrice\":null}");
        var response = first.get(8, TimeUnit.SECONDS);
        var replay = second.get(8, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(response.body());
        assertThat(replay.headers().firstValue("Location")).isEqualTo(response.headers().firstValue("Location"));
        assertThat(itemIds(ok(send("GET", MINE, seller, null, null)))).hasSize(1);
        problem(send("POST", ROOT, seller, key, "{\"productName\":\"different\"}"), 409, "IDEMPOTENCY_KEY_CONFLICT");

        String fail = key();
        sql("ALTER TABLE app.group_purchase_idempotency ADD CONSTRAINT reject_test_receipt "
                + "CHECK (response_body IS NULL OR idempotency_key <> '" + fail + "')");
        try {
            problem(send("POST", ROOT, seller, fail, "{}"), 500, "INTERNAL_ERROR");
            assertThat(sqlInt("SELECT count(*) FROM app.group_purchase_idempotency WHERE idempotency_key = '" + fail + "'"))
                    .isZero();
            assertThat(itemIds(ok(send("GET", MINE, seller, null, null)))).hasSize(1);
        } finally {
            sql("ALTER TABLE app.group_purchase_idempotency DROP CONSTRAINT reject_test_receipt");
        }
        assertThat(send("POST", ROOT, seller, fail, "{}").statusCode()).isEqualTo(201);
    }

    @Test
    void keysetPagesIncludeTimestampTiesAndApplyTheirOwnScope() throws Exception {
        UUID seller = UUID.randomUUID();
        var ids = List.of("00100000-0000-0000-0000-000000000001", "00100000-0000-0000-0000-000000000002",
                "00100000-0000-0000-0000-000000000003");
        for (String id : ids) {
            sql("INSERT INTO app.group_purchases VALUES ('" + id + "', '" + seller
                    + "', 'RECRUITING', 'tie', 'description', 1, 10, '2095-01-01T00:00:00Z', 12, 1,"
                    + " '2089-01-01T00:00:00Z', '2089-01-01T00:00:00Z', '2089-01-01T00:00:00Z')");
        }
        for (String path : List.of(MINE, ROOT)) {
            var page = ok(send("GET", path + "?limit=2", seller, null, null));
            assertThat(itemIds(page)).containsExactly(ids.get(2), ids.get(1));
            assertThat(page.path("items").get(0).has("productDescription")).isFalse();
            String cursor = page.path("nextCursor").stringValue();
            var next = ok(send("GET", path + "?limit=2&cursor=" + cursor, seller, null, null));
            assertThat(itemIds(next).getFirst()).isEqualTo(ids.getFirst());
            if (path.equals(MINE)) assertThat(next.path("nextCursor").isNull()).isTrue();
            String differentScope = path.equals(MINE) ? ROOT : MINE;
            problem(send("GET", differentScope + "?cursor=" + cursor, seller, null, null), 400, "INVALID_REQUEST");
        }
        for (String suffix : List.of("?cursor=bad!", "?cursor=", "?limit=0", "?limit=101", "?limit=1&limit=2")) {
            problem(send("GET", ROOT + suffix, null, null, null), 400, "INVALID_REQUEST");
        }
    }

    private static HttpResponse<String> queueAfterChange(UUID seller, PurchaseChange change, String method,
            String path, String body) throws Exception {
        var tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var queued = tx.execute(status -> {
            app.getBean(ChangeGroupPurchase.class).changeDraft(seller, key(), change);
            int pid = app.getBean(JdbcClient.class).sql("SELECT pg_backend_pid()").query(Integer.class).single();
            var waiting = pending(method, path, seller, key(), body);
            try {
                awaitBlocked(pid);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            return waiting;
        });
        return queued.get(8, TimeUnit.SECONDS);
    }

    private static void awaitBlocked(int pid) throws Exception {
        try (var observer = database.connection(); var statement = observer.createStatement()) {
            long expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(850);
            do {
                try (var rows = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE "
                        + pid + " = ANY (pg_blocking_pids(pid))")) {
                    rows.next();
                    if (rows.getInt(1) > 0) return;
                }
                Thread.sleep(10);
            } while (System.nanoTime() < expires);
        }
        throw new AssertionError("The competing transaction never waited for the row lock");
    }

    private static int backendPid(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static int sqlInt(String query) throws Exception {
        try (var connection = database.connection(); var statement = connection.createStatement();
                var rows = statement.executeQuery(query)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void sql(String query) throws Exception {
        try (var connection = database.connection(); var statement = connection.createStatement()) {
            statement.execute(query);
        }
    }

    private static String terms(String name, int quantity, String deadline, Integer version) {
        return "{\"productName\":\"" + name + "\",\"productDescription\":\"설명\\n두 번째 줄\","
                + "\"unitPrice\":1000,\"baseQuantity\":" + quantity + ",\"finalDeadline\":\"" + deadline + "\""
                + (version == null ? "" : ",\"draftVersion\":" + version) + "}";
    }

    private static String version(long version) {
        return "{\"draftVersion\":" + version + "}";
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String register(UUID seller, String body) throws Exception {
        var response = send("POST", ROOT, seller, key(), body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var json = JSON.readTree(response.body());
        String id = json.path("id").stringValue();
        assertThat(response.headers().firstValue("Location")).contains(MINE + "/" + id);
        assertThat(json.path("detailPath").stringValue()).isEqualTo(MINE + "/" + id);
        return id;
    }

    private static List<String> itemIds(JsonNode page) {
        var ids = new ArrayList<String>();
        page.path("items").forEach(item -> ids.add(item.path("id").stringValue()));
        return ids;
    }

    private static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return JSON.readTree(response.body());
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var json = JSON.readTree(response.body());
        assertThat(json.path("code").stringValue()).isEqualTo(code);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(response.headers().firstValue("X-Request-Id")).contains(json.path("requestId").stringValue());
    }

    private static HttpResponse<String> send(String method, String path, UUID seller, String key, String body)
            throws Exception {
        return client.send(request(method, path, seller, key, body, "BUYER,SELLER").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static CompletableFuture<HttpResponse<String>> pending(String method, String path, UUID seller, String key,
            String body) {
        return client.sendAsync(request(method, path, seller, key, body, "SELLER").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(String method, String path, UUID seller, String key, String body,
            String roles) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8)).method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        if (seller != null) {
            request.header("X-Internal-Caller", "api-gateway")
                    .header("X-Internal-Credential", PostgresFixture.INTERNAL_CREDENTIAL)
                    .header("X-Authenticated-Member-Id", seller.toString()).header("X-Authenticated-Roles", roles);
        }
        return request;
    }
}
