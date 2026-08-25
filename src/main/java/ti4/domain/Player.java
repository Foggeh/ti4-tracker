package ti4.domain;

public record Player(
        long id,
        long gameId,
        String name,
        String faction,
        String color,
        Integer seat) {
}
