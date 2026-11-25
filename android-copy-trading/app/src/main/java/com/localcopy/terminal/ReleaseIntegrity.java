package com.localcopy.terminal;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/** 发布构建在开启交易相关功能前核对安装包签名，校验值来自构建配置。 */
final class ReleaseIntegrity {
    static void requireTrustedPackage(Context context) {
        String expected = BuildConfig.RELEASE_CERT_SHA256;
        if (expected.isEmpty()) return;
        try {
            SigningInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES)).signingInfo;
            if (info != null && matches(info.getApkContentsSigners(), expected)) return;
        } catch (PackageManager.NameNotFoundException | GeneralSecurityException unavailable) {
            // A missing certificate cannot authenticate the package.
        }
        throw new IllegalStateException("安装包签名校验失败，请使用原签名的发布包");
    }

    static boolean matches(Signature[] signatures, String expected) throws GeneralSecurityException {
        if (signatures == null || signatures.length != 1 || !expected.matches("[0-9a-fA-F]{64}")) return false;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
        byte[] pinned = new byte[digest.length];
        for (int i = 0; i < pinned.length; i++) {
            pinned[i] = (byte) Integer.parseInt(expected.substring(i * 2, i * 2 + 2), 16);
        }
        return MessageDigest.isEqual(digest, pinned);
    }
}
