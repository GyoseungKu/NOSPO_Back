package kr.nospo.lifecycle.application;

import kr.nospo.support.Secrets;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Per-operation AES-256-GCM data key, envelope-wrapped with the existing AUTH key.
 * DB/backups containing both envelope and wrapping key remain recoverable: not crypto erasure. */
@Component
public class WithdrawalArchiveCrypto {
    public record Encrypted(byte[] ciphertext, byte[] wrappedKey) {
        @Override public String toString(){return "WithdrawalArchive[REDACTED]";}
    }
    private final Secrets secrets;
    private final SecureRandom random=new SecureRandom();
    public WithdrawalArchiveCrypto(Secrets secrets){this.secrets=secrets;}
    private String context(UUID operation){return "withdrawal-archive:v1:"+operation;}
    public Encrypted encrypt(UUID operation,byte[] plaintext){
        byte[] key=new byte[32];random.nextBytes(key);
        try{return new Encrypted(crypt(Cipher.ENCRYPT_MODE,key,plaintext,context(operation)),
                secrets.encrypt(key,context(operation)+":key"));}
        finally{Arrays.fill(key,(byte)0);}
    }
    public byte[] decrypt(UUID operation,Encrypted encrypted){
        byte[] key=secrets.decrypt(encrypted.wrappedKey(),context(operation)+":key");
        try{return crypt(Cipher.DECRYPT_MODE,key,encrypted.ciphertext(),context(operation));}
        finally{Arrays.fill(key,(byte)0);}
    }
    private byte[] crypt(int mode,byte[] key,byte[] value,String context){
        try{
            byte[] nonce=new byte[12];
            if(mode==Cipher.ENCRYPT_MODE)random.nextBytes(nonce);
            else {if(value.length<28)throw new GeneralSecurityException();System.arraycopy(value,0,nonce,0,12);}
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            if(mode==Cipher.DECRYPT_MODE)return cipher.doFinal(value,12,value.length-12);
            byte[] encrypted=cipher.doFinal(value),result=Arrays.copyOf(nonce,12+encrypted.length);
            System.arraycopy(encrypted,0,result,12,encrypted.length);return result;
        }catch(GeneralSecurityException ex){throw new IllegalStateException("Withdrawal archive cryptography failed");}
    }
}
