package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;
    private final PeriodicTelemetryTask periodicTask;   // sursa pragului curent

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt,
                             BlockingQueue<Double> alarmEvents,
                             PeriodicTelemetryTask periodicTask) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
        this.periodicTask = periodicTask;
    }

    @Override
    public void run() {
        long lastRelease = 0;
        boolean everReleased = false;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                double temperature = alarmEvents.take();   // blochează până apare un eveniment
                long now = System.nanoTime();

                // interval minim între sosiri: respingem evenimentele prea dese
                if (everReleased && (now - lastRelease) < MIN_INTERARRIVAL_MS * 1_000_000L) {
                    System.out.println("[" + deviceId + "] Alarmă respinsă (interval minim "
                            + MIN_INTERARRIVAL_MS + " ms neatins), temperatura="
                            + String.format(Locale.US, "%.2f", temperature));
                    continue;
                }

                lastRelease = now;
                everReleased = true;

                String msg = deviceId + ";" + System.currentTimeMillis() + ";"
                        + String.format(Locale.US, "%.2f", temperature) + ";"
                        + String.format(Locale.US, "%.2f", periodicTask.getThreshold());
                mqtt.publish(Config.alarmTopic(deviceId), msg);
                System.out.println("[" + deviceId + "] Alarmă publicată: " + msg);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}