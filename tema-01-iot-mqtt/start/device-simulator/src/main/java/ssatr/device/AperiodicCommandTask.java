package ssatr.device;

import java.util.concurrent.BlockingQueue;

import java.util.Locale;

/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 *
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzi care trebuie suportate:
 *
 *   SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
 *   SET_THRESHOLD <valoare> schimbă pragul de alarmă
 *   RESET                   readuce senzorul la valoarea inițială
 *   STATUS                  raportează perioada și pragul curente
 *
 * După executarea fiecărei comenzi, device-ul publică pe Config.statusTopic(deviceId)
 * o confirmare: "deviceId;timestamp;text", de exemplu
 * "device-1;1727600000000;OK SET_INTERVAL 1000".
 */
public class AperiodicCommandTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<String> commands;
    private final PeriodicTelemetryTask periodic;

    public AperiodicCommandTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                BlockingQueue<String> commands, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.commands = commands;
        this.periodic = periodic;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String command = commands.take();   // blochează până sosește o comandă
                String reply = execute(command.trim());
                String msg = deviceId + ";" + System.currentTimeMillis() + ";" + reply;
                mqtt.publish(Config.statusTopic(deviceId), msg);
                System.out.println("[" + deviceId + "] " + command + " -> " + reply);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // orice altă problemă nu are voie să oprească taskul
                String msg = deviceId + ";" + System.currentTimeMillis() + ";ERROR " + e.getMessage();
                mqtt.publish(Config.statusTopic(deviceId), msg);
            }
        }
    }

    private String execute(String command) {
        if (command.isEmpty()) {
            return "ERROR comanda goala";
        }
        String[] parts = command.split("\\s+");
        String name = parts[0].toUpperCase();

        try {
            switch (name) {
                case "SET_INTERVAL": {
                    if (parts.length != 2) return "ERROR lipseste parametrul: SET_INTERVAL <ms>";
                    long ms = Long.parseLong(parts[1]);
                    if (ms <= 0) return "ERROR intervalul trebuie sa fie pozitiv";
                    periodic.setPeriodMs(ms);
                    return "OK SET_INTERVAL " + ms;
                }
                case "SET_THRESHOLD": {
                    if (parts.length != 2) return "ERROR lipseste parametrul: SET_THRESHOLD <valoare>";
                    double th = Double.parseDouble(parts[1]);
                    periodic.setThreshold(th);
                    return "OK SET_THRESHOLD " + String.format(Locale.US, "%.2f", th);
                }
                case "RESET":
                    sensor.reset();
                    return "OK RESET";
                case "STATUS":
                    return "OK STATUS period=" + periodic.getPeriodMs()
                            + " threshold=" + String.format(Locale.US, "%.2f", periodic.getThreshold());
                default:
                    return "ERROR comanda necunoscuta: " + parts[0];
            }
        } catch (NumberFormatException e) {
            return "ERROR parametru invalid: " + parts[1];
        }
    }
}
