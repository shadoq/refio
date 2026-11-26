package com.acme.invoice;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StringReader;
import java.util.Base64;
import java.util.Properties;

/** The two cookies the portal sets: the signed-in identity and the customer's list preferences. */
public final class SessionCookie {
    private SessionCookie() {
    }

    /** Restores the identity that the login handler serialized into the cookie (Base64 for old proxies). */
    public static Long parseSession(String cookie) {
        try {
            byte[] blob = Base64.getUrlDecoder().decode(cookie);
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(blob))) {
                return (Long) in.readObject();
            }
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            return null;
        }
    }

    /** Serializes the customer id for the login handler, the way {@link #parseSession} reads it. */
    public static String writeSession(long customerId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(customerId);
            }
            return Base64.getUrlEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("cannot write session cookie", e);
        }
    }

    /** Preferences travel as a plain {@code key=value} list; nothing in it is turned back into an object. */
    public static Properties parsePreferences(String cookie) {
        Properties preferences = new Properties();
        try {
            preferences.load(new StringReader(cookie.replace('&', '\n')));
        } catch (IOException e) {
            throw new IllegalArgumentException("unreadable preferences cookie", e);
        }
        return preferences;
    }
}
