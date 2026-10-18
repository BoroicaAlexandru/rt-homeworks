package ssatr.service;

import java.util.concurrent.BlockingQueue;

/**
 * Preia mesajele de un anumit tip ("telemetry", "alarm" sau "status") din coada
 * inbox, le salvează și actualizează dashboard-ul.
 *
 * Formatul mesajelor (câmpuri separate prin ";"):
 *   telemetry:  deviceId;timestamp;temperatura
 *   alarm:      deviceId;timestamp;temperatura;prag
 *   status:     deviceId;timestamp;text
 */
public class MessageProcessor implements Runnable {

    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String message = inbox.take();
                storage.append(kind, message);   // salvare, exact cum a sosit

                try {
                    process(message);
                } catch (RuntimeException e) {
                    // format invalid: nu oprim thread-ul
                    System.out.println("[" + kind + "] mesaj invalid ignorat: " + message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void process(String message) {
        String[] p = message.split(";");
        String deviceId = p[0];

        switch (kind) {
            case "telemetry":
                dashboard.onTelemetry(deviceId, Double.parseDouble(p[2]));
                break;
            case "alarm":
                dashboard.onAlarm(deviceId);
                System.out.println("!!! ALARMA " + deviceId + ": temperatura " + p[2]
                        + " peste pragul " + p[3]);
                break;
            case "status":
                dashboard.onStatus(deviceId);
                System.out.println(">>> STATUS " + deviceId + ": " + p[2]);
                break;
            default:
                System.out.println("Tip de mesaj necunoscut: " + kind);
        }
    }
}
