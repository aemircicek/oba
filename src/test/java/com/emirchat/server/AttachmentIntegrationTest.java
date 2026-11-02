package com.emirchat.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.chat.FileMessage;
import com.emirchat.client.ChatClient;
import com.emirchat.client.ServerConnection;
import com.emirchat.crypto.Identity;
import com.emirchat.e2e.Attachments;
import com.emirchat.trust.KnownPeers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Encrypted file sharing through a real server that must not be able to read or swap the files. */
class AttachmentIntegrationTest {
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;
    private ChatServer server;

    private final class Device implements AutoCloseable {
        final String user;
        final Identity identity;
        final Path home;
        final BlockingQueue<FileMessage> files = new LinkedBlockingQueue<>();
        final BlockingQueue<String> problems = new LinkedBlockingQueue<>();
        ChatClient client;

        Device(String user) throws Exception {
            this.user = user;
            this.home = dir.resolve(user + "-device");
            this.identity = Identity.loadOrCreate(home);
        }

        Device start() throws Exception {
            KnownPeers servers = new KnownPeers(home.resolve("known_servers"), (n, fp) -> true);
            ServerConnection c = ServerConnection.open("127.0.0.1", server.port(), identity, servers);
            client = ChatClient.start(c, identity, home.resolve("accounts"), user, PASSWORD, true, new ChatClient.Listener() {
                @Override public void file(String from, FileMessage f, long id) { files.add(f); }

                @Override public void groupFile(long g, String from, FileMessage f, long id) { files.add(f); }

                @Override public void undecryptable(String from, String reason) { problems.add(from + ": " + reason); }
            });
            return this;
        }

        FileMessage nextFile() throws InterruptedException {
            FileMessage f = files.poll(10, TimeUnit.SECONDS);
            assertNotNull(f, user + " expected a file; problems: " + problems);
            return f;
        }

        @Override public void close() {
            if (client != null) client.close();
        }
    }

