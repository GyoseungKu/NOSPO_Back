package kr.nospo.identity.domain;

import java.util.Locale;

public final class EmailAddress {
    private EmailAddress() {}

    public static String normalize(String value) {
        if (value == null) throw new IllegalArgumentException();
        String email = value.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254
                || !email.matches(
                        "[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
                || email.startsWith(".")
                || email.contains("..")
                || email.contains(".@")
                || email.indexOf('@') > 64) throw new IllegalArgumentException();
        return email;
    }
}
