package vn.edu.vnu.registration.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

public class GeneralUtil {
    public static boolean within(Instant now, Instant from, Instant until) {
        return !now.isBefore(from) && now.isBefore(until);
    }

    public static String requestHash(List<Long> sorted) {
        try {
            var canonical = "{\"sectionIds\":[" + sorted.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]}";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
