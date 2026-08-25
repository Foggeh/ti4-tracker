package ti4.domain;

public record Game(
        long id,
        String name,
        String createdAt,
        int vpTarget,
        int maxSecrets,
        boolean finished) {
}
