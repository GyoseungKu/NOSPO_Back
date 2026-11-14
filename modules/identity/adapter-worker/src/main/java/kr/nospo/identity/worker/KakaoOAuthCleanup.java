package kr.nospo.identity.worker;

import kr.nospo.identity.application.KakaoOAuthService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="nospo.auth.kakao.cleanup-enabled", havingValue="true", matchIfMissing=true)
public class KakaoOAuthCleanup {
    private final KakaoOAuthService service;
    public KakaoOAuthCleanup(KakaoOAuthService service) { this.service = service; }
    @Scheduled(fixedDelay=60000)
    public void cleanup() { service.cleanup(); }
}
