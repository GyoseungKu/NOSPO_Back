package kr.nospo.identity.integration;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;

import kr.nospo.identity.application.EmailSender;

import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class SmtpEmailSender implements EmailSender {
    private final JavaMailSender mail;
    private final String from, confirmUrl;

    public SmtpEmailSender(
            JavaMailSender mail,
            @Value("${nospo.mail.from}") String from,
            @Value("${nospo.auth.email-confirm-url}") String confirmUrl) {
        this.mail = mail;
        this.from = from;
        this.confirmUrl = confirmUrl;
        URI uri = URI.create(confirmUrl);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getFragment() != null
                || uri.getQuery() != null)
            throw new IllegalArgumentException(
                    "email-confirm-url must be an HTTP(S) page without query or fragment");
    }

    public void send(String recipient, String token) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject("NOSPO 이메일 인증");
        // Fragment prevents the token from entering server/proxy access logs. The frontend POSTs it
        // to confirm.
        message.setText(
                "아래 페이지에서 이메일 인증을 완료해 주세요. 링크 유효기간은 요청 후 10분입니다.\n"
                        + confirmUrl
                        + "#token="
                        + token);
        if (token.startsWith("RESET:")) {
            message.setSubject("NOSPO 비밀번호 재설정");
            message.setText("비밀번호 재설정 인증번호: " + token.substring(6) + "\n유효기간은 10분입니다.");
        }
        if (token.startsWith("CHANGE:")) {
            message.setSubject("NOSPO 이메일 변경 인증");
            message.setText("이메일 변경 인증번호: " + token.substring(7) + "\n유효기간은 10분입니다.");
        }
        try {
            mail.send(message);
        } catch (MailException ex) {
            throw new DeliveryFailure(isPermanent(ex));
        }
    }

    private boolean isPermanent(Throwable error) {
        var pending = new java.util.ArrayDeque<Throwable>();
        var visited =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<Throwable, Boolean>());
        pending.add(error);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current instanceof MailAuthenticationException) return true;
            if (current instanceof SMTPAddressFailedException smtp && smtp.getReturnCode() >= 500)
                return true;
            if (current instanceof SMTPSendFailedException smtp && smtp.getReturnCode() >= 500)
                return true;
            if (current instanceof SendFailedException failed
                    && failed.getInvalidAddresses() != null
                    && failed.getInvalidAddresses().length > 0) return true;
            if (current instanceof MailSendException send)
                java.util.Collections.addAll(pending, send.getMessageExceptions());
            if (current instanceof MessagingException mailError
                    && mailError.getNextException() != null)
                pending.add(mailError.getNextException());
            if (current.getCause() != null) pending.add(current.getCause());
        }
        return false;
    }
}
