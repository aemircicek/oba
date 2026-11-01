package com.emirchat.protocol;

import com.emirchat.crypto.Hkdf;
import com.emirchat.crypto.Identity;
import com.emirchat.trust.PeerRejectedException;
import com.emirchat.trust.TrustPolicy;
import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.regex.Pattern;
import javax.crypto.KeyAgreement;

/**
 * Mutually authenticated key exchange in the style of SIGMA-I (the design behind the IKE and
 * TLS 1.3 handshakes):
 *
 * <pre>
 *  client                                              server
 *  HELLO  { version, ephemeral X25519 key, nonce } -->
 *         <-- HELLO { version, ephemeral X25519 key, nonce }
 *         both: shared = X25519(eph, peer eph)
 *               th     = SHA-256(label || client HELLO || server HELLO)
 *               handshake keys = HKDF(salt = th, ikm = shared)
 *         <-- AUTH_s (encrypted) { name, identity key, Sig_s(th, name, key) }
 *  AUTH_c (encrypted) { name, identity key, Sig_c(th, AUTH_s, name, key) } -->
 *         <-- FINISHED (encrypted)   server accepted the client
 *         both: traffic keys = HKDF(salt = SHA-256(th || AUTH_s || AUTH_c), ikm = shared)
 * </pre>
 *
 * Properties: forward secrecy (fresh ephemeral keys per session), mutual authentication
 * (each side signs the transcript, so ephemeral keys cannot be swapped), identity hiding from
 * passive observers (AUTH messages are encrypted), and separate keys per direction.
 */
