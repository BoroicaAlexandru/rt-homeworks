package ssatr.device;

import java.util.concurrent.BlockingQueue;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * TASK PERIODIC, cu perioada T = periodMs: fiecare job citește senzorul și
 * publică valoarea pe topic-ul de telemetrie.
 *
 * Dacă temperatura depășește pragul, taskul NU tratează el alarma, ci doar
 * semnalează un eveniment în coada alarmEvents. Alarma este tratată de
 * taskul sporadic.
 */
public class PeriodicTelemetryTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;

    // volatile: sunt citite aici, dar modificate de taskul aperiodic (alt thread)
    private volatile long periodMs = 2000;
    private volatile double threshold = 28.0;

    public PeriodicTelemetryTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                 BlockingQueue<Double> alarmEvents) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        long next = System.nanoTime();   // eliberarea jobului curent pe grilă

        while (!Thread.currentThread().isInterrupted()) {
            // jobul: citire + publicare
            double t = sensor.read();
            String msg = deviceId + ";" + System.currentTimeMillis() + ";"
                    + String.format(Locale.US, "%.2f", t);
            mqtt.publish(Config.telemetryTopic(deviceId), msg);

            // depășire de prag: doar semnalăm taskului sporadic
            if (t > threshold)
                alarmEvents.offer(t);


            // așteptare absolută: următoarea eliberare = precedenta + T
            next += periodMs * 1_000_000L;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public void setPeriodMs(long periodMs) {
        this.periodMs = periodMs;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public long getPeriodMs() {
        return periodMs;
    }

    public double getThreshold() {
        return threshold;
    }
}