    @BeforeEach
    void start() throws Exception {
        server = new ChatServer(new ChatServer.Config(dir.resolve("server"), "127.0.0.1", 0, 1_000)).start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static void befriend(Device a, Device b) throws Exception {
        a.client.connection().sendFriendRequest(b.user);
        b.client.connection().respondFriendRequest(a.user, true);
    }

    private static byte[] randomFile(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return data;
    }

    private Path storedBlob() throws Exception {
        try (Stream<Path> files = Files.list(dir.resolve("server/attachments"))) {
            return files.findFirst().orElseThrow();
        }
    }

    @Test
    void aFileReachesAFriendIntactAcrossManyChunks() throws Exception {
        try (Device emir = new Device("emir").start(); Device efe = new Device("efe").start()) {
            befriend(emir, efe);
            byte[] data = randomFile(Packet.MAX_CHUNK_BYTES * 10 + 1234); // several windows of chunks
            ChatClient.SentFile sent = emir.client.sendFile("efe", "paftalar.dwg", "application/acad", data);

            FileMessage received = efe.nextFile();
            assertEquals("paftalar.dwg", received.name());
            assertEquals(data.length, received.size());
            assertEquals(sent.file(), received);
            assertArrayEquals(data, efe.client.downloadFile(received));
            assertArrayEquals(data, efe.client.downloadFile(received), "second open comes from the local cache");
            assertArrayEquals(data, emir.client.downloadFile(sent.file()), "the sender can open their own file");
            assertTrue(efe.problems.isEmpty(), efe.problems.toString());
        }
    }

    @Test
    void theServerOnlyStoresCiphertext() throws Exception {
        try (Device emir = new Device("emir").start(); Device efe = new Device("efe").start()) {
            befriend(emir, efe);
            String secret = "the wifi password is hunter2 and the door code is 4711";
            emir.client.sendFile("efe", "secrets.txt", "text/plain", secret.getBytes(StandardCharsets.UTF_8));
            efe.nextFile();
            String stored = new String(Files.readAllBytes(storedBlob()), StandardCharsets.ISO_8859_1);
            assertFalse(stored.contains("hunter2"));
            assertFalse(stored.contains("secrets"), "not even the file name is on the server");
        }
    }

    @Test
    void aBlobSwappedOrModifiedOnTheServerIsRefused() throws Exception {
        try (Device emir = new Device("emir").start(); Device efe = new Device("efe").start()) {
            befriend(emir, efe);
            emir.client.sendFile("efe", "photo.png", "image/png", randomFile(5000));
            FileMessage f = efe.nextFile();
            Path blob = storedBlob();
            byte[] bytes = Files.readAllBytes(blob);
            bytes[100] ^= 1; // a malicious server flips one bit
            Files.write(blob, bytes);
            assertThrows(SecurityException.class, () -> efe.client.downloadFile(f));
        }
    }

    @Test
    void onlyTheAudienceCanDownload() throws Exception {
        try (Device emir = new Device("emir").start(); Device efe = new Device("efe").start();
             Device mallory = new Device("mallory").start()) {
            befriend(emir, efe);
            befriend(emir, mallory); // a friend of the sender, but not the recipient of this file
            emir.client.sendFile("efe", "for-efe.txt", "text/plain", "only for efe".getBytes(StandardCharsets.UTF_8));
            FileMessage f = efe.nextFile();

            ApiException e = assertThrows(ApiException.class,
                    () -> mallory.client.connection().call(new Packet.FetchAttachment(f.attachmentId(), 0)));
            assertEquals(ErrorCode.ATTACHMENT_NOT_FOUND, e.code());
            ApiException unknown = assertThrows(ApiException.class,
                    () -> mallory.client.connection().call(new Packet.FetchAttachment(new byte[16], 0)));
            assertEquals(e.getMessage(), unknown.getMessage(), "forbidden and unknown look the same");
        }
    }

    @Test
    void groupFilesReachCurrentMembersOnly() throws Exception {
        try (Device emir = new Device("emir").start(); Device efe = new Device("efe").start();
             Device ayse = new Device("ayse").start()) {
            befriend(emir, efe);
            befriend(emir, ayse);
            long g = emir.client.createGroup("MobilCAD ekibi", List.of("efe", "ayse")).id();
            for (Device d : List.of(efe, ayse)) {
                for (int i = 0; i < 100 && d.client.groups().stream().noneMatch(x -> x.id() == g); i++) Thread.sleep(50);
            }
            byte[] data = randomFile(70_000);
            var sent = emir.client.sendGroupFile(g, "plan.pdf", "application/pdf", data);
            assertTrue(sent.getValue().isEmpty());
            FileMessage atEfe = efe.nextFile();
            FileMessage atAyse = ayse.nextFile();
            assertArrayEquals(data, efe.client.downloadFile(atEfe));

            emir.client.removeGroupMember(g, "ayse");
            ApiException e = assertThrows(ApiException.class,
                    () -> ayse.client.connection().call(new Packet.FetchAttachment(atAyse.attachmentId(), 0)));
            assertEquals(ErrorCode.ATTACHMENT_NOT_FOUND, e.code(), "removed members lose access to stored files");
        }
    }

    @Test
    void uploadRulesAreEnforced() throws Exception {
        try (Device emir = new Device("emir").start(); Device stranger = new Device("stranger").start();
             Device efe = new Device("efe").start()) {
            befriend(emir, efe);
            assertTrue(stranger.client.friends().friends().isEmpty());
            ServerConnection c = emir.client.connection();
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(ApiException.class,
                    () -> c.call(new Packet.UploadStart(Attachments.MAX_CIPHERTEXT_BYTES + 1, "efe", 0))).code());
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(ApiException.class,
                    () -> c.call(new Packet.UploadStart(100, "efe", 5))).code(), "one audience only");
            assertEquals(ErrorCode.NOT_FRIENDS, assertThrows(ApiException.class,
                    () -> c.call(new Packet.UploadStart(100, "stranger", 0))).code());
            assertEquals(ErrorCode.GROUP_NOT_FOUND, assertThrows(ApiException.class,
                    () -> c.call(new Packet.UploadStart(100, "", 999))).code());

            byte[] id = assertInstanceOf(Packet.UploadStarted.class, c.call(new Packet.UploadStart(100, "efe", 0))).attachmentId();
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(ApiException.class,
                    () -> c.call(new Packet.UploadChunk(id, 10, new byte[10]))).code(), "chunks must arrive in order");
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(ApiException.class,
                    () -> c.call(new Packet.UploadChunk(id, 0, new byte[101]))).code(), "no more than reserved");
            c.call(new Packet.UploadChunk(id, 0, new byte[60]));
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(ApiException.class,
                    () -> c.call(new Packet.UploadFinish(id))).code(), "cannot finish early");
            assertEquals(ErrorCode.ATTACHMENT_NOT_FOUND, assertThrows(ApiException.class,
                    () -> efe.client.connection().call(new Packet.FetchAttachment(id, 0))).code(), "unfinished is invisible");
            assertEquals(ErrorCode.ATTACHMENT_NOT_FOUND, assertThrows(ApiException.class,
                    () -> efe.client.connection().call(new Packet.UploadChunk(id, 60, new byte[40]))).code(),
                    "only the uploader can write");

            for (int i = 0; i < AttachmentService.MAX_OPEN_UPLOADS - 1; i++) c.call(new Packet.UploadStart(100, "efe", 0));
            assertEquals(ErrorCode.LIMIT_REACHED, assertThrows(ApiException.class,
                    () -> c.call(new Packet.UploadStart(100, "efe", 0))).code(), "open uploads are limited");
        }
    }
}
