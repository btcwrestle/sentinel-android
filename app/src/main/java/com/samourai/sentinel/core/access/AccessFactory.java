package com.samourai.sentinel.core.access;

import android.content.Context;

import androidx.annotation.Nullable;

import com.samourai.sentinel.util.Hash;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

//import android.util.Log;

public class AccessFactory {

    private static long TIMEOUT_DELAY = 1000 * 60 * 15;
    private static long lastPin = 0L;

    public static final int MIN_PIN_LENGTH = 5;
    public static final int MAX_PIN_LENGTH = 8;

    private static boolean isLoggedIn = false;
    private static String _pin = "";
    private static boolean isPinProtected = false;
    private static byte[] dataKey = null;

    private static AccessFactory instance = null;

    private AccessFactory() {
        ;
    }

    public static AccessFactory getInstance(Context ctx) {
        if (instance == null) {
            instance = new AccessFactory();
        }
        return instance;
    }

    public void setIsLoggedIn(boolean logged) {
        isLoggedIn = logged;
    }


    /**
     * Previous PIN verifier. New installs do not store one. Still used to
     * recognise a correct PIN while an older install is migrated.
     */
    public boolean validateHash(@NotNull String pin, String pinHash) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] b = digest.digest(pin.getBytes(StandardCharsets.UTF_8));
        Hash hash = new Hash(b);
        return hash.toString().equals(pinHash);
    }

    /**
     * Unlocked payload key for this process. Null until a PIN is entered,
     * and null when payload files are stored without a PIN.
     */
    @Nullable
    public byte[] getDataKey() {
        return dataKey;
    }

    public void setDataKey(@Nullable byte[] key) {
        if (dataKey != null && dataKey != key) {
            Arrays.fill(dataKey, (byte) 0);
        }
        dataKey = key;
    }

    public void clearDataKey() {
        setDataKey(null);
    }

    public String getPin() {
        return _pin;
    }

    /**
     * Note: {@code pin} may be null, which clears the in-memory PIN. Callers
     * such as the "clear wallet" flow rely on this.
     */
    public void setPin(String pin) {
        _pin = pin;
        if (pin != null && !pin.isEmpty()) {
            isPinProtected = true;
        }
        updatePin();
    }

    /**
     * True once a PIN has been supplied for this process.
     *
     * Used to prevent writing a payload in plaintext after {@link #setPin(String)}
     * has been passed null, which would produce a file that can never be
     * decrypted on the next launch.
     */
    public boolean isPinProtected() {
        return isPinProtected;
    }

    public void setPinProtected(boolean protectedFlag) {
        isPinProtected = protectedFlag;
    }

    /**
     * #300: true only after this process has passed MainActivity's
     * unlock (or the no-PIN path). A new process starts false, so an
     * activity Android restores before the PIN cannot read payloads.
     * Static on purpose: the Koin instance and getInstance() must agree,
     * and both already share the PIN fields this way.
     */
    private static boolean sessionAdmitted = false;

    public boolean isSessionAdmitted() {
        return sessionAdmitted;
    }

    public void admitSession() {
        sessionAdmitted = true;
    }


    public void updatePin() {
        lastPin = System.currentTimeMillis();
    }

    public boolean isTimedOut() {
        return (System.currentTimeMillis() - lastPin) > TIMEOUT_DELAY;
    }

    public void reset() {
        lastPin = 0L;
    }
}
