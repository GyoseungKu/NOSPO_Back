package kr.nospo.member.domain;

import java.text.Normalizer;
import java.util.Locale;

public final class MemberInput {
    private MemberInput() {}

    public static String nickname(String value) {
        if (value == null) throw new IllegalArgumentException();
        String n = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        if (!n.matches("[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9_]{2,20}")) throw new IllegalArgumentException();
        return n;
    }

    public static String nicknameKey(String value) {
        return nickname(value).toLowerCase(Locale.ROOT);
    }

    public static String phone(String value) {
        if (value == null) throw new IllegalArgumentException();
        String p = value.replace("-", "").replace(" ", "");
        if (p.matches("01[016789][0-9]{7,8}")) p = "+82" + p.substring(1);
        if (!p.matches("\\+[1-9][0-9]{7,14}")) throw new IllegalArgumentException();
        return p;
    }
}
