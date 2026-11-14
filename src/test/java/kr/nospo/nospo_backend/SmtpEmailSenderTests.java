package kr.nospo.nospo_backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import kr.nospo.identity.application.EmailSender;
import kr.nospo.identity.integration.SmtpEmailSender;

import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpEmailSenderTests {
    @Test
    void emailLoginLinkUsesPurposeFragmentAndRawTokenWithoutLoginPrefix() {
        var mail = mock(JavaMailSender.class);
        var sender = new SmtpEmailSender(mail, "noreply@example.com", "https://nospo.example/auth/confirm");
        sender.send("reader@example.com", "LOGIN:test-token");
        var message = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        assertThat(message.getValue().getSubject()).isEqualTo("NOSPO 이메일 로그인 추가 인증");
        assertThat(message.getValue().getText()).contains("https://nospo.example/auth/confirm#purpose=ADD_EMAIL_LOGIN&token=test-token")
                .doesNotContain("LOGIN:", "?token=");
    }
    @Test
    void provider5xxIsPermanentAnd4xxIsRetryable() {
        for (int status : new int[] {451, 550}) {
            var mail = mock(JavaMailSender.class);
            var exception =
                    new SMTPSendFailedException(
                            "DATA", status, "provider response", null, null, null, null);
            doThrow(new MailSendException("send failed", exception))
                    .when(mail)
                    .send(any(SimpleMailMessage.class));
            var sender =
                    new SmtpEmailSender(
                            mail, "noreply@example.com", "https://nospo.example/auth/confirm");
            assertThatThrownBy(() -> sender.send("reader@example.com", "test-token"))
                    .isInstanceOfSatisfying(
                            EmailSender.DeliveryFailure.class,
                            e -> assertThat(e.permanent()).isEqualTo(status >= 500));
        }
    }
}
