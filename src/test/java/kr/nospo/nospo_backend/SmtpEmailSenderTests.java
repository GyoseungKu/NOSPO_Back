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
