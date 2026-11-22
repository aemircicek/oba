package com.emirchat.client;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import com.emirchat.api.Packet;
import com.emirchat.crypto.Identity;
import com.emirchat.protocol.Wire;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Linking computers to an account (3.6), both sides: a new computer asking to join
 * ({@link #link}), and one already signed in offering its key and approving (see
 * {@link DeviceLink} for the cryptography and why the codes are safe to compare).
 */
public final class DeviceLinking {
    static final String DEVICE_FILE = "device";
    static final String DEVICE_SECRET_FILE = "device-secret.bin";
    static final String ACCOUNT_IDENTITY_FILE = "account-identity.bin";

    private final ServerConnection connection;
    private final Supplier<ChatClient.Listener> listener;

    DeviceLinking(ServerConnection connection, Supplier<ChatClient.Listener> listener) {
        this.connection = connection;
        this.listener = listener;
    }

    /**
     * The account's identity on a computer that was linked to it (it got it from another of the
     * account's computers), or null on the account's first computer, which uses its own key.
     * Unlocks the folder's vault with the password.
     */
    public static Identity linkedIdentity(Path accountDir, String user, String password) throws IOException {
        Path dir = accountDir.resolve(user);
        if (!Files.exists(dir.resolve(ACCOUNT_IDENTITY_FILE))) return null;
        com.emirchat.e2e.Vault.unlock(dir, password);
        byte[] data = com.emirchat.e2e.Vault.read(dir.resolve(ACCOUNT_IDENTITY_FILE));
        try {
            Wire.Reader r = new Wire.Reader(data);
            byte[] pub = r.exactBytes(Identity.ENCODED_PUBLIC_KEY_LENGTH);
            byte[] priv = r.bytes(256);
            r.expectEnd();
            return Identity.fromEncoded(pub, priv);
        } catch (ProtocolException e) {
            throw new IOException("The account's key on this computer is damaged", e);
        }
    }

    /** What linking gave this computer. */
    public record Linked(Identity account, int deviceId) {}

    /**
     * Links this computer to an account (3.6): proves the password (and code), shows {@code code}
     * the number to compare with the computer that approves, waits for its approval, and keeps the
     * account's identity, this device's number and its secret in the account folder. Then sign in
     * normally with the identity returned.
     */
    public static Linked link(ServerConnection connection, Path accountDir, String user, String password, String twoStep,
                              java.util.function.Consumer<String> code) throws IOException, ApiException {
        if (!connection.hasAccountSecurity()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "The server is older than 3.6: it cannot link computers.");
        }
        java.security.KeyPair ephemeral = com.emirchat.crypto.X25519.generate();
        byte[] mine = com.emirchat.crypto.X25519.encode(ephemeral.getPublic());
        java.util.concurrent.CompletableFuture<Packet.LinkOffered> offered = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<Packet.LinkGranted> granted = new java.util.concurrent.CompletableFuture<>();
        connection.onPush(p -> {
            if (p instanceof Packet.LinkOffered o) offered.complete(o);
            if (p instanceof Packet.LinkGranted g) granted.complete(g);
        });
        connection.onClosed(() -> {
            offered.completeExceptionally(new IOException("The server closed the connection"));
            granted.completeExceptionally(new IOException("The server closed the connection"));
        });
        // first only a commitment to our key: it is revealed after the other computer sent its own
        long linkId = ((Packet.LinkPending) connection.call(new Packet.LinkLogin(user, password,
                twoStep == null ? "" : twoStep, DeviceLink.commitment(mine), ChatClient.deviceName()))).linkId();
        Packet.LinkGranted g;
        try {
            Packet.LinkOffered o = offered.get(5, java.util.concurrent.TimeUnit.MINUTES);
            if (o.linkId() != linkId) throw new IOException("Another link answered");
            connection.call(new Packet.LinkReveal(linkId, mine));
            code.accept(DeviceLink.code(mine, o.ephemeral()));
            g = granted.get(5, java.util.concurrent.TimeUnit.MINUTES);
            if (g.deviceId() >= 1 && !java.util.Arrays.equals(g.ephemeral(), o.ephemeral())) {
                throw new IOException("The keys came from another computer than the one that offered");
            }
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("No computer approved in five minutes");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Stopped", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        }
        if (g.deviceId() < 1) throw new ApiException(ErrorCode.INVALID_REQUEST, "The other computer said no.");
        Identity account = DeviceLink.open(ephemeral, g.ephemeral(), g.sealed());
        Path dir = accountDir.resolve(AccountServiceNames.normalize(user));
        Files.createDirectories(dir);
        com.emirchat.e2e.Vault.unlock(dir, password);
        com.emirchat.e2e.Vault.write(dir.resolve(ACCOUNT_IDENTITY_FILE),
                new Wire.Writer().bytes(account.publicKeyBytes()).bytes(account.exportPrivate()).toByteArray());
        com.emirchat.e2e.Vault.write(dir.resolve(DEVICE_SECRET_FILE), g.secret());
        Files.writeString(dir.resolve(DEVICE_FILE), Integer.toString(g.deviceId()));
        return new Linked(account, g.deviceId());
    }

    /** Usernames as the server keeps them (lower case, no spaces around). */
    private static final class AccountServiceNames {
        static String normalize(String u) {
            return u == null ? "" : u.strip().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A computer asking to join, after it revealed its key: the code to compare and its name. */
    public record LinkPrompt(long linkId, String name, String code) {}

    /** Links in progress that we offered our key to: our one-time key, their commitment, and later their key. */
    private record Offer(java.security.KeyPair mine, byte[] commitment, byte[] theirs) {}

    private final Map<Long, Offer> offers = new java.util.concurrent.ConcurrentHashMap<>();

    /** A computer asks to join: offer our one-time key (it then reveals its own). */
    void offer(Packet.LinkRequest request) {
        if (offers.size() > 8) return;
        java.security.KeyPair mine = com.emirchat.crypto.X25519.generate();
        offers.put(request.linkId(), new Offer(mine, request.commitment(), null));
        try {
            connection.call(new Packet.LinkOffer(request.linkId(), com.emirchat.crypto.X25519.encode(mine.getPublic())));
        } catch (IOException | ApiException e) {
            offers.remove(request.linkId()); // another of our computers offered first, or it gave up
        }
    }

    /** It revealed its key: check it against its commitment, then ask the user with the code. */
    void revealed(Packet.LinkRevealed r) {
        Offer o = offers.get(r.linkId());
        if (o == null) return;
        if (!java.security.MessageDigest.isEqual(o.commitment(), DeviceLink.commitment(r.ephemeral()))) {
            offers.remove(r.linkId());
            listener.get().undecryptable("", "A computer asking to join broke its promise: someone may be in the middle");
            return;
        }
        offers.put(r.linkId(), new Offer(o.mine(), o.commitment(), r.ephemeral()));
        listener.get().linkRequest(new LinkPrompt(r.linkId(), r.name(),
                DeviceLink.code(r.ephemeral(), com.emirchat.crypto.X25519.encode(o.mine().getPublic()))));
    }

    /** Approves, after the user saw the same code on both computers: seals the account's key to it. */
    void approve(LinkPrompt prompt, Identity accountIdentity) throws IOException, ApiException {
        Offer o = offers.remove(prompt.linkId());
        if (o == null || o.theirs() == null) throw new ApiException(ErrorCode.INVALID_REQUEST, "That computer is no longer waiting.");
        DeviceLink.Sealed sealed = DeviceLink.seal(accountIdentity, o.mine(), o.theirs());
        connection.call(new Packet.LinkApprove(prompt.linkId(), sealed.ephemeral(), sealed.ciphertext()));
    }

    void deny(LinkPrompt prompt) throws IOException, ApiException {
        offers.remove(prompt.linkId());
        connection.call(new Packet.LinkDeny(prompt.linkId()));
    }
}
