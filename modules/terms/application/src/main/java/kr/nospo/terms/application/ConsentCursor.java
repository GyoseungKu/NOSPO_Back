package kr.nospo.terms.application;

import kr.nospo.support.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Component
public class ConsentCursor {
    private final Secrets secrets;
    public ConsentCursor(Secrets secrets) { this.secrets=secrets; }

    public String encode(UUID member, ConsentStore.Position position) {
        String body="v1|"+member+"|"+position.ceiling()+"|"+position.agreedAt()+"|"+position.consentId();
        return base64(body.getBytes(StandardCharsets.UTF_8))+"."+base64(secrets.fingerprint("consents:"+body));
    }

    public ConsentStore.Position decode(UUID member, String cursor) {
        try {
            if(cursor.length()>768) throw new IllegalArgumentException();
            String[] parts=cursor.split("\\.",-1);
            if(parts.length!=2) throw new IllegalArgumentException();
            byte[] bytes=Base64.getUrlDecoder().decode(parts[0]);
            if(!base64(bytes).equals(parts[0])) throw new IllegalArgumentException();
            String body=new String(bytes,StandardCharsets.UTF_8);
            byte[] signature=Base64.getUrlDecoder().decode(parts[1]);
            if(!base64(signature).equals(parts[1]) ||
                    !MessageDigest.isEqual(secrets.fingerprint("consents:"+body),signature))
                throw new IllegalArgumentException();
            String[] fields=body.split("\\|",-1);
            if(fields.length!=5 || !"v1".equals(fields[0]) || !member.equals(UUID.fromString(fields[1])))
                throw new IllegalArgumentException();
            long ceiling=Long.parseLong(fields[2]);
            if(ceiling<1) throw new IllegalArgumentException();
            return new ConsentStore.Position(ceiling,Instant.parse(fields[3]),UUID.fromString(fields[4]));
        } catch(RuntimeException invalid) { throw new ApiException(400,"INVALID_CURSOR"); }
    }

    private static String base64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
