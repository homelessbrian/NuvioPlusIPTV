package com.nuvio.vpn.android;

import android.security.keystore.KeyGenParameterSpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.Key;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Host-only keys for exercising the real encrypted store. This does not model
 * hardware-backed Android Keystore isolation, consent, or key invalidation. */
final class TestAndroidKeyStore implements AutoCloseable {
    private static final Map<String, Key> keys = new ConcurrentHashMap<>();
    private final Provider previous;

    TestAndroidKeyStore() {
        previous = Security.getProvider("AndroidKeyStore");
        Security.removeProvider("AndroidKeyStore");
        Provider provider = new Provider("AndroidKeyStore", 1.0, "Temporary host VPN test keys") {};
        provider.put("KeyStore.AndroidKeyStore", Store.class.getName());
        provider.put("KeyGenerator.AES", Generator.class.getName());
        Security.addProvider(provider);
        keys.clear();
    }

    @Override public void close() {
        keys.clear();
        Security.removeProvider("AndroidKeyStore");
        if (previous != null) Security.addProvider(previous);
    }

    public static final class Store extends KeyStoreSpi {
        @Override public Key engineGetKey(String alias, char[] password) { return keys.get(alias); }
        @Override public Certificate[] engineGetCertificateChain(String alias) { return null; }
        @Override public Certificate engineGetCertificate(String alias) { return null; }
        @Override public Date engineGetCreationDate(String alias) { return keys.containsKey(alias) ? new Date(0) : null; }
        @Override public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) { keys.put(alias, key); }
        @Override public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) throws KeyStoreException { throw new KeyStoreException("Unsupported host fixture entry"); }
        @Override public void engineSetCertificateEntry(String alias, Certificate certificate) throws KeyStoreException { throw new KeyStoreException("Unsupported host fixture entry"); }
        @Override public void engineDeleteEntry(String alias) { keys.remove(alias); }
        @Override public Enumeration<String> engineAliases() { return Collections.enumeration(keys.keySet()); }
        @Override public boolean engineContainsAlias(String alias) { return keys.containsKey(alias); }
        @Override public int engineSize() { return keys.size(); }
        @Override public boolean engineIsKeyEntry(String alias) { return keys.containsKey(alias); }
        @Override public boolean engineIsCertificateEntry(String alias) { return false; }
        @Override public String engineGetCertificateAlias(Certificate certificate) { return null; }
        @Override public void engineStore(OutputStream output, char[] password) { }
        @Override public void engineLoad(InputStream input, char[] password) { }
    }

    public static final class Generator extends KeyGeneratorSpi {
        private String alias;
        @Override protected void engineInit(SecureRandom random) { throw new IllegalArgumentException("A test alias is required"); }
        @Override protected void engineInit(int size, SecureRandom random) { throw new IllegalArgumentException("A test alias is required"); }
        @Override protected void engineInit(AlgorithmParameterSpec parameters, SecureRandom random) throws InvalidAlgorithmParameterException {
            if (!(parameters instanceof KeyGenParameterSpec)) throw new InvalidAlgorithmParameterException("A test alias is required");
            alias = ((KeyGenParameterSpec) parameters).getKeystoreAlias();
        }
        @Override protected SecretKey engineGenerateKey() {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            SecretKey key = new SecretKeySpec(bytes, "AES");
            java.util.Arrays.fill(bytes, (byte) 0);
            keys.put(alias, key);
            return key;
        }
    }
}
