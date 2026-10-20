package dev.ghost.wozai;
/** Fresh private material confined to controlled test peers. */
final class TestNoise {
    static byte[] key(){byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);return key;}
}
