package kr.nospo.identity.contract;

import java.util.List;
import java.util.UUID;

public interface LoginMethods {
    record Status(List<String> loginMethods, boolean canAddEmail, boolean canLinkKakao) {
        public Status {
            loginMethods = List.copyOf(loginMethods);
        }
    }

    Status get(UUID member);
}
