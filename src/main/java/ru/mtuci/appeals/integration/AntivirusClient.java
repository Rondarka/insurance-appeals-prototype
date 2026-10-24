package ru.mtuci.appeals.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Антивирусная проверка вложений (решение 39): clamd из ClamAV по его протоколу INSTREAM,
 * без отдельной библиотеки. Файл передаётся порциями, clamd отвечает «stream: OK» или
 * «stream: <угроза> FOUND».
 *
 * Если адрес не задан, проверка выключена. В стенде ClamAV по умолчанию не поднимается: он
 * тяжёлый, одни базы сигнатур около 300 МБ. Включается профилем compose «antivirus».
 */
@Component
public class AntivirusClient {

    private static final int CHUNK = 8192;

    private final String host;
    private final int port;
    private final int timeoutMillis;

    public AntivirusClient(@Value("${appeals.attachments.antivirus.host:}") String host,
                           @Value("${appeals.attachments.antivirus.port:3310}") int port,
                           @Value("${appeals.attachments.antivirus.timeout:PT10S}") Duration timeout) {
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.timeoutMillis = (int) timeout.toMillis();
    }

    public boolean enabled() {
        return !host.isEmpty();
    }

    /** Пусто, если угроз нет; иначе имя найденной угрозы. Если антивирус недоступен, исключение. */
    public Optional<String> scan(InputStream content) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            OutputStream out = socket.getOutputStream();
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[CHUNK];
            int read;
            while ((read = content.read(chunk)) > 0) {
                out.write(ByteBuffer.allocate(4).putInt(read).array());
                out.write(chunk, 0, read);
            }
            // порция нулевой длины: файл закончился
            out.write(new byte[4]);
            out.flush();
            String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
                    .replace("\0", "").trim();
            if (reply.endsWith("OK")) {
                return Optional.empty();
            }
            if (reply.endsWith("FOUND")) {
                return Optional.of(reply.substring(reply.indexOf(':') + 1, reply.length() - "FOUND".length()).trim());
            }
            throw new AntivirusUnavailableException("Антивирус ответил не по протоколу: " + reply, null);
        } catch (IOException exception) {
            throw new AntivirusUnavailableException("Антивирусная проверка недоступна", exception);
        }
    }
}
