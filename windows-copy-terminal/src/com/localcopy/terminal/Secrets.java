package com.localcopy.terminal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class Secrets {
    private static final String ALIAS = "binance-copy-api-v1";

    final String key;
    final String secret;
    Secrets(String key, String secret) {
        if (!key.matches("[A-Za-z0-9_-]{8,160}") || !secret.matches("[A-Za-z0-9+/=_-]{8,240}")) {
            throw new IllegalArgumentException("请输入币安 HMAC API Key 和 Secret");
        }
        this.key = key;
        this.secret = secret;
    }

    static void save(LocalData store, String realm, Secrets value) throws Exception {
        String sealed = seal(value, realm, key(store,true));
        if (!store.prefs.edit().putString("credentials_" + realm, sealed).commit()) {
            throw new IllegalStateException("凭证保存失败");
        }
    }

    static String seal(Secrets value, String realm, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(realm.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = new JSONObject().put("k", value.key).put("s", value.secret)
                .toString().getBytes(StandardCharsets.UTF_8);
        try {
            Base64.Encoder encoder = Base64.getEncoder();
            return encoder.encodeToString(cipher.getIV()) + "." + encoder.encodeToString(cipher.doFinal(bytes));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static Secrets load(LocalData store, String realm) throws Exception {
        String text = store.prefs.getString("credentials_" + realm, "");
        if (text.isEmpty()) throw new IllegalStateException("请先保存所选环境的 API 密钥");
        try {
            return open(text, realm, key(store,false));
        } catch (Exception invalid) {
            throw new IllegalStateException("本机无法解密已保存的 API 密钥，请核对原账户密钥", invalid);
        }
    }

    static Secrets open(String encrypted, String realm, SecretKey key) throws Exception {
        String[] items = encrypted.split("\\.", -1);
        if (items.length != 2 || encrypted.length() > 2048) throw new GeneralSecurityException("密钥密文格式无效");
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] iv = decoder.decode(items[0]);
        byte[] ciphertext = decoder.decode(items[1]);
        if (iv.length != 12 || ciphertext.length < 16) throw new GeneralSecurityException("密钥密文格式无效");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(realm.getBytes(StandardCharsets.UTF_8));
        byte[] raw = cipher.doFinal(ciphertext);
        try {
            JSONObject data = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            return new Secrets(data.getString("k"), data.getString("s"));
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    private static synchronized SecretKey key(LocalData store, boolean create) throws Exception {
        String wrapped=store.prefs.getString("windows_master","");
        if(wrapped.isEmpty()){
            if(!create) throw new GeneralSecurityException("本机凭证不可用");
            KeyGenerator generator=KeyGenerator.getInstance("AES");
            generator.init(256);
            SecretKey key=generator.generateKey();
            String protectedText=Bridge.call("protect", new JSONObject().put("value",Base64.getEncoder().encodeToString(key.getEncoded()))).getString("value");
            if(!store.prefs.edit().putString("windows_master",protectedText).commit()) throw new java.io.IOException("无法保存加密密钥");
            return key;
        }
        byte[] raw=Base64.getDecoder().decode(Bridge.call("unprotect",new JSONObject().put("value",wrapped)).getString("value"));
        try{return new javax.crypto.spec.SecretKeySpec(raw,"AES");}finally{Arrays.fill(raw,(byte)0);}
    }
}