public final class Handshake {
    public static final int VERSION = 1;
    public static final int MAX_NAME_BYTES = 32;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,32}");

    private static final byte[] LABEL = "SecureChat-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SERVER_SIG_CONTEXT = "SecureChat-v1 server signature".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLIENT_SIG_CONTEXT = "SecureChat-v1 client signature".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FINISHED = "SecureChat-v1 server finished".getBytes(StandardCharsets.UTF_8);
    private static final int X25519_ENCODED_LENGTH = 44;
    private static final int NONCE_LENGTH = 32;
    private static final int SIGNATURE_LENGTH = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Handshake() {}

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static Session asClient(FrameIO io, Identity me, String myName, TrustPolicy trust)
            throws IOException, PeerRejectedException {
        requireValidName(myName);
        KeyPair ephemeral = newEphemeral();
        byte[] clientHello = hello(ephemeral);
        io.write(clientHello);
        byte[] serverHello = io.read();
        byte[] peerEphemeral = parseHello(serverHello);

        Keys keys = new Keys(ephemeral, peerEphemeral, clientHello, serverHello);
        try {
            SecureChannel hs = new SecureChannel(io, keys.handshake("hs c2s"), keys.handshake("hs s2c"));

            byte[] serverAuth = hs.receive();
            Peer server = parseAuth(serverAuth, SERVER_SIG_CONTEXT, keys.transcript);
            trust.check(server.name, server.fingerprint);

            byte[] clientAuth = auth(me, myName, CLIENT_SIG_CONTEXT, concat(keys.transcript, serverAuth));
            hs.send(clientAuth);

            // Do not report success until the server has verified and accepted us.
            byte[] finished;
            try {
                finished = hs.receive();
            } catch (EOFException e) {
                throw new IOException("Peer closed the connection during the handshake"
                        + " (it may have rejected your identity key)", e);
            }
            if (!MessageDigest.isEqual(finished, FINISHED)) throw new ProtocolException("Invalid FINISHED message");

            keys.finish(serverAuth, clientAuth);
            return new Session(new SecureChannel(io, keys.traffic("app c2s"), keys.traffic("app s2c")),
                    server.name, server.fingerprint, server.key);
        } finally {
            keys.destroy();
        }
    }

    public static Session asServer(FrameIO io, Identity me, String myName, TrustPolicy trust)
            throws IOException, PeerRejectedException {
        requireValidName(myName);
        byte[] clientHello = io.read();
        byte[] peerEphemeral = parseHello(clientHello);
        KeyPair ephemeral = newEphemeral();
        byte[] serverHello = hello(ephemeral);
        io.write(serverHello);

        Keys keys = new Keys(ephemeral, peerEphemeral, clientHello, serverHello);
        try {
            SecureChannel hs = new SecureChannel(io, keys.handshake("hs s2c"), keys.handshake("hs c2s"));

            byte[] serverAuth = auth(me, myName, SERVER_SIG_CONTEXT, keys.transcript);
            hs.send(serverAuth);

            byte[] clientAuth = hs.receive();
            Peer client = parseAuth(clientAuth, CLIENT_SIG_CONTEXT, concat(keys.transcript, serverAuth));
            trust.check(client.name, client.fingerprint);
            hs.send(FINISHED);

            keys.finish(serverAuth, clientAuth);
            return new Session(new SecureChannel(io, keys.traffic("app s2c"), keys.traffic("app c2s")),
                    client.name, client.fingerprint, client.key);
        } finally {
            keys.destroy();
        }
    }

    // ---------------------------------------------------------------- messages

    private static byte[] hello(KeyPair ephemeral) {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        return new Wire.Writer().u8(VERSION).bytes(ephemeral.getPublic().getEncoded()).bytes(nonce).toByteArray();
    }

    private static byte[] parseHello(byte[] message) throws ProtocolException {
        Wire.Reader r = new Wire.Reader(message);
        int version = r.u8();
        if (version != VERSION) throw new ProtocolException("Unsupported protocol version " + version);
        byte[] ephemeral = r.exactBytes(X25519_ENCODED_LENGTH);
        r.exactBytes(NONCE_LENGTH);
        r.expectEnd();
        return ephemeral;
    }

    private static byte[] auth(Identity me, String name, byte[] context, byte[] transcript) {
        byte[] key = me.publicKeyBytes();
        byte[] signature = me.sign(signedData(context, transcript, name, key));
        return new Wire.Writer().string(name).bytes(key).bytes(signature).toByteArray();
    }

    private record Peer(String name, String fingerprint, byte[] key) {}

    private static Peer parseAuth(byte[] message, byte[] context, byte[] transcript) throws ProtocolException {
        Wire.Reader r = new Wire.Reader(message);
        String name = r.string(MAX_NAME_BYTES);
        byte[] key = r.exactBytes(Identity.ENCODED_PUBLIC_KEY_LENGTH);
        byte[] signature = r.exactBytes(SIGNATURE_LENGTH);
        r.expectEnd();
        if (!isValidName(name)) throw new ProtocolException("Peer sent an invalid name");
        if (!Identity.verify(key, signedData(context, transcript, name, key), signature)) {
            throw new SecurityException("Peer's handshake signature is invalid");
        }
        return new Peer(name, Identity.fingerprint(key), key);
    }

    private static byte[] signedData(byte[] context, byte[] transcript, String name, byte[] key) {
        return new Wire.Writer().bytes(context).bytes(transcript).string(name).bytes(key).toByteArray();
    }

    // ---------------------------------------------------------------- key schedule

    private static final class Keys {
        private final byte[] shared;
        private final byte[] transcript;
        private byte[] handshakeSecret;
        private byte[] trafficSecret;

        Keys(KeyPair ephemeral, byte[] peerEphemeral, byte[] clientHello, byte[] serverHello) throws ProtocolException {
            this.shared = x25519(ephemeral, peerEphemeral);
            this.transcript = sha256(LABEL, clientHello, serverHello);
            this.handshakeSecret = Hkdf.extract(transcript, shared);
        }

        byte[] handshake(String label) {
            return Hkdf.expand(handshakeSecret, label.getBytes(StandardCharsets.UTF_8), 32);
        }

        void finish(byte[] serverAuth, byte[] clientAuth) {
            trafficSecret = Hkdf.extract(sha256(transcript, serverAuth, clientAuth), shared);
        }

        byte[] traffic(String label) {
            return Hkdf.expand(trafficSecret, label.getBytes(StandardCharsets.UTF_8), 32);
        }

        /** Best-effort wiping; the JVM may still hold copies, see README "Limitations". */
        void destroy() {
            Arrays.fill(shared, (byte) 0);
            if (handshakeSecret != null) Arrays.fill(handshakeSecret, (byte) 0);
            if (trafficSecret != null) Arrays.fill(trafficSecret, (byte) 0);
        }
    }

    private static KeyPair newEphemeral() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 unavailable", e);
        }
    }

    private static byte[] x25519(KeyPair ephemeral, byte[] peerEncoded) throws ProtocolException {
        try {
            PublicKey peer = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(peerEncoded));
            KeyAgreement agreement = KeyAgreement.getInstance("X25519");
            agreement.init(ephemeral.getPrivate());
            agreement.doPhase(peer, true);
            byte[] secret = agreement.generateSecret();
            // Reject low-order points, which would force a predictable all-zero secret.
            int acc = 0;
            for (byte b : secret) acc |= b;
            if (acc == 0) throw new ProtocolException("Peer sent a low-order X25519 key");
            return secret;
        } catch (GeneralSecurityException e) {
            throw new ProtocolException("Invalid peer ephemeral key: " + e.getMessage());
        }
    }

    private static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                digest.update(new Wire.Writer().bytes(part).toByteArray());
            }
            return digest.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void requireValidName(String name) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("Name must be 1-32 characters: letters, digits, '_', '.', '-'");
        }
    }
}
