package cn.openpocket.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Tokens are encrypted with a non-exportable Android Keystore key. */
final class SecureStore {
    private final String alias;
    private final SharedPreferences prefs;
    SecureStore(Context context) { this(context,"session","openpocket.session.v1"); }
    SecureStore(Context context,String preferences,String alias) { prefs = context.getSharedPreferences(preferences, Context.MODE_PRIVATE);this.alias=alias; }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(alias, null);
    }
    void saveToken(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        String payload = Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        prefs.edit().putString("token", iv + ":" + payload).putBoolean("expired", false).commit();
    }
    String token() {
        try {
            String[] parts = prefs.getString("token", "").split(":"); if (parts.length != 2) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { clear(); return ""; }
    }
    String server() { return prefs.getString("server", ""); }
    void server(String value) { prefs.edit().putString("server", value).apply(); }
    String email() { return prefs.getString("email", ""); }
    void email(String value) { prefs.edit().putString("email", value).apply(); }
    String lastChat() { return prefs.getString("last_chat", ""); }
    void lastChat(String value) { prefs.edit().putString("last_chat", value).apply(); }
    boolean expired() { return prefs.getBoolean("expired", false); }
    void expired(boolean value) { prefs.edit().putBoolean("expired", value).apply(); }
    void clear() { prefs.edit().remove("token").remove("last_chat").remove("expired").apply(); }
}
