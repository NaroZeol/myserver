package app.thoughts.mobile;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.jcraft.jsch.Identity;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

/** Private key never leaves Android Keystore. JSch delegates signatures here. */
final class DeviceKey implements Identity {

  private static final String ALIAS = "thoughts-ssh-device-v1";
  private final PrivateKey key;
  private final byte[] publicBlob;

  DeviceKey() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias(ALIAS)) {
      KeyPairGenerator generator = KeyPairGenerator.getInstance(
        "RSA",
        "AndroidKeyStore"
      );
      generator.initialize(
        new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
          .setKeySize(3072)
          .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
          .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
          .build()
      );
      generator.generateKeyPair();
    }
    key = (PrivateKey) store.getKey(ALIAS, null);
    RSAPublicKey pub = (RSAPublicKey) store
      .getCertificate(ALIAS)
      .getPublicKey();
    publicBlob = pack(
      "ssh-rsa".getBytes("UTF-8"),
      pub.getPublicExponent().toByteArray(),
      pub.getModulus().toByteArray()
    );
  }

  private static byte[] pack(byte[]... fields) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    for (byte[] field : fields) {
      out.writeInt(field.length);
      out.write(field);
    }
    return bytes.toByteArray();
  }

  String publicKey() {
    return (
      "ssh-rsa " +
      Base64.encodeToString(publicBlob, Base64.NO_WRAP) +
      " thoughts-phone"
    );
  }

  String fingerprint() throws Exception {
    return (
      "SHA256:" +
      Base64.encodeToString(
        java.security.MessageDigest.getInstance("SHA-256").digest(publicBlob),
        Base64.NO_WRAP | Base64.NO_PADDING
      )
    );
  }

  public byte[] getPublicKeyBlob() {
    return publicBlob.clone();
  }

  public byte[] getSignature(byte[] data) {
    return getSignature(data, "rsa-sha2-256");
  }

  public byte[] getSignature(byte[] data, String algorithm) {
    try {
      if (
        !algorithm.equals("rsa-sha2-256") && !algorithm.equals("rsa-sha2-512")
      ) return null;
      Signature signer = Signature.getInstance(
        algorithm.equals("rsa-sha2-512") ? "SHA512withRSA" : "SHA256withRSA"
      );
      signer.initSign(key);
      signer.update(data);
      return pack(algorithm.getBytes("UTF-8"), signer.sign());
    } catch (Exception e) {
      return null;
    }
  }

  public String getAlgName() {
    return "ssh-rsa";
  }

  public String getName() {
    return ALIAS;
  }

  public boolean isEncrypted() {
    return false;
  }

  public boolean setPassphrase(byte[] ignored) {
    return true;
  }

  public void clear() {
    /* The system owns the non-exportable key. */
  }
}
