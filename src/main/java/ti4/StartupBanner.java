package ti4;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Prints where to point a browser once the server is actually listening.
 *
 * <p>Two addresses, because there are two audiences: the machine running it,
 * and the phones around the table. A logged {@code http://} URL is also a
 * clickable link in IntelliJ's console, which saves typing it by hand.
 *
 * <p>Listens for {@link WebServerInitializedEvent} rather than reading
 * {@code server.port}, so the number printed is the port actually bound — the
 * two differ whenever the port is overridden on the command line. No web server
 * means no event, which is why this stays quiet during tests.
 */
@Component
class StartupBanner {

    private static final Logger log = LoggerFactory.getLogger(StartupBanner.class);

    @EventListener
    void announce(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();

        log.info("TI4 tracker is ready.");
        log.info("  On this PC:      http://localhost:{}", port);

        lanAddress().ifPresentOrElse(
                address -> log.info("  At the table:    http://{}:{}", address, port),
                () -> log.info("  At the table:    no LAN address found -- check Wi-Fi, "
                        + "or run 'ipconfig' and use the IPv4 address"));
    }

    /**
     * The first site-local IPv4 address on an interface that is up.
     *
     * <p>Deliberately not {@code InetAddress.getLocalHost()}: on a machine with
     * several adapters — a real one plus whatever VPN, Hyper-V or Docker has
     * installed — that returns whichever the OS feels like, which is often not
     * the one the phones can reach.
     */
    private static Optional<String> lanAddress() {
        try {
            return NetworkInterface.networkInterfaces()
                    .filter(StartupBanner::isUsable)
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(address -> address instanceof Inet4Address)
                    .filter(InetAddress::isSiteLocalAddress)
                    .map(InetAddress::getHostAddress)
                    .findFirst();
        } catch (SocketException e) {
            log.debug("Could not enumerate network interfaces.", e);
            return Optional.empty();
        }
    }

    private static boolean isUsable(NetworkInterface candidate) {
        try {
            return candidate.isUp() && !candidate.isLoopback() && !candidate.isVirtual();
        } catch (SocketException e) {
            return false;
        }
    }
}
