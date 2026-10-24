package ru.mtuci.appeals;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Антивирус для тестов: clamd по протоколу INSTREAM, как настоящий ClamAV. Находит
 * тестовую сигнатуру EICAR, остальное считает чистым. Его можно «выключить»: тогда он
 * закрывает соединение, не ответив.
 */
final class TestAntivirus {

    /** Стандартная безвредная строка, которую все антивирусы считают заражённой. */
    static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    private static volatile boolean available = true;
    private static final ServerSocket SERVER = start();

    private TestAntivirus() {
    }

    static int port() {
        return SERVER.getLocalPort();
    }

    static void available(boolean value) {
        available = value;
    }

    private static ServerSocket start() {
        try {
            ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        answer(socket);
                    } catch (IOException ignored) {
                        // соединение оборвалось: ждём следующего
                    }
                }
            }, "test-clamd");
            thread.setDaemon(true);
            thread.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void answer(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        readCommand(in);
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        int length;
        while ((length = in.readInt()) > 0) {
            content.write(in.readNBytes(length));
        }
        if (!available) {
            return;
        }
        String reply = content.toString(StandardCharsets.ISO_8859_1).contains("EICAR-STANDARD-ANTIVIRUS-TEST-FILE")
                ? "stream: Eicar-Test-Signature FOUND\0" : "stream: OK\0";
        OutputStream out = socket.getOutputStream();
        out.write(reply.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Команда «zINSTREAM» заканчивается нулевым байтом. */
    private static void readCommand(InputStream in) throws IOException {
        while (in.read() > 0) {
            // пропускаем имя команды
        }
    }
}
