package kr.nospo.book.application;

import org.springframework.stereotype.Component;

@Component
public class KakaoShelfStoragePolicy {
    /** A shared permanent catalog requires an explicit decision about fields, retention and redisplay. */
    public boolean allowsSharedEditionCreation() {
        return false;
    }
}
