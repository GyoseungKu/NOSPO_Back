package kr.nospo.member.application;

public interface ProfileImageCodec {
    byte[] sanitize(byte[] input);
}
