package com.emirchat.crypto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * A long-term Ed25519 identity key pair. The public key's SHA-256 fingerprint is what
 * users compare out-of-band to rule out man-in-the-middle attacks.
 */
public final class Identity {
    public static final String ALGORITHM = "Ed25519";
    /** X.509 SubjectPublicKeyInfo encoding of an Ed25519 key is always 44 bytes. */
    public static final int ENCODED_PUBLIC_KEY_LENGTH = 44;

    private static final String PRIVATE_FILE = "identity.key";
    private static final String PUBLIC_FILE = "identity.pub";

    private final KeyPair keyPair;

    private Identity(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static Identity generate() {
        try {
            return new Identity(KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    }

    /** Loads the identity stored in {@code dir}, creating a new one (with owner-only permissions) if absent. */
    public static Identity loadOrCreate(Path dir) throws IOException {
        Path privatePath = dir.resolve(PRIVATE_FILE);
        Path publicPath = dir.resolve(PUBLIC_FILE);
        if (Files.exists(privatePath)) {
            return load(privatePath, publicPath);
        }
        createPrivateDirectory(dir);
        Identity identity = generate();
        writeOwnerOnly(privatePath, identity.keyPair.getPrivate().getEncoded());
        Files.write(publicPath, identity.publicKeyBytes());
        return identity;
    }

    private static Identity load(Path privatePath, Path publicPath) throws IOException {
        try {
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privatePath)));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicPath)));
            Identity identity = new Identity(new KeyPair(publicKey, privateKey));
            byte[] probe = "identity-self-test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (!verify(identity.publicKeyBytes(), probe, identity.sign(probe))) {
                throw new IOException("Identity files do not match: " + privatePath + " / " + publicPath);
            }
            return identity;
        } catch (GeneralSecurityException e) {
            throw new IOException("Corrupt identity files in " + privatePath.getParent(), e);
        }
    }

    public byte[] publicKeyBytes() {
        return keyPair.getPublic().getEncoded();
    }

    public String fingerprint() {
        return fingerprint(publicKeyBytes());
    }

    public byte[] sign(byte[] data) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(keyPair.getPrivate());
            signature.update(data);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    public static boolean verify(byte[] encodedPublicKey, byte[] data, byte[] sig) {
        try {
            PublicKey key = KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(encodedPublicKey));
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(key);
            signature.update(data);
            return signature.verify(sig);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** SHA-256 of the encoded public key as 64 hex characters. */
    public static String fingerprint(byte[] encodedPublicKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encodedPublicKey));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Groups a fingerprint into blocks of four characters for easier visual comparison. */
    public static String pretty(String fingerprint) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fingerprint.length(); i += 4) {
            if (i > 0) sb.append(i % 32 == 0 ? "\n" : " ");
            sb.append(fingerprint, i, Math.min(i + 4, fingerprint.length()));
        }
        return sb.toString();
    }

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static void createPrivateDirectory(Path dir) throws IOException {
        if (Files.isDirectory(dir)) return;
        Path absolute = dir.toAbsolutePath();
        if (posix(absolute)) {
            Files.createDirectories(absolute, PosixFilePermissions.asFileAttribute(
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE)));
        } else {
            Files.createDirectories(absolute);
        }
    }

    private static void writeOwnerOnly(Path path, byte[] content) throws IOException {
        Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        FileAttribute<?>[] attrs = posix(path.toAbsolutePath())
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))}
                : new FileAttribute<?>[0];
        try (SeekableByteChannel channel = Files.newByteChannel(path, options, attrs)) {
            channel.write(ByteBuffer.wrap(content));
        } catch (FileAlreadyExistsException e) {
            throw new IOException("Refusing to overwrite existing key file " + path, e);
        }
    }
}
